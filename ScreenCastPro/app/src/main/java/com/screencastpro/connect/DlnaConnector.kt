package com.screencastpro.connect

import com.screencastpro.core.Upnp
import com.screencastpro.net.Http

/**
 * Control DLNA/UPnP (servicio AVTransport). Se ejecuta siempre fuera del hilo principal.
 * El televisor abre la URL HTTP del teléfono con su propio reproductor de MPEG-TS.
 */
object DlnaConnector {

    /**
     * Envía la URL al televisor y comprueba que de verdad reproduce. Muchos televisores aceptan
     * SetAVTransportURI y después no reproducen (p. ej. "formato no compatible"), así que tras Play se
     * pregunta el estado y, si no está reproduciendo, se prueba el siguiente perfil de
     * [Upnp.TS_PROTOCOL_INFO]. Si ninguno funciona, se lanza una excepción.
     */
    fun load(
        avTransportUrl: String,
        streamUrl: String,
        title: String,
        profiles: List<String> = Upnp.DLNA_PROFILES,
    ) {
        var lastError: Exception? = null
        for (profile in profiles) {
            try {
                val (setAction, setBody) = Upnp.setAvTransportUri(streamUrl, title, profile)
                Http.soap(avTransportUrl, Upnp.soapActionHeader(setAction), setBody)
                val (playAction, playBody) = Upnp.play()
                Http.soap(avTransportUrl, Upnp.soapActionHeader(playAction), playBody)
                // La TV tarda en abrir el flujo (a veces varios segundos): se consulta hasta 8 s.
                var state: String? = null
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(1000)
                    state = transportState(avTransportUrl)
                    if (state == "PLAYING" || state == "TRANSITIONING") return
                }
                lastError = java.io.IOException("El televisor no reproduce ($profile, estado $state)")
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
