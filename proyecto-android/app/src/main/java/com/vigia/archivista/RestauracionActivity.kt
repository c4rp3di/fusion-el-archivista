package com.vigia.archivista

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.archivista.copia.AdbArchivos
import com.vigia.archivista.copia.AppRestaurable
import com.vigia.archivista.copia.ArchivoNoValido
import com.vigia.archivista.copia.ContrasenaIncorrecta
import com.vigia.archivista.copia.CopiaAbierta
import com.vigia.archivista.copia.EntradaIndice
import com.vigia.archivista.copia.ExtractorDatosExternos
import com.vigia.archivista.copia.ExtractorRunAs
import com.vigia.archivista.copia.OrquestadorXiaomi
import com.vigia.archivista.copia.RestauracionSaf
import com.vigia.archivista.copia.TipoArchivo
import com.vigia.archivista.copia.ErrorCopia
import com.vigia.archivista.copia.InstaladorApks
import com.vigia.archivista.copia.ProcesoRestauracion
import com.vigia.archivista.copia.Progreso
import com.vigia.archivista.core.ActividadBase
import com.vigia.archivista.core.Formato
import com.vigia.archivista.core.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pantalla de restauración: elegir .bkp, contraseña, descifrar, extraer los APK e instalarlos con el instalador del sistema. */
class RestauracionActivity : ActividadBase() {

    private lateinit var textoArchivo: TextView
    private lateinit var cardClave: View
    private lateinit var etContrasena: EditText
    private lateinit var btnElegir: MaterialButton
    private lateinit var btnDescifrar: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private lateinit var btnInstalarTodas: MaterialButton
    private lateinit var textoFase: TextView
    private lateinit var barra: ProgressBar
    private lateinit var textoResultado: TextView
    private lateinit var tituloApps: TextView
    private lateinit var listaApps: LinearLayout
    private lateinit var nota: TextView
    private lateinit var tituloExtras: TextView
    private lateinit var listaExtras: LinearLayout

    private var archivo: Uri? = null
    private var apps: List<AppRestaurable> = emptyList()
    private val estados = HashMap<String, TextView>()
    private val instaladas = HashSet<String>()
    private val cola = ArrayList<AppRestaurable>()
    private var instalando: AppRestaurable? = null
    private var huellaAntes = -1L
    private var trabajo: Job? = null
    private var copia: CopiaAbierta? = null
    private var ocupadoExtra = false
    private var extraPendiente: EntradaIndice? = null
    private var estadoPendiente: TextView? = null

    private val lanzadorElegir = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) archivoElegido(uri)
    }

    /** Carpeta de destino de los archivos personales. */
    private val lanzadorDestino = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val entrada = extraPendiente
        val estado = estadoPendiente
        if (uri != null && entrada != null && estado != null) restaurarPersonal(entrada, estado, uri)
    }

    /** Se llama cuando el instalador del sistema (ACTION_VIEW) se cierra, se haya instalado o no. */
    private val lanzadorInstalar = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val app = instalando
        if (app != null) {
            val ahora = InstaladorApks.huella(this, app.paquete)
            terminarApp(app, ahora != -1L && ahora != huellaAntes)
        }
    }

    /** Resultado de las instalaciones por sesión (apps con varios APK). */
    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(contexto: Context, intent: Intent) {
            val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (estado == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmar = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmar != null) {
                    try {
                        startActivity(confirmar)
                    } catch (e: Exception) {
                        val app = instalando
                        if (app != null) terminarApp(app, false)
                    }
                }
                return
            }
            val app = instalando
            if (app != null) terminarApp(app, estado == PackageInstaller.STATUS_SUCCESS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restauracion)
        prepararPantalla(R.string.ayuda_restaurar_titulo, R.string.ayuda_restaurar)

        textoArchivo = findViewById(R.id.textoArchivo)
        cardClave = findViewById(R.id.cardClave)
        etContrasena = findViewById(R.id.etContrasenaCopia)
        btnElegir = findViewById(R.id.btnElegirArchivo)
        btnDescifrar = findViewById(R.id.btnDescifrar)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnInstalarTodas = findViewById(R.id.btnInstalarTodas)
        textoFase = findViewById(R.id.textoFase)
        barra = findViewById(R.id.barra)
        textoResultado = findViewById(R.id.textoResultado)
        tituloApps = findViewById(R.id.tituloApps)
        listaApps = findViewById(R.id.listaRestaurables)
        nota = findViewById(R.id.notaRestaurar)
        tituloExtras = findViewById(R.id.tituloExtras)
        listaExtras = findViewById(R.id.listaExtras)

        btnElegir.setOnClickListener { lanzadorElegir.launch(arrayOf("*/*")) }
        btnDescifrar.setOnClickListener { descifrar() }
        btnCancelar.setOnClickListener { trabajo?.cancel() }
        btnInstalarTodas.setOnClickListener {
            val pendientes = ArrayList<AppRestaurable>()
            for (a in apps) {
                if (!instaladas.contains(a.paquete)) pendientes.add(a)
            }
            iniciarCola(pendientes)
        }

        ContextCompat.registerReceiver(
            this, receptor, IntentFilter(InstaladorApks.ACCION_RESULTADO), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receptor)
        } catch (e: IllegalArgumentException) {
            // Ya estaba desregistrado.
        }
        // Los APK extraídos solo se borran al salir de verdad, no al girar la pantalla ni mientras el instalador los lee.
        if (isFinishing) ProcesoRestauracion.limpiar(applicationContext)
        super.onDestroy()
    }

    private fun archivoElegido(uri: Uri) {
        lifecycleScope.launch {
            val valida = withContext(Dispatchers.IO) { ProcesoRestauracion.esCopiaValida(applicationContext, uri) }
            val info = withContext(Dispatchers.IO) { ProcesoRestauracion.nombreYTamano(applicationContext, uri) }
            limpiarResultados()
            textoArchivo.text = getString(R.string.archivo_elegido, info.first + " (" + Formato.bytes(maxOf(info.second, 0L)) + ")")
            textoArchivo.visibility = View.VISIBLE
            if (valida) {
                archivo = uri
                cardClave.visibility = View.VISIBLE
            } else {
                archivo = null
                cardClave.visibility = View.GONE
                mostrarResultado(getString(R.string.error_no_es_copia))
            }
        }
    }

    private fun descifrar() {
        val uri = archivo ?: return
        if (trabajo != null) return
        val t = etContrasena.text
        if (t.isEmpty()) {
            aviso(R.string.err_contrasena_corta)
            return
        }
        val clave = CharArray(t.length)
        t.getChars(0, t.length, clave, 0)
        limpiarResultados()
        ocupado(true)
        trabajo = lifecycleScope.launch {
            try {
                val abierta = ProcesoRestauracion.descifrarYExtraer(applicationContext, uri, clave) { p ->
                    runOnUiThread { pintarProgreso(p) }
                }
                copia = abierta
                etContrasena.setText("")
                cardClave.visibility = View.GONE
                mostrarApps(abierta.apps)
                mostrarExtras(abierta.extras)
            } catch (e: CancellationException) {
                mostrarResultado(getString(R.string.copia_cancelada))
                throw e
            } catch (e: ContrasenaIncorrecta) {
                mostrarResultado(getString(R.string.error_contrasena))
            } catch (e: ArchivoNoValido) {
                mostrarResultado(e.message ?: getString(R.string.error_no_es_copia))
            } catch (e: ErrorCopia) {
                mostrarResultado(e.message ?: "")
            } catch (e: Exception) {
                mostrarResultado(getString(R.string.error_generico, e.message ?: e.javaClass.simpleName))
            } finally {
                clave.fill(0.toChar())
                trabajo = null
                ocupado(false)
            }
        }
    }

    private fun mostrarApps(lista: List<AppRestaurable>) {
        apps = lista
        estados.clear()
        instaladas.clear()
        listaApps.removeAllViews()
        if (lista.isEmpty()) return
        tituloApps.text = getString(R.string.restaurar_apps_titulo, lista.size)
        tituloApps.visibility = View.VISIBLE
        btnInstalarTodas.visibility = View.VISIBLE
        nota.visibility = View.VISIBLE
        for (app in lista) {
            val par = Ui.tarjetaConColumna(this)
            val columna = par.second
            columna.addView(Ui.titulo(this, app.etiqueta))
            var detalle = app.paquete + " · " + Formato.bytes(app.tamano())
            if (app.apks.size > 1) detalle += " · " + getString(R.string.app_varios_apk, app.apks.size)
            columna.addView(Ui.texto(this, detalle, 12f))
            val estado = Ui.texto(this, getString(R.string.estado_pendiente), 13f, true)
            columna.addView(estado)
            columna.addView(Ui.botonSecundario(this, getString(R.string.btn_instalar)) { iniciarCola(listOf(app)) })
            estados[app.paquete] = estado
            listaApps.addView(par.first)
        }
    }

    /** Pone en cola las apps y lanza la primera; cada una pide confirmación al sistema. */
    private fun iniciarCola(lista: List<AppRestaurable>) {
        if (lista.isEmpty()) return
        if (instalando != null || cola.isNotEmpty()) {
            aviso(R.string.restaurar_ocupado)
            return
        }
        if (!puedeInstalar()) return
        cola.addAll(lista)
        siguiente()
    }

    private fun siguiente() {
        if (cola.isEmpty()) {
            instalando = null
            return
        }
        val app = cola.removeAt(0)
        instalando = app
        huellaAntes = InstaladorApks.huella(this, app.paquete)
        estados[app.paquete]?.setText(R.string.estado_instalando)
        try {
            if (app.apks.size == 1) {
                // ACTION_VIEW con el tipo MIME de APK: el instalador del sistema pide la confirmación.
                lanzadorInstalar.launch(InstaladorApks.intentVer(this, app.apks[0]))
            } else {
                InstaladorApks.instalarConSesion(this, app.apks)
            }
        } catch (e: Exception) {
            terminarApp(app, false)
        }
    }

    private fun terminarApp(app: AppRestaurable, correcto: Boolean) {
        if (instalando !== app) return
        if (correcto) instaladas.add(app.paquete)
        estados[app.paquete]?.setText(if (correcto) R.string.estado_instalada else R.string.estado_no_instalada)
        instalando = null
        siguiente()
    }

    private fun puedeInstalar(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permiso_instalar_titulo)
                .setMessage(R.string.permiso_instalar_texto)
                .setPositiveButton(R.string.btn_ajustes) { _, _ -> abrirAjustesInstalacion() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return false
        }
        return true
    }

    private fun abrirAjustesInstalacion() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
        } catch (e: Exception) {
            aviso(R.string.no_se_pudo_abrir)
        }
    }


    // ---- Datos adicionales: externos, internos, Xiaomi y archivos personales ----

    private fun mostrarExtras(extras: List<EntradaIndice>) {
        listaExtras.removeAllViews()
        if (extras.isEmpty()) return
        tituloExtras.visibility = View.VISIBLE
        nota.text = (if (apps.isNotEmpty()) getString(R.string.restaurar_nota) + "\n\n" else "") + getString(R.string.restaurar_nota_extras)
        nota.visibility = View.VISIBLE
        val xiaomi = ArrayList<EntradaIndice>()
        for (e in extras) {
            if (e.tipo == TipoArchivo.BAK_XIAOMI) {
                xiaomi.add(e)
                continue
            }
            val par = Ui.tarjetaConColumna(this)
            val columna = par.second
            val tam = Formato.bytes(e.tamano)
            val titulo = when (e.tipo) {
                TipoArchivo.DATOS_EXTERNOS -> getString(R.string.extra_externos, tam)
                TipoArchivo.DATOS_INTERNOS -> getString(R.string.extra_internos, tam)
                else -> getString(R.string.extra_personal, tam)
            }
            columna.addView(Ui.titulo(this, e.etiqueta))
            columna.addView(Ui.texto(this, titulo, 12f))
            val estado = Ui.texto(this, "", 13f, true)
            columna.addView(estado)
            when (e.tipo) {
                TipoArchivo.DATOS_EXTERNOS -> columna.addView(Ui.botonSecundario(this, getString(R.string.btn_restaurar_extra)) { restaurarExternos(e, estado) })
                TipoArchivo.DATOS_INTERNOS -> columna.addView(Ui.botonSecundario(this, getString(R.string.btn_restaurar_extra)) { restaurarInternos(e, estado) })
                else -> columna.addView(Ui.botonSecundario(this, getString(R.string.btn_restaurar_personal)) {
                    if (!ocupadoExtra) {
                        extraPendiente = e
                        estadoPendiente = estado
                        lanzadorDestino.launch(null)
                    }
                })
            }
            listaExtras.addView(par.first)
        }
        if (xiaomi.isNotEmpty()) {
            var total = 0L
            for (e in xiaomi) total += e.tamano
            val par = Ui.tarjetaConColumna(this)
            val columna = par.second
            columna.addView(Ui.titulo(this, "Xiaomi"))
            columna.addView(Ui.texto(this, getString(R.string.extra_xiaomi, xiaomi.size, Formato.bytes(total)), 12f))
            val estado = Ui.texto(this, "", 13f, true)
            columna.addView(estado)
            columna.addView(Ui.botonSecundario(this, getString(R.string.btn_restaurar_xiaomi)) { restaurarXiaomi(xiaomi, estado) })
            listaExtras.addView(par.first)
        }
    }

    /**
     * Ejecuta una restauración de datos adicionales fuera del hilo principal. El bloque devuelve (éxito, texto); un fallo
     * inesperado se muestra en la propia tarjeta. Solo se hace una a la vez.
     */
    private fun ejecutarExtra(estado: TextView, exigeShell: Boolean, bloque: suspend (CopiaAbierta) -> Pair<Boolean, String>) {
        val abierta = copia ?: return
        if (ocupadoExtra) {
            aviso(R.string.restaurar_ocupado)
            return
        }
        if (exigeShell && !AdbArchivos.conectado()) {
            estado.setText(R.string.err_requiere_modo)
            return
        }
        ocupadoExtra = true
        estado.setText(R.string.estado_restaurando)
        lifecycleScope.launch {
            var correcto = false
            var texto = ""
            try {
                val r = withContext(Dispatchers.IO) { bloque(abierta) }
                correcto = r.first
                texto = r.second
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                texto = e.message ?: e.javaClass.simpleName
            } finally {
                ocupadoExtra = false
            }
            estado.text = if (correcto) texto else getString(R.string.estado_fallo, texto)
        }
    }

    private fun restaurarExternos(e: EntradaIndice, estado: TextView) {
        ejecutarExtra(estado, true) { abierta ->
            val tmp = File(AdbArchivos.dirTemporal(applicationContext), e.clave + "_ext.tar")
            try {
                ProcesoRestauracion.volcarA(abierta, e, tmp)
                val fallo = ExtractorDatosExternos.restaurar(tmp)
                if (fallo == null) Pair(true, getString(R.string.estado_restaurado)) else Pair(false, fallo)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun restaurarInternos(e: EntradaIndice, estado: TextView) {
        ejecutarExtra(estado, true) { abierta ->
            if (InstaladorApks.huella(applicationContext, e.clave) == -1L) {
                Pair(false, getString(R.string.err_app_no_instalada))
            } else {
                val tmp = File(AdbArchivos.dirTemporal(applicationContext), e.clave + "_data.tgz")
                try {
                    ProcesoRestauracion.volcarA(abierta, e, tmp)
                    val fallo = ExtractorRunAs.restaurar(e.clave, tmp)
                    if (fallo == null) Pair(true, getString(R.string.estado_restaurado)) else Pair(false, fallo)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun restaurarXiaomi(lista: List<EntradaIndice>, estado: TextView) {
        ejecutarExtra(estado, true) { abierta ->
            val temporales = ArrayList<File>()
            try {
                val pares = ArrayList<Pair<String, File>>()
                for (i in lista.indices) {
                    val f = File(AdbArchivos.dirTemporal(applicationContext), "xiaomi_restaurar_" + i + ".bin")
                    ProcesoRestauracion.volcarA(abierta, lista[i], f)
                    temporales.add(f)
                    pares.add(Pair(lista[i].clave, f))
                }
                val fallo = OrquestadorXiaomi.restaurar(applicationContext, pares)
                if (fallo == null) Pair(true, getString(R.string.restaurado_xiaomi)) else Pair(false, fallo)
            } finally {
                for (f in temporales) f.delete()
            }
        }
    }

    private fun restaurarPersonal(e: EntradaIndice, estado: TextView, destino: Uri) {
        ejecutarExtra(estado, false) { abierta ->
            var ultimo = 0L
            val r = RestauracionSaf.extraer(applicationContext, abierta, e, destino) { bytes ->
                if (bytes - ultimo > 4L * 1024L * 1024L) {
                    ultimo = bytes
                    runOnUiThread { estado.text = getString(R.string.estado_restaurando_bytes, Formato.bytes(bytes)) }
                }
            }
            Pair(true, getString(R.string.restaurado_personal, r.archivos, Formato.bytes(r.bytes)))
        }
    }

    private fun limpiarResultados() {
        textoResultado.visibility = View.GONE
        tituloApps.visibility = View.GONE
        btnInstalarTodas.visibility = View.GONE
        nota.visibility = View.GONE
        nota.setText(R.string.restaurar_nota)
        listaApps.removeAllViews()
        listaExtras.removeAllViews()
        tituloExtras.visibility = View.GONE
        estados.clear()
        instaladas.clear()
        apps = emptyList()
        copia = null
    }

    private fun mostrarResultado(texto: String) {
        textoResultado.text = texto
        textoResultado.visibility = View.VISIBLE
    }

    private fun pintarProgreso(p: Progreso) {
        var texto = getString(p.fase)
        if (p.detalle.isNotEmpty()) texto += " · " + p.detalle
        textoFase.text = texto
        if (p.fraccion < 0f) {
            barra.isIndeterminate = true
        } else {
            barra.isIndeterminate = false
            barra.progress = (p.fraccion * 1000f).toInt()
        }
    }

    private fun ocupado(si: Boolean) {
        btnElegir.isEnabled = !si
        btnDescifrar.isEnabled = !si
        etContrasena.isEnabled = !si
        btnCancelar.visibility = if (si) View.VISIBLE else View.GONE
        textoFase.visibility = if (si) View.VISIBLE else View.GONE
        barra.visibility = if (si) View.VISIBLE else View.GONE
        if (si) {
            barra.isIndeterminate = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
