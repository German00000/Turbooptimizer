package com.screencastpro.core

import kotlin.math.max
import kotlin.math.min

enum class ThermalLevel { NORMAL, MODERATE, SEVERE, CRITICAL }

/** Muestra de estado tomada cada pocos segundos por el servicio. */
data class Sample(
    /** Eventos de cliente lento (cola llena) desde la muestra anterior. */
    val lagEvents: Int,
    /** Tiempo medio de escritura al socket en la ventana, en ms. */
    val avgWriteMs: Double,
    val thermal: ThermalLevel,
)

data class Decision(
    val level: Int,
    val bitrateBps: Int,
    val levelChanged: Boolean,
    val bitrateChanged: Boolean,
)

/**
 * Control de calidad adaptativo. Reglas:
 *  - Congestión (clientes lentos o escrituras lentas): baja bitrate; si ya está en mínimo, baja de escalón.
 *  - Calor: moderado → baja bitrate; severo → baja de escalón y bitrate fuerte; crítico → mínimo absoluto.
 *  - Sin problemas durante varios ciclos: sube bitrate hasta el objetivo y después recupera escalón,
 *    nunca por encima del escalón solicitado por el usuario.
 *
 * Índice de escalón 0 = mejor calidad. Sin Android: se puede probar en JVM.
 */
class AdaptiveController(
    private val levelCount: Int,
    private val topLevel: Int,
    private val bitrateFor: (level: Int) -> Int,
    private val minBps: Int = 400_000,
) {
    companion object {
        const val CONGESTION_WRITE_MS = 150.0
        const val RECOVER_AFTER_CALM_TICKS = 3
        const val STEP_UP_AFTER_CALM_TICKS = 6
    }

    var level: Int = topLevel
        private set
    var bitrate: Int = bitrateFor(topLevel)
        private set
    private var calmTicks = 0

    init {
        require(levelCount >= 1 && topLevel in 0 until levelCount) { "Escalón fuera de rango" }
    }

    fun update(s: Sample): Decision {
        val prevLevel = level
        val prevBitrate = bitrate
        val lowest = levelCount - 1
        val congested = s.lagEvents > 0 || s.avgWriteMs > CONGESTION_WRITE_MS

        when {
            s.thermal == ThermalLevel.CRITICAL -> {
                level = lowest
                bitrate = minBps
                calmTicks = 0
            }
            s.thermal == ThermalLevel.SEVERE -> {
                level = min(level + 1, lowest)
                bitrate = max(minBps, (bitrate * 0.6).toInt())
                calmTicks = 0
            }
            s.thermal == ThermalLevel.MODERATE -> {
                bitrate = max(minBps, (bitrate * 0.8).toInt())
                calmTicks = 0
            }
            congested -> {
                calmTicks = 0
                if (bitrate > minBps) {
                    bitrate = max(minBps, (bitrate * 0.7).toInt())
                } else if (level < lowest) {
                    level += 1
                    bitrate = max(minBps, min(bitrateFor(level), bitrate))
                }
            }
            else -> {
                calmTicks++
                val target = bitrateFor(level)
                if (calmTicks >= RECOVER_AFTER_CALM_TICKS && bitrate < target) {
                    bitrate = min(target, (bitrate * 1.15).toInt() + 1)
                }
                if (calmTicks >= STEP_UP_AFTER_CALM_TICKS * 2 && level > topLevel && bitrate >= target) {
                    level -= 1
                    bitrate = bitrateFor(level)
                    calmTicks = 0
                }
            }
        }
        bitrate = bitrate.coerceIn(minBps, bitrateFor(topLevel).coerceAtLeast(minBps))
        return Decision(
            level = level,
            bitrateBps = bitrate,
            levelChanged = level != prevLevel,
            bitrateChanged = bitrate != prevBitrate,
        )
    }
}
