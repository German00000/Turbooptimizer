package com.screencastpro.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.screencastpro.MainActivity
import com.screencastpro.R
import com.screencastpro.SessionState
import com.screencastpro.connect.TvConnection
import com.screencastpro.core.AdaptiveController
import com.screencastpro.core.BitrateChoice
import com.screencastpro.core.EncodeParams
import com.screencastpro.core.Level
import com.screencastpro.core.ProfileMath
import com.screencastpro.core.Sample
import com.screencastpro.core.StreamHub
import com.screencastpro.core.StreamServer
import com.screencastpro.core.ThermalLevel
import com.screencastpro.core.TsMuxer
import com.screencastpro.net.Lan
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Servicio en primer plano que captura la pantalla y la sirve por HTTP en la red local.
 *
 * Orden de arranque (requerido por Android 14+):
 *   1. startForeground(tipo mediaProjection)  2. getMediaProjection  3. registerCallback
 *   4. VirtualDisplay con la superficie del codificador  5. servidor HTTP  6. bucle de control
 *
 * Cada [TICK_MS] se ejecuta el control adaptativo (congestión, temperatura) y la gestión de
 * ahorro: sin espectadores durante [IDLE_AFTER_MS] se detiene la entrada del codificador.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.screencastpro.action.START"
        const val ACTION_STOP = "com.screencastpro.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SHORT_SIDE = "short_side"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_LOW_LATENCY = "low_latency"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_BITRATE = "bitrate"

        /** Puerto HTTP en la red local. */
        const val PORT = 8765

        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screencast_stream"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 2000L
        private const val IDLE_AFTER_MS = 20_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    @Volatile private var encoder: VideoEncoder? = null
    private var audio: AudioCapture? = null
    @Volatile private var muxer: TsMuxer? = null
    @Volatile private var hub: StreamHub? = null
    private var server: StreamServer? = null
    private var controller: AdaptiveController? = null
    private var token = ""

    private var shortSide = 720
    private var fps = 30
    private var lowLatency = true
    private var battery = false
    private var bitrateChoice = BitrateChoice.AUTO
    private var wantAudio = false

    private var ladder: List<Level> = emptyList()
    private var topLevel = 0
    private var currentLevel = 0
    private var srcW = 0
    private var srcH = 0
    private var dpi = 0
    private var audioActive = false
    private var started = false
    private var idle = false
    private var lastThermal = ThermalLevel.NORMAL

    private val framesOut = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    private var lastTickNs = 0L
    private var lastFrames = 0L
    private var lastBytes = 0L
    private var measuredFps = 0.0
    private var measuredBps = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post { onDisplayChanged() }
        }
        override fun onLowMemory() {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- arranque

    private fun startCapture(intent: Intent) {
        if (started) return
        // Debe llamarse en los 5 s siguientes a startForegroundService, antes de cualquier otra cosa.
        ensureForeground(getString(R.string.state_starting))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail(getString(R.string.permission_denied_projection))
            return
        }

        shortSide = intent.getIntExtra(EXTRA_SHORT_SIDE, 720)
        fps = intent.getIntExtra(EXTRA_FPS, 30)
        lowLatency = intent.getBooleanExtra(EXTRA_LOW_LATENCY, true)
        battery = intent.getBooleanExtra(EXTRA_BATTERY, false)
        bitrateChoice = BitrateChoice.fromOrdinal(intent.getIntExtra(EXTRA_BITRATE, 0))
        wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)

        try {
            startInternal(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la transmisión", e)
            fail(e.message ?: "Error al iniciar la transmisión")
        }
    }

    private fun startInternal(resultCode: Int, data: Intent) {
        started = true
        SessionState.update { it.copy(running = true, status = getString(R.string.state_starting), error = null) }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection no disponible")
        projection = mp
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: el callback debe registrarse antes de createVirtualDisplay.
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopCapture() }
                }
            }, main)
        }
        registerComponentCallbacks(componentCallbacks)

        val (w, h, d) = displaySize()
        srcW = w; srcH = h; dpi = d

        val effShort = if (battery) minOf(shortSide, 720) else shortSide
        val effFps = if (battery) minOf(fps, 30) else fps
        ladder = ProfileMath.ladder(effShort, effFps)
        topLevel = Capabilities.negotiateLevel(Capabilities.pickEncoder(), ladder, srcW, srcH)
        currentLevel = topLevel
        controller = AdaptiveController(
            levelCount = ladder.size,
            topLevel = topLevel,
            bitrateFor = { lvl -> bitrateForLevel(lvl) },
        )

        token = UUID.randomUUID().toString().replace("-", "")
        val h2 = StreamHub()
        h2.onKeyframeRequested = { encoder?.requestKeyframe() }
        hub = h2
        server = StreamServer(h2, PORT, token).also { it.start() }

        audioActive = wantAudio && Build.VERSION.SDK_INT >= 29 && hasRecordAudio() && startAudio(mp)
        muxer = TsMuxer(audioActive)

        startVideo(topLevel, timelineOffsetUs = 0L, bitrate = controller!!.bitrate)
        lastTickNs = System.nanoTime()

        ensureForeground(getString(R.string.notification_text))
        main.postDelayed(tick, TICK_MS)
        publishState()
        Log.i(TAG, "Transmitiendo ${ladder[currentLevel]} en puerto $PORT, audio=$audioActive")
    }

    /** Crea o reconfigura el codificador y el VirtualDisplay al nivel indicado. */
    private fun startVideo(level: Int, timelineOffsetUs: Long, bitrate: Int) {
        val p = paramsFor(level, bitrate)
        val enc = VideoEncoder(p, lowLatency, timelineOffsetUs) { au, pts, key -> onVideo(au, pts, key) }
        enc.start()
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "ScreenCastPro", p.width, p.height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.inputSurface, null, main,
            )
        } else {
            vd.resize(p.width, p.height, dpi)
            vd.setSurface(enc.inputSurface)
        }
        val old = encoder
        encoder = enc
        currentLevel = level
        old?.stop()
    }

    private fun startAudio(mp: MediaProjection): Boolean {
        val cap = AudioCapture(mp) { adts, pts -> onAudio(adts, pts) }
        return try {
            if (cap.start()) { audio = cap; true } else false
        } catch (e: Exception) {
            Log.w(TAG, "Audio interno no disponible, se continúa solo con vídeo", e)
            false
        }
    }

    private fun hasRecordAudio(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- datos

    private fun onVideo(au: ByteArray, ptsUs: Long, key: Boolean) {
        val m = muxer ?: return
        val h = hub ?: return
        val chunk = m.videoAccessUnit(au, ptsUs, key)
        framesOut.incrementAndGet()
        bytesOut.addAndGet(chunk.size.toLong())
        h.publish(chunk, ptsUs, key)
    }

    private fun onAudio(adts: ByteArray, ptsUs: Long) {
        val m = muxer ?: return
        val h = hub ?: return
        val chunk = m.audioAccessUnit(adts, ptsUs)
        if (chunk.isEmpty()) return
        bytesOut.addAndGet(chunk.size.toLong())
        h.publish(chunk, ptsUs, false)
    }

    // ---------------------------------------------------------------- control

    private fun onTick() {
        val h = hub ?: return
        val c = controller ?: return

        val now = System.nanoTime()
        val dtS = (now - lastTickNs) / 1e9
        val frames = framesOut.get()
        val bytes = bytesOut.get()
        if (dtS > 0) {
            measuredFps = (frames - lastFrames) / dtS
            measuredBps = ((bytes - lastBytes) * 8 / dtS).toLong()
        }
        lastTickNs = now
        lastFrames = frames
        lastBytes = bytes

        val thermal = Thermal.current(this)
        lastThermal = thermal
        val decision = c.update(Sample(h.drainLagEvents(), h.averageWriteMs(), thermal))

        // Ahorro: sin espectadores y sin peticiones recientes, se detiene la entrada del codificador.
        val sinceActivityMs = System.currentTimeMillis() - h.lastActivityMs
        if (h.subscriberCount == 0 && sinceActivityMs > IDLE_AFTER_MS) {
            if (!idle) {
                virtualDisplay?.setSurface(null)
                idle = true
                Log.i(TAG, "Sin espectadores: codificador en pausa")
            }
        } else if (idle) {
            idle = false
            encoder?.let {
                virtualDisplay?.setSurface(it.inputSurface)
                it.requestKeyframe()
            }
        }

        if (!idle) {
            try {
                if (currentLevel != c.level) {
                    reconfigure(c.level, c.bitrate)
                } else if (decision.bitrateChanged) {
                    encoder?.setBitrate(c.bitrate)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo adaptar la calidad", e)
            }
        }
        publishState()
    }

    private fun reconfigure(level: Int, bitrate: Int) {
        val frameUs = 1_000_000L / maxOf(1, ladder[level].fps)
        val offset = maxOf(0L, encoder?.lastPtsUs ?: 0L) + frameUs
        startVideo(level, offset, bitrate)
    }

    private fun onDisplayChanged() {
        if (!started) return
        val (w, h, d) = displaySize()
        if (w == srcW && h == srcH && d == dpi) return
        srcW = w; srcH = h; dpi = d
        if (!idle) {
            try {
                reconfigure(currentLevel, controller?.bitrate ?: 0)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo adaptar a la rotación", e)
            }
        }
    }

    private fun dimsFor(level: Int): Pair<Int, Int> =
        ProfileMath.fitSize(srcW, srcH, ladder[level].shortSide)

    private fun paramsFor(level: Int, bitrate: Int): EncodeParams {
        val (w, h) = dimsFor(level)
        return EncodeParams(w, h, ladder[level].fps, bitrate)
    }

    private fun bitrateForLevel(level: Int): Int {
        val (w, h) = dimsFor(level)
        val base = ProfileMath.defaultBitrate(w, h, ladder[level].fps, battery)
        return (base * ProfileMath.bitrateScale(bitrateChoice)).toInt()
    }

    private fun publishState() {
        val h = hub
        val ip = Lan.localIpv4()
        val base = if (ip != null) "http://$ip:$PORT/$token" else null
        val lvl = ladder.getOrNull(currentLevel) ?: return
        val (w, hgt) = dimsFor(currentLevel)
        val adapted = currentLevel != topLevel
        SessionState.update {
            it.copy(
                running = true,
                status = if (idle) "En pausa: sin espectadores" else getString(R.string.state_streaming),
                width = w,
                height = hgt,
                fps = measuredFps,
                targetBitrateBps = controller?.bitrate ?: 0,
                measuredBitrateBps = measuredBps,
                latencyMs = ProfileMath.estimateLatencyMs(
                    lvl.fps,
                    h?.averageQueueMs() ?: 0.0,
                    h?.averageWriteMs() ?: 0.0,
                ),
                clients = h?.subscriberCount ?: 0,
                audioActive = audioActive,
                thermal = lastThermal.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                qualityLabel = "${min(w, hgt)}p@${lvl.fps}" + if (adapted) " (adaptado)" else "",
                tsUrl = base?.let { "$it/live.ts" },
                hlsUrl = base?.let { "$it/live.m3u8" },
                error = null,
            )
        }
    }

    // ---------------------------------------------------------------- parada

    private fun stopCapture() {
        if (!started && projection == null && server == null) {
            stopSelf()
            return
        }
        started = false
        main.removeCallbacks(tick)
        try { unregisterComponentCallbacks(componentCallbacks) } catch (_: Exception) {}
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        audio?.stop()
        audio = null
        muxer = null
        server?.stop()
        server = null
        hub?.onKeyframeRequested = null
        hub = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        controller = null
        idle = false
        audioActive = false
        TvConnection.disconnectAll(applicationContext)
        SessionState.reset()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        stopCapture()
        SessionState.reset(error = message)
    }

    // ---------------------------------------------------------------- utilidades

    @Suppress("DEPRECATION")
    private fun displaySize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun ensureForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notification_stop), stopPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }
}
