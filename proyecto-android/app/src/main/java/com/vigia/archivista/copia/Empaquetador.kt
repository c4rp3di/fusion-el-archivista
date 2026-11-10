package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.vigia.archivista.R
import com.vigia.archivista.seguridad.CifradoFlujo
import com.vigia.archivista.seguridad.DerivarClave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Datos de un archivo ya listos para leer: su tamaño exacto y el flujo de sus bytes. */
class Fuente(val tamano: Long, val flujo: InputStream)

/**
 * Un archivo del .bkp. [abrir] se llama justo antes de leerlo (a veces prepara datos con el shell), de modo que nunca
 * hay más de un archivo en preparación a la vez. [tamanoPrevisto] solo sirve para la barra de progreso.
 */
class Item(
    val clave: String,
    val tipo: TipoArchivo,
    val etiqueta: String,
    val nombre: String,
    val tamanoPrevisto: Long,
    val abrir: () -> Fuente
)

/**
 * Construye el paquete (formato VBK2) en streaming, lo cifra con CifradoFlujo y lo escribe en el destino. No hay
 * contenedor temporal: los bytes pasan del origen al cifrado directamente, así que no se necesita espacio extra.
 */
object Empaquetador {

    /** @return el tamaño final del archivo .bkp, o -1 si el proveedor no lo dice. */
    suspend fun cifrarA(
        c: Context,
        items: List<Item>,
        contrasena: CharArray,
        destino: Uri,
        alProgreso: (Progreso) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val trabajo = coroutineContext.job
        var correcto = false
        var clave: ByteArray? = null
        val pendientes = arrayOfNulls<Fuente>(items.size)
        try {
            alProgreso(Progreso(R.string.fase_derivando, "", -1f))
            val sal = DerivarClave.nuevaSal()
            val k = DerivarClave.derivar(contrasena, sal, DerivarClave.ITERACIONES)
            clave = k
            trabajo.ensureActive()

            var ultimoAviso = 0L
            val partes = ArrayList<() -> InputStream>()
            partes.add { Tar.bytes(FormatoBkp.preambulo(items.size)) }
            for (i in items.indices) {
                val item = items[i]
                partes.add {
                    trabajo.ensureActive()
                    alProgreso(Progreso(R.string.fase_preparando, item.etiqueta, (i.toFloat()) / items.size))
                    val f = item.abrir()
                    pendientes[i] = f
                    Tar.bytes(FormatoBkp.cabeceraEntrada(FormatoBkp.Entrada(item.clave, item.tipo, item.etiqueta, item.nombre, f.tamano)))
                }
                partes.add {
                    val f = pendientes[i] ?: throw IOException("archivo no preparado")
                    pendientes[i] = null
                    Tar.Exacto(f.flujo, f.tamano, item.etiqueta) { leidos ->
                        trabajo.ensureActive()
                        if (leidos - ultimoAviso > 262144L || leidos == f.tamano) {
                            ultimoAviso = leidos
                            val dentro = if (f.tamano > 0) leidos.toFloat() / f.tamano else 1f
                            alProgreso(Progreso(R.string.fase_cifrando, item.etiqueta, (i + dentro) / items.size))
                        }
                    }
                }
            }

            val salidaSistema = c.contentResolver.openOutputStream(destino, "wt")
                ?: throw IOException("no se pudo abrir el destino")
            val salida = BufferedOutputStream(salidaSistema, 65536)
            var cifradoOk = false
            try {
                FormatoBkp.escribirCabecera(salida, DerivarClave.ITERACIONES, sal)
                val entrada = BufferedInputStream(Tar.unir(partes), 65536)
                try {
                    CifradoFlujo.cifrar(entrada, salida, k)
                } finally {
                    try {
                        entrada.close()
                    } catch (e: Exception) {
                        // Ya cerrado.
                    }
                }
                salida.flush()
                cifradoOk = true
            } finally {
                try {
                    salida.close()
                } catch (e: IOException) {
                    // Un fallo al cerrar solo importa si el cifrado terminó bien: la copia podría estar incompleta.
                    if (cifradoOk) throw e
                }
            }
            val tam = try {
                c.contentResolver.openFileDescriptor(destino, "r")?.use { it.statSize } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            correcto = true
            tam
        } finally {
            clave?.fill(0)
            for (f in pendientes) {
                try {
                    f?.flujo?.close()
                } catch (e: Exception) {
                    // Nada que hacer.
                }
            }
            if (!correcto) {
                try {
                    DocumentsContract.deleteDocument(c.contentResolver, destino)
                } catch (e: Exception) {
                    // El documento puede no existir aún o el proveedor no permitir borrarlo.
                }
            }
        }
    }
}
