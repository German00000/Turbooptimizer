package com.screencastpro

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico visible y copiable desde el propio teléfono (sin adb).
 * Se guarda en filesDir/diag.log con tamaño limitado. Duplica cada línea en logcat.
 * Las rutas con el token de sesión se sustituyen antes de escribir (ver StreamServer).
 */
object DiagLog {
    private const val FILE_NAME = "diag.log"
    private const val MAX_BYTES = 256 * 1024
    private const val KEEP_BYTES = 128 * 1024

    @Volatile private var file: File? = null
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    fun i(tag: String, msg: String) = write(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write(Log.ERROR, tag, msg, t)

    fun read(): String = synchronized(lock) {
        try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    }

    fun clear() = synchronized(lock) {
        try { file?.delete() } catch (_: Exception) {}
    }

    private fun write(priority: Int, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(clock.format(Date())).append(' ')
            append(when (priority) { Log.ERROR -> "E"; Log.WARN -> "W"; else -> "I" })
            append('/').append(tag).append(": ").append(msg)
            if (t != null) append(" [").append(t.javaClass.simpleName).append(": ").append(t.message ?: "sin detalle").append(']')
        }
        Log.println(priority, "ScreenCastPro", line)
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.exists() && f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(KEEP_BYTES))
                f.appendText(line + "\n")
            } catch (_: Exception) {
                // El registro nunca debe tumbar la app.
            }
        }
    }
}
