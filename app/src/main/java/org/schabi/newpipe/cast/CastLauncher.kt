/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.widget.Toast
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.CastMedia
import org.schabi.newpipe.cast.dlna.CastStreamCandidate
import org.schabi.newpipe.cast.dlna.CastStreamSelector
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Entry point of "Cast to TV": chooses the stream and shows the list of TVs.
 */
object CastLauncher {

    /** @return true if casting makes sense for this kind of stream */
    @JvmStatic
    fun isSupported(streamType: StreamType): Boolean = streamType == StreamType.VIDEO_STREAM

    /**
     * Shows the dialog to choose a TV, or a message if the video cannot be cast.
     */
    @JvmStatic
    fun show(fragment: Fragment, info: StreamInfo) {
        CastLogcat.install()
        val context = fragment.context ?: return
        val media = buildMedia(info)
        if (media == null) {
            CastLog.w(
                "No castable stream for ${info.url}. Available: " +
                    info.videoStreams.joinToString { describe(it) }
            )
            Toast.makeText(context, R.string.cast_error_no_stream, Toast.LENGTH_LONG).show()
            return
        }
        if (fragment.isStateSaved) {
            return
        }
        CastDeviceDialog.newInstance(media, info.serviceId, info.url)
            .show(fragment.childFragmentManager, CastDeviceDialog.TAG)
    }

    /**
     * @return what to send to the TV, with the original URL of the chosen stream, or null if
     * there is no suitable stream
     */
    fun buildMedia(info: StreamInfo): CastMedia? {
        val candidates = info.videoStreams.map { toCandidate(it) }
        val chosen = CastStreamSelector.select(candidates) ?: return null
        CastLog.d("Chosen stream for \"${info.name}\": ${chosen.height}p ${chosen.mimeType}")
        return CastMedia(
            url = chosen.url,
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            mimeType = CastStreamSelector.MP4_MIME_TYPE,
            durationSeconds = info.duration
        )
    }

    private fun toCandidate(stream: VideoStream) = CastStreamCandidate(
        url = if (stream.isUrl) stream.content.orEmpty() else "",
        mimeType = stream.format?.mimeType,
        height = stream.height.takeIf { it > 0 }
            ?: stream.getResolution().substringBefore('p').toIntOrNull()
            ?: 0,
        isVideoOnly = stream.isVideoOnly(),
        isProgressiveHttp = stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    )

    private fun describe(stream: VideoStream) = "${stream.getResolution()} ${stream.format?.mimeType} ${stream.deliveryMethod}" +
        if (stream.isVideoOnly()) " video-only" else ""
}
