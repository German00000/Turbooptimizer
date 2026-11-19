package com.screencastpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsMuxerTest {

    private fun packets(data: ByteArray): List<ByteArray> {
        assertEquals("El flujo debe ser múltiplo de 188", 0, data.size % 188)
        return (0 until data.size / 188).map { data.copyOfRange(it * 188, it * 188 + 188) }
    }

    private fun pid(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
    private fun pusi(p: ByteArray) = (p[1].toInt() and 0x40) != 0
    private fun cc(p: ByteArray) = p[3].toInt() and 0x0F
    private fun payloadStart(p: ByteArray): Int {
        val afc = (p[3].toInt() shr 4) and 0x03
        if (afc == 1) return 4
        val afLen = p[4].toInt() and 0xFF
        return 5 + afLen
    }

    private fun pcrOf(p: ByteArray): Long? {
        val afc = (p[3].toInt() shr 4) and 0x03
        if (afc < 2 || (p[4].toInt() and 0xFF) == 0) return null
        if ((p[5].toInt() and 0x10) == 0) return null
        val b = p
        return ((b[6].toLong() and 0xFF) shl 25) or ((b[7].toLong() and 0xFF) shl 17) or
            ((b[8].toLong() and 0xFF) shl 9) or ((b[9].toLong() and 0xFF) shl 1) or
            ((b[10].toLong() and 0x80) shr 7)
    }

    /** Reensambla el PES de un PID concreto a partir de sus paquetes. */
    private fun reassemble(all: List<ByteArray>, wantedPid: Int): List<ByteArray> {
        val pes = mutableListOf<ByteArray>()
        var current: java.io.ByteArrayOutputStream? = null
        for (p in all) {
            if (pid(p) != wantedPid) continue
            if (pusi(p)) {
                current?.let { pes.add(it.toByteArray()) }
                current = java.io.ByteArrayOutputStream()
            }
            current?.write(p, payloadStart(p), 188 - payloadStart(p))
        }
        current?.let { pes.add(it.toByteArray()) }
        return pes
    }

    private fun decodePts(pes: ByteArray): Long {
        val b = pes
        return ((b[9].toLong() and 0x0E) shl 29) or
            ((b[10].toLong() and 0xFF) shl 22) or
            ((b[11].toLong() and 0xFE) shl 14) or
            ((b[12].toLong() and 0xFF) shl 7) or
            ((b[13].toLong() and 0xFE) shr 1)
    }

    private fun fakeAnnexB(size: Int, keyframe: Boolean): ByteArray {
        val d = ByteArray(size) { (it % 251).toByte() }
        d[0] = 0; d[1] = 0; d[2] = 0; d[3] = 1
        d[4] = if (keyframe) 0x65 else 0x41
        return d
    }

    @Test
    fun crcOfSectionWithItsCrcIsZero() {
        val mux = TsMuxer(hasAudio = true)
        val data = mux.videoAccessUnit(fakeAnnexB(100, true), 0, keyframe = true)
        val pat = packets(data).first { pid(it) == TsMuxer.PID_PAT }
        // Sección: pointer_field (1) + tabla. Longitud en bytes 6..7 del paquete (tras pointer en 4).
        val sectionStart = 5
        val sectionLen = ((pat[6].toInt() and 0x0F) shl 8) or (pat[7].toInt() and 0xFF)
        val total = 3 + sectionLen
        assertEquals("residuo CRC debe ser 0", 0, TsMuxer.crc32Mpeg(pat, sectionStart, total))
    }

    @Test
    fun keyframeStartsWithTablesAndAllPacketsAreSynced() {
        val mux = TsMuxer(hasAudio = true)
        val data = mux.videoAccessUnit(fakeAnnexB(5000, true), 0, keyframe = true)
        val all = packets(data)
        assertTrue(all.all { it[0] == 0x47.toByte() })
        assertEquals(TsMuxer.PID_PAT, pid(all[0]))
        assertTrue(pusi(all[0]))
        assertEquals(TsMuxer.PID_PMT, pid(all[1]))
    }

    @Test
    fun pesPayloadIsRecoveredAndPtsDecodes() {
        val mux = TsMuxer(hasAudio = true)
        val au = fakeAnnexB(3000, true)
        val ptsUs = 1_234_567L
        val data = mux.videoAccessUnit(au, ptsUs, keyframe = true)
        val pes = reassemble(packets(data), TsMuxer.PID_VIDEO)
        assertEquals(1, pes.size)
        val p = pes[0]
        assertEquals(0x00, p[0].toInt()); assertEquals(0x00, p[1].toInt()); assertEquals(0x01, p[2].toInt())
        assertEquals(0xE0, p[3].toInt() and 0xFF)
        val expectedPts = ptsUs * 9 / 100
        assertEquals(expectedPts, decodePts(p))
        // El payload (Annex B) debe estar íntegro al final del PES.
        val payload = p.copyOfRange(14, p.size)
        assertTrue(payload.contentEquals(au))
    }

    @Test
    fun pcrMatchesVideoPts() {
        val mux = TsMuxer(hasAudio = false)
        val data = mux.videoAccessUnit(fakeAnnexB(800, false), 2_000_000L, keyframe = false)
        val first = packets(data).first { pid(it) == TsMuxer.PID_VIDEO && pusi(it) }
        assertEquals(2_000_000L * 9 / 100, pcrOf(first))
    }

    @Test
    fun continuityCountersIncrementPerPid() {
        val mux = TsMuxer(hasAudio = true)
        var last = -1
        repeat(3) { i ->
            val d = mux.videoAccessUnit(fakeAnnexB(20_000, i == 0), i * 33_000L, keyframe = i == 0)
            for (p in packets(d)) {
                if (pid(p) != TsMuxer.PID_VIDEO) continue
                val c = cc(p)
                if (last >= 0) assertEquals((last + 1) and 0x0F, c)
                last = c
            }
        }
    }

    @Test
    fun audioFramesUseAdtsAndOwnPid() {
        val mux = TsMuxer(hasAudio = true)
        val frame = ByteArray(300) { (it and 0x7F).toByte() }
        val adts = TsMuxer.adtsHeader(frame.size, 44100, 2) + frame
        val data = mux.audioAccessUnit(adts, 500_000L)
        val all = packets(data)
        assertTrue(all.all { pid(it) == TsMuxer.PID_AUDIO })
        val pes = reassemble(all, TsMuxer.PID_AUDIO)[0]
        assertEquals(0xC0, pes[3].toInt() and 0xFF)
        val payload = pes.copyOfRange(14, pes.size)
        // Cabecera ADTS: sincronismo y longitud total
        assertEquals(0xFF, payload[0].toInt() and 0xFF)
        assertEquals(0xF0, payload[1].toInt() and 0xF0)
        val len = ((payload[3].toInt() and 0x03) shl 11) or ((payload[4].toInt() and 0xFF) shl 3) or ((payload[5].toInt() shr 5) and 0x07)
        assertEquals(frame.size + 7, len)
        assertEquals(4, (payload[2].toInt() shr 2) and 0x0F) // 44100 Hz
    }

    @Test
    fun audioIgnoredWhenMuxerHasNoAudio() {
        val mux = TsMuxer(hasAudio = false)
        assertEquals(0, mux.audioAccessUnit(ByteArray(10), 0).size)
    }

    @Test
    fun largeKeyframeIsSplitIntoManyPackets() {
        val mux = TsMuxer(hasAudio = false)
        val data = mux.videoAccessUnit(fakeAnnexB(200_000, true), 0, keyframe = true)
        assertTrue(packets(data).size > 1000)
    }
}
