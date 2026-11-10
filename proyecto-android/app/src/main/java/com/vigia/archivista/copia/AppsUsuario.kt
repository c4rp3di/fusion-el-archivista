package com.vigia.archivista.copia

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/**
 * [permiteBackup]: la app admite la copia nativa de Android (ALLOW_BACKUP). [depurable]: se puede usar «run-as» con ella
 * (solo apps compiladas en modo debug).
 */
class AppInfo(
    val paquete: String,
    val etiqueta: String,
    val icono: Drawable,
    val tamano: Long,
    val permiteBackup: Boolean = false,
    val depurable: Boolean = false
)

/**
 * Lista de apps de usuario: las del sistema (incluidas las preinstaladas actualizadas) se ocultan. ALLOW_BACKUP y
 * «depurable» se leen de los indicadores de la app, que es lo mismo que mostraría «cmd package dump» pero al instante
 * y sin gastar una llamada de shell por app.
 */
object AppsUsuario {

    @Suppress("DEPRECATION")
    fun cargar(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val todas: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }
        val res = ArrayList<AppInfo>()
        for (ai in todas) {
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (ai.packageName == c.packageName) continue
            var tam = File(ai.sourceDir).length()
            val divididos = ai.splitSourceDirs
            if (divididos != null) {
                for (s in divididos) tam += File(s).length()
            }
            val permite = (ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
            val depurable = (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            res.add(AppInfo(ai.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm), tam, permite, depurable))
        }
        res.sortBy { it.etiqueta.lowercase() }
        return res
    }
}
