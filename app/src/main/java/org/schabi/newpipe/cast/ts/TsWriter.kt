/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.ts

import java.io.OutputStream

/**
 * Writes an MPEG transport stream with one H.264 video and one AAC (ADTS) audio stream.
 *
 * Times are in 90 kHz units. The PCR is carried by the video stream.
 */
class TsWriter(private val output: OutputStream) {
    private val continuity = HashMap<Int, Int>()
    private val packet = ByteArray(PACKET_SIZE)

    /** Writes the program tables, needed by players to find the streams. */
    fun writeTables() {
        writeSection(PAT_PID, pat())
        writeSection(PMT_PID, pmt())
    }

    /**
     * @param annexB the access unit, with start codes
     * @param pcr the program clock reference to send with it, or null
     */
    fun writeVideo(annexB: ByteArray, pts: Long, dts: Long, isKeyframe: Boolean, pcr: Long?) {
        writePes(VIDEO_PID, VIDEO_STREAM_ID, annexB, pts, dts, isKeyframe, pcr)
    }

    /** @param adts one or more AAC frames, each with its ADTS header */
    fun writeAudio(adts: ByteArray, pts: Long) {
        writePes(AUDIO_PID, AUDIO_STREAM_ID, adts, pts, pts, randomAccess = true, pcr = null)
    }

    fun flush() = output.flush()

    private fun writePes(
        pid: Int,
        streamId: Int,
        payload: ByteArray,
        pts: Long,
        dts: Long,
        randomAccess: Boolean,
        pcr: Long?
    ) {
        val hasDts = dts != pts
        val headerDataLength = if (hasDts) 10 else 5
        val header = ByteArray(9 + headerDataLength)
        header[0] = 0
        header[1] = 0
        header[2] = 1
        header[3] = streamId.toByte()
        val pesLength = 3 + headerDataLength + payload.size
        // 0 (unbounded) is allowed only for video, which can be bigger than 64 KiB
        val lengthField = if (pesLength > 0xFFFF) 0 else pesLength
        header[4] = (lengthField shr 8).toByte()
        header[5] = lengthField.toByte()
        header[6] = 0x80.toByte() // marker bits '10'
        header[7] = (if (hasDts) 0xC0 else 0x80).toByte()
        header[8] = headerDataLength.toByte()
        writeTimestamp(header, 9, if (hasDts) 0x3 else 0x2, pts)
        if (hasDts) {
            writeTimestamp(header, 14, 0x1, dts)
        }

        val pes = header + payload
        var offset = 0
        var first = true
        while (offset < pes.size) {
            packet.fill(0xFF.toByte())
            packet[0] = SYNC_BYTE
            packet[1] = ((if (first) 0x40 else 0) or (pid shr 8 and 0x1F)).toByte()
            packet[2] = pid.toByte()

            // adaptation field: PCR and random access flag on the first packet, then stuffing
            val firstPcr = if (first) pcr else null
            val fieldFlags = (if (first && randomAccess) 0x40 else 0) or (if (firstPcr != null) 0x10 else 0)
            val minFieldLength = when {
                firstPcr != null -> 8

                // flags + 6 bytes of PCR + length byte
                fieldFlags != 0 -> 2

                else -> 0
            }
            val remaining = pes.size - offset
            val space = PACKET_SIZE - 4 - minFieldLength
            val fieldLength = if (remaining < space) minFieldLength + (space - remaining) else minFieldLength
            val payloadSize = PACKET_SIZE - 4 - fieldLength

            packet[3] = ((if (fieldLength > 0) 0x30 else 0x10) or nextContinuity(pid)).toByte()
            if (fieldLength > 0) {
                packet[4] = (fieldLength - 1).toByte()
                if (fieldLength > 1) {
                    packet[5] = fieldFlags.toByte()
                    if (firstPcr != null) {
                        writePcr(packet, 6, firstPcr)
                    }
                    // the rest up to the payload is stuffing, already 0xFF
                }
            }
            System.arraycopy(pes, offset, packet, 4 + fieldLength, payloadSize)
            output.write(packet)
            offset += payloadSize
            first = false
        }
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        packet.fill(0xFF.toByte())
        packet[0] = SYNC_BYTE
        packet[1] = (0x40 or (pid shr 8 and 0x1F)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or nextContinuity(pid)).toByte()
        packet[4] = 0 // pointer field
        System.arraycopy(section, 0, packet, 5, section.size)
        output.write(packet)
    }

    private fun nextContinuity(pid: Int): Int {
        val value = continuity[pid] ?: 0
        continuity[pid] = (value + 1) and 0x0F
        return value
    }

    private fun pat(): ByteArray = withCrc(
        byteArrayOf(
            0x00, // table id
            0xB0.toByte(), 13, // section syntax, length
            0x00, 0x01, // transport stream id
            0xC1.toByte(), // version 0, current
            0x00, 0x00, // section numbers
            0x00, PROGRAM_NUMBER.toByte(),
            (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte()
        )
    )

    private fun pmt(): ByteArray = withCrc(
        byteArrayOf(
            0x02, // table id
            0xB0.toByte(), 23, // section syntax, length
            0x00, PROGRAM_NUMBER.toByte(),
            0xC1.toByte(),
            0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), // PCR PID
            0xF0.toByte(), 0x00, // program info length
            STREAM_TYPE_H264.toByte(), (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0x00,
            STREAM_TYPE_AAC.toByte(), (0xE0 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0x00
        )
    )

    companion object {
        const val PACKET_SIZE = 188
        const val SYNC_BYTE: Byte = 0x47
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        const val AUDIO_PID = 0x0101
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_AAC = 0x0F
        private const val PROGRAM_NUMBER = 1
        private const val VIDEO_STREAM_ID = 0xE0
        private const val AUDIO_STREAM_ID = 0xC0

        /** Clock of PTS, DTS and PCR base. */
        const val CLOCK = 90_000L

        private fun writeTimestamp(target: ByteArray, offset: Int, prefix: Int, value: Long) {
            val time = value and 0x1FFFFFFFFL
            target[offset] = ((prefix shl 4) or ((time shr 29).toInt() and 0x0E) or 0x01).toByte()
            target[offset + 1] = (time shr 22).toByte()
            target[offset + 2] = (((time shr 14).toInt() and 0xFE) or 0x01).toByte()
            target[offset + 3] = (time shr 7).toByte()
            target[offset + 4] = (((time shl 1).toInt() and 0xFE) or 0x01).toByte()
        }

        private fun writePcr(target: ByteArray, offset: Int, value: Long) {
            val base = value and 0x1FFFFFFFFL
            target[offset] = (base shr 25).toByte()
            target[offset + 1] = (base shr 17).toByte()
            target[offset + 2] = (base shr 9).toByte()
            target[offset + 3] = (base shr 1).toByte()
            target[offset + 4] = (((base and 1L).toInt() shl 7) or 0x7E).toByte() // reserved bits, extension 0
            target[offset + 5] = 0
        }

        /** Appends the MPEG-2 CRC32 of [section]. */
        fun withCrc(section: ByteArray): ByteArray {
            val crc = crc32(section)
            return section + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        }

        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) {
                    crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
                }
            }
            return crc
        }
    }
}
