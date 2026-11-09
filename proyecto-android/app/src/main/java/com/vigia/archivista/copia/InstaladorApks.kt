package com.vigia.archivista.copia

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Instala los APK extraídos. Una app de un solo APK se instala con el instalador del sistema mediante un Intent
 * ACTION_VIEW con el tipo MIME de APK. Una app con «splits» (varios APK) no se puede instalar así —el instalador
 * solo recibiría uno—, por lo que se instalan todos juntos en una sesión de PackageInstaller; el sistema pide la
 * confirmación igualmente.
 */
object InstaladorApks {
    const val MIME_APK = "application/vnd.android.package-archive"
    const val ACCION_RESULTADO = "com.vigia.archivista.RESULTADO_INSTALACION"

    fun intentVer(c: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".proveedor", apk)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, MIME_APK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** Escribe todos los APK en una sesión y la confirma. El resultado llega por broadcast con [ACCION_RESULTADO]. */
    fun instalarConSesion(c: Context, apks: List<File>) {
        val instalador = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = instalador.createSession(params)
        try {
            instalador.openSession(id).use { sesion ->
                for (apk in apks) {
                    FileInputStream(apk).use { entrada ->
                        sesion.openWrite(apk.name, 0, apk.length()).use { salida ->
                            entrada.copyTo(salida, 65536)
                            sesion.fsync(salida)
                        }
                    }
                }
                val intent = Intent(ACCION_RESULTADO)
                intent.setPackage(c.packageName)
                var banderas = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) banderas = banderas or PendingIntent.FLAG_MUTABLE
                val pendiente = PendingIntent.getBroadcast(c, id, intent, banderas)
                sesion.commit(pendiente.intentSender)
            }
        } catch (e: Exception) {
            try {
                instalador.abandonSession(id)
            } catch (e2: Exception) {
                // La sesión ya estaba cerrada.
            }
            throw e
        }
    }

    /**
     * Marca de la versión instalada: la fecha de la última actualización, o -1 si no está instalada. Si cambia tras el
     * instalador, la instalación (o actualización) se hizo; si no, la persona canceló.
     */
    @Suppress("DEPRECATION")
    fun huella(c: Context, paquete: String): Long {
        return try {
            c.packageManager.getPackageInfo(paquete, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }
    }
}
