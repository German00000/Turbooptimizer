package com.turbooptimizer.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsTrackerTest {

    @Test fun emptyTrackerHasNoSummary() {
        assertNull(FpsTracker().summary())
    }

    @Test fun computesMinMaxAndAverage() {
        val t = FpsTracker()
        listOf(60f, 30f, 90f).forEach(t::add)
        val s = t.summary()!!
        assertEquals(30f, s.min, 0f)
        assertEquals(90f, s.max, 0f)
        assertEquals(60f, s.avg, 0.001f)
        assertEquals(3, s.count)
    }

    @Test fun ignoresInvalidSamples() {
        val t = FpsTracker()
        t.add(Float.NaN)
        t.add(Float.POSITIVE_INFINITY)
        t.add(-5f)
        assertNull(t.summary())
        t.add(45f)
        assertEquals(1, t.summary()!!.count)
    }

    @Test fun zeroIsAValidSample() {
        // Una pantalla estática da 0 FPS: debe contar para el mínimo y el promedio.
        val t = FpsTracker()
        t.add(0f)
        t.add(60f)
        assertEquals(0f, t.summary()!!.min, 0f)
        assertEquals(30f, t.summary()!!.avg, 0.001f)
    }

    @Test fun resetClearsEverything() {
        val t = FpsTracker()
        t.add(120f)
        t.reset()
        assertNull(t.summary())
        t.add(10f)
        assertEquals(10f, t.summary()!!.max, 0f)
    }
}
