package com.turbooptimizer.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsMonitorTest {

    private val now = 10_000_000_000L

    /** Presentaciones cada [intervalNs] que terminan en [endNs]. */
    private fun presents(count: Int, intervalNs: Long, endNs: Long) = (0 until count).map { endNs - it * intervalNs }.reversed()

    // --- Contador compuesto (método alternativo) ---

    @Test fun parsesNormalReply() {
        assertEquals(0x0002a3f1L, FpsMonitor.parseFrameCount("Result: Parcel(0002a3f1    '....')"))
        assertEquals(0xffffff00L, FpsMonitor.parseFrameCount("Result: Parcel(ffffff00 '....')\n"))
    }

    @Test fun rejectsErrorReplies() {
        assertNull(FpsMonitor.parseFrameCount("Result: Parcel(Error: 0xffffffff \"Unknown error -1\")"))
        assertNull(FpsMonitor.parseFrameCount("Result: Parcel(Error: 0xfffffffb \"Permission denied\")"))
        assertNull(FpsMonitor.parseFrameCount("service: Service SurfaceFlinger does not exist"))
    }

    @Test fun rejectsUnexpectedFormats() {
        assertNull(FpsMonitor.parseFrameCount(""))
        assertNull(FpsMonitor.parseFrameCount("12345678"))
        assertNull(FpsMonitor.parseFrameCount("Result: Parcel(1234 '..')"))
    }

    // --- Lista de capas ---

    @Test fun selectsOnlyLayersOfExactPackage() {
        val out = """
            SurfaceView[com.game.x/com.game.x.Main]#0
            SurfaceView[com.game.xtra/com.game.xtra.Main]#0
            Surface(name=null)/@0x1a2b
            com.game.x/com.game.x.Main#0
        """.trimIndent()
        assertEquals(
            listOf("SurfaceView[com.game.x/com.game.x.Main]#0", "com.game.x/com.game.x.Main#0"),
            FpsMonitor.parseLayerNames(out, "com.game.x"),
        )
    }

    @Test fun layerListWithoutMatchIsEmpty() {
        assertEquals(emptyList<String>(), FpsMonitor.parseLayerNames("", "com.game.x"))
        assertEquals(emptyList<String>(), FpsMonitor.parseLayerNames("SurfaceView[com.other/x]#0", "com.game.x"))
    }

    // --- Marcas de presentación (método principal) ---

    @Test fun parsesPresentColumnAndIgnoresPendingRows() {
        val out = "16666667\n" +
            "1000\t2000\t3000\n" +
            "4000 5000 6000\n" +
            "9223372036854775807 0 0\n" +
            "Error: layer not found\n"
        assertEquals(listOf(3000L, 6000L), FpsMonitor.parseLatencyPresents(out))
    }

    @Test fun emptyLatencyOutputGivesNoFrames() {
        assertEquals(emptyList<Long>(), FpsMonitor.parseLatencyPresents(""))
        assertEquals(emptyList<Long>(), FpsMonitor.parseLatencyPresents("16666667\n"))
    }

    @Test fun computesFpsFromPresentTimes() {
        val stats = FpsMonitor.computeLatencyStats(presents(120, 10_000_000L, now), now)!!
        assertEquals(100f, stats.fps, 0.5f)
    }

    @Test fun reportsZeroWhenNothingPresentedRecently() {
        val stats = FpsMonitor.computeLatencyStats(presents(20, 10_000_000L, now - 3_000_000_000L), now)!!
        assertEquals(0f, stats.fps, 0f)
    }

    @Test fun needsAtLeastTwoFrames() {
        assertNull(FpsMonitor.computeLatencyStats(listOf(now), now))
        assertNull(FpsMonitor.computeLatencyStats(emptyList(), now))
    }

    @Test fun rejectsTimestampsFromAnotherClock() {
        assertNull(FpsMonitor.computeLatencyStats(presents(10, 10_000_000L, now + 60_000_000_000L), now))
    }

    @Test fun usesBufferSpanWhenBufferIsTruncated() {
        // 128 fotogramas a 240 Hz cubren ~0,5 s: el cálculo debe seguir siendo 240 FPS.
        val stats = FpsMonitor.computeLatencyStats(presents(128, 4_166_667L, now), now)!!
        assertEquals(240f, stats.fps, 1f)
    }
}
