package com.vigia.archivista.adb

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.archivista.R
import com.vigia.archivista.core.ActividadBase
import kotlinx.coroutines.launch

/**
 * Modo avanzado: empareja la app con la depuración inalámbrica de este teléfono para poder ejecutar órdenes de shell.
 * Necesita Android 11 o superior y una red wifi. El emparejamiento se hace una sola vez.
 */
class ModoAvanzadoActivity : ActividadBase() {

    private lateinit var estado: TextView
    private lateinit var registro: TextView
    private lateinit var etPuerto: EditText
    private lateinit var etCodigo: EditText
    private lateinit var etPuertoCon: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modo_avanzado)
        prepararPantalla(R.string.ayuda_modo_adb_titulo, R.string.ayuda_modo_adb)
        estado = findViewById(R.id.adbEstado)
        registro = findViewById(R.id.adbRegistro)
        etPuerto = findViewById(R.id.etPuertoPar)
        etCodigo = findViewById(R.id.etCodigoPar)
        etPuertoCon = findViewById(R.id.etPuertoCon)

        findViewById<MaterialButton>(R.id.btnAbrirDesarrollo).setOnClickListener { abrirDesarrollo() }
        findViewById<MaterialButton>(R.id.btnEmparejar).setOnClickListener { emparejar() }
        findViewById<MaterialButton>(R.id.btnConectarAdb).setOnClickListener { conectar() }
        findViewById<MaterialButton>(R.id.btnOlvidarAdb).setOnClickListener { olvidar() }
    }

    override fun onResume() {
        super.onResume()
        if (ShellManager.estado() == ShellManager.Estado.NO_DISPONIBLE) {
            estado.setText(R.string.adb_no_disponible)
            return
        }
        estado.setText(R.string.adb_comprobando)
        lifecycleScope.launch {
            val e = ShellManager.verificar()
            estado.setText(if (e == ShellManager.Estado.CONECTADO) R.string.adb_conectado else R.string.adb_sin_conexion)
        }
    }

    private fun abrirDesarrollo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            aviso(R.string.no_se_pudo_abrir)
        }
    }

    private fun emparejar() {
        if (ShellManager.estado() == ShellManager.Estado.NO_DISPONIBLE) {
            aviso(R.string.adb_no_disponible)
            return
        }
        val puerto = etPuerto.text.toString().trim().toIntOrNull()
        val codigo = etCodigo.text.toString().trim()
        if (puerto == null || puerto < 1 || puerto > 65535 || codigo.length != 6) {
            aviso(R.string.adb_datos_invalidos)
            return
        }
        estado.setText(R.string.adb_emparejando)
        lifecycleScope.launch {
            val resultado = try {
                if (ShellManager.emparejar(applicationContext, puerto, codigo)) "emparejado" else "rechazado"
            } catch (e: Exception) {
                "error: " + e.javaClass.simpleName + " " + (e.message ?: "")
            }
            registro.text = "Emparejar → " + resultado
            if (resultado == "emparejado") {
                etCodigo.setText("")
                estado.setText(R.string.adb_emparejado_ok)
            } else {
                estado.setText(R.string.adb_emparejar_fallo)
            }
        }
    }

    private fun conectar() {
        if (ShellManager.estado() == ShellManager.Estado.NO_DISPONIBLE) {
            aviso(R.string.adb_no_disponible)
            return
        }
        val textoPuerto = etPuertoCon.text.toString().trim()
        val puertoManual = if (textoPuerto.isEmpty()) null else textoPuerto.toIntOrNull()
        if (textoPuerto.isNotEmpty() && (puertoManual == null || puertoManual < 1 || puertoManual > 65535)) {
            aviso(R.string.adb_datos_invalidos)
            return
        }
        estado.setText(R.string.adb_conectando)
        lifecycleScope.launch {
            val resultado = try {
                if (ShellManager.conectar(applicationContext, puertoManual)) "conectado" else "sin conexión"
            } catch (e: Exception) {
                "error: " + e.javaClass.simpleName + " " + (e.message ?: "")
            }
            registro.text = "Conectar → " + resultado
            estado.setText(if (resultado == "conectado") R.string.adb_conectado else R.string.adb_conectar_fallo)
        }
    }

    private fun olvidar() {
        AlertDialog.Builder(this)
            .setTitle(R.string.olvidar_emparejamiento)
            .setMessage(R.string.confirmar_olvidar)
            .setPositiveButton(R.string.olvidar) { _, _ ->
                ShellManager.olvidar(applicationContext)
                estado.setText(R.string.adb_sin_conexion)
                registro.text = ""
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
