package com.vigia.archivista

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.vigia.archivista.adb.ModoAvanzadoActivity
import com.vigia.archivista.adb.ShellManager
import com.vigia.archivista.copia.AppInfo
import com.vigia.archivista.copia.AdbArchivos
import com.vigia.archivista.copia.AppsUsuario
import com.vigia.archivista.copia.ErrorCopia
import com.vigia.archivista.copia.FormatoBkp
import com.vigia.archivista.copia.OpcionesBackup
import com.vigia.archivista.copia.OrquestadorXiaomi
import com.vigia.archivista.copia.Progreso
import com.vigia.archivista.copia.ProcesoBackup
import com.vigia.archivista.copia.ResultadoBackup
import com.vigia.archivista.core.ActividadBase
import com.vigia.archivista.core.Formato
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private lateinit var cbExternos: CheckBox
    private lateinit var cbInternos: CheckBox
    private lateinit var cbXiaomi: CheckBox
    private lateinit var textoOpciones: TextView

    private var apps: List<AppInfo> = emptyList()
    private val seleccion = HashSet<String>()
    private var adaptador: AdaptadorApps? = null
    private var elegidas: List<AppInfo> = emptyList()
    private var trabajo: Job? = null
    private var opciones = OpcionesBackup(false, false, emptyList())
    private var xiaomiPendientes: List<OrquestadorXiaomi.Remoto> = emptyList()

    private val lanzadorCrear = registerForActivityResult(ActivityResultContracts.CreateDocument(FormatoBkp.MIME)) { uri ->
        if (uri == null) {
            AdbArchivos.limpiar(this)
            xiaomiPendientes = emptyList()
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
        cbExternos = findViewById(R.id.cbExternos)
        cbInternos = findViewById(R.id.cbInternos)
        cbXiaomi = findViewById(R.id.cbXiaomi)
        textoOpciones = findViewById(R.id.textoOpciones)

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

    /** Los datos adicionales solo se ofrecen con el Modo Avanzado conectado; la opción de Xiaomi, además, si la app existe. */
    private fun pintarOpciones(e: ShellManager.Estado) {
        val conectado = e == ShellManager.Estado.CONECTADO
        val hayXiaomi = OrquestadorXiaomi.instalada(this)
        cbExternos.isEnabled = conectado
        cbInternos.isEnabled = conectado
        cbXiaomi.isEnabled = conectado && hayXiaomi
        if (!conectado) {
            cbExternos.isChecked = false
            cbInternos.isChecked = false
        }
        if (!cbXiaomi.isEnabled) cbXiaomi.isChecked = false
        textoOpciones.setText(
            when {
                !conectado -> R.string.opciones_requieren_modo
                !hayXiaomi -> R.string.opciones_xiaomi_no
                else -> R.string.opciones_listas
            }
        )
    }

    private fun pintarOrigen(e: ShellManager.Estado) {
        pintarOpciones(e)
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
        xiaomiPendientes = emptyList()
        if (cbXiaomi.isChecked) {
            flujoXiaomi()
        } else {
            lanzarDestino()
        }
    }

    private fun lanzarDestino() {
        val fecha = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        lanzadorCrear.launch("backup_" + fecha + FormatoBkp.EXTENSION)
    }

    /**
     * Abre la app de Xiaomi por ADB, avisa a la persona de que haga allí su copia y vigila los archivos nuevos. Cuando pulsa
     * «Ya he terminado» se recogen y se pide el destino del .bkp; los archivos se copian al cifrar, no antes.
     */
    private fun flujoXiaomi() {
        btnIniciar.isEnabled = false
        trabajo = lifecycleScope.launch {
            try {
                val marca = OrquestadorXiaomi.marcar(this@BackupActivity)
                val fallo = OrquestadorXiaomi.abrirInterfaz()
                if (fallo != null) {
                    mostrarResultado(getString(R.string.xiaomi_no_abre, fallo))
                    return@launch
                }
                val listo = CompletableDeferred<Boolean>()
                val dialogo = AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.xiaomi_titulo)
                    .setMessage(R.string.xiaomi_mensaje)
                    .setCancelable(false)
                    .setPositiveButton(R.string.xiaomi_listo) { _, _ -> listo.complete(true) }
                    .setNegativeButton(R.string.btn_cancelar) { _, _ -> listo.complete(false) }
                    .create()
                dialogo.show()
                val sondeo = launch {
                    while (true) {
                        try {
                            val nuevos = OrquestadorXiaomi.buscarNuevos(marca)
                            var tam = 0L
                            for (n in nuevos) tam += n.tamano
                            dialogo.setMessage(getString(R.string.xiaomi_mensaje) + "\n\n" + getString(R.string.xiaomi_detectados, nuevos.size, Formato.bytes(tam)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Un sondeo fallido no importa: se reintenta en el siguiente.
                        }
                        delay(4000)
                    }
                }
                val acepto = try {
                    listo.await()
                } finally {
                    sondeo.cancel()
                    if (dialogo.isShowing) dialogo.dismiss()
                }
                if (!acepto) {
                    AdbArchivos.limpiar(this@BackupActivity)
                    mostrarResultado(getString(R.string.copia_cancelada))
                    return@launch
                }
                val nuevos = OrquestadorXiaomi.buscarNuevos(marca)
                if (nuevos.isEmpty()) {
                    AdbArchivos.limpiar(this@BackupActivity)
                    mostrarResultado(getString(R.string.xiaomi_nada))
                    return@launch
                }
                xiaomiPendientes = nuevos
                lanzarDestino()
            } catch (e: CancellationException) {
                AdbArchivos.limpiar(this@BackupActivity)
                throw e
            } catch (e: Exception) {
                AdbArchivos.limpiar(this@BackupActivity)
                mostrarResultado(getString(R.string.error_generico, e.message ?: e.javaClass.simpleName))
            } finally {
                trabajo = null
                btnIniciar.isEnabled = true
            }
        }
    }

    private fun crearCopia(destino: Uri) {
        val clave = leerClave(etContrasena)
        opciones = OpcionesBackup(cbExternos.isChecked, cbInternos.isChecked, xiaomiPendientes)
        ocupado(true)
        textoResultado.visibility = View.GONE
        trabajo = lifecycleScope.launch {
            try {
                val r = ProcesoBackup.ejecutar(applicationContext, elegidas, clave, destino, opciones) { p ->
                    runOnUiThread { pintarProgreso(p) }
                }
                etContrasena.setText("")
                etRepetir.setText("")
                xiaomiPendientes = emptyList()
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
        if (r.externos + r.internos + r.xiaomi > 0) {
            sb.append("\n\n")
            sb.append(getString(R.string.copia_datos, r.externos, r.internos, r.xiaomi))
        }
        if (r.omitidas.isNotEmpty()) {
            sb.append("\n\n")
            sb.append(getString(R.string.copia_omitidas, r.omitidas.joinToString(", ")))
        }
        for (a in r.avisos) {
            sb.append("\n• ")
            sb.append(a)
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
        if (si) {
            cbExternos.isEnabled = false
            cbInternos.isEnabled = false
            cbXiaomi.isEnabled = false
        } else {
            pintarOpciones(ShellManager.estado())
        }
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
