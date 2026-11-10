package com.vigia.archivista.copia

import android.content.Context
import com.vigia.archivista.R
import com.vigia.archivista.adb.ShellManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream

/**
 * Utilidades comunes a todo lo que usa el shell para mover datos. ShellManager devuelve texto, no sirve para bytes: por eso
 * el shell escribe sus resultados en la carpeta de archivos externos de esta app (Android/data/<esta app>/files/tmp), que la
 * app sí puede leer, y la app los lee de ahí. Todo lo temporal se borra al terminar.
 */
object AdbArchivos {
    val PAQUETE_VALIDO = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    class Resultado(val codigo: Int, val salida: String)

    fun conectado(): Boolean = ShellManager.estado() == ShellManager.Estado.CONECTADO

    fun dirTemporal(c: Context): File {
        val base = c.getExternalFilesDir("tmp") ?: throw ErrorCopia(c.getString(R.string.error_sin_almacenamiento))
        base.mkdirs()
        return base
    }

    /** Escapa un texto para ponerlo entre comillas simples en el shell. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Ejecuta una orden y devuelve su código de salida y su salida (error incluido). */
    suspend fun ejecutar(orden: String): Resultado {
        val txt = ShellManager.ejecutar("( " + orden + " ) 2>&1; echo @@RC=\$?")
        val i = txt.lastIndexOf("@@RC=")
        if (i < 0) return Resultado(-1, txt)
        val rc = txt.substring(i + 5).trim().toIntOrNull() ?: -1
        return Resultado(rc, txt.substring(0, i).trim())
    }

    /** Para usarlo desde los flujos de lectura (hilos de E/S, no corrutinas). */
    fun ejecutarBloqueando(orden: String): Resultado = runBlocking { ejecutar(orden) }

    fun esGzip(f: File): Boolean {
        return try {
            FileInputStream(f).use { it.read() == 0x1F && it.read() == 0x8B }
        } catch (e: Exception) {
            false
        }
    }

    /** true si [ruta] es relativa y no sale de su carpeta (sin «..», sin «/» inicial ni comillas raras). */
    fun rutaSegura(ruta: String): Boolean {
        if (ruta.isEmpty() || ruta.startsWith("/") || ruta.contains("\u0000") || ruta.contains("\n")) return false
        for (parte in ruta.split("/")) {
            if (parte == "..") return false
        }
        return true
    }

    /** Borra todo lo temporal de esta app (en el lado de la app: el shell escribió en su propia carpeta). */
    fun limpiar(c: Context) {
        try {
            dirTemporal(c).deleteRecursively()
        } catch (e: Exception) {
            // Sin almacenamiento externo no hay nada que limpiar.
        }
    }

    /** Lee un archivo y lo borra al cerrar el flujo, se haya leído entero o no. */
    class BorrarAlCerrar(private val archivo: File) : FileInputStream(archivo) {
        override fun close() {
            try {
                super.close()
            } finally {
                archivo.delete()
            }
        }
    }

    fun vacia(): Fuente = Fuente(0L, Tar.vacio())
}
