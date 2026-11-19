package com.screencastpro.core

import java.io.ByteArrayOutputStream

/**
 * Muxer MPEG-TS (ISO/IEC 13818-1) mínimo para:
 *  - una pista de vídeo H.264 en formato Annex B (start codes 00 00 00 01),
 *  - opcionalmente una pista de audio AAC con cabecera ADTS.
 *
 * Cada unidad de acceso se devuelve como un bloque de paquetes de 188 bytes. Antes de cada
 * fotograma clave se escriben PAT y PMT, así que cualquier cliente puede empezar a decodificar
 * en cualquier keyframe y cada segmento HLS es independiente.
 *
 * Sin dependencias de Android: se puede probar en JVM.
 */
class TsMuxer(private val hasAudio: Boolean) {

    companion object {
        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101
        const val TS_PACKET = 188

        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private const val STREAM_ID_VIDEO = 0xE0
        private const val STREAM_ID_AUDIO = 0xC0
        private const val PTS_WRAP = (1L shl 33)
        /** PAT/PMT se repiten al menos cada 100 ms: muchos demultiplexores de TV se sincronizan con ello. */
        private const val PSI_INTERVAL_90K = 9_000L

        private val CRC_TABLE = IntArray(256).also { table ->
            for (i in 0 until 256) {
                var c = i shl 24
                repeat(8) {
                    c = if ((c and (1 shl 31)) != 0) (c shl 1) xor 0x04C11DB7 else (c shl 1)
                }
                table[i] = c
            }
        }

        /** CRC-32/MPEG-2 usado en las secciones PSI. Sobre una sección completa con su CRC el resultado es 0. */
        fun crc32Mpeg(data: ByteArray, off: Int = 0, len: Int = data.size - off): Int {
            var crc = -1
            for (i in off until off + len) {
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
            }
            return crc
        }

        /** Cabecera ADTS de 7 bytes (sin CRC) para una trama AAC-LC de [frameLen] bytes de datos. */
        fun adtsHeader(frameLen: Int, sampleRate: Int, channels: Int): ByteArray {
            val sfIndex = when (sampleRate) {
                96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
                24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
                else -> 4
            }
            val total = frameLen + 7
            val h = ByteArray(7)
            h[0] = 0xFF.toByte()
            h[1] = 0xF1.toByte()                                  // MPEG-4, sin CRC
            h[2] = ((1 shl 6) or (sfIndex shl 2) or ((channels shr 2) and 1)).toByte() // perfil LC = 1
            h[3] = (((channels and 3) shl 6) or ((total shr 11) and 3)).toByte()
            h[4] = ((total shr 3) and 0xFF).toByte()
            h[5] = (((total and 7) shl 5) or 0x1F).toByte()       // fullness alta = 0x7FF
            h[6] = 0xFC.toByte()
            return h
        }
    }

    private val out = ByteArrayOutputStream(64 * 1024)
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    /** Último PTS (90 kHz) en el que se emitieron PAT/PMT. */
    private var lastPsi90k = -1L
    private var ccAudio = 0

    /** Devuelve los paquetes TS de una unidad de acceso de vídeo (Annex B). */
    @Synchronized
    fun videoAccessUnit(annexB: ByteArray, ptsUs: Long, keyframe: Boolean): ByteArray {
        out.reset()
        val pts90k = toPts90k(ptsUs)
        if (keyframe || lastPsi90k < 0 || pts90k - lastPsi90k >= PSI_INTERVAL_90K) {
            writeTables()
            lastPsi90k = pts90k
        }
        val pes = buildPes(STREAM_ID_VIDEO, pts90k, annexB, dataAlignment = true)
        emitPes(PID_VIDEO, pes, pcrBase = pts90k)
        return out.toByteArray()
    }

    /** Devuelve los paquetes TS de una trama de audio ADTS. Ignorado si el muxer se creó sin audio. */
    @Synchronized
    fun audioAccessUnit(adts: ByteArray, ptsUs: Long): ByteArray {
        out.reset()
        if (!hasAudio) return ByteArray(0)
        val pes = buildPes(STREAM_ID_AUDIO, toPts90k(ptsUs), adts, dataAlignment = false)
        emitPes(PID_AUDIO, pes, pcrBase = null)
        return out.toByteArray()
    }

    /**
     * Paquetes nulos (PID 0x1FFF). Los decodificadores los descartan; sirven para que el flujo no quede
     * sin datos cuando la pantalla está quieta y no hay vídeo que enviar.
     */
    @Synchronized
    fun nullPackets(count: Int): ByteArray {
        val out = ByteArray(count * TS_PACKET) { 0xFF.toByte() }
        for (i in 0 until count) {
            val o = i * TS_PACKET
            out[o] = 0x47
            out[o + 1] = 0x1F          // PID 0x1FFF: 5 bits altos
            out[o + 2] = 0xFF.toByte() // PID: 8 bits bajos
            out[o + 3] = 0x10          // solo payload
        }
        return out
    }

    private fun toPts90k(ptsUs: Long): Long {
        val v = (maxOf(0L, ptsUs) * 9L) / 100L  // us → 90 kHz
        return v % PTS_WRAP
    }

    // ---------- PSI ----------

    private fun writeTables() {
        emitSinglePacket(PID_PAT, pat(), ccPat).also { ccPat = (ccPat + 1) and 0x0F }
        emitSinglePacket(PID_PMT, pmt(), ccPmt).also { ccPmt = (ccPmt + 1) and 0x0F }
    }

    private fun pat(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01,             // transport_stream_id
            0xC1.toByte(), 0x00, 0x00, // version 0, current, sección 0 de 0
            0x00, 0x01,             // program_number
            (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte(),
        )
        return section(0x00, body)
    }

    private fun pmt(): ByteArray {
        val streams = if (hasAudio) listOf(STREAM_TYPE_H264 to PID_VIDEO, STREAM_TYPE_AAC_ADTS to PID_AUDIO)
        else listOf(STREAM_TYPE_H264 to PID_VIDEO)
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00))
        body.write(byteArrayOf((0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte())) // PCR = vídeo
        body.write(byteArrayOf(0xF0.toByte(), 0x00))                                          // program_info_length = 0
        for ((type, pid) in streams) {
            body.write(type)
            body.write(byteArrayOf((0xE0 or (pid shr 8)).toByte(), (pid and 0xFF).toByte()))
            body.write(byteArrayOf(0xF0.toByte(), 0x00))                                      // ES_info_length = 0
        }
        return section(0x02, body.toByteArray())
    }

    /** Sección PSI completa: table_id + longitud + cuerpo + CRC32. */
    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val sectionLength = body.size + 4 // incluye CRC
        val raw = ByteArray(3 + body.size + 4)
        raw[0] = tableId.toByte()
        raw[1] = (0xB0 or ((sectionLength shr 8) and 0x0F)).toByte()
        raw[2] = (sectionLength and 0xFF).toByte()
        System.arraycopy(body, 0, raw, 3, body.size)
        val crc = crc32Mpeg(raw, 0, raw.size - 4)
        raw[raw.size - 4] = (crc ushr 24).toByte()
        raw[raw.size - 3] = (crc ushr 16).toByte()
        raw[raw.size - 2] = (crc ushr 8).toByte()
        raw[raw.size - 1] = crc.toByte()
        return raw
    }

    private fun emitSinglePacket(pid: Int, section: ByteArray, cc: Int) {
        val pkt = ByteArray(TS_PACKET) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte() // payload_unit_start = 1
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or cc).toByte()                      // solo payload
        pkt[4] = 0x00                                        // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.size)
        out.write(pkt, 0, pkt.size)
    }

    // ---------- PES ----------

    private fun buildPes(streamId: Int, pts90k: Long, payload: ByteArray, dataAlignment: Boolean): ByteArray {
        val headerLen = 14 // start code (3) + stream_id (1) + length (2) + flags (2) + header_len (1) + PTS (5)
        val pes = ByteArray(headerLen + payload.size)
        pes[0] = 0x00; pes[1] = 0x00; pes[2] = 0x01
        pes[3] = streamId.toByte()
        // En vídeo la longitud 0 significa "sin límite" (permitido por la norma). En audio es la longitud exacta.
        val pesLen = if (streamId == STREAM_ID_VIDEO) 0 else 3 + 5 + payload.size
        val len = if (pesLen > 0xFFFF) 0 else pesLen
        pes[4] = (len shr 8).toByte()
        pes[5] = (len and 0xFF).toByte()
        pes[6] = (if (dataAlignment) 0x84 else 0x80).toByte()
        pes[7] = 0x80.toByte()                       // solo PTS
        pes[8] = 0x05                                // longitud de cabecera opcional
        pes[9] = (0x21 or ((pts90k shr 29).toInt() and 0x0E)).toByte()
        pes[10] = ((pts90k shr 22) and 0xFF).toByte()
        pes[11] = (((pts90k shr 14) and 0xFE) or 0x01).toByte()
        pes[12] = ((pts90k shr 7) and 0xFF).toByte()
        pes[13] = (((pts90k shl 1) and 0xFE) or 0x01).toByte()
        System.arraycopy(payload, 0, pes, headerLen, payload.size)
        return pes
    }

    /** Trocea un PES en paquetes TS. El PCR se coloca en el primer paquete del PES de vídeo. */
    private fun emitPes(pid: Int, pes: ByteArray, pcrBase: Long?) {
        var off = 0
        var first = true
        while (off < pes.size) {
            val remaining = pes.size - off
            val pcr = if (first) pcrBase else null
            val pkt = ByteArray(TS_PACKET) { 0xFF.toByte() }
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F)).toByte()
            pkt[2] = (pid and 0xFF).toByte()
            val cc = nextCc(pid)
            val payloadLen: Int
            if (pcr == null && remaining >= 184) {
                payloadLen = 184
                pkt[3] = (0x10 or cc).toByte()                    // solo payload
            } else {
                // Campo de adaptación: longitud (1) + contenido; total del paquete = 4 + 1 + afLen + payload = 188.
                val minAf = if (pcr != null) 7 else 0             // flags (1) + PCR (6)
                payloadLen = minOf(remaining, 183 - minAf)
                val afLen = 183 - payloadLen
                pkt[3] = (0x30 or cc).toByte()                    // adaptación + payload
                pkt[4] = afLen.toByte()
                if (afLen > 0) {
                    pkt[5] = (if (pcr != null) 0x10 else 0x00).toByte()
                    if (pcr != null) writePcr(pkt, 6, pcr)
                }
            }
            System.arraycopy(pes, off, pkt, TS_PACKET - payloadLen, payloadLen)
            out.write(pkt, 0, pkt.size)
            off += payloadLen
            first = false
        }
    }

    private fun writePcr(pkt: ByteArray, pos: Int, base: Long) {
        pkt[pos] = (base shr 25).toByte()
        pkt[pos + 1] = (base shr 17).toByte()
        pkt[pos + 2] = (base shr 9).toByte()
        pkt[pos + 3] = (base shr 1).toByte()
        pkt[pos + 4] = (((base and 1L) shl 7) or 0x7EL).toByte()  // bit bajo + reservado + bit alto de extensión (0)
        pkt[pos + 5] = 0x00                                        // extensión = 0
    }

    private fun nextCc(pid: Int): Int = when (pid) {
        PID_VIDEO -> ccVideo.also { ccVideo = (ccVideo + 1) and 0x0F }
        PID_AUDIO -> ccAudio.also { ccAudio = (ccAudio + 1) and 0x0F }
        else -> 0
    }
}
