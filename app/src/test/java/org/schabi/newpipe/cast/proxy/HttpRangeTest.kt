/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRangeTest {
    @Test
    fun parsesSupportedRanges() {
        assertNull(HttpRange.parse(null))
        assertNull(HttpRange.parse("  "))
        assertEquals(HttpRange(0, 99), HttpRange.parse("bytes=0-99"))
        assertEquals(HttpRange(100, null), HttpRange.parse("bytes=100-"))
        assertEquals(HttpRange(null, null, 500), HttpRange.parse("bytes=-500"))
        assertEquals(HttpRange(5, 10), HttpRange.parse(" Bytes = 5 - 10 ".replace("Bytes = ", "bytes=")))
    }

    @Test
    fun formatsHeaderValues() {
        assertEquals("bytes=0-99", HttpRange(0, 99).toHeaderValue())
        assertEquals("bytes=100-", HttpRange(100, null).toHeaderValue())
        assertEquals("bytes=-500", HttpRange(null, null, 500).toHeaderValue())
    }

    @Test
    fun rejectsInvalidRanges() {
        listOf("items=0-1", "bytes=0-1,5-6", "bytes=abc", "bytes=10-5", "bytes=-0", "bytes=-", "bytes=x-")
            .forEach {
                try {
                    HttpRange.parse(it)
                    throw AssertionError("$it should be rejected")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
            }
    }

    @Test
    fun parsesTotalLengthFromContentRange() {
        assertEquals(12345, HttpRange.totalLengthFromContentRange("bytes 0-0/12345"))
        assertEquals(-1, HttpRange.totalLengthFromContentRange("bytes 0-0/*"))
        assertEquals(-1, HttpRange.totalLengthFromContentRange(null))
    }
}
