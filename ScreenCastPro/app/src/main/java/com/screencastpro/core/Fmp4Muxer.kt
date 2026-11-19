package com.screencastpro.core

import java.io.ByteArrayOutputStream

/**
 * Muxer MP4 fragmentado (ISO/IEC 14496-12) para vídeo H.264 en directo.
 *
 * Es el formato que los televisores DLNA suelen reproducir en streaming (`video/mp4`), y que el
 * televisor anuncia en GetProtocolInfo. Estructura:
 *   - initSegment(): ftyp + moov (avcC con SPS/PPS, sin muestras). Se envía una sola vez al inicio.
 *   - fragment(): moof + mdat con una muestra por fragmento. Un fragmento por fotograma permite
 *     empezar a reproducir en cualquier keyframe y mantiene la latencia baja.
 *
 * Sin audio: el vídeo de MP4 para DLNA se transmite solo con imagen.
 * Sin dependencias de Android: se prueba en JVM.
 */
class Fmp4Muxer(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
) {
    companion object {
        const val TIMESCALE = 90_000
        private const val TRACK_ID = 1
        private val SYNC_FLAGS = 0x02000000
        private val NON_SYNC_FLAGS = 0x01010000

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
    }

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var sequence = 1
    private var lastDts = -1L

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

    /** Segmento de inicio. Requiere [hasParams]. */
    fun initSegment(): ByteArray {
        val s = requireNotNull(sps) { "Falta SPS" }
        val p = requireNotNull(pps) { "Falta PPS" }
        val ftyp = box(
            "ftyp",
            "isom".toByteArray(Charsets.US_ASCII), be32(0x200),
            "isom".toByteArray(Charsets.US_ASCII), "iso5".toByteArray(Charsets.US_ASCII),
            "iso6".toByteArray(Charsets.US_ASCII), "avc1".toByteArray(Charsets.US_ASCII),
        )
        val moov = box("moov", mvhd(), trak(s, p), mvex())
        return cat(ftyp, moov)
    }

    /**
     * Un fragmento (moof + mdat) con un fotograma. [annexB] es el fotograma completo (puede incluir
     * SPS/PPS delante de un keyframe; se convierte a NAL con longitud de 4 bytes).
     */
    fun fragment(annexB: ByteArray, ptsUs: Long, keyframe: Boolean): ByteArray {
        val pts90k = maxOf(0L, ptsUs) * 9L / 100L
        val duration = if (lastDts < 0) (TIMESCALE / maxOf(1, fps)).toLong()
            else (pts90k - lastDts).coerceIn(1L, TIMESCALE.toLong())
        lastDts = pts90k

        val payload = ByteArrayOutputStream(annexB.size + 64)
        for (nal in nalUnits(annexB)) {
            if (nal.isEmpty()) continue
            payload.write(be32(nal.size), 0, 4)
            payload.write(nal, 0, nal.size)
        }
        val sample = payload.toByteArray()

        val mfhd = fullBox("mfhd", 0, 0, be32(sequence++))
        val tfhd = fullBox("tfhd", 0, 0x020000, be32(TRACK_ID))          // default-base-is-moof
        val tfdt = fullBox("tfdt", 1, 0, be64(pts90k))
        // El data_offset depende del tamaño del propio moof. Como el trun tiene siempre el mismo tamaño,
        // se calcula primero con un valor provisional y después se fija con el valor real.
        val moofSize = box("moof", mfhd, box("traf", tfhd, tfdt, trunBox(0, duration, sample.size, keyframe))).size
        // data_offset: desde el inicio del moof hasta el primer byte de la muestra (tras la cabecera de mdat).
        val moof = box("moof", mfhd, box("traf", tfhd, tfdt, trunBox(moofSize + 8, duration, sample.size, keyframe)))
        val mdat = box("mdat", sample)
        return cat(moof, mdat)
    }

    private fun trunBox(dataOffset: Int, duration: Long, size: Int, keyframe: Boolean): ByteArray =
        fullBox(
            "trun", 0, 0x000701,                 // data-offset | duration | size | flags
            be32(1), be32(dataOffset), be32(duration.toInt()), be32(size),
            be32(if (keyframe) SYNC_FLAGS else NON_SYNC_FLAGS),
        )


    // ---------- cajas del segmento de inicio ----------

    private fun mvhd(): ByteArray = fullBox(
        "mvhd", 0, 0,
        be32(0), be32(0), be32(1000), be32(0),       // creación, modificación, timescale, duración
        be32(0x00010000), be16(0x0100), ByteArray(10), // rate 1.0, volume 1.0, reservado
        matrix(),
        ByteArray(24),                                // pre_defined
        be32(TRACK_ID + 1),
    )

    private fun trak(s: ByteArray, p: ByteArray): ByteArray = box("trak", tkhd(), mdia(s, p))

    private fun tkhd(): ByteArray = fullBox(
        "tkhd", 0, 3,                                 // enabled | in_movie
        be32(0), be32(0), be32(TRACK_ID), be32(0), be32(0),
        ByteArray(8), be16(0), be16(0), be16(0), be16(0),
        matrix(),
        be32(width shl 16), be32(height shl 16),
    )

    private fun mdia(s: ByteArray, p: ByteArray): ByteArray = box(
        "mdia",
        fullBox("mdhd", 0, 0, be32(0), be32(0), be32(TIMESCALE), be32(0), be16(0x55C4), be16(0)),
        fullBox("hdlr", 0, 0, be32(0), "vide".toByteArray(Charsets.US_ASCII), ByteArray(12), "ScreenCastPro\u0000".toByteArray(Charsets.US_ASCII)),
        box("minf", fullBox("vmhd", 0, 1, be16(0), ByteArray(6)), dinf(), stbl(s, p)),
    )

    private fun dinf(): ByteArray = box(
        "dinf",
        fullBox("dref", 0, 0, be32(1), fullBox("url ", 0, 1)),
    )

    private fun stbl(s: ByteArray, p: ByteArray): ByteArray = box(
        "stbl",
        fullBox("stsd", 0, 0, be32(1), avc1(s, p)),
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

    private fun mvex(): ByteArray = box(
        "mvex",
        fullBox("trex", 0, 0, be32(TRACK_ID), be32(1), be32(0), be32(0), be32(0)),
    )

    private fun matrix(): ByteArray = cat(
        be32(0x00010000), be32(0), be32(0),
        be32(0), be32(0x00010000), be32(0),
        be32(0), be32(0), be32(0x40000000),
    )
}
