package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import com.vigia.archivista.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext

/** Qué más se incluye además de los APK. [xiaomi] son los archivos que la app de Xiaomi acaba de crear. */
class OpcionesBackup(val externos: Boolean, val internos: Boolean, val xiaomi: List<OrquestadorXiaomi.Remoto>)

class ResultadoBackup(
    val apps: Int,
    val apks: Int,
    val bytesApks: Long,
    val bytesFinal: Long,
    val omitidas: List<String>,
    val porShell: Int,
    val porGestor: Int,
    val externos: Int,
    val internos: Int,
    val xiaomi: Int,
    val avisos: List<String>
)

/**
 * Copia de apps: a) localiza los APK (ShellManager «pm path»), b) los suma a lo que haya pedido la persona (datos externos,
 * datos internos con run-as, archivos de Xiaomi), c) lo cifra con CifradoFlujo y la contraseña y d) lo escribe en el
 * destino elegido con el selector de archivos del sistema.
 */
object ProcesoBackup {

    suspend fun ejecutar(
        c: Context,
        apps: List<AppInfo>,
        contrasena: CharArray,
        destino: Uri,
        opciones: OpcionesBackup,
        alProgreso: (Progreso) -> Unit
    ): ResultadoBackup = withContext(Dispatchers.IO) {
        val trabajo = coroutineContext.job
        val avisos = ArrayList<String>()
        val aviso: (String) -> Unit = { avisos.add(it) }
        var usoShellParaDatos = false
        try {
            // a) Localizar los APK de cada app.
            alProgreso(Progreso(R.string.fase_localizando, "", -1f))
            val rutas = FuenteApks.resolver(c, apps.map { it.paquete })
            trabajo.ensureActive()

            val items = ArrayList<Item>()
            val omitidas = ArrayList<String>()
            val aceptadas = ArrayList<AppInfo>()
            var totalApks = 0L
            var nApks = 0
            for (app in apps) {
                val lista = rutas.porPaquete[app.paquete]
                val ficheros = lista?.map { File(it) }
                if (ficheros == null || ficheros.isEmpty() || ficheros.any { !it.canRead() }) {
                    omitidas.add(app.etiqueta)
                    continue
                }
                aceptadas.add(app)
                for (f in ficheros) {
                    val tam = f.length()
                    totalApks += tam
                    nApks++
                    items.add(Item(app.paquete, TipoArchivo.APK, app.etiqueta, f.name, tam) { Fuente(f.length(), FileInputStream(f)) })
                }
            }
            if (items.isEmpty()) throw ErrorCopia(c.getString(R.string.error_sin_apks))

            // b) Datos adicionales, solo con el Modo Avanzado.
            var nExternos = 0
            var nInternos = 0
            var nXiaomi = 0
            val quiereShell = opciones.externos || opciones.internos || opciones.xiaomi.isNotEmpty()
            if (quiereShell && !AdbArchivos.conectado()) {
                avisos.add(c.getString(R.string.aviso_sin_modo_avanzado))
            } else if (quiereShell) {
                usoShellParaDatos = true
                if (opciones.externos) {
                    alProgreso(Progreso(R.string.fase_buscando_datos, "", -1f))
                    val existentes = try {
                        ExtractorDatosExternos.detectar(aceptadas.map { it.paquete })
                    } catch (e: Exception) {
                        avisos.add(c.getString(R.string.aviso_datos_externos_fallo, e.message ?: e.javaClass.simpleName))
                        emptyMap()
                    }
                    for (app in aceptadas) {
                        val rutasExt = existentes[app.paquete] ?: continue
                        items.add(Item(app.paquete, TipoArchivo.DATOS_EXTERNOS, app.etiqueta, app.paquete + "_ext.tar.gz", 0L) {
                            val f = ExtractorDatosExternos.capturar(c, app.paquete, rutasExt, aviso)
                            if (f.tamano > 0) nExternos++
                            f
                        })
                    }
                }
                if (opciones.internos) {
                    var noDepurables = 0
                    for (app in aceptadas) {
                        if (!app.depurable) {
                            noDepurables++
                            continue
                        }
                        items.add(Item(app.paquete, TipoArchivo.DATOS_INTERNOS, app.etiqueta, app.paquete + "_data.tgz", 0L) {
                            val f = ExtractorRunAs.capturar(c, app.paquete, aviso)
                            if (f.tamano > 0) nInternos++
                            f
                        })
                    }
                    if (noDepurables > 0) avisos.add(c.getString(R.string.aviso_no_depurables, noDepurables))
                }
                for (i in opciones.xiaomi.indices) {
                    val remoto = opciones.xiaomi[i]
                    items.add(Item(remoto.clave, TipoArchivo.BAK_XIAOMI, remoto.clave.substringAfterLast('/'), remoto.clave.substringAfterLast('/'), remoto.tamano) {
                        val f = OrquestadorXiaomi.capturar(c, remoto, i, aviso)
                        if (f.tamano > 0) nXiaomi++
                        f
                    })
                }
            }

            // c) y d) Cifrar y guardar.
            val tamFinal = Empaquetador.cifrarA(c, items, contrasena, destino, alProgreso)
            ResultadoBackup(
                aceptadas.size, nApks, totalApks, tamFinal, omitidas, rutas.porShell, rutas.porGestor,
                nExternos, nInternos, nXiaomi, avisos
            )
        } finally {
            if (usoShellParaDatos) AdbArchivos.limpiar(c)
        }
    }
}
