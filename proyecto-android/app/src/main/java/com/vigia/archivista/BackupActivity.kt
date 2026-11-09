package com.vigia.archivista

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vigia.archivista.adb.ModoAvanzadoActivity
import com.vigia.archivista.adb.ShellManager
import com.vigia.archivista.copia.AppInfo
import com.vigia.archivista.copia.AppsUsuario
import com.vigia.archivista.copia.ErrorCopia
import com.vigia.archivista.copia.FormatoBkp
import com.vigia.archivista.copia.Progreso
import com.vigia.archivista.copia.ProcesoBackup
import com.vigia.archivista.copia.ResultadoBackup
import com.vigia.archivista.core.ActividadBase
import com.vigia.archivista.core.Formato
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla de copia: elegir apps, contraseña, destino (selector de archivos del sistema) y cifrar. */
class BackupActivity : ActividadBase() {

    private lateinit var lista: RecyclerView
    private lateinit var cargando: TextView
    private lateinit var resumen: TextView
    private lateinit var origen: TextView
    private lateinit var etContrasena: EditText
    private lateinit var etRepetir: EditText
    private lateinit var textoFase: TextView
    private lateinit var barra: ProgressBar
    private lateinit var btnIniciar: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private lateinit var btnTodas: MaterialButton
    private lateinit var btnNinguna: MaterialButton
    private lateinit var textoResultado: TextView

    private var apps: List<AppInfo> = emptyList()
    private val seleccion = HashSet<String>()
    private var adaptador: AdaptadorApps? = null
    private var elegidas: List<AppInfo> = emptyList()
    private var trabajo: Job? = null

    private val lanzadorCrear = registerForActivityResult(ActivityResultContracts.CreateDocument(FormatoBkp.MIME)) { uri ->
        if (uri == null) {
            aviso(R.string.aviso_destino_cancelado)
        } else {
            crearCopia(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        prepararPantalla(R.string.ayuda_backup_titulo, R.string.ayuda_backup)

        lista = findViewById(R.id.listaApps)
        cargando = findViewById(R.id.cargandoApps)
        resumen = findViewById(R.id.resumenSeleccion)
        origen = findViewById(R.id.estadoOrigen)
        etContrasena = findViewById(R.id.etContrasena)
        etRepetir = findViewById(R.id.etRepetir)
        textoFase = findViewById(R.id.textoFase)
        barra = findViewById(R.id.barra)
        btnIniciar = findViewById(R.id.btnIniciar)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnTodas = findViewById(R.id.btnTodas)
        btnNinguna = findViewById(R.id.btnNinguna)
        textoResultado = findViewById(R.id.textoResultado)

        lista.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.cardOrigen).setOnClickListener {
            startActivity(Intent(this, ModoAvanzadoActivity::class.java))
        }
        btnTodas.setOnClickListener { marcarTodas(true) }
        btnNinguna.setOnClickListener { marcarTodas(false) }
        btnIniciar.setOnClickListener { iniciar() }
        btnCancelar.setOnClickListener { trabajo?.cancel() }

        actualizarResumen()
        cargarApps()
    }

    override fun onResume() {
        super.onResume()
        pintarOrigen(ShellManager.estado())
        lifecycleScope.launch {
            pintarOrigen(ShellManager.verificar())
        }
    }

    private fun pintarOrigen(e: ShellManager.Estado) {
        origen.setText(
            when (e) {
                ShellManager.Estado.CONECTADO -> R.string.estado_conectado
                ShellManager.Estado.DESCONECTADO -> R.string.estado_desconectado
                ShellManager.Estado.NO_DISPONIBLE -> R.string.estado_no_disponible
            }
        )
    }

    private fun cargarApps() {
        lifecycleScope.launch {
            val cargadas = withContext(Dispatchers.IO) { AppsUsuario.cargar(applicationContext) }
            apps = cargadas
            val a = AdaptadorApps(cargadas, seleccion) { actualizarResumen() }
            adaptador = a
            lista.adapter = a
            if (cargadas.isEmpty()) {
                cargando.setText(R.string.backup_sin_apps)
            } else {
                cargando.visibility = View.GONE
            }
            actualizarResumen()
        }
    }

    private fun marcarTodas(marcar: Boolean) {
        seleccion.clear()
        if (marcar) {
            for (a in apps) seleccion.add(a.paquete)
        }
        adaptador?.notifyDataSetChanged()
        actualizarResumen()
    }

    private fun actualizarResumen() {
        var tam = 0L
        for (a in apps) {
            if (seleccion.contains(a.paquete)) tam += a.tamano
        }
        resumen.text = getString(R.string.resumen_seleccion, seleccion.size, Formato.bytes(tam))
    }

    private fun leerClave(campo: EditText): CharArray {
        val t = campo.text
        val a = CharArray(t.length)
        t.getChars(0, t.length, a, 0)
        return a
    }

    /** Valida y pide al sistema dónde guardar «backup_fecha.bkp»; el trabajo empieza al recibir el destino. */
    private fun iniciar() {
        if (trabajo != null) return
        if (seleccion.isEmpty()) {
            aviso(R.string.err_sin_seleccion)
            return
        }
        val clave = leerClave(etContrasena)
        val repetida = leerClave(etRepetir)
        val corta = clave.size < 8
        val iguales = clave.contentEquals(repetida)
        clave.fill(0.toChar())
        repetida.fill(0.toChar())
        if (corta) {
            aviso(R.string.err_contrasena_corta)
            return
        }
        if (!iguales) {
            aviso(R.string.err_contrasenas_distintas)
            return
        }
        elegidas = apps.filter { seleccion.contains(it.paquete) }
        val fecha = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        lanzadorCrear.launch("backup_" + fecha + FormatoBkp.EXTENSION)
    }

    private fun crearCopia(destino: Uri) {
        val clave = leerClave(etContrasena)
        ocupado(true)
        textoResultado.visibility = View.GONE
        trabajo = lifecycleScope.launch {
            try {
                val r = ProcesoBackup.ejecutar(applicationContext, elegidas, clave, destino) { p ->
                    runOnUiThread { pintarProgreso(p) }
                }
                etContrasena.setText("")
                etRepetir.setText("")
                mostrarResultado(textoOk(r))
            } catch (e: CancellationException) {
                mostrarResultado(getString(R.string.copia_cancelada))
                throw e
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

    private fun textoOk(r: ResultadoBackup): String {
        val tamFinal = if (r.bytesFinal >= 0) Formato.bytes(r.bytesFinal) else "—"
        val sb = StringBuilder()
        sb.append(getString(R.string.copia_ok, r.apps, r.apks, Formato.bytes(r.bytesApks), tamFinal))
        sb.append("\n\n")
        sb.append(getString(R.string.copia_origen, r.porShell, r.porGestor))
        if (r.omitidas.isNotEmpty()) {
            sb.append("\n\n")
            sb.append(getString(R.string.copia_omitidas, r.omitidas.joinToString(", ")))
        }
        return sb.toString()
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
        btnIniciar.isEnabled = !si
        btnTodas.isEnabled = !si
        btnNinguna.isEnabled = !si
        etContrasena.isEnabled = !si
        etRepetir.isEnabled = !si
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
