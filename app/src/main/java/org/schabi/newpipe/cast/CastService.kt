/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.CastMedia
import org.schabi.newpipe.cast.dlna.DashManifest
import org.schabi.newpipe.cast.dlna.DlnaClient
import org.schabi.newpipe.cast.dlna.DlnaDevice
import org.schabi.newpipe.cast.dlna.LocalNetwork
import org.schabi.newpipe.cast.dlna.TransportState
import org.schabi.newpipe.cast.dlna.UpnpException
import org.schabi.newpipe.cast.proxy.CastProxyServer

/** What the TV is doing, as shown in the controller and in the notification. */
enum class CastStatus(@StringRes val text: Int) {
    CONNECTING(R.string.cast_status_connecting),
    BUFFERING(R.string.cast_status_buffering),
    PLAYING(R.string.cast_status_playing),
    PAUSED(R.string.cast_status_paused),
    STOPPED(R.string.cast_status_stopped),
    ERROR(R.string.cast_status_error)
}

/**
 * State of the current cast session, published by [CastService.state].
 *
 * @param active false when there is no session (the controller then closes itself)
 * @param error a user readable error, null if everything is fine
 */
data class CastState(
    val active: Boolean = false,
    val deviceName: String = "",
    val title: String = "",
    val uploader: String = "",
    val status: CastStatus = CastStatus.CONNECTING,
    val positionSeconds: Long = -1,
    val durationSeconds: Long = -1,
    val hasVolumeControl: Boolean = false,
    val volume: Int = -1,
    val error: String? = null
)

/**
 * Foreground service which owns a cast session: it runs the local proxy, sends the video to
 * the TV, polls the TV every second for its state and handles the commands from the controller
 * and the notification. Being a foreground service, the session continues when NewPipe is in
 * the background.
 *
 * All the network work happens on a single worker thread, so commands are executed in order.
 */
class CastService : Service() {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastService")
    }

    // only accessed on the worker thread
    private var client: DlnaClient? = null
    private var proxy: CastProxyServer? = null
    private var polling: ScheduledFuture<*>? = null
    private var hasPlayed = false
    private var stoppedPolls = 0
    private var failedPolls = 0
    private var sessionId = 0

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedStatus: CastStatus? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CastLogcat.install()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val device = intent.getSerializableExtra(EXTRA_DEVICE) as? DlnaDevice

                @Suppress("DEPRECATION")
                val media = intent.getSerializableExtra(EXTRA_MEDIA) as? CastMedia
                if (device == null || media == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                publish(initialState(device, media))
                startInForeground()
                worker.execute { startSession(device, media) }
            }

            ACTION_PLAY_PAUSE -> worker.execute { playPause() }

            ACTION_SEEK -> {
                val position = intent.getLongExtra(EXTRA_POSITION, -1)
                worker.execute { seek(position) }
            }

            ACTION_VOLUME_UP -> worker.execute { changeVolume(+VOLUME_STEP) }

            ACTION_VOLUME_DOWN -> worker.execute { changeVolume(-VOLUME_STEP) }

            ACTION_STOP -> worker.execute { stopSession(stopTv = true) }

            else -> if (!currentState.active) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.execute { endSession(keepState = currentState.error != null && !currentState.active) }
        worker.shutdown()
        super.onDestroy()
    }

    // /////////////////////////////////////////////////////////////////////////
    // Session (worker thread)
    // /////////////////////////////////////////////////////////////////////////

    private fun startSession(device: DlnaDevice, media: CastMedia) {
        endSession(keepState = true)
        val session = ++sessionId
        hasPlayed = false
        stoppedPolls = 0
        failedPolls = 0
        CastLog.d("Starting session #$session on \"${device.friendlyName}\" for \"${media.title}\"")
        acquireLocks()

        val castUrl = try {
            prepareUrl(media)
        } catch (e: Exception) {
            CastLog.e("Could not prepare the video URL", e)
            fail(getString(R.string.cast_error_no_wifi))
            return
        }

        val dlna = DlnaClient(device)
        client = dlna
        try {
            // Some TVs refuse a new URI while they are playing something else
            val state = runCatching { dlna.getTransportInfo() }.getOrNull()
            if (state == TransportState.PLAYING || state == TransportState.PAUSED) {
                runCatching { dlna.stop() }
            }
            dlna.setAvTransportUri(media.copy(url = castUrl))
            playWithRetries(dlna)
        } catch (e: UpnpException) {
            val error = "${e.errorCode} ${e.errorDescription}"
            fail(
                if (media.dash != null) {
                    getString(R.string.cast_error_dash_refused, error)
                } else {
                    getString(R.string.cast_error_tv, error)
                }
            )
            return
        } catch (e: Exception) {
            CastLog.e("Could not start playback", e)
            fail(getString(R.string.cast_error_unreachable))
            return
        }

        if (dlna.hasVolumeControl) {
            val volume = runCatching { dlna.getVolume() }.getOrDefault(-1)
            publish(currentState.copy(hasVolumeControl = volume >= 0, volume = volume))
        }
        publish(currentState.copy(status = CastStatus.BUFFERING))
        polling = worker.scheduleWithFixedDelay({ poll(session) }, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * @return the URL to give to the TV: through the local proxy, or the direct link if the
     * user chose so in the settings. DASH manifests always go through the proxy, which serves
     * them.
     */
    private fun prepareUrl(media: CastMedia): String {
        val directLink = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.cast_direct_link_key), false)
        if (directLink && media.dash == null) {
            CastLog.d("Sending the direct link to the TV (proxy disabled in the settings)")
            return media.url
        }
        val local = LocalNetwork.find() ?: throw IllegalStateException("No local network address")
        val server = CastProxyServer(OkHttpUpstream(), local.address)
        server.start()
        proxy = server

        val dash = media.dash
        if (dash != null) {
            val videoUrl = server.register(dash.video.url, dash.video.mimeType)
            val audioUrl = server.register(dash.audio.url, dash.audio.mimeType)
            val manifest = DashManifest.build(dash, videoUrl, audioUrl, media.durationSeconds)
            CastLog.d("DASH manifest for the TV:\n$manifest")
            val url = server.registerContent(manifest.toByteArray(Charsets.UTF_8), DashManifest.MIME_TYPE)
            CastLog.d("Proxy manifest URL for the TV: $url")
            return url
        }

        val url = server.register(media.url, media.mimeType)
        CastLog.d("Proxy URL for the TV: $url")
        return url
    }

    /** Right after SetAVTransportURI some TVs answer Play with 701 until they are ready. */
    private fun playWithRetries(dlna: DlnaClient) {
        var attempt = 0
        while (true) {
            try {
                dlna.play()
                return
            } catch (e: UpnpException) {
                if (e.errorCode != ERROR_TRANSITION_NOT_AVAILABLE || ++attempt >= PLAY_ATTEMPTS) {
                    throw e
                }
                CastLog.d("Play not possible yet, retrying in 1 second")
                Thread.sleep(1000)
            }
        }
    }

    private fun poll(session: Int) {
        val dlna = client ?: return
        if (session != sessionId) {
            return
        }
        try {
            val transport = dlna.getTransportInfo()
            val position = dlna.getPositionInfo()
            failedPolls = 0

            val status = when (transport) {
                TransportState.PLAYING -> CastStatus.PLAYING
                TransportState.PAUSED -> CastStatus.PAUSED
                TransportState.TRANSITIONING -> CastStatus.BUFFERING
                TransportState.STOPPED, TransportState.NO_MEDIA -> CastStatus.STOPPED
                TransportState.UNKNOWN -> currentState.status
            }
            if (status == CastStatus.PLAYING || status == CastStatus.PAUSED) {
                hasPlayed = true
            }
            publish(
                currentState.copy(
                    status = if (!hasPlayed && status == CastStatus.STOPPED) CastStatus.BUFFERING else status,
                    positionSeconds = position.positionSeconds,
                    durationSeconds = position.durationSeconds.takeIf { it > 0 } ?: currentState.durationSeconds,
                    error = null
                )
            )

            // the video ended, or the TV was used for something else
            stoppedPolls = if (hasPlayed && status == CastStatus.STOPPED) stoppedPolls + 1 else 0
            if (stoppedPolls >= STOPPED_POLLS_BEFORE_END) {
                CastLog.d("The TV stopped playing, ending the session")
                stopSession(stopTv = false)
            }
        } catch (e: Exception) {
            failedPolls++
            if (failedPolls >= FAILED_POLLS_BEFORE_ERROR) {
                fail(getString(R.string.cast_error_unreachable))
            }
        }
    }

    private fun playPause() {
        val dlna = client ?: return
        try {
            if (currentState.status == CastStatus.PLAYING) {
                dlna.pause()
                publish(currentState.copy(status = CastStatus.PAUSED))
            } else {
                dlna.play()
                publish(currentState.copy(status = CastStatus.PLAYING))
            }
        } catch (e: Exception) {
            showCommandError(e)
        }
    }

    private fun seek(positionSeconds: Long) {
        val dlna = client ?: return
        if (positionSeconds < 0) {
            return
        }
        try {
            dlna.seek(positionSeconds)
            publish(currentState.copy(positionSeconds = positionSeconds))
        } catch (e: Exception) {
            showCommandError(e)
        }
    }

    private fun changeVolume(delta: Int) {
        val dlna = client ?: return
        if (!dlna.hasVolumeControl) {
            return
        }
        try {
            val current = currentState.volume.takeIf { it >= 0 } ?: dlna.getVolume()
            val volume = (current + delta).coerceIn(0, MAX_VOLUME)
            dlna.setVolume(volume)
            publish(currentState.copy(volume = volume, hasVolumeControl = true))
        } catch (e: Exception) {
            showCommandError(e)
        }
    }

    private fun showCommandError(e: Exception) {
        val message = if (e is UpnpException) {
            getString(R.string.cast_error_tv, "${e.errorCode} ${e.errorDescription}")
        } else {
            getString(R.string.cast_error_unreachable)
        }
        publish(currentState.copy(error = message))
    }

    private fun fail(message: String) {
        CastLog.e("Session failed: $message")
        endSession(keepState = true)
        // not active anymore: the controller shows the error and disables the buttons
        publish(currentState.copy(active = false, status = CastStatus.ERROR, error = message))
        stopForegroundAndSelf()
    }

    private fun stopSession(stopTv: Boolean) {
        if (stopTv) {
            client?.let { runCatching { it.stop() } }
        }
        endSession()
        stopForegroundAndSelf()
    }

    /** Releases everything; [keepState] keeps the state visible, e.g. to show an error. */
    private fun endSession(keepState: Boolean = false) {
        polling?.cancel(false)
        polling = null
        client = null
        proxy?.stop()
        proxy = null
        releaseLocks()
        if (!keepState) {
            publish(CastState(active = false))
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Locks: keep Wi-Fi and CPU awake while the TV downloads through the proxy
    // /////////////////////////////////////////////////////////////////////////

    @SuppressLint("WakelockTimeout") // released when the session ends
    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { it.acquire() }
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NewPipe:$TAG")
            ?.apply { setReferenceCounted(false) }
            ?.also { it.acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // /////////////////////////////////////////////////////////////////////////
    // Notification
    // /////////////////////////////////////////////////////////////////////////

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(currentState), type)
        lastNotifiedStatus = currentState.status
    }

    private fun stopForegroundAndSelf() {
        lastNotifiedStatus = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(state: CastState) {
        if (!state.active || state.status == lastNotifiedStatus || lastNotifiedStatus == null) {
            return
        }
        lastNotifiedStatus = state.status
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not update the notification", e)
        }
    }

    private fun buildNotification(state: CastState): Notification {
        val contentIntent = PendingIntentCompat.getActivity(
            this,
            0,
            Intent(this, CastControllerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            0,
            false
        )
        val isPlaying = state.status == CastStatus.PLAYING
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(state.title)
            .setContentText(getString(R.string.cast_notification_text, getString(state.status.text), state.deviceName))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                getString(R.string.cast_play_pause),
                commandIntent(ACTION_PLAY_PAUSE, 1)
            )
            .addAction(R.drawable.ic_stop, getString(R.string.cast_stop), commandIntent(ACTION_STOP, 2))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun commandIntent(action: String, requestCode: Int) = PendingIntentCompat.getService(
        this,
        requestCode,
        Intent(this, CastService::class.java).setAction(action),
        0,
        false
    )

    private fun publish(state: CastState) {
        currentState = state
        mutableState.postValue(state)
        updateNotification(state)
    }

    companion object {
        private const val TAG = "CastService"
        private const val NOTIFICATION_ID = 0xCA57
        private const val ACTION_START = "org.schabi.newpipe.cast.START"
        private const val ACTION_PLAY_PAUSE = "org.schabi.newpipe.cast.PLAY_PAUSE"
        private const val ACTION_SEEK = "org.schabi.newpipe.cast.SEEK"
        private const val ACTION_VOLUME_UP = "org.schabi.newpipe.cast.VOLUME_UP"
        private const val ACTION_VOLUME_DOWN = "org.schabi.newpipe.cast.VOLUME_DOWN"
        private const val ACTION_STOP = "org.schabi.newpipe.cast.STOP"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_MEDIA = "media"
        private const val EXTRA_POSITION = "position"

        private const val ERROR_TRANSITION_NOT_AVAILABLE = 701
        private const val PLAY_ATTEMPTS = 4
        private const val STOPPED_POLLS_BEFORE_END = 3
        private const val FAILED_POLLS_BEFORE_ERROR = 8
        private const val VOLUME_STEP = 2
        private const val MAX_VOLUME = 100

        @Volatile
        private var currentState = CastState()
        private val mutableState = MutableLiveData(CastState())

        /** The state of the current session, observed by the controller. */
        val state: LiveData<CastState> = mutableState

        /**
         * Starts casting [media] (with the original URL of the video) to [device].
         */
        @MainThread
        fun start(context: Context, device: DlnaDevice, media: CastMedia) {
            // published right away, so that the controller opened next does not see the old state
            val initial = initialState(device, media)
            currentState = initial
            mutableState.value = initial
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device)
                .putExtra(EXTRA_MEDIA, media)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun initialState(device: DlnaDevice, media: CastMedia) = CastState(
            active = true,
            deviceName = device.friendlyName,
            title = media.title,
            uploader = media.uploader,
            durationSeconds = media.durationSeconds
        )

        fun playPause(context: Context) = command(context, ACTION_PLAY_PAUSE)

        fun stop(context: Context) = command(context, ACTION_STOP)

        fun volumeUp(context: Context) = command(context, ACTION_VOLUME_UP)

        fun volumeDown(context: Context) = command(context, ACTION_VOLUME_DOWN)

        fun seek(context: Context, positionSeconds: Long) {
            command(context, ACTION_SEEK) { putExtra(EXTRA_POSITION, positionSeconds) }
        }

        private fun command(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            if (!currentState.active) {
                return
            }
            context.startService(Intent(context, CastService::class.java).setAction(action).apply(extras))
        }
    }
}
