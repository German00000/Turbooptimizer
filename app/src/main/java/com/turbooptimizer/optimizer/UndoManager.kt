package com.turbooptimizer.optimizer

import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.data.LogEntry
import com.turbooptimizer.data.OperationLogRepository
import com.turbooptimizer.data.SettingsRepository
import com.turbooptimizer.data.UndoAction

/** Sistema de recuperación: revierte los cambios reversibles registrados en el log. */
class UndoManager(
    private val shizuku: ShizukuManager,
    private val settings: SettingsRepository,
    private val log: OperationLogRepository,
) {
    suspend fun undo(entry: LogEntry): List<String> {
        if (!entry.canUndo) return listOf("No hay cambios reversibles en esta operación")
        if (!shizuku.isReady) return listOf("Se necesita Shizuku activo para deshacer estos cambios")
        val messages = mutableListOf<String>()
        var allOk = true
        for (u in entry.undo.reversed()) {
            val r = shizuku.exec(u.command())
            if (!r.isSuccess) allOk = false
            messages += "${u.description()}: ${if (r.isSuccess) "OK" else r.message}"
            if (u is UndoAction.EnablePackage && r.isSuccess) {
                settings.update { it.copy(disabledByApp = it.disabledByApp - u.pkg) }
            }
        }
        log.markUndone(entry.id)
        log.add(LogCategory.UNDO, "Deshacer: ${entry.title}", messages, success = allOk)
        return messages
    }

    /** Reactiva todas las apps que TurboOptimizer desactivó temporalmente. */
    suspend fun restoreDisabled(): List<String> {
        val pkgs = settings.current().disabledByApp
        if (pkgs.isEmpty()) return listOf("No hay apps desactivadas")
        if (!shizuku.isReady) return listOf("Se necesita Shizuku para reactivar. También puedes hacerlo desde Ajustes > Aplicaciones.")
        val messages = mutableListOf<String>()
        val restored = mutableSetOf<String>()
        for (pkg in pkgs) {
            val r = shizuku.exec(ShellCommands.enable(pkg))
            if (r.isSuccess) restored += pkg
            messages += "$pkg: ${if (r.isSuccess) "reactivada" else r.message}"
        }
        settings.update { it.copy(disabledByApp = it.disabledByApp - restored) }
        log.add(LogCategory.UNDO, "Reactivar apps desactivadas", messages, success = restored.size == pkgs.size)
        return messages
    }
}
