package com.vigia.archivista

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.vigia.archivista.adb.ModoAvanzadoActivity
import com.vigia.archivista.adb.ShellManager
import com.vigia.archivista.core.ActividadBase
import com.vigia.archivista.core.Ayuda
import com.vigia.archivista.core.Ui
import kotlinx.coroutines.launch

class MainActivity : ActividadBase() {

    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.estadoShell)

        findViewById<View>(R.id.cardEstado).setOnClickListener {
            startActivity(Intent(this, ModoAvanzadoActivity::class.java))
        }

        val cabecera = findViewById<LinearLayout>(R.id.filaCabecera)
        cabecera.addView(Ayuda.enlace(this, "?  Ayuda") { Ayuda.mostrar(this, R.string.ayuda_principal_titulo, R.string.ayuda_principal) })

        val contenedor = findViewById<LinearLayout>(R.id.contenidoPrincipal)
        contenedor.addView(
            tarjetaAccion(R.string.menu_backup, R.string.menu_backup_desc, R.string.btn_crear_copia) {
                startActivity(Intent(this, BackupActivity::class.java))
            }
        )
        contenedor.addView(
            tarjetaAccion(R.string.menu_archivos, R.string.menu_archivos_desc, R.string.btn_archivar) {
                startActivity(Intent(this, ArchivosActivity::class.java))
            }
        )
        contenedor.addView(
            tarjetaAccion(R.string.menu_restaurar, R.string.menu_restaurar_desc, R.string.btn_restaurar_copia) {
                startActivity(Intent(this, RestauracionActivity::class.java))
            }
        )
        pintarEstado(ShellManager.estado())
    }

    override fun onResume() {
        super.onResume()
        pintarEstado(ShellManager.estado())
        lifecycleScope.launch {
            pintarEstado(ShellManager.verificar())
        }
    }

    /** Tarjeta de Ui.kt con título, descripción y un botón. */
    private fun tarjetaAccion(titulo: Int, descripcion: Int, boton: Int, alPulsar: () -> Unit): View {
        val par = Ui.tarjetaConColumna(this)
        val columna = par.second
        columna.addView(Ui.titulo(this, getString(titulo)))
        columna.addView(Ui.texto(this, getString(descripcion), 13f))
        columna.addView(Ui.espacio(this, 10))
        columna.addView(Ui.boton(this, getString(boton)) { alPulsar() })
        return par.first
    }

    private fun pintarEstado(e: ShellManager.Estado) {
        estado.setText(
            when (e) {
                ShellManager.Estado.CONECTADO -> R.string.estado_conectado
                ShellManager.Estado.DESCONECTADO -> R.string.estado_desconectado
                ShellManager.Estado.NO_DISPONIBLE -> R.string.estado_no_disponible
            }
        )
    }
}
