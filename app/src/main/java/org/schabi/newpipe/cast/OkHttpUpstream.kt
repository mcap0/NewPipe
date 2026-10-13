/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.proxy.HttpRange
import org.schabi.newpipe.cast.proxy.Upstream
import org.schabi.newpipe.cast.proxy.UpstreamResponse
import org.schabi.newpipe.cast.proxy.YoutubeRange
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl

/**
 * Downloads the video for [org.schabi.newpipe.cast.proxy.CastProxyServer] with OkHttp.
 *
 * YouTube `videoplayback` URLs are requested the same way NewPipe's own player does (see
 * `YoutubeHttpDataSource`): with a POST request, the `rn` parameter and the headers of the
 * client the URL was obtained with. This is the main reason the proxy is needed: a TV which
 * downloads the URL directly with a plain GET is often answered with HTTP 403.
 *
 * When googlevideo answers HTTP 403 to a range requested with the `range` parameter, the same
 * bytes are requested once more with the `Range` header, a different request which it may accept.
 */
class OkHttpUpstream(private val useRangeParameter: Boolean = false) : Upstream {
    private val requestNumber = AtomicInteger()

    private val client: OkHttpClient = DownloaderImpl.getInstance().client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun open(url: String, range: HttpRange?): UpstreamResponse {
        val withRangeParameter = useRangeParameter && YoutubeRange.urlWithRange(url, range) != null
        val response = request(url, range, withRangeParameter)
        if (response.code != 403 || !withRangeParameter) {
            return response
        }
        response.close()
        CastLog.w("HTTP 403 for range parameter ${range?.toHeaderValue()}, trying the Range header")
        return request(url, range, withRangeParameter = false)
    }

    private fun request(url: String, range: HttpRange?, withRangeParameter: Boolean): UpstreamResponse {
        val isVideoPlayback = url.contains("/videoplayback")
        val withRange = if (withRangeParameter) YoutubeRange.urlWithRange(url, range) else null
        val baseUrl = withRange ?: url
        val requestUrl = if (isVideoPlayback && !baseUrl.contains("&rn=")) {
            "$baseUrl&rn=${requestNumber.getAndIncrement()}"
        } else {
            baseUrl
        }

        val builder = Request.Builder()
            .url(requestUrl)
            .header("Accept-Encoding", "identity")
        if (withRange == null) {
            range?.let { builder.header("Range", it.toHeaderValue()) }
        }

        if (isVisionOsStreamingUrl(requestUrl)) {
            builder.header("User-Agent", getVisionOsUserAgent(null))
        } else {
            builder.header("User-Agent", DownloaderImpl.USER_AGENT)
        }
        if (isWebStreamingUrl(requestUrl)) {
            builder.header("Origin", YOUTUBE_BASE_URL)
                .header("Referer", YOUTUBE_BASE_URL)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        if (isVideoPlayback) {
            builder.post(POST_BODY.toRequestBody())
        }

        CastLog.d(
            "Upstream -> ${if (isVideoPlayback) "POST" else "GET"} ${range?.toHeaderValue() ?: "whole file"}" +
                (if (withRange != null) " (as parameter) " else " ") + requestUrl.take(LOGGED_URL_LENGTH) + "…"
        )
        val response = client.newCall(builder.build()).execute()
        val body = response.body
        if (!response.isSuccessful) {
            CastLog.w("Upstream <- HTTP ${response.code} for ${range?.toHeaderValue() ?: "whole file"}")
        }
        // with the range parameter, the server answers 200 with only the bytes asked
        val asPartial = withRange != null && response.code == 200
        return UpstreamResponse(
            code = if (asPartial) 206 else response.code,
            contentLength = body.contentLength(),
            contentRange = if (asPartial) {
                val start = range!!.start!!
                val length = body.contentLength().takeIf { it >= 0 } ?: (range.end!! - start + 1)
                YoutubeRange.contentRange(url, start, length)
            } else {
                response.header("Content-Range")
            },
            contentType = response.header("Content-Type"),
            body = body.byteStream(),
            onClose = { response.close() }
        )
    }

    companion object {
        private const val YOUTUBE_BASE_URL = "https://www.youtube.com"
        private const val LOGGED_URL_LENGTH = 120
        private val POST_BODY = byteArrayOf(0x78, 0)
    }
}
