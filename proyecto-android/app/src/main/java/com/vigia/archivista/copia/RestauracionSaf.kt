package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import java.io.IOException

class ResultadoExtraccion(val archivos: Int, val bytes: Long, val omitidos: Int)

/** Vuelca un archivo personal (un TAR dentro de la copia) en la carpeta que la persona elija con el selector del sistema. */
object RestauracionSaf {

    /** [alAvanzar] recibe los bytes escritos hasta ahora y puede lanzar una excepción para cancelar. */
    fun extraer(c: Context, copia: CopiaAbierta, e: EntradaIndice, destino: Uri, alAvanzar: (Long) -> Unit): ResultadoExtraccion {
        val escritor = ArbolSaf.Escritor(c, destino)
        var archivos = 0
        var bytes = 0L
        var omitidos = 0
        ProcesoRestauracion.abrirRegion(copia, e).use { entrada ->
            val lector = TarLector(entrada)
            while (true) {
                val t = lector.siguiente() ?: break
                if (!t.esArchivo) continue
                var ruta = t.nombre
                if (ruta.startsWith("./")) ruta = ruta.substring(2)
                if (!AdbArchivos.rutaSegura(ruta)) {
                    omitidos++
                    continue
                }
                val doc = escritor.crearArchivo(ruta)
                val salida = c.contentResolver.openOutputStream(doc, "wt") ?: throw IOException("no se pudo escribir " + ruta)
                salida.use { s ->
                    lector.copiarA(s) { n ->
                        bytes += n
                        alAvanzar(bytes)
                    }
                }
                archivos++
            }
        }
        return ResultadoExtraccion(archivos, bytes, omitidos)
    }
}
