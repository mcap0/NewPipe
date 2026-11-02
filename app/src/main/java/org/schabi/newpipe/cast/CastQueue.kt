/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

/** What the TV is doing, as seen by the queue. */
enum class TvStatus { PLAYING, PAUSED, TRANSITIONING, STOPPED, OTHER }

/** What the session has to do after a poll of the TV. */
enum class QueueAction { NONE, PLAY_CURRENT, ENDED }

/**
 * What the queue needs to know about an item.
 *
 * The queue is a port of the Lumia app's `core/CastQueue.cpp`: the detection of the end of a
 * video, the automatic advance to the next one and the two STOPPED polls are the result of a
 * long tuning and must stay the same.
 */
interface CastQueueItem {
    /** YouTube page URL, identifies the video. */
    val videoUrl: String

    /** The duration, -1 if unknown. Updated by the session when the stream is prepared. */
    var durationSeconds: Long
}

/**
 * The videos sent to the TV, in the order they are played.
 *
 * The queue deliberately does not know about the TV or the streams: [onPoll] only sees the
 * transport state and position reported by the TV.
 */
class CastQueue<T : CastQueueItem> {
    private val items = ArrayList<T>()

    var index = 0
        private set

    private var hasCurrent = false
    private var hasPlayed = false
    private var endReported = false
    private var stoppedPolls = 0
    private var lastPosition = -1L

    fun reset(first: T) {
        items.clear()
        items.add(first)
        index = 0
        hasCurrent = true
        resetDetection()
        endReported = false
    }

    /**
     * Plays [item] now, inserting it immediately after the current one.
     *
     * @return [QueueAction.PLAY_CURRENT] to play it, or [QueueAction.NONE] if it is already the
     * current video (the Lumia app ignores that, so the stream is not restarted)
     */
    fun playNow(item: T): QueueAction {
        if (hasCurrent && items[index].videoUrl == item.videoUrl) {
            return QueueAction.NONE
        }

        if (!hasCurrent) {
            items.add(item)
            index = items.size - 1
            hasCurrent = true
        } else {
            val insertAt = index + 1
            items.add(insertAt, item)
            index = insertAt
        }

        resetDetection()
        endReported = false
        return QueueAction.PLAY_CURRENT
    }

    /** Appends [item] after the pending ones, ignoring a duplicate pending URL. */
    fun add(item: T) {
        val firstPending = if (hasCurrent) index + 1 else 0
        for (i in firstPending until items.size) {
            if (items[i].videoUrl == item.videoUrl) {
                return
            }
        }
        items.add(item)
    }

    fun playIndex(index: Int): QueueAction {
        if (!hasCurrent || index !in items.indices || index == this.index) {
            return QueueAction.NONE
        }
        this.index = index
        resetDetection()
        endReported = false
        return QueueAction.PLAY_CURRENT
    }

    fun next(): QueueAction {
        if (!hasCurrent || index + 1 >= items.size) {
            return QueueAction.NONE
        }
        index++
        resetDetection()
        endReported = false
        return QueueAction.PLAY_CURRENT
    }

    fun remove(index: Int): Boolean {
        if (index !in items.indices || (hasCurrent && index == this.index)) {
            return false
        }
        items.removeAt(index)
        if (hasCurrent && index < this.index) {
            this.index--
        }
        return true
    }

    fun items(): List<T> = items

    fun hasCurrent(): Boolean = hasCurrent && index in items.indices

    fun current(): T = items[index]

    /** The TV accepted the stream and starts at [startSeconds]. */
    fun started(startSeconds: Long) {
        resetDetection()
        lastPosition = startSeconds
        endReported = false
    }

    /**
     * A poll of the TV. Two consecutive STOPPED polls are needed: a TV can report STOPPED for
     * a moment while it switches stream and then continue on its own.
     */
    fun onPoll(status: TvStatus, positionSeconds: Long): QueueAction {
        if (endReported) {
            return QueueAction.NONE
        }

        if (status == TvStatus.PLAYING || status == TvStatus.PAUSED) {
            hasPlayed = true
            stoppedPolls = 0
            if (positionSeconds >= 0) {
                lastPosition = positionSeconds
            }
            return QueueAction.NONE
        }

        if (status != TvStatus.STOPPED) {
            stoppedPolls = 0
            return QueueAction.NONE
        }

        if (!hasPlayed) {
            return QueueAction.NONE
        }

        stoppedPolls++
        if (stoppedPolls < STOPPED_POLLS_BEFORE_END) {
            return QueueAction.NONE
        }

        val duration = if (hasCurrent()) items[index].durationSeconds else -1
        val ended = duration <= 0 || lastPosition >= duration - END_MARGIN_SECONDS

        if (ended && hasCurrent() && index + 1 < items.size) {
            index++
            resetDetection()
            endReported = false
            return QueueAction.PLAY_CURRENT
        }

        finishSession()
        return QueueAction.ENDED
    }

    fun lastPosition(): Long = lastPosition

    fun hasPlayed(): Boolean = hasPlayed

    fun finishSession() {
        stoppedPolls = 0
        endReported = true
    }

    private fun resetDetection() {
        hasPlayed = false
        stoppedPolls = 0
        lastPosition = -1
    }

    companion object {
        private const val STOPPED_POLLS_BEFORE_END = 2
        private const val END_MARGIN_SECONDS = 15
    }
}
