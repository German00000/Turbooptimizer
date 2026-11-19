package com.turbooptimizer.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(100f, FpsMonitor.computeLatencyFps(presents(120, 10_000_000L, now))!!, 0.5f)
    }

    @Test fun fpsDoesNotDependOnSystemClock() {
        // Las marcas de SurfaceFlinger pueden estar en cualquier base de reloj: solo importan sus diferencias.
        val a = FpsMonitor.computeLatencyFps(presents(60, 16_666_667L, 1_000_000_000L))
        val b = FpsMonitor.computeLatencyFps(presents(60, 16_666_667L, 5_000_000_000_000_000L))
        assertEquals(a!!, b!!, 0.001f)
        assertEquals(60f, a, 0.5f)
    }

    @Test fun needsAtLeastTwoFrames() {
        assertNull(FpsMonitor.computeLatencyFps(listOf(now)))
        assertNull(FpsMonitor.computeLatencyFps(emptyList()))
    }

    @Test fun staleOnlyWhenNewestMarkDidNotChange() {
        assertTrue(FpsMonitor.isStale(changedAtNs = 0L, nowNs = 2_000_000_000L))
        assertFalse(FpsMonitor.isStale(changedAtNs = 1_000_000_000L, nowNs = 2_000_000_000L))
    }

    @Test fun usesBufferSpanWhenBufferIsTruncated() {
        // 128 fotogramas a 240 Hz cubren ~0,5 s: el cálculo debe seguir siendo 240 FPS.
        assertEquals(240f, FpsMonitor.computeLatencyFps(presents(128, 4_166_667L, now))!!, 1f)
    }

    // --- Texto de estado (sustituye al «N/D» permanente) ---

    @Test fun placeholderExplainsWhyThereIsNoValue() {
        assertEquals("Sin permisos", FpsMonitor.placeholder(FpsMonitor.STATUS_NO_PERMISSION))
        assertEquals("Shizuku inactivo", FpsMonitor.placeholder(FpsMonitor.STATUS_SHIZUKU_OFF))
        assertEquals("No compatible", FpsMonitor.placeholder("No compatible: service call 1013 sin respuesta"))
        assertEquals("Midiendo…", FpsMonitor.placeholder("Reintentando: --list falló"))
        assertEquals("Midiendo…", FpsMonitor.placeholder(FpsMonitor.NO_MEASUREMENT))
    }
}
