package com.screencastpro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PsiRepetitionTest {

    private fun countPat(data: ByteArray): Int {
        var n = 0
        for (i in 0 until data.size / 188) {
            val o = i * 188
            val pid = ((data[o + 1].toInt() and 0x1F) shl 8) or (data[o + 2].toInt() and 0xFF)
            if (pid == TsMuxer.PID_PAT) n++
        }
        return n
    }

    private fun au(key: Boolean) = ByteArray(300) { if (it == 4) (if (key) 0x65 else 0x41).toByte() else 0x11 }

    @Test
    fun patIsRepeatedAtLeastEvery100Ms() {
        val mux = TsMuxer(hasAudio = false)
        // Keyframe en 0; no keyframes a 40, 100 y 120 ms.
        assertEquals(1, countPat(mux.videoAccessUnit(au(true), 0, keyframe = true)))
        assertEquals(0, countPat(mux.videoAccessUnit(au(false), 40_000, keyframe = false)))
        assertEquals(1, countPat(mux.videoAccessUnit(au(false), 100_000, keyframe = false)))
        assertEquals(0, countPat(mux.videoAccessUnit(au(false), 120_000, keyframe = false)))
    }
}
