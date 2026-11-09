package com.vigia.archivista.core

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.vigia.archivista.R

/** Colores de la suite. El tema oscuro lo aporta Material 3 (DayNight); aquí solo están los acentos y el texto. */
object Tema {
    fun rojo(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_rojo)

    fun ambar(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_ambar)

    fun gris(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_gris)

    fun azul(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_azul)

    fun verde(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_verde)

    fun morado(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_morado)

    fun naranja(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_naranja)

    fun cian(c: Context): Int = ContextCompat.getColor(c, R.color.vigia_cian)

    fun texto(c: Context): Int {
        val v = TypedValue()
        c.theme.resolveAttribute(android.R.attr.textColorPrimary, v, true)
        return if (v.resourceId != 0) ContextCompat.getColor(c, v.resourceId) else v.data
    }

    fun primario(c: Context): Int {
        val v = TypedValue()
        c.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, v, true)
        return v.data
    }
}
