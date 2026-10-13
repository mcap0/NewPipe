/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

/**
 * A video stream that could be sent to the TV, decoupled from the extractor classes so that
 * the selection rules can be unit tested without them.
 *
 * @param height the vertical resolution, e.g. 720, or 0 if unknown
 * @param isVideoOnly true for DASH video streams without audio
 * @param isProgressiveHttp true if the stream is a single file downloadable via HTTP (not DASH,
 * HLS, ...)
 */
data class CastStreamCandidate(
    val url: String,
    val mimeType: String?,
    val height: Int,
    val isVideoOnly: Boolean,
    val isProgressiveHttp: Boolean
)

/**
 * Chooses the stream sent to the TV.
 *
 * DLNA renderers need one file with both audio and video, so only "progressive" MP4 streams
 * with audio are used, at the highest resolution up to [MAX_HEIGHT].
 *
 * TODO phase 2: 1080p and above only exist as separate DASH video and audio streams. Casting
 *  them would need the local proxy to mux both into one MP4 (or MPEG-TS) on the fly.
 */
object CastStreamSelector {
    const val MAX_HEIGHT = 720
    const val MP4_MIME_TYPE = "video/mp4"

    fun select(candidates: List<CastStreamCandidate>): CastStreamCandidate? {
        val usable = candidates.filter {
            !it.isVideoOnly &&
                it.isProgressiveHttp &&
                it.url.isNotEmpty() &&
                it.mimeType.equals(MP4_MIME_TYPE, ignoreCase = true)
        }
        return usable.filter { it.height <= MAX_HEIGHT }.maxByOrNull { it.height }
    }
}
