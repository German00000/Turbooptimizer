package com.screencastpro.connect

import com.screencastpro.core.Upnp
import com.screencastpro.net.Http

/**
 * Control DLNA/UPnP (servicio AVTransport). Se ejecuta siempre fuera del hilo principal.
 * El televisor abre la URL HTTP del teléfono con su propio reproductor de MPEG-TS.
 */
object DlnaConnector {

    /**
     * Envía la URL al televisor. Los renderizadores difieren en qué tipo MIME aceptan para MPEG-TS
     * (VIDAA, Samsung, LG, Sony…): se prueba cada perfil de [Upnp.TS_PROTOCOL_INFO] hasta que
     * SetAVTransportURI responda con éxito. Si ninguno funciona, se lanza el último error.
     */
    fun load(avTransportUrl: String, streamUrl: String, title: String) {
        var lastError: Exception? = null
        for (profile in Upnp.TS_PROTOCOL_INFO) {
            try {
                val (setAction, setBody) = Upnp.setAvTransportUri(streamUrl, title, profile)
                Http.soap(avTransportUrl, Upnp.soapActionHeader(setAction), setBody)
                val (playAction, playBody) = Upnp.play()
                Http.soap(avTransportUrl, Upnp.soapActionHeader(playAction), playBody)
                return
            } catch (e: java.io.IOException) {
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("El televisor no aceptó ningún perfil de transmisión")
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
