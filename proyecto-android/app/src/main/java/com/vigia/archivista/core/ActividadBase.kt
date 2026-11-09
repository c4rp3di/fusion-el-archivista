package com.vigia.archivista.core

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.vigia.archivista.R

/**
 * Base de todas las pantallas de la suite: aporta el botón Volver, la ayuda de cada pantalla y los avisos cortos, para que
 * el aspecto y el comportamiento sean los mismos en todas las apps.
 */
abstract class ActividadBase : AppCompatActivity() {

    /** Columna donde se añaden las vistas cuando la pantalla se monta con usarPantallaScroll(). */
    protected lateinit var contenido: LinearLayout

    /** Conecta el botón Volver (id btnVolver) y añade el enlace «? Ayuda». Se llama justo después de setContentView. */
    protected fun prepararPantalla(tituloAyuda: Int, textoAyuda: Int) {
        findViewById<MaterialButton>(R.id.btnVolver)?.setOnClickListener { finish() }
        Ayuda.instalar(this, tituloAyuda, textoAyuda)
    }

    /** Pantalla con desplazamiento, título y una columna vacía (contenido) que se rellena desde el código. */
    protected fun usarPantallaScroll(titulo: Int, tituloAyuda: Int, textoAyuda: Int) {
        setContentView(R.layout.pantalla_scroll)
        findViewById<TextView>(R.id.tituloPantalla).setText(titulo)
        contenido = findViewById(R.id.contenido)
        prepararPantalla(tituloAyuda, textoAyuda)
    }

    protected fun aviso(texto: Int) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }

    protected fun aviso(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
