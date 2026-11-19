package com.turbooptimizer.core.shizuku

import android.os.Process

/** Constructores de los comandos permitidos por [CommandPolicy]. */
object ShellCommands {
    private val userId: String get() = (Process.myUid() / 100_000).toString()

    fun forceStop(pkg: String) = listOf("am", "force-stop", pkg)
    fun killBackground(pkg: String) = listOf("am", "kill", pkg)
    fun killAllBackground() = listOf("am", "kill-all")
    fun disable(pkg: String) = listOf("pm", "disable-user", "--user", userId, pkg)
    fun enable(pkg: String) = listOf("pm", "enable", "--user", userId, pkg)
    fun trimCaches() = listOf("pm", "trim-caches", "999G")
    fun getBackground(pkg: String) = listOf("cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND")
    fun setBackground(pkg: String, mode: String) = listOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", mode)
    fun gameMode(pkg: String, mode: String) = listOf("cmd", "game", "mode", mode, pkg)
    fun getGlobal(key: String) = listOf("settings", "get", "global", key)
    fun putGlobal(key: String, value: String) = listOf("settings", "put", "global", key, value)
    fun meminfo() = listOf("dumpsys", "meminfo", "-c")
    fun thermal() = listOf("dumpsys", "thermalservice")
    fun cat(paths: List<String>) = listOf("cat") + paths
    fun surfaceFlingerFrames() = listOf("service", "call", "SurfaceFlinger", "1013")
    fun surfaceFlingerLayers() = listOf("dumpsys", "SurfaceFlinger", "--list")
    fun surfaceFlingerLatency(layer: String) = listOf("dumpsys", "SurfaceFlinger", "--latency", layer)

    fun display(cmd: List<String>) = cmd.joinToString(" ")
}
