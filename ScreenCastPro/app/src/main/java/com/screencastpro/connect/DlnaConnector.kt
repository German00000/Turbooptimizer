package com.screencastpro.connect

import com.screencastpro.core.Upnp
import com.screencastpro.net.Http

/**
 * Control DLNA/UPnP (servicio AVTransport). Se ejecuta siempre fuera del hilo principal.
 * El televisor abre la URL HTTP del teléfono con su propio reproductor de MPEG-TS.
 */
object DlnaConnector {

    fun load(avTransportUrl: String, streamUrl: String, title: String) {
        val (setAction, setBody) = Upnp.setAvTransportUri(streamUrl, title)
        Http.soap(avTransportUrl, Upnp.soapActionHeader(setAction), setBody)
        val (playAction, playBody) = Upnp.play()
        Http.soap(avTransportUrl, Upnp.soapActionHeader(playAction), playBody)
    }

    fun stop(avTransportUrl: String) {
        val (action, body) = Upnp.stop()
        Http.soap(avTransportUrl, Upnp.soapActionHeader(action), body)
    }

    /** Estado de transporte: PLAYING, STOPPED, NO_MEDIA_PRESENT, TRANSITIONING… */
    fun transportState(avTransportUrl: String): String? {
        val (action, body) = Upnp.getTransportInfo()
        return Upnp.parseTransportState(Http.soap(avTransportUrl, Upnp.soapActionHeader(action), body))
    }
}
