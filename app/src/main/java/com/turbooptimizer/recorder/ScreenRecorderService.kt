package com.turbooptimizer.recorder

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.turbooptimizer.MainActivity
import com.turbooptimizer.R
import com.turbooptimizer.TurboApp
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.util.NotificationChannels
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Servicio en primer plano (tipo mediaProjection) que aloja el [RecorderEngine]. */
class ScreenRecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var engine: RecorderEngine? = null
    private var output: RecordingOutput? = null
    private var tickerJob: Job? = null
    private var startedAt = 0L
    private var pausedAccum = 0L
    private var pausedAt = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (engine != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        val shortSide = intent.getIntExtra(EXTRA_SHORT_SIDE, 720)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE_MBPS, 6) * 1_000_000
        val hasMic = Permissions.hasMicrophone(this)
        val internal = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO, false) && hasMic
        val mic = intent.getBooleanExtra(EXTRA_MIC, false) && hasMic
        val folder = intent.getStringExtra(EXTRA_FOLDER)

        // Android 14+: hay que estar en primer plano con el tipo mediaProjection ANTES de obtener la proyección.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Preparando grabación…"), type)
        } catch (t: Throwable) {
            fail("No se pudo iniciar el servicio de grabación: ${t.message}")
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("Permiso de captura de pantalla denegado")
            return
        }
        RecorderState.state.update { it.copy(status = RecorderState.Status.STARTING, error = null, audioNote = null) }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data) ?: error("MediaProjection no disponible")
            // Android 14+: registrar el callback antes de crear el VirtualDisplay.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj

            val (w, h) = RecorderCapabilities.outputSize(this, shortSide).let { size ->
                if (RecorderCapabilities.supports(size.first, size.second, fps)) size
                else RecorderCapabilities.outputSize(this, 720)
            }
            val config = RecordingConfig(
                width = w, height = h, fps = fps, bitrate = bitrate,
                densityDpi = resources.displayMetrics.densityDpi,
                internalAudio = internal, microphone = mic,
            )
            val out = RecordingOutputFactory.create(this, folder)
            output = out
            val e = RecorderEngine(proj, config, out) { t ->
                Handler(Looper.getMainLooper()).post { fail("Error durante la grabación: ${t.message}") }
            }
            e.start()
            engine = e
            startedAt = System.currentTimeMillis()
            pausedAccum = 0
            RecorderState.state.update {
                it.copy(status = RecorderState.Status.RECORDING, config = config, outputName = out.name,
                    elapsedMs = 0, audioNote = e.audioNote ?: if (intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO, false) && !hasMic) "Sin permiso de micrófono: el audio interno también lo requiere." else null)
            }
            startTicker()
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo iniciar la grabación", t)
            fail("No se pudo iniciar la grabación: ${t.message}")
        }
    }

    private fun elapsed(): Long {
        val pausedNow = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0
        return System.currentTimeMillis() - startedAt - pausedAccum - pausedNow
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var n = 0
            while (isActive) {
                RecorderState.state.update { it.copy(elapsedMs = elapsed()) }
                if (n++ % 2 == 0) updateNotification()
                delay(500)
            }
        }
    }

    private fun pause() {
        val e = engine ?: return
        if (e.paused) return
        e.pause()
        pausedAt = System.currentTimeMillis()
        RecorderState.state.update { it.copy(status = RecorderState.Status.PAUSED) }
        updateNotification()
    }

    private fun resume() {
        val e = engine ?: return
        if (!e.paused) return
        e.resume()
        pausedAccum += System.currentTimeMillis() - pausedAt
        pausedAt = 0
        RecorderState.state.update { it.copy(status = RecorderState.Status.RECORDING) }
        updateNotification()
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        tickerJob?.cancel()
        val e = engine
        val out = output
        val duration = elapsed()
        RecorderState.state.update { it.copy(status = RecorderState.Status.STOPPING) }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { e?.stop() ?: false }.getOrDefault(false)
            out?.let { RecordingOutputFactory.finish(this@ScreenRecorderService, it, ok) }
            runCatching { projection?.stop() }
            (application as TurboApp).container.log.add(
                LogCategory.RECORDER,
                if (ok) "Grabación guardada: ${out?.name}" else "Grabación fallida",
                listOf("Duración: ${formatDuration(duration)}"),
                success = ok,
            )
            RecorderState.state.update {
                it.copy(
                    status = RecorderState.Status.IDLE,
                    lastOutput = if (ok) out?.uri else it.lastOutput,
                    error = if (ok || out == null) it.error else "La grabación no contenía datos",
                )
            }
            launch(Dispatchers.Main) {
                engine = null
                ServiceCompat.stopForeground(this@ScreenRecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        RecorderState.state.update { it.copy(status = RecorderState.Status.IDLE, error = message) }
        if (engine != null || output != null) {
            stopRecording()
        } else {
            runCatching { projection?.stop() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun action(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ScreenRecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String? = null): Notification {
        val paused = engine?.paused == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, NotificationChannels.RECORDER)
            .setSmallIcon(R.drawable.ic_stat_turbo)
            .setContentTitle(if (paused) "Grabación en pausa" else "Grabando pantalla")
            .setContentText(text ?: formatDuration(elapsed()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (engine != null) {
            b.addAction(0, if (paused) "Reanudar" else "Pausar", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            b.addAction(0, "Detener", action(ACTION_STOP, 2))
        }
        return b.build()
    }

    private fun updateNotification() {
        if (!Permissions.hasNotifications(this)) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
    }

    override fun onDestroy() {
        if (engine != null && !stopping) runCatching { engine?.stop() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenRecorderService"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.turbooptimizer.recorder.START"
        const val ACTION_PAUSE = "com.turbooptimizer.recorder.PAUSE"
        const val ACTION_RESUME = "com.turbooptimizer.recorder.RESUME"
        const val ACTION_STOP = "com.turbooptimizer.recorder.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_SHORT_SIDE = "short_side"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE_MBPS = "bitrate"
        private const val EXTRA_INTERNAL_AUDIO = "internal_audio"
        private const val EXTRA_MIC = "mic"
        private const val EXTRA_FOLDER = "folder"

        fun startIntent(
            context: Context, resultCode: Int, data: Intent, shortSide: Int, fps: Int, bitrateMbps: Int,
            internalAudio: Boolean, mic: Boolean, folderUri: String?,
        ) = Intent(context, ScreenRecorderService::class.java).setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_DATA, data)
            .putExtra(EXTRA_SHORT_SIDE, shortSide)
            .putExtra(EXTRA_FPS, fps)
            .putExtra(EXTRA_BITRATE_MBPS, bitrateMbps)
            .putExtra(EXTRA_INTERNAL_AUDIO, internalAudio)
            .putExtra(EXTRA_MIC, mic)
            .putExtra(EXTRA_FOLDER, folderUri)

        fun command(context: Context, action: String) {
            context.startService(Intent(context, ScreenRecorderService::class.java).setAction(action))
        }
    }
}
