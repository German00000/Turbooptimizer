package com.screencastpro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class Level41Test {

    @Test
    fun phoneScreen720x1600At60IsCappedTo30() {
        // 720×1600 = 4500 macrobloques: 245 760 / 4500 = 54 FPS máximo en nivel 4.1.
        assertEquals(30, ProfileMath.capFpsForLevel41(720, 1600, 60))
    }

    @Test
    fun fullHd1080pCappedTo30() {
        assertEquals(30, ProfileMath.capFpsForLevel41(1920, 1080, 60))
    }

    @Test
    fun hd720pLandscapeKeeps60() {
        assertEquals(60, ProfileMath.capFpsForLevel41(1280, 720, 60))
    }

    @Test
    fun lowerRequestIsNotRaised() {
        assertEquals(24, ProfileMath.capFpsForLevel41(720, 1600, 24))
    }

    @Test
    fun macroblocksRoundUpToWholeBlocks() {
        assertEquals(4500, ProfileMath.macroblocks(720, 1600))
        assertEquals(8160, ProfileMath.macroblocks(1920, 1080))
    }

    @Test
    fun nullPacketsAreValidTsWithPid0x1FFF() {
        val mux = TsMuxer(hasAudio = false)
        val data = mux.nullPackets(4)
        assertEquals(4 * 188, data.size)
        for (i in 0 until 4) {
            val p = data.copyOfRange(i * 188, i * 188 + 188)
            assertEquals(0x47.toByte(), p[0])
            assertEquals(0x1FFF, ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF))
        }
    }
}
