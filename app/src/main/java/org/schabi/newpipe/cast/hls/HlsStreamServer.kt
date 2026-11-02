/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.hls

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.SrtConverter

/**
 * The stream server of a cast: reads YouTube's HLS playlists (the visionOS client's
 * `hlsManifestUrl`), and serves the TV `/v.ts?start=<seconds>`, the chosen H.264 variant and
 * audio rendition remuxed into one MPEG-TS stream, and `/s.srt?start=<seconds>`, the subtitles
 * moved back by the start of the first segment sent.
 *
 * Line by line port of `PerleCast/StreamServer.cpp` of the Lumia app (dumbphone), whose cast to
 * a Samsung TV works in every case tried (seeks in a row, resumes, any point of the video). The
 * rules come from long testing with the TV (tools/tvlab) and are kept as they are:
 * - a new server for every new address: the TV never sees the old segment URLs again;
 * - every `/v.ts` connection runs until the TV closes it (it opens two at once and keeps either);
 * - downloads are shared by the connections (each segment fetched once, a few kept in memory);
 * - [close] shuts every connection of the TV: a silent `/v.ts` left open wedges it (701).
 *
 * @param bindAddress the Wi-Fi address of the phone
 * @param baseClient the HTTP client the downloads are made with
 */
class HlsStreamServer(private val bindAddress: InetAddress, baseClient: OkHttpClient) {
    class AudioOption(val name: String, val language: String, val url: String, val isDefault: Boolean, val group: String = "")

    private class Segment(val url: String, val ticks: Long, val start: Long)

    /** one video segment and the audio segments that cover the same time */
    private class SegmentData(val video: ByteArray, val audio: List<ByteArray>)

    private data class SegmentKey(val index: Int, val audioFirst: Int, val audioEnd: Int)

    private class Variant(
        val url: String,
        val group: String,
        val videoCodec: String,
        val audioCodec: String,
        val height: Int,
        val bandwidth: Long
    )

    /** highest resolution to stream (set before [start]) */
    @Volatile
    var maxHeight = 1080

    /** segments kept in memory (fewer while the app is in the background) */
    @Volatile
    var cacheLimit = 8

    /** the audio renditions of the video */
    var audioOptions: List<AudioOption> = emptyList()
        private set

    /** the index in [audioOptions] of the one being streamed */
    var audioIndex = 0
        private set

    /** the resolutions available, highest first */
    var heights: List<Int> = emptyList()
        private set

    /** the resolution streamed */
    var height = 0
        private set

    private val streams = AtomicInteger()

    /** `/v.ts` connections being served */
    val streamCount: Int
        get() = streams.get()

    val durationSeconds: Long
        get() = totalTicks / TICKS_PER_SECOND

    private val client = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val timeoutClients = ConcurrentHashMap<Int, OkHttpClient>()

    // downloads shared by the TV's connections (it opens /v.ts with HEAD, GET, drops it and asks
    // again): each segment is fetched once, the few around the playing point stay in memory
    private val cacheLock = Any()
    private val cache = LinkedHashMap<SegmentKey, CompletableFuture<SegmentData>>()

    // the TV's open connections: close() shuts them. A /v.ts left open and silent (download
    // stuck, or a closed server whose stream had not noticed yet) makes the TV refuse new
    // addresses with 701 for good (tvlab F1/F2)
    private val socketsLock = Any()
    private val sockets = ArrayList<Socket>()

    private var listener: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "CastHls").apply { isDaemon = true }
    }
    private var masterUrl = ""
    private var audioLanguage = ""
    private var subtitleSrt: String? = null
    private var localUrl = ""
    private var video: List<Segment> = emptyList()
    private var audio: List<Segment> = emptyList()
    private var totalTicks = 0L
    private val generation = AtomicLong()

    @Volatile
    private var closed = true

    /**
     * Reads the master and media playlists (blocking: call it on a worker thread) and starts
     * listening.
     *
     * @param subtitleSrt the subtitles in SubRip format, null for none
     * @return the address of the server, e.g. `http://192.168.1.5:31234`
     */
    @Throws(IOException::class)
    fun start(masterUrl: String, audioLanguage: String?, subtitleSrt: String?): String {
        close()
        this.masterUrl = masterUrl
        this.audioLanguage = audioLanguage.orEmpty()
        this.subtitleSrt = subtitleSrt

        prepare()

        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(bindAddress, 20000 + Random.nextInt(30000)))
        } catch (e: IOException) {
            socket.bind(InetSocketAddress(bindAddress, 0))
        }
        listener = socket
        val host = bindAddress.hostAddress.orEmpty().let { if (it.contains(':')) "[$it]" else it }
        localUrl = "http://$host:${socket.localPort}"
        closed = false
        pool.execute { acceptLoop(socket) }
        return localUrl
    }

    fun close() {
        closed = true
        synchronized(socketsLock) {
            if (sockets.isNotEmpty()) CastLog.d("closing ${sockets.size} tv connection(s)")
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        synchronized(cacheLock) { cache.clear() }
        listener?.let { runCatching { it.close() } }
        listener = null
    }

    /** Also stops the threads: the server cannot be started again. */
    fun shutdown() {
        close()
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------------------------------
    // HTTP downloads
    // ---------------------------------------------------------------------------------------

    private fun clientFor(seconds: Int): OkHttpClient = timeoutClients.getOrPut(seconds) {
        client.newBuilder()
            .connectTimeout(seconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(seconds.toLong(), TimeUnit.SECONDS)
            // the Lumia app waits that long for the answer, then that long for the body
            .callTimeout(2L * seconds, TimeUnit.SECONDS)
            .build()
    }

    @Throws(IOException::class)
    private fun download(url: String, seconds: Int = 30): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        clientFor(seconds).newCall(request).execute().use { response ->
            if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
            return response.body.bytes()
        }
    }

    private fun downloadText(url: String): String = String(download(url), Charsets.UTF_8)

    @Throws(IOException::class)
    private fun downloadRetry(url: String): ByteArray {
        var attempt = 0
        while (true) {
            // a stuck request blocked the TV for the whole 30 s: short first tries, a long last one
            try {
                return download(url, RETRY_TIMEOUTS[attempt])
            } catch (e: IOException) {
                CastLog.w("download attempt ${attempt + 1}: ${e.message}")
                if (attempt >= RETRY_TIMEOUTS.lastIndex) throw e
            }
            try {
                Thread.sleep(1000L * (attempt + 1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Stream closed", e)
            }
            attempt++
        }
    }

    // ---------------------------------------------------------------------------------------
    // Playlists
    // ---------------------------------------------------------------------------------------

    private fun mediaSegments(playlistUrl: String): List<Pair<String, Long>> {
        val result = ArrayList<Pair<String, Long>>()
        val lines = lines(downloadText(playlistUrl))
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXTINF:")) continue
            val value = lines[i].substring(8).substringBefore(',')
            val ticks = ((value.trim().toDoubleOrNull() ?: 0.0) * TICKS_PER_SECOND).toLong()
            for (j in i + 1 until lines.size) {
                if (lines[j].isNotEmpty() && lines[j][0] != '#') {
                    result.add(resolve(playlistUrl, lines[j]) to ticks)
                    break
                }
            }
        }
        return result
    }

    private fun prepare() {
        val master = downloadText(masterUrl)
        val variants = ArrayList<Variant>()
        val audioLines = ArrayList<String>()
        parseMaster(master, masterUrl, variants, audioLines)
        if (variants.isEmpty()) throw IOException("No H.264 variant")

        // the TV decodes, the phone only remuxes: any H.264 resolution up to 1080p can be sent
        heights = variants.map { it.height }.filter { it <= 1080 }.distinct().sortedDescending()

        var playable = variants.filter { it.height <= maxHeight }
        if (playable.isEmpty() && heights.isNotEmpty()) playable = variants.filter { it.height == heights.last() }
        if (playable.isEmpty()) throw IOException("No H.264 variant up to 1080p")
        val variant = playable.sortedWith(
            compareByDescending<Variant> { it.height }.thenBy { it.bandwidth }
        ).first()
        height = variant.height
        audioOptions = audioOptionsFor(audioLines, variants, variant, masterUrl)
        audioIndex = pickAudio(audioOptions, audioLanguage)
        val audioUrl = audioOptions.getOrNull(audioIndex)?.url.orEmpty()

        val videoSegments = ArrayList<Segment>()
        totalTicks = 0
        for ((url, ticks) in mediaSegments(variant.url)) {
            videoSegments.add(Segment(url, ticks, totalTicks))
            totalTicks += ticks
        }
        if (videoSegments.isEmpty()) throw IOException("Empty video playlist")
        video = videoSegments

        val audioSegments = ArrayList<Segment>()
        if (audioUrl.isNotEmpty()) {
            var start = 0L
            for ((url, ticks) in mediaSegments(audioUrl)) {
                audioSegments.add(Segment(url, ticks, start))
                start += ticks
            }
        }
        audio = audioSegments
        CastLog.d(
            "hls: ${height}p (${variant.bandwidth / 1000} kb/s), ${video.size} video and ${audio.size} audio " +
                "segments, ${durationSeconds}s, audio ${audioOptions.getOrNull(audioIndex)?.language ?: "in the video"}"
        )
    }

    // ---------------------------------------------------------------------------------------
    // Connections of the TV
    // ---------------------------------------------------------------------------------------

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            synchronized(socketsLock) {
                if (closed) {
                    runCatching { socket.close() }
                    return@synchronized null
                }
                sockets.add(socket)
                socket
            } ?: continue
            try {
                pool.execute {
                    try {
                        serve(socket)
                    } catch (_: Exception) {
                        // the TV closed it, or the server was closed
                    }
                    synchronized(socketsLock) { sockets.remove(socket) }
                    runCatching { socket.close() }
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val requestBytes = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (requestBytes.size() < 8192) {
            val text = requestBytes.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) break
            val loaded = input.read(chunk)
            if (loaded <= 0) break
            requestBytes.write(chunk, 0, loaded)
        }
        val request = requestBytes.toString("ISO-8859-1")

        val firstLine = request.substringBefore("\r\n")
        val parts = firstLine.split(' ')
        val method = if (parts.size >= 2) parts[0] else "GET"
        val path = if (parts.size >= 2) parts[1] else "/"
        val headOnly = method == "HEAD"
        run {
            var at = request.indexOf("\nRange:")
            if (at < 0) at = request.indexOf("\nrange:")
            val range = if (at >= 0) {
                val end = request.indexOf('\r', at).let { if (it < 0) request.length else it }
                " " + request.substring(at + 1, end).trim()
            } else {
                ""
            }
            CastLog.d("tv asks $method $path$range")
        }

        val output = socket.getOutputStream()
        if (path.startsWith("/v.ts")) {
            // Live stream: no length, no byte ranges; the TV seeks by asking for a new start position.
            val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t" +
                "\r\ntransferMode.dlna.org: Streaming" +
                "\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000" +
                "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            if (!headOnly) {
                val start = startOf(path)
                // every request runs until the TV closes it: the TV opens two at once and keeps
                // either one (ending the older left it with nothing, "Transition not available");
                // the segment cache makes the copy free
                val number = generation.incrementAndGet()
                streams.incrementAndGet()
                try {
                    streamTransport(output, start, number)
                } catch (e: Exception) {
                    CastLog.d("stream from ${start.toInt()}s broken: ${e.message}")
                    throw e
                } finally {
                    streams.decrementAndGet()
                }
            }
        } else {
            var contentType = "application/x-srt"
            var body: ByteArray
            var code = 200
            try {
                if (path.startsWith("/s.srt")) {
                    body = buildSubtitle(startOf(path)).toByteArray(Charsets.UTF_8)
                } else {
                    code = 404
                    contentType = "text/plain; charset=utf-8"
                    body = "Not found".toByteArray(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                code = 502
                contentType = "text/plain; charset=utf-8"
                body = "Subtitle unavailable".toByteArray(Charsets.UTF_8)
            }
            val reason = when (code) {
                200 -> " OK"
                404 -> " Not Found"
                else -> " Bad Gateway"
            }
            val header = "HTTP/1.1 $code$reason" +
                "\r\nContent-Type: $contentType" +
                "\r\nContent-Length: ${body.size}" +
                "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            if (!headOnly) output.write(body)
        }
        output.flush()
    }

    private fun fetchSegment(index: Int, audioFirst: Int, audioEnd: Int): CompletableFuture<SegmentData> {
        val key = SegmentKey(index, audioFirst, audioEnd)
        synchronized(cacheLock) {
            cache[key]?.let { return it }

            // keep the recent ones: drop finished segments well behind this one (a seek back downloads again)
            val iterator = cache.entries.iterator()
            while (iterator.hasNext() && cache.size > cacheLimit) {
                val entry = iterator.next()
                if (entry.value.isDone && entry.key.index + 2 < index) iterator.remove()
            }

            val videoUrl = video[index].url
            val audioUrls = (audioFirst until minOf(audioEnd, audio.size)).map { audio[it].url }
            val future = CompletableFuture<SegmentData>()
            cache[key] = future
            try {
                pool.execute {
                    try {
                        val videoBytes = downloadRetry(videoUrl)
                        val audioBytes = audioUrls.map { downloadRetry(it) }
                        future.complete(SegmentData(videoBytes, audioBytes))
                    } catch (e: Exception) {
                        CastLog.w("segment ${index + 1} download failed: ${e.message}")
                        // not kept: the next connection tries again
                        synchronized(cacheLock) { cache.remove(key) }
                        future.completeExceptionally(e)
                    }
                }
            } catch (e: Exception) {
                cache.remove(key)
                future.completeExceptionally(e)
            }
            return future
        }
    }

    /** The TV's clock starts at 0 with the first segment of /v.ts?start=: the cues move back by that segment's start. */
    private fun buildSubtitle(startSeconds: Double): String {
        val srt = subtitleSrt ?: return ""
        val startTicks = (maxOf(0.0, startSeconds) * TICKS_PER_SECOND).toLong()
        var startIndex = 0
        while (startIndex + 1 < video.size && video[startIndex + 1].start <= startTicks) ++startIndex
        val offsetTicks = if (video.isEmpty()) 0 else video[startIndex].start
        return if (offsetTicks > 0) SrtConverter.shift(srt, offsetTicks / 10000) else srt
    }

    private fun streamTransport(output: OutputStream, startSeconds: Double, number: Long) {
        val startTicks = (maxOf(0.0, startSeconds) * TICKS_PER_SECOND).toLong()
        var startIndex = 0
        while (startIndex + 1 < video.size && video[startIndex + 1].start <= startTicks) ++startIndex

        // audio segment ranges for each video segment, by playlist time
        val audioRanges = arrayOfNulls<Pair<Int, Int>>(video.size)
        var nextAudio = 0
        while (nextAudio + 1 < audio.size && audio[nextAudio + 1].start <= video[startIndex].start) ++nextAudio
        for (i in startIndex until video.size) {
            val end = if (i + 1 < video.size) video[i + 1].start else Long.MAX_VALUE
            val first = nextAudio
            while (nextAudio < audio.size && audio[nextAudio].start < end) ++nextAudio
            audioRanges[i] = first to nextAudio
        }

        fun fetch(index: Int): CompletableFuture<SegmentData> {
            val range = audioRanges[index]!!
            return fetchSegment(index, range.first, range.second)
        }

        val buffer = ByteArrayOutputStream(1 shl 20)
        var muxer: HlsTsMuxer? = null
        var origin = 0L
        val separateAudio = audio.isNotEmpty()
        var anyAudio = false

        val pending = ArrayDeque<CompletableFuture<SegmentData>>()
        var nextToSchedule = startIndex
        var ahead = 0
        while (ahead < PREFETCH && nextToSchedule < video.size) {
            pending.addLast(fetch(nextToSchedule))
            ahead++
            nextToSchedule++
        }

        var i = startIndex
        while (i < video.size && !closed) {
            val current = pending.pollFirst() ?: break
            val fetchStart = System.currentTimeMillis()
            val data = try {
                current.get()
            } catch (e: ExecutionException) {
                CastLog.w("segment ${i + 1} failed: ${e.cause?.message}")
                throw IOException(e.cause?.message ?: "segment failed", e.cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Stream closed", e)
            }
            val waited = System.currentTimeMillis() - fetchStart
            if (nextToSchedule < video.size) {
                pending.addLast(fetch(nextToSchedule))
                nextToSchedule++
            }

            val parsed = TsDemux.parseTsSegment(data.video)
            if (parsed == null) {
                i++
                continue
            }
            val mux = muxer ?: run {
                // the first frame we send starts the TV's clock at 0
                origin = parsed.baseRaw + (parsed.video.firstOrNull()?.dts90k ?: 0)
                HlsTsMuxer(buffer, origin).also { muxer = it }
            }

            for (sample in parsed.video) {
                mux.pushVideo(sample.annexB, parsed.baseRaw + sample.pts90k, parsed.baseRaw + sample.dts90k, sample.key)
            }

            val audioParts = ArrayList<TsDemux.TsSegmentData>()
            if (separateAudio) {
                for (part in data.audio) {
                    TsDemux.parseAacSegment(part)?.let { audioParts.add(it) }
                }
            } else if (parsed.hasAudio) {
                audioParts.add(parsed)
            }

            for (part in audioParts) {
                for (frame in part.audio) {
                    val pts = part.baseRaw + frame.pts90k
                    if (pts < origin) continue
                    mux.pushAudio(HlsTsMuxer.makeAdts(frame.aac, part.audioSampleRate, part.audioChannels), pts)
                    anyAudio = true
                }
            }

            // without audio the muxer would hold every video frame waiting to interleave
            if (!anyAudio) mux.finish()
            val bytes = buffer.size()
            val writeStart = System.currentTimeMillis()
            buffer.writeTo(output)
            output.flush()
            buffer.reset()
            CastLog.d(
                "segment ${i + 1}/${video.size}: ${bytes / 1024} KB, waited download $waited ms, " +
                    "TV took it in ${System.currentTimeMillis() - writeStart} ms (stream $number)"
            )
            i++
        }
        CastLog.d(if (closed) "stream closed (new video)" else "stream finished")

        muxer?.let {
            it.finish()
            buffer.writeTo(output)
            output.flush()
            buffer.reset()
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val PREFETCH = 3
        private val RETRY_TIMEOUTS = intArrayOf(8, 15, 30)
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** @return the start asked with `?start=<seconds>`, 0 if absent */
        fun startOf(path: String): Double {
            val at = path.indexOf("start=")
            if (at < 0) return 0.0
            return path.substring(at + 6).takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        }

        private fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        private fun resolve(base: String, path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            URI(base).resolve(path).toString()
        }

        private fun getAttribute(line: String, key: String): String {
            val token = "$key="
            var p = line.indexOf(token)
            if (p < 0) return ""
            p += token.length
            if (p < line.length && line[p] == '"') {
                val end = line.indexOf('"', p + 1)
                if (end < 0) return ""
                return line.substring(p + 1, end)
            }
            val end = line.indexOf(',', p)
            return if (end < 0) line.substring(p) else line.substring(p, end)
        }

        private fun codecFamily(value: String): String = value.substringBefore('.')

        private fun parseMaster(master: String, base: String, variants: MutableList<Variant>, audioLines: MutableList<String>) {
            val lines = lines(master)
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-MEDIA:")) audioLines.add(lines[i])
                if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue

                val attributes = lines[i].substring(18)
                val codecs = getAttribute(attributes, "CODECS")
                var codecAt = codecs.indexOf("avc1")
                if (codecAt < 0) continue
                var codecEnd = codecs.indexOf(',', codecAt)
                val videoCodec = (if (codecEnd < 0) codecs.substring(codecAt) else codecs.substring(codecAt, codecEnd)).trim()
                var audioCodec = ""
                codecAt = codecs.indexOf("mp4a")
                if (codecAt >= 0) {
                    codecEnd = codecs.indexOf(',', codecAt)
                    audioCodec = (if (codecEnd < 0) codecs.substring(codecAt) else codecs.substring(codecAt, codecEnd)).trim()
                }
                val resolution = getAttribute(attributes, "RESOLUTION")
                val height = resolution.substringAfter('x', "").toIntOrNull() ?: 0
                val bandwidth = getAttribute(attributes, "BANDWIDTH").toLongOrNull() ?: 0
                var url = ""
                for (j in i + 1 until lines.size) {
                    if (lines[j].isEmpty() || lines[j][0] == '#') continue
                    url = resolve(base, lines[j])
                    break
                }
                if (height > 0 && url.isNotEmpty()) {
                    variants.add(Variant(url, getAttribute(attributes, "AUDIO"), videoCodec, audioCodec, height, bandwidth))
                }
            }
        }

        private fun primaryLanguage(language: String): String = language.substringBefore('-').lowercase(Locale.US)

        /**
         * The audio renditions that go with the chosen variant: YouTube can publish one
         * otherwise identical video variant for each dubbed audio group.
         */
        private fun audioOptionsFor(
            audioLines: List<String>,
            variants: List<Variant>,
            chosen: Variant,
            masterUrl: String
        ): List<AudioOption> {
            val groups = ArrayList<String>()
            if (chosen.group.isNotEmpty()) groups.add(chosen.group)
            for (variant in variants) {
                if (variant.group.isEmpty() || variant.audioCodec.isEmpty()) continue
                if (chosen.audioCodec.isNotEmpty() && codecFamily(variant.audioCodec) != codecFamily(chosen.audioCodec)) continue
                if (codecFamily(variant.videoCodec) != codecFamily(chosen.videoCodec)) continue
                if (variant.group !in groups) groups.add(variant.group)
            }

            val result = ArrayList<AudioOption>()
            for (audioLine in audioLines) {
                val line = audioLine.substring(13)
                if (getAttribute(line, "TYPE") != "AUDIO") continue
                val group = getAttribute(line, "GROUP-ID")
                val uri = getAttribute(line, "URI")
                if (uri.isEmpty() || group !in groups) continue
                val url = resolve(masterUrl, uri)
                if (result.any { it.url == url }) continue
                result.add(
                    AudioOption(
                        getAttribute(line, "NAME"),
                        getAttribute(line, "LANGUAGE"),
                        url,
                        getAttribute(line, "DEFAULT") == "YES",
                        group
                    )
                )
            }
            // the renditions of the chosen variant's own group first (the same audio quality as
            // the video), the other groups only for the languages it lacks: one entry per track
            val own = result.filter { it.group == chosen.group }
            val others = result.filter { it.group != chosen.group }
                .filter { other -> own.none { it.language == other.language && it.name == other.name } }
                .distinctBy { it.language to it.name }
            return own + others
        }

        private fun pickAudio(options: List<AudioOption>, preferred: String): Int {
            if (preferred.isNotEmpty()) {
                options.indexOfFirst { it.language.equals(preferred, ignoreCase = true) }
                    .takeIf { it >= 0 }?.let { return it }
                options.indexOfFirst { it.language.isNotEmpty() && primaryLanguage(it.language) == primaryLanguage(preferred) }
                    .takeIf { it >= 0 }?.let { return it }
            }
            options.indexOfFirst { it.isDefault }.takeIf { it >= 0 }?.let { return it }
            // dubbed videos often mark no rendition as default: the original track, not the first dub
            return options.indexOfFirst { it.name.contains("original", ignoreCase = true) }.coerceAtLeast(0)
        }
    }
}
