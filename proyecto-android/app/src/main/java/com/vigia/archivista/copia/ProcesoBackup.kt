package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.vigia.archivista.R
import com.vigia.archivista.core.Formato
import com.vigia.archivista.seguridad.CifradoFlujo
import com.vigia.archivista.seguridad.DerivarClave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class ResultadoBackup(
    val apps: Int,
    val apks: Int,
    val bytesApks: Long,
    val bytesFinal: Long,
    val omitidas: List<String>,
    val porShell: Int,
    val porGestor: Int
)

/**
 * Crea la copia: a) localiza los APK (ShellManager «pm path»), b) los junta en un archivo temporal, c) lo cifra con
 * CifradoFlujo y la clave derivada de la contraseña y d) lo escribe en el destino elegido con el selector de archivos
 * del sistema. Solo se copian los APK: los datos internos de las apps no son accesibles sin root.
 */
object ProcesoBackup {

    private class ArchivoApk(val paquete: String, val etiqueta: String, val fichero: File)

    suspend fun ejecutar(
        c: Context,
        apps: List<AppInfo>,
        contrasena: CharArray,
        destino: Uri,
        alProgreso: (Progreso) -> Unit
    ): ResultadoBackup = withContext(Dispatchers.IO) {
        val trabajo = coroutineContext.job
        val temporal = File(c.cacheDir, "copia_en_curso.tmp")
        var terminado = false
        var clave: ByteArray? = null
        try {
            // a) Localizar los APK de cada app.
            alProgreso(Progreso(R.string.fase_localizando, "", -1f))
            val rutas = FuenteApks.resolver(c, apps.map { it.paquete })
            trabajo.ensureActive()

            val archivos = ArrayList<ArchivoApk>()
            val omitidas = ArrayList<String>()
            var appsOk = 0
            for (app in apps) {
                val lista = rutas.porPaquete[app.paquete]
                val ficheros = lista?.map { File(it) }
                if (ficheros == null || ficheros.isEmpty() || ficheros.any { !it.canRead() }) {
                    omitidas.add(app.etiqueta)
                    continue
                }
                for (f in ficheros) archivos.add(ArchivoApk(app.paquete, app.etiqueta, f))
                appsOk++
            }
            if (archivos.isEmpty()) throw ErrorCopia(c.getString(R.string.error_sin_apks))
            var totalApks = 0L
            for (a in archivos) totalApks += a.fichero.length()
            if (c.cacheDir.usableSpace < totalApks + 8L * 1024L * 1024L) {
                throw ErrorCopia(c.getString(R.string.error_sin_espacio, Formato.bytes(totalApks)))
            }

            // b) Copiar los APK a un archivo temporal.
            var copiados = 0L
            var ultimoAviso = 0L
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporal), 65536)).use { d ->
                d.writeInt(FormatoBkp.MAGIA_INTERNA)
                d.writeInt(archivos.size)
                val buffer = ByteArray(65536)
                for (a in archivos) {
                    val tam = a.fichero.length()
                    d.writeUTF(a.paquete)
                    d.writeUTF(a.etiqueta)
                    d.writeUTF(a.fichero.name)
                    d.writeLong(tam)
                    var restante = tam
                    FileInputStream(a.fichero).use { entrada ->
                        while (restante > 0) {
                            trabajo.ensureActive()
                            val n = entrada.read(buffer, 0, minOf(buffer.size.toLong(), restante).toInt())
                            if (n < 0) throw IOException("el APK de " + a.etiqueta + " cambió mientras se copiaba")
                            d.write(buffer, 0, n)
                            restante -= n
                            copiados += n
                            if (copiados - ultimoAviso > 262144L) {
                                ultimoAviso = copiados
                                alProgreso(Progreso(R.string.fase_copiando, a.etiqueta, copiados.toFloat() / totalApks))
                            }
                        }
                    }
                }
            }

            // c) Cifrar el paquete con CifradoFlujo.
            alProgreso(Progreso(R.string.fase_derivando, "", -1f))
            val sal = DerivarClave.nuevaSal()
            val k = DerivarClave.derivar(contrasena, sal, DerivarClave.ITERACIONES)
            clave = k
            trabajo.ensureActive()

            // d) Guardar el archivo final en el destino elegido.
            val tamPaquete = temporal.length()
            var escritos = 0L
            val salidaSistema = c.contentResolver.openOutputStream(destino, "wt")
                ?: throw IOException("no se pudo abrir el destino")
            val salida = BufferedOutputStream(salidaSistema, 65536)
            var cifradoOk = false
            try {
                FormatoBkp.escribirCabecera(salida, DerivarClave.ITERACIONES, sal)
                val controlada = SalidaControlada(salida) { total ->
                    trabajo.ensureActive()
                    if (total - escritos > 262144L) {
                        escritos = total
                        alProgreso(Progreso(R.string.fase_cifrando, "", total.toFloat() / (tamPaquete + 1L)))
                    }
                }
                BufferedInputStream(FileInputStream(temporal), 65536).use { entrada: InputStream ->
                    CifradoFlujo.cifrar(entrada, controlada, k)
                }
                salida.flush()
                cifradoOk = true
            } finally {
                try {
                    salida.close()
                } catch (e: IOException) {
                    // Un fallo al cerrar solo importa si el cifrado terminó bien: entonces la copia puede estar incompleta.
                    if (cifradoOk) throw e
                }
            }
            val bytesFinal = try {
                c.contentResolver.openFileDescriptor(destino, "r")?.use { it.statSize } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            terminado = true
            ResultadoBackup(appsOk, archivos.size, totalApks, bytesFinal, omitidas, rutas.porShell, rutas.porGestor)
        } finally {
            clave?.fill(0)
            temporal.delete()
            if (!terminado) {
                try {
                    DocumentsContract.deleteDocument(c.contentResolver, destino)
                } catch (e: Exception) {
                    // El documento puede no existir aún o el proveedor no permitir borrarlo.
                }
            }
        }
    }
}
