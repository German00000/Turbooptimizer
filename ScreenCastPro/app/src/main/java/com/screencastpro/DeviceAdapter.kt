package com.screencastpro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.screencastpro.discovery.Protocol
import com.screencastpro.discovery.TvDevice

/** Lista «Dispositivos encontrados» con el botón Conectar / Desconectar por televisor. */
class DeviceAdapter(
    private val onAction: (device: TvDevice, protocol: Protocol, connected: Boolean) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private var items: List<TvDevice> = emptyList()

    /** IP del televisor conectado: su botón pasa a «Desconectar». */
    var connectedHost: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<TvDevice>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val protocols: TextView = view.findViewById(R.id.deviceProtocols)
        val button: MaterialButton = view.findViewById(R.id.connectButton)
        val alt: MaterialButton = view.findViewById(R.id.altButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val device = items[position]
        val connectedHere = device.host == connectedHost
        holder.name.text = device.name
        holder.protocols.text = device.protocolsLabel

        // Protocolo principal: Cast si existe, si no DLNA. Si hay ambos, el otro va en el segundo botón.
        val primary = device.preferredProtocol
        val secondary = device.protocols.firstOrNull { it != primary }
        holder.button.text = if (connectedHere) holder.button.context.getString(R.string.disconnect)
            else "${holder.button.context.getString(R.string.connect)} ${primary.label}"
        holder.button.setOnClickListener { onAction(device, primary, connectedHere) }

        if (secondary != null) {
            holder.alt.visibility = android.view.View.VISIBLE
            holder.alt.text = "${holder.alt.context.getString(R.string.connect)} ${secondary.label}"
            holder.alt.setOnClickListener { onAction(device, secondary, false) }
        } else {
            holder.alt.visibility = android.view.View.GONE
        }
    }
}
