/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.ts

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * What is needed to put an H.264 or AAC track into MPEG-TS, read from the `stsd` box.
 */
sealed interface CodecConfig {
    /**
     * @param nalLengthSize the size of the length prefix of every NAL unit in the samples
     * @param sps the sequence parameter sets, without start codes
     * @param pps the picture parameter sets, without start codes
     */
    class Avc(val nalLengthSize: Int, val sps: List<ByteArray>, val pps: List<ByteArray>) : CodecConfig

    /**
     * @param objectType the MPEG-4 audio object type, e.g. 2 for AAC-LC
     * @param frequencyIndex the sampling frequency index (e.g. 4 for 44100 Hz)
     * @param channels the channel configuration (e.g. 2 for stereo)
     */
    class Aac(val objectType: Int, val frequencyIndex: Int, val channels: Int) : CodecConfig
}

/**
 * The track of a fragmented MP4 file (as served by YouTube: one track per file).
 *
 * @param timescale ticks per second of the sample times
 */
class Mp4Track(
    val trackId: Int,
    val timescale: Long,
    val codec: CodecConfig,
    val defaultSampleDuration: Int,
    val defaultSampleSize: Int
)

/**
 * One reference of the segment index (`sidx`): a `moof` + `mdat` fragment.
 *
 * @param offset the position of the fragment in the file
 * @param startSeconds the presentation time at which the fragment starts
 */
data class Mp4Segment(val offset: Long, val size: Long, val startSeconds: Double, val durationSeconds: Double)

/**
 * @param decodeTime in track timescale units
 * @param compositionOffset presentation time minus decode time, in track timescale units
 */
class Mp4Sample(val decodeTime: Long, val compositionOffset: Int, val data: ByteArray)

/**
 * Minimal parser for the fragmented MP4 files used by DASH, enough to read H.264 and AAC samples
 * one fragment at a time from a forward-only stream.
 */
object FragmentedMp4 {
    private const val FTYP = 0x66747970
    private const val MOOV = 0x6D6F6F76
    private const val TRAK = 0x7472616B
    private const val TKHD = 0x746B6864
    private const val MDIA = 0x6D646961
    private const val MDHD = 0x6D646864
    private const val MINF = 0x6D696E66
    private const val STBL = 0x7374626C
    private const val STSD = 0x73747364
    private const val MVEX = 0x6D766578
    private const val TREX = 0x74726578
    private const val AVC1 = 0x61766331
    private const val AVC3 = 0x61766333
    private const val AVCC = 0x61766343
    private const val MP4A = 0x6D703461
    private const val ESDS = 0x65736473
    private const val SIDX = 0x73696478
    internal const val MOOF = 0x6D6F6F66
    internal const val MDAT = 0x6D646174
    private const val TRAF = 0x74726166
    private const val TFHD = 0x74666864
    private const val TFDT = 0x74666474
    private const val TRUN = 0x7472756E

    /** Size of the fields of a VisualSampleEntry before its child boxes. */
    private const val VISUAL_SAMPLE_ENTRY_SIZE = 78

    /** Size of the fields of an AudioSampleEntry before its child boxes. */
    private const val AUDIO_SAMPLE_ENTRY_SIZE = 28

    /**
     * Parses the initialization data (`ftyp` + `moov`).
     *
     * @throws IOException if there is no H.264 or AAC track
     */
    fun parseInit(init: ByteArray): Mp4Track {
        val moov = boxes(ByteBuffer.wrap(init)).firstOrNull { it.type == MOOV }
            ?: throw IOException("No moov box in the initialization data")
        val trak = boxes(moov.body).firstOrNull { it.type == TRAK }
            ?: throw IOException("No track in moov")

        val tkhd = child(trak.body, TKHD)
        val tkhdVersion = tkhd.get(0).toInt()
        val trackId = tkhd.getInt(if (tkhdVersion == 1) 4 + 16 else 4 + 8)

        val mdia = child(trak.body, MDIA)
        val mdhd = child(mdia, MDHD)
        val timescale = mdhd.getInt(if (mdhd.get(0).toInt() == 1) 4 + 16 else 4 + 8).toLong() and 0xFFFFFFFFL

        val stsd = child(child(child(mdia, MINF), STBL), STSD)
        // version, flags and entry count, then the first sample entry
        stsd.position(8)
        val entry = boxes(stsd.slice()).firstOrNull() ?: throw IOException("Empty stsd")
        val codec = when (entry.type) {
            AVC1, AVC3 -> parseAvcC(child(entry.body, AVCC, VISUAL_SAMPLE_ENTRY_SIZE))
            MP4A -> parseEsds(child(entry.body, ESDS, AUDIO_SAMPLE_ENTRY_SIZE))
            else -> throw IOException("Unsupported codec ${typeName(entry.type)}")
        }

        var defaultDuration = 0
        var defaultSize = 0
        boxes(moov.body).firstOrNull { it.type == MVEX }?.let { mvex ->
            boxes(mvex.body).filter { it.type == TREX }.forEach { trex ->
                val body = trex.body
                if (body.getInt(4) == trackId) {
                    defaultDuration = body.getInt(12)
                    defaultSize = body.getInt(16)
                }
            }
        }
        return Mp4Track(trackId, timescale, codec, defaultDuration, defaultSize)
    }

    /**
     * Parses the segment index.
     *
     * @param sidx the bytes of the `sidx` box
     * @param sidxOffset the position of the `sidx` box in the file
     */
    fun parseSidx(sidx: ByteArray, sidxOffset: Long): List<Mp4Segment> {
        val box = boxes(ByteBuffer.wrap(sidx)).firstOrNull { it.type == SIDX }
            ?: throw IOException("No sidx box in the index data")
        val body = box.body
        val version = body.get().toInt()
        body.position(body.position() + 3 + 4) // flags, reference ID
        val timescale = body.int.toLong() and 0xFFFFFFFFL
        if (timescale <= 0) {
            throw IOException("Invalid sidx timescale")
        }
        val earliestTime: Long
        val firstOffset: Long
        if (version == 0) {
            earliestTime = body.int.toLong() and 0xFFFFFFFFL
            firstOffset = body.int.toLong() and 0xFFFFFFFFL
        } else {
            earliestTime = body.long
            firstOffset = body.long
        }
        body.position(body.position() + 2) // reserved
        val count = body.short.toInt() and 0xFFFF

        var offset = sidxOffset + box.size + firstOffset
        var time = earliestTime
        val segments = ArrayList<Mp4Segment>(count)
        repeat(count) {
            val size = (body.int and 0x7FFFFFFF).toLong()
            val duration = body.int.toLong() and 0xFFFFFFFFL
            body.int // SAP
            segments.add(
                Mp4Segment(offset, size, time.toDouble() / timescale, duration.toDouble() / timescale)
            )
            offset += size
            time += duration
        }
        return segments
    }

    /**
     * Reads the samples of the fragments that follow, one `moof` + `mdat` pair at a time.
     */
    class FragmentReader(input: InputStream, private val track: Mp4Track) {
        private val input = DataInputStream(input)

        /** Decode time of the next sample, for fragments without tfdt. */
        private var nextDecodeTime = 0L

        /** @return the samples of the next fragment, or null at the end of the stream */
        fun nextFragment(): List<Mp4Sample>? {
            while (true) {
                val moofHeader = readHeader() ?: return null
                if (moofHeader.type != MOOF) {
                    skipFully(moofHeader.bodySize)
                    continue
                }
                val moof = ByteBuffer.wrap(readFully(moofHeader.bodySize))
                val runs = parseMoof(moof)

                var skipped = 0L
                var mdatHeader = readHeader() ?: throw EOFException("moof without mdat")
                while (mdatHeader.type != MDAT) {
                    skipFully(mdatHeader.bodySize)
                    skipped += mdatHeader.headerSize + mdatHeader.bodySize
                    mdatHeader = readHeader() ?: throw EOFException("moof without mdat")
                }
                val mdat = readFully(mdatHeader.bodySize)
                // the data offsets of trun are relative to the start of moof
                val mdatDataStart =
                    moofHeader.headerSize + moofHeader.bodySize + skipped + mdatHeader.headerSize
                return buildSamples(runs, mdat, mdatDataStart)
            }
        }

        private class Run(
            val decodeTime: Long,
            val dataOffset: Long?,
            val durations: IntArray,
            val sizes: IntArray,
            val compositionOffsets: IntArray
        )

        private fun parseMoof(moof: ByteBuffer): List<Run> {
            val runs = ArrayList<Run>()
            for (traf in boxes(moof).filter { it.type == TRAF }) {
                val children = boxes(traf.body)
                val tfhd = children.firstOrNull { it.type == TFHD }?.body ?: continue
                val tfhdFlags = tfhd.int and 0xFFFFFF
                if (tfhd.int != track.trackId) {
                    continue
                }
                if ((tfhdFlags and 0x01) != 0) tfhd.long // base data offset: only moof-relative files are supported
                if ((tfhdFlags and 0x02) != 0) tfhd.int // sample description index
                val defaultDuration = if ((tfhdFlags and 0x08) != 0) tfhd.int else track.defaultSampleDuration
                val defaultSize = if ((tfhdFlags and 0x10) != 0) tfhd.int else track.defaultSampleSize

                var decodeTime = children.firstOrNull { it.type == TFDT }?.body?.let { tfdt ->
                    if (tfdt.get().toInt() == 1) {
                        tfdt.position(4)
                        tfdt.long
                    } else {
                        tfdt.position(4)
                        tfdt.int.toLong() and 0xFFFFFFFFL
                    }
                } ?: nextDecodeTime

                for (trun in children.filter { it.type == TRUN }) {
                    val body = trun.body
                    val flags = body.int and 0xFFFFFF
                    val count = body.int
                    val dataOffset = if ((flags and 0x01) != 0) body.int.toLong() else null
                    if ((flags and 0x04) != 0) body.int // first sample flags
                    val durations = IntArray(count)
                    val sizes = IntArray(count)
                    val offsets = IntArray(count)
                    for (i in 0 until count) {
                        durations[i] = if ((flags and 0x100) != 0) body.int else defaultDuration
                        sizes[i] = if ((flags and 0x200) != 0) body.int else defaultSize
                        if ((flags and 0x400) != 0) body.int // sample flags
                        offsets[i] = if ((flags and 0x800) != 0) body.int else 0
                    }
                    runs.add(Run(decodeTime, dataOffset, durations, sizes, offsets))
                    decodeTime += durations.sumOf { it.toLong() }
                }
                nextDecodeTime = decodeTime
            }
            return runs
        }

        private fun buildSamples(runs: List<Run>, mdat: ByteArray, mdatDataStart: Long): List<Mp4Sample> {
            val samples = ArrayList<Mp4Sample>()
            var position = 0L // inside mdat, used when a trun has no data offset
            for (run in runs) {
                if (run.dataOffset != null) {
                    position = run.dataOffset - mdatDataStart
                }
                var time = run.decodeTime
                for (i in run.sizes.indices) {
                    val size = run.sizes[i]
                    if (position < 0 || position + size > mdat.size) {
                        throw IOException("Sample outside of mdat")
                    }
                    val start = position.toInt()
                    samples.add(Mp4Sample(time, run.compositionOffsets[i], mdat.copyOfRange(start, start + size)))
                    position += size
                    time += run.durations[i]
                }
            }
            return samples
        }

        private class Header(val type: Int, val headerSize: Long, val bodySize: Long)

        private fun readHeader(): Header? {
            val first = input.read()
            if (first < 0) {
                return null
            }
            val size32 = (first.toLong() shl 24) or
                (input.readUnsignedByte().toLong() shl 16) or
                (input.readUnsignedByte().toLong() shl 8) or
                input.readUnsignedByte().toLong()
            val type = input.readInt()
            return when (size32) {
                1L -> Header(type, 16, input.readLong() - 16)
                0L -> throw IOException("Boxes extending to the end of the file are not supported")
                else -> Header(type, 8, size32 - 8)
            }
        }

        private fun readFully(size: Long): ByteArray {
            if (size < 0 || size > MAX_BOX_SIZE) {
                throw IOException("Invalid box size $size")
            }
            return ByteArray(size.toInt()).also { input.readFully(it) }
        }

        private fun skipFully(size: Long) {
            var remaining = size
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }

    /** Fragments bigger than this are not expected (YouTube's are a few MB). */
    private const val MAX_BOX_SIZE = 64L * 1024 * 1024

    private class Box(val type: Int, val size: Long, val body: ByteBuffer)

    /** @return the boxes in [buffer], from its position to its limit */
    private fun boxes(buffer: ByteBuffer): List<Box> {
        val data = buffer.slice()
        val result = ArrayList<Box>()
        while (data.remaining() >= 8) {
            val start = data.position()
            var size = data.int.toLong() and 0xFFFFFFFFL
            val type = data.int
            var headerSize = 8
            if (size == 1L) {
                size = data.long
                headerSize = 16
            } else if (size == 0L) {
                size = (data.limit() - start).toLong()
            }
            if (size < headerSize || start + size > data.limit()) {
                break
            }
            val body = data.duplicate()
            body.position(start + headerSize)
            body.limit((start + size).toInt())
            result.add(Box(type, size, body.slice()))
            data.position((start + size).toInt())
        }
        return result
    }

    private fun child(parent: ByteBuffer, type: Int, skip: Int = 0): ByteBuffer {
        val data = parent.duplicate()
        data.position(skip)
        return boxes(data).firstOrNull { it.type == type }?.body
            ?: throw IOException("No ${typeName(type)} box")
    }

    private fun parseAvcC(avcC: ByteBuffer): CodecConfig.Avc {
        avcC.position(4) // version, profile, compatibility, level
        val nalLengthSize = (avcC.get().toInt() and 0x03) + 1
        val sps = readParameterSets(avcC, avcC.get().toInt() and 0x1F)
        val pps = readParameterSets(avcC, avcC.get().toInt() and 0xFF)
        return CodecConfig.Avc(nalLengthSize, sps, pps)
    }

    private fun readParameterSets(buffer: ByteBuffer, count: Int): List<ByteArray> = List(count) {
        val length = buffer.short.toInt() and 0xFFFF
        ByteArray(length).also { buffer.get(it) }
    }

    /** Finds the AudioSpecificConfig in the descriptors of the esds box. */
    private fun parseEsds(esds: ByteBuffer): CodecConfig.Aac {
        esds.position(4) // version and flags
        while (esds.hasRemaining()) {
            val tag = esds.get().toInt() and 0xFF
            var length = 0
            for (i in 0 until 4) {
                val b = esds.get().toInt() and 0xFF
                length = (length shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) break
            }
            when (tag) {
                // ES_Descriptor: ES_ID, flags and the optional fields they announce
                0x03 -> {
                    esds.short
                    val flags = esds.get().toInt() and 0xFF
                    if ((flags and 0x80) != 0) esds.short
                    if ((flags and 0x40) != 0) esds.position(esds.position() + (esds.get().toInt() and 0xFF))
                    if ((flags and 0x20) != 0) esds.short
                }

                // DecoderConfigDescriptor: fixed fields, then the DecoderSpecificInfo
                0x04 -> esds.position(esds.position() + 13)

                // DecoderSpecificInfo: the AudioSpecificConfig
                0x05 -> {
                    val b0 = esds.get().toInt() and 0xFF
                    val b1 = esds.get().toInt() and 0xFF
                    var objectType = b0 shr 3
                    var frequencyIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
                    var channels = (b1 shr 3) and 0x0F
                    if (objectType == 31 || frequencyIndex == 15) {
                        throw IOException("Unsupported AudioSpecificConfig")
                    }
                    // HE-AAC is signaled implicitly in YouTube files, AAC-LC is what ADTS needs
                    if (objectType > 4) objectType = 2
                    if (channels == 0) channels = 2
                    return CodecConfig.Aac(objectType, frequencyIndex, channels)
                }

                else -> esds.position(esds.position() + length)
            }
        }
        throw IOException("No AudioSpecificConfig in esds")
    }

    private fun typeName(type: Int) = String(
        byteArrayOf((type shr 24).toByte(), (type shr 16).toByte(), (type shr 8).toByte(), type.toByte()),
        Charsets.ISO_8859_1
    )
}
