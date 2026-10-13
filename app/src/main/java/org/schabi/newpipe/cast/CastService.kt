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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastChoice
import org.schabi.newpipe.cast.dlna.CastDash
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.CastMedia
import org.schabi.newpipe.cast.dlna.DidlLite
import org.schabi.newpipe.cast.dlna.DlnaClient
import org.schabi.newpipe.cast.dlna.DlnaDevice
import org.schabi.newpipe.cast.dlna.LocalNetwork
import org.schabi.newpipe.cast.dlna.SrtConverter
import org.schabi.newpipe.cast.dlna.TransportState
import org.schabi.newpipe.cast.dlna.TsFormat
import org.schabi.newpipe.cast.dlna.UpnpException
import org.schabi.newpipe.cast.proxy.CastProxyServer
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.util.DependentPreferenceHelper

/** What the TV is doing, as shown in the controller and in the notification. */
enum class CastStatus(@StringRes val text: Int) {
    CONNECTING(R.string.cast_status_connecting),
    BUFFERING(R.string.cast_status_buffering),
    PLAYING(R.string.cast_status_playing),
    PAUSED(R.string.cast_status_paused),
    STOPPED(R.string.cast_status_stopped),
    ERROR(R.string.cast_status_error)
}

/** A video of the queue, as shown in the controller. */
data class CastQueueEntry(val title: String, val uploader: String, val durationSeconds: Long)

/**
 * State of the current cast session, published by [CastService.state].
 *
 * @param active false when there is no session (the controller then closes itself)
 * @param error a user readable error, null if everything is fine
 * @param qualities the resolutions that can be chosen, highest first
 * @param quality the resolution being cast, 0 if unknown
 * @param autoQuality true if the user did not choose a resolution
 * @param audioTracks the names of the audio tracks that can be chosen, empty if only one
 * @param audioTrack the index in [audioTracks] of the track being cast
 * @param subtitles the names of the subtitles that can be chosen
 * @param subtitle the index in [subtitles] of the subtitles sent to the TV, -1 for none
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
    val error: String? = null,
    val queue: List<CastQueueEntry> = emptyList(),
    val queueIndex: Int = 0,
    val qualities: List<Int> = emptyList(),
    val quality: Int = 0,
    val autoQuality: Boolean = true,
    val audioTracks: List<String> = emptyList(),
    val audioTrack: Int = 0,
    val subtitles: List<String> = emptyList(),
    val subtitle: Int = -1
)

/**
 * Foreground service which owns a cast session: it runs the local proxy, sends the videos of
 * the queue to the TV one after the other, polls the TV every second for its state and handles
 * the commands from the controller and the notification. Being a foreground service, the
 * session continues when NewPipe is in the background.
 *
 * The position reached is saved in the history like for the local player, so that the video
 * continues from there, on the TV or on the phone.
 *
 * All the network work happens on a single worker thread, so commands are executed in order.
 */
class CastService : Service() {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastService")
    }

    // only accessed on the worker thread
    private var device: DlnaDevice? = null
    private var client: DlnaClient? = null
    private var proxy: CastProxyServer? = null
    private var sinkProtocolInfo: String? = null
    private var sinkProtocolInfoLoaded = false
    private var polling: ScheduledFuture<*>? = null
    private var hasPlayed = false
    private var stoppedPolls = 0
    private var failedPolls = 0
    private var polls = 0
    private var sessionId = 0

    private val queue = ArrayList<CastItem>()
    private var queueIndex = 0

    // the choices of the user, kept for the next videos of the queue
    private var wantedHeight: Int? = null
    private var wantedAudioTrackId: String? = null
    private var wantedSubtitles: String? = null

    // what is being cast
    private var currentChoice: CastChoice? = null
    private var currentSubtitles: SubtitlesStream? = null

    // set for live MPEG-TS sessions, which seek by restarting the stream at another time
    private var liveMedia: CastMedia? = null
    private var liveDash: CastDash? = null
    private var liveFormat: TsFormat? = null

    // the subtitles sent to the TV, as SRT
    private var currentSrt: String? = null
    private var positionOffset = 0L

    // position to seek to once the TV plays a video which does not start at the beginning
    private var pendingSeek = 0L

    // last position seen while playing, saved in the history
    private var lastPosition = -1L

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedStatus: CastStatus? = null
    private var lastNotifiedTitle: String? = null

    private val history by lazy { HistoryRecordManager(applicationContext) }

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
                val item = takeItem(intent)
                if (device == null || item == null) {
                    if (!currentState.active) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                publish(initialState(device, item))
                startInForeground()
                worker.execute { startSession(device, item) }
            }

            ACTION_ENQUEUE -> {
                val item = takeItem(intent)
                val playNow = intent.getBooleanExtra(EXTRA_PLAY_NOW, false)
                if (item != null) {
                    worker.execute { enqueue(item, playNow) }
                }
            }

            ACTION_PLAY_PAUSE -> worker.execute { playPause() }

            ACTION_SEEK -> {
                val position = intent.getLongExtra(EXTRA_POSITION, -1)
                worker.execute { seek(position) }
            }

            ACTION_NEXT -> worker.execute { playIndex(queueIndex + 1) }

            ACTION_PLAY_INDEX -> {
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                worker.execute { playIndex(index) }
            }

            ACTION_REMOVE -> {
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                worker.execute { remove(index) }
            }

            ACTION_SET_QUALITY -> {
                val height = intent.getIntExtra(EXTRA_INDEX, 0)
                worker.execute { setQuality(height.takeIf { it > 0 }) }
            }

            ACTION_SET_AUDIO_TRACK -> {
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                worker.execute { setAudioTrack(index) }
            }

            ACTION_SET_SUBTITLES -> {
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                worker.execute { setSubtitles(index) }
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

    private fun startSession(device: DlnaDevice, item: CastItem) {
        endSession(keepState = true)
        val session = ++sessionId
        CastLog.d("Starting session #$session on \"${device.friendlyName}\" for \"${item.title}\"")
        acquireLocks()

        this.device = device
        client = DlnaClient(device)
        sinkProtocolInfo = null
        sinkProtocolInfoLoaded = false
        queue.clear()
        queue.add(item)
        queueIndex = 0

        try {
            val local = LocalNetwork.find() ?: throw IllegalStateException("No local network address")
            proxy = CastProxyServer(OkHttpUpstream(), local.address).also { it.start() }
        } catch (e: Exception) {
            CastLog.e("Could not start the local proxy", e)
            fail(getString(R.string.cast_error_no_wifi))
            return
        }

        if (!playCurrent(startSeconds = null)) {
            return
        }

        val dlna = client ?: return
        if (dlna.hasVolumeControl) {
            val volume = runCatching { dlna.getVolume() }.getOrDefault(-1)
            publish(currentState.copy(hasVolumeControl = volume >= 0, volume = volume))
        }
        polling = worker.scheduleWithFixedDelay({ poll(session) }, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * Sends the current video of the queue to the TV.
     *
     * @param startSeconds where to start, null to continue from the position saved in the history
     * @param isNewVideo false when the same video is sent again with other streams
     * @return false if the session failed
     */
    private fun playCurrent(startSeconds: Long?, isNewVideo: Boolean = true): Boolean {
        val dlna = client ?: return false
        val server = proxy ?: return false
        val item = queue.getOrNull(queueIndex) ?: return false
        hasPlayed = false
        stoppedPolls = 0
        failedPolls = 0
        pendingSeek = 0
        liveMedia = null
        liveDash = null
        liveFormat = null
        currentSrt = null
        positionOffset = 0

        val handOff = item.handOffSeconds
        item.handOffSeconds = null
        val start = (startSeconds ?: handOff ?: savedPosition(item)).coerceAtLeast(0)
        lastPosition = start
        if (isNewVideo) {
            markAsViewed(item)
        }

        val choice = item.choose(wantedHeight, wantedAudioTrackId)
        if (choice == null) {
            fail(getString(R.string.cast_error_no_stream))
            return false
        }
        currentChoice = choice
        val media = item.media(choice)
        currentSubtitles = item.findSubtitles(wantedSubtitles)
        currentSrt = currentSubtitles?.let { loadSrt(it) }
        CastLog.d(
            "Casting \"${item.title}\" (${queueIndex + 1}/${queue.size}) from ${start}s: " +
                (choice.single?.let { "${it.height}p MP4" } ?: choice.dash?.let { "${it.video.height}p + ${it.audio.codecs} muxed" }) +
                (currentSubtitles?.let { ", subtitles ${it.languageTag}" } ?: "")
        )
        publish(stateForCurrent(item).copy(status = CastStatus.CONNECTING, positionSeconds = start, error = null))

        val castMedia = prepareMedia(media, dlna, server, start)
        try {
            // Some TVs refuse a new URI while they are playing something else
            val state = runCatching { dlna.getTransportInfo() }.getOrNull()
            if (state == TransportState.PLAYING || state == TransportState.PAUSED) {
                runCatching { dlna.stop() }
            }
            dlna.setAvTransportUri(castMedia)
            playWithRetries(dlna)
        } catch (e: UpnpException) {
            val error = "${e.errorCode} ${e.errorDescription}"
            fail(
                if (media.dash != null) {
                    getString(R.string.cast_error_live_refused, error)
                } else {
                    getString(R.string.cast_error_tv, error)
                }
            )
            return false
        } catch (e: Exception) {
            CastLog.e("Could not start playback", e)
            fail(getString(R.string.cast_error_unreachable))
            return false
        }
        publish(currentState.copy(status = CastStatus.BUFFERING))
        return true
    }

    /**
     * @return what to give to the TV: the URL through the local proxy, or the direct link if the
     * user chose so in the settings. Separate video and audio streams always go through the
     * proxy, which muxes them into MPEG-TS.
     */
    private fun prepareMedia(
        media: CastMedia,
        dlna: DlnaClient,
        server: CastProxyServer,
        startSeconds: Long
    ): CastMedia {
        val dash = media.dash
        if (dash != null) {
            val format = TsFormat.choose(sinkProtocolInfo(dlna))
            liveDash = dash
            liveFormat = format
            liveMedia = media.copy(mimeType = format.mimeType, dlnaFeatures = format.dlnaFeatures)
            return liveMediaAt(startSeconds, server)
        }

        pendingSeek = startSeconds
        val subtitleUrl = currentSrt?.let { registerSrt(it, 0, server) }
        val directLink = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.cast_direct_link_key), false)
        if (directLink && subtitleUrl == null) {
            CastLog.d("Sending the direct link to the TV (proxy disabled in the settings)")
            return media
        }
        val url = server.register(media.url, media.mimeType, subtitleUrl)
        CastLog.d("Proxy URL for the TV: $url")
        return media.copy(url = url, subtitleUrl = subtitleUrl)
    }

    private fun sinkProtocolInfo(dlna: DlnaClient): String? {
        if (!sinkProtocolInfoLoaded) {
            sinkProtocolInfoLoaded = true
            sinkProtocolInfo = try {
                dlna.getSinkProtocolInfo()
            } catch (e: Exception) {
                CastLog.w("Could not read the formats supported by the TV: ${e.message}")
                null
            }
            CastLog.d(
                "Video formats declared by the TV:\n" +
                    TsFormat.parse(sinkProtocolInfo).joinToString("\n") { (mime, profile) -> "$mime ${profile ?: "*"}" }
            )
        }
        return sinkProtocolInfo
    }

    /**
     * @return the live MPEG-TS stream starting at [startSeconds]. Its timestamps start at 0, so
     * the subtitles are shifted to match.
     */
    private fun liveMediaAt(startSeconds: Long, server: CastProxyServer): CastMedia {
        val live = liveMedia!!
        val subtitleUrl = currentSrt?.let { registerSrt(it, startSeconds, server) }
        val url = server.registerMux(liveDash!!, liveFormat!!, subtitleUrl)
        CastLog.d("Live MPEG-TS URL for the TV (${live.mimeType}, ${liveFormat?.profile ?: "no profile"}): $url")
        positionOffset = startSeconds
        return live.copy(url = if (startSeconds > 0) "$url?t=$startSeconds" else url, subtitleUrl = subtitleUrl)
    }

    /** Downloads the subtitles and converts them to SRT. */
    private fun loadSrt(subtitles: SubtitlesStream): String? {
        return try {
            val text = DownloaderImpl.getInstance().get(subtitles.content).responseBody()
            SrtConverter.convert(text, CastItem.suffixOf(subtitles).orEmpty())
                ?: throw IllegalArgumentException("Unsupported subtitle format ${CastItem.suffixOf(subtitles)}")
        } catch (e: Exception) {
            CastLog.e("Could not load the subtitles", e)
            null
        }
    }

    /** Serves the subtitles to the TV, starting at [startSeconds]. */
    private fun registerSrt(srt: String, startSeconds: Long, server: CastProxyServer): String {
        val shifted = if (startSeconds > 0) SrtConverter.shift(srt, startSeconds * 1000) else srt
        return server.registerContent(shifted.toByteArray(Charsets.UTF_8), DidlLite.SRT_MIME_TYPE, "srt")
            .also { CastLog.d("Subtitles for the TV: $it") }
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

            if (status == CastStatus.PLAYING && pendingSeek > 0) {
                // TVs ignore seeks before they play, so the saved position is reached now
                val target = pendingSeek
                pendingSeek = 0
                CastLog.d("Continuing from ${target}s")
                runCatching { dlna.seek(target) }.onFailure { CastLog.w("Could not seek to ${target}s: ${it.message}") }
                publish(currentState.copy(positionSeconds = target))
                return
            }

            val positionSeconds = position.positionSeconds.takeIf { it >= 0 }?.plus(positionOffset) ?: -1L
            if ((status == CastStatus.PLAYING || status == CastStatus.PAUSED) && positionSeconds >= 0 && pendingSeek == 0L) {
                lastPosition = positionSeconds
            }
            publish(
                currentState.copy(
                    status = if (!hasPlayed && status == CastStatus.STOPPED) CastStatus.BUFFERING else status,
                    positionSeconds = if (pendingSeek > 0) currentState.positionSeconds else positionSeconds,
                    durationSeconds = if (liveMedia != null) {
                        currentState.durationSeconds
                    } else {
                        position.durationSeconds.takeIf { it > 0 } ?: currentState.durationSeconds
                    },
                    error = null
                )
            )

            if (++polls % SAVE_EVERY_POLLS == 0 && status == CastStatus.PLAYING) {
                saveProgress()
            }

            // the video ended, or the TV was used for something else
            stoppedPolls = if (hasPlayed && status == CastStatus.STOPPED) stoppedPolls + 1 else 0
            if (stoppedPolls >= STOPPED_POLLS_BEFORE_END) {
                onTvStopped()
            }
        } catch (e: Exception) {
            failedPolls++
            if (failedPolls >= FAILED_POLLS_BEFORE_ERROR) {
                fail(getString(R.string.cast_error_unreachable))
            }
        }
    }

    /** The TV stopped by itself: at the end of the video the queue continues. */
    private fun onTvStopped() {
        val item = queue.getOrNull(queueIndex)
        val duration = currentState.durationSeconds.takeIf { it > 0 } ?: item?.durationSeconds ?: -1L
        val ended = duration <= 0 || lastPosition >= duration - END_MARGIN_SECONDS
        if (ended && item != null && duration > 0) {
            lastPosition = duration
        }
        if (ended && queueIndex + 1 < queue.size) {
            CastLog.d("The video ended, playing the next one of the queue")
            saveProgress()
            queueIndex++
            playCurrent(startSeconds = null)
            return
        }
        CastLog.d("The TV stopped playing, ending the session")
        stopSession(stopTv = false)
    }

    private fun enqueue(item: CastItem, playNow: Boolean) {
        if (client == null) {
            return
        }
        if (playNow) {
            saveProgress()
            queue.add(queueIndex + 1, item)
            queueIndex++
            playCurrent(startSeconds = null)
        } else {
            queue.add(item)
            CastLog.d("Added \"${item.title}\" to the queue (${queue.size} videos)")
            publish(currentState.copy(queue = queueEntries()))
        }
    }

    private fun playIndex(index: Int) {
        if (client == null || index !in queue.indices || index == queueIndex) {
            return
        }
        saveProgress()
        queueIndex = index
        playCurrent(startSeconds = null)
    }

    private fun remove(index: Int) {
        if (index !in queue.indices || index == queueIndex) {
            return
        }
        queue.removeAt(index)
        if (index < queueIndex) {
            queueIndex--
        }
        publish(currentState.copy(queue = queueEntries(), queueIndex = queueIndex))
    }

    private fun setQuality(height: Int?) {
        if (client == null) {
            return
        }
        wantedHeight = height
        restartCurrent()
    }

    private fun setAudioTrack(index: Int) {
        val item = queue.getOrNull(queueIndex) ?: return
        val trackId = item.audioTrackIds.getOrNull(index) ?: return
        // the first track is the original one, which the progressive MP4 streams have too
        wantedAudioTrackId = if (index == 0) null else trackId
        restartCurrent()
    }

    private fun setSubtitles(index: Int) {
        val item = queue.getOrNull(queueIndex) ?: return
        wantedSubtitles = item.subtitles.getOrNull(index)?.let { CastItem.subtitleKey(it) }
        restartCurrent()
    }

    /** Sends the current video again with the new choices, from the same position. */
    private fun restartCurrent() {
        if (client == null) {
            return
        }
        val position = lastPosition.coerceAtLeast(0)
        saveProgress()
        playCurrent(startSeconds = position, isNewVideo = false)
    }

    private fun playPause() {
        val dlna = client ?: return
        try {
            if (currentState.status == CastStatus.PLAYING) {
                dlna.pause()
                publish(currentState.copy(status = CastStatus.PAUSED))
                saveProgress()
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
        pendingSeek = 0
        val server = proxy ?: return
        if (liveMedia != null) {
            // a live stream cannot be seeked: the TV gets a new one starting at that time
            try {
                dlna.setAvTransportUri(liveMediaAt(positionSeconds, server))
                playWithRetries(dlna)
                lastPosition = positionSeconds
                hasPlayed = false
                stoppedPolls = 0
                publish(currentState.copy(positionSeconds = positionSeconds, status = CastStatus.BUFFERING))
            } catch (e: Exception) {
                showCommandError(e)
            }
            return
        }
        try {
            dlna.seek(positionSeconds)
            lastPosition = positionSeconds
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
        if (client != null) {
            saveProgress()
        }
        polling?.cancel(false)
        polling = null
        client = null
        device = null
        proxy?.stop()
        proxy = null
        queue.clear()
        queueIndex = 0
        releaseLocks()
        if (!keepState) {
            publish(CastState(active = false))
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Queue and history
    // /////////////////////////////////////////////////////////////////////////

    private fun queueEntries() = queue.map { CastQueueEntry(it.title, it.uploader, it.durationSeconds) }

    /** @return the state showing the current video of the queue and what can be chosen for it */
    private fun stateForCurrent(item: CastItem): CastState {
        val choice = currentChoice
        val audioTrackId = choice?.dash?.let { wantedAudioTrackId } ?: item.audioTrackIds.firstOrNull()
        return currentState.copy(
            active = true,
            deviceName = device?.friendlyName.orEmpty(),
            title = item.title,
            uploader = item.uploader,
            durationSeconds = item.durationSeconds,
            queue = queueEntries(),
            queueIndex = queueIndex,
            qualities = item.qualities,
            quality = choice?.height ?: 0,
            autoQuality = wantedHeight == null,
            audioTracks = item.audioTrackIds.map { item.audioTrackName(this, it) },
            audioTrack = item.audioTrackIds.indexOf(audioTrackId).coerceAtLeast(0),
            subtitles = item.subtitles.map { item.subtitleName(this, it) },
            subtitle = currentSubtitles?.let { item.subtitles.indexOf(it) } ?: -1
        )
    }

    private fun isWatchHistoryEnabled() = PreferenceManager.getDefaultSharedPreferences(this)
        .getBoolean(getString(R.string.enable_watch_history_key), true)

    /** @return the position saved in the history for [item], or 0 */
    private fun savedPosition(item: CastItem): Long {
        if (!DependentPreferenceHelper.getResumePlaybackEnabled(this)) {
            return 0
        }
        return try {
            val state = history.loadStreamState(item.info).blockingGet()
            if (state == null || state.isFinished(item.durationSeconds)) 0 else state.progressMillis / 1000
        } catch (e: Exception) {
            CastLog.w("Could not load the saved position: ${e.message}")
            0
        }
    }

    /** Saves the position reached on the TV, like the local player does. */
    private fun saveProgress() {
        val item = queue.getOrNull(queueIndex) ?: return
        val position = lastPosition
        if (position <= 0 || !hasPlayed || !isWatchHistoryEnabled()) {
            return
        }
        try {
            history.saveStreamState(item.info, position * 1000).blockingAwait()
        } catch (e: Exception) {
            CastLog.w("Could not save the position: ${e.message}")
        }
    }

    /** Adds the video to the watch history, as if it was played in NewPipe. */
    private fun markAsViewed(item: CastItem) {
        try {
            history.onViewed(item.info).blockingGet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not add the cast video to the history", e)
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
        lastNotifiedTitle = currentState.title
    }

    private fun stopForegroundAndSelf() {
        lastNotifiedStatus = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(state: CastState) {
        if (!state.active || lastNotifiedStatus == null ||
            (state.status == lastNotifiedStatus && state.title == lastNotifiedTitle)
        ) {
            return
        }
        lastNotifiedStatus = state.status
        lastNotifiedTitle = state.title
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
            Intent(this, CastControllerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
            .apply {
                if (state.queueIndex + 1 < state.queue.size) {
                    addAction(R.drawable.ic_next, getString(R.string.cast_next), commandIntent(ACTION_NEXT, 3))
                }
            }
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
        private const val ACTION_ENQUEUE = "org.schabi.newpipe.cast.ENQUEUE"
        private const val ACTION_PLAY_PAUSE = "org.schabi.newpipe.cast.PLAY_PAUSE"
        private const val ACTION_SEEK = "org.schabi.newpipe.cast.SEEK"
        private const val ACTION_NEXT = "org.schabi.newpipe.cast.NEXT"
        private const val ACTION_PLAY_INDEX = "org.schabi.newpipe.cast.PLAY_INDEX"
        private const val ACTION_REMOVE = "org.schabi.newpipe.cast.REMOVE"
        private const val ACTION_SET_QUALITY = "org.schabi.newpipe.cast.SET_QUALITY"
        private const val ACTION_SET_AUDIO_TRACK = "org.schabi.newpipe.cast.SET_AUDIO_TRACK"
        private const val ACTION_SET_SUBTITLES = "org.schabi.newpipe.cast.SET_SUBTITLES"
        private const val ACTION_VOLUME_UP = "org.schabi.newpipe.cast.VOLUME_UP"
        private const val ACTION_VOLUME_DOWN = "org.schabi.newpipe.cast.VOLUME_DOWN"
        private const val ACTION_STOP = "org.schabi.newpipe.cast.STOP"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_PLAY_NOW = "play_now"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_INDEX = "index"

        private const val ERROR_TRANSITION_NOT_AVAILABLE = 701
        private const val PLAY_ATTEMPTS = 4
        private const val STOPPED_POLLS_BEFORE_END = 3
        private const val FAILED_POLLS_BEFORE_ERROR = 8
        private const val SAVE_EVERY_POLLS = 5
        private const val END_MARGIN_SECONDS = 15
        private const val VOLUME_STEP = 2
        private const val MAX_VOLUME = 100

        @Volatile
        private var currentState = CastState()
        private val mutableState = MutableLiveData(CastState())

        /**
         * The videos handed to the service: a [StreamInfo] is too big for an [Intent], and the
         * service runs in the same process.
         */
        private val pendingItems = ConcurrentHashMap<Int, CastItem>()
        private val nextItemKey = AtomicInteger()

        /** The state of the current session, observed by the controller. */
        @JvmStatic
        val state: LiveData<CastState> = mutableState

        /** @return true while a video is being cast */
        @JvmStatic
        val isActive: Boolean
            get() = currentState.active

        /**
         * Starts casting [item] to [device], replacing the current session if any.
         */
        @MainThread
        fun start(context: Context, device: DlnaDevice, item: CastItem) {
            handOffFromPhone(item)
            // published right away, so that the controller opened next does not see the old state
            val initial = initialState(device, item)
            currentState = initial
            mutableState.value = initial
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device)
            ContextCompat.startForegroundService(context, putItem(intent, item))
        }

        /**
         * Adds [item] to the queue of the current session.
         *
         * @param playNow true to play it right away, false to play it after the others
         */
        @MainThread
        fun enqueue(context: Context, item: CastItem, playNow: Boolean) {
            if (playNow && currentState.active) {
                handOffFromPhone(item)
            }
            command(context, ACTION_ENQUEUE) {
                putItem(this, item)
                putExtra(EXTRA_PLAY_NOW, playNow)
            }
        }

        /**
         * Pauses the player of the phone, so that the video does not play twice, and continues
         * on the TV from where the phone was if it was playing the same video.
         */
        private fun handOffFromPhone(item: CastItem) {
            try {
                val position = PlayerHolder.getInstance().pauseForCasting(item.info.serviceId, item.info.url)
                if (position >= 0) {
                    item.handOffSeconds = position / 1000
                    CastLog.d("Continuing on the TV from the phone player at ${position / 1000}s")
                }
            } catch (e: Exception) {
                CastLog.w("Could not pause the phone player: ${e.message}")
            }
        }

        private fun putItem(intent: Intent, item: CastItem): Intent {
            val key = nextItemKey.incrementAndGet()
            pendingItems[key] = item
            return intent.putExtra(EXTRA_ITEM, key)
        }

        private fun takeItem(intent: Intent): CastItem? = pendingItems.remove(intent.getIntExtra(EXTRA_ITEM, -1))

        private fun initialState(device: DlnaDevice, item: CastItem) = CastState(
            active = true,
            deviceName = device.friendlyName,
            title = item.title,
            uploader = item.uploader,
            durationSeconds = item.durationSeconds,
            queue = listOf(CastQueueEntry(item.title, item.uploader, item.durationSeconds))
        )

        fun playPause(context: Context) = command(context, ACTION_PLAY_PAUSE)

        fun stop(context: Context) = command(context, ACTION_STOP)

        fun next(context: Context) = command(context, ACTION_NEXT)

        fun playIndex(context: Context, index: Int) = command(context, ACTION_PLAY_INDEX) { putExtra(EXTRA_INDEX, index) }

        fun remove(context: Context, index: Int) = command(context, ACTION_REMOVE) { putExtra(EXTRA_INDEX, index) }

        /** @param height the resolution, or 0 for automatic */
        fun setQuality(context: Context, height: Int) = command(context, ACTION_SET_QUALITY) { putExtra(EXTRA_INDEX, height) }

        fun setAudioTrack(context: Context, index: Int) = command(context, ACTION_SET_AUDIO_TRACK) { putExtra(EXTRA_INDEX, index) }

        /** @param index the index in [CastState.subtitles], -1 for none */
        fun setSubtitles(context: Context, index: Int) = command(context, ACTION_SET_SUBTITLES) { putExtra(EXTRA_INDEX, index) }

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
