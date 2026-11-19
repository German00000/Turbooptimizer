package com.turbooptimizer.core.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Etiqueta de Logcat de todos los mensajes de la app: `adb logcat -s TurboOptimizer`.
 * El componente que emite el mensaje va entre corchetes al principio, p. ej. `[FpsMonitor]`.
 */
const val DIAG_TAG = "TurboOptimizer"

enum class DiagLevel(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

/** Una línea del diagnóstico. El mensaje ya viene sanitizado por [Redactor]. */
data class DiagEntry(
    val timeMs: Long,
    val level: DiagLevel,
    val component: String,
    val message: String,
) {
    /** Formato parecido a Logcat: `HH:mm:ss.SSS L [Componente] mensaje`. */
    fun format(): String = "${TIME_FORMAT.format(java.time.Instant.ofEpochMilli(timeMs))} ${level.letter} [$component] $message"

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * Registro de diagnóstico de la app: guarda las últimas [CAPACITY] entradas en memoria (nunca se escriben
 * en disco) y las reenvía a Logcat con la etiqueta [DIAG_TAG]. Todo pasa por [Redactor].
 */
object Diagnostics {

    const val CAPACITY = 2_000

    private val lock = Any()
    private val items = ArrayDeque<DiagEntry>()
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())

    /** Entradas en orden cronológico (la más antigua primero). */
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    fun d(component: String, message: String) = record(DiagLevel.DEBUG, component, message)
    fun i(component: String, message: String) = record(DiagLevel.INFO, component, message)
    fun w(component: String, message: String, t: Throwable? = null) = record(DiagLevel.WARN, component, message, t)
    fun e(component: String, message: String, t: Throwable? = null) = record(DiagLevel.ERROR, component, message, t)

    fun record(level: DiagLevel, component: String, message: String, t: Throwable? = null) {
        val text = buildString {
            append(Redactor.clean(message))
            // Solo la clase y el mensaje de la excepción, sin traza: la traza no se muestra ni se copia.
            if (t != null) append(" (").append(t.javaClass.simpleName).append(": ").append(Redactor.clean(t.message.orEmpty())).append(')')
        }
        val entry = DiagEntry(System.currentTimeMillis(), level, component, text)
        synchronized(lock) {
            if (items.size == CAPACITY) items.removeFirst()
            items.addLast(entry)
            _entries.value = items.toList()
        }
        emitToLogcat(level, component, text)
    }

    fun clear() = synchronized(lock) {
        items.clear()
        _entries.value = emptyList()
    }

    /** Texto para copiar: cabecera (versión, dispositivo…) seguida de las líneas en orden cronológico. */
    fun export(entries: List<DiagEntry>, header: List<String>): String =
        (header + entries.map { it.format() }).joinToString("\n")

    private fun emitToLogcat(level: DiagLevel, component: String, text: String) {
        val line = "[$component] $text"
        // Logcat puede no estar disponible (p. ej. en pruebas JVM): el registro en memoria sigue funcionando.
        runCatching {
            when (level) {
                DiagLevel.DEBUG -> Log.d(DIAG_TAG, line)
                DiagLevel.INFO -> Log.i(DIAG_TAG, line)
                DiagLevel.WARN -> Log.w(DIAG_TAG, line)
                DiagLevel.ERROR -> Log.e(DIAG_TAG, line)
            }
        }
    }
}
