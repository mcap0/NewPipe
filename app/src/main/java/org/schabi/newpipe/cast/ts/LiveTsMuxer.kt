/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.ts

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
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
 * The streams are read from the fragment containing the requested start time, which is how
 * seeking works: the TV gets a new stream starting there.
 */
class LiveTsMuxer(private val upstream: Upstream, private val tracks: CastDash) {
    private class Input(val track: Mp4Track, val segments: List<Mp4Segment>, val url: String)

    private var video: Input? = null
    private var audio: Input? = null

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
        val (videoInput, audioInput) = prepare()
        val videoSegment = videoInput.segments.lastOrNull { it.startSeconds <= startSeconds }
            ?: videoInput.segments.first()
        val audioSegment = audioInput.segments.lastOrNull { it.startSeconds <= videoSegment.startSeconds }
            ?: audioInput.segments.first()
        val base = videoSegment.startSeconds
        CastLog.d(
            "TS mux from ${"%.1f".format(base)} s (asked ${"%.1f".format(startSeconds)} s): " +
                "video at byte ${videoSegment.offset}, audio at byte ${audioSegment.offset}"
        )

        RangedInputStream(upstream, videoInput.url, videoSegment.offset).use { videoStream ->
            RangedInputStream(upstream, audioInput.url, audioSegment.offset).use { audioStream ->
                val videoCursor = Cursor(FragmentedMp4.FragmentReader(videoStream, videoInput.track), videoInput.track)
                val audioCursor = Cursor(FragmentedMp4.FragmentReader(audioStream, audioInput.track), audioInput.track)
                mux(videoCursor, audioCursor, base, output)
            }
        }
    }

    /** The samples of one stream, read one fragment at a time. */
    private class Cursor(private val reader: FragmentedMp4.FragmentReader, val track: Mp4Track) {
        private var fragment: List<Mp4Sample> = emptyList()
        private var index = 0

        fun peek(): Mp4Sample? {
            while (index >= fragment.size) {
                fragment = reader.nextFragment() ?: return null
                index = 0
            }
            return fragment[index]
        }

        fun next(): Mp4Sample? = peek()?.also { index++ }

        fun seconds(time: Long) = time.toDouble() / track.timescale
    }

    private fun mux(video: Cursor, audio: Cursor, baseSeconds: Double, output: OutputStream) {
        val writer = TsWriter(output)
        val avc = video.track.codec as CodecConfig.Avc
        val aac = audio.track.codec as CodecConfig.Aac
        fun clock(seconds: Double) = ((seconds - baseSeconds) * TsWriter.CLOCK).roundToLong() + START_DELAY

        writer.writeTables()
        // MP4 files hide the B-frame delay of the first frame with an edit list: without it,
        // the video would be late compared to the audio
        val videoShift = video.peek()?.let { video.seconds(it.compositionOffset.toLong()) } ?: 0.0
        var videoFrames = 0L
        var audioFrames = 0L
        while (true) {
            val videoSample = video.peek()
            val audioSample = audio.peek()
            if (videoSample == null && audioSample == null) break

            val videoTime = videoSample?.let { video.seconds(it.decodeTime) - videoShift } ?: Double.MAX_VALUE
            val audioTime = audioSample?.let { audio.seconds(it.decodeTime) } ?: Double.MAX_VALUE

            if (videoTime <= audioTime) {
                val sample = video.next()!!
                val dts = clock(videoTime)
                val pts = clock(video.seconds(sample.decodeTime + sample.compositionOffset) - videoShift)
                val (annexB, isKeyframe) = toAnnexB(sample.data, avc)
                if (isKeyframe) writer.writeTables()
                writer.writeVideo(annexB, pts, dts, isKeyframe, (dts - START_DELAY).coerceAtLeast(0))
                writer.flush()
                videoFrames++
            } else {
                val sample = audio.next()!!
                // audio before the first video frame would have a negative time
                if (audioTime >= baseSeconds) {
                    writer.writeAudio(adts(sample.data, aac), clock(audioTime))
                    audioFrames++
                }
            }
        }
        writer.flush()
        CastLog.d("TS mux finished: $videoFrames video frames, $audioFrames audio frames")
    }

    companion object {
        /** Like ffmpeg: timestamps start 0.7 s after the clock, room for B-frame reordering. */
        const val START_DELAY = 63_000L

        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_AUD = 9
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
            nals.forEachIndexed { i, nal ->
                if (types[i] != NAL_AUD) {
                    out.write(START_CODE)
                    out.write(nal)
                }
            }
            return out.toByteArray() to isKeyframe
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
