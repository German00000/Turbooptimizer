package com.screencastpro.connect

import com.screencastpro.DiagLog
import com.screencastpro.core.Upnp
import com.screencastpro.net.Http

/**
 * Control DLNA/UPnP (servicio AVTransport). Se ejecuta siempre fuera del hilo principal.
 * El televisor abre la URL del teléfono con su propio reproductor.
 */
object DlnaConnector {
    private const val TAG = "DLNA"

    /**
     * Envía la URL al televisor y comprueba que de verdad reproduce. Tras Play se consulta el estado
     * hasta 8 s; si no reproduce, se prueba el siguiente perfil. Si ninguno funciona, lanza excepción
     * con el último motivo.
     */
    fun load(
        avTransportUrl: String,
        streamUrl: String,
        title: String,
        profiles: List<String> = Upnp.DLNA_PROFILES,
    ) {
        var lastError: Exception? = null
        for (profile in profiles) {
            DiagLog.i(TAG, "probando perfil $profile")
            try {
                val (setAction, setBody) = Upnp.setAvTransportUri(streamUrl, title, profile)
                Http.soap(avTransportUrl, Upnp.soapActionHeader(setAction), setBody)
                val (playAction, playBody) = Upnp.play()
                Http.soap(avTransportUrl, Upnp.soapActionHeader(playAction), playBody)
                var state: String? = null
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(1000)
                    state = transportState(avTransportUrl)
                    DiagLog.i(TAG, "estado de reproducción: $state")
                    if (state == "PLAYING" || state == "TRANSITIONING") {
                        DiagLog.i(TAG, "reproduciendo con $profile")
                        return
                    }
                }
                lastError = java.io.IOException("El televisor no reproduce ($profile, estado $state)")
            } catch (e: Exception) {
                DiagLog.w(TAG, "perfil $profile falló", e)
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
