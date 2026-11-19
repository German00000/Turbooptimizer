package com.screencastpro.connect

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.screencastpro.DiagLog
import com.screencastpro.SessionState
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
    private const val MAX_STALLED_RELOADS = 3

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
    /** Comprobaciones seguidas con la TV en PLAYING y sin espectadores. */
    @Volatile private var stalledChecks = 0
    /** Recargas hechas porque la TV dejó de leer el flujo. */
    @Volatile private var stalledReloads = 0

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
                    DiagLog.i("TV", "vigilancia DLNA: estado $s")
                    val viewers = SessionState.stats.value.clients
                    if (viewers > 0) stalledReloads = 0
                    stalledChecks = if (s == "PLAYING" && viewers == 0) stalledChecks + 1 else 0
                    if (stalledChecks >= 2) DiagLog.w("TV", "la TV dice PLAYING pero no lee el flujo")
                    s == "STOPPED" || s == "NO_MEDIA_PRESENT" || stalledChecks >= 2
                } catch (_: Exception) {
                    true
                }
                if (reload && autoReconnect) {
                    if (stalledChecks >= 2) {
                        stalledReloads++
                        stalledChecks = 0
                        if (stalledReloads > MAX_STALLED_RELOADS) {
                            main.post {
                                if (gen == generation) setState(State.Error(dev, "La TV no lee el flujo tras varios intentos (ver registro)"))
                            }
                            return@execute
                        }
                    }
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
        stalledChecks = 0
        stalledReloads = 0
        setState(State.Connecting(device))
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
        setState(State.Idle)
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
                        setState(State.Connected(dev, url))
                    }

                    override fun onFailed(message: String) {
                        if (gen == generation) scheduleRetry(message)
                    }

                    override fun onLost(recoverable: Boolean) {
                        if (gen != generation) return
                        if (recoverable) scheduleRetry("Se perdió la conexión con ${dev.name}")
                        else setState(State.Error(dev, "La sesión Cast ha terminado"))
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
                            setState(State.Connected(dev, url))
                            main.removeCallbacks(dlnaMonitor)
                            main.postDelayed(dlnaMonitor, DLNA_CHECK_MS)
                        }
                    } catch (e: Exception) {
                        val detail = e.message?.takeIf { it.isNotBlank() } ?: "sin detalle"
                        main.post { if (gen == generation) scheduleRetry("DLNA ${e.javaClass.simpleName}: $detail") }
                    }
                }
            }
        }
    }

    private fun scheduleRetry(reason: String) {
        val dev = device ?: return
        if (!autoReconnect || attempts >= MAX_ATTEMPTS) {
            setState(State.Error(dev, reason))
            return
        }
        attempts++
        val gen = generation
        setState(State.Connecting(dev))
        main.postDelayed({
            if (gen == generation && device != null) startConnect(dev)
        }, RETRY_DELAY_MS)
    }

    private fun setState(s: State) {
        val text = when (s) {
            is State.Idle -> "sin conexión"
            is State.Connecting -> "conectando con ${s.device.name} (${s.device.host}, ${s.device.preferredProtocol.label})"
            is State.Connected -> "conectado a ${s.device.name} (${s.device.host})"
            is State.Error -> "error: ${s.message}"
        }
        DiagLog.i("TV", text)
        state.value = s
    }

    private fun fail(dev: TvDevice, message: String) {
        setState(State.Error(dev, message))
    }
}
