/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Entry point of "Cast to TV": checks that the video can be cast, then shows the list of TVs,
 * or, while a video is already being cast, offers to play it now or add it to the TV queue.
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
        val item = CastItem(info)
        if (!item.isCastable) {
            CastLog.w(
                "No castable stream for ${info.url}. Available: " +
                    (info.videoStreams + info.videoOnlyStreams).joinToString { describe(it) } +
                    "; audio: " + info.audioStreams.joinToString {
                        "${it.format?.mimeType} ${it.codec} ${it.deliveryMethod}"
                    }
            )
            Toast.makeText(context, R.string.cast_error_no_stream, Toast.LENGTH_LONG).show()
            return
        }
        if (fragment.isStateSaved) {
            return
        }
        if (CastService.isActive) {
            showSessionChoice(fragment, item)
        } else {
            showDevices(fragment, item)
        }
    }

    /** Opens the remote control of the video being cast. */
    @JvmStatic
    fun openController(context: Context) {
        context.startActivity(
            Intent(context, CastControllerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun showDevices(fragment: Fragment, item: CastItem) {
        CastDeviceDialog.newInstance(item).show(fragment.childFragmentManager, CastDeviceDialog.TAG)
    }

    private fun showSessionChoice(fragment: Fragment, item: CastItem) {
        val context = fragment.requireContext()
        val state = CastService.state.value
        val choices = arrayOf(
            context.getString(R.string.cast_play_now),
            context.getString(R.string.cast_add_to_queue),
            context.getString(R.string.cast_open_controller),
            context.getString(R.string.cast_other_tv)
        )
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.cast_playing_on, state?.deviceName.orEmpty()))
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        CastService.enqueue(context, item, playNow = true)
                        openController(context)
                    }

                    1 -> {
                        CastService.enqueue(context, item, playNow = false)
                        Toast.makeText(context, R.string.cast_added_to_queue, Toast.LENGTH_SHORT).show()
                    }

                    2 -> openController(context)

                    else -> if (fragment.isAdded && !fragment.isStateSaved) {
                        showDevices(fragment, item)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun describe(stream: VideoStream) = "${stream.getResolution()} ${stream.format?.mimeType} ${stream.deliveryMethod}" +
        if (stream.isVideoOnly()) " video-only" else ""
}
