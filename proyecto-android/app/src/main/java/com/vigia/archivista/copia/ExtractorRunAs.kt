package com.vigia.archivista.copia

import android.content.Context
import java.io.File

/**
 * Datos internos (/data/data/<paquete>) de apps depurables, con «run-as». Solo funciona con apps marcadas como depurables
 * (las que compilas tú en modo debug): Android no permite run-as sobre las demás.
 */
object ExtractorRunAs {

    /** El shell redirige la salida de «tar» a un archivo de esta app (la redirección la hace el shell, no la app destino). */
    fun capturar(c: Context, paquete: String, aviso: (String) -> Unit): Fuente {
        try {
            if (!AdbArchivos.PAQUETE_VALIDO.matches(paquete)) return AdbArchivos.vacia()
            val salida = File(AdbArchivos.dirTemporal(c), paquete + "_data.tgz")
            salida.delete()
            val r = AdbArchivos.ejecutarBloqueando("run-as " + paquete + " tar czf - . > " + AdbArchivos.q(salida.path))
            if (r.codigo != 0 || !salida.exists() || salida.length() == 0L) {
                salida.delete()
                aviso(paquete + ": no se pudieron copiar sus datos internos (" + ExtractorDatosExternos.primeraLinea(r.salida) + ")")
                return AdbArchivos.vacia()
            }
            return Fuente(salida.length(), AdbArchivos.BorrarAlCerrar(salida))
        } catch (e: Exception) {
            aviso(paquete + ": no se pudieron copiar sus datos internos (" + (e.message ?: e.javaClass.simpleName) + ")")
            return AdbArchivos.vacia()
        }
    }

    /** null si todo fue bien; si no, el motivo. La app debe estar instalada y ser depurable. */
    suspend fun restaurar(paquete: String, tgz: File): String? {
        if (!AdbArchivos.PAQUETE_VALIDO.matches(paquete)) return "nombre de paquete no válido"
        val problema = ExtractorDatosExternos.validar(tgz, null)
        if (problema != null) return problema
        val opcion = if (AdbArchivos.esGzip(tgz)) "xzf" else "xf"
        val r = AdbArchivos.ejecutar("cat " + AdbArchivos.q(tgz.path) + " | run-as " + paquete + " tar " + opcion + " -")
        return if (r.codigo == 0) null else ExtractorDatosExternos.primeraLinea(r.salida)
    }
}
