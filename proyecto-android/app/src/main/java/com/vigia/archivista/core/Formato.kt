package com.vigia.archivista.core

import java.util.Locale

object Formato {
    fun bytes(n: Long): String {
        val kb = 1024.0
        return when {
            n >= kb * kb * kb -> String.format(Locale.US, "%.1f GB", n / (kb * kb * kb))
            n >= kb * kb -> String.format(Locale.US, "%.0f MB", n / (kb * kb))
            n >= kb -> String.format(Locale.US, "%.0f KB", n / kb)
            else -> n.toString() + " B"
        }
    }

    fun kb(n: Long): String = bytes(n * 1024L)

    fun porcentaje(valor: Double): String = String.format(Locale.US, "%.0f%%", valor)

    fun decimal(valor: Double, decimales: Int = 1): String = String.format(Locale.US, "%." + decimales + "f", valor)

    /** «hace 12 s», «hace 3 min». */
    fun hace(ms: Long): String {
        val s = ms / 1000L
        return when {
            s < 5 -> "ahora mismo"
            s < 60 -> "hace " + s + " s"
            s < 3600 -> "hace " + (s / 60) + " min"
            else -> "hace " + (s / 3600) + " h"
        }
    }

    fun duracion(ms: Long): String {
        val min = ms / 60000L
        return if (min >= 60) (min / 60).toString() + " h " + (min % 60) + " min" else min.toString() + " min"
    }
}
