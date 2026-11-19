package com.turbooptimizer.data

import android.content.Context
import com.turbooptimizer.core.shizuku.ShellCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class LogCategory(val label: String) {
    OPTIMIZE("Optimización"),
    CLEAN("Limpieza"),
    APPS("Aplicaciones"),
    GAME("Game Booster"),
    RAM_BOOST("RAM Boost"),
    UNDO("Deshacer"),
    RECORDER("Grabador"),
}

/** Acción que revierte un cambio aplicado mediante Shizuku. */
@Serializable
sealed class UndoAction {
    abstract fun description(): String
    abstract fun command(): List<String>

    @Serializable
    @SerialName("enable_package")
    data class EnablePackage(val pkg: String) : UndoAction() {
        override fun description() = "Reactivar $pkg"
        override fun command() = ShellCommands.enable(pkg)
    }

    @Serializable
    @SerialName("background_mode")
    data class SetBackgroundMode(val pkg: String, val mode: String) : UndoAction() {
        override fun description() = "Restaurar segundo plano de $pkg ($mode)"
        override fun command() = ShellCommands.setBackground(pkg, mode)
    }

    @Serializable
    @SerialName("restore_setting")
    data class RestoreSetting(val key: String, val value: String) : UndoAction() {
        override fun description() = "Restaurar $key = $value"
        override fun command() = ShellCommands.putGlobal(key, value)
    }

    @Serializable
    @SerialName("game_mode")
    data class SetGameMode(val pkg: String, val mode: String = "standard") : UndoAction() {
        override fun description() = "Restaurar modo de juego de $pkg ($mode)"
        override fun command() = ShellCommands.gameMode(pkg, mode)
    }
}

@Serializable
data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val category: LogCategory,
    val title: String,
    val details: List<String> = emptyList(),
    val success: Boolean = true,
    val simulated: Boolean = false,
    val bytesFreed: Long = 0,
    val ramBeforeBytes: Long? = null,
    val ramAfterBytes: Long? = null,
    val undo: List<UndoAction> = emptyList(),
    val undone: Boolean = false,
) {
    val canUndo: Boolean get() = undo.isNotEmpty() && !undone && !simulated
}

/** Registro local de operaciones (nunca sale del dispositivo). */
class OperationLogRepository(context: Context) {

    private val file = File(context.filesDir, "operation_log.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    private var loaded = false
    private var lastId = 0L

    private suspend fun ensureLoaded() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(ListSerializer(LogEntry.serializer()), file.readText()) else emptyList() }
                .getOrDefault(emptyList())
        }
        lastId = _entries.value.maxOfOrNull { it.id } ?: 0L
        loaded = true
    }

    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun add(
        category: LogCategory,
        title: String,
        details: List<String> = emptyList(),
        success: Boolean = true,
        simulated: Boolean = false,
        bytesFreed: Long = 0,
        ramBefore: Long? = null,
        ramAfter: Long? = null,
        undo: List<UndoAction> = emptyList(),
    ): LogEntry = mutex.withLock {
        ensureLoaded()
        lastId = maxOf(System.currentTimeMillis(), lastId + 1)
        val entry = LogEntry(
            id = lastId,
            timestamp = System.currentTimeMillis(),
            category = category,
            title = title,
            details = details.take(300),
            success = success,
            simulated = simulated,
            bytesFreed = bytesFreed,
            ramBeforeBytes = ramBefore,
            ramAfterBytes = ramAfter,
            undo = undo,
        )
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        persist()
        entry
    }

    suspend fun find(id: Long): LogEntry? = mutex.withLock {
        ensureLoaded()
        _entries.value.firstOrNull { it.id == id }
    }

    suspend fun markUndone(id: Long) = mutex.withLock {
        ensureLoaded()
        _entries.value = _entries.value.map { if (it.id == id) it.copy(undone = true) else it }
        persist()
    }

    suspend fun clear() = mutex.withLock {
        ensureLoaded()
        // Se conservan las entradas que aún se pueden deshacer para no perder la recuperación.
        _entries.value = _entries.value.filter { it.canUndo }
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(LogEntry.serializer()), _entries.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
