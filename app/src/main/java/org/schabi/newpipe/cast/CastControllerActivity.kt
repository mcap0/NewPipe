/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.databinding.ActivityCastControllerBinding
import org.schabi.newpipe.databinding.ItemCastQueueBinding
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Remote control for the video playing on the TV. The session itself lives in [CastService],
 * so this screen can be closed and opened again from the bar in the main screen, the
 * notification or the "Cast to TV" button at any time.
 */
class CastControllerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCastControllerBinding
    private var isUserSeeking = false
    private var shownQueue: Pair<List<CastQueueEntry>, Int>? = null
    private var state = CastState()

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
        binding.castNext.setOnClickListener { CastService.next(this) }
        binding.castVolumeUp.setOnClickListener { CastService.volumeUp(this) }
        binding.castVolumeDown.setOnClickListener { CastService.volumeDown(this) }
        binding.castQuality.setOnClickListener { chooseQuality() }
        binding.castAudioTrack.setOnClickListener { chooseAudioTrack() }
        binding.castSubtitles.setOnClickListener { chooseSubtitles() }
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
        this.state = state
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
        val hasNext = state.queueIndex + 1 < state.queue.size
        binding.castNext.isEnabled = controlsEnabled && hasNext
        binding.castNext.alpha = if (hasNext) 1f else DISABLED_ALPHA

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

        binding.castQuality.isVisible = state.qualities.isNotEmpty()
        binding.castQuality.isEnabled = controlsEnabled
        binding.castQuality.text = qualityText(state)
        binding.castAudioTrack.isVisible = state.audioTracks.isNotEmpty()
        binding.castAudioTrack.isEnabled = controlsEnabled
        binding.castAudioTrack.text = state.audioTracks.getOrNull(state.audioTrack) ?: getString(R.string.audio_track)
        binding.castSubtitles.isVisible = state.subtitles.isNotEmpty()
        binding.castSubtitles.isEnabled = controlsEnabled
        binding.castSubtitles.text = state.subtitles.getOrNull(state.subtitle) ?: getString(R.string.cast_subtitles_off)

        renderQueue(state)
    }

    private fun qualityText(state: CastState): String {
        val current = state.quality.takeIf { it > 0 }?.let { "${it}p" }
        return when {
            !state.autoQuality && current != null -> current
            current != null -> getString(R.string.cast_quality_auto_current, current)
            else -> getString(R.string.cast_quality_auto)
        }
    }

    private fun renderQueue(state: CastState) {
        val queue = state.queue to state.queueIndex
        if (queue == shownQueue) {
            return
        }
        shownQueue = queue
        binding.castQueueTitle.isVisible = state.queue.size > 1
        binding.castQueueList.isVisible = state.queue.size > 1
        binding.castQueueList.removeAllViews()
        if (state.queue.size <= 1) {
            return
        }
        state.queue.forEachIndexed { index, entry ->
            val row = ItemCastQueueBinding.inflate(layoutInflater, binding.castQueueList, false)
            val isCurrent = index == state.queueIndex
            row.castQueueItemTitle.text = entry.title
            row.castQueueItemTitle.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            row.castQueueItemDetails.text = listOfNotNull(
                entry.uploader.takeIf { it.isNotEmpty() },
                entry.durationSeconds.takeIf { it > 0 }?.let { Localization.getDurationString(it) }
            ).joinToString(" · ")
            row.castQueuePlaying.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            row.castQueueRemove.isVisible = !isCurrent
            row.castQueueRemove.setOnClickListener { CastService.remove(this, index) }
            row.root.setOnClickListener {
                if (!isCurrent) {
                    CastService.playIndex(this, index)
                }
            }
            binding.castQueueList.addView(row.root)
        }
    }

    private fun chooseQuality() {
        val qualities = state.qualities
        val names = arrayOf(getString(R.string.cast_quality_auto)) + qualities.map { "${it}p" }
        val checked = if (state.autoQuality) 0 else qualities.indexOf(state.quality) + 1
        AlertDialog.Builder(this)
            .setTitle(R.string.cast_quality)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                CastService.setQuality(this, if (which == 0) 0 else qualities[which - 1])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseAudioTrack() {
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_track)
            .setSingleChoiceItems(state.audioTracks.toTypedArray(), state.audioTrack) { dialog, which ->
                CastService.setAudioTrack(this, which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseSubtitles() {
        val names = arrayOf(getString(R.string.cast_subtitles_off)) + state.subtitles
        AlertDialog.Builder(this)
            .setTitle(R.string.cast_subtitles)
            .setSingleChoiceItems(names, state.subtitle + 1) { dialog, which ->
                CastService.setSubtitles(this, which - 1)
                if (which > 0) {
                    Toast.makeText(this, R.string.cast_subtitles_hint, Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val DISABLED_ALPHA = 0.3f
    }
}
