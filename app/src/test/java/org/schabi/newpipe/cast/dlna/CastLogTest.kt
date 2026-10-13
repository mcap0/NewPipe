/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastLogTest {
    @After
    fun tearDown() {
        CastLog.clear()
    }

    @Test
    fun keepsOnlyTheLatestEntries() {
        CastLog.clear()
        repeat(CastLog.MAX_ENTRIES + 10) { CastLog.d("line $it") }
        assertEquals(CastLog.MAX_ENTRIES, CastLog.size())
        val dump = CastLog.dump()
        assertFalse(dump.contains("line 9\n"))
        assertTrue(dump.contains("line 10\n"))
        assertTrue(dump.endsWith("line ${CastLog.MAX_ENTRIES + 9}"))
    }

    @Test
    fun truncatesLongEntriesAndIncludesErrors() {
        CastLog.clear()
        CastLog.d("x".repeat(CastLog.MAX_ENTRY_LENGTH + 5))
        CastLog.e("failed", IllegalStateException("boom"))
        val dump = CastLog.dump()
        assertTrue(dump.contains("[5 more chars]"))
        assertTrue(dump.contains("E failed"))
        assertTrue(dump.contains("IllegalStateException: boom"))
    }
}
