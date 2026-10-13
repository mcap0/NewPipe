/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeRangeTest {
    private val url = "https://rr1.googlevideo.com/videoplayback?expire=1&itag=137&clen=5000"

    @Test
    fun addsTheRangeParameterToVideoplaybackUrls() {
        assertEquals("$url&range=100-199", YoutubeRange.urlWithRange(url, HttpRange(100, 199)))
    }

    @Test
    fun keepsTheHeaderWhenTheParameterCannotBeUsed() {
        assertNull(YoutubeRange.urlWithRange(url, null))
        assertNull(YoutubeRange.urlWithRange(url, HttpRange(100, null)))
        assertNull(YoutubeRange.urlWithRange("https://example.com/video.mp4", HttpRange(0, 1)))
    }

    @Test
    fun buildsTheContentRangeFromClen() {
        assertEquals("bytes 100-199/5000", YoutubeRange.contentRange(url, 100, 100))
        assertEquals("bytes 0-9/*", YoutubeRange.contentRange("https://x/videoplayback?itag=1", 0, 10))
    }
}
