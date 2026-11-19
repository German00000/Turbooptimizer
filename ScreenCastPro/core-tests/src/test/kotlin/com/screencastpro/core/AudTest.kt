package com.screencastpro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AudTest {

    private val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

    private fun count(data: ByteArray, pattern: ByteArray): Int {
        var n = 0
        for (i in 0..data.size - pattern.size) {
            if ((pattern.indices).all { data[i + it] == pattern[it] }) n++
        }
        return n
    }

    @Test
    fun accessUnitGetsAudBeforeSpsPpsAndIdr() {
        val mux = TsMuxer(hasAudio = false)
        val au = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val out = mux.videoAccessUnit(au, 0, keyframe = true)
        assertEquals(1, count(out, aud))
        assertEquals(1, count(out, aud + au))
    }

    @Test
    fun existingAudIsNotDuplicated() {
        val mux = TsMuxer(hasAudio = false)
        val au = aud + byteArrayOf(0, 0, 0, 1, 0x41, 9, 9)
        val out = mux.videoAccessUnit(au, 0, keyframe = false)
        assertEquals(1, count(out, aud))
    }
}
