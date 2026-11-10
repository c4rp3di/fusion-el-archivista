package com.vigia.archivista.copia

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.IOException

/** Un archivo dentro de una carpeta elegida con el selector del sistema. */
class ArchivoSaf(val ruta: String, val uri: Uri, val tamano: Long, val modificado: Long)

/** Recorre y escribe carpetas del Storage Access Framework con DocumentsContract (sin librerías extra). */
object ArbolSaf {

    fun nombreRaiz(c: Context, arbol: Uri): String {
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))
            c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            } ?: "carpeta"
        } catch (e: Exception) {
            "carpeta"
        }
    }

    /** Lista todos los archivos de la carpeta (sin carpetas vacías), con su ruta relativa y separada por «/». */
    fun listar(c: Context, arbol: Uri, alContar: (Int) -> Unit): List<ArchivoSaf> {
        val res = ArrayList<ArchivoSaf>()
        val pendientes = ArrayList<Pair<String, String>>()
        pendientes.add(Pair(DocumentsContract.getTreeDocumentId(arbol), ""))
        val columnas = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        while (pendientes.isNotEmpty()) {
            val actual = pendientes.removeAt(pendientes.size - 1)
            val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, actual.first)
            c.contentResolver.query(hijos, columnas, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(0)
                    val nombre = cur.getString(1) ?: continue
                    val mime = cur.getString(2) ?: ""
                    val ruta = if (actual.second.isEmpty()) nombre else actual.second + "/" + nombre
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pendientes.add(Pair(id, ruta))
                    } else {
                        val tam = if (cur.isNull(3)) -1L else cur.getLong(3)
                        val fecha = if (cur.isNull(4)) 0L else cur.getLong(4)
                        res.add(ArchivoSaf(ruta, DocumentsContract.buildDocumentUriUsingTree(arbol, id), tam, fecha))
                        if (res.size % 200 == 0) alContar(res.size)
                    }
                }
            }
        }
        res.sortBy { it.ruta }
        return res
    }

    /** Crea (o reutiliza) las carpetas de [ruta] bajo la raíz [arbol] y devuelve el documento de la última. */
    class Escritor(private val c: Context, private val arbol: Uri) {
        private val carpetas = HashMap<String, Uri>()

        init {
            carpetas[""] = DocumentsContract.buildDocumentUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))
        }

        private fun carpeta(ruta: String): Uri {
            val ya = carpetas[ruta]
            if (ya != null) return ya
            val padre = carpeta(ruta.substringBeforeLast('/', ""))
            val nombre = ruta.substringAfterLast('/')
            val nuevo = buscarHijo(padre, nombre, true)
                ?: DocumentsContract.createDocument(c.contentResolver, padre, DocumentsContract.Document.MIME_TYPE_DIR, nombre)
                ?: throw IOException("no se pudo crear la carpeta " + nombre)
            carpetas[ruta] = nuevo
            return nuevo
        }

        private fun buscarHijo(padre: Uri, nombre: String, soloCarpeta: Boolean): Uri? {
            val id = DocumentsContract.getDocumentId(padre)
            val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, id)
            c.contentResolver.query(
                hijos,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == nombre) {
                        val esDir = cur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        if (esDir == soloCarpeta) return DocumentsContract.buildDocumentUriUsingTree(arbol, cur.getString(0))
                    }
                }
            }
            return null
        }

        /** Crea el archivo [ruta] (con sus carpetas) y devuelve su documento; el sistema renombra si ya existe uno igual. */
        fun crearArchivo(ruta: String): Uri {
            val padre = carpeta(ruta.substringBeforeLast('/', ""))
            val nombre = ruta.substringAfterLast('/')
            val ext = nombre.substringAfterLast('.', "").lowercase()
            val mime = (if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)) ?: "application/octet-stream"
            return DocumentsContract.createDocument(c.contentResolver, padre, mime, nombre)
                ?: throw IOException("no se pudo crear " + nombre)
        }
    }
}
