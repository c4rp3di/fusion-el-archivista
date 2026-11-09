package com.vigia.archivista.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * Modo avanzado: ejecuta órdenes del shell de ADB en este mismo teléfono (depuración inalámbrica, Android 11 o superior).
 * Solo se usan las funciones que existen en la versión fijada de la librería; el puerto de conexión se descubre con el
 * servicio de nombres de red de Android (mDNS) o se escribe a mano.
 */
object AdbInalambricoShell {
    @Volatile
    private var conexion: ConexionAdb? = null

    @Volatile
    var conectado: Boolean = false
        private set

    fun disponible(): Boolean = Build.VERSION.SDK_INT >= 30

    /** Hay una conexión creada (aunque ahora mismo pudiera no responder). */
    fun hayConexion(): Boolean = conexion != null

    private fun obtener(contexto: Context): ConexionAdb {
        val actual = conexion
        if (actual != null) return actual
        synchronized(this) {
            val c = conexion ?: ConexionAdb.obtener(contexto.applicationContext)
            conexion = c
            return c
        }
    }

    /** Empareja con el código de seis dígitos y el puerto que muestra Ajustes › Depuración inalámbrica. */
    suspend fun emparejar(contexto: Context, puerto: Int, codigo: String): Boolean = withContext(Dispatchers.IO) {
        obtener(contexto).pair("127.0.0.1", puerto, codigo)
    }

    /**
     * Se conecta al puerto indicado o, si no se da, al que anuncia este mismo teléfono. Devuelve true si la conexión
     * queda funcionando. Lanza una excepción con el motivo si no se puede.
     */
    suspend fun conectar(contexto: Context, puertoManual: Int?): Boolean = withContext(Dispatchers.IO) {
        val m = obtener(contexto)
        var puerto = puertoManual
        var hostAnunciado: String? = null
        if (puerto == null) {
            val encontrado = descubrir(contexto.applicationContext, 8000L)
            if (encontrado == null) {
                conectado = false
                throw IllegalStateException(
                    "No se encontró el puerto de conexión. Escríbelo a mano: está en Depuración inalámbrica, junto a «Dirección IP y puerto»."
                )
            }
            hostAnunciado = encontrado.first
            puerto = encontrado.second
        }
        val hosts = listOfNotNull("127.0.0.1", hostAnunciado).distinct()
        var ok = false
        var ultimoError: Exception? = null
        for (h in hosts) {
            try {
                if (conectarEn(m, h, puerto)) {
                    ok = true
                    break
                }
            } catch (e: Exception) {
                ultimoError = e
            }
        }
        if (!ok) {
            conectado = false
            if (ultimoError != null) throw ultimoError
            return@withContext false
        }
        conectado = comprobar()
        conectado
    }

    /** Ejecuta una orden de shell y devuelve lo que imprime. Lanza una excepción si la conexión no funciona. */
    suspend fun ejecutar(orden: String): String = withContext(Dispatchers.IO) {
        val m = conexion ?: throw IllegalStateException("Modo avanzado sin conectar")
        try {
            ejecutarEn(m, orden)
        } catch (e: Exception) {
            conectado = false
            throw e
        }
    }

    /**
     * La librería lanza una excepción si el shell termina y cierra el flujo antes de que se lea su salida (una carrera que
     * afecta sobre todo a órdenes rápidas). Se evita manteniendo el shell un segundo más y tratando el cierre del flujo
     * como fin normal de la lectura.
     */
    private fun ejecutarEn(m: AbsAdbConnectionManager, orden: String): String {
        val flujo = m.openStream("shell:" + orden + "; sleep 1")
        try {
            val entrada = flujo.openInputStream()
            val salida = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = entrada.read(buffer)
                    if (n < 0) break
                    salida.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                // El flujo se cerró: es el final de la salida.
            }
            return salida.toString("UTF-8").trim()
        } finally {
            try {
                flujo.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun comprobar(): Boolean {
        val m = conexion ?: return false
        return try {
            ejecutarEn(m, "echo vigia").contains("vigia")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun verificar(): Boolean = withContext(Dispatchers.IO) {
        conectado = if (conexion == null) false else comprobar()
        conectado
    }

    /** connect(host, puerto) por reflexión: si la versión de la librería no lo tiene, el error lista lo que sí ofrece. */
    private fun conectarEn(m: AbsAdbConnectionManager, host: String, puerto: Int): Boolean {
        try {
            val metodo = m.javaClass.getMethod("connect", String::class.java, Integer.TYPE)
            return metodo.invoke(m, host, puerto) as Boolean
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("La librería no tiene connect(host, puerto). Ofrece: " + metodosDe(m))
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    private fun metodosDe(m: AbsAdbConnectionManager): String {
        return m.javaClass.methods.map { it.name }
            .filter { it.contains("onnect") || it.contains("air") || it.contains("pen") || it.contains("tream") }
            .distinct().sorted().joinToString(", ")
    }

    /** Busca el servicio «_adb-tls-connect» que anuncia este mismo teléfono. Devuelve (dirección, puerto). */
    @Suppress("DEPRECATION")
    private suspend fun descubrir(contexto: Context, esperaMs: Long): Pair<String, Int>? {
        val nsd = contexto.getSystemService(Context.NSD_SERVICE) as NsdManager
        val locales = direccionesLocales()
        var descubridor: NsdManager.DiscoveryListener? = null
        try {
            return withTimeoutOrNull(esperaMs) {
                suspendCancellableCoroutine<Pair<String, Int>?> { cont ->
                    val resolutor = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = (info.host?.hostAddress ?: "").substringBefore('%')
                            if (host.isNotEmpty() && locales.contains(host) && cont.isActive) {
                                cont.resume(Pair(host, info.port))
                            }
                        }
                    }
                    val d = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(tipo: String, error: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onStopDiscoveryFailed(tipo: String, error: Int) {
                        }

                        override fun onDiscoveryStarted(tipo: String) {
                        }

                        override fun onDiscoveryStopped(tipo: String) {
                        }

                        override fun onServiceFound(info: NsdServiceInfo) {
                            try {
                                nsd.resolveService(info, resolutor)
                            } catch (e: Exception) {
                            }
                        }

                        override fun onServiceLost(info: NsdServiceInfo) {
                        }
                    }
                    descubridor = d
                    nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, d)
                }
            }
        } finally {
            val d = descubridor
            if (d != null) {
                try {
                    nsd.stopServiceDiscovery(d)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun direccionesLocales(): Set<String> {
        val res = HashSet<String>()
        res.add("127.0.0.1")
        res.add("::1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val direcciones = interfaces.nextElement().inetAddresses
                while (direcciones.hasMoreElements()) {
                    val d = direcciones.nextElement().hostAddress ?: continue
                    res.add(d.substringBefore('%'))
                }
            }
        } catch (e: Exception) {
        }
        return res
    }

    fun olvidar(contexto: Context) {
        conexion = null
        conectado = false
        CajaClaves.borrarTodo(contexto.applicationContext)
    }
}
