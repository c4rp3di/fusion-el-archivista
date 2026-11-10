package com.vigia.archivista.copia

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Orquesta la app de copias de Xiaomi (com.miui.backup), que sí puede leer los datos privados de las apps: la persona hace
 * la copia en su pantalla y El Archivista recoge los archivos que aparecen. Las rutas de MIUI/HyperOS varían, así que
 * primero se mira /sdcard/MIUI/backup y, si no hay nada nuevo, se busca cualquier «.bak» reciente en /sdcard.
 */
object OrquestadorXiaomi {
    const val PAQUETE = "com.miui.backup"
    private const val RAIZ_MIUI = "/sdcard/MIUI/backup"

    /** Archivo nuevo en el almacenamiento: ruta relativa a /sdcard y tamaño. */
    class Remoto(val clave: String, val tamano: Long)

    @Suppress("DEPRECATION")
    fun instalada(c: Context): Boolean {
        return try {
            c.packageManager.getPackageInfo(PAQUETE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Crea un archivo-marca: todo lo que Xiaomi cree después será «más nuevo» que él. Devuelve su ruta. */
    suspend fun marcar(c: Context): String {
        val marca = File(AdbArchivos.dirTemporal(c), "marca_xiaomi")
        val r = AdbArchivos.ejecutar("touch " + AdbArchivos.q(marca.path))
        if (r.codigo != 0) throw ErrorCopia("No se pudo preparar la detección: " + ExtractorDatosExternos.primeraLinea(r.salida))
        return marca.path
    }

    /** Abre la app de copias de Xiaomi. Devuelve null si se abrió o el motivo si no. */
    suspend fun abrirInterfaz(): String? {
        val directa = AdbArchivos.ejecutar("am start -n " + PAQUETE + "/.LocalBackupActivity")
        if (directa.codigo == 0 && !directa.salida.contains("Error") && !directa.salida.contains("Exception")) return null
        // El nombre de la actividad cambia entre versiones: se abre la app por su icono.
        val porIcono = AdbArchivos.ejecutar("monkey -p " + PAQUETE + " -c android.intent.category.LAUNCHER 1")
        if (porIcono.codigo == 0 && !porIcono.salida.contains("No activities found")) return null
        return ExtractorDatosExternos.primeraLinea(porIcono.salida)
    }

    /** Archivos creados después de la marca. */
    suspend fun buscarNuevos(marca: String): List<Remoto> {
        var lista = buscar(RAIZ_MIUI, marca, "")
        if (lista.isEmpty()) lista = buscar("/sdcard", marca, "-maxdepth 6 -name '*.bak'")
        return lista
    }

    private suspend fun buscar(raiz: String, marca: String, filtro: String): List<Remoto> {
        val orden = "find " + raiz + " " + filtro + " -type f -newer " + AdbArchivos.q(marca) + " -exec stat -c '%s|%n' {} +"
        val r = AdbArchivos.ejecutar(orden)
        val res = ArrayList<Remoto>()
        for (cruda in r.salida.lines()) {
            val l = cruda.trim()
            val barra = l.indexOf('|')
            if (barra <= 0) continue
            val tam = l.substring(0, barra).toLongOrNull() ?: continue
            val ruta = l.substring(barra + 1)
            if (!ruta.startsWith("/sdcard/")) continue
            val clave = ruta.removePrefix("/sdcard/")
            if (clave.startsWith("Android/") || !AdbArchivos.rutaSegura(clave)) continue
            res.add(Remoto(clave, tam))
        }
        return res
    }

    /** Copia el archivo al almacenamiento de la app (lo lee el cifrado) y lo borra al terminar. Si falla, entrega uno vacío. */
    fun capturar(c: Context, remoto: Remoto, indice: Int, aviso: (String) -> Unit): Fuente {
        try {
            val destino = File(AdbArchivos.dirTemporal(c), "xiaomi_" + indice + ".bin")
            destino.delete()
            val r = AdbArchivos.ejecutarBloqueando("cp " + AdbArchivos.q("/sdcard/" + remoto.clave) + " " + AdbArchivos.q(destino.path))
            if (r.codigo != 0 || !destino.exists()) {
                destino.delete()
                aviso(remoto.clave + ": no se pudo copiar (" + ExtractorDatosExternos.primeraLinea(r.salida) + ")")
                return AdbArchivos.vacia()
            }
            if (remoto.clave.endsWith(".bak")) {
                val info = LectorBakXiaomi.inspeccionar(destino)
                if (info == null) aviso(remoto.clave + ": formato .bak no reconocido; se guarda tal cual")
            }
            return Fuente(destino.length(), AdbArchivos.BorrarAlCerrar(destino))
        } catch (e: Exception) {
            aviso(remoto.clave + ": no se pudo copiar (" + (e.message ?: e.javaClass.simpleName) + ")")
            return AdbArchivos.vacia()
        }
    }

    /**
     * Devuelve los archivos a su sitio en /sdcard (las rutas ya vienen de un .bkp autenticado, pero se vuelven a comprobar)
     * y abre la app de Xiaomi para que la persona termine la restauración. null si todo fue bien; si no, el motivo.
     */
    suspend fun restaurar(c: Context, archivos: List<Pair<String, File>>): String? {
        for (par in archivos) {
            val clave = par.first
            if (!AdbArchivos.rutaSegura(clave) || clave.startsWith("Android/")) return "ruta no permitida: " + clave
            val destino = "/sdcard/" + clave
            val dir = destino.substringBeforeLast('/')
            val r = AdbArchivos.ejecutar("mkdir -p " + AdbArchivos.q(dir) + " && cp " + AdbArchivos.q(par.second.path) + " " + AdbArchivos.q(destino))
            if (r.codigo != 0) return clave + ": " + ExtractorDatosExternos.primeraLinea(r.salida)
        }
        return abrirInterfaz()
    }
}
