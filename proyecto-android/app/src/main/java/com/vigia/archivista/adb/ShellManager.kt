package com.vigia.archivista.adb

import android.content.Context

/**
 * Punto único para ejecutar órdenes con privilegios de shell (UID 2000). Hoy usa un proveedor, la depuración inalámbrica
 * de este mismo teléfono (Android 11 o superior). Cualquier función que necesite shell debe comprobar estado() y, si no está
 * conectado, degradar con el aviso «Requiere Modo Avanzado».
 */
object ShellManager {

    enum class Estado { NO_DISPONIBLE, DESCONECTADO, CONECTADO }

    fun estado(): Estado {
        return when {
            !AdbInalambricoShell.disponible() -> Estado.NO_DISPONIBLE
            AdbInalambricoShell.conectado -> Estado.CONECTADO
            else -> Estado.DESCONECTADO
        }
    }

    /** Comprueba de verdad que la conexión responde y devuelve el estado resultante. */
    suspend fun verificar(): Estado {
        if (AdbInalambricoShell.disponible()) AdbInalambricoShell.verificar()
        return estado()
    }

    /**
     * Ejecuta una orden y devuelve lo que imprime. Lanza IllegalStateException si el modo avanzado no está disponible
     * o no se ha conectado nunca. Un fallo suelto de lectura no impide reintentar.
     */
    suspend fun ejecutar(orden: String): String {
        if (!AdbInalambricoShell.disponible()) throw IllegalStateException("Requiere Android 11 o superior")
        if (!AdbInalambricoShell.hayConexion()) throw IllegalStateException("Requiere Modo Avanzado")
        return AdbInalambricoShell.ejecutar(orden)
    }

    suspend fun emparejar(contexto: Context, puerto: Int, codigo: String): Boolean =
        AdbInalambricoShell.emparejar(contexto, puerto, codigo)

    suspend fun conectar(contexto: Context, puertoManual: Int?): Boolean =
        AdbInalambricoShell.conectar(contexto, puertoManual)

    fun olvidar(contexto: Context) {
        AdbInalambricoShell.olvidar(contexto)
    }
}
