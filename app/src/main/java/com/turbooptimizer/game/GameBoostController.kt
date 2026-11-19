package com.turbooptimizer.game

import android.content.Context
import com.turbooptimizer.apps.AppRepository
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.data.OperationLogRepository
import com.turbooptimizer.data.OptimizationProfile
import com.turbooptimizer.optimizer.OptimizationEngine
import com.turbooptimizer.optimizer.OptimizationPlan
import com.turbooptimizer.optimizer.OptimizationResult
import com.turbooptimizer.optimizer.UndoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameSession(
    val packageName: String,
    val label: String,
    val startedAt: Long,
    val logId: Long?,
    val auto: Boolean,
    val profile: OptimizationProfile,
)

/** Estado compartido entre la UI y [GameSessionService]. */
object GameSessionState {
    val session = MutableStateFlow<GameSession?>(null)
    val fps = MutableStateFlow<Float?>(null)
    /** Motivo legible del último valor de FPS (o de por qué es N/D). */
    val fpsStatus = MutableStateFlow("Sin medición")
    val overlayVisible = MutableStateFlow(true)
    val watching = MutableStateFlow(false)
}

class GameBoostController(
    private val context: Context,
    private val optimizer: OptimizationEngine,
    private val undo: UndoManager,
    private val log: OperationLogRepository,
    private val apps: AppRepository,
) {
    val session: StateFlow<GameSession?> = GameSessionState.session.asStateFlow()

    suspend fun prepare(pkg: String, profile: OptimizationProfile): OptimizationPlan =
        optimizer.buildPlan(profile, focusPackage = pkg)

    /**
     * Aplica el plan aprobado, abre el juego (si [launch]) y arranca el servicio de sesión.
     * Los cambios reversibles se revierten automáticamente al terminar la sesión.
     */
    suspend fun startSession(
        pkg: String,
        plan: OptimizationPlan,
        selectedIds: Set<String>,
        simulate: Boolean,
        auto: Boolean = false,
        launch: Boolean = true,
    ): OptimizationResult {
        val label = apps.label(pkg)
        val result = optimizer.execute(plan, selectedIds, simulate, LogCategory.GAME, "Game Booster: $label (${plan.profile.title})")
        GameSessionState.session.value = GameSession(
            packageName = pkg,
            label = label,
            startedAt = System.currentTimeMillis(),
            logId = result.logId.takeIf { result.undoAvailable },
            auto = auto,
            profile = plan.profile,
        )
        GameSessionState.fps.value = null
        GameSessionService.start(context)
        if (launch) apps.launch(pkg)
        return result
    }

    /** Termina la sesión y restaura los ajustes temporales (animaciones, modo de juego, apps desactivadas…). */
    suspend fun endSession(): List<String> {
        val s = GameSessionState.session.value ?: return emptyList()
        GameSessionState.session.value = null
        GameSessionState.fps.value = null
        val messages = s.logId?.let { id -> log.find(id)?.takeIf { it.canUndo }?.let { undo.undo(it) } }.orEmpty()
        log.add(
            LogCategory.GAME,
            "Fin de sesión: ${s.label}",
            listOf("Duración: ${(System.currentTimeMillis() - s.startedAt) / 60_000} min") + messages,
        )
        GameSessionService.refresh(context)
        return messages
    }

    fun setWatching(enabled: Boolean) {
        GameSessionState.watching.value = enabled
        if (enabled) GameSessionService.start(context) else GameSessionService.refresh(context)
    }
}
