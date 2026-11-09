package com.vigia.archivista.copia

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

class AppInfo(val paquete: String, val etiqueta: String, val icono: Drawable, val tamano: Long)

/** Lista de apps de usuario: las del sistema (incluidas las preinstaladas actualizadas) se ocultan. */
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
            res.add(AppInfo(ai.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm), tam))
        }
        res.sortBy { it.etiqueta.lowercase() }
        return res
    }
}
