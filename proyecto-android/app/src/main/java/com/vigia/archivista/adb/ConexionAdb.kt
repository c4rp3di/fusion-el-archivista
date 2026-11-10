package com.vigia.archivista.adb

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * Gestor de la conexión ADB. La clave RSA y su certificado se crean la primera vez y se guardan cifrados: el emparejamiento
 * (que se hace una sola vez con el código de seis dígitos) queda ligado a ellos.
 */
class ConexionAdb private constructor(
    private val clavePrivada: PrivateKey,
    private val certificado: Certificate
) : AbsAdbConnectionManager() {

    init {
        // La versión de Android cuyo adbd se va a usar: la de este mismo teléfono.
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = clavePrivada

    override fun getCertificate(): Certificate = certificado

    override fun getDeviceName(): String = "Vigia"

    companion object {
        private const val CLAVE = "clave_privada"
        private const val CERT = "certificado"

        fun obtener(contexto: Context): ConexionAdb {
            val guardadaClave = CajaClaves.leer(contexto, CLAVE)
            val guardadaCert = CajaClaves.leer(contexto, CERT)
            if (guardadaClave != null && guardadaCert != null) {
                try {
                    val privada = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(guardadaClave))
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(guardadaCert))
                    return ConexionAdb(privada, cert)
                } catch (e: Exception) {
                    // Si no se pueden leer, se crean nuevas (habrá que emparejar otra vez).
                }
            }
            return crear(contexto)
        }

        private fun crear(contexto: Context): ConexionAdb {
            val generador = KeyPairGenerator.getInstance("RSA")
            generador.initialize(2048, SecureRandom())
            val par = generador.generateKeyPair()
            val publica = par.public
            val privada = par.private

            val algoritmo = "SHA512withRSA"
            val ahora = Date()
            val caduca = Date(System.currentTimeMillis() + 3650L * 86_400_000L)
            val sujeto = X500Name("CN=Vigia")

            val extensiones = CertificateExtensions()
            extensiones.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publica).getIdentifier()))
            extensiones.set("PrivateKeyUsage", PrivateKeyUsageExtension(ahora, caduca))

            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algoritmo)))
            info.set("subject", CertificateSubjectName(sujeto))
            info.set("key", CertificateX509Key(publica))
            info.set("validity", CertificateValidity(ahora, caduca))
            info.set("issuer", CertificateIssuerName(sujeto))
            info.set("extensions", extensiones)
            val cert = X509CertImpl(info)
            cert.sign(privada, algoritmo)

            CajaClaves.guardar(contexto, CLAVE, privada.encoded)
            CajaClaves.guardar(contexto, CERT, cert.encoded)
            return ConexionAdb(privada, cert)
        }
    }
}
