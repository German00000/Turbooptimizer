package com.turbooptimizer.optimizer

import android.app.ActivityManager
import android.content.Context
import com.turbooptimizer.apps.AppRepository
import com.turbooptimizer.core.safety.SafetyGuard
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.data.OperationLogRepository
import com.turbooptimizer.data.SettingsRepository
import com.turbooptimizer.monitor.SystemMonitor
import com.turbooptimizer.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RamCandidate(val packageName: String, val label: String, val ramKb: Long?)

data class RamBoostResult(
    val timestamp: Long,
    val before: Long,
    val after: Long,
    val total: Long,
    val stopped: List<String>,
    val failed: List<String>,
    val simulated: Boolean,
    val trigger: String,
) {
    val freed: Long get() = (after - before).coerceAtLeast(0)
}

/**
 * RAM Boost honesto:
 *  - Solo actúa sobre las apps que el usuario puso en la lista negra.
 *  - Nunca toca el juego actual, la lista blanca ni componentes críticos.
 *  - No se ejecuta en bucle: el intervalo mínimo es de 5 minutos y solo si la RAM libre baja del umbral.
 */
class RamBoostManager(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val safety: SafetyGuard,
    private val settings: SettingsRepository,
    private val log: OperationLogRepository,
    private val apps: AppRepository,
    private val monitor: SystemMonitor,
) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val mutex = Mutex()
    private val _last = MutableStateFlow<RamBoostResult?>(null)
    val last: StateFlow<RamBoostResult?> = _last.asStateFlow()
    @Volatile var lastRunAt: Long = 0L
        private set

    suspend fun candidates(currentGame: String?): List<RamCandidate> = withContext(Dispatchers.IO) {
        val s = settings.current()
        val extra = setOfNotNull(currentGame)
        val running = if (shizuku.isReady) apps.runningPss() else null
        s.blacklist
            .filter { apps.isInstalled(it) && safety.protectionReason(it, s.whitelist, extra) == null }
            .filter { running == null || it in running }
            .map { RamCandidate(it, apps.label(it), running?.get(it)) }
            .sortedByDescending { it.ramKb ?: 0 }
    }

    suspend fun run(currentGame: String?, simulate: Boolean, trigger: String): RamBoostResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            apps.invalidateRunning()
            val list = candidates(currentGame)
            val mem = monitor.memoryInfo()
            val before = mem.availMem
            val stopped = mutableListOf<String>()
            val failed = mutableListOf<String>()
            if (!simulate) {
                for (c in list) {
                    val ok = if (shizuku.isReady) {
                        shizuku.exec(ShellCommands.forceStop(c.packageName)).isSuccess
                    } else {
                        runCatching { am.killBackgroundProcesses(c.packageName) }.isSuccess
                    }
                    if (ok) stopped += c.label else failed += c.label
                }
                if (list.isNotEmpty()) delay(1_500)
            } else {
                stopped += list.map { it.label }
            }
            val after = monitor.memoryInfo().availMem
            apps.invalidateRunning()
            val result = RamBoostResult(System.currentTimeMillis(), before, after, mem.totalMem, stopped, failed, simulate, trigger)
            _last.value = result
            lastRunAt = result.timestamp
            log.add(
                category = LogCategory.RAM_BOOST,
                title = "RAM Boost ($trigger)",
                details = listOf(
                    "RAM disponible antes: ${formatBytes(before)}",
                    "RAM disponible después: ${formatBytes(after)}",
                    if (stopped.isEmpty()) "No había apps de la lista negra en ejecución" else "Detenidas: ${stopped.joinToString()}",
                ) + if (failed.isNotEmpty()) listOf("Fallidas: ${failed.joinToString()}") else emptyList(),
                success = failed.isEmpty(),
                simulated = simulate,
                ramBefore = before,
                ramAfter = after,
            )
            result
        }
    }
}
