/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the Lumia app's `tests/queue_tests.cpp`. */
class CastQueueTest {
    private class Item(override val videoUrl: String, override var durationSeconds: Long) : CastQueueItem

    private fun item(url: String, duration: Long) = Item(url, duration)

    @Test
    fun addAppendsAndIgnoresDuplicatePendingUrl() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.add(item("video-2", 120))
        queue.add(item("video-2", 120))
        queue.add(item("video-3", 90))
        assertEquals(3, queue.items().size)
    }

    @Test
    fun playNowInsertsAfterCurrent() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.add(item("video-2", 120))
        queue.add(item("video-3", 90))

        val now = item("video-now", 80)
        assertEquals(QueueAction.PLAY_CURRENT, queue.playNow(now))
        assertEquals(1, queue.index)
        assertEquals("video-now", queue.current().videoUrl)
        assertEquals("video-2", queue.items()[2].videoUrl)
        assertEquals(QueueAction.NONE, queue.playNow(now))
    }

    @Test
    fun playIndexAndNext() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.add(item("video-2", 120))
        queue.add(item("video-3", 90))
        queue.add(item("video-now", 80))

        assertEquals(QueueAction.NONE, queue.playIndex(queue.items().size))
        assertEquals(QueueAction.PLAY_CURRENT, queue.playIndex(2))
        assertEquals(QueueAction.NONE, queue.playIndex(queue.index))
        assertEquals(QueueAction.PLAY_CURRENT, queue.playIndex(0))
        assertEquals(0, queue.index)
        assertEquals(QueueAction.PLAY_CURRENT, queue.next())
        assertEquals("video-2", queue.current().videoUrl)
        assertEquals(QueueAction.PLAY_CURRENT, queue.playIndex(queue.items().size - 1))
        assertEquals(QueueAction.NONE, queue.next())
    }

    @Test
    fun removeKeepsTheCurrentItem() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.add(item("video-2", 120))
        queue.add(item("video-3", 90))
        queue.playIndex(1)

        assertFalse(queue.remove(queue.index))
        assertTrue(queue.remove(0))
        assertEquals(0, queue.index)
        assertEquals("video-2", queue.current().videoUrl)
        assertTrue(queue.remove(1))
        assertEquals(1, queue.items().size)
        assertEquals(0, queue.index)
    }

    @Test
    fun stoppedBeforePlaybackIsIgnored() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.add(item("video-2", 120))
        queue.started(0)

        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.STOPPED, -1))
        assertFalse(queue.hasPlayed())
        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.PLAYING, 30))
        assertTrue(queue.hasPlayed())
        assertEquals(30L, queue.lastPosition())
        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.PAUSED, 35))
        assertEquals(35L, queue.lastPosition())
        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.PLAYING, -1))
        assertEquals(35L, queue.lastPosition())
        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.STOPPED, -1))

        queue.onPoll(TvStatus.PLAYING, 92)
        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.STOPPED, -1))
        assertEquals(QueueAction.PLAY_CURRENT, queue.onPoll(TvStatus.STOPPED, -1))
        assertEquals("video-2", queue.current().videoUrl)
        assertFalse(queue.hasPlayed())
    }

    @Test
    fun stoppingAtTheEndEndsTheQueueOnce() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-2", 120))
        queue.started(0)
        queue.onPoll(TvStatus.PLAYING, 110)

        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.STOPPED, -1))
        assertEquals(QueueAction.ENDED, queue.onPoll(TvStatus.STOPPED, -1))
        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.STOPPED, -1))
    }

    @Test
    fun stoppingInTheMiddleEndsTheSession() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.started(0)
        queue.onPoll(TvStatus.PLAYING, 30)

        assertEquals(QueueAction.NONE, queue.onPoll(TvStatus.STOPPED, -1))
        assertEquals(QueueAction.ENDED, queue.onPoll(TvStatus.STOPPED, -1))
    }

    @Test
    fun startedResetsDetection() {
        val queue = CastQueue<Item>()
        queue.reset(item("video-1", 100))
        queue.started(12)
        assertFalse(queue.hasPlayed())
        assertEquals(12L, queue.lastPosition())

        queue.onPoll(TvStatus.PLAYING, 18)
        queue.started(5)
        assertFalse(queue.hasPlayed())
        assertEquals(5L, queue.lastPosition())
    }
}
