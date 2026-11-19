package com.screencastpro.connect

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

/**
 * Conexión con Chromecast / Google TV mediante Google Cast SDK.
 *
 * Flujo: buscar la ruta de MediaRouter cuya IP coincide con el televisor → seleccionarla → cuando la
 * sesión está activa, cargar la URL HLS del teléfono en el receptor por defecto.
 */
class CastConnector(private val appContext: Context) {

    interface Listener {
        fun onLoaded()
        fun onFailed(message: String)
        /** [recoverable] = true si la sesión se cortó por error y conviene reintentar. */
        fun onLost(recoverable: Boolean)
    }

    companion object {
        private const val TAG = "CastConnector"
        private const val ROUTE_RETRIES = 20
        private const val ROUTE_RETRY_MS = 500L
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var wantedUrl: String? = null
    private var discoveryCallback: MediaRouter.Callback? = null
    private var registered = false

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) = load(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            listener?.onFailed("Sesión Cast no iniciada (código $error)")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            listener?.onLost(error != 0)
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = load(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            listener?.onFailed("No se pudo reanudar la sesión Cast (código $error)")
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun connect(host: String, url: String, listener: Listener) {
        this.listener = listener
        this.wantedUrl = url
        val ctx = try {
            CastContext.getSharedInstance(appContext)
        } catch (e: Exception) {
            listener.onFailed("Google Play services no disponible para Cast")
            return
        }
        val sm = ctx.sessionManager
        if (!registered) {
            sm.addSessionManagerListener(sessionListener, CastSession::class.java)
            registered = true
        }
        ensureDiscovery()
        val current = sm.currentCastSession
        if (current != null && current.isConnected &&
            current.castDevice?.ipAddress?.hostAddress == host
        ) {
            load(current)
            return
        }
        selectRoute(host, ROUTE_RETRIES)
    }

    /** Termina la sesión Cast. Antes se quita el listener para no disparar reconexiones. */
    fun disconnect() {
        listener = null
        wantedUrl = null
        try {
            CastContext.getSharedInstance(appContext).sessionManager.endCurrentSession(true)
        } catch (e: Exception) {
            Log.w(TAG, "Error al cerrar la sesión Cast", e)
        }
    }

    private fun ensureDiscovery() {
        if (discoveryCallback != null) return
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        val cb = object : MediaRouter.Callback() {}
        MediaRouter.getInstance(appContext).addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        discoveryCallback = cb
    }

    private fun selectRoute(host: String, retriesLeft: Int) {
        val router = MediaRouter.getInstance(appContext)
        val route = router.routes.firstOrNull { r ->
            CastDevice.getFromBundle(r.extras)?.ipAddress?.hostAddress == host
        }
        if (route != null) {
            router.selectRoute(route)
            return
        }
        if (retriesLeft <= 0) {
            listener?.onFailed("No se encontró el dispositivo Cast en la red")
            return
        }
        main.postDelayed({ if (listener != null) selectRoute(host, retriesLeft - 1) }, ROUTE_RETRY_MS)
    }

    private fun load(session: CastSession) {
        val url = wantedUrl ?: return
        val client = session.remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, "ScreenCast Pro")
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("application/x-mpegURL")
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        listener?.onLoaded()
    }
}
