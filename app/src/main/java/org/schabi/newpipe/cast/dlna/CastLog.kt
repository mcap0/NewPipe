/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory circular log of everything "Cast to TV" does: SSDP answers, device descriptions,
 * SOAP requests and responses, proxy requests and errors.
 *
 * DLNA renderers differ a lot from each other and the developer usually cannot test on the
 * user's TV, so the user can copy this log from the settings and attach it to a bug report.
 *
 * This class does not depend on Android, so that it can be used in unit tests. The Android
 * layer forwards every line to logcat through [sink].
 */
object CastLog {
    const val TAG = "DlnaCast"

    /** Maximum number of entries kept in memory; the oldest ones are dropped first. */
    const val MAX_ENTRIES = 1000

    /** Long payloads (XML documents) are truncated to this many characters. */
    const val MAX_ENTRY_LENGTH = 8000

    /** Size of the log copied to be pasted in a message. */
    const val SHARE_LENGTH = 20_000

    private val entries = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Additional destination of every log line, e.g. logcat. Called outside of the lock. */
    @Volatile
    var sink: ((level: Char, message: String, error: Throwable?) -> Unit)? = null

    fun d(message: String) = add('D', message, null)

    fun w(message: String, error: Throwable? = null) = add('W', message, error)

    fun e(message: String, error: Throwable? = null) = add('E', message, error)

    private fun add(level: Char, message: String, error: Throwable?) {
        val text = buildString {
            append(truncate(message))
            if (error != null) {
                append('\n')
                append(truncate(stackTraceOf(error)))
            }
        }
        synchronized(this) {
            val line = "${timeFormat.format(Date())} $level $text"
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
        sink?.invoke(level, message, error)
    }

    /** @return the whole log, oldest entries first, ready to be copied to the clipboard */
    @Synchronized
    fun dump(): String = entries.joinToString("\n")

    /**
     * @return the newest entries, oldest first, at most [maxChars] characters: what is pasted in
     * a message is often cut, and the end is what tells what went wrong
     */
    @Synchronized
    fun dumpRecent(maxChars: Int = SHARE_LENGTH): String {
        val recent = ArrayList<String>()
        var length = 0
        for (entry in entries.descendingIterator()) {
            if (recent.isNotEmpty() && length + entry.length + 1 > maxChars) break
            recent.add(entry)
            length += entry.length + 1
        }
        return recent.asReversed().joinToString("\n")
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    private fun truncate(text: String): String {
        return if (text.length <= MAX_ENTRY_LENGTH) {
            text
        } else {
            text.substring(0, MAX_ENTRY_LENGTH) + "… [${text.length - MAX_ENTRY_LENGTH} more chars]"
        }
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
