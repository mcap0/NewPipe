/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.hls

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import org.schabi.newpipe.cast.ts.TsWriter

/**
 * Remuxes the demuxed HLS samples into the MPEG-TS stream sent to the TV, interleaved by time.
 *
 * Port of `PerleCast/core/TsMuxer.cpp` of the Lumia app (dumbphone). [TsWriter] is the same
 * writer as the Lumia one.
 *
 * @param seekOffset90k the time of the first frame sent: the TV's clock starts there
 */
class HlsTsMuxer(output: OutputStream, private val seekOffset90k: Long) {
    private class Sample(val bytes: ByteArray, val pts: Long, val dts: Long, val keyframe: Boolean)

    private val writer = TsWriter(output)
    private val video = ArrayDeque<Sample>()
    private val audio = ArrayDeque<Sample>()
    private var tablesWritten = false

    // dts of the last PAT/PMT: repeated every 100 ms
    private var lastTables = 0L

    fun pushVideo(annexB: ByteArray, pts90k: Long, dts90k: Long, keyframe: Boolean) {
        video.addLast(
            Sample(
                dropTrailingSei(annexB),
                maxOf(0L, pts90k - seekOffset90k) + START_DELAY,
                maxOf(0L, dts90k - seekOffset90k) + START_DELAY,
                keyframe
            )
        )
        emitReady(false)
    }

    fun pushAudio(adts: ByteArray, pts90k: Long) {
        val pts = maxOf(0L, pts90k - seekOffset90k) + START_DELAY
        audio.addLast(Sample(adts, pts, pts, false))
        emitReady(false)
    }

    fun finish() = emitReady(true)

    private fun emitReady(drain: Boolean) {
        if (!tablesWritten && (video.isNotEmpty() || audio.isNotEmpty())) {
            writer.writeSection(TsWriter.SDT_PID, LUMIA_SDT)
            writer.writeTables()
            tablesWritten = true
        }
        while (video.isNotEmpty() && audio.isNotEmpty()) {
            if (video.first.dts <= audio.first.pts) {
                emitVideo(video.removeFirst())
            } else {
                emitAudio(audio.removeFirst())
            }
        }
        if (drain) {
            while (video.isNotEmpty()) emitVideo(video.removeFirst())
            while (audio.isNotEmpty()) emitAudio(audio.removeFirst())
        }
    }

    // MPEG-TS wants a PCR at least every 100 ms and the tables repeated: with a PCR only on
    // keyframes (every ~6 s) and PAT/PMT once, the Samsung stayed in "Transition not available"
    // at some seek points, depending on the content (tools/tvlab: same segments remuxed by ffmpeg
    // played). PCR on every video frame, tables every 100 ms.
    private fun repeatTables(dts: Long) {
        if (dts - lastTables < TABLES_INTERVAL) return
        writer.writeTables()
        lastTables = dts
    }

    private fun emitVideo(sample: Sample) {
        repeatTables(sample.dts)
        writer.writeVideo(sample.bytes, sample.pts, sample.dts, sample.keyframe, maxOf(0L, sample.dts - PCR_DELAY))
    }

    private fun emitAudio(sample: Sample) {
        repeatTables(sample.pts)
        writer.writeAudio(sample.bytes, sample.pts)
    }

    companion object {
        private const val START_DELAY = 126_000L
        private const val PCR_DELAY = 63_000L
        private const val TABLES_INTERVAL = 9_000L

        /** The Lumia app's SDT, byte for byte (TsWriter::WriteSdt): the stream is identical to the one it sends. */
        private val LUMIA_SDT: ByteArray = run {
            val base = byteArrayOf(
                0x42, 0xF0.toByte(), 0x1B, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0xFF.toByte(), 0x01, 0xFF.toByte(),
                0x00, 0x01, 0xFC.toByte(), 0x80.toByte(), 0x0E, 0x48, 0x0C, 0x01, 0x07
            ) + "NewPipe".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x05) + "NewPipe".toByteArray(Charsets.US_ASCII)
            base[1] = (0xF0 or ((base.size + 4 - 3) shr 8)).toByte()
            base[2] = (base.size + 4 - 3).toByte()
            TsWriter.withCrc(base)
        }

        /**
         * YouTube's HLS puts an SEI after each segment's first keyframe, in its own PES without a
         * timestamp; the demuxer joins it to the keyframe, so the access unit ended with SEI after
         * the slices. The Samsung then stayed in "Transition not available" at some seek points
         * (depending on that SEI; tools/tvlab: ffmpeg, which moves it, played the same frames).
         * The SEI is optional metadata: dropped.
         */
        fun dropTrailingSei(b: ByteArray): ByteArray {
            val length = b.size
            val result = ByteArrayOutputStream(length)
            var vcl = false
            var i = 0
            fun z(k: Int) = b[k].toInt() == 0
            fun one(k: Int) = b[k].toInt() == 1
            while (i < length) {
                // the next start code (3 or 4 bytes) after this NAL's start code
                val start = i
                var header = i
                while (header + 2 < length && !(z(header) && z(header + 1) && one(header + 2))) ++header
                if (header + 3 >= length) {
                    result.write(b, start, length - start)
                    break
                }
                val nal = header + 3
                var next = nal + 1
                while (next + 2 < length &&
                    !(z(next) && z(next + 1) && (one(next + 2) || (z(next + 2) && next + 3 < length && one(next + 3))))
                ) {
                    ++next
                }
                if (next + 2 >= length) next = length
                val type = b[nal].toInt() and 0x1F
                if (type in 1..5) vcl = true
                if (!(type == 6 && vcl)) result.write(b, start, next - start)
                i = next
            }
            return result.toByteArray()
        }

        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        private fun audioRateIndex(rate: Int): Int = RATES.indexOf(rate).takeIf { it >= 0 } ?: 4

        /** Prefixes a raw AAC frame with its ADTS header, like the Lumia app's MakeAdts (AAC LC). */
        fun makeAdts(data: ByteArray, sampleRate: Int, channels: Int): ByteArray {
            val length = data.size + 7
            val frequency = audioRateIndex(sampleRate)
            val result = ByteArray(length)
            result[0] = 0xFF.toByte()
            result[1] = 0xF1.toByte()
            result[2] = ((1 shl 6) or (frequency shl 2) or ((channels shr 2) and 1)).toByte()
            result[3] = (((channels and 3) shl 6) or ((length shr 11) and 3)).toByte()
            result[4] = ((length shr 3) and 0xFF).toByte()
            result[5] = (((length and 7) shl 5) or 0x1F).toByte()
            result[6] = 0xFC.toByte()
            System.arraycopy(data, 0, result, 7, data.size)
            return result
        }
    }
}
