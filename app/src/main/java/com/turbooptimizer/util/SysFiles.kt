package com.turbooptimizer.util

import java.io.File
import java.util.concurrent.TimeUnit

/** Lectura segura (solo lectura) de archivos de /proc, /sys y propiedades del sistema. */
object SysFiles {

    fun readText(path: String): String? = runCatching {
        val f = File(path)
        if (f.canRead()) f.readText().trim() else null
    }.getOrNull()

    fun readLong(path: String): Long? = readText(path)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()

    @Volatile private var propsCache: Map<String, String>? = null

    /**
     * Propiedades del sistema mediante `getprop` (permitido a las apps; SELinux oculta las sensibles).
     * Se lee una sola vez y se cachea.
     */
    fun props(): Map<String, String> {
        propsCache?.let { return it }
        val map = HashMap<String, String>()
        runCatching {
            val p = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
            val regex = Regex("^\\[(.+?)]: \\[(.*)]$")
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> regex.find(line)?.let { map[it.groupValues[1]] = it.groupValues[2] } }
            }
            p.waitFor(3, TimeUnit.SECONDS)
            p.destroy()
        }
        propsCache = map
        return map
    }

    fun prop(key: String): String? = props()[key]?.takeIf { it.isNotBlank() }
}
