/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastStreamSelectorTest {
    private fun stream(
        height: Int,
        mime: String? = "video/mp4",
        videoOnly: Boolean = false,
        progressive: Boolean = true
    ) = CastStreamCandidate("https://h/$height-$mime-$videoOnly-$progressive", mime, height, videoOnly, progressive)

    @Test
    fun choosesHighestProgressiveMp4UpTo720p() {
        val chosen = CastStreamSelector.select(
            listOf(stream(360), stream(720), stream(480), stream(1080, videoOnly = true))
        )
        assertEquals(720, chosen!!.height)
    }

    @Test
    fun ignoresVideoOnlyNonMp4AndNonProgressive() {
        val chosen = CastStreamSelector.select(
            listOf(
                stream(720, videoOnly = true),
                stream(720, mime = "video/webm"),
                stream(720, progressive = false),
                stream(240)
            )
        )
        assertEquals(240, chosen!!.height)
    }

    @Test
    fun ignoresResolutionsAbove720p() {
        assertNull(CastStreamSelector.select(listOf(stream(1080), stream(1440))))
    }

    @Test
    fun returnsNullWithoutUsableStreams() {
        assertNull(CastStreamSelector.select(emptyList()))
        assertNull(CastStreamSelector.select(listOf(stream(360, mime = null))))
    }

    private fun videoOnly(height: Int, codec: String = "avc1.640028", ranges: Boolean = true, bitrate: Int = 1000) = CastStreamCandidate(
        url = "https://h/v$height-$codec",
        mimeType = "video/mp4",
        height = height,
        isVideoOnly = true,
        isProgressiveHttp = true,
        codec = codec,
        width = height * 16 / 9,
        bitrate = bitrate,
        initRange = if (ranges) 0L..740L else null,
        indexRange = if (ranges) 741L..1500L else null
    )

    private fun audio(
        codec: String = "mp4a.40.2",
        bitrate: Int = 128_000,
        original: Boolean = true,
        mime: String = "audio/mp4",
        track: String? = null
    ) = CastAudioCandidate(
        url = "https://h/a-$codec-$bitrate-$original" + (track?.let { "-$it" } ?: ""),
        mimeType = mime,
        isProgressiveHttp = true,
        codec = codec,
        bitrate = bitrate,
        isOriginal = original,
        initRange = 0L..630L,
        indexRange = 631L..900L,
        trackId = track
    )

    @Test
    fun dashPrefersH264UpTo1080p() {
        val dash = CastStreamSelector.selectDash(
            listOf(videoOnly(720), videoOnly(1080), videoOnly(1440), videoOnly(2160, "av01.0.12M.08")),
            listOf(audio())
        )!!
        assertEquals(1080, dash.video.height)
        assertEquals("avc1.640028", dash.video.codecs)
        assertEquals(0L, dash.video.initStart)
        assertEquals(1500L, dash.video.indexEnd)
    }

    @Test
    fun dashNeedsH264AndAac() {
        assertNull(
            CastStreamSelector.selectDash(
                listOf(videoOnly(1080, "av01.0.08M.08"), videoOnly(720, "vp09.00.40.08")),
                listOf(audio())
            )
        )
        assertNull(CastStreamSelector.selectDash(listOf(videoOnly(720)), listOf(audio(codec = "opus"))))
    }

    @Test
    fun dashPrefersOriginalAacAudio() {
        val dash = CastStreamSelector.selectDash(
            listOf(videoOnly(720)),
            listOf(
                audio(bitrate = 256_000, original = false),
                audio(codec = "opus", bitrate = 160_000, mime = "audio/webm"),
                audio(codec = "ac-3", bitrate = 384_000),
                audio(bitrate = 48_000),
                audio(bitrate = 128_000)
            )
        )!!
        assertEquals("https://h/a-mp4a.40.2-128000-true", dash.audio.url)
    }

    @Test
    fun dashNeedsRangesAndAudio() {
        assertNull(CastStreamSelector.selectDash(listOf(videoOnly(720, ranges = false)), listOf(audio())))
        assertNull(CastStreamSelector.selectDash(listOf(videoOnly(720)), emptyList()))
        assertNull(CastStreamSelector.selectDash(emptyList(), listOf(audio())))
    }

    @Test
    fun rangeOfRejectsUnknownRanges() {
        assertNull(CastStreamSelector.rangeOf(-1, -1))
        assertNull(CastStreamSelector.rangeOf(0, 0))
        assertEquals(0L..740L, CastStreamSelector.rangeOf(0, 740))
    }

    @Test
    fun listsQualitiesOfProgressiveAndMuxedStreams() {
        assertEquals(
            listOf(1080, 720, 480, 360),
            CastStreamSelector.qualities(
                listOf(stream(360), stream(720), stream(1080)),
                listOf(videoOnly(1080), videoOnly(720), videoOnly(480), videoOnly(1440), videoOnly(720, "vp09.00.40.08"))
            )
        )
    }

    @Test
    fun autoQualityPrefersProgressive() {
        val choice = CastStreamSelector.choose(listOf(stream(360)), listOf(videoOnly(1080)), listOf(audio()))!!
        assertEquals(360, choice.single!!.height)
        assertNull(choice.dash)
    }

    @Test
    fun chosenQualityUsesMuxedStreamsWhenNeeded() {
        val progressive = listOf(stream(360))
        val videos = listOf(videoOnly(480), videoOnly(720), videoOnly(1080))
        assertEquals(360, CastStreamSelector.choose(progressive, videos, listOf(audio()), height = 360)!!.single!!.height)
        val dash = CastStreamSelector.choose(progressive, videos, listOf(audio()), height = 720)!!.dash!!
        assertEquals(720, dash.video.height)
    }

    @Test
    fun otherAudioTrackIsAlwaysMuxed() {
        val audios = listOf(audio(track = "en.4"), audio(original = false, track = "it.3"))
        assertEquals(listOf("en.4", "it.3"), CastStreamSelector.audioTracks(audios))

        val dubbed = CastStreamSelector.choose(listOf(stream(360)), listOf(videoOnly(720)), audios, audioTrackId = "it.3")!!
        assertEquals("https://h/a-mp4a.40.2-128000-false-it.3", dubbed.dash!!.audio.url)

        val original = CastStreamSelector.choose(listOf(stream(360)), listOf(videoOnly(720)), audios, audioTrackId = "en.4")!!
        assertEquals(360, original.single!!.height)
    }

    @Test
    fun noAudioTrackChoiceWithOneTrack() {
        assertEquals(emptyList<String>(), CastStreamSelector.audioTracks(listOf(audio(), audio(bitrate = 48_000))))
        assertEquals(emptyList<String>(), CastStreamSelector.audioTracks(listOf(audio(track = "en"), audio(track = "en"))))
    }
}
