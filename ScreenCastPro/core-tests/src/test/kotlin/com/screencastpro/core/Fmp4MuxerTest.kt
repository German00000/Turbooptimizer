package com.screencastpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fmp4MuxerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x11, 0x22, 0x33)
    private val pps = byteArrayOf(0x68, 0x3C, 0x44)
    private val idr = byteArrayOf(0x65, 0x01, 0x02, 0x03, 0x04)
    private val pFrame = byteArrayOf(0x41, 0x09, 0x0A)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(n) }
        return out.toByteArray()
    }

    /** Lista las cajas de primer nivel: (tipo, offset, tamaño). */
    private fun topBoxes(data: ByteArray): List<Triple<String, Int, Int>> {
        val res = mutableListOf<Triple<String, Int, Int>>()
        var off = 0
        while (off + 8 <= data.size) {
            val size = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or
                ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
            val type = String(data, off + 4, 4, Charsets.US_ASCII)
            res.add(Triple(type, off, size))
            off += size
        }
        return res
    }

    private fun contains(data: ByteArray, text: String): Boolean =
        String(data, Charsets.ISO_8859_1).contains(text, ignoreCase = false)

    @Test
    fun extractsSpsAndPpsFromCodecConfig() {
        val m = Fmp4Muxer(640, 360, 30)
        assertTrue(!m.hasParams)
        m.extractParams(annexB(sps, pps, idr))
        assertTrue(m.hasParams)
    }

    @Test
    fun initSegmentHasFtypAndMoovWithAvcC() {
        val m = Fmp4Muxer(640, 360, 30)
        m.extractParams(annexB(sps, pps))
        val init = m.initSegment()
        val boxes = topBoxes(init).map { it.first }
        assertEquals(listOf("ftyp", "moov"), boxes)
        assertTrue(contains(init, "avcC"))
        assertTrue(contains(init, "avc1"))
        assertTrue(contains(init, "mvex"))
        // Los bytes de SPS y PPS deben aparecer dentro del avcC
        assertTrue(contains(init, String(sps, Charsets.ISO_8859_1)))
        assertTrue(contains(init, String(pps, Charsets.ISO_8859_1)))
    }

    @Test
    fun fragmentIsMoofThenMdatWithCorrectDataOffset() {
        val m = Fmp4Muxer(640, 360, 30)
        m.extractParams(annexB(sps, pps))
        val frag = m.fragment(annexB(idr), 0, keyframe = true)
        val boxes = topBoxes(frag)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.first })
        assertEquals(frag.size, boxes.sumOf { it.third })

        val moof = boxes[0]
        val mdat = boxes[1]
        // trun.data_offset debe apuntar al primer byte de la muestra dentro de mdat
        val trunOff = (String(frag, Charsets.ISO_8859_1).indexOf("trun"))
        val dataOffsetPos = trunOff + 4 + 4 + 4 + 0 // tipo(4) + version/flags(4) + sample_count(4)
        val dataOffset = ((frag[dataOffsetPos].toInt() and 0xFF) shl 24) or ((frag[dataOffsetPos + 1].toInt() and 0xFF) shl 16) or
            ((frag[dataOffsetPos + 2].toInt() and 0xFF) shl 8) or (frag[dataOffsetPos + 3].toInt() and 0xFF)
        assertEquals(mdat.second + 8, moof.second + dataOffset)
    }

    @Test
    fun sampleIsLengthPrefixedAnnexBWithoutStartCodes() {
        val m = Fmp4Muxer(640, 360, 30)
        m.extractParams(annexB(sps, pps))
        val frag = m.fragment(annexB(idr, pFrame), 0, keyframe = true)
        val mdat = topBoxes(frag).first { it.first == "mdat" }
        val payloadStart = mdat.second + 8
        // Primera NAL: longitud 4 bytes + cabecera del IDR
        val len1 = ((frag[payloadStart].toInt() and 0xFF) shl 24) or ((frag[payloadStart + 1].toInt() and 0xFF) shl 16) or
            ((frag[payloadStart + 2].toInt() and 0xFF) shl 8) or (frag[payloadStart + 3].toInt() and 0xFF)
        assertEquals(idr.size, len1)
        assertEquals(0x65.toByte(), frag[payloadStart + 4])
        // Sin start codes dentro de la muestra
        val sample = frag.copyOfRange(payloadStart, mdat.second + mdat.third)
        for (i in 0 until sample.size - 3) {
            assertTrue(!(sample[i] == 0.toByte() && sample[i + 1] == 0.toByte() && sample[i + 2] == 1.toByte()))
        }
    }

    @Test
    fun sequenceNumbersIncrease() {
        val m = Fmp4Muxer(640, 360, 30)
        m.extractParams(annexB(sps, pps))
        val f1 = m.fragment(annexB(idr), 0, true)
        val f2 = m.fragment(annexB(pFrame), 33_333, false)
        fun seq(f: ByteArray): Int {
            val i = String(f, Charsets.ISO_8859_1).indexOf("mfhd") + 4 + 4
            return ((f[i].toInt() and 0xFF) shl 24) or ((f[i + 1].toInt() and 0xFF) shl 16) or
                ((f[i + 2].toInt() and 0xFF) shl 8) or (f[i + 3].toInt() and 0xFF)
        }
        assertEquals(1, seq(f1))
        assertEquals(2, seq(f2))
    }

    private fun adts(payload: ByteArray): ByteArray = TsMuxer.adtsHeader(payload.size, 44100, 2) + payload

    @Test
    fun initWithAudioHasTwoTracksAndAacConfig() {
        val m = Fmp4Muxer(640, 360, 30, withAudio = true)
        m.extractParams(annexB(sps, pps))
        val init = m.initSegment()
        assertTrue(contains(init, "soun"))
        assertTrue(contains(init, "mp4a"))
        assertTrue(contains(init, "esds"))
        // AudioSpecificConfig AAC-LC, 44,1 kHz, 2 canales
        assertTrue(contains(init, String(byteArrayOf(0x12, 0x10), Charsets.ISO_8859_1)))
        // Dos trex: uno por pista
        val count = Regex("trex").findAll(String(init, Charsets.ISO_8859_1)).count()
        assertEquals(2, count)
    }

    @Test
    fun initWithoutAudioHasOnlyVideo() {
        val m = Fmp4Muxer(640, 360, 30)
        m.extractParams(annexB(sps, pps))
        assertTrue(!contains(m.initSegment(), "soun"))
    }

    @Test
    fun audioFragmentCarriesRawAacWithoutAdtsHeader() {
        val m = Fmp4Muxer(640, 360, 30, withAudio = true)
        val raw = byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x0C)
        val frag = m.audioFragment(adts(raw), 0)
        val boxes = topBoxes(frag)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.first })
        val mdat = boxes[1]
        val payload = frag.copyOfRange(mdat.second + 8, mdat.second + mdat.third)
        assertTrue(payload.contentEquals(raw))
        // Pista de audio: track_ID 2 en tfhd
        assertTrue(contains(frag, String(byteArrayOf(0, 0, 0, 2), Charsets.ISO_8859_1)))
    }

    @Test
    fun audioFragmentIsEmptyWithoutAudioTrack() {
        val m = Fmp4Muxer(640, 360, 30)
        assertEquals(0, m.audioFragment(adts(byteArrayOf(1, 2, 3)), 0).size)
    }
}
