package com.vigia.archivista.copia

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Formato del archivo .bkp:
 *
 *   «VIGIABKP1» (9 bytes ASCII, en claro) · iteraciones PBKDF2 (4, big endian) · sal (16)
 *   · flujo cifrado de CifradoFlujo («VCF1»…) que contiene el paquete de APK.
 *
 * Paquete de APK (ya descifrado): «VBK1» (4) · número de archivos (4) · por cada archivo:
 *   paquete (UTF) · nombre de la app (UTF) · nombre del archivo .apk (UTF) · tamaño (8) · bytes del APK.
 *
 * La cabecera mágica solo sirve para no intentar abrir archivos ajenos; la integridad y la contraseña las
 * comprueba AES-GCM bloque a bloque.
 */
object FormatoBkp {
    const val MAGIA = "VIGIABKP1"
    const val MAGIA_INTERNA = 0x56424B31
    const val MAX_ARCHIVOS = 20000
    const val EXTENSION = ".bkp"
    const val MIME = "application/octet-stream"

    private val MAGIA_BYTES = MAGIA.toByteArray(Charsets.US_ASCII)

    class Cabecera(val iteraciones: Int, val sal: ByteArray)

    fun escribirCabecera(salida: OutputStream, iteraciones: Int, sal: ByteArray) {
        salida.write(MAGIA_BYTES)
        salida.write(ByteBuffer.allocate(4).putInt(iteraciones).array())
        salida.write(sal)
    }

    /**
     * Lee y valida la cabecera dejando [entrada] justo al comienzo del flujo cifrado.
     * @throws IllegalArgumentException si el archivo no es una copia de El Archivista.
     */
    fun leerCabecera(entrada: InputStream): Cabecera {
        val d = DataInputStream(entrada)
        try {
            val magia = ByteArray(MAGIA_BYTES.size)
            d.readFully(magia)
            if (!magia.contentEquals(MAGIA_BYTES)) throw IllegalArgumentException("no es una copia de El Archivista")
            val iter = d.readInt()
            if (iter < 10_000 || iter > 5_000_000) throw IllegalArgumentException("parámetros de cifrado no válidos")
            val sal = ByteArray(16)
            d.readFully(sal)
            return Cabecera(iter, sal)
        } catch (e: EOFException) {
            throw IllegalArgumentException("no es una copia de El Archivista")
        }
    }

    /** Comprobación rápida (solo lee los 9 primeros bytes) para avisar antes de pedir la contraseña. */
    fun esCopiaValida(entrada: InputStream): Boolean {
        return try {
            leerCabecera(entrada)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
