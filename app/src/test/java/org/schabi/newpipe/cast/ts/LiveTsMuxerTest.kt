/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.ts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTsMuxerTest {
    private val avc = CodecConfig.Avc(4, listOf(byteArrayOf(0x67, 1)), listOf(byteArrayOf(0x68, 2)))

    /** An MP4 sample: NAL units prefixed by their length on 4 bytes. */
    private fun sample(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, nal ->
        acc + byteArrayOf(0, 0, 0, nal.size.toByte()) + nal
    }

    private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, nal ->
        acc + byteArrayOf(0, 0, 0, 1) + nal
    }

    private val aud = byteArrayOf(0x09, 0xF0.toByte())
    private val sps = byteArrayOf(0x67, 1)
    private val pps = byteArrayOf(0x68, 2)
    private val idr = byteArrayOf(0x65, 7, 7)
    private val slice = byteArrayOf(0x41, 8)
    private val sei = byteArrayOf(0x06, 5, 5)

    @Test
    fun addsParameterSetsBeforeKeyframes() {
        val (data, isKeyframe) = LiveTsMuxer.toAnnexB(sample(idr), avc)
        assertTrue(isKeyframe)
        assertArrayEquals(annexB(aud, sps, pps, idr), data)
    }

    @Test
    fun movesTheSeiBeforeTheSlices() {
        // as in the first frame of some YouTube videos: [5, 6]
        val (data, _) = LiveTsMuxer.toAnnexB(sample(idr, sei), avc)
        assertArrayEquals(annexB(aud, sps, pps, sei, idr), data)
    }

    @Test
    fun dropsDelimitersAndEndOfSequence() {
        val (data, isKeyframe) = LiveTsMuxer.toAnnexB(sample(aud, slice, byteArrayOf(0x0A)), avc)
        assertFalse(isKeyframe)
        assertArrayEquals(annexB(aud, slice), data)
    }
}
