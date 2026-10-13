/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

class TsFormatTest {
    /** Part of the Sink list of a Samsung UE43CU7170 (Tizen 2025), which has no video/mp2t. */
    private val samsung = listOf(
        "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_FLAGS=ED100000000000000000000000000000",
        "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_NA_ISO;DLNA.ORG_FLAGS=ED100000000000000000000000000000",
        "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_NA;DLNA.ORG_FLAGS=ED100000000000000000000000000000",
        "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_HD_NA_T;DLNA.ORG_FLAGS=ED100000000000000000000000000000",
        "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_HD_KO_ISO;DLNA.ORG_FLAGS=ED100000000000000000000000000000",
        "http-get:*:video/mp4:DLNA.ORG_PN=MPEG4_P2_MP4_SP_AAC;DLNA.ORG_FLAGS=ED100000000000000000000000000000"
    )

    @Test
    fun neverChoosesTheTimestampedVariant() {
        assertEquals(TsFormat("video/mpeg", null), TsFormat.choose(samsung.joinToString(",")))
    }

    @Test
    fun prefersAnH264ProfileWithAac() {
        val sink = (
            samsung + listOf(
                "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_EU",
                "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HP_HD_AAC_ISO",
                "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_EU_ISO;DLNA.ORG_FLAGS=ED100000000000000000000000000000",
                "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HP_HD_AAC_ISO;DLNA.ORG_FLAGS=ED100000000000000000000000000000"
            )
            ).joinToString(",")
        val format = TsFormat.choose(sink)
        assertEquals(TsFormat("video/mpeg", "AVC_TS_HP_HD_AAC_ISO"), format)
        assertEquals("DLNA.ORG_PN=AVC_TS_HP_HD_AAC_ISO;" + TsFormat.LIVE_FEATURES, format.dlnaFeatures)
    }

    @Test
    fun prefersMp2tWithoutProfiles() {
        assertEquals(
            TsFormat("video/mp2t", null),
            TsFormat.choose("http-get:*:video/mpeg:*,http-get:*:video/mp2t:*")
        )
    }

    @Test
    fun defaultsWhenUnknown() {
        assertEquals(TsFormat.DEFAULT, TsFormat.choose(null))
        assertEquals(TsFormat.DEFAULT, TsFormat.choose("http-get:*:video/vnd.dlna.mpeg-tts:*"))
        assertEquals(TsFormat.LIVE_FEATURES, TsFormat.DEFAULT.dlnaFeatures)
    }

    @Test
    fun parsesOnlyVideoEntries() {
        assertEquals(
            listOf("video/mpeg" to "MPEG_TS_NA_ISO", "video/x-test" to null),
            TsFormat.parse(
                "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3, http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_NA_ISO;X=1," +
                    "http-get:*:VIDEO/x-test:*"
            )
        )
    }
}
