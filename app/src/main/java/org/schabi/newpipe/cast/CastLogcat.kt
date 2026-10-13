/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.util.Log
import org.schabi.newpipe.cast.dlna.CastLog

/**
 * Forwards [CastLog] to logcat with the tag [CastLog.TAG] ("DlnaCast"), so that the log can
 * also be read with `adb logcat -s DlnaCast`.
 */
object CastLogcat {
    fun install() {
        if (CastLog.sink != null) {
            return
        }
        CastLog.sink = { level, message, error ->
            when (level) {
                'E' -> Log.e(CastLog.TAG, message, error)
                'W' -> Log.w(CastLog.TAG, message, error)
                else -> Log.d(CastLog.TAG, message, error)
            }
        }
    }
}
