/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.hls

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTsMuxerTest {
    private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, nal ->
        acc + byteArrayOf(0, 0, 0, 1) + nal
    }

    private val sps = byteArrayOf(0x67, 1, 2)
    private val pps = byteArrayOf(0x68, 3)
    private val sei = byteArrayOf(0x06, 5, 5)
    private val idr = byteArrayOf(0x65, 7, 7, 7)

    @Test
    fun seiAfterTheSlicesIsDropped() {
        // the tvlab case: YouTube's SEI joined after the keyframe's slices
        val unit = annexB(sps, pps, idr, sei)
        assertArrayEquals(annexB(sps, pps, idr), HlsTsMuxer.dropTrailingSei(unit))
    }

    @Test
    fun seiBeforeTheSlicesIsKept() {
        val unit = annexB(sps, pps, sei, idr)
        assertArrayEquals(unit, HlsTsMuxer.dropTrailingSei(unit))
    }

    @Test
    fun remuxedStreamDemuxesBack() {
        val output = ByteArrayOutputStream()
        val muxer = HlsTsMuxer(output, 1000)
        val adts = HlsTsMuxer.makeAdts(ByteArray(20) { 1 }, 44100, 2)
        muxer.pushVideo(annexB(sps, pps, idr), 4000, 1000, true)
        muxer.pushAudio(adts, 1000)
        muxer.pushVideo(annexB(byteArrayOf(0x41, 9)), 7000, 4000, false)
        muxer.pushAudio(adts, 3000)
        muxer.finish()
        val bytes = output.toByteArray()
        assertEquals(0, bytes.size % 188)

        val parsed = TsDemux.parseTsSegment(bytes)
        assertNotNull(parsed)
        parsed!!
        assertEquals(2, parsed.video.size)
        assertTrue(parsed.video[0].key)
        // the clock starts at the offset, 1.4 s later like ffmpeg
        assertEquals(126_000L, parsed.baseRaw)
        assertEquals(3000L, parsed.video[0].pts90k)
        assertEquals(2, parsed.audio.size)
        assertEquals(44100, parsed.audioSampleRate)
        assertEquals(2, parsed.audioChannels)
    }
}
