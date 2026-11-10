package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vigia.archivista.R
import com.vigia.archivista.core.Formato
import com.vigia.archivista.seguridad.CifradoFlujo
import com.vigia.archivista.seguridad.DerivarClave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Una app de la copia con sus APK ya extraídos (varios si la app usa «splits»). */
class AppRestaurable(val paquete: String, val etiqueta: String, val apks: List<File>) {
    fun tamano(): Long {
        var t = 0L
        for (f in apks) t += f.length()
        return t
    }
}

/** Un archivo no-APK de la copia: no se extrae, se queda en el contenedor descifrado y se lee desde [desplazamiento]. */
class EntradaIndice(
    val clave: String,
    val tipo: TipoArchivo,
    val etiqueta: String,
    val nombre: String,
    val desplazamiento: Long,
    val tamano: Long
)

/** Copia descifrada: los APK ya extraídos y el índice del resto, que se restaura cuando la persona lo pide. */
class CopiaAbierta(val contenedor: File, val apps: List<AppRestaurable>, val extras: List<EntradaIndice>)

/**
 * Restaura: comprueba la cabecera mágica, descifra con CifradoFlujo hacia un contenedor temporal y lo recorre. Los APK se
 * extraen a una carpeta temporal; lo demás (datos, Xiaomi, archivos personales) se lee del contenedor cuando se necesita.
 * Se leen las copias VBK2 y también las VBK1 antiguas, que solo traían APK.
 */
object ProcesoRestauracion {

    private const val CARPETA = "restauracion"
    private const val CONTENEDOR = "contenedor_descifrado.tmp"

    fun carpetaApks(c: Context): File = File(c.cacheDir, CARPETA)

    /** true si el archivo empieza por la cabecera de El Archivista. No lee más de 29 bytes. */
    fun esCopiaValida(c: Context, uri: Uri): Boolean {
        return try {
            c.contentResolver.openInputStream(uri)?.use { FormatoBkp.esCopiaValida(it) } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun nombreYTamano(c: Context, uri: Uri): Pair<String, Long> {
        var nombre = "copia.bkp"
        var tam = -1L
        try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val iN = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iT = cur.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0 && !cur.isNull(iN)) nombre = cur.getString(iN)
                    if (iT >= 0 && !cur.isNull(iT)) tam = cur.getLong(iT)
                }
            }
        } catch (e: Exception) {
            // Sin nombre ni tamaño la restauración funciona igual, solo sin barra de progreso medida.
        }
        return Pair(nombre, tam)
    }

    /** Borra los APK extraídos, el contenedor descifrado y los temporales. Se llama al salir de la pantalla. */
    fun limpiar(c: Context) {
        carpetaApks(c).deleteRecursively()
        File(c.cacheDir, CONTENEDOR).delete()
        AdbArchivos.limpiar(c)
    }

    /** Lee los bytes de [e] dentro del contenedor. */
    fun abrirRegion(copia: CopiaAbierta, e: EntradaIndice): InputStream {
        val entrada = FileInputStream(copia.contenedor)
        try {
            Tar.saltar(entrada, e.desplazamiento)
        } catch (ex: Exception) {
            entrada.close()
            throw IOException("la copia descifrada está incompleta")
        }
        return BufferedInputStream(Tar.Exacto(entrada, e.tamano, e.etiqueta) { }, 65536)
    }

    /** Copia los bytes de [e] a un archivo (para pasárselo al shell). */
    fun volcarA(copia: CopiaAbierta, e: EntradaIndice, destino: File) {
        destino.parentFile?.mkdirs()
        abrirRegion(copia, e).use { entrada ->
            FileOutputStream(destino).use { salida -> entrada.copyTo(salida, 65536) }
        }
    }

    /**
     * @throws ArchivoNoValido si no es una copia de El Archivista o está incompleta.
     * @throws ContrasenaIncorrecta si la contraseña no abre la copia.
     * @throws ErrorCopia si no hay espacio u otro fallo con mensaje para la persona.
     */
    suspend fun descifrarYExtraer(
        c: Context,
        uri: Uri,
        contrasena: CharArray,
        alProgreso: (Progreso) -> Unit
    ): CopiaAbierta = withContext(Dispatchers.IO) {
        val trabajo = coroutineContext.job
        val contenedor = File(c.cacheDir, CONTENEDOR)
        var clave: ByteArray? = null
        var correcto = false
        try {
            val tamArchivo = nombreYTamano(c, uri).second
            if (tamArchivo > 0 && c.cacheDir.usableSpace < tamArchivo * 2L + 8L * 1024L * 1024L) {
                throw ErrorCopia(c.getString(R.string.error_sin_espacio, Formato.bytes(tamArchivo * 2L)))
            }
            limpiar(c)

            val origen = c.contentResolver.openInputStream(uri) ?: throw IOException("no se pudo abrir el archivo")
            BufferedInputStream(origen, 65536).use { entrada ->
                val cabecera = try {
                    FormatoBkp.leerCabecera(entrada)
                } catch (e: IllegalArgumentException) {
                    throw ArchivoNoValido(c.getString(R.string.error_no_es_copia))
                }

                alProgreso(Progreso(R.string.fase_derivando, "", -1f))
                val k = DerivarClave.derivar(contrasena, cabecera.sal, cabecera.iteraciones)
                clave = k
                trabajo.ensureActive()

                // Descifrar con CifradoFlujo hacia el contenedor temporal.
                var ultimo = 0L
                val leida = EntradaControlada(entrada) { total ->
                    trabajo.ensureActive()
                    if (total - ultimo > 262144L) {
                        ultimo = total
                        val f = if (tamArchivo > 0) total.toFloat() / tamArchivo else -1f
                        alProgreso(Progreso(R.string.fase_descifrando, "", f))
                    }
                }
                try {
                    BufferedOutputStream(FileOutputStream(contenedor), 65536).use { salida ->
                        CifradoFlujo.descifrar(leida, salida, k)
                    }
                } catch (e: CancellationException) {
                    // CancellationException es un IllegalStateException: sin este caso, cancelar pasaría por «contraseña incorrecta».
                    throw e
                } catch (e: IllegalStateException) {
                    throw ContrasenaIncorrecta()
                } catch (e: IllegalArgumentException) {
                    throw ArchivoNoValido(c.getString(R.string.error_copia_incompleta))
                }
            }

            val copia = indexar(c, contenedor, alProgreso, trabajo)
            correcto = true
            copia
        } finally {
            clave?.fill(0)
            if (!correcto) contenedor.delete()
        }
    }

    private fun indexar(c: Context, contenedor: File, alProgreso: (Progreso) -> Unit, trabajo: Job): CopiaAbierta {
        val raiz = carpetaApks(c)
        raiz.mkdirs()
        val porPaquete = LinkedHashMap<String, MutableList<File>>()
        val etiquetas = HashMap<String, String>()
        val extras = ArrayList<EntradaIndice>()
        try {
            val cuenta = EntradaContada(BufferedInputStream(FileInputStream(contenedor), 65536))
            DataInputStream(cuenta).use { d ->
                val magia = d.readInt()
                val v2 = magia == FormatoBkp.MAGIA_INTERNA
                if (!v2 && magia != FormatoBkp.MAGIA_INTERNA_V1) throw ArchivoNoValido(c.getString(R.string.error_no_es_copia))
                val n = d.readInt()
                if (n < 1 || n > FormatoBkp.MAX_ARCHIVOS) throw ArchivoNoValido(c.getString(R.string.error_copia_incompleta))
                val buffer = ByteArray(65536)
                for (i in 0 until n) {
                    trabajo.ensureActive()
                    val e = FormatoBkp.leerEntrada(d, v2)
                    alProgreso(Progreso(R.string.fase_extrayendo, e.etiqueta, i.toFloat() / n))
                    if (e.tipo != TipoArchivo.APK) {
                        // Se deja en el contenedor: solo se anota dónde empieza.
                        if (e.tamano > 0) {
                            extras.add(EntradaIndice(e.clave, e.tipo, e.etiqueta, e.nombre, cuenta.posicion, e.tamano))
                        }
                        Tar.saltar(d, e.tamano)
                        continue
                    }
                    // Los nombres vienen del archivo: se sanean para que nada pueda escribir fuera de la carpeta temporal.
                    val carpeta = File(raiz, sanear(e.clave))
                    carpeta.mkdirs()
                    var base = sanear(File(e.nombre).name)
                    if (!base.endsWith(".apk")) base += ".apk"
                    var destino = File(carpeta, base)
                    var contador = 1
                    while (destino.exists()) {
                        destino = File(carpeta, contador.toString() + "_" + base)
                        contador++
                    }
                    var restante = e.tamano
                    FileOutputStream(destino).use { salida ->
                        while (restante > 0) {
                            trabajo.ensureActive()
                            val leer = minOf(buffer.size.toLong(), restante).toInt()
                            val r = d.read(buffer, 0, leer)
                            if (r < 0) throw EOFException()
                            salida.write(buffer, 0, r)
                            restante -= r
                        }
                    }
                    porPaquete.getOrPut(e.clave) { ArrayList() }.add(destino)
                    etiquetas[e.clave] = e.etiqueta
                }
            }
        } catch (e: EOFException) {
            throw ArchivoNoValido(c.getString(R.string.error_copia_incompleta))
        } catch (e: IllegalArgumentException) {
            throw ArchivoNoValido(c.getString(R.string.error_copia_incompleta))
        }
        val apps = ArrayList<AppRestaurable>()
        for ((pkg, ficheros) in porPaquete) {
            apps.add(AppRestaurable(pkg, etiquetas[pkg] ?: pkg, ficheros))
        }
        return CopiaAbierta(contenedor, apps, extras)
    }

    private fun sanear(texto: String): String {
        val limpio = texto.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        return if (limpio.isEmpty()) "x" else limpio.take(120)
    }
}
