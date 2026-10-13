/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.Serializable

/**
 * One stream of a DASH presentation: a single MP4 file whose segments are found through its
 * segment index.
 *
 * @param bandwidth bits per second, 0 if unknown
 * @param initStart first byte of the initialization data (ftyp + moov)
 * @param initEnd last byte (included) of the initialization data
 * @param indexStart first byte of the segment index (sidx)
 * @param indexEnd last byte (included) of the segment index
 */
data class DashTrack(
    val url: String,
    val mimeType: String,
    val codecs: String,
    val bandwidth: Int,
    val initStart: Long,
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val width: Int = 0,
    val height: Int = 0
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A video-only and an audio stream, which the cast proxy muxes into one MPEG-TS stream for the
 * TV, since there is no single file with both.
 */
data class CastDash(val video: DashTrack, val audio: DashTrack) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * How the MPEG-TS stream is announced to the TV: MIME type and DLNA profile.
 *
 * @param profile the `DLNA.ORG_PN` value, or null when the TV declares none that fits
 */
data class TsFormat(val mimeType: String, val profile: String?) : Serializable {
    /** The fourth field of the protocolInfo and the `contentFeatures.dlna.org` header. */
    val dlnaFeatures: String
        get() = profile?.let { "DLNA.ORG_PN=$it;$LIVE_FEATURES" } ?: LIVE_FEATURES

    companion object {
        private const val serialVersionUID = 1L

        /** No seeking by bytes or time, converted content, streaming transfer mode. */
        const val LIVE_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        val DEFAULT = TsFormat("video/mp2t", null)

        /**
         * `video/vnd.dlna.mpeg-tts` is the timestamped variant, with 192-byte packets: the
         * muxer writes plain 188-byte packets, which DLNA calls `video/mpeg` with `_ISO` profiles.
         */
        private const val TIMESTAMPED = "video/vnd.dlna.mpeg-tts"

        /** Without a fitting profile, in order of preference. */
        private val PLAIN_MIME_TYPES = listOf("video/mp2t", "video/mpeg", "video/x-mpeg-ts")

        /**
         * Chooses from the formats the TV declares, as TVs differ in the name they expect:
         * an H.264 MPEG-TS profile with 188-byte packets (`AVC_TS_..._ISO`), AAC and HD first.
         *
         * @param sinkProtocolInfo the `Sink` answer of `GetProtocolInfo`: protocolInfo entries
         * separated by commas, e.g. `http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_EU_ISO`, or
         * null if unknown
         */
        fun choose(sinkProtocolInfo: String?): TsFormat {
            val declared = parse(sinkProtocolInfo)
            val avc = declared.filter { (mime, profile) ->
                mime != TIMESTAMPED && profile != null && profile.startsWith("AVC_TS_") && profile.endsWith("_ISO")
            }
            val best = avc.maxByOrNull { (_, profile) ->
                (if ("AAC" in profile!!) 4 else 0) + (if ("HD" in profile) 2 else 0) + (if ("HP" in profile) 1 else 0)
            }
            if (best != null) return TsFormat(best.first, best.second)
            val mimeTypes = declared.map { it.first }.toSet()
            return PLAIN_MIME_TYPES.firstOrNull { it in mimeTypes }?.let { TsFormat(it, null) } ?: DEFAULT
        }

        /** @return the video entries of [sinkProtocolInfo] as (MIME type, DLNA profile) */
        fun parse(sinkProtocolInfo: String?): List<Pair<String, String?>> = sinkProtocolInfo.orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val fields = entry.trim().split(':', limit = 4)
                val mime = fields.getOrNull(2)?.lowercase()?.takeIf { it.startsWith("video/") }
                    ?: return@mapNotNull null
                val profile = fields.getOrNull(3)?.split(';')
                    ?.firstOrNull { it.startsWith("DLNA.ORG_PN=") }
                    ?.removePrefix("DLNA.ORG_PN=")
                mime to profile
            }
    }
}
