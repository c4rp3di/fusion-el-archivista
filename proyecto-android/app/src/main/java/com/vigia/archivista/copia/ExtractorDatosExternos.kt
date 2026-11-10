package com.vigia.archivista.copia

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Datos externos de las apps (Android/data/<paquete> y Android/obb/<paquete>), leídos con el shell porque desde Android 11
 * ninguna app puede entrar en esas carpetas. Cada paquete va en un TAR con rutas «data/<paquete>/…» y «obb/<paquete>/…».
 */
object ExtractorDatosExternos {

    /** Qué paquetes tienen datos: paquete → rutas relativas a /sdcard/Android («data/pkg», «obb/pkg»). */
    suspend fun detectar(paquetes: List<String>): Map<String, List<String>> {
        val res = LinkedHashMap<String, MutableList<String>>()
        val validos = paquetes.filter { AdbArchivos.PAQUETE_VALIDO.matches(it) }
        for (grupo in validos.chunked(20)) {
            val orden = "for p in " + grupo.joinToString(" ") + "; do for t in data obb; do [ -d /sdcard/Android/\$t/\$p ] && echo \$t/\$p; done; done"
            val r = AdbArchivos.ejecutar(orden)
            for (cruda in r.salida.lines()) {
                val l = cruda.trim()
                if (l.startsWith("data/") || l.startsWith("obb/")) {
                    val pkg = l.substringAfter('/')
                    if (AdbArchivos.PAQUETE_VALIDO.matches(pkg)) res.getOrPut(pkg) { ArrayList() }.add(l)
                }
            }
        }
        return res
    }

    /** Crea el TAR con el shell y lo entrega como flujo. Si algo falla devuelve un archivo vacío y deja un aviso. */
    fun capturar(c: Context, paquete: String, rutas: List<String>, aviso: (String) -> Unit): Fuente {
        try {
            val salida = File(AdbArchivos.dirTemporal(c), paquete + "_ext.tar.gz")
            salida.delete()
            val lista = rutas.joinToString(" ") { AdbArchivos.q(it) }
            var r = AdbArchivos.ejecutarBloqueando("tar -czf " + AdbArchivos.q(salida.path) + " -C /sdcard/Android " + lista)
            if (r.codigo != 0 && (!salida.exists() || salida.length() == 0L)) {
                // Algunos tar del sistema no saben comprimir: se reintenta sin comprimir (el .bkp ya va cifrado).
                salida.delete()
                r = AdbArchivos.ejecutarBloqueando("tar -cf " + AdbArchivos.q(salida.path) + " -C /sdcard/Android " + lista)
            }
            if (!salida.exists() || salida.length() == 0L) {
                salida.delete()
                aviso(paquete + ": no se pudieron copiar sus datos externos (" + primeraLinea(r.salida) + ")")
                return AdbArchivos.vacia()
            }
            if (r.codigo != 0) aviso(paquete + ": datos externos copiados con advertencias (" + primeraLinea(r.salida) + ")")
            return Fuente(salida.length(), AdbArchivos.BorrarAlCerrar(salida))
        } catch (e: Exception) {
            aviso(paquete + ": no se pudieron copiar sus datos externos (" + (e.message ?: e.javaClass.simpleName) + ")")
            return AdbArchivos.vacia()
        }
    }

    /**
     * Devuelve null si todo fue bien, o un texto con el motivo. Antes de extraer comprueba que el TAR solo contenga rutas
     * «data/…» y «obb/…» sin «..», para que un archivo manipulado no pueda escribir fuera de Android/data y Android/obb.
     */
    suspend fun restaurar(tar: File): String? {
        val problema = validar(tar, listOf("data/", "obb/"))
        if (problema != null) return problema
        val opcion = if (AdbArchivos.esGzip(tar)) "-xzf " else "-xf "
        val r = AdbArchivos.ejecutar("tar " + opcion + AdbArchivos.q(tar.path) + " -C /sdcard/Android")
        return if (r.codigo == 0) null else primeraLinea(r.salida)
    }

    /** Recorre el TAR (gzip o no) y devuelve un motivo si alguna ruta no es aceptable; null si todo es correcto. */
    fun validar(tar: File, prefijos: List<String>?): String? {
        try {
            var flujo: InputStream = FileInputStream(tar)
            if (AdbArchivos.esGzip(tar)) flujo = GZIPInputStream(flujo)
            flujo.use { entrada ->
                val lector = TarLector(entrada)
                while (true) {
                    val e = lector.siguiente() ?: break
                    var nombre = e.nombre
                    if (nombre.startsWith("./")) nombre = nombre.substring(2)
                    if (nombre.isEmpty() || nombre == ".") continue
                    if (!AdbArchivos.rutaSegura(nombre)) return "el archivo contiene una ruta no permitida: " + nombre
                    if (prefijos != null && prefijos.none { nombre.startsWith(it) }) return "el archivo contiene una ruta fuera de su carpeta: " + nombre
                }
            }
            return null
        } catch (e: IOException) {
            return "el archivo de datos está dañado"
        }
    }

    fun primeraLinea(t: String): String {
        val l = t.lines().firstOrNull { it.isNotBlank() } ?: "sin detalle"
        return if (l.length > 160) l.substring(0, 160) else l
    }
}
