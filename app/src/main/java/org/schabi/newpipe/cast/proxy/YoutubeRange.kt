/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

/**
 * Range requests the way YouTube's own players make them for DASH streams: with a `range`
 * parameter in the URL instead of the `Range` header, which googlevideo sometimes answers with
 * HTTP 403 for these streams. NewPipe's player does the same (see `YoutubeHttpDataSource`).
 *
 * The server then answers 200 with only the bytes asked, so the answer is turned back into the
 * 206 the callers expect.
 */
object YoutubeRange {
    /** @return [url] with the `range` parameter for [range], or null if it cannot be used */
    fun urlWithRange(url: String, range: HttpRange?): String? {
        val start = range?.start ?: return null
        val end = range.end ?: return null
        if (!url.contains("/videoplayback") || url.contains("&range=")) {
            return null
        }
        return "$url&range=$start-$end"
    }

    /**
     * @param contentLength the length of the answer to a request made with [urlWithRange]
     * @return the `Content-Range` such an answer would have had with the header: the total length
     * comes from the `clen` parameter of the URL, `*` if it is missing
     */
    fun contentRange(url: String, start: Long, contentLength: Long): String {
        val total = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1) ?: "*"
        return "bytes $start-${start + contentLength - 1}/$total"
    }
}
