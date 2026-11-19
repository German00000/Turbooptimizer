package com.turbooptimizer.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricMathTest {

    // ---------- CPU ----------

    @Test fun parsesAggregateCpuLine() {
        val stat = "cpu  4000 0 3000 90000 200 0 100 0 0 0\ncpu0 500 0 400 11000 20 0 10 0 0 0\n"
        val t = MetricMath.parseCpuTimes(stat)!!
        assertEquals(97300L, t.total) // 4000+0+3000+90000+200+0+100+0
        assertEquals(90200L, t.idle)  // idle + iowait
    }

    @Test fun cpuPercentFromDeltas() {
        val a = MetricMath.CpuTimes(total = 1000, idle = 800)
        val b = MetricMath.CpuTimes(total = 1200, idle = 860) // Δtotal 200, Δidle 60 → 70 %
        assertEquals(70f, MetricMath.cpuPercent(a, b)!!, 0.01f)
    }

    @Test fun cpuPercentNeedsReferenceAndRejectsCounterReset() {
        val cur = MetricMath.CpuTimes(1000, 800)
        assertNull(MetricMath.cpuPercent(null, cur))
        assertNull(MetricMath.cpuPercent(MetricMath.CpuTimes(2000, 100), cur)) // contador retrocedió
        assertNull(MetricMath.cpuPercent(cur, cur)) // Δtotal = 0
    }

    @Test fun cpuPercentStaysInRange() {
        val a = MetricMath.CpuTimes(1000, 500)
        val busy = MetricMath.CpuTimes(1100, 500)
        assertEquals(100f, MetricMath.cpuPercent(a, busy)!!, 0.01f)
        val idle = MetricMath.CpuTimes(1100, 600)
        assertEquals(0f, MetricMath.cpuPercent(a, idle)!!, 0.01f)
    }

    @Test fun rejectsMalformedProcStat() {
        assertNull(MetricMath.parseCpuTimes(""))
        assertNull(MetricMath.parseCpuTimes("cpu  1 2"))
        assertNull(MetricMath.parseCpuTimes("cpu  1 x 3 4"))
    }

    // ---------- RAM ----------

    @Test fun ramUsedIsTotalMinusAvailable() {
        assertEquals(0.25f, MetricMath.ramUsedFraction(total = 4000, avail = 3000), 0.0001f)
        assertEquals(1000L, MetricMath.ramUsedBytes(total = 4000, avail = 3000))
    }

    @Test fun ramFractionClampedBetweenZeroAndOne() {
        assertEquals(0f, MetricMath.ramUsedFraction(total = 4000, avail = 5000), 0f)
        assertEquals(1f, MetricMath.ramUsedFraction(total = 4000, avail = -10), 0f)
        assertEquals(0f, MetricMath.ramUsedFraction(total = 0, avail = 0), 0f)
    }

    // ---------- Temperatura ----------

    private val dump = """
        Current temperatures from HAL:
        	Temperature{mValue=38.5, mType=0, mName=cpu0-silver-usr, mStatus=0}
        	Temperature{mValue=41.0, mType=0, mName=cpu4-gold-usr, mStatus=0}
        	Temperature{mValue=45.5, mType=0, mName=cpu-skin-therm, mStatus=0}
        	Temperature{mValue=0.0, mType=0, mName=cpu7-invalid, mStatus=0}
        	Temperature{mValue=36.2, mType=1, mName=gpu0, mStatus=0}
        	Temperature{mValue=33.0, mType=3, mName=skin-therm, mStatus=0}
        	Temperature{mValue=29.9, mType=2, mName=battery, mStatus=0}
        Current cooling devices from HAL:
        	CoolingDevice{mValue=0, mType=0, mName=thermal-cpufreq}
    """.trimIndent()

    @Test fun parsesSensorsWithNamesAndDecimals() {
        val s = MetricMath.parseThermalSensors(dump)
        assertEquals(6, s.size) // el de 0.0 se descarta
        assertEquals("cpu4-gold-usr", s.first { it.tempC == 41.0f }.name)
        assertEquals(36.2f, s.first { it.type == MetricMath.TYPE_GPU }.tempC, 0.001f)
    }

    @Test fun selectsCpuSensorIgnoringAuxiliaryNames() {
        val s = MetricMath.parseThermalSensors(dump)
        // cpu-skin-therm (45.5) es de tipo CPU pero es auxiliar: no debe elegirse.
        val cpu = MetricMath.selectCpu(s)!!
        assertEquals("cpu4-gold-usr", cpu.name)
        assertEquals(41.0f, cpu.tempC, 0.001f)
    }

    @Test fun selectsSkinAndBatterySensorsByType() {
        val s = MetricMath.parseThermalSensors(dump)
        assertEquals(33.0f, MetricMath.selectOfType(s, MetricMath.TYPE_SKIN)!!.tempC, 0.001f)
        assertEquals(29.9f, MetricMath.selectOfType(s, MetricMath.TYPE_BATTERY)!!.tempC, 0.001f)
    }

    @Test fun noSensorsMeansNoValue() {
        assertTrue(MetricMath.parseThermalSensors("Current temperatures from HAL:\n").isEmpty())
        assertNull(MetricMath.selectCpu(emptyList()))
    }

    @Test fun cpuSensorNotFoundWhenOnlyAuxiliaryExists() {
        val s = MetricMath.parseThermalSensors("Temperature{mValue=50.0, mType=0, mName=battery-cpu, mStatus=0}")
        assertNull(MetricMath.selectCpu(s))
    }
}
