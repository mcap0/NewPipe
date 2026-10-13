/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.os.Bundle
import android.view.MenuItem
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.databinding.ActivityCastControllerBinding
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Remote control for the video playing on the TV. The session itself lives in [CastService],
 * so this screen can be closed and opened again from the notification at any time.
 */
class CastControllerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCastControllerBinding
    private var isUserSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityCastControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            setTitle(R.string.cast_to_tv)
        }

        binding.castPlayPause.setOnClickListener { CastService.playPause(this) }
        binding.castStop.setOnClickListener {
            CastService.stop(this)
            finish()
        }
        binding.castVolumeUp.setOnClickListener { CastService.volumeUp(this) }
        binding.castVolumeDown.setOnClickListener { CastService.volumeDown(this) }
        binding.castCopyLog.setOnClickListener { ShareUtils.copyToClipboard(this, CastLog.dump()) }
        binding.castSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.castPosition.text = Localization.getDurationString(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = false
                CastService.seek(this@CastControllerActivity, seekBar.progress.toLong())
            }
        })

        CastService.state.observe(this) { render(it) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render(state: CastState) {
        if (!state.active && state.error == null) {
            // no session (anymore): nothing to control
            finish()
            return
        }
        binding.castTitle.text = state.title
        binding.castUploader.text = state.uploader
        binding.castUploader.isVisible = state.uploader.isNotEmpty()
        binding.castDevice.text = getString(R.string.cast_playing_on, state.deviceName)
        binding.castStatus.setText(state.status.text)
        binding.castError.isVisible = state.error != null
        binding.castError.text = state.error

        val controlsEnabled = state.active
        binding.castPlayPause.isEnabled = controlsEnabled
        binding.castPlayPause.setImageResource(
            if (state.status == CastStatus.PLAYING) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.castStop.isEnabled = controlsEnabled

        val duration = state.durationSeconds
        binding.castSeekBar.isEnabled = controlsEnabled && duration > 0
        if (duration > 0) {
            binding.castSeekBar.max = duration.toInt()
            binding.castDuration.text = Localization.getDurationString(duration)
        } else {
            binding.castDuration.text = ""
        }
        if (!isUserSeeking) {
            val position = state.positionSeconds.coerceAtLeast(0)
            binding.castSeekBar.progress = position.toInt()
            binding.castPosition.text = Localization.getDurationString(position)
        }

        binding.castVolumeControls.isVisible = state.hasVolumeControl
        binding.castVolumeUp.isEnabled = controlsEnabled
        binding.castVolumeDown.isEnabled = controlsEnabled
        binding.castVolume.text = getString(R.string.cast_volume, state.volume)
    }
}
