package com.screencastpro.connect

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.screencastpro.discovery.Protocol
import com.screencastpro.discovery.TvDevice
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Orquesta la conexión con un televisor: elige el protocolo (Cast primero, DLNA si no hay Cast),
 * gestiona reintentos y vigila que la reproducción siga activa (reconexión automática).
 */
object TvConnection {

    sealed class State {
        object Idle : State()
        data class Connecting(val device: TvDevice) : State()
        data class Connected(val device: TvDevice, val url: String) : State()
        data class Error(val device: TvDevice?, val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    private const val TITLE = "ScreenCast Pro"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 3000L
    private const val DLNA_CHECK_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tv-connect").apply { isDaemon = true }
    }

    private var appContext: Context? = null
    private var device: TvDevice? = null
    private var protocol: Protocol = Protocol.CAST
    private var dlnaUrl: String? = null
    private var hlsUrl: String? = null
    private var autoReconnect = true
    private var attempts = 0
    private var cast: CastConnector? = null

    /** Cada conexión tiene un número; las respuestas de conexiones anteriores se ignoran. */
    private var generation = 0

    private val dlnaMonitor = object : Runnable {
        override fun run() {
            val dev = device ?: return
            val av = dev.avTransportUrl
            if (protocol != Protocol.DLNA || av == null || state.value !is State.Connected) return
            val gen = generation
            io.execute {
                val reload: Boolean = try {
                    val s = DlnaConnector.transportState(av)
                    s == "STOPPED" || s == "NO_MEDIA_PRESENT"
                } catch (_: Exception) {
                    true
                }
                if (reload && autoReconnect) {
                    try {
                        DlnaConnector.load(av, dlnaUrl ?: return@execute, TITLE)
                    } catch (_: Exception) {
                        main.post { if (gen == generation) scheduleRetry("El televisor dejó de responder") }
                    }
                }
            }
            main.postDelayed(this, DLNA_CHECK_MS)
        }
    }

    /**
     * @param dlnaUrl URL MPEG-TS (televisores DLNA; el perfil probado es video/mp2ts).
     * @param hlsUrl URL HLS (Chromecast / Google Cast).
     */
    /** [protocol] es el modo elegido por el usuario (Cast o DLNA), debe estar anunciado por el dispositivo. */
    fun connect(
        context: Context,
        device: TvDevice,
        protocol: Protocol,
        dlnaUrl: String,
        hlsUrl: String,
        autoReconnect: Boolean,
    ) {
        require(protocol in device.protocols) { "El dispositivo no anuncia ${protocol.label}" }
        appContext = context.applicationContext
        if (this.device != null) disconnect()
        generation++
        this.device = device
        this.protocol = protocol
        this.dlnaUrl = dlnaUrl
        this.hlsUrl = hlsUrl
        this.autoReconnect = autoReconnect
        attempts = 0
        state.value = State.Connecting(device)
        startConnect(device)
    }

    fun disconnect() {
        generation++
        main.removeCallbacks(dlnaMonitor)
        val dev = device
        device = null
        if (protocol == Protocol.DLNA) {
            dev?.avTransportUrl?.let { av ->
                io.execute { try { DlnaConnector.stop(av) } catch (_: Exception) {} }
            }
        }
        cast?.disconnect()
        state.value = State.Idle
    }

    /** Llamado al detener la transmisión: desconecta cualquier televisor activo. */
    fun disconnectAll(context: Context) {
        appContext = context.applicationContext
        if (state.value !is State.Idle) disconnect()
    }

    private fun startConnect(dev: TvDevice) {
        val ctx = appContext ?: return
        val gen = generation
        when (protocol) {
            Protocol.CAST -> {
                val url = hlsUrl ?: return fail(dev, "Falta la URL de transmisión")
                val connector = cast ?: CastConnector(ctx).also { cast = it }
                connector.connect(dev.host, url, object : CastConnector.Listener {
                    override fun onLoaded() {
                        if (gen != generation) return
                        attempts = 0
                        state.value = State.Connected(dev, url)
                    }

                    override fun onFailed(message: String) {
                        if (gen == generation) scheduleRetry(message)
                    }

                    override fun onLost(recoverable: Boolean) {
                        if (gen != generation) return
                        if (recoverable) scheduleRetry("Se perdió la conexión con ${dev.name}")
                        else state.value = State.Error(dev, "La sesión Cast ha terminado")
                    }
                })
            }
            Protocol.DLNA -> {
                val av = dev.avTransportUrl ?: return fail(dev, "El televisor no expone AVTransport")
                val url = dlnaUrl ?: return fail(dev, "Falta la URL de transmisión")
                io.execute {
                    try {
                        DlnaConnector.load(av, url, TITLE)
                        main.post {
                            if (gen != generation) return@post
                            attempts = 0
                            state.value = State.Connected(dev, url)
                            main.removeCallbacks(dlnaMonitor)
                            main.postDelayed(dlnaMonitor, DLNA_CHECK_MS)
                        }
                    } catch (e: Exception) {
                        main.post { if (gen == generation) scheduleRetry("DLNA: ${e.message ?: "error de red"}") }
                    }
                }
            }
        }
    }

    private fun scheduleRetry(reason: String) {
        val dev = device ?: return
        if (!autoReconnect || attempts >= MAX_ATTEMPTS) {
            state.value = State.Error(dev, reason)
            return
        }
        attempts++
        val gen = generation
        state.value = State.Connecting(dev)
        main.postDelayed({
            if (gen == generation && device != null) startConnect(dev)
        }, RETRY_DELAY_MS)
    }

    private fun fail(dev: TvDevice, message: String) {
        state.value = State.Error(dev, message)
    }
}
