package com.vigia.archivista.core

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Piezas de pantalla reutilizables: tarjetas con relieve, títulos, filas y botones, con el mismo estilo en toda la suite. */
object Ui {

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** Una tarjeta con relieve y esquinas redondeadas; devuelve la tarjeta y se rellena con columna(). */
    fun tarjeta(c: Context): MaterialCardView {
        val t = MaterialCardView(c, null, com.google.android.material.R.attr.materialCardViewElevatedStyle)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(c, 6), 0, dp(c, 6))
        t.layoutParams = lp
        t.radius = dp(c, 16).toFloat()
        t.cardElevation = dp(c, 3).toFloat()
        return t
    }

    fun columna(c: Context, margen: Int = 14): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(c, margen), dp(c, margen), dp(c, margen), dp(c, margen))
        return l
    }

    /** Tarjeta ya con su columna interior; añade las vistas a la columna. */
    fun tarjetaConColumna(c: Context): Pair<MaterialCardView, LinearLayout> {
        val t = tarjeta(c)
        val col = columna(c)
        t.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Pair(t, col)
    }

    fun texto(c: Context, texto: CharSequence, tam: Float = 14f, negrita: Boolean = false, color: Int? = null): TextView {
        val t = TextView(c)
        t.text = texto
        t.textSize = tam
        if (negrita) t.setTypeface(t.typeface, Typeface.BOLD)
        if (color != null) t.setTextColor(color)
        return t
    }

    fun monoespaciado(c: Context, valor: CharSequence, tam: Float = 11f): TextView {
        val t = texto(c, valor, tam)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        return t
    }

    fun titulo(c: Context, valor: CharSequence): TextView = texto(c, valor, 18f, true)

    fun seccion(c: Context, valor: CharSequence): TextView {
        val t = texto(c, valor, 16f, true)
        t.setPadding(0, dp(c, 14), 0, dp(c, 2))
        return t
    }

    fun espacio(c: Context, alto: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, alto))
        return v
    }

    /** Dos textos en una fila: el de la izquierda ocupa el espacio y el de la derecha va alineado al final. */
    fun fila(c: Context, izquierda: CharSequence, derecha: CharSequence, tam: Float = 13f): LinearLayout {
        val f = LinearLayout(c)
        f.orientation = LinearLayout.HORIZONTAL
        val a = texto(c, izquierda, tam)
        val b = texto(c, derecha, tam, true)
        f.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        f.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return f
    }

    fun boton(c: Context, texto: CharSequence, alPulsar: () -> Unit): MaterialButton {
        val b = MaterialButton(c)
        b.text = texto
        b.isAllCaps = false
        b.setOnClickListener { alPulsar() }
        return b
    }

    fun botonSecundario(c: Context, texto: CharSequence, alPulsar: () -> Unit): MaterialButton {
        val b = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = texto
        b.isAllCaps = false
        b.setOnClickListener { alPulsar() }
        return b
    }

    /** Una línea de leyenda: cuadrito de color y etiqueta. */
    fun leyenda(c: Context, color: Int, etiqueta: CharSequence, valor: CharSequence = ""): LinearLayout {
        val f = LinearLayout(c)
        f.orientation = LinearLayout.HORIZONTAL
        f.setPadding(0, dp(c, 2), 0, dp(c, 2))
        val punto = texto(c, "■ ", 14f, false, color)
        val txt = texto(c, etiqueta, 13f)
        f.addView(punto)
        f.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (valor.isNotEmpty()) f.addView(texto(c, valor, 13f, true))
        return f
    }
}
