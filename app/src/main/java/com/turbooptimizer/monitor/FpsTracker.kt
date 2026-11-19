package com.turbooptimizer.monitor

/** Resumen de las muestras de FPS de una sesión. */
data class FpsSummary(val min: Float, val max: Float, val avg: Float, val count: Int)

/**
 * Acumula las muestras aceptadas de FPS y calcula mínimo, máximo y promedio.
 * Solo guarda sumas, así que su coste es constante aunque la sesión dure horas.
 */
class FpsTracker {
    private var count = 0
    private var sum = 0.0
    private var min = Float.MAX_VALUE
    private var max = -Float.MAX_VALUE

    fun add(fps: Float) {
        if (!fps.isFinite() || fps < 0f) return
        count++
        sum += fps
        if (fps < min) min = fps
        if (fps > max) max = fps
    }

    fun reset() {
        count = 0
        sum = 0.0
        min = Float.MAX_VALUE
        max = -Float.MAX_VALUE
    }

    /** null mientras no haya ninguna muestra. */
    fun summary(): FpsSummary? =
        if (count == 0) null else FpsSummary(min = min, max = max, avg = (sum / count).toFloat(), count = count)
}
