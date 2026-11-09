package com.vigia.archivista.copia

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.vigia.archivista.adb.ShellManager

/**
 * Localiza los APK de cada app. Con el Modo Avanzado conectado se pregunta al shell con «pm path» (varias apps por
 * llamada, porque cada orden de ShellManager tarda al menos un segundo); si no está conectado, o el shell falla,
 * se usa el gestor de paquetes de Android, que devuelve las mismas rutas. Una app con «splits» devuelve varias.
 */
object FuenteApks {

    class Rutas(val porPaquete: Map<String, List<String>>, val porShell: Int, val porGestor: Int)

    private val PAQUETE_VALIDO = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private const val GRUPO = 15

    suspend fun resolver(c: Context, paquetes: List<String>): Rutas {
        val res = LinkedHashMap<String, List<String>>()
        var porShell = 0
        if (ShellManager.estado() == ShellManager.Estado.CONECTADO) {
            val validos = paquetes.filter { PAQUETE_VALIDO.matches(it) }
            for (grupo in validos.chunked(GRUPO)) {
                try {
                    val orden = "for p in " + grupo.joinToString(" ") + "; do echo @@\$p; pm path \$p; done"
                    val encontrados = interpretar(ShellManager.ejecutar(orden))
                    for (p in grupo) {
                        val rutas = encontrados[p]
                        if (rutas != null && rutas.isNotEmpty()) {
                            res[p] = rutas
                            porShell++
                        }
                    }
                } catch (e: Exception) {
                    // Este grupo se resuelve abajo con el gestor de paquetes.
                }
            }
        }
        var porGestor = 0
        for (p in paquetes) {
            if (res.containsKey(p)) continue
            val rutas = rutasDelGestor(c, p)
            if (rutas.isNotEmpty()) {
                res[p] = rutas
                porGestor++
            }
        }
        return Rutas(res, porShell, porGestor)
    }

    /** La salida es «@@paquete» seguido de una o más líneas «package:/data/app/…/base.apk». */
    private fun interpretar(salida: String): Map<String, List<String>> {
        val res = LinkedHashMap<String, MutableList<String>>()
        var actual: String? = null
        for (cruda in salida.lines()) {
            val linea = cruda.trim()
            if (linea.startsWith("@@")) {
                actual = linea.substring(2)
                res[actual] = ArrayList()
            } else if (linea.startsWith("package:") && actual != null) {
                val ruta = linea.substring(8).trim()
                if (ruta.endsWith(".apk")) res[actual]?.add(ruta)
            }
        }
        return res
    }

    @Suppress("DEPRECATION")
    private fun rutasDelGestor(c: Context, paquete: String): List<String> {
        return try {
            val pm = c.packageManager
            val ai = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(paquete, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                pm.getApplicationInfo(paquete, 0)
            }
            val lista = ArrayList<String>()
            lista.add(ai.sourceDir)
            val divididos = ai.splitSourceDirs
            if (divididos != null) lista.addAll(divididos)
            lista
        } catch (e: Exception) {
            emptyList()
        }
    }
}
