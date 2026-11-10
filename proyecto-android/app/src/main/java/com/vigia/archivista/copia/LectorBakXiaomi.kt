package com.vigia.archivista.copia

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Reconoce el contenido de un .bak de Xiaomi: un TAR con una cabecera propia delante. Busca la marca «ustar» (que en un
 * TAR está 257 bytes después del inicio de su cabecera) y comprueba la suma de control para no confundirse con datos
 * casuales. También entiende el formato «ANDROID BACKUP» sin cifrar (TAR deflatado tras cuatro líneas de texto).
 * Solo sirve para validar y para leer el contenido: al .bkp se guarda el .bak original, que es lo único que la app de
 * copias de Xiaomi sabe restaurar.
 */
object LectorBakXiaomi {
    private const val BUSQUEDA = 1_048_576
    private const val ANDROID_BACKUP = "ANDROID BACKUP\n"

    /** Dónde empieza el TAR dentro del archivo y si viene deflatado. */
    class Info(val desplazamiento: Long, val deflatado: Boolean)

    fun inspeccionar(f: File): Info? {
        try {
            FileInputStream(f).use { entrada ->
                val cabeza = ByteArray(minOf(f.length(), BUSQUEDA.toLong()).toInt())
                var hecho = 0
                while (hecho < cabeza.size) {
                    val n = entrada.read(cabeza, hecho, cabeza.size - hecho)
                    if (n < 0) break
                    hecho += n
                }
                val texto = String(cabeza, 0, minOf(hecho, 64), Charsets.ISO_8859_1)
                if (texto.startsWith(ANDROID_BACKUP)) {
                    val lineas = texto.split("\n")
                    if (lineas.size >= 5 && lineas[3] == "none") {
                        val largo = (lineas[0].length + lineas[1].length + lineas[2].length + lineas[3].length + 4).toLong()
                        return Info(largo, lineas[2] == "1")
                    }
                    return null
                }
                var p = 257
                while (p + 5 <= hecho) {
                    if (cabeza[p].toInt() == 'u'.code && cabeza[p + 1].toInt() == 's'.code && cabeza[p + 2].toInt() == 't'.code &&
                        cabeza[p + 3].toInt() == 'a'.code && cabeza[p + 4].toInt() == 'r'.code
                    ) {
                        if (sumaValida(cabeza, p - 257, hecho)) return Info((p - 257).toLong(), false)
                    }
                    p++
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    /** Flujo del TAR «limpio», sin la cabecera de MIUI. */
    fun abrirTar(f: File, info: Info): InputStream {
        val entrada = FileInputStream(f)
        var saltado = 0L
        while (saltado < info.desplazamiento) {
            val s = entrada.skip(info.desplazamiento - saltado)
            if (s <= 0) break
            saltado += s
        }
        val buf = BufferedInputStream(entrada, 65536)
        return if (info.deflatado) InflaterInputStream(buf) else buf
    }

    /** Cuenta los archivos del TAR; -1 si no se puede leer. */
    fun contarArchivos(f: File, info: Info): Int {
        return try {
            abrirTar(f, info).use { entrada ->
                val lector = TarLector(entrada)
                var n = 0
                while (true) {
                    val e = lector.siguiente() ?: break
                    if (e.esArchivo) n++
                }
                n
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun sumaValida(b: ByteArray, ini: Int, hecho: Int): Boolean {
        if (ini < 0 || ini + 512 > hecho) return false
        var suma = 0L
        for (i in 0 until 512) {
            suma += if (i in 148..155) 32 else (b[ini + i].toInt() and 0xFF)
        }
        var guardada = 0L
        var empezado = false
        for (i in 148 until 156) {
            val c = b[ini + i].toInt()
            if (c in '0'.code..'7'.code) {
                guardada = guardada * 8 + (c - '0'.code)
                empezado = true
            } else if (empezado || (c != ' '.code && c != 0)) {
                break
            }
        }
        return guardada == suma
    }
}
