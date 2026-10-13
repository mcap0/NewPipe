/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.Serializable
import java.util.Locale

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
 * A video-only and an audio stream that the TV plays together through a DASH manifest, since
 * there is no single file with both.
 */
data class CastDash(val video: DashTrack, val audio: DashTrack) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Builds the static MPEG-DASH manifest (MPD) sent to the TV. The streams are not downloaded or
 * remuxed: the TV reads them directly (through the proxy) using the byte ranges in the manifest.
 */
object DashManifest {
    const val MIME_TYPE = "application/dash+xml"

    /**
     * @param videoUrl the URL the TV downloads the video from (usually the proxy)
     * @param audioUrl the URL the TV downloads the audio from (usually the proxy)
     * @param durationSeconds the duration of the video, or <= 0 if unknown
     */
    fun build(dash: CastDash, videoUrl: String, audioUrl: String, durationSeconds: Long): String {
        val duration = if (durationSeconds > 0) {
            " mediaPresentationDuration=\"PT${durationSeconds}S\""
        } else {
            ""
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"" +
            " profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"" +
            " minBufferTime=\"PT2S\"$duration>\n" +
            "<Period id=\"0\" start=\"PT0S\"$duration>\n" +
            adaptationSet("video", dash.video, videoUrl) +
            adaptationSet("audio", dash.audio, audioUrl) +
            "</Period>\n" +
            "</MPD>\n"
    }

    private fun adaptationSet(id: String, track: DashTrack, url: String): String {
        val size = if (track.width > 0 && track.height > 0) {
            " width=\"${track.width}\" height=\"${track.height}\""
        } else {
            ""
        }
        val audioConfig = if (id == "audio") {
            "<AudioChannelConfiguration" +
                " schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"" +
                " value=\"2\"/>\n"
        } else {
            ""
        }
        return "<AdaptationSet id=\"$id\" contentType=\"$id\"" +
            " mimeType=\"${XmlUtils.escape(track.mimeType)}\" segmentAlignment=\"true\"" +
            " subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">\n" +
            "<Representation id=\"$id-0\" codecs=\"${XmlUtils.escape(track.codecs)}\"" +
            " bandwidth=\"${track.bandwidth.coerceAtLeast(1)}\"$size>\n" +
            audioConfig +
            "<BaseURL>${XmlUtils.escape(url)}</BaseURL>\n" +
            "<SegmentBase indexRange=\"${range(track.indexStart, track.indexEnd)}\" indexRangeExact=\"true\">\n" +
            "<Initialization range=\"${range(track.initStart, track.initEnd)}\"/>\n" +
            "</SegmentBase>\n" +
            "</Representation>\n" +
            "</AdaptationSet>\n"
    }

    private fun range(start: Long, end: Long) = String.format(Locale.US, "%d-%d", start, end)
}
