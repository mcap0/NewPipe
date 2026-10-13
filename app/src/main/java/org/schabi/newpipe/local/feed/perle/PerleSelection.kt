/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.perle

import kotlin.random.Random
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Pure helpers used by "Perle" to pick the most viewed videos of each channel and to
 * mix them so that every channel has the same weight in the final list.
 */
object PerleSelection {
    const val VIDEOS_PER_CHANNEL = 8
    const val MIN_DURATION_SECONDS = 120L

    /**
     * Keeps only regular videos (no shorts, no live streams, no past live streams,
     * no videos shorter than [MIN_DURATION_SECONDS]) and returns the [limit] most viewed ones.
     */
    fun selectTopVideos(
        items: List<StreamInfoItem>,
        limit: Int = VIDEOS_PER_CHANNEL
    ): List<StreamInfoItem> {
        return items
            .filter {
                it.streamType == StreamType.VIDEO_STREAM &&
                    !it.isShortFormContent &&
                    it.duration >= MIN_DURATION_SECONDS
            }
            .distinctBy { it.url }
            .sortedByDescending { it.viewCount }
            .take(limit)
    }

    /**
     * Shuffles the videos of each channel and then interleaves the channels: every round takes
     * one video from each channel which still has videos left, in a random channel order.
     * This way no channel dominates the list, regardless of how many videos it has.
     */
    fun <T> interleave(perChannel: List<List<T>>, random: Random = Random.Default): List<T> {
        val queues = perChannel
            .filter { it.isNotEmpty() }
            .map { ArrayDeque(it.shuffled(random)) }
        val result = ArrayList<T>(queues.sumOf { it.size })
        var remaining = queues
        while (remaining.isNotEmpty()) {
            remaining.shuffled(random).forEach { result.add(it.removeFirst()) }
            remaining = remaining.filter { it.isNotEmpty() }
        }
        return result
    }
}
