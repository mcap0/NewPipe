/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.perle

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class PerleSelectionTest {
    private fun item(
        id: String,
        views: Long,
        duration: Long = 600,
        type: StreamType = StreamType.VIDEO_STREAM,
        short: Boolean = false
    ) = StreamInfoItem(0, "https://example.com/$id", id, type).apply {
        viewCount = views
        this.duration = duration
        isShortFormContent = short
    }

    @Test
    fun selectTopVideosKeepsMostViewedRegularVideos() {
        val items = listOf(
            item("a", 10),
            item("b", 500),
            item("short", 9_999, short = true),
            item("live", 9_999, type = StreamType.LIVE_STREAM),
            item("pastLive", 9_999, type = StreamType.POST_LIVE_STREAM),
            item("tooShort", 9_999, duration = 119),
            item("c", 300),
            item("b", 500)
        )

        val selected = PerleSelection.selectTopVideos(items, limit = 2)

        assertEquals(listOf("b", "c"), selected.map { it.name })
    }

    @Test
    fun interleaveGivesEveryChannelTheSameWeight() {
        val big = (1..10).map { "big$it" }
        val small = listOf("small1", "small2")
        val other = listOf("other1", "other2", "other3")

        repeat(20) { seed ->
            val result = PerleSelection.interleave(listOf(big, small, other, emptyList()), Random(seed))

            assertEquals(big.size + small.size + other.size, result.size)
            assertEquals((big + small + other).toSet(), result.toSet())
            // the first round contains exactly one video of each channel
            assertEquals(setOf("big", "small", "other"), result.take(3).map { it.takeWhile(Char::isLetter) }.toSet())
            // both videos of the small channel appear within the first two rounds
            assertTrue(result.take(6).containsAll(small))
        }
    }

    @Test
    fun interleaveOfNothingIsEmpty() {
        assertTrue(PerleSelection.interleave(emptyList<List<String>>()).isEmpty())
    }
}
