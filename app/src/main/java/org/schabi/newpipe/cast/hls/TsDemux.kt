/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.hls

import java.io.ByteArrayOutputStream

/**
 * MPEG-TS demuxer for the HLS segments of YouTube: H.264 video (Annex B access units) and AAC
 * audio (ADTS frames split into raw AAC frames).
 *
 * Line by line port of `Perle81.Native/Media/TsDemux.cpp` of the Lumia app (dumbphone), whose
 * cast to the TV works in every case tried on a Samsung TV.
 */
object TsDemux {
    /** One H.264 access unit (one PES packet), Annex B, with SPS and PPS in front of key frames. */
    class VideoAccessUnit(
        /** presentation time, 90 kHz, relative to [TsSegmentData.baseRaw] */
        val pts90k: Long,
        /** decoding time, same origin */
        val dts90k: Long,
        /** contains an IDR slice */
        val key: Boolean,
        val annexB: ByteArray
    ) {
        var dur90k = 0L
    }

    /** One raw AAC frame (ADTS header removed), presentation time in 90 kHz. */
    class AudioFrame(val pts90k: Long, val aac: ByteArray)

    class TsSegmentData {
        var hasVideo = false
        var hasAudio = false
        val video = ArrayList<VideoAccessUnit>()
        val audio = ArrayList<AudioFrame>()
        var spsAnnexB: ByteArray? = null
        var ppsAnnexB: ByteArray? = null
        var audioSpecificConfig: ByteArray? = null
        var audioSampleRate = 0
        var audioChannels = 0

        /**
         * The 33-bit 90 kHz value all the times are relative to: DTS of the first video access
         * unit of a TS segment, or the ID3 transport timestamp of an AAC segment.
         */
        var baseRaw = 0L
        var error = ""
    }

    private const val PACKET_SIZE = 188
    private const val PAT_PID = 0
    private const val STREAM_TYPE_H264 = 0x1B
    private const val STREAM_TYPE_ADTS_AAC = 0x0F
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private const val ID3_OWNER = "com.apple.streaming.transportStreamTimestamp"

    private class PesUnit {
        /** elementary stream bytes after the PES header */
        val data = ByteArrayOutputStream()
        var hasPts = false
        var hasDts = false
        var pts = 0L
        var dts = 0L
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    /** Reads the 33-bit timestamp of a PES header (5 bytes, marker bits in between). */
    private fun readTimestamp(p: ByteArray, o: Int): Long = ((u(p[o]) shr 1) and 0x07).toLong() shl 30 or
        (u(p[o + 1]).toLong() shl 22) or
        (((u(p[o + 2]) shr 1) and 0x7F).toLong() shl 15) or
        (u(p[o + 3]).toLong() shl 7) or
        ((u(p[o + 4]) shr 1) and 0x7F).toLong()

    /** Signed distance from origin on the 33-bit clock (handles the wrap-around every ~26 hours). */
    private fun unwrap(value: Long, origin: Long): Long {
        val wrap = 1L shl 33
        var d = (value - origin) % wrap
        if (d < 0) d += wrap
        if (d >= wrap / 2) d -= wrap
        return d
    }

    private fun parsePes(p: ByteArray, unit: PesUnit) {
        // 00 00 01 stream_id length(2) then, for most streams, flags(2) header_length(1) and the optional fields
        if (p.size < 9 || p[0].toInt() != 0 || p[1].toInt() != 0 || p[2].toInt() != 1) return
        val streamId = u(p[3])
        if (streamId == 0xBC || streamId == 0xBE || streamId == 0xBF || streamId in 0xF0..0xF2 ||
            streamId == 0xF8 || streamId == 0xFF
        ) {
            unit.data.write(p, 6, p.size - 6)
            return
        }
        val flags = u(p[7])
        val headerLen = u(p[8])
        val start = 9 + headerLen
        if (start > p.size) return
        if ((flags and 0x80) != 0 && p.size >= 14) {
            unit.hasPts = true
            unit.pts = readTimestamp(p, 9)
            unit.dts = unit.pts
        }
        if ((flags and 0x40) != 0 && p.size >= 19) {
            unit.hasDts = true
            unit.dts = readTimestamp(p, 14)
        }
        unit.data.write(p, start, p.size - start)
    }

    /** Splits Annex B data into NAL units (without start codes): pairs of offset and length. */
    private fun splitNals(b: ByteArray): List<Pair<Int, Int>> {
        val nals = ArrayList<Pair<Int, Int>>()
        val n = b.size
        var i = 0
        var cur = -1
        while (i + 2 < n) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                if (cur != -1) nals.add(cur to i - cur)
                i += 3
                cur = i
                continue
            }
            i++
        }
        if (cur != -1 && cur < n) {
            // trailing zeros belong to the next start code (00 00 00 01): strip them
            var end = n
            while (end > cur && b[end - 1].toInt() == 0) end--
            nals.add(cur to end - cur)
        }
        return nals
    }

    /** Appends the ADTS frames of one buffer, the first one at time pts (90 kHz, relative to the segment base). */
    private fun appendAdts(b: ByteArray, from: Int, firstPts: Long, out: TsSegmentData) {
        var pts = firstPts
        var p = from
        while (p + 7 <= b.size) {
            if (u(b[p]) != 0xFF || (u(b[p + 1]) and 0xF0) != 0xF0) {
                p++
                continue
            }
            val protectionAbsent = u(b[p + 1]) and 1
            val profile = (u(b[p + 2]) shr 6) and 3 // AOT - 1
            val freqIndex = (u(b[p + 2]) shr 2) and 0x0F
            val channels = ((u(b[p + 2]) and 1) shl 2) or ((u(b[p + 3]) shr 6) and 3)
            val frameLen = ((u(b[p + 3]) and 0x03) shl 11) or (u(b[p + 4]) shl 3) or ((u(b[p + 5]) shr 5) and 0x07)
            val hdr = if (protectionAbsent != 0) 7 else 9
            if (freqIndex > 12 || frameLen < hdr || p + frameLen > b.size) {
                p++
                continue
            }
            if (out.audioSampleRate == 0) {
                out.audioSampleRate = RATES[freqIndex]
                out.audioChannels = channels
                out.audioSpecificConfig = byteArrayOf(
                    (((profile + 1) shl 3) or (freqIndex shr 1)).toByte(),
                    (((freqIndex and 1) shl 7) or (channels shl 3)).toByte()
                )
            }
            out.audio.add(AudioFrame(pts, b.copyOfRange(p + hdr, p + frameLen)))
            pts += 1024L * 90000 / RATES[freqIndex]
            p += frameLen
        }
    }

    /** Reads the base time of an ID3v2 tag (0 when absent): returns it and the offset of the first frame after it. */
    private fun id3Base(b: ByteArray): Pair<Long, Int> {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) {
            return 0L to 0
        }
        var base = 0L
        val size = ((u(b[6]) and 0x7F) shl 21) or ((u(b[7]) and 0x7F) shl 14) or ((u(b[8]) and 0x7F) shl 7) or (u(b[9]) and 0x7F)
        val end = minOf(b.size, 10 + size)
        var p = 10
        val owner = ID3_OWNER.toByteArray(Charsets.ISO_8859_1)
        while (p + 10 <= end) {
            if (b[p].toInt() == 0) break
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val fsize = (u(b[p + 4]) shl 24) or (u(b[p + 5]) shl 16) or (u(b[p + 6]) shl 8) or u(b[p + 7])
            if (fsize < 0 || fsize > end - p - 10) break
            if (id == "PRIV") {
                val pl = p + 10
                if (fsize > owner.size + 1 && fsize >= owner.size + 1 + 8 &&
                    (owner.indices).all { b[pl + it] == owner[it] }
                ) {
                    val v = pl + fsize - 8
                    var ts = 0L
                    for (i in 0 until 8) ts = (ts shl 8) or u(b[v + i]).toLong()
                    base = ts and ((1L shl 33) - 1)
                }
            }
            p += 10 + fsize
        }
        return base to end
    }

    private fun annexBOf(nal: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(4 + length)
        System.arraycopy(START_CODE, 0, out, 0, 4)
        System.arraycopy(nal, offset, out, 4, length)
        return out
    }

    /** Parses one complete TS segment. Returns null (and logs nothing) when no PAT/PMT or no PES was found. */
    fun parseTsSegment(data: ByteArray): TsSegmentData? {
        val out = TsSegmentData()
        var pmtPid = -1
        var videoPid = -1
        var audioPid = -1
        val units = HashMap<Int, ArrayList<PesUnit>>() // pid -> PES packets in order
        val open = LinkedHashMap<Int, PesUnit>() // pid -> PES being collected
        val started = HashSet<Int>()

        fun flush(pid: Int) {
            val unit = open.remove(pid) ?: return
            units.getOrPut(pid) { ArrayList() }.add(unit)
        }

        var off = 0
        while (off + PACKET_SIZE <= data.size) {
            val pk = off
            off += PACKET_SIZE
            if (u(data[pk]) != 0x47) {
                out.error = "TS sync lost"
                return null
            }
            val pusi = (u(data[pk + 1]) and 0x40) != 0
            val pid = ((u(data[pk + 1]) and 0x1F) shl 8) or u(data[pk + 2])
            val afc = (u(data[pk + 3]) shr 4) and 3
            var pos = 4
            if ((afc and 2) != 0) {
                pos += 1 + u(data[pk + 4])
            }
            if ((afc and 1) == 0 || pos >= PACKET_SIZE) continue
            val payload = pk + pos
            val plen = PACKET_SIZE - pos

            if (pid == PAT_PID && pusi && plen > 1) {
                val p = 1 + u(data[payload])
                // PAT: table_id 0, section length; program entries of 4 bytes, the last 4 bytes are the CRC
                if (p + 8 <= plen && data[payload + p].toInt() == 0) {
                    val secLen = ((u(data[payload + p + 1]) and 0x0F) shl 8) or u(data[payload + p + 2])
                    val end = minOf(plen, p + 3 + secLen)
                    var q = p + 8
                    while (q + 4 <= end - 4) {
                        val program = (u(data[payload + q]) shl 8) or u(data[payload + q + 1])
                        if (program != 0 && pmtPid < 0) {
                            pmtPid = ((u(data[payload + q + 2]) and 0x1F) shl 8) or u(data[payload + q + 3])
                        }
                        q += 4
                    }
                }
                continue
            }
            if (pid == pmtPid && pusi && plen > 1) {
                val p = 1 + u(data[payload])
                if (p + 12 <= plen && data[payload + p].toInt() == 2) {
                    val secLen = ((u(data[payload + p + 1]) and 0x0F) shl 8) or u(data[payload + p + 2])
                    val progInfo = ((u(data[payload + p + 10]) and 0x0F) shl 8) or u(data[payload + p + 11])
                    var q = p + 12 + progInfo
                    val end = minOf(plen, p + 3 + secLen)
                    while (q + 5 <= end - 4) {
                        val type = u(data[payload + q])
                        val elPid = ((u(data[payload + q + 1]) and 0x1F) shl 8) or u(data[payload + q + 2])
                        val esLen = ((u(data[payload + q + 3]) and 0x0F) shl 8) or u(data[payload + q + 4])
                        if (type == STREAM_TYPE_H264 && videoPid < 0) videoPid = elPid
                        if (type == STREAM_TYPE_ADTS_AAC && audioPid < 0) audioPid = elPid
                        q += 5 + esLen
                    }
                }
                continue
            }
            if (pid != videoPid && pid != audioPid) continue

            if (pusi) {
                flush(pid)
                val unit = PesUnit()
                open[pid] = unit
                started.add(pid)
                parsePes(data.copyOfRange(payload, payload + plen), unit)
            } else if (pid in started) {
                open[pid]?.data?.write(data, payload, plen)
            }
        }
        for ((pid, unit) in open) {
            units.getOrPut(pid) { ArrayList() }.add(unit)
        }
        open.clear()

        if (pmtPid < 0) {
            out.error = "PAT/PMT not found"
            return null
        }

        // ---- video: one PES = one access unit (Annex B), SPS/PPS kept for the sequence header
        val vu = units[videoPid].orEmpty()
        val au = units[audioPid].orEmpty()
        val hasVideo = videoPid >= 0 && vu.isNotEmpty()
        val hasAudio = audioPid >= 0 && au.isNotEmpty()
        if (!hasVideo && !hasAudio) {
            out.error = "no audio or video PES"
            return null
        }

        // origin: first DTS of the video (or first PTS of the audio when there is no video), so no time is negative
        var origin = 0L
        var haveOrigin = false
        if (hasVideo) {
            for (unit in vu) {
                if (unit.hasPts) {
                    origin = if (unit.hasDts) unit.dts else unit.pts
                    haveOrigin = true
                    break
                }
            }
        }
        if (!haveOrigin && hasAudio) {
            for (unit in au) {
                if (unit.hasPts) {
                    origin = unit.pts
                    haveOrigin = true
                    break
                }
            }
        }
        if (!haveOrigin) {
            out.error = "no PTS"
            return null
        }
        out.baseRaw = origin

        if (hasVideo) {
            out.hasVideo = true
            for (unit in vu) {
                if (!unit.hasPts) continue
                val bytes = unit.data.toByteArray()
                val pts = unwrap(unit.pts, origin)
                val dts = if (unit.hasDts) unwrap(unit.dts, origin) else pts
                var key = false
                var hasSps = false
                var hasPps = false
                val annexB = ByteArrayOutputStream(bytes.size + 64)
                for ((start, len) in splitNals(bytes)) {
                    if (len == 0) continue
                    when (u(bytes[start]) and 0x1F) {
                        7 -> {
                            hasSps = true
                            if (out.spsAnnexB == null) out.spsAnnexB = annexBOf(bytes, start, len)
                        }

                        8 -> {
                            hasPps = true
                            if (out.ppsAnnexB == null) out.ppsAnnexB = annexBOf(bytes, start, len)
                        }

                        5 -> key = true
                    }
                    annexB.write(START_CODE)
                    annexB.write(bytes, start, len)
                }
                var accessUnit = annexB.toByteArray()
                // key frames always carry SPS and PPS in front, so the decoder can start at any segment
                val sps = out.spsAnnexB
                val pps = out.ppsAnnexB
                if (key && (!hasSps || !hasPps) && sps != null && pps != null) {
                    accessUnit = sps + pps + accessUnit
                }
                out.video.add(VideoAccessUnit(pts, dts, key, accessUnit))
            }
            // durations: distance to the next access unit in decoding order
            for (i in out.video.indices) {
                out.video[i].dur90k = if (i + 1 < out.video.size) {
                    maxOf(0L, out.video[i + 1].dts90k - out.video[i].dts90k)
                } else if (i > 0) {
                    out.video[i - 1].dur90k
                } else {
                    3600
                }
            }
        }

        if (hasAudio) {
            out.hasAudio = true
            for (unit in au) {
                if (unit.hasPts) appendAdts(unit.data.toByteArray(), 0, unwrap(unit.pts, origin), out)
            }
            if (out.audio.isEmpty()) out.hasAudio = false
        }

        if (!out.hasVideo && !out.hasAudio) {
            out.error = "no samples"
            return null
        }
        return out
    }

    /**
     * Parses one AAC segment of YouTube's HLS audio renditions: ADTS frames after an ID3 tag
     * whose PRIV frame "com.apple.streaming.transportStreamTimestamp" gives the base time.
     */
    fun parseAacSegment(data: ByteArray): TsSegmentData? {
        val out = TsSegmentData()
        val (base, start) = id3Base(data)
        out.baseRaw = base
        appendAdts(data, start, 0, out)
        out.hasAudio = out.audio.isNotEmpty()
        if (!out.hasAudio) {
            out.error = "no ADTS frames"
            return null
        }
        return out
    }
}
