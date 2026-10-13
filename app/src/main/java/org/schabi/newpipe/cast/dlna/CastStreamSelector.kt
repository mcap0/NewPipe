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
 * @param codec e.g. `avc1.64001F`, null if unknown
 * @param initRange the byte range of the MP4 initialization data, null if unknown
 * @param indexRange the byte range of the MP4 segment index (sidx), null if unknown
 */
data class CastStreamCandidate(
    val url: String,
    val mimeType: String?,
    val height: Int,
    val isVideoOnly: Boolean,
    val isProgressiveHttp: Boolean,
    val codec: String? = null,
    val width: Int = 0,
    val bitrate: Int = 0,
    val initRange: LongRange? = null,
    val indexRange: LongRange? = null
)

/**
 * An audio stream that could be sent to the TV together with a video-only stream.
 *
 * @param isOriginal false for dubbed or descriptive audio tracks
 */
data class CastAudioCandidate(
    val url: String,
    val mimeType: String?,
    val isProgressiveHttp: Boolean,
    val codec: String? = null,
    val bitrate: Int = 0,
    val isOriginal: Boolean = true,
    val initRange: LongRange? = null,
    val indexRange: LongRange? = null
)

/**
 * Chooses the stream sent to the TV.
 *
 * DLNA renderers play best one file with both audio and video, so "progressive" MP4 streams with
 * audio are used first, at the highest resolution up to [MAX_HEIGHT]. YouTube rarely offers them
 * nowadays, so otherwise a video-only MP4 and an M4A audio stream, chosen by [selectDash], are
 * muxed by the cast proxy into one MPEG-TS stream while they are sent.
 */
object CastStreamSelector {
    const val MAX_HEIGHT = 720
    const val MAX_DASH_HEIGHT = 1080
    const val MP4_MIME_TYPE = "video/mp4"
    const val M4A_MIME_TYPE = "audio/mp4"

    fun select(candidates: List<CastStreamCandidate>): CastStreamCandidate? {
        val usable = candidates.filter {
            !it.isVideoOnly &&
                it.isProgressiveHttp &&
                it.url.isNotEmpty() &&
                it.mimeType.equals(MP4_MIME_TYPE, ignoreCase = true)
        }
        return usable.filter { it.height <= MAX_HEIGHT }.maxByOrNull { it.height }
    }

    /**
     * Chooses a video-only H.264 MP4 stream up to [MAX_DASH_HEIGHT] and an AAC M4A stream
     * (original language first): the codecs MPEG-TS carries and every TV decodes. Both need
     * their init and index ranges, used to read them fragment by fragment.
     *
     * @return the streams to mux, or null if there are no usable streams
     */
    fun selectDash(videos: List<CastStreamCandidate>, audios: List<CastAudioCandidate>): CastDash? {
        val usableVideos = videos.filter {
            it.isVideoOnly &&
                it.isProgressiveHttp &&
                it.url.isNotEmpty() &&
                it.mimeType.equals(MP4_MIME_TYPE, ignoreCase = true) &&
                it.height in 1..MAX_DASH_HEIGHT &&
                (it.codec == null || it.codec.startsWithIgnoreCase(H264)) &&
                hasRanges(it.initRange, it.indexRange)
        }
        val video = usableVideos.maxOfHeight() ?: return null

        val usableAudios = audios.filter {
            it.isProgressiveHttp &&
                it.url.isNotEmpty() &&
                it.mimeType.equals(M4A_MIME_TYPE, ignoreCase = true) &&
                (it.codec == null || it.codec.startsWithIgnoreCase(AAC)) &&
                hasRanges(it.initRange, it.indexRange)
        }
        val audio = usableAudios.sortedWith(
            compareByDescending<CastAudioCandidate> { it.isOriginal }
                .thenByDescending { it.bitrate }
        ).firstOrNull() ?: return null

        return CastDash(
            video = DashTrack(
                url = video.url,
                mimeType = MP4_MIME_TYPE,
                codecs = video.codec ?: DEFAULT_VIDEO_CODEC,
                bandwidth = video.bitrate,
                width = video.width,
                height = video.height,
                initStart = video.initRange!!.first,
                initEnd = video.initRange.last,
                indexStart = video.indexRange!!.first,
                indexEnd = video.indexRange.last
            ),
            audio = DashTrack(
                url = audio.url,
                mimeType = M4A_MIME_TYPE,
                codecs = audio.codec ?: DEFAULT_AUDIO_CODEC,
                bandwidth = audio.bitrate,
                initStart = audio.initRange!!.first,
                initEnd = audio.initRange.last,
                indexStart = audio.indexRange!!.first,
                indexEnd = audio.indexRange.last
            )
        )
    }

    /** @return the range from [start] to [end] (both included), or null if not valid */
    fun rangeOf(start: Int, end: Int): LongRange? = if (start >= 0 && end > start) start.toLong()..end.toLong() else null

    private fun hasRanges(initRange: LongRange?, indexRange: LongRange?) = initRange != null && indexRange != null

    private fun List<CastStreamCandidate>.maxOfHeight() = maxWithOrNull(compareBy<CastStreamCandidate> { it.height }.thenBy { it.bitrate })

    private fun String?.startsWithIgnoreCase(prefix: String) = this?.startsWith(prefix, ignoreCase = true) == true

    private const val H264 = "avc1"
    private const val AAC = "mp4a"
    private const val DEFAULT_VIDEO_CODEC = "avc1.4d401f"
    private const val DEFAULT_AUDIO_CODEC = "mp4a.40.2"
}
