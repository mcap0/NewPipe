/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

/**
 * A single byte range from a `Range: bytes=...` header.
 *
 * @param start first byte, or null for a suffix range (`bytes=-500`, the last 500 bytes)
 * @param end last byte (inclusive), or null for an open range (`bytes=100-`)
 * @param suffixLength length of a suffix range, null otherwise
 */
data class HttpRange(val start: Long?, val end: Long?, val suffixLength: Long? = null) {

    /** @return the header value to forward to the upstream server */
    fun toHeaderValue(): String = when {
        suffixLength != null -> "bytes=-$suffixLength"
        end != null -> "bytes=$start-$end"
        else -> "bytes=$start-"
    }

    companion object {
        /**
         * Parses a Range header. Only single ranges in bytes are supported: that is what video
         * players ask for. Multiple ranges (`bytes=0-1,5-6`) would need a multipart answer.
         *
         * @return the range, null if there is no header (the whole file is requested)
         * @throws IllegalArgumentException if the header is malformed or not supported
         */
        fun parse(header: String?): HttpRange? {
            if (header.isNullOrBlank()) {
                return null
            }
            val value = header.trim()
            require(value.startsWith("bytes=", ignoreCase = true)) { "Unsupported range unit: $value" }
            val spec = value.substring("bytes=".length).trim()
            require(!spec.contains(',')) { "Multiple ranges are not supported: $value" }

            val dash = spec.indexOf('-')
            require(dash >= 0) { "Malformed range: $value" }
            val startText = spec.substring(0, dash).trim()
            val endText = spec.substring(dash + 1).trim()

            if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull()
                require(suffix != null && suffix > 0) { "Malformed suffix range: $value" }
                return HttpRange(null, null, suffix)
            }
            val start = startText.toLongOrNull()
            require(start != null && start >= 0) { "Malformed range start: $value" }
            if (endText.isEmpty()) {
                return HttpRange(start, null)
            }
            val end = endText.toLongOrNull()
            require(end != null && end >= start) { "Malformed range end: $value" }
            return HttpRange(start, end)
        }

        /**
         * Parses the total length from a `Content-Range: bytes 0-0/12345` header.
         * @return the total length, or -1 if unknown (`*`) or malformed
         */
        fun totalLengthFromContentRange(contentRange: String?): Long {
            val total = contentRange?.substringAfterLast('/', "")?.trim() ?: return -1
            return total.toLongOrNull() ?: -1
        }
    }
}
