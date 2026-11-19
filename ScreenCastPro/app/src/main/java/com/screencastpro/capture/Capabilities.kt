package com.screencastpro.capture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.screencastpro.core.EncodeParams
import com.screencastpro.core.Level
import com.screencastpro.core.ProfileMath

/** Consulta qué codificadores H.264 tiene el dispositivo y si admiten un tamaño y FPS concretos. */
object Capabilities {

    private fun avcEncoders(): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }

    /** Codificador por hardware si existe; si no, el primero disponible (software). */
    fun pickEncoder(): MediaCodecInfo? {
        val list = avcEncoders()
        return list.firstOrNull { isHardware(it) } ?: list.firstOrNull()
    }

    fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) !info.isSoftwareOnly
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    fun supports(info: MediaCodecInfo, width: Int, height: Int, fps: Int): Boolean {
        val caps = try {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val video = caps.videoCapabilities ?: return false
        return video.areSizeAndRateSupported(width, height, fps.toDouble())
    }

    /**
     * Primer escalón de la escalera de calidad que el codificador admite con el tamaño que
     * corresponde a la pantalla. Si ninguno encaja, devuelve el último (el más bajo).
     */
    fun negotiateLevel(
        info: MediaCodecInfo?,
        ladder: List<Level>,
        srcW: Int,
        srcH: Int,
    ): Int {
        if (info == null) return ladder.lastIndex
        for ((i, lvl) in ladder.withIndex()) {
            val (w, h) = ProfileMath.fitSize(srcW, srcH, lvl.shortSide)
            if (supports(info, w, h, lvl.fps)) return i
        }
        return ladder.lastIndex
    }

    fun describe(p: EncodeParams): String = "${p.width}×${p.height}@${p.fps}"
}
