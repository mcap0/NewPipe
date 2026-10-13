/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

import java.io.IOException
import java.io.InputStream
import org.schabi.newpipe.cast.dlna.CastLog

/**
 * Reads [url] from [start] to the end through consecutive range requests of [chunkSize] bytes,
 * so that no connection stays idle for long while the reader waits for the TV to consume data.
 * A chunk which fails is requested again once from where it stopped.
 */
class RangedInputStream(
    private val upstream: Upstream,
    private val url: String,
    start: Long,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE
) : InputStream() {
    private var position = start
    private var totalLength = -1L
    private var current: UpstreamResponse? = null
    private var currentEnd = -1L // last byte of the current chunk
    private var finished = false
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }
        var retried = false
        while (true) {
            if (closed) throw IOException("Stream closed")
            if (finished) return -1
            val body = current?.body ?: run {
                if (!openNextChunk()) return -1
                current?.body ?: return -1
            }
            val read = try {
                body.read(b, off, len)
            } catch (e: IOException) {
                if (retried) throw e
                CastLog.w("Upstream read failed at $position, retrying: ${e.message}")
                retried = true
                closeCurrent()
                continue
            }
            if (read < 0) {
                closeCurrent()
                when {
                    // a server ignoring ranges sent the whole file
                    currentEnd == Long.MAX_VALUE -> finished = true

                    // the chunk is complete, or the file is: the next chunk will tell
                    position > currentEnd || totalLength in 0..position -> {}

                    // the connection ended before the end of the chunk
                    retried -> throw IOException("Upstream ended early at $position")

                    else -> retried = true
                }
                continue
            }
            position += read
            return read
        }
    }

    private fun openNextChunk(): Boolean {
        if (totalLength in 0..position) {
            finished = true
            return false
        }
        val end = position + chunkSize - 1
        val response = upstream.open(url, HttpRange(position, end))
        when (response.code) {
            206 -> {
                val total = HttpRange.totalLengthFromContentRange(response.contentRange)
                if (total >= 0) totalLength = total
                currentEnd = end
            }

            200 -> {
                // the server ignored the range and sends everything from the start
                totalLength = response.contentLength
                skipFully(response.body, position)
                currentEnd = Long.MAX_VALUE
            }

            416 -> {
                response.close()
                finished = true
                return false
            }

            else -> {
                response.close()
                throw IOException("HTTP ${response.code} for range $position-$end")
            }
        }
        current = response
        return true
    }

    private fun skipFully(input: InputStream?, amount: Long) {
        var remaining = amount
        while (remaining > 0 && input != null) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun closeCurrent() {
        try {
            current?.close()
        } catch (_: IOException) {
            // nothing to do
        }
        current = null
    }

    override fun close() {
        closed = true
        closeCurrent()
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 2L * 1024 * 1024

        /** Downloads the bytes from [start] to [end] (both included). */
        fun readRange(upstream: Upstream, url: String, start: Long, end: Long): ByteArray {
            upstream.open(url, HttpRange(start, end)).use { response ->
                if (response.code != 206 && response.code != 200) {
                    throw IOException("HTTP ${response.code} for range $start-$end")
                }
                val body = response.body ?: throw IOException("No body for range $start-$end")
                if (response.code == 200) {
                    body.skip(start)
                }
                val result = ByteArray((end - start + 1).toInt())
                var offset = 0
                while (offset < result.size) {
                    val read = body.read(result, offset, result.size - offset)
                    if (read < 0) throw IOException("Range $start-$end ended after $offset bytes")
                    offset += read
                }
                return result
            }
        }
    }
}
