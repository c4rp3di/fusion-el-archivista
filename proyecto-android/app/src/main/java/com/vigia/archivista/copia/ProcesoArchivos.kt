package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import com.vigia.archivista.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class Carpeta(val uri: Uri, val nombre: String)

class ResultadoArchivos(val carpetas: Int, val archivos: Int, val bytes: Long, val bytesFinal: Long, val omitidos: Int)

/**
 * Copia de archivos personales (fotos, documentos, descargas…): cada carpeta elegida va como un TAR dentro del .bkp
 * (tipo ARCHIVO_PERSONAL). El TAR no se crea en disco: se genera sobre la marcha mientras se cifra, así que una carpeta
 * de decenas de GB no necesita espacio temporal ni memoria.
 */
object ProcesoArchivos {

    suspend fun ejecutar(
        c: Context,
        carpetas: List<Carpeta>,
        contrasena: CharArray,
        destino: Uri,
        alProgreso: (Progreso) -> Unit
    ): ResultadoArchivos = withContext(Dispatchers.IO) {
        val trabajo = coroutineContext.job
        val items = ArrayList<Item>()
        var archivos = 0
        var bytes = 0L
        var omitidos = 0
        for (carpeta in carpetas) {
            alProgreso(Progreso(R.string.fase_explorando, carpeta.nombre, -1f))
            val lista = ArbolSaf.listar(c, carpeta.uri) { n ->
                trabajo.ensureActive()
                alProgreso(Progreso(R.string.fase_explorando, carpeta.nombre + " · " + n, -1f))
            }
            val validos = ArrayList<ArchivoSaf>()
            for (a in lista) {
                if (a.tamano >= 0) validos.add(a) else omitidos++
            }
            if (validos.isEmpty()) continue
            var total = Tar.fin().size.toLong()
            for (a in validos) total += Tar.tamanoEntrada(nombreTar(carpeta, a), a.tamano)
            archivos += validos.size
            bytes += total
            val tamanoTar = total
            items.add(Item(carpeta.nombre, TipoArchivo.ARCHIVO_PERSONAL, carpeta.nombre, nombreSeguro(carpeta.nombre) + ".tar", tamanoTar) {
                Fuente(tamanoTar, flujoTar(c, carpeta, validos, trabajo))
            })
        }
        if (items.isEmpty()) throw ErrorCopia(c.getString(R.string.error_sin_archivos))
        val tamFinal = Empaquetador.cifrarA(c, items, contrasena, destino, alProgreso)
        ResultadoArchivos(items.size, archivos, bytes, tamFinal, omitidos)
    }

    private fun nombreTar(carpeta: Carpeta, a: ArchivoSaf): String = nombreSeguro(carpeta.nombre) + "/" + a.ruta

    private fun nombreSeguro(n: String): String {
        val limpio = n.replace("/", "_").trim()
        return if (limpio.isEmpty() || limpio == "." || limpio == "..") "carpeta" else limpio
    }

    /** TAR generado archivo a archivo: cabecera, datos exactos y relleno; al final los dos bloques vacíos. */
    private fun flujoTar(c: Context, carpeta: Carpeta, archivos: List<ArchivoSaf>, trabajo: Job): InputStream {
        val partes = ArrayList<() -> InputStream>()
        for (a in archivos) {
            val nombre = nombreTar(carpeta, a)
            partes.add { Tar.bytes(Tar.cabecera(nombre, a.tamano, a.modificado / 1000L)) }
            partes.add {
                trabajo.ensureActive()
                val origen = c.contentResolver.openInputStream(a.uri) ?: throw IOException("no se pudo leer " + a.ruta)
                Tar.Exacto(origen, a.tamano, a.ruta) { trabajo.ensureActive() }
            }
            val relleno = Tar.relleno(a.tamano)
            if (relleno > 0) partes.add { Tar.bytes(ByteArray(relleno)) }
        }
        partes.add { Tar.bytes(Tar.fin()) }
        return Tar.unir(partes)
    }
}
