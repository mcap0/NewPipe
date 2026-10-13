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
}
