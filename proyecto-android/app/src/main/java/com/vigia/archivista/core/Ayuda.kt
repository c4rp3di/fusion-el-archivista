package com.vigia.archivista.core

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vigia.archivista.R

/** Ayuda integrada: un enlace «? Ayuda» junto al botón Volver de cada pantalla que abre una explicación breve. */
object Ayuda {

    /** Un texto con aspecto de enlace, con zona táctil cómoda. */
    fun enlace(contexto: Context, texto: String, alPulsar: () -> Unit): TextView {
        val t = TextView(contexto)
        t.text = texto
        t.textSize = 14f
        t.setTextColor(Tema.primario(contexto))
        t.isClickable = true
        t.isFocusable = true
        val m = (12 * contexto.resources.displayMetrics.density).toInt()
        t.setPadding(m, m, m, m)
        t.gravity = Gravity.CENTER_VERTICAL
        t.setOnClickListener { alPulsar() }
        return t
    }

    fun mostrar(actividad: Activity, titulo: Int, texto: Int) {
        AlertDialog.Builder(actividad)
            .setTitle(titulo)
            .setMessage(texto)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Añade «? Ayuda» junto al botón Volver (id btnVolver) de la pantalla. */
    fun instalar(actividad: Activity, titulo: Int, texto: Int) {
        val volver = actividad.findViewById<View>(R.id.btnVolver) ?: return
        val padre = volver.parent as? LinearLayout ?: return
        val enlace = enlace(actividad, "?  Ayuda") { mostrar(actividad, titulo, texto) }
        if (padre.orientation == LinearLayout.HORIZONTAL) {
            padre.addView(enlace)
        } else {
            val indice = padre.indexOfChild(volver)
            padre.removeView(volver)
            val fila = LinearLayout(actividad)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.gravity = Gravity.CENTER_VERTICAL
            fila.addView(volver)
            fila.addView(enlace)
            padre.addView(fila, indice)
        }
    }
}
