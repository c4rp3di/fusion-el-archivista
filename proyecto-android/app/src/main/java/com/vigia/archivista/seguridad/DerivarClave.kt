package com.vigia.archivista.seguridad

import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Convierte la contraseña de la persona en la clave maestra de 32 bytes que espera [CifradoFlujo].
 * Es el mismo PBKDF2-HMAC-SHA256 de La Bóveda (310 000 iteraciones), con HMAC estándar para que funcione igual en
 * todas las versiones de Android. La sal y las iteraciones se guardan en la cabecera del .bkp.
 */
object DerivarClave {
    const val ITERACIONES = 310_000
    const val TAM_SAL = 16

    fun nuevaSal(): ByteArray {
        val sal = ByteArray(TAM_SAL)
        SecureRandom().nextBytes(sal)
        return sal
    }

    /** Devuelve 32 bytes. Quien llama debe rellenarlos con ceros cuando termine (clave.fill(0)). */
    fun derivar(contrasena: CharArray, sal: ByteArray, iteraciones: Int): ByteArray {
        val codificada = Charsets.UTF_8.encode(CharBuffer.wrap(contrasena))
        val clavePwd = ByteArray(codificada.remaining())
        codificada.get(clavePwd)
        if (codificada.hasArray()) codificada.array().fill(0)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(clavePwd, "HmacSHA256"))
            mac.update(sal)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iteraciones) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            return t
        } finally {
            clavePwd.fill(0)
        }
    }
}
