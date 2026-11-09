package com.vigia.archivista

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vigia.archivista.copia.AppInfo
import com.vigia.archivista.core.Formato

/** Lista de apps con casilla. [seleccion] guarda los nombres de paquete marcados; [alCambiar] avisa tras cada cambio. */
class AdaptadorApps(
    private val apps: List<AppInfo>,
    private val seleccion: MutableSet<String>,
    private val alCambiar: () -> Unit
) : RecyclerView.Adapter<AdaptadorApps.Fila>() {

    class Fila(vista: View) : RecyclerView.ViewHolder(vista) {
        val icono: ImageView = vista.findViewById(R.id.appIcono)
        val nombre: TextView = vista.findViewById(R.id.appNombre)
        val detalle: TextView = vista.findViewById(R.id.appDetalle)
        val marca: CheckBox = vista.findViewById(R.id.appMarca)
    }

    override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): Fila {
        val vista = LayoutInflater.from(padre.context).inflate(R.layout.item_app, padre, false)
        return Fila(vista)
    }

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(fila: Fila, posicion: Int) {
        val app = apps[posicion]
        fila.icono.setImageDrawable(app.icono)
        fila.nombre.text = app.etiqueta
        fila.detalle.text = fila.itemView.context.getString(R.string.detalle_app, app.paquete, Formato.bytes(app.tamano))
        fila.marca.isChecked = seleccion.contains(app.paquete)
        fila.itemView.setOnClickListener {
            if (seleccion.contains(app.paquete)) seleccion.remove(app.paquete) else seleccion.add(app.paquete)
            fila.marca.isChecked = seleccion.contains(app.paquete)
            alCambiar()
        }
    }
}
