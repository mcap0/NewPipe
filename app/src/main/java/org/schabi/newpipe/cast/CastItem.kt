/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.content.Context
import org.schabi.newpipe.cast.dlna.CastAudioCandidate
import org.schabi.newpipe.cast.dlna.CastChoice
import org.schabi.newpipe.cast.dlna.CastMedia
import org.schabi.newpipe.cast.dlna.CastStreamCandidate
import org.schabi.newpipe.cast.dlna.CastStreamSelector
import org.schabi.newpipe.cast.dlna.SrtConverter
import org.schabi.newpipe.cast.dlna.TsFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.Localization

/**
 * A video of the cast queue, with the qualities, audio tracks and subtitles that can be chosen
 * for it.
 */
class CastItem(val info: StreamInfo) : CastQueueItem {
    val title: String = info.name.orEmpty()
    val uploader: String = info.uploaderName.orEmpty()
    override val videoUrl: String = info.url.orEmpty()
    override var durationSeconds: Long = info.duration

    /**
     * Where to start when this video is played: the phone player's position when it was sent
     * to the TV, or -1 to use the position saved in the history. Used once, then reset to -1.
     */
    @Volatile
    var startSeconds: Long = -1

    /** The audio track chosen for this video, null for the original one. */
    @Volatile
    var audioTrackId: String? = null

    /** The language of [audioTrackId], kept for the next videos like the Lumia app's `audioLang`. */
    @Volatile
    var audioLanguage: String? = null

    private val progressive = info.videoStreams.map { toCandidate(it) }
    private val videoOnly = info.videoOnlyStreams.map { toCandidate(it) }
    private val audios = info.audioStreams.map { toAudioCandidate(it) }

    /** The resolutions that can be cast, highest first. */
    val qualities: List<Int> = CastStreamSelector.qualities(progressive, videoOnly)

    /** The ids of the audio tracks that can be chosen, empty if there is only one. */
    val audioTrackIds: List<String> = CastStreamSelector.audioTracks(audios)

    /** The subtitles that can be sent to the TV, one per language. */
    val subtitles: List<SubtitlesStream> = info.subtitles
        .filter { it.isUrl && !it.content.isNullOrEmpty() && SrtConverter.isSupported(suffixOf(it)) }
        .sortedBy { FORMAT_PREFERENCE.indexOf(suffixOf(it)) }
        .distinctBy { subtitleKey(it) }

    val isCastable: Boolean
        get() = choose(null, null) != null

    /**
     * @param height the wanted resolution, null for automatic
     * @param audioTrackId the wanted audio track, null for the original one
     */
    fun choose(height: Int?, audioTrackId: String?): CastChoice? = CastStreamSelector.choose(progressive, videoOnly, audios, height, audioTrackId)

    /** @return what to send to the TV, with the original URLs of the streams */
    fun media(choice: CastChoice): CastMedia {
        val dash = choice.dash
        return if (dash != null) {
            CastMedia(
                url = dash.video.url,
                title = title,
                uploader = uploader,
                mimeType = TsFormat.DEFAULT.mimeType,
                durationSeconds = durationSeconds,
                dash = dash
            )
        } else {
            CastMedia(
                url = choice.single!!.url,
                title = title,
                uploader = uploader,
                mimeType = CastStreamSelector.MP4_MIME_TYPE,
                durationSeconds = durationSeconds
            )
        }
    }

    fun audioTrackName(context: Context, trackId: String): String {
        val stream = info.audioStreams.firstOrNull { it.audioTrackId == trackId } ?: return trackId
        return Localization.audioTrackName(context, stream)
    }

    /** @return the language of [trackId], or null if it is unknown or the track is gone */
    fun audioLanguageOf(trackId: String?): String? = info.audioStreams
        .firstOrNull { trackId != null && it.audioTrackId == trackId }
        ?.audioLocale
        ?.language

    /** @return a track in [language], preferring the original one, or null if there is none */
    fun audioTrackForLanguage(language: String?): String? {
        if (language.isNullOrEmpty()) {
            return null
        }
        val candidates = info.audioStreams.filter {
            it.audioTrackId != null && it.audioLocale?.language.equals(language, ignoreCase = true)
        }
        return candidates.firstOrNull { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            ?.audioTrackId
            ?: candidates.firstOrNull()?.audioTrackId
    }

    fun subtitleName(context: Context, subtitles: SubtitlesStream): String = PlayerHelper.captionLanguageOf(context, subtitles)

    /** @return the subtitles matching [key] (see [subtitleKey]), or null */
    fun findSubtitles(key: String?): SubtitlesStream? = key?.let { subtitles.firstOrNull { s -> subtitleKey(s) == it } }
        // e.g. "en" wanted and only "en-US" available
        ?: key?.substringBefore('|')?.substringBefore('-')?.let { language ->
            subtitles.firstOrNull { s -> s.languageTag.orEmpty().substringBefore('-') == language && !s.isAutoGenerated }
                ?: subtitles.firstOrNull { s -> s.languageTag.orEmpty().substringBefore('-') == language }
        }

    companion object {
        private val FORMAT_PREFERENCE = listOf("srt", "vtt", "ttml", "xml", "dfxp")

        fun suffixOf(subtitles: SubtitlesStream): String? = subtitles.format?.suffix

        /** Identifies the subtitles of a language across the videos of the queue. */
        fun subtitleKey(subtitles: SubtitlesStream): String = "${subtitles.languageTag}|${subtitles.isAutoGenerated}"

        private fun toCandidate(stream: VideoStream) = CastStreamCandidate(
            url = if (stream.isUrl) stream.content.orEmpty() else "",
            mimeType = stream.format?.mimeType,
            height = stream.height.takeIf { it > 0 }
                ?: stream.getResolution().substringBefore('p').toIntOrNull()
                ?: 0,
            isVideoOnly = stream.isVideoOnly(),
            isProgressiveHttp = stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
            codec = stream.codec,
            width = stream.width,
            bitrate = stream.bitrate,
            initRange = CastStreamSelector.rangeOf(stream.initStart, stream.initEnd),
            indexRange = CastStreamSelector.rangeOf(stream.indexStart, stream.indexEnd)
        )

        private fun toAudioCandidate(stream: AudioStream) = CastAudioCandidate(
            url = if (stream.isUrl) stream.content.orEmpty() else "",
            mimeType = stream.format?.mimeType,
            isProgressiveHttp = stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
            codec = stream.codec,
            bitrate = stream.bitrate.takeIf { it > 0 } ?: stream.averageBitrate.coerceAtLeast(0),
            isOriginal = stream.audioTrackType == null || stream.audioTrackType == AudioTrackType.ORIGINAL,
            initRange = CastStreamSelector.rangeOf(stream.initStart, stream.initEnd),
            indexRange = CastStreamSelector.rangeOf(stream.indexStart, stream.indexEnd),
            trackId = stream.audioTrackId
        )
    }
}
