/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.CastLogcat
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Settings of "Cast to TV": direct link instead of the local proxy, and the diagnostic log.
 */
class CastSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
        CastLogcat.install()
        requirePreference<Preference>(R.string.cast_log_key).setOnPreferenceClickListener {
            showLog()
            true
        }
    }

    private fun showLog() {
        val context = requireContext()
        val log = CastLog.dump()
        val padding = resources.getDimensionPixelSize(R.dimen.video_item_search_padding)
        val text = TextView(context).apply {
            this.text = log.ifEmpty { getString(R.string.cast_log_empty) }
            typeface = Typeface.MONOSPACE
            textSize = LOG_TEXT_SIZE_SP
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.cast_log_title)
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(R.string.cast_copy_log) { _, _ -> ShareUtils.copyToClipboard(context, CastLog.dumpRecent()) }
            .setNeutralButton(R.string.cast_clear_log) { _, _ -> CastLog.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val LOG_TEXT_SIZE_SP = 11f
    }
}
