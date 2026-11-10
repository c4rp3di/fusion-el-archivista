package com.vigia.archivista.copia

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Enumeration
import java.io.SequenceInputStream

/**
 * TAR mínimo (ustar con registros PAX) escrito y leído en streaming, sin librerías. El escritor calcula el tamaño exacto
 * de antemano, lo que permite cifrar carpetas de decenas de GB sin crear un TAR temporal.
 */
object Tar {
    const val BLOQUE = 512

    /** Los dos bloques vacíos que cierran un TAR. */
    fun fin(): ByteArray = ByteArray(2 * BLOQUE)

    fun relleno(n: Long): Int = ((BLOQUE - (n % BLOQUE)) % BLOQUE).toInt()

    /** Tamaño total que ocupa un archivo en el TAR: cabecera(s) + datos + relleno. */
    fun tamanoEntrada(nombre: String, tam: Long): Long = cabecera(nombre, tam, 0L).size.toLong() + tam + relleno(tam)

    /** Cabecera de un archivo normal; añade un registro PAX previo si el nombre pasa de 100 bytes o el tamaño de 8 GiB. */
    fun cabecera(nombre: String, tam: Long, mtimeSeg: Long): ByteArray {
        val nombreBytes = nombre.toByteArray(Charsets.UTF_8)
        val registros = StringBuilder()
        if (nombreBytes.size > 100) registros.append(registro("path", nombre))
        val cabe = tam <= 8589934591L
        if (!cabe) registros.append(registro("size", tam.toString()))
        val salida = java.io.ByteArrayOutputStream()
        if (registros.isNotEmpty()) {
            val datos = registros.toString().toByteArray(Charsets.UTF_8)
            salida.write(bloque("././@PaxHeader".toByteArray(Charsets.US_ASCII), datos.size.toLong(), 0L, 'x'))
            salida.write(datos)
            salida.write(ByteArray(relleno(datos.size.toLong())))
        }
        val nombreCorto = if (nombreBytes.size > 100) nombreBytes.copyOf(100) else nombreBytes
        salida.write(bloque(nombreCorto, if (cabe) tam else 0L, mtimeSeg, '0'))
        return salida.toByteArray()
    }

    /** «longitud clave=valor\n», donde la longitud cuenta el registro entero, incluida ella misma. */
    private fun registro(clave: String, valor: String): String {
        val cuerpo = " " + clave + "=" + valor + "\n"
        var largo = cuerpo.toByteArray(Charsets.UTF_8).size
        var total = largo + largo.toString().length
        while (total.toString().length + largo != total) {
            total = largo + total.toString().length
        }
        return total.toString() + cuerpo
    }

    private fun bloque(nombre: ByteArray, tam: Long, mtime: Long, tipo: Char): ByteArray {
        val b = ByteArray(BLOQUE)
        System.arraycopy(nombre, 0, b, 0, minOf(nombre.size, 100))
        octal(b, 100, 8, 420L)
        octal(b, 108, 8, 0L)
        octal(b, 116, 8, 0L)
        octal(b, 124, 12, tam)
        octal(b, 136, 12, mtime)
        for (i in 148 until 156) b[i] = ' '.code.toByte()
        b[156] = tipo.code.toByte()
        val magia = "ustar".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magia, 0, b, 257, magia.size)
        b[263] = '0'.code.toByte()
        b[264] = '0'.code.toByte()
        var suma = 0L
        for (x in b) suma += (x.toInt() and 0xFF)
        val txt = String.format("%06o", suma)
        for (i in 0 until 6) b[148 + i] = txt[i].code.toByte()
        b[154] = 0
        b[155] = ' '.code.toByte()
        return b
    }

    /** Escribe [valor] en octal con ceros a la izquierda, dejando el último byte a 0 (como hace tar). */
    private fun octal(b: ByteArray, pos: Int, largo: Int, valor: Long) {
        val txt = java.lang.Long.toOctalString(valor)
        val relleno = (largo - 1) - txt.length
        var i = 0
        while (i < relleno) {
            b[pos + i] = '0'.code.toByte()
            i++
        }
        for (k in txt.indices) b[pos + relleno + k] = txt[k].code.toByte()
        b[pos + largo - 1] = 0
    }

    /**
     * Lee exactamente [n] bytes de [entrada] o lanza EOFException. Útil para saltar datos.
     */
    fun saltar(entrada: InputStream, n: Long) {
        var resto = n
        val tmp = ByteArray(8192)
        while (resto > 0) {
            val s = entrada.skip(resto)
            if (s > 0) {
                resto -= s
            } else {
                val r = entrada.read(tmp, 0, minOf(tmp.size.toLong(), resto).toInt())
                if (r < 0) throw EOFException()
                resto -= r
            }
        }
    }

    /** Flujo de datos que entrega exactamente [tam] bytes: si el origen se acaba antes, falla; si sobra, lo ignora. */
    class Exacto(private val origen: InputStream, private val tam: Long, private val descripcion: String, private val alLeer: (Long) -> Unit) : InputStream() {
        private var leidos = 0L

        override fun read(): Int {
            if (leidos >= tam) return -1
            val v = origen.read()
            if (v < 0) throw IOException(descripcion + " cambió mientras se copiaba")
            leidos++
            alLeer(leidos)
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (leidos >= tam) return -1
            val pedir = minOf(len.toLong(), tam - leidos).toInt()
            val n = origen.read(b, off, pedir)
            if (n < 0) throw IOException(descripcion + " cambió mientras se copiaba")
            leidos += n
            alLeer(leidos)
            return n
        }

        override fun close() {
            origen.close()
        }
    }

    /** Une partes que se crean justo cuando hacen falta (así nunca hay dos archivos abiertos a la vez). */
    fun unir(partes: List<() -> InputStream>): InputStream {
        val e = object : Enumeration<InputStream> {
            var i = 0
            override fun hasMoreElements(): Boolean = i < partes.size
            override fun nextElement(): InputStream {
                val p = partes[i]
                i++
                return p()
            }
        }
        return SequenceInputStream(e)
    }

    fun bytes(b: ByteArray): InputStream = ByteArrayInputStream(b)

    fun vacio(): InputStream = ByteArrayInputStream(ByteArray(0))
}

/** Lector de TAR en streaming. Entiende ustar (con prefijo), PAX (path y size) y nombres largos GNU. */
class TarLector(private val entrada: InputStream) {

    class Entrada(val nombre: String, val tam: Long, val esArchivo: Boolean)

    private var restoDatos = 0L
    private var relleno = 0L

    /** Avanza a la siguiente entrada, o devuelve null al llegar al final. Los datos se leen con [leer]. */
    fun siguiente(): Entrada? {
        if (restoDatos + relleno > 0) {
            Tar.saltar(entrada, restoDatos + relleno)
            restoDatos = 0
            relleno = 0
        }
        var nombrePax: String? = null
        var tamPax = -1L
        while (true) {
            val cab = ByteArray(Tar.BLOQUE)
            if (!leerBloque(cab)) return null
            if (cab.all { it.toInt() == 0 }) return null
            comprobarSuma(cab)
            val tipo = cab[156].toInt().toChar()
            var tam = numero(cab, 124, 12)
            if (tipo == 'x' || tipo == 'g') {
                val datos = leerTodo(tam)
                if (tipo == 'x') {
                    for (r in registros(datos)) {
                        if (r.first == "path") nombrePax = r.second
                        if (r.first == "size") tamPax = r.second.toLongOrNull() ?: -1L
                    }
                }
                Tar.saltar(entrada, Tar.relleno(tam).toLong())
                continue
            }
            if (tipo == 'L') {
                val datos = leerTodo(tam)
                nombrePax = String(datos, Charsets.UTF_8).trimEnd(0.toChar())
                Tar.saltar(entrada, Tar.relleno(tam).toLong())
                continue
            }
            var nombre = nombrePax ?: texto(cab, 0, 100)
            if (nombrePax == null && String(cab, 257, 5, Charsets.US_ASCII) == "ustar") {
                val prefijo = texto(cab, 345, 155)
                if (prefijo.isNotEmpty()) nombre = prefijo + "/" + nombre
            }
            if (tamPax >= 0) tam = tamPax
            val archivo = tipo == '0' || cab[156].toInt() == 0
            restoDatos = tam
            relleno = Tar.relleno(tam).toLong()
            return Entrada(nombre, tam, archivo)
        }
    }

    /** Lee datos de la entrada actual; -1 cuando se acaban. */
    fun leer(b: ByteArray, off: Int, len: Int): Int {
        if (restoDatos <= 0) return -1
        val n = entrada.read(b, off, minOf(len.toLong(), restoDatos).toInt())
        if (n < 0) throw EOFException()
        restoDatos -= n
        return n
    }

    fun copiarA(salida: OutputStream, alCopiar: (Int) -> Unit) {
        val buffer = ByteArray(65536)
        while (true) {
            val n = leer(buffer, 0, buffer.size)
            if (n < 0) break
            salida.write(buffer, 0, n)
            alCopiar(n)
        }
    }

    private fun leerBloque(b: ByteArray): Boolean {
        var hecho = 0
        while (hecho < b.size) {
            val n = entrada.read(b, hecho, b.size - hecho)
            if (n < 0) {
                if (hecho == 0) return false
                throw EOFException()
            }
            hecho += n
        }
        return true
    }

    private fun leerTodo(n: Long): ByteArray {
        if (n < 0 || n > 1_048_576L) throw IOException("registro TAR demasiado grande")
        val b = ByteArray(n.toInt())
        var hecho = 0
        while (hecho < b.size) {
            val r = entrada.read(b, hecho, b.size - hecho)
            if (r < 0) throw EOFException()
            hecho += r
        }
        return b
    }

    private fun comprobarSuma(cab: ByteArray) {
        var suma = 0L
        for (i in cab.indices) {
            suma += if (i in 148..155) 32 else (cab[i].toInt() and 0xFF)
        }
        val guardada = numero(cab, 148, 8)
        if (guardada != suma) throw IOException("cabecera TAR dañada")
    }

    private fun texto(b: ByteArray, pos: Int, largo: Int): String {
        var fin = pos
        while (fin < pos + largo && b[fin].toInt() != 0) fin++
        return String(b, pos, fin - pos, Charsets.UTF_8)
    }

    private fun numero(b: ByteArray, pos: Int, largo: Int): Long {
        if ((b[pos].toInt() and 0x80) != 0) {
            var v = 0L
            for (i in 1 until largo) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        var empezado = false
        for (i in pos until pos + largo) {
            val c = b[i].toInt()
            if (c in '0'.code..'7'.code) {
                v = v * 8 + (c - '0'.code)
                empezado = true
            } else if (empezado || (c != ' '.code && c != 0)) {
                break
            }
        }
        return v
    }

    private fun registros(datos: ByteArray): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        var pos = 0
        while (pos < datos.size) {
            var sp = pos
            while (sp < datos.size && datos[sp].toInt() != ' '.code) sp++
            val largo = String(datos, pos, sp - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (largo <= 0 || pos + largo > datos.size) break
            val cuerpo = String(datos, sp + 1, pos + largo - sp - 2, Charsets.UTF_8)
            val ig = cuerpo.indexOf('=')
            if (ig > 0) res.add(Pair(cuerpo.substring(0, ig), cuerpo.substring(ig + 1)))
            pos += largo
        }
        return res
    }
}
