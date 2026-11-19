package com.turbooptimizer.game

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.turbooptimizer.MainActivity
import com.turbooptimizer.R
import com.turbooptimizer.TurboApp
import com.turbooptimizer.data.SettingsRepository
import com.turbooptimizer.util.NotificationChannels
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatDuration
import com.turbooptimizer.util.formatPercent
import com.turbooptimizer.util.formatTemp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Servicio en primer plano del Game Booster:
 *  - Overlay de estadísticas (FPS, temperatura, RAM, CPU, batería, tiempo).
 *  - RAM Boost programado (intervalo configurable ≥ 5 min, solo si la RAM libre baja del umbral).
 *  - Detección automática del juego en primer plano (UsageStats) si el usuario la activa.
 * Se detiene solo cuando no hay sesión ni detección activa.
 */
class GameSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val container by lazy { (application as TurboApp).container }
    private var overlay: OverlayController? = null
    private var sessionJob: Job? = null
    private var watchJob: Job? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Obligatorio: pasar a primer plano de inmediato.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        when (intent?.action) {
            ACTION_TOGGLE_OVERLAY -> GameSessionState.overlayVisible.value = !GameSessionState.overlayVisible.value
            ACTION_END_SESSION -> scope.launch { container.gameBooster.endSession() }
            ACTION_STOP_WATCH -> {
                GameSessionState.watching.value = false
                scope.launch { container.settings.update { it.copy(autoDetectGames = false) } }
            }
        }
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            combine(GameSessionState.session, GameSessionState.watching, GameSessionState.overlayVisible) { s, w, o -> Triple(s, w, o) }
                .distinctUntilChanged()
                .collect { (session, watching, overlayVisible) ->
                    if (session == null && !watching) {
                        stopEverything()
                        return@collect
                    }
                    updateNotification()
                    if (session != null) {
                        if (sessionJob?.isActive != true) sessionJob = scope.launch { sessionLoop() }
                        if (overlayVisible && container.settings.current().overlayEnabled && Permissions.canDrawOverlays(this@GameSessionService)) {
                            ensureOverlay().show()
                        } else overlay?.hide()
                    } else {
                        sessionJob?.cancel(); sessionJob = null
                        overlay?.hide()
                    }
                    if (watching && watchJob?.isActive != true) watchJob = scope.launch { watchLoop() }
                    if (!watching) { watchJob?.cancel(); watchJob = null }
                }
        }
    }

    private fun ensureOverlay(): OverlayController = overlay ?: OverlayController(this) {
        GameSessionState.overlayVisible.value = false
    }.also { overlay = it }

    private suspend fun sessionLoop() {
        container.fps.reset()
        var nextBoostCheck = System.currentTimeMillis() + 60_000
        // Mantiene suscrito el flujo de métricas mientras dure la sesión.
        val metricsJob = scope.launch { container.snapshots.collect { } }
        try {
            while (scope.isActive) {
                val session = GameSessionState.session.value ?: break
                // Sin lectura válida el valor pasa a null (N/D): nunca se muestra un FPS antiguo.
                val fps = container.fps.sample(session.packageName)
                GameSessionState.fps.value = fps
                GameSessionState.fpsStatus.value = container.fps.status.value
                val snap = container.snapshots.value
                overlay?.update(
                    OverlayStats(
                        fps = GameSessionState.fps.value?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "N/D",
                        temp = formatTemp(snap?.bestTempC),
                        ram = snap?.let { formatPercent(it.ramUsedFraction * 100) } ?: "N/D",
                        cpu = formatPercent(snap?.cpuUsagePercent),
                        battery = snap?.let { "${it.batteryPercent}%" } ?: "N/D",
                        time = formatDuration(System.currentTimeMillis() - session.startedAt),
                    ),
                )
                // RAM Boost programado
                val settings = container.settings.current()
                val now = System.currentTimeMillis()
                if (settings.ramBoostEnabled && now >= nextBoostCheck && snap != null) {
                    val interval = settings.ramBoostIntervalMin.coerceAtLeast(SettingsRepository.MIN_RAM_INTERVAL) * 60_000L
                    if (snap.ramAvailPercent < settings.ramBoostThresholdPercent && now - container.ramBoost.lastRunAt >= interval) {
                        val r = container.ramBoost.run(session.packageName, settings.simulationMode, "automático durante ${session.label}")
                        notifyBoost("RAM disponible: ${formatBytes(r.before)} → ${formatBytes(r.after)}")
                    }
                    nextBoostCheck = now + 60_000 // se comprueba cada minuto, se ejecuta como mucho cada «intervalo»
                }
                delay(1_000)
            }
        } finally {
            metricsJob.cancel()
        }
    }

    /** Detecta cuándo un juego de la lista pasa a primer plano usando eventos de UsageStats. */
    private suspend fun watchLoop() {
        if (!Permissions.hasUsageAccess(this)) {
            Log.w(TAG, "Detección automática sin acceso de uso")
            GameSessionState.watching.value = false
            return
        }
        val usm = getSystemService(UsageStatsManager::class.java)
        var lastQuery = System.currentTimeMillis() - 10_000
        var foreground: String? = null
        var awaySince: Long? = null
        val event = UsageEvents.Event()
        while (scope.isActive && GameSessionState.watching.value) {
            val now = System.currentTimeMillis()
            runCatching {
                val events = usm.queryEvents(lastQuery, now)
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.packageName
                }
            }
            lastQuery = now
            val settings = container.settings.current()
            val session = GameSessionState.session.value
            val fg = foreground
            if (session == null && fg != null && fg in settings.games) {
                runCatching {
                    val plan = container.gameBooster.prepare(fg, settings.gameProfile)
                    val ids = plan.actions.filter { it.selectedByDefault }.map { it.id }.toSet()
                    container.gameBooster.startSession(fg, plan, ids, settings.simulationMode, auto = true, launch = false)
                }.onFailure { Log.w(TAG, "No se pudo iniciar la sesión automática", it) }
                awaySince = null
            } else if (session != null && session.auto) {
                if (fg != session.packageName) {
                    val since = awaySince ?: now.also { awaySince = it }
                    if (now - since > 60_000) {
                        container.gameBooster.endSession()
                        awaySince = null
                    }
                } else awaySince = null
            }
            delay(3_000)
        }
    }

    private fun stopEverything() {
        sessionJob?.cancel(); sessionJob = null
        watchJob?.cancel(); watchJob = null
        overlay?.hide()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, GameSessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val session = GameSessionState.session.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NotificationChannels.GAME)
            .setSmallIcon(R.drawable.ic_stat_turbo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (session != null) {
            builder.setContentTitle("Game Booster: ${session.label}")
                .setContentText("Perfil ${session.profile.title}. Toca «Finalizar» para restaurar los ajustes.")
                .setUsesChronometer(true)
                .setWhen(session.startedAt)
                .addAction(0, if (GameSessionState.overlayVisible.value) "Ocultar overlay" else "Mostrar overlay", pending(ACTION_TOGGLE_OVERLAY, 1))
                .addAction(0, "Finalizar", pending(ACTION_END_SESSION, 2))
        } else {
            builder.setContentTitle("Detección automática de juegos")
                .setContentText("Se aplicará tu perfil al abrir un juego de tu lista.")
                .addAction(0, "Desactivar", pending(ACTION_STOP_WATCH, 3))
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (Permissions.hasNotifications(this)) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
        }
    }

    private fun notifyBoost(text: String) {
        if (!Permissions.hasNotifications(this)) return
        val n = NotificationCompat.Builder(this, NotificationChannels.GAME)
            .setSmallIcon(R.drawable.ic_stat_turbo)
            .setContentTitle("RAM Boost aplicado")
            .setContentText(text)
            .setTimeoutAfter(8_000)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(BOOST_NOTIFICATION_ID, n) }
    }

    override fun onDestroy() {
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GameSessionService"
        private const val NOTIFICATION_ID = 1001
        private const val BOOST_NOTIFICATION_ID = 1002
        const val ACTION_TOGGLE_OVERLAY = "com.turbooptimizer.game.TOGGLE_OVERLAY"
        const val ACTION_END_SESSION = "com.turbooptimizer.game.END_SESSION"
        const val ACTION_STOP_WATCH = "com.turbooptimizer.game.STOP_WATCH"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GameSessionService::class.java))
        }

        /** Notifica un cambio de estado; si el servicio no tiene trabajo, se detendrá solo. */
        fun refresh(context: Context) {
            if (GameSessionState.session.value != null || GameSessionState.watching.value) start(context)
        }
    }
}
