package com.vigia.archivista

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.archivista.copia.ArbolSaf
import com.vigia.archivista.copia.Carpeta
import com.vigia.archivista.copia.ErrorCopia
import com.vigia.archivista.copia.FormatoBkp
import com.vigia.archivista.copia.ProcesoArchivos
import com.vigia.archivista.copia.Progreso
import com.vigia.archivista.copia.ResultadoArchivos
import com.vigia.archivista.core.ActividadBase
import com.vigia.archivista.core.Formato
import com.vigia.archivista.core.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copia de archivos personales: carpetas elegidas con el selector del sistema (SAF), cifradas en un .bkp propio. */
class ArchivosActivity : ActividadBase() {

    private lateinit var listaCarpetas: LinearLayout
    private lateinit var textoVacio: TextView
    private lateinit var etContrasena: EditText
    private lateinit var etRepetir: EditText
    private lateinit var textoFase: TextView
    private lateinit var barra: ProgressBar
    private lateinit var btnIniciar: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private lateinit var btnAnadir: MaterialButton
    private lateinit var textoResultado: TextView

    private val carpetas = ArrayList<Carpeta>()
    private var elegidas: List<Carpeta> = emptyList()
    private var trabajo: Job? = null

    private val lanzadorCarpeta = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) anadir(uri)
    }

    private val lanzadorCrear = registerForActivityResult(ActivityResultContracts.CreateDocument(FormatoBkp.MIME)) { uri ->
        if (uri == null) aviso(R.string.aviso_destino_cancelado) else crearCopia(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archivos)
        prepararPantalla(R.string.ayuda_archivos_titulo, R.string.ayuda_archivos)

        listaCarpetas = findViewById(R.id.listaCarpetas)
        textoVacio = findViewById(R.id.textoVacio)
        etContrasena = findViewById(R.id.etContrasena)
        etRepetir = findViewById(R.id.etRepetir)
        textoFase = findViewById(R.id.textoFase)
        barra = findViewById(R.id.barra)
        btnIniciar = findViewById(R.id.btnIniciar)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnAnadir = findViewById(R.id.btnAnadirCarpeta)
        textoResultado = findViewById(R.id.textoResultado)

        btnAnadir.setOnClickListener { lanzadorCarpeta.launch(null) }
        btnIniciar.setOnClickListener { iniciar() }
        btnCancelar.setOnClickListener { trabajo?.cancel() }
    }

    private fun anadir(uri: Uri) {
        for (c in carpetas) {
            if (c.uri == uri) return
        }
        lifecycleScope.launch {
            val nombre = withContext(Dispatchers.IO) { ArbolSaf.nombreRaiz(applicationContext, uri) }
            carpetas.add(Carpeta(uri, nombre))
            pintarCarpetas()
        }
    }

    private fun pintarCarpetas() {
        listaCarpetas.removeAllViews()
        textoVacio.visibility = if (carpetas.isEmpty()) View.VISIBLE else View.GONE
        for (carpeta in carpetas) {
            val fila = LinearLayout(this)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.gravity = android.view.Gravity.CENTER_VERTICAL
            val nombre = Ui.texto(this, "📁 " + carpeta.nombre, 15f, true)
            nombre.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            fila.addView(nombre)
            fila.addView(Ui.botonSecundario(this, getString(R.string.btn_quitar)) {
                carpetas.remove(carpeta)
                pintarCarpetas()
            })
            listaCarpetas.addView(fila)
        }
    }

    private fun leerClave(campo: EditText): CharArray {
        val t = campo.text
        val a = CharArray(t.length)
        t.getChars(0, t.length, a, 0)
        return a
    }

    private fun iniciar() {
        if (trabajo != null) return
        if (carpetas.isEmpty()) {
            aviso(R.string.err_sin_carpetas)
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
        elegidas = ArrayList(carpetas)
        val fecha = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        lanzadorCrear.launch("archivos_" + fecha + FormatoBkp.EXTENSION)
    }

    private fun crearCopia(destino: Uri) {
        val clave = leerClave(etContrasena)
        ocupado(true)
        textoResultado.visibility = View.GONE
        trabajo = lifecycleScope.launch {
            try {
                val r = ProcesoArchivos.ejecutar(applicationContext, elegidas, clave, destino) { p ->
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

    private fun textoOk(r: ResultadoArchivos): String {
        val tamFinal = if (r.bytesFinal >= 0) Formato.bytes(r.bytesFinal) else "—"
        var t = getString(R.string.archivos_ok, r.carpetas, r.archivos, Formato.bytes(r.bytes), tamFinal)
        if (r.omitidos > 0) t += "\n\n" + getString(R.string.archivos_omitidos, r.omitidos)
        return t
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
        btnAnadir.isEnabled = !si
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
