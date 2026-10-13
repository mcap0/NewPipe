/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.Serializable
import java.util.Locale

/**
 * What is sent to the TV.
 *
 * @param url the URL the TV downloads the video from (the local proxy or the direct link)
 * @param mimeType e.g. `video/mp4`
 * @param durationSeconds the duration, or a value <= 0 if unknown
 * @param thumbnailUrl optional album art shown by some TVs
 * @param dash if not null, [url] is ignored and the TV gets a DASH manifest playing these
 * separate video and audio streams together
 */
data class CastMedia(
    val url: String,
    val title: String,
    val uploader: String,
    val mimeType: String,
    val durationSeconds: Long,
    val thumbnailUrl: String? = null,
    val dash: CastDash? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Time format used by UPnP: `H+:MM:SS` (e.g. `0:03:25`), optionally followed by fractions.
 */
object UpnpTime {
    /** @return [seconds] formatted as `H:MM:SS`, as needed by Seek and the `duration` attribute */
    fun format(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /**
     * Parses `H+:MM:SS[.F+]` or `H+:MM:SS[.F0/F1]`.
     *
     * @return the time in seconds, or -1 if it is unknown (`NOT_IMPLEMENTED`, empty, ...)
     */
    fun parse(text: String?): Long {
        if (text.isNullOrBlank()) {
            return -1
        }
        val parts = text.trim().substringBefore('.').split(':')
        if (parts.size != 3) {
            return -1
        }
        val numbers = parts.map { it.trim().removePrefix("+").toLongOrNull() ?: return -1 }
        return numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
}

/**
 * Builds the DIDL-Lite metadata (`CurrentURIMetaData`) describing the video.
 *
 * Many renderers, Samsung TVs in particular, refuse `SetAVTransportURI` when the metadata is
 * empty or invalid, so a complete item with title, creator, class and `res` is always sent.
 */
object DidlLite {
    /**
     * The fourth field of the protocolInfo is the DLNA profile. `*` means "anything" and is
     * accepted by most renderers; it could be replaced with e.g.
     * `DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000`.
     */
    const val DLNA_FEATURES = "*"

    fun protocolInfo(mimeType: String) = "http-get:*:$mimeType:$DLNA_FEATURES"

    /**
     * @return the DIDL-Lite document, not yet escaped for being put inside a SOAP envelope
     */
    fun build(media: CastMedia, itemId: String = "0"): String {
        val title = XmlUtils.escape(media.title)
        val uploader = XmlUtils.escape(media.uploader)
        val duration = if (media.durationSeconds > 0) {
            " duration=\"${UpnpTime.format(media.durationSeconds)}.000\""
        } else {
            ""
        }
        val albumArt = media.thumbnailUrl?.let {
            "<upnp:albumArtURI>${XmlUtils.escape(it)}</upnp:albumArtURI>"
        }.orEmpty()

        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
            " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
            " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"" +
            " xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
            "<item id=\"${XmlUtils.escape(itemId)}\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>$title</dc:title>" +
            "<dc:creator>$uploader</dc:creator>" +
            "<upnp:artist>$uploader</upnp:artist>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            albumArt +
            "<res protocolInfo=\"${XmlUtils.escape(protocolInfo(media.mimeType))}\"$duration>" +
            XmlUtils.escape(media.url) +
            "</res>" +
            "</item>" +
            "</DIDL-Lite>"
    }
}
