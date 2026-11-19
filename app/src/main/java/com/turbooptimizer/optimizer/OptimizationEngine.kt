package com.turbooptimizer.optimizer

import android.app.ActivityManager
import android.content.Context
import com.turbooptimizer.apps.AppRepository
import com.turbooptimizer.core.safety.SafetyGuard
import com.turbooptimizer.core.shizuku.CommandPolicy
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShellResult
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.data.OperationLogRepository
import com.turbooptimizer.data.OptimizationProfile
import com.turbooptimizer.data.SettingsRepository
import com.turbooptimizer.data.UndoAction
import com.turbooptimizer.monitor.SystemMonitor
import com.turbooptimizer.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class ActionKind {
    FORCE_STOP, KILL_BACKGROUND, LIMITED_KILL, RESTRICT_BACKGROUND, DISABLE_PACKAGE,
    GAME_MODE, ANIMATION_SCALE, TRIM_CACHES, KILL_ALL_BACKGROUND,
}

enum class Risk(val label: String) { LOW("Riesgo bajo"), MEDIUM("Riesgo medio") }

/** Una acción concreta que se mostrará al usuario ANTES de ejecutarse. */
data class PlannedAction(
    val id: String,
    val kind: ActionKind,
    val title: String,
    val explanation: String,
    val commands: List<List<String>>,
    val apiDescription: String? = null,
    val target: String? = null,
    val value: String? = null,
    val requiresShizuku: Boolean,
    val reversible: Boolean,
    val selectedByDefault: Boolean = true,
    val risk: Risk = Risk.LOW,
    val ramKb: Long? = null,
) {
    val commandText: String
        get() = apiDescription ?: commands.joinToString("\n") { ShellCommands.display(it) }
}

data class OptimizationPlan(
    val profile: OptimizationProfile,
    val actions: List<PlannedAction>,
    val notes: List<String>,
    val limitedMode: Boolean,
    val focusPackage: String?,
)

data class ActionOutcome(val action: PlannedAction, val success: Boolean, val message: String)

data class OptimizationResult(
    val outcomes: List<ActionOutcome>,
    val ramBefore: Long,
    val ramAfter: Long,
    val simulated: Boolean,
    val logId: Long,
    val undoAvailable: Boolean,
)

class OptimizationEngine(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val safety: SafetyGuard,
    private val settings: SettingsRepository,
    private val log: OperationLogRepository,
    private val apps: AppRepository,
    private val monitor: SystemMonitor,
) {
    private val am = context.getSystemService(ActivityManager::class.java)

    /**
     * Construye el plan según el perfil. No modifica nada.
     * @param focusPackage juego que se priorizará y nunca se detendrá.
     */
    suspend fun buildPlan(profile: OptimizationProfile, focusPackage: String? = null): OptimizationPlan = withContext(Dispatchers.IO) {
        val s = settings.current()
        val ready = shizuku.isReady
        val focus = (focusPackage ?: s.selectedGame)?.takeIf { apps.isInstalled(it) }
        val extra = setOfNotNull(focus)
        val notes = mutableListOf<String>()
        val actions = mutableListOf<PlannedAction>()

        if (ready) apps.invalidateRunning()
        val running = if (ready) apps.runningPss() else null
        fun prot(pkg: String) = safety.protectionReason(pkg, s.whitelist, extra)

        val blacklist = s.blacklist.filter { apps.isInstalled(it) && prot(it) == null }.sortedBy { apps.label(it) }
        val skipped = s.blacklist.filter { apps.isInstalled(it) && prot(it) != null }
        if (skipped.isNotEmpty()) {
            notes += "Se omiten ${skipped.size} apps de tu lista negra porque están protegidas: " +
                skipped.joinToString { "${apps.label(it)} (${prot(it)})" }
        }
        if (s.blacklist.isEmpty()) {
            notes += "Tu lista negra está vacía. Añade en Aplicaciones o en Seguridad las apps que permites detener para una optimización más eficaz."
        }
        val runningBlack = if (running != null) blacklist.filter { it in running } else blacklist
        if (running != null && blacklist.size > runningBlack.size) {
            notes += "${blacklist.size - runningBlack.size} apps de la lista negra no se están ejecutando y no necesitan acción."
        }
        // Otras apps de usuario en ejecución (solo se cierran sus procesos en segundo plano).
        val others: List<String> = running?.entries
            ?.filter { (pkg, _) -> pkg !in s.blacklist && CommandPolicy.isValidPackage(pkg) && apps.isUserApp(pkg) && prot(pkg) == null }
            ?.sortedByDescending { it.value }
            ?.map { it.key }
            ?.take(15)
            .orEmpty()

        if (!ready) {
            notes += "Modo limitado (sin Shizuku): solo se puede pedir a Android que cierre procesos en segundo plano de las apps de tu lista negra. Android decide si los cierra."
            blacklist.forEach { actions += limitedKill(it) }
            if (profile != OptimizationProfile.BALANCED) {
                notes += "Las acciones avanzadas del perfil ${profile.title} requieren Shizuku."
            }
            return@withContext OptimizationPlan(profile, actions, notes, limitedMode = true, focusPackage = focus)
        }

        when (profile) {
            OptimizationProfile.BALANCED -> {
                runningBlack.forEach { actions += killBg(it, running?.get(it)) }
                others.take(8).forEach { actions += killBg(it, running?.get(it), selected = false) }
            }
            OptimizationProfile.PERFORMANCE -> {
                runningBlack.forEach { actions += forceStop(it, running?.get(it)) }
                others.forEach { actions += killBg(it, running?.get(it)) }
                focus?.let { actions += gameMode(it) }
                actions += animations("0.5")
            }
            OptimizationProfile.EXTREME -> {
                blacklist.forEach { pkg ->
                    actions += forceStop(pkg, running?.get(pkg))
                    actions += restrictBackground(pkg)
                    actions += disablePackage(pkg)
                }
                others.forEach { actions += killBg(it, running?.get(it)) }
                actions += killAll()
                actions += trimCaches()
                focus?.let { actions += gameMode(it) }
                actions += animations("0")
                notes += "La desactivación temporal de apps está desmarcada por defecto. Márcala solo si sabes que no necesitas esas apps ahora; podrás reactivarlas desde el panel principal o el Registro."
            }
        }
        if (focus != null) notes += "Juego protegido y priorizado: ${apps.label(focus)}."
        OptimizationPlan(profile, actions, notes, limitedMode = false, focusPackage = focus)
    }

    suspend fun execute(
        plan: OptimizationPlan,
        selectedIds: Set<String>,
        simulate: Boolean,
        category: LogCategory = LogCategory.OPTIMIZE,
        title: String = "Optimización · perfil ${plan.profile.title}",
    ): OptimizationResult = withContext(Dispatchers.IO) {
        val s = settings.current()
        val before = monitor.memoryInfo().availMem
        val outcomes = mutableListOf<ActionOutcome>()
        val undo = mutableListOf<UndoAction>()
        val extra = setOfNotNull(plan.focusPackage)
        for (action in plan.actions.filter { it.id in selectedIds }) {
            outcomes += when {
                simulate -> ActionOutcome(action, true, "Simulación: no se aplicó ningún cambio")
                action.requiresShizuku && !shizuku.isReady -> ActionOutcome(action, false, "Shizuku no está disponible")
                else -> runCatching { apply(action, s, extra, undo) }
                    .getOrElse { ActionOutcome(action, false, it.message ?: it.toString()) }
            }
        }
        if (!simulate && outcomes.isNotEmpty()) delay(1_500) // deja que Android recupere la memoria
        val after = monitor.memoryInfo().availMem
        apps.invalidateRunning()
        val entry = log.add(
            category = category,
            title = title,
            details = outcomes.map { (if (it.success) "✔ " else "✖ ") + it.action.title + " — " + it.message } +
                "RAM disponible: ${formatBytes(before)} → ${formatBytes(after)}",
            success = outcomes.all { it.success },
            simulated = simulate,
            ramBefore = before,
            ramAfter = after,
            undo = undo,
        )
        OptimizationResult(outcomes, before, after, simulate, entry.id, undo.isNotEmpty())
    }

    private suspend fun apply(a: PlannedAction, s: AppSettings, extra: Set<String>, undo: MutableList<UndoAction>): ActionOutcome {
        // Revalidación justo antes de actuar (la situación puede haber cambiado desde el plan).
        val pkg = a.target
        if (pkg != null && a.kind in STOP_KINDS) {
            safety.protectionReason(pkg, s.whitelist, extra)?.let { return ActionOutcome(a, false, "Omitida: $it") }
        }
        return when (a.kind) {
            ActionKind.FORCE_STOP, ActionKind.KILL_BACKGROUND, ActionKind.KILL_ALL_BACKGROUND, ActionKind.TRIM_CACHES ->
                outcome(a, shizuku.exec(a.commands.first(), 30_000))
            ActionKind.LIMITED_KILL -> {
                am.killBackgroundProcesses(requireNotNull(pkg))
                ActionOutcome(a, true, "Solicitado a Android")
            }
            ActionKind.RESTRICT_BACKGROUND -> {
                val p = requireNotNull(pkg)
                val prev = parseAppOpsMode(shizuku.exec(ShellCommands.getBackground(p)).stdout)
                val r = shizuku.exec(ShellCommands.setBackground(p, "ignore"))
                if (r.isSuccess) undo += UndoAction.SetBackgroundMode(p, prev)
                outcome(a, r)
            }
            ActionKind.DISABLE_PACKAGE -> {
                val p = requireNotNull(pkg)
                val r = shizuku.exec(ShellCommands.disable(p))
                if (r.isSuccess) {
                    undo += UndoAction.EnablePackage(p)
                    settings.update { it.copy(disabledByApp = it.disabledByApp + p) }
                }
                outcome(a, r)
            }
            ActionKind.GAME_MODE -> {
                val p = requireNotNull(pkg)
                val r = shizuku.exec(ShellCommands.gameMode(p, "performance"))
                if (r.isSuccess) undo += UndoAction.SetGameMode(p, "standard")
                if (!r.isSuccess) ActionOutcome(a, false, "El juego o el fabricante no admiten modos de juego (${r.message})")
                else outcome(a, r)
            }
            ActionKind.ANIMATION_SCALE -> {
                val value = requireNotNull(a.value)
                var ok = true
                for (key in CommandPolicy.ANIMATION_KEYS) {
                    val prev = shizuku.exec(ShellCommands.getGlobal(key)).stdout.trim()
                        .takeIf { it.toFloatOrNull() != null }?.let { normalizeScale(it) } ?: "1.0"
                    val r = shizuku.exec(ShellCommands.putGlobal(key, value))
                    if (r.isSuccess) undo += UndoAction.RestoreSetting(key, prev) else ok = false
                }
                ActionOutcome(a, ok, if (ok) "Aplicado" else "Algunos valores no se pudieron cambiar")
            }
        }
    }

    private fun normalizeScale(v: String): String = when (v.toFloatOrNull()) {
        0f -> "0"
        0.5f -> "0.5"
        1.5f -> "1.5"
        2f -> "2.0"
        else -> "1.0"
    }

    private fun outcome(a: PlannedAction, r: ShellResult) =
        ActionOutcome(a, r.isSuccess, if (r.isSuccess) "Aplicado" else r.message)

    private fun parseAppOpsMode(out: String): String {
        val mode = Regex("RUN_ANY_IN_BACKGROUND:\\s*(\\w+)").find(out)?.groupValues?.get(1)
        return mode?.takeIf { it in setOf("allow", "ignore", "default") } ?: "default"
    }

    // ----------------- Constructores de acciones -----------------

    private fun ramText(kb: Long?) = kb?.let { " (~${formatBytes(it * 1024)} en RAM)" }.orEmpty()

    private fun forceStop(pkg: String, kb: Long?) = PlannedAction(
        id = "fs:$pkg", kind = ActionKind.FORCE_STOP,
        title = "Detener ${apps.label(pkg)}${ramText(kb)}",
        explanation = "Equivale a «Forzar detención» en Ajustes: cierra la app y sus servicios. No borra datos. " +
            "Volverá a funcionar normalmente cuando la abras; hasta entonces puede no mostrar notificaciones.",
        commands = listOf(ShellCommands.forceStop(pkg)), target = pkg,
        requiresShizuku = true, reversible = false, ramKb = kb,
    )

    private fun killBg(pkg: String, kb: Long?, selected: Boolean = true) = PlannedAction(
        id = "kill:$pkg", kind = ActionKind.KILL_BACKGROUND,
        title = "Cerrar procesos en segundo plano de ${apps.label(pkg)}${ramText(kb)}",
        explanation = "Android cierra solo los procesos de la app que no están visibles ni en uso. La app no se desactiva y sus notificaciones siguen funcionando.",
        commands = listOf(ShellCommands.killBackground(pkg)), target = pkg,
        requiresShizuku = true, reversible = false, selectedByDefault = selected, ramKb = kb,
    )

    private fun limitedKill(pkg: String) = PlannedAction(
        id = "limited:$pkg", kind = ActionKind.LIMITED_KILL,
        title = "Pedir a Android que cierre ${apps.label(pkg)} en segundo plano",
        explanation = "API pública de Android. Solo afecta a procesos en segundo plano; Android puede ignorar la petición.",
        commands = emptyList(), apiDescription = "ActivityManager.killBackgroundProcesses(\"$pkg\")",
        target = pkg, requiresShizuku = false, reversible = false,
    )

    private fun restrictBackground(pkg: String) = PlannedAction(
        id = "bg:$pkg", kind = ActionKind.RESTRICT_BACKGROUND,
        title = "Restringir segundo plano de ${apps.label(pkg)}",
        explanation = "Cambia el permiso RUN_ANY_IN_BACKGROUND a «ignore»: la app no podrá ejecutarse en segundo plano. " +
            "Se guarda el valor anterior para poder restaurarlo desde el Registro.",
        commands = listOf(ShellCommands.getBackground(pkg), ShellCommands.setBackground(pkg, "ignore")),
        target = pkg, requiresShizuku = true, reversible = true,
    )

    private fun disablePackage(pkg: String) = PlannedAction(
        id = "disable:$pkg", kind = ActionKind.DISABLE_PACKAGE,
        title = "Desactivar temporalmente ${apps.label(pkg)}",
        explanation = "La app desaparece del cajón y no podrá ejecutarse hasta que la reactives (sus datos se conservan). " +
            "Reversible con un toque desde el panel principal, el Registro o Ajustes de Android.",
        commands = listOf(ShellCommands.disable(pkg)), target = pkg,
        requiresShizuku = true, reversible = true, selectedByDefault = false, risk = Risk.MEDIUM,
    )

    private fun gameMode(pkg: String) = PlannedAction(
        id = "game:$pkg", kind = ActionKind.GAME_MODE,
        title = "Prioridad de rendimiento para ${apps.label(pkg)}",
        explanation = "Activa el modo «performance» de Game Mode de Android para el juego. Solo funciona si el juego y el fabricante lo admiten. Reversible.",
        commands = listOf(ShellCommands.gameMode(pkg, "performance")), target = pkg,
        requiresShizuku = true, reversible = true,
    )

    private fun animations(scale: String) = PlannedAction(
        id = "anim:$scale", kind = ActionKind.ANIMATION_SCALE,
        title = if (scale == "0") "Desactivar animaciones del sistema" else "Reducir animaciones del sistema a ${scale}x",
        explanation = "Cambia las tres escalas de animación (las mismas de Opciones de desarrollador). La interfaz se siente más rápida. " +
            "Se guardan los valores actuales para restaurarlos.",
        commands = CommandPolicy.ANIMATION_KEYS.map { ShellCommands.putGlobal(it, scale) },
        value = scale, requiresShizuku = true, reversible = true,
    )

    private fun trimCaches() = PlannedAction(
        id = "trim", kind = ActionKind.TRIM_CACHES,
        title = "Liberar caché de todas las apps (gestionado por Android)",
        explanation = "Pide al sistema que libere archivos de caché de las apps. Android decide qué borrar; no afecta a datos, fotos ni partidas guardadas.",
        commands = listOf(ShellCommands.trimCaches()), requiresShizuku = true, reversible = false,
    )

    private fun killAll() = PlannedAction(
        id = "killall", kind = ActionKind.KILL_ALL_BACKGROUND,
        title = "Cerrar todos los procesos en segundo plano",
        explanation = "Android finaliza los procesos en caché/segundo plano que puede cerrar con seguridad. La app en primer plano y los servicios del sistema no se ven afectados.",
        commands = listOf(ShellCommands.killAllBackground()), requiresShizuku = true, reversible = false,
    )

    companion object {
        private val STOP_KINDS = setOf(
            ActionKind.FORCE_STOP, ActionKind.KILL_BACKGROUND, ActionKind.LIMITED_KILL,
            ActionKind.RESTRICT_BACKGROUND, ActionKind.DISABLE_PACKAGE,
        )
    }
}
