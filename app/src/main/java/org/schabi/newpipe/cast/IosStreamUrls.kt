/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.cast.dlna.CastChoice
import org.schabi.newpipe.cast.dlna.CastDash
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.DashTrack

/**
 * Some YouTube URLs of the separate DASH streams (`c=ANDROID_VR`) answer HTTP 403 to any range
 * which goes past the first MiB of the audio file, so the TV cannot read the whole stream. The
 * same streams requested through the iOS client are readable to the end: their URLs are asked
 * for here and used in place of the others, when the itag and the length of the file match.
 */
object IosStreamUrls {
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val IOS_CLIENT_NAME = "IOS"
    private const val IOS_CLIENT_ID = "5"
    private const val IOS_CLIENT_VERSION = "20.10.4"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val ITAG = Regex("[?&]itag=(\\d+)")
    private val CLEN = Regex("[?&]clen=(\\d+)")

    /**
     * @return [choice] with the DASH URLs replaced by the iOS ones where there are any, else
     * [choice] itself
     */
    fun withIosUrls(choice: CastChoice, videoId: String): CastChoice {
        val dash = choice.dash ?: return choice
        val urls = try {
            fetch(videoId)
        } catch (e: Exception) {
            CastLog.w("Could not get the iOS stream URLs, using the others", e)
            return choice
        }
        val swapped = CastDash(video = swap(dash.video, urls), audio = swap(dash.audio, urls))
        CastLog.d(
            "Streams: video ${if (swapped.video.url == dash.video.url) "kept" else "from iOS"}, " +
                "audio ${if (swapped.audio.url == dash.audio.url) "kept" else "from iOS"}"
        )
        return choice.copy(dash = swapped)
    }

    private fun swap(track: DashTrack, urls: Map<Pair<Int, Long>, String>): DashTrack {
        val key = keyOf(track.url) ?: return track
        return urls[key]?.let { track.copy(url = it) } ?: track
    }

    /** @return the itag and the length of the file of [url], or null if they are not in it */
    private fun keyOf(url: String): Pair<Int, Long>? {
        val itag = ITAG.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val length = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return itag to length
    }

    /** @return the URLs of the adaptive formats of [videoId] from the iOS client, by itag and length */
    private fun fetch(videoId: String): Map<Pair<Int, Long>, String> {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", IOS_CLIENT_NAME)
                        .put("clientVersion", IOS_CLIENT_VERSION)
                        .put("deviceMake", "Apple")
                        .put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone")
                        .put("osVersion", "18.3.2.22D82")
                        .put("hl", "en")
                        .put("gl", "US")
                )
            )
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val request = Request.Builder()
            .url(PLAYER_URL)
            .header("User-Agent", IOS_USER_AGENT)
            .header("X-YouTube-Client-Name", IOS_CLIENT_ID)
            .header("X-YouTube-Client-Version", IOS_CLIENT_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = DownloaderImpl.getInstance().client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body.string()
        }
        val formats = JSONObject(text).getJSONObject("streamingData").optJSONArray("adaptiveFormats")
            ?: return emptyMap()
        val urls = HashMap<Pair<Int, Long>, String>()
        for (i in 0 until formats.length()) {
            val format = formats.getJSONObject(i)
            val url = format.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val key = keyOf(url) ?: continue
            urls[key] = url
        }
        return urls
    }
}
