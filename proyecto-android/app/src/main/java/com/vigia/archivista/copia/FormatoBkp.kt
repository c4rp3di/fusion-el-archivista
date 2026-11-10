package com.vigia.archivista.copia

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
 * Paquete (ya descifrado), versión 2: «VBK2» (4) · número de archivos (4) · por cada archivo:
 *   paquete o ruta (UTF) · tipo (4: 1 APK, 2 datos externos, 3 Xiaomi, 4 datos internos, 5 archivos personales)
 *   · etiqueta (UTF) · nombre del archivo (UTF) · tamaño (8) · bytes del archivo.
 * La versión 1 («VBK1», solo APK) no tiene el campo tipo; se sigue pudiendo leer.
 *
 * La cabecera mágica solo sirve para no intentar abrir archivos ajenos; la integridad y la contraseña las
 * comprueba AES-GCM bloque a bloque.
 */
object FormatoBkp {
    const val MAGIA = "VIGIABKP1"
    const val MAGIA_INTERNA_V1 = 0x56424B31
    const val MAGIA_INTERNA = 0x56424B32
    const val MAX_ARCHIVOS = 20000
    const val EXTENSION = ".bkp"
    const val MIME = "application/octet-stream"

    private val MAGIA_BYTES = MAGIA.toByteArray(Charsets.US_ASCII)

    class Cabecera(val iteraciones: Int, val sal: ByteArray)

    /** Descripción de un archivo dentro del paquete (sin sus bytes). */
    class Entrada(val clave: String, val tipo: TipoArchivo, val etiqueta: String, val nombre: String, val tamano: Long)

    /** Bytes del preámbulo del paquete: magia interna y número de archivos. */
    fun preambulo(cantidad: Int): ByteArray {
        return ByteBuffer.allocate(8).putInt(MAGIA_INTERNA).putInt(cantidad).array()
    }

    /** Bytes de la cabecera de un archivo; después van exactamente [Entrada.tamano] bytes de datos. */
    fun cabeceraEntrada(e: Entrada): ByteArray {
        val buf = ByteArrayOutputStream()
        val d = DataOutputStream(buf)
        d.writeUTF(e.clave)
        d.writeInt(e.tipo.codigo)
        d.writeUTF(e.etiqueta)
        d.writeUTF(e.nombre)
        d.writeLong(e.tamano)
        d.flush()
        return buf.toByteArray()
    }

    /** Lee la cabecera de un archivo del paquete. [v2] false = formato VBK1 (siempre APK). */
    fun leerEntrada(d: DataInputStream, v2: Boolean): Entrada {
        val clave = d.readUTF()
        var tipo = TipoArchivo.APK
        if (v2) {
            tipo = TipoArchivo.de(d.readInt()) ?: throw IllegalArgumentException("tipo de archivo desconocido")
        }
        val etiqueta = d.readUTF()
        val nombre = d.readUTF()
        val tam = d.readLong()
        if (tam < 0) throw IllegalArgumentException("tamaño no válido")
        return Entrada(clave, tipo, etiqueta, nombre, tam)
    }

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

/** Qué contiene cada archivo del paquete. */
enum class TipoArchivo(val codigo: Int) {
    APK(1),
    DATOS_EXTERNOS(2),
    BAK_XIAOMI(3),
    DATOS_INTERNOS(4),
    ARCHIVO_PERSONAL(5);

    companion object {
        fun de(codigo: Int): TipoArchivo? {
            for (t in values()) {
                if (t.codigo == codigo) return t
            }
            return null
        }
    }
}
