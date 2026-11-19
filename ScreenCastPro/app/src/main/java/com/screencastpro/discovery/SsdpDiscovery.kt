package com.screencastpro.discovery

import com.screencastpro.DiagLog
import com.screencastpro.core.Upnp
import com.screencastpro.net.Lan
import com.screencastpro.net.Http
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI

/** Búsqueda SSDP (UPnP) de renderizadores DLNA en la red local. */
object SsdpDiscovery {

    /** Envía M-SEARCH y devuelve los LOCATION de las respuestas recibidas en [timeoutMs]. */
    fun search(timeoutMs: Int = 2500): List<String> {
        val locations = LinkedHashSet<String>()
        val request = Upnp.msearchRequest(mxSeconds = 2).toByteArray(Charsets.US_ASCII)
        val group = InetAddress.getByName(Upnp.SSDP_ADDRESS)
        MulticastSocket().use { socket ->
            // Multicast por la interfaz Wi-Fi: con datos móviles, la ruta por defecto puede no llegar a la LAN.
            Lan.wifiIpv4()?.let { socket.setInterface(InetAddress.getByName(it)) }
            socket.soTimeout = 300
            repeat(2) { // UDP no es fiable: dos envíos
                socket.send(DatagramPacket(request, request.size, group, Upnp.SSDP_PORT))
            }
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    Upnp.parseSsdpHeaders(text)["LOCATION"]?.let { locations += it }
                } catch (_: SocketTimeoutException) {
                    // siguiente intento hasta el plazo
                }
            }
        }
        return locations.toList()
    }

    /** Descarga la descripción del dispositivo y extrae su TV/renderizador si expone AVTransport. */
    fun describe(location: String): TvDevice? {
        return try {
            val xml = Http.get(location)
            val info = Upnp.parseDeviceDescription(xml, location)
            val control = info.avTransportControlUrl ?: return null
            val host = URI(location).host ?: return null
            TvDevice(
                host = host,
                name = info.friendlyName.ifBlank {
                    listOf(info.manufacturer, info.modelName).filter { it.isNotBlank() }
                        .joinToString(" ").ifBlank { host }
                },
                protocols = setOf(Protocol.DLNA),
                avTransportUrl = control,
            )
        } catch (_: IOException) {
            null
        } catch (e: Exception) {
            DiagLog.w("SSDP", "descripción no válida (${e.javaClass.simpleName}) en $location")
            null // XML malformado u otro dispositivo no compatible
        }
    }
}
