package com.vigia.archivista.copia

import java.io.InputStream
import java.io.OutputStream

/** Salida que cuenta los bytes escritos y avisa en cada escritura; el aviso puede lanzar una excepción para cancelar. */
class SalidaControlada(private val destino: OutputStream, private val alEscribir: (Long) -> Unit) : OutputStream() {
    private var total = 0L

    override fun write(b: Int) {
        destino.write(b)
        total++
        alEscribir(total)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        destino.write(b, off, len)
        total += len
        alEscribir(total)
    }

    override fun flush() {
        destino.flush()
    }

    override fun close() {
        destino.close()
    }
}

/** Entrada que cuenta los bytes leídos y avisa en cada lectura; el aviso puede lanzar una excepción para cancelar. */
class EntradaControlada(private val origen: InputStream, private val alLeer: (Long) -> Unit) : InputStream() {
    private var total = 0L

    override fun read(): Int {
        val v = origen.read()
        if (v >= 0) {
            total++
            alLeer(total)
        }
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = origen.read(b, off, len)
        if (n > 0) {
            total += n
            alLeer(total)
        }
        return n
    }

    override fun close() {
        origen.close()
    }
}

/** Progreso de una operación larga: [fase] es un recurso de texto, [fraccion] va de 0 a 1 (negativa = sin medir). */
class Progreso(val fase: Int, val detalle: String, val fraccion: Float)

/** Entrada que cuenta cuántos bytes se han consumido (leídos o saltados): sirve para saber dónde empieza cada archivo. */
class EntradaContada(private val origen: InputStream) : InputStream() {
    var posicion = 0L
        private set

    override fun read(): Int {
        val v = origen.read()
        if (v >= 0) posicion++
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = origen.read(b, off, len)
        if (n > 0) posicion += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = origen.skip(n)
        if (s > 0) posicion += s
        return s
    }

    override fun close() {
        origen.close()
    }
}
