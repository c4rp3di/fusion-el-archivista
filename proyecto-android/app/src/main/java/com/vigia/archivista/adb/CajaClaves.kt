package com.vigia.archivista.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Guarda las claves del Modo avanzado cifradas con una clave AES del Keystore (que no sale del hardware seguro). */
object CajaClaves {
    private const val ALIAS = "vigia_adb_caja"
    private const val PREFS = "vigia_adb"

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(ALIAS)) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
        return ks.getKey(ALIAS, null) as SecretKey
    }

    fun guardar(contexto: Context, nombre: String, datos: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, clave())
        val iv = c.iv
        val cifrado = c.doFinal(datos)
        val todo = iv + cifrado
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(nombre, Base64.encodeToString(todo, Base64.NO_WRAP)).apply()
    }

    fun leer(contexto: Context, nombre: String): ByteArray? {
        val texto = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(nombre, null) ?: return null
        return try {
            val todo = Base64.decode(texto, Base64.NO_WRAP)
            val iv = todo.copyOfRange(0, 12)
            val cifrado = todo.copyOfRange(12, todo.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, iv))
            c.doFinal(cifrado)
        } catch (e: Exception) {
            null
        }
    }

    fun borrarTodo(contexto: Context) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
