package com.screencastpro.core

import java.io.ByteArrayOutputStream

/**
 * Muxer MP4 fragmentado (ISO/IEC 14496-12) para H.264 en directo, con audio AAC opcional.
 *
 * Es el formato que los televisores DLNA suelen reproducir en streaming (`video/mp4`).
 * Estructura:
 *   - initSegment(): ftyp + moov con una pista de vídeo (avcC con SPS/PPS) y, si hay audio,
 *     una pista de audio (mp4a + esds con AudioSpecificConfig). Sin muestras. Se envía una vez.
 *   - fragment(): moof + mdat con una muestra de vídeo (un fragmento por fotograma).
 *   - audioFragment(): moof + mdat con una trama AAC (la cabecera ADTS se quita).
 *
 * Sin dependencias de Android: se prueba en JVM.
 */
class Fmp4Muxer(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val withAudio: Boolean = false,
) {
    companion object {
        const val TIMESCALE = 90_000
        const val VIDEO_TRACK = 1
        const val AUDIO_TRACK = 2
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_CHANNELS = 2
        private const val SYNC_FLAGS = 0x02000000
        private const val NON_SYNC_FLAGS = 0x01010000
        private const val ADTS_HEADER = 7

        private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun be64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

        private fun cat(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(parts.sumOf { it.size })
            for (p in parts) out.write(p, 0, p.size)
            return out.toByteArray()
        }

        fun box(type: String, vararg parts: ByteArray): ByteArray {
            val body = cat(*parts)
            return cat(be32(8 + body.size), type.toByteArray(Charsets.US_ASCII), body)
        }

        fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
            box(
                type,
                byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()),
                *parts,
            )

        /** Recorre las unidades NAL de un flujo Annex B y devuelve cada una sin start code. */
        fun nalUnits(data: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            var start = -1
            var i = 0
            while (i + 2 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    if (start >= 0) result.add(trimTrailingZeros(data, start, i))
                    i += 3
                    start = i
                } else {
                    i++
                }
            }
            if (start in 0 until data.size) result.add(data.copyOfRange(start, data.size))
            return result
        }

        /** Quita los ceros previos a un start code de 4 bytes (00 00 00 01). */
        private fun trimTrailingZeros(data: ByteArray, from: Int, startCodeAt: Int): ByteArray {
            var end = startCodeAt
            while (end > from && data[end - 1] == 0.toByte()) end--
            return data.copyOfRange(from, end)
        }

        /** Descriptor de configuración AAC-LC: 44,1 kHz estéreo → 0x12 0x10. */
        private fun audioSpecificConfig(): ByteArray {
            val sfIndex = 4 // 44100 Hz
            return byteArrayOf(
                ((2 shl 3) or (sfIndex shr 1)).toByte(),              // AAC-LC (2) + 4 bits de frecuencia
                (((sfIndex and 1) shl 7) or (AUDIO_CHANNELS shl 3)).toByte(),
            )
        }
    }

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var sequence = 1
    private var lastVideoDts = -1L
    private var lastAudioDts = -1L

    val hasParams: Boolean get() = sps != null && pps != null

    /** Busca SPS (tipo 7) y PPS (tipo 8) en un bloque Annex B. Idempotente. */
    fun extractParams(annexB: ByteArray) {
        for (nal in nalUnits(annexB)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
            }
        }
    }

    /** Perfil y nivel H.264 del SPS, por ejemplo "perfil 66, nivel 5.1". Null si aún no hay SPS. */
    fun spsSummary(): String? {
        val s = sps ?: return null
        if (s.size < 4) return null
        val level = s[3].toInt() and 0xFF
        return "perfil ${s[1].toInt() and 0xFF}, nivel ${level / 10}.${level % 10}"
    }

    /** Segmento de inicio. Requiere [hasParams]. */
    fun initSegment(): ByteArray {
        val s = requireNotNull(sps) { "Falta SPS" }
        val p = requireNotNull(pps) { "Falta PPS" }
        val ftyp = box(
            "ftyp",
            "isom".toByteArray(Charsets.US_ASCII), be32(0x200),
            "isom".toByteArray(Charsets.US_ASCII), "iso5".toByteArray(Charsets.US_ASCII),
            "iso6".toByteArray(Charsets.US_ASCII), "avc1".toByteArray(Charsets.US_ASCII),
            "mp41".toByteArray(Charsets.US_ASCII),
        )
        val tracks = if (withAudio) arrayOf(videoTrak(s, p), audioTrak()) else arrayOf(videoTrak(s, p))
        val trackCount = tracks.size
        val mvex = box(
            "mvex",
            *Array(trackCount) { i -> fullBox("trex", 0, 0, be32(i + 1), be32(1), be32(0), be32(0), be32(0)) },
        )
        val moov = box("moov", mvhd(trackCount + 1), *tracks, mvex)
        return cat(ftyp, moov)
    }

    /**
     * Un fragmento de vídeo (moof + mdat) con un fotograma. [annexB] es el fotograma completo (puede incluir
     * SPS/PPS delante de un keyframe; se convierte a NAL con longitud de 4 bytes).
     */
    fun fragment(annexB: ByteArray, ptsUs: Long, keyframe: Boolean): ByteArray {
        val pts90k = maxOf(0L, ptsUs) * 9L / 100L
        val duration = if (lastVideoDts < 0) (TIMESCALE / maxOf(1, fps)).toLong()
            else (pts90k - lastVideoDts).coerceIn(1L, TIMESCALE.toLong())
        lastVideoDts = pts90k
        return buildFragment(VIDEO_TRACK, pts90k, duration, lengthPrefixed(annexB), keyframe)
    }

    /** Un fragmento de audio con una trama ADTS (se quita la cabecera de 7 bytes). Vacío si no hay audio. */
    fun audioFragment(adts: ByteArray, ptsUs: Long): ByteArray {
        if (!withAudio || adts.size <= ADTS_HEADER) return ByteArray(0)
        val pts90k = maxOf(0L, ptsUs) * 9L / 100L
        val frameDuration = AUDIO_FRAME_TICKS
        val duration = if (lastAudioDts < 0) frameDuration else (pts90k - lastAudioDts).coerceIn(1L, TIMESCALE.toLong())
        lastAudioDts = pts90k
        val raw = adts.copyOfRange(ADTS_HEADER, adts.size)
        return buildFragment(AUDIO_TRACK, pts90k, duration, raw, keyframe = true)
    }

    private fun lengthPrefixed(annexB: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream(annexB.size + 64)
        for (nal in nalUnits(annexB)) {
            if (nal.isEmpty()) continue
            payload.write(be32(nal.size), 0, 4)
            payload.write(nal, 0, nal.size)
        }
        return payload.toByteArray()
    }

    /**
     * moof + mdat para una muestra. El data_offset depende del tamaño del propio moof: se calcula primero
     * con un valor provisional (el trun tiene siempre el mismo tamaño) y después se fija.
     */
    private fun buildFragment(track: Int, dts90k: Long, duration: Long, sample: ByteArray, keyframe: Boolean): ByteArray {
        val mfhd = fullBox("mfhd", 0, 0, be32(sequence++))
        val tfhd = fullBox("tfhd", 0, 0x020000, be32(track))           // default-base-is-moof
        val tfdt = fullBox("tfdt", 1, 0, be64(dts90k))
        val moofSize = box("moof", mfhd, box("traf", tfhd, tfdt, trunBox(0, duration, sample.size, keyframe))).size
        val moof = box("moof", mfhd, box("traf", tfhd, tfdt, trunBox(moofSize + 8, duration, sample.size, keyframe)))
        return cat(moof, box("mdat", sample))
    }

    private fun trunBox(dataOffset: Int, duration: Long, size: Int, keyframe: Boolean): ByteArray =
        fullBox(
            "trun", 0, 0x000701,                 // data-offset | duration | size | flags
            be32(1), be32(dataOffset), be32(duration.toInt()), be32(size),
            be32(if (keyframe) SYNC_FLAGS else NON_SYNC_FLAGS),
        )

    // ---------- cajas del segmento de inicio ----------

    private fun mvhd(nextTrackId: Int): ByteArray = fullBox(
        "mvhd", 0, 0,
        be32(0), be32(0), be32(1000), be32(0),       // creación, modificación, timescale, duración
        be32(0x00010000), be16(0x0100), ByteArray(10), // rate 1.0, volume 1.0, reservado
        matrix(),
        ByteArray(24),                                // pre_defined
        be32(nextTrackId),
    )

    private fun videoTrak(s: ByteArray, p: ByteArray): ByteArray = box(
        "trak",
        tkhd(VIDEO_TRACK, 0, width, height),
        box(
            "mdia",
            mdhd(),
            fullBox("hdlr", 0, 0, be32(0), "vide".toByteArray(Charsets.US_ASCII), ByteArray(12),
                "ScreenCastPro video\u0000".toByteArray(Charsets.US_ASCII)),
            box("minf", fullBox("vmhd", 0, 1, be16(0), ByteArray(6)), dinf(), stbl(stsd(avc1(s, p)))),
        ),
    )

    private fun audioTrak(): ByteArray = box(
        "trak",
        tkhd(AUDIO_TRACK, 0x0100, 0, 0),
        box(
            "mdia",
            mdhd(),
            fullBox("hdlr", 0, 0, be32(0), "soun".toByteArray(Charsets.US_ASCII), ByteArray(12),
                "ScreenCastPro audio\u0000".toByteArray(Charsets.US_ASCII)),
            box("minf", fullBox("smhd", 0, 0, be16(0), be16(0)), dinf(), stbl(stsd(mp4a()))),
        ),
    )

    private fun mdhd(): ByteArray =
        fullBox("mdhd", 0, 0, be32(0), be32(0), be32(TIMESCALE), be32(0), be16(0x55C4), be16(0))

    private fun tkhd(trackId: Int, volume: Int, w: Int, h: Int): ByteArray = fullBox(
        "tkhd", 0, 3,                                 // enabled | in_movie
        be32(0), be32(0), be32(trackId), be32(0), be32(0),
        ByteArray(8), be16(0), be16(0), be16(volume), be16(0),
        matrix(),
        be32(w shl 16), be32(h shl 16),
    )

    private fun dinf(): ByteArray = box("dinf", fullBox("dref", 0, 0, be32(1), fullBox("url ", 0, 1)))

    private fun stsd(entry: ByteArray): ByteArray = fullBox("stsd", 0, 0, be32(1), entry)

    private fun stbl(stsd: ByteArray): ByteArray = box(
        "stbl",
        stsd,
        fullBox("stts", 0, 0, be32(0)),
        fullBox("stsc", 0, 0, be32(0)),
        fullBox("stsz", 0, 0, be32(0), be32(0)),
        fullBox("stco", 0, 0, be32(0)),
    )

    private fun avc1(s: ByteArray, p: ByteArray): ByteArray {
        val avcC = box(
            "avcC",
            byteArrayOf(1, s[1], s[2], s[3], 0xFF.toByte(), 0xE1.toByte()),
            be16(s.size), s,
            byteArrayOf(1),
            be16(p.size), p,
        )
        return box(
            "avc1",
            ByteArray(6), be16(1),                    // reservado + data_reference_index
            ByteArray(16),                            // pre_defined / reservado
            be16(width), be16(height),
            be32(0x00480000), be32(0x00480000),       // 72 dpi
            be32(0),
            be16(1),                                  // frame_count
            ByteArray(32),                            // compressorname
            be16(0x0018), be16(0xFFFF),               // profundidad, pre_defined
            avcC,
        )
    }

    private fun mp4a(): ByteArray {
        val asc = audioSpecificConfig()
        // ES_Descriptor (0x03) → DecoderConfigDescriptor (0x04) con DecoderSpecificInfo (0x05) → SLConfig (0x06)
        val dsi = byteArrayOf(0x05, asc.size.toByte()) + asc
        val dcdBody = byteArrayOf(0x40, (0x05 shl 2 or 1).toByte(), 0, 0, 0) +
            be32(128_000) + be32(128_000) + dsi
        val dcd = byteArrayOf(0x04, dcdBody.size.toByte()) + dcdBody
        val sl = byteArrayOf(0x06, 0x01, 0x02)
        val esBody = be16(2) + byteArrayOf(0) + dcd + sl
        val es = byteArrayOf(0x03, esBody.size.toByte()) + esBody
        val esds = fullBox("esds", 0, 0, es)
        return box(
            "mp4a",
            ByteArray(6), be16(1),                                // reservado + data_reference_index
            ByteArray(8),                                         // version, revision, vendor
            be16(AUDIO_CHANNELS), be16(16),                       // canales, 16 bits
            be16(0), be16(0),                                     // pre_defined, reservado
            be32(AUDIO_SAMPLE_RATE shl 16),                       // frecuencia 16.16
            esds,
        )
    }

    private fun matrix(): ByteArray = cat(
        be32(0x00010000), be32(0), be32(0),
        be32(0), be32(0x00010000), be32(0),
        be32(0), be32(0), be32(0x40000000),
    )

    /** Duración de una trama AAC-LC (1024 muestras) en unidades de TIMESCALE. */
    private val AUDIO_FRAME_TICKS: Long = 1024L * TIMESCALE / AUDIO_SAMPLE_RATE
}
