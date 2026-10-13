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
 * Chooses the MIME type of the MPEG-TS stream from the formats the TV declares, as TVs differ in
 * the name they expect.
 */
object TsMimeType {
    const val DEFAULT = "video/mp2t"

    /** In order of preference. */
    private val KNOWN = listOf(DEFAULT, "video/vnd.dlna.mpeg-tts", "video/mpeg", "video/x-mpeg-ts")

    /**
     * @param sinkProtocolInfo the `Sink` answer of `GetProtocolInfo`: protocolInfo entries
     * separated by commas, e.g. `http-get:*:video/mp2t:*,http-get:*:video/mp4:*`, or null if
     * unknown
     */
    fun choose(sinkProtocolInfo: String?): String {
        val declared = sinkProtocolInfo.orEmpty()
            .split(',')
            .mapNotNull { it.trim().split(':').getOrNull(2)?.lowercase() }
            .toSet()
        return KNOWN.firstOrNull { it in declared } ?: DEFAULT
    }
}
