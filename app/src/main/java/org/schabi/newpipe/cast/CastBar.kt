/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.CastBarBinding

/**
 * The bar at the bottom of the main screen shown while a video is cast: it opens the remote
 * control again after it was closed.
 */
object CastBar {
    /**
     * @param content the view above the bar, which is shrunk so that the bar does not cover it
     */
    @JvmStatic
    fun bind(activity: AppCompatActivity, bar: CastBarBinding, content: View) {
        bar.root.setOnClickListener { CastLauncher.openController(activity) }
        bar.castBarPlayPause.setOnClickListener { CastService.playPause(activity) }
        CastService.state.observe(activity) { state ->
            val visible = state.active
            if (bar.root.isVisible != visible) {
                bar.root.isVisible = visible
                content.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = if (visible) activity.resources.getDimensionPixelSize(R.dimen.cast_bar_height) else 0
                }
            }
            if (!visible) {
                return@observe
            }
            bar.castBarTitle.text = state.title
            bar.castBarDevice.text = activity.getString(
                R.string.cast_notification_text,
                activity.getString(state.status.text),
                state.deviceName
            )
            bar.castBarPlayPause.setImageResource(
                if (state.status == CastStatus.PLAYING) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
        }
    }
}
