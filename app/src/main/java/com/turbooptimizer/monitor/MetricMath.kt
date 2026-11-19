package com.turbooptimizer.monitor

/**
 * Cálculos puros de las métricas del sistema (sin dependencias de Android, testeables en JVM).
 */
object MetricMath {

    private val WHITESPACE = Regex("\\s+")

    // ---------- CPU ----------

    /** Tiempos acumulados de la línea agregada `cpu` de /proc/stat, en jiffies. */
    data class CpuTimes(val total: Long, val idle: Long)

    /**
     * Lee la línea `cpu  user nice system idle iowait irq softirq steal ...`.
     * idle = idle + iowait (iowait no es trabajo útil). total = suma de los 8 primeros campos
     * (los campos guest ya están incluidos en user/nice).
     */
    fun parseCpuTimes(procStat: String): CpuTimes? {
        val line = procStat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val values = line.removePrefix("cpu").trim().split(WHITESPACE).map { it.toLongOrNull() ?: return null }
        if (values.size < 4) return null
        val total = values.take(8).sum()
        val idle = values[3] + values.getOrElse(4) { 0L }
        return CpuTimes(total = total, idle = idle)
    }

    /**
     * Porcentaje de uso entre dos lecturas: 1 - Δidle/Δtotal. Devuelve null si no hay referencia
     * previa, si el contador retrocedió (reinicio) o si los deltas son incoherentes.
     */
    fun cpuPercent(prev: CpuTimes?, cur: CpuTimes): Float? {
        if (prev == null) return null
        val dTotal = cur.total - prev.total
        val dIdle = cur.idle - prev.idle
        if (dTotal <= 0L || dIdle < 0L || dIdle > dTotal) return null
        return ((1.0 - dIdle.toDouble() / dTotal) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    // ---------- RAM ----------

    /** RAM usada = total − disponible, acotada a [0, total]. Devuelve una fracción 0..1. */
    fun ramUsedFraction(total: Long, avail: Long): Float {
        if (total <= 0L) return 0f
        val used = (total - avail).coerceIn(0L, total)
        return (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    fun ramUsedBytes(total: Long, avail: Long): Long = (total - avail).coerceIn(0L, maxOf(total, 0L))

    // ---------- Temperatura (HAL térmica vía dumpsys thermalservice) ----------

    /** Tipos de sensor de android.os.Temperature. */
    const val TYPE_CPU = 0
    const val TYPE_GPU = 1
    const val TYPE_BATTERY = 2
    const val TYPE_SKIN = 3

    data class ThermalSensor(val name: String, val type: Int, val tempC: Float)

    /** Coincide con `Temperature{mValue=39.5, mType=0, mName=cpu0-silver-usr, mStatus=0}`. */
    private val SENSOR = Regex("""mValue=(-?[\d.]+),\s*mType=(-?\d+)(?:,\s*mName=([^,}\r\n]*))?""")

    /** Descarta valores no plausibles (sensor sin datos suele dar 0 o valores fuera de rango). */
    fun isPlausibleTemp(c: Float): Boolean = c.isFinite() && c > 0.5f && c < 125f

    fun parseThermalSensors(section: String): List<ThermalSensor> =
        SENSOR.findAll(section).mapNotNull { m ->
            val value = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
            val type = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (!isPlausibleTemp(value)) return@mapNotNull null
            val name = m.groupValues[3].trim().ifEmpty { "tipo $type" }
            ThermalSensor(name, type, value)
        }.toList()

    /** Sensores que no reflejan el SoC aunque sean de tipo CPU (p. ej. paneles, módem, cargador). */
    private val NOT_SOC = Regex("skin|batt|ambient|usb|modem|wifi|camera|display|charg", RegexOption.IGNORE_CASE)

    /** CPU: el sensor más caliente entre los de tipo CPU que no son auxiliares. */
    fun selectCpu(sensors: List<ThermalSensor>): ThermalSensor? =
        sensors.filter { it.type == TYPE_CPU && !NOT_SOC.containsMatchIn(it.name) }.maxByOrNull { it.tempC }

    fun selectOfType(sensors: List<ThermalSensor>, type: Int): ThermalSensor? =
        sensors.filter { it.type == type }.maxByOrNull { it.tempC }
}
