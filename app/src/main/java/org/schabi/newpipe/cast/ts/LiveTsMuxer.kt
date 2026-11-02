/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.ts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.roundToLong
import org.schabi.newpipe.cast.dlna.CastDash
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.DashTrack
import org.schabi.newpipe.cast.proxy.RangedInputStream
import org.schabi.newpipe.cast.proxy.Upstream

/**
 * Turns a video-only H.264 MP4 and an AAC M4A stream (DASH, one file each) into a single MPEG-TS
 * stream while they are being downloaded, so that TVs which need one file with audio and video
 * can play them without waiting for a download.
 *
 * The streams are read from the segment containing the requested start time, which is how
 * seeking works: the TV gets a new stream starting there. The segments are downloaded three at a
 * time and kept in a small cache, shared by every connection of the TV: the Lumia app's
 * `StreamServer` does the same, and the TV sometimes opens two connections at once and keeps
 * either one.
 */
class LiveTsMuxer(private val upstream: Upstream, private val tracks: CastDash) {
    private class Input(val track: Mp4Track, val segments: List<Mp4Segment>, val url: String)

    private class SegmentKey(val videoIndex: Int, val audioFirst: Int, val audioEnd: Int)

    private class AudioData(val bytes: ByteArray, val startSeconds: Double)

    private class SegmentData(val video: ByteArray, val audio: List<AudioData>)

    private var video: Input? = null
    private var audio: Input? = null

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "CastMux").apply { isDaemon = true }
    }
    private val cacheLock = Any()
    private val cache = LinkedHashMap<SegmentKey, CompletableFuture<SegmentData>>()

    @Volatile
    private var cacheLimit = DEFAULT_CACHE_LIMIT

    @Volatile
    private var closed = false

    /** @return the length of the video, in seconds, like the Lumia app's StreamServer */
    val durationSeconds: Long
        get() = prepare().first.segments.sumOf { it.durationSeconds }.toLong()

    /** Stops every download and forgets the cache, like the Lumia app's `StreamServer::Close`. */
    fun close() {
        closed = true
        synchronized(cacheLock) {
            cache.values.forEach { it.cancel(true) }
            cache.clear()
        }
        executor.shutdownNow()
    }

    /** @param count how many segments to keep, fewer while the app is in the background */
    fun setCacheLimit(count: Int) {
        cacheLimit = count.coerceAtLeast(1)
    }

    /**
     * @return the start of the segment the stream beginning at [startSeconds] starts with: the
     * TV's clock starts there, so the subtitles move back by that much
     */
    fun segmentStartSeconds(startSeconds: Double): Double {
        val input = prepare().first
        return input.segments[startIndex(input, startSeconds)].startSeconds
    }

    /** Downloads and parses the initialization data and the segment index of both streams. */
    @Synchronized
    private fun prepare(): Pair<Input, Input> {
        val v = video ?: load(tracks.video).also { video = it }
        val a = audio ?: load(tracks.audio).also { audio = it }
        if (v.track.codec !is CodecConfig.Avc) throw IOException("The video stream is not H.264")
        if (a.track.codec !is CodecConfig.Aac) throw IOException("The audio stream is not AAC")
        return v to a
    }

    private fun load(dashTrack: DashTrack): Input {
        val init = RangedInputStream.readRange(upstream, dashTrack.url, dashTrack.initStart, dashTrack.initEnd)
        val index = RangedInputStream.readRange(upstream, dashTrack.url, dashTrack.indexStart, dashTrack.indexEnd)
        val track = FragmentedMp4.parseInit(init)
        val segments = FragmentedMp4.parseSidx(index, dashTrack.indexStart)
        if (segments.isEmpty()) throw IOException("No segments in ${dashTrack.mimeType} stream")
        return Input(track, segments, dashTrack.url)
    }

    /**
     * Writes the stream from [startSeconds] until the end, or until writing fails because the TV
     * closed the connection.
     */
    fun write(output: OutputStream, startSeconds: Double) {
        if (closed) {
            return
        }
        val (videoInput, audioInput) = prepare()
        val startIndex = startIndex(videoInput, startSeconds)
        val audioRanges = audioRanges(startIndex)
        val base = videoInput.segments[startIndex].startSeconds
        CastLog.d(
            "TS mux from ${"%.1f".format(base)} s (asked ${"%.1f".format(startSeconds)} s): " +
                "video segment ${startIndex + 1}/${videoInput.segments.size}, " +
                "${"%.1f".format(videoInput.segments[startIndex].durationSeconds)} s, first segments: " +
                videoInput.segments.drop(startIndex).take(LOGGED_SEGMENTS)
                    .joinToString { "%.1f".format(it.durationSeconds) } + " s"
        )

        val counting = CountingOutputStream(output)
        val writer = TsWriter(counting)
        fun clock(seconds: Double) = ((seconds - base) * TsWriter.CLOCK).roundToLong() + START_DELAY

        // like ffmpeg: service description and program tables first, then again every 500 and
        // 100 ms, so that a player can start anywhere and finds them quickly
        writer.writeSdt()
        writer.writeTables()

        var lastSdt = 0L
        var lastTables = 0L
        var videoShift: Double? = null
        val startedAt = System.currentTimeMillis()
        var nextReport = PROGRESS_SECONDS
        var videoFrames = 0L
        var audioFrames = 0L
        var skippedAudio = 0L
        var reorderedFrames = 0L

        val pending = ArrayDeque<CompletableFuture<SegmentData>>()
        var nextToSchedule = startIndex
        while (pending.size < PREFETCH && nextToSchedule < videoInput.segments.size) {
            val range = audioRanges[nextToSchedule - startIndex]
            pending.addLast(fetchSegment(nextToSchedule, range.first, range.second))
            nextToSchedule++
        }

        for (i in startIndex until videoInput.segments.size) {
            if (closed) {
                break
            }
            val current = pending.pollFirst() ?: break
            val fetchStart = System.currentTimeMillis()
            val data = try {
                current.get()
            } catch (e: CancellationException) {
                break
            } catch (e: ExecutionException) {
                val cause = e.cause
                CastLog.e("Segment ${i + 1} failed: ${cause?.message}")
                throw if (cause is IOException) {
                    cause
                } else {
                    IOException(cause?.message ?: "Segment download failed", cause)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Stream closed", e)
            }
            val waited = System.currentTimeMillis() - fetchStart
            if (nextToSchedule < videoInput.segments.size) {
                val range = audioRanges[nextToSchedule - startIndex]
                pending.addLast(fetchSegment(nextToSchedule, range.first, range.second))
                nextToSchedule++
            }

            val videoSamples = samples(data.video, videoInput, videoInput.segments[i].startSeconds)
            val audioSamples = ArrayList<Mp4Sample>()
            for (part in data.audio) {
                audioSamples.addAll(samples(part.bytes, audioInput, part.startSeconds))
            }

            // MP4 files hide the B-frame delay of the first frame with an edit list: without it,
            // the video would be late compared to the audio
            val shift = videoShift ?: videoSamples.firstOrNull()?.let {
                it.compositionOffset.toDouble() / videoInput.track.timescale
            } ?: 0.0
            videoShift = shift
            fun videoSeconds(sample: Mp4Sample) = sample.decodeTime.toDouble() / videoInput.track.timescale - shift
            fun audioSeconds(sample: Mp4Sample) = sample.decodeTime.toDouble() / audioInput.track.timescale

            val avc = videoInput.track.codec as CodecConfig.Avc
            val aac = audioInput.track.codec as CodecConfig.Aac
            if (i == startIndex) {
                CastLog.d(
                    "Mux start: base ${"%.3f".format(base)} s, first video sample at " +
                        (
                            videoSamples.firstOrNull()?.let {
                                "${"%.3f".format(videoSeconds(it))} s (+${"%.3f".format(shift)} s)"
                            } ?: "none"
                            ) +
                        ", first audio sample at " +
                        (audioSamples.firstOrNull()?.let { "%.3f".format(audioSeconds(it)) + " s" } ?: "none") +
                        ", ${avc.sps.size} SPS, ${avc.pps.size} PPS, AAC type ${aac.objectType} " +
                        "index ${aac.frequencyIndex} channels ${aac.channels}"
                )
            }

            var videoIndex = 0
            var audioIndex = 0
            while (videoIndex < videoSamples.size || audioIndex < audioSamples.size) {
                val videoSample = videoSamples.getOrNull(videoIndex)
                val audioSample = audioSamples.getOrNull(audioIndex)
                val videoTime = videoSample?.let { videoSeconds(it) } ?: Double.MAX_VALUE
                val audioTime = audioSample?.let { audioSeconds(it) } ?: Double.MAX_VALUE

                if (videoTime <= audioTime) {
                    val sample = videoSample!!
                    videoIndex++
                    val dts = clock(videoTime)
                    val pts = clock(
                        sample.decodeTime.toDouble() / videoInput.track.timescale +
                            sample.compositionOffset.toDouble() / videoInput.track.timescale - shift
                    )
                    val (annexB, isKeyframe) = toAnnexB(sample.data, avc)
                    val pcr = (dts - PCR_DELAY).coerceAtLeast(0)
                    val inputTypes = nalTypes(sample.data, avc)
                    if (isReordered(inputTypes)) {
                        reorderedFrames++
                        if (reorderedFrames <= LOGGED_REORDERS) {
                            CastLog.d(
                                "Mux frame $videoFrames at ${"%.2f".format(videoTime - base)} s: " +
                                    "NAL types $inputTypes, sent ${sentNalTypes(annexB)}"
                            )
                        }
                    }
                    if (videoFrames == 0L) {
                        CastLog.d(
                            "Mux first video frame: keyframe $isKeyframe, NAL types $inputTypes, " +
                                "sent ${sentNalTypes(annexB)}, PTS $pts, DTS $dts, PCR $pcr, " +
                                "$skippedAudio audio frames skipped"
                        )
                        lastSdt = dts
                        lastTables = dts
                    } else {
                        if (dts - lastSdt >= SDT_INTERVAL) {
                            writer.writeSdt()
                            lastSdt = dts
                        }
                        if (isKeyframe || dts - lastTables >= TABLES_INTERVAL) {
                            writer.writeTables()
                            lastTables = dts
                        }
                    }
                    writer.writeVideo(annexB, pts, dts, isKeyframe, pcr)
                    writer.flush()
                    videoFrames++
                    val sent = videoTime - base
                    if (sent >= nextReport) {
                        nextReport += PROGRESS_SECONDS
                        CastLog.d(
                            "Mux progress: ${sent.toLong()} s of video, $videoFrames video and " +
                                "$audioFrames audio frames, ${counting.count / 1024} KB sent in " +
                                "${(System.currentTimeMillis() - startedAt) / 1000} s" +
                                if (reorderedFrames > 0) ", $reorderedFrames frames reordered" else ""
                        )
                    }
                } else {
                    val sample = audioSample!!
                    audioIndex++
                    // audio before the first video frame would have a negative time
                    if (audioTime >= base) {
                        if (audioFrames == 0L) {
                            CastLog.d(
                                "Mux first audio frame: PTS ${clock(audioTime)}, " +
                                    "after $videoFrames video frames"
                            )
                        }
                        writer.writeAudio(adts(sample.data, aac), clock(audioTime))
                        audioFrames++
                    } else {
                        skippedAudio++
                    }
                }
            }

            writer.flush()
            val bytes = counting.count - counting.segmentBytes
            CastLog.d(
                "segment ${i + 1}/${videoInput.segments.size}: ${bytes / 1024} KB, waited download " +
                    "$waited ms, $videoFrames video and $audioFrames audio frames so far"
            )
            counting.segmentBytes = counting.count
        }
        writer.flush()
        CastLog.d(
            if (closed) {
                "stream closed (new video)"
            } else {
                "stream finished: $videoFrames video frames, $audioFrames audio frames"
            }
        )
    }

    /** @return the index of the segment containing [startSeconds], like the Lumia app */
    private fun startIndex(input: Input, startSeconds: Double): Int {
        var index = 0
        while (index + 1 < input.segments.size &&
            input.segments[index + 1].startSeconds <= startSeconds
        ) {
            index++
        }
        return index
    }

    /**
     * The audio segments playing during each video segment, by playlist time, like the Lumia
     * app: an audio segment belongs to the video segment in which it starts.
     */
    private fun audioRanges(startIndex: Int): List<Pair<Int, Int>> {
        val videoSegments = video!!.segments
        val audioSegments = audio!!.segments
        val ranges = ArrayList<Pair<Int, Int>>(videoSegments.size - startIndex)
        var nextAudio = 0
        while (nextAudio + 1 < audioSegments.size &&
            audioSegments[nextAudio + 1].startSeconds <= videoSegments[startIndex].startSeconds
        ) {
            nextAudio++
        }
        for (i in startIndex until videoSegments.size) {
            val end = if (i + 1 < videoSegments.size) {
                videoSegments[i + 1].startSeconds
            } else {
                Double.MAX_VALUE
            }
            val first = nextAudio
            while (nextAudio < audioSegments.size && audioSegments[nextAudio].startSeconds < end) {
                nextAudio++
            }
            ranges.add(first to nextAudio)
        }
        return ranges
    }

    /**
     * Downloads a video segment and its audio segments, sharing a download already in progress
     * with the other connections of the TV.
     */
    private fun fetchSegment(index: Int, audioFirst: Int, audioEnd: Int): CompletableFuture<SegmentData> {
        val key = SegmentKey(index, audioFirst, audioEnd)
        synchronized(cacheLock) {
            cache[key]?.let { return it }

            // keep the recent ones: drop finished segments well behind this one (a seek back
            // downloads again)
            val iterator = cache.entries.iterator()
            while (cache.size > cacheLimit && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.isDone && entry.key.videoIndex + 2 < index) {
                    iterator.remove()
                }
            }

            val future = CompletableFuture<SegmentData>()
            cache[key] = future
            executor.execute {
                try {
                    future.complete(download(key))
                } catch (t: Throwable) {
                    // not kept: the next connection tries again
                    synchronized(cacheLock) { cache.remove(key) }
                    future.completeExceptionally(t)
                }
            }
            return future
        }
    }

    private fun download(key: SegmentKey): SegmentData {
        val videoInput = video!!
        val audioInput = audio!!
        val videoBytes = downloadSegment(videoInput.url, videoInput.segments[key.videoIndex])
        val audioBytes = ArrayList<AudioData>(key.audioEnd - key.audioFirst)
        for (i in key.audioFirst until key.audioEnd.coerceAtMost(audioInput.segments.size)) {
            val segment = audioInput.segments[i]
            audioBytes.add(AudioData(downloadSegment(audioInput.url, segment), segment.startSeconds))
        }
        return SegmentData(videoBytes, audioBytes)
    }

    /** Like the Lumia app: short first tries, so that a stuck request does not block the TV. */
    private fun downloadSegment(url: String, segment: Mp4Segment): ByteArray {
        if (segment.size <= 0) {
            throw IOException("Invalid segment size ${segment.size}")
        }
        val start = segment.offset
        val end = start + segment.size - 1
        for (attempt in RETRY_TIMEOUTS.indices) {
            if (closed) {
                throw IOException("Stream closed")
            }
            try {
                return RangedInputStream.readRange(upstream, url, start, end, RETRY_TIMEOUTS[attempt])
            } catch (e: Exception) {
                CastLog.w("Segment download attempt ${attempt + 1}: ${e.message}")
                if (attempt == RETRY_TIMEOUTS.lastIndex) {
                    throw e
                }
            }
            Thread.sleep(1000L * (attempt + 1))
        }
        throw IOException("Could not download the segment")
    }

    /** @return the samples of one MP4 fragment file, read from its own start */
    private fun samples(bytes: ByteArray, input: Input, startSeconds: Double): List<Mp4Sample> {
        val reader = FragmentedMp4.FragmentReader(
            ByteArrayInputStream(bytes),
            input.track,
            (startSeconds * input.track.timescale).roundToLong()
        )
        val samples = ArrayList<Mp4Sample>()
        while (true) {
            val fragment = reader.nextFragment() ?: break
            samples.addAll(fragment)
        }
        return samples
    }

    /** Counts the bytes sent to the TV, for the log. */
    private class CountingOutputStream(private val output: OutputStream) : OutputStream() {
        var count = 0L
            private set
        var segmentBytes = 0L

        override fun write(b: Int) {
            output.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            output.write(b, off, len)
            count += len
        }

        override fun flush() = output.flush()
    }

    companion object {
        /**
         * Like ffmpeg: the first frame is presented 1.4 s after the start of the clock and decoded
         * [PCR_DELAY] after it arrives, so the clock reference starts above 0 and always grows,
         * even when the first decode time is earlier than the first presentation time (B-frames).
         */
        const val START_DELAY = 126_000L

        /** Time between the arrival of a frame (PCR) and its decoding (DTS): 0.7 s like ffmpeg. */
        const val PCR_DELAY = 63_000L

        /** How often the program tables and the service description are repeated, in 90 kHz units. */
        private const val TABLES_INTERVAL = 9_000L
        private const val SDT_INTERVAL = 45_000L

        /** How many segments are downloaded ahead, like the Lumia app. */
        private const val PREFETCH = 3

        /** How many downloaded segments are kept: 8, 4 while the app is in the background. */
        private const val DEFAULT_CACHE_LIMIT = 8

        /** Per-attempt timeouts, like the Lumia app's first, second and last download tries. */
        private val RETRY_TIMEOUTS = intArrayOf(8, 15, 30)

        private const val LOGGED_SEGMENTS = 6
        private const val LOGGED_REORDERS = 3

        /** How often the progress of the stream is logged, in seconds of video. */
        private const val PROGRESS_SECONDS = 10.0

        private const val NAL_SLICE = 1
        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_AUD = 9

        /**
         * Access unit delimiters (one is written already), end of sequence and stream (which
         * would end the video for the decoder) and filler data.
         */
        private val DROPPED_NALS = setOf(NAL_AUD, 10, 11, 12)
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val ACCESS_UNIT_DELIMITER = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        /**
         * Converts an MP4 sample (NAL units prefixed by their length) to Annex B (NAL units
         * prefixed by start codes), adding the parameter sets before keyframes.
         *
         * @return the converted access unit and whether it is a keyframe
         */
        fun toAnnexB(sample: ByteArray, avc: CodecConfig.Avc): Pair<ByteArray, Boolean> {
            val nals = ArrayList<ByteArray>()
            var position = 0
            while (position + avc.nalLengthSize <= sample.size) {
                var length = 0
                for (i in 0 until avc.nalLengthSize) {
                    length = (length shl 8) or (sample[position + i].toInt() and 0xFF)
                }
                position += avc.nalLengthSize
                if (length <= 0 || position + length > sample.size) break
                nals.add(sample.copyOfRange(position, position + length))
                position += length
            }

            val types = nals.map { it[0].toInt() and 0x1F }
            val isKeyframe = NAL_IDR in types
            val out = ByteArrayOutputStream(sample.size + 64)
            out.write(ACCESS_UNIT_DELIMITER)
            if (isKeyframe && NAL_SPS !in types) {
                avc.sps.forEach {
                    out.write(START_CODE)
                    out.write(it)
                }
                if (NAL_PPS !in types) {
                    avc.pps.forEach {
                        out.write(START_CODE)
                        out.write(it)
                    }
                }
            }
            // H.264 wants the SEI and other non-picture units before the slices of the picture:
            // YouTube files sometimes have an SEI after them (e.g. in the first frame), which
            // made TVs stall at the start of the video
            val (slices, others) = nals.indices.partition { types[it] in NAL_SLICE..NAL_IDR }
            for (i in others + slices) {
                if (types[i] !in DROPPED_NALS) {
                    out.write(START_CODE)
                    out.write(nals[i])
                }
            }
            return out.toByteArray() to isKeyframe
        }

        /** @return true if a non-picture unit comes after a slice, which [toAnnexB] fixes */
        fun isReordered(types: List<Int>): Boolean {
            val firstSlice = types.indexOfFirst { it in NAL_SLICE..NAL_IDR }
            return firstSlice >= 0 && types.drop(firstSlice + 1).any { it !in NAL_SLICE..NAL_IDR && it !in DROPPED_NALS }
        }

        /** @return the types of the NAL units of an Annex B access unit (4-byte start codes), for the log */
        fun sentNalTypes(annexB: ByteArray): List<Int> {
            val types = ArrayList<Int>()
            var i = 0
            while (i + 4 < annexB.size) {
                if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) {
                    types.add(annexB[i + 4].toInt() and 0x1F)
                    i += 4
                } else {
                    i++
                }
            }
            return types
        }

        /** @return the types of the NAL units of an MP4 sample, for the log */
        fun nalTypes(sample: ByteArray, avc: CodecConfig.Avc): List<Int> {
            val types = ArrayList<Int>()
            var position = 0
            while (position + avc.nalLengthSize < sample.size) {
                var length = 0
                for (i in 0 until avc.nalLengthSize) {
                    length = (length shl 8) or (sample[position + i].toInt() and 0xFF)
                }
                position += avc.nalLengthSize
                if (length <= 0 || position + length > sample.size) break
                types.add(sample[position].toInt() and 0x1F)
                position += length
            }
            return types
        }

        /** Prefixes a raw AAC frame with its ADTS header. */
        fun adts(frame: ByteArray, aac: CodecConfig.Aac): ByteArray {
            val length = frame.size + 7
            val header = byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(), // MPEG-4, layer 0, no CRC
                ((((aac.objectType - 1) and 0x03) shl 6) or (aac.frequencyIndex shl 2) or (aac.channels shr 2 and 0x01)).toByte(),
                (((aac.channels and 0x03) shl 6) or (length shr 11 and 0x03)).toByte(),
                (length shr 3).toByte(),
                (((length and 0x07) shl 5) or 0x1F).toByte(), // buffer fullness 0x7FF
                0xFC.toByte()
            )
            return header + frame
        }
    }
}
