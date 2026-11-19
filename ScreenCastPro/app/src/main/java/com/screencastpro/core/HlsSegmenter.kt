package com.screencastpro.core

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Agrupa paquetes TS en segmentos HLS (~[targetUs] cada uno) que empiezan siempre en un keyframe.
 * Mantiene solo los últimos [keep] segmentos en memoria para acotar la RAM.
 * Un segmento de 1 s equivale a ~1 MB con 8 Mbps: con 6 segmentos son ~6 MB en el peor caso.
 */
class HlsSegmenter(
    private val targetUs: Long = 1_000_000L,
    private val keep: Int = 6,
) {
    class Segment(val seq: Long, val durationUs: Long, val data: ByteArray)

    private val segments = ArrayDeque<Segment>()
    private var current = ByteArrayOutputStream(256 * 1024)
    private var currentStartUs = -1L
    private var nextSeq = 0L

    /** Añade un bloque TS. Ignora datos hasta el primer keyframe. */
    @Synchronized
    fun append(chunk: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (currentStartUs < 0) {
            if (!keyframe) return
            currentStartUs = ptsUs
        }
        if (keyframe && current.size() > 0 && ptsUs - currentStartUs >= targetUs) {
            closeCurrent(ptsUs)
        }
        current.write(chunk, 0, chunk.size)
    }

    private fun closeCurrent(endUs: Long) {
        val dur = maxOf(1L, endUs - currentStartUs)
        segments.addLast(Segment(nextSeq++, dur, current.toByteArray()))
        while (segments.size > keep) segments.removeFirst()
        current.reset()
        currentStartUs = endUs
    }

    @Synchronized
    fun playlist(): String {
        val sb = StringBuilder()
        val first = segments.firstOrNull()?.seq ?: nextSeq
        val maxDurSec = segments.maxOfOrNull { it.durationUs }?.let { (it + 999_999) / 1_000_000 } ?: 1L
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(maxOf(1L, maxDurSec)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
        for (s in segments) {
            sb.append(String.format(Locale.US, "#EXTINF:%.3f,\n", s.durationUs / 1_000_000.0))
            sb.append("seg/").append(s.seq).append(".ts\n")
        }
        return sb.toString()
    }

    @Synchronized
    fun segment(seq: Long): ByteArray? = segments.firstOrNull { it.seq == seq }?.data

    @Synchronized
    fun segmentCount(): Int = segments.size
}
