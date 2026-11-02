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
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastChoice
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.CastMedia
import org.schabi.newpipe.cast.dlna.CastStreamSelector
import org.schabi.newpipe.cast.dlna.DidlLite
import org.schabi.newpipe.cast.dlna.DlnaClient
import org.schabi.newpipe.cast.dlna.DlnaDevice
import org.schabi.newpipe.cast.dlna.LocalNetwork
import org.schabi.newpipe.cast.dlna.PositionInfo
import org.schabi.newpipe.cast.dlna.SrtConverter
import org.schabi.newpipe.cast.dlna.TransportState
import org.schabi.newpipe.cast.dlna.UpnpException
import org.schabi.newpipe.cast.hls.HlsStreamServer
import org.schabi.newpipe.cast.proxy.CastProxyServer
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.util.DependentPreferenceHelper

/** State of the cast session, as shown by the controller. */
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
 * @param audioTracks the names of the audio tracks that can be chosen, empty if only one
 * @param audioTrack the index in [audioTracks] of the track being cast
 * @param subtitles the names of the subtitles that can be chosen
 * @param subtitle the index in [subtitles] of the subtitles sent to the TV, -1 for none
 */
data class CastState(
    val active: Boolean = false,
    val deviceName: String = "",
    /** URL of the video being cast */
    val url: String = "",
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
    val audioTracks: List<String> = emptyList(),
    val audioTrack: Int = 0,
    val subtitles: List<String> = emptyList(),
    val subtitle: Int = -1
)

/**
 * Foreground service which owns a cast session: it runs the local proxy, sends the videos of
 * the queue to the TV one after the other, polls the TV every two seconds for its state and
 * handles the commands from the controller and the notification.
 *
 * The session, the queue and the transport are a port of the Lumia app in
 * `apps/PerleTV/PerleCast` (dumbphone): the TV is stopped and its connections are closed only
 * when a new address is sent, a resume after the TV stopped reuses the same server, and a TV
 * which does not answer is waited for instead of ending the session. Those rules are delicate
 * and got their shape from long testing on a Samsung TV, so they are kept as they are.
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
    private var hlsServer: HlsStreamServer? = null
    private var polling: ScheduledFuture<*>? = null
    private var active = false

    private val queue = CastQueue<CastItem>()
    private var maxHeight = DEFAULT_MAX_HEIGHT
    private var polls = 0
    private var failedPolls = 0
    private var resumes = 0

    // the state of the current stream
    private var duration = -1L
    private var position = 0L
    private var startOffset = 0L
    private var sessionError = ""
    private var lastTransport: TransportState? = null

    // what is being cast
    private var currentChoice: CastChoice? = null
    private var currentSubtitles: SubtitlesStream? = null
    private var currentSrt: String? = null
    private var liveStream: CastProxyServer.LiveStream? = null
    private var liveMedia: CastMedia? = null

    // the progressive MP4 path, used only when there are no muxable DASH streams
    private var progressiveMedia: CastMedia? = null
    private var pendingSeek = -1L

    private var wantedSubtitles: String? = null
    private var sessionId = 0

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
                val request = intent.getLongExtra(EXTRA_REQUEST, 0)
                worker.execute { if (request == seekRequest.get()) seek(position) }
            }

            ACTION_NEXT -> worker.execute { playIndex(queue.index + 1) }

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
                worker.execute { setQuality(height) }
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
        worker.execute { endSession(stopTv = false, notify = false, keepState = true) }
        worker.shutdown()
        super.onDestroy()
    }

    // /////////////////////////////////////////////////////////////////////////
    // Session (worker thread)
    // /////////////////////////////////////////////////////////////////////////

    private fun startSession(device: DlnaDevice, item: CastItem) {
        // a new session first stops the old TV, like CastSession::StartSession
        endSession(stopTv = true, notify = false, keepState = true)
        val session = ++sessionId
        CastLog.d(
            "Starting session #$session on \"${device.friendlyName}\" for \"${item.title}\" " +
                "(app ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE})"
        )
        acquireLocks()

        this.device = device
        client = DlnaClient(device)
        queue.reset(item)
        active = true
        polls = 0
        failedPolls = 0
        resumes = 0
        duration = -1
        position = 0
        sessionError = ""
        lastTransport = null

        if (!playCurrent(-1, isNewVideo = true)) {
            fail(sessionError.ifEmpty { getString(R.string.cast_error_cannot_play) })
            return
        }

        val dlna = client ?: return
        if (dlna.hasVolumeControl) {
            val volume = runCatching { dlna.getVolume() }.getOrDefault(-1)
            publish(currentState.copy(hasVolumeControl = volume >= 0, volume = volume))
        }
        polling = worker.scheduleWithFixedDelay({ poll(session) }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Sends the current video of the queue to the TV.
     *
     * @param start where to start, -1 to use the item's start or the position saved in history
     * @return false if the stream could not be prepared or the TV refused it; the session and
     * the queue are left alive, like CastSession::PlayCurrent
     */
    private fun playCurrent(start: Long, isNewVideo: Boolean = false): Boolean {
        val dlna = client ?: return false
        if (!queue.hasCurrent()) {
            return false
        }
        val item = queue.current()
        var from = if (start >= 0) {
            start
        } else if (item.startSeconds >= 0) {
            item.startSeconds
        } else {
            savedPosition(item)
        }
        item.startSeconds = -1
        resumes = 0
        sessionError = ""
        position = from
        startOffset = from
        pendingSeek = -1
        publish(stateForCurrent(item).copy(status = CastStatus.BUFFERING, positionSeconds = from, error = null))

        currentSubtitles = item.findSubtitles(wantedSubtitles)
        currentSrt = currentSubtitles?.let { loadSrt(it) }

        // tvlab: a new address while the TV still holds a /v.ts of the old server wedges it
        // (701 forever). First the old server and all its connections, then Stop, then the new
        // address; this is not done for the very first start, which has no old server.
        val oldProxy = proxy
        val oldHls = hlsServer
        if (oldProxy != null || oldHls != null) {
            oldHls?.shutdown()
            oldProxy?.stop()
            runCatching { dlna.stop() }
        }
        proxy = null
        hlsServer = null
        liveStream = null
        liveMedia = null
        progressiveMedia = null
        currentChoice = null

        // YouTube: always the HLS stream of the visionOS client, exactly like the Lumia app
        // (CastSession::PlayCurrent + StreamServer). Videos without HLS (other services) use
        // the DASH / MP4 path below; a failure here is reported, never retried on the other path.
        val hlsUrl = item.info.hlsUrl
        if (!hlsUrl.isNullOrEmpty()) {
            try {
                val local = LocalNetwork.find() ?: throw IllegalStateException("No local network address")
                val server = HlsStreamServer(local.address, DownloaderImpl.getInstance().client)
                server.maxHeight = maxHeight
                hlsServer = server
                val localUrl = server.start(hlsUrl, item.audioLanguage ?: savedAudioLanguage(), currentSrt)
                duration = server.durationSeconds
                item.durationSeconds = if (duration > 0) duration else -1
                if (duration > 0 && from >= duration - END_MARGIN_SECONDS) {
                    from = 0
                }
                liveStream = CastProxyServer.LiveStream(
                    url = "$localUrl/v.ts",
                    // startAt adds the start, the cues follow the stream
                    subtitleUrl = currentSrt?.let { "$localUrl/s.srt" },
                    durationSeconds = duration
                )
                liveMedia = CastMedia(
                    url = "",
                    title = item.title,
                    uploader = item.uploader,
                    mimeType = LIVE_MIME_TYPE,
                    durationSeconds = item.durationSeconds
                )
            } catch (e: Exception) {
                return prepareFailed(item, e.message ?: getString(R.string.cast_error_no_wifi))
            }
            val started = startAt(from)
            CastLog.d(
                "play \"${item.title}\" from ${from}s of ${duration}s, ${hlsServer?.height ?: 0}p (hls)" +
                    if (started) "" else ", FAILED: $sessionError"
            )
            publish(
                stateForCurrent(item).copy(
                    status = if (started) CastStatus.BUFFERING else CastStatus.ERROR,
                    positionSeconds = from,
                    error = sessionError.takeIf { it.isNotEmpty() }
                )
            )
            if (started && isNewVideo) {
                markAsViewed(item)
            }
            return started
        }

        val audioTrack = item.audioTrackId
            ?: item.audioTrackForLanguage(item.audioLanguage ?: savedAudioLanguage())
        val choice = item.choose(maxHeight, audioTrack)?.let { IosStreamUrls.withIosUrls(it, item.info.id) }
        if (choice == null) {
            return prepareFailed(item, getString(R.string.cast_error_no_stream))
        }
        currentChoice = choice

        try {
            val local = LocalNetwork.find() ?: throw IllegalStateException("No local network address")
            val server = CastProxyServer(OkHttpUpstream(), local.address, OkHttpUpstream(useRangeParameter = true))
            server.start()
            proxy = server

            val dash = choice.dash
            if (dash != null) {
                val live = server.startLive(dash, currentSrt?.toByteArray(Charsets.UTF_8))
                liveStream = live
                duration = live.durationSeconds
                item.durationSeconds = if (duration > 0) duration else -1
                if (duration > 0 && from >= duration - END_MARGIN_SECONDS) {
                    from = 0
                }
                liveMedia = CastMedia(
                    url = "",
                    title = item.title,
                    uploader = item.uploader,
                    mimeType = LIVE_MIME_TYPE,
                    durationSeconds = item.durationSeconds
                )
            } else {
                val single = choice.single ?: return prepareFailed(item, getString(R.string.cast_error_no_stream))
                duration = item.durationSeconds
                val subtitleUrl = currentSrt?.let { registerSrt(it, 0, server) }
                val direct = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(getString(R.string.cast_direct_link_key), false)
                val url = if (direct && subtitleUrl == null) {
                    CastLog.d("Sending the direct link to the TV (proxy disabled in the settings)")
                    single.url
                } else {
                    server.register(single.url, CastStreamSelector.MP4_MIME_TYPE, subtitleUrl)
                }
                progressiveMedia = CastMedia(
                    url = url,
                    title = item.title,
                    uploader = item.uploader,
                    mimeType = CastStreamSelector.MP4_MIME_TYPE,
                    durationSeconds = item.durationSeconds,
                    subtitleUrl = subtitleUrl
                )
            }
        } catch (e: Exception) {
            return prepareFailed(item, e.message ?: getString(R.string.cast_error_no_wifi))
        }

        val started = startAt(from)
        CastLog.d(
            "play \"${item.title}\" from ${from}s of ${duration}s, ${currentChoice?.height ?: 0}p" +
                if (started) "" else ", FAILED: $sessionError"
        )
        publish(
            stateForCurrent(item).copy(
                status = if (started) CastStatus.BUFFERING else CastStatus.ERROR,
                positionSeconds = from,
                error = sessionError.takeIf { it.isNotEmpty() }
            )
        )
        if (started && isNewVideo) {
            markAsViewed(item)
        }
        return started
    }

    private fun prepareFailed(item: CastItem, error: String): Boolean {
        sessionError = error
        CastLog.e("prepare failed for \"${item.title}\": $error")
        publish(stateForCurrent(item).copy(status = CastStatus.ERROR, error = error))
        return false
    }

    /**
     * The stream cannot seek by bytes: a new /v.ts starting at the wanted second replaces the
     * current one.
     */
    private fun startAt(seconds: Long): Boolean {
        var from = seconds
        if (duration > 0 && from >= duration) {
            from = (duration - 5).coerceAtLeast(0)
        }
        startOffset = from
        position = from
        val live = liveStream
        val media = if (live != null) {
            val previous = liveMedia ?: return false
            previous.copy(
                url = "${live.url}?start=$from",
                subtitleUrl = live.subtitleUrl?.let { "$it?start=$from" }
            )
        } else {
            progressiveMedia ?: return false
        }
        if (live == null) {
            pendingSeek = if (from > 0) from else -1
        }

        val dlna = client ?: return false
        var started = false
        try {
            setUriWithRetries(dlna, media)
            playWithRetries(dlna)
            // the progressive MP4 path can seek by time; the live MPEG-TS stream gets a new
            // address instead, like CastSession::StartAt
            if (live == null && pendingSeek > 0) {
                dlna.seek(pendingSeek)
            }
            started = true
        } catch (e: UpnpException) {
            sessionError = "${e.errorCode} ${e.errorDescription}"
        } catch (e: Exception) {
            sessionError = e.message ?: getString(R.string.cast_error_unreachable)
        }
        // CastSession::StartAt records the start even when the TV refused it
        queue.started(from)
        return started
    }

    private fun currentPosition(): Long = queue.lastPosition().takeIf { it >= 0 } ?: position

    /** Downloads the subtitles and converts them to SRT. */
    private fun loadSrt(subtitles: SubtitlesStream): String? {
        return try {
            val text = DownloaderImpl.getInstance().get(subtitles.content).responseBody()
            SrtConverter.convert(text, CastItem.suffixOf(subtitles).orEmpty())
                ?: throw IllegalArgumentException(
                    "Unsupported subtitle format ${CastItem.suffixOf(subtitles)}"
                )
        } catch (e: Exception) {
            CastLog.e("Could not load the subtitles", e)
            null
        }
    }

    /** Makes [srt] available to the progressive MP4 path, moved back to [startSeconds]. */
    private fun registerSrt(srt: String, startSeconds: Long, server: CastProxyServer): String {
        val shifted = if (startSeconds > 0) {
            SrtConverter.shift(srt, startSeconds * 1000)
        } else {
            srt
        }
        return server.registerContent(
            shifted.toByteArray(Charsets.UTF_8),
            DidlLite.SRT_MIME_TYPE,
            "srt"
        ).also { CastLog.d("Subtitles for the TV: $it") }
    }

    private fun saveProgress() {
        if (!queue.hasCurrent() || !queue.hasPlayed()) {
            return
        }
        savePosition(queue.current(), currentPosition())
    }

    private fun savePosition(item: CastItem, seconds: Long) {
        if (seconds < 0 || !isWatchHistoryEnabled()) {
            return
        }
        try {
            history.saveStreamState(item.info, seconds * 1000).blockingAwait()
        } catch (e: Exception) {
            CastLog.w("Could not save the position: ${e.message}")
        }
    }

    private fun endSession(stopTv: Boolean, notify: Boolean, keepState: Boolean = false) {
        val wasActive = active
        if (wasActive) {
            saveProgress()
        }
        active = false
        if (stopTv) {
            client?.let { runCatching { it.stop() } }
        }
        polling?.cancel(false)
        polling = null
        proxy?.stop()
        proxy = null
        hlsServer?.shutdown()
        hlsServer = null
        client = null
        device = null
        liveStream = null
        liveMedia = null
        progressiveMedia = null
        currentChoice = null
        currentSubtitles = null
        currentSrt = null
        lastTransport = null
        releaseLocks()
        if (notify && wasActive) {
            publish(CastState(active = false))
        } else if (!keepState) {
            publish(CastState(active = false))
        }
    }

    private fun enqueue(item: CastItem, playNow: Boolean) {
        if (!active) {
            return
        }
        if (playNow) {
            saveProgress()
            if (queue.playNow(item) == QueueAction.PLAY_CURRENT) {
                playCurrent(-1, isNewVideo = true)
            }
        } else {
            queue.add(item)
            CastLog.d("Added \"${item.title}\" to the queue (${queue.items().size} videos)")
            publish(currentState.copy(queue = queueEntries()))
        }
    }

    private fun playIndex(index: Int) {
        if (!active) {
            return
        }
        saveProgress()
        if (queue.playIndex(index) == QueueAction.PLAY_CURRENT) {
            playCurrent(-1, isNewVideo = true)
        }
    }

    private fun next() {
        if (!active) {
            return
        }
        saveProgress()
        if (queue.next() == QueueAction.PLAY_CURRENT) {
            playCurrent(-1, isNewVideo = true)
        }
    }

    private fun remove(index: Int) {
        if (queue.remove(index)) {
            publish(currentState.copy(queue = queueEntries(), queueIndex = queue.index))
        }
    }

    private fun setAudioTrack(index: Int) {
        if (!active || !queue.hasCurrent()) {
            return
        }
        val item = queue.current()
        val hls = hlsServer
        if (hls != null) {
            // the audio renditions of the HLS master, like CastSession::SetAudio
            val language = hls.audioOptions.getOrNull(index)?.language ?: return
            item.audioLanguage = language
            saveAudioLanguage(language)
            playCurrent(currentPosition(), isNewVideo = false)
            return
        }
        val trackId = item.audioTrackIds.getOrNull(index) ?: return
        item.audioTrackId = trackId
        item.audioLanguage = item.audioLanguageOf(trackId)
        saveAudioLanguage(item.audioLanguage)
        playCurrent(currentPosition(), isNewVideo = false)
    }

    private fun setSubtitles(index: Int) {
        if (!active || !queue.hasCurrent()) {
            return
        }
        val item = queue.current()
        wantedSubtitles = item.subtitles.getOrNull(index)?.let { CastItem.subtitleKey(it) }
        playCurrent(currentPosition(), isNewVideo = false)
    }

    private fun setQuality(height: Int) {
        if (!active || height <= 0) {
            return
        }
        maxHeight = height
        playCurrent(currentPosition(), isNewVideo = false)
    }

    private fun seek(seconds: Long) {
        if (!active || seconds < 0) {
            return
        }
        // fresh playlists like the phone player's reopen: the segment URLs read at the start
        // expire (HTTP 403), so a seek sends the video again from there
        playCurrent(seconds, isNewVideo = false)
    }

    private fun playPause() {
        val dlna = client ?: return
        if (!active) {
            return
        }
        try {
            if (lastTransport == TransportState.PLAYING) {
                dlna.pause()
                lastTransport = TransportState.PAUSED
                publish(currentState.copy(status = CastStatus.PAUSED, error = null))
            } else {
                dlna.play()
                lastTransport = TransportState.TRANSITIONING
                publish(currentState.copy(status = CastStatus.BUFFERING, error = null))
            }
        } catch (e: Exception) {
            showCommandError(e)
        }
    }

    private fun changeVolume(delta: Int) {
        val dlna = client ?: return
        if (!active || !dlna.hasVolumeControl) {
            return
        }
        try {
            val current = currentState.volume.takeIf { it >= 0 } ?: dlna.getVolume()
            dlna.setVolume((current + delta).coerceIn(0, MAX_VOLUME))
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

    private fun poll(session: Int) {
        if (session != sessionId) {
            return
        }
        val dlna = client ?: return
        if (!active) {
            return
        }

        val transport: TransportState
        val positionInfo: PositionInfo
        try {
            transport = dlna.getTransportInfo()
            positionInfo = dlna.getPositionInfo()
        } catch (e: Exception) {
            // turned off, another input, Wi-Fi gone: keep the session and wait for it to come back
            failedPolls++
            if (failedPolls >= FAILED_POLLS_BEFORE_ERROR) {
                CastLog.w("The TV does not answer: ${e.message}")
                publish(currentState.copy(error = getString(R.string.cast_error_unreachable)))
            }
            return
        }
        failedPolls = 0
        if (transport != lastTransport) {
            CastLog.d("TV state: $transport, position ${positionInfo.positionSeconds}s")
        }
        lastTransport = transport

        val tvPosition = if (positionInfo.positionSeconds >= 0) {
            startOffset + positionInfo.positionSeconds
        } else {
            -1
        }
        if (tvPosition >= 0 &&
            (transport == TransportState.PLAYING || transport == TransportState.PAUSED)
        ) {
            position = tvPosition
        }

        val action = queue.onPoll(tvStatus(transport), tvPosition)
        polls++
        if (polls % SAVE_EVERY_POLLS == 0 && transport == TransportState.PLAYING) {
            saveProgress()
        }
        if (polls % SAVE_EVERY_POLLS == 0) {
            CastLog.d(
                "tv ${CastControllerStatus.text(transport)} at $tvPosition/$duration s, " +
                    "queue ${queue.index + 1}/${queue.items().size}"
            )
        }

        when (action) {
            QueueAction.PLAY_CURRENT -> {
                // the previous video ended: watched to the end, it starts again from the beginning
                val previous = queue.index - 1
                queue.items().getOrNull(previous)?.let { savePosition(it, 0) }
                playCurrent(-1, isNewVideo = true)
            }

            QueueAction.ENDED -> {
                if (duration > 0 && currentPosition() < duration - END_MARGIN_SECONDS && resumes < MAX_RESUMES) {
                    // the TV stopped in the middle (lost stream, its own hiccup): continue from there
                    resumes++
                    CastLog.w(
                        "The TV stopped at ${currentPosition()}s, resuming ($resumes/$MAX_RESUMES)"
                    )
                    publish(
                        currentState.copy(
                            status = CastStatus.BUFFERING,
                            error = getString(R.string.cast_resuming)
                        )
                    )
                    if (!startAt(currentPosition())) {
                        // the next poll publishes the error, like CastSession::Poll
                        publish(currentState.copy(error = sessionError.takeIf { it.isNotEmpty() }))
                    }
                } else {
                    CastLog.d("The TV stopped at ${currentPosition()}/$duration s: session ends")
                    if (queue.hasCurrent() && duration > 0 &&
                        currentPosition() >= duration - END_MARGIN_SECONDS
                    ) {
                        savePosition(queue.current(), 0)
                    } else {
                        saveProgress()
                    }
                    endSession(stopTv = false, notify = true, keepState = false)
                    stopForegroundAndSelf()
                }
            }

            QueueAction.NONE -> publish(
                currentState.copy(
                    status = statusOf(transport),
                    positionSeconds = position,
                    durationSeconds = duration,
                    error = sessionError.takeIf { it.isNotEmpty() }
                )
            )
        }
    }

    private fun tvStatus(transport: TransportState): TvStatus = when (transport) {
        TransportState.PLAYING -> TvStatus.PLAYING
        TransportState.PAUSED -> TvStatus.PAUSED
        TransportState.TRANSITIONING -> TvStatus.TRANSITIONING
        TransportState.STOPPED, TransportState.NO_MEDIA -> TvStatus.STOPPED
        else -> TvStatus.OTHER
    }

    private fun statusOf(transport: TransportState): CastStatus = when (transport) {
        TransportState.PLAYING -> CastStatus.PLAYING
        TransportState.PAUSED -> CastStatus.PAUSED
        TransportState.TRANSITIONING -> CastStatus.BUFFERING
        TransportState.STOPPED, TransportState.NO_MEDIA -> CastStatus.STOPPED
        else -> currentState.status
    }

    /**
     * Some TVs refuse a new URI while they are playing something else. The Lumia app does not
     * ask or wait: it sends Stop before every new address and retries the address on 701.
     */
    private fun setUriWithRetries(dlna: DlnaClient, media: CastMedia): Int {
        var attempt = 0
        while (true) {
            try {
                dlna.setAvTransportUri(media)
                return attempt + 1
            } catch (e: UpnpException) {
                if (e.errorCode != ERROR_TRANSITION_NOT_AVAILABLE || ++attempt >= PLAY_ATTEMPTS) {
                    throw e
                }
                Thread.sleep(1000)
            }
        }
    }

    /** Right after SetAVTransportURI some TVs answer Play with 701 until they are ready. */
    private fun playWithRetries(dlna: DlnaClient): Int {
        var attempt = 0
        while (true) {
            try {
                dlna.play()
                return attempt + 1
            } catch (e: UpnpException) {
                if (e.errorCode != ERROR_TRANSITION_NOT_AVAILABLE || ++attempt >= PLAY_ATTEMPTS) {
                    throw e
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun fail(message: String) {
        CastLog.e("Session failed: $message")
        endSession(stopTv = false, notify = false, keepState = true)
        // not active anymore: the controller shows the error and disables the buttons
        publish(currentState.copy(active = false, status = CastStatus.ERROR, error = message))
        stopForegroundAndSelf()
    }

    private fun stopSession(stopTv: Boolean) {
        endSession(stopTv = stopTv, notify = true, keepState = false)
        stopForegroundAndSelf()
    }

    // /////////////////////////////////////////////////////////////////////////
    // Queue and history
    // /////////////////////////////////////////////////////////////////////////

    private fun queueEntries() = queue.items().map { CastQueueEntry(it.title, it.uploader, it.durationSeconds) }

    /** @return the state showing the current video of the queue and what can be chosen for it */
    private fun stateForCurrent(item: CastItem): CastState {
        val choice = currentChoice
        val audioTrackId = item.audioTrackId
            ?: item.audioTrackForLanguage(item.audioLanguage ?: savedAudioLanguage())
            ?: item.audioTrackIds.firstOrNull()
        val hls = hlsServer
        // the qualities and audio tracks of the HLS master, like CastSession::Publish
        val audioNames = hls?.audioOptions?.map { option ->
            var name = option.name.ifEmpty { "Audio" }
            if (option.language.isNotEmpty() && option.language != name) name += " (${option.language})"
            name
        }
        return currentState.copy(
            active = true,
            deviceName = device?.friendlyName.orEmpty(),
            url = item.videoUrl,
            title = item.title,
            uploader = item.uploader,
            durationSeconds = if (duration > 0) duration else item.durationSeconds,
            positionSeconds = position,
            queue = queueEntries(),
            queueIndex = queue.index,
            qualities = hls?.heights ?: item.qualities,
            quality = hls?.height ?: choice?.height ?: 0,
            audioTracks = audioNames?.takeIf { it.size > 1 }
                ?: if (hls != null) emptyList() else item.audioTrackIds.map { item.audioTrackName(this, it) },
            audioTrack = if (hls != null) hls.audioIndex else item.audioTrackIds.indexOf(audioTrackId).coerceAtLeast(0),
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

    private fun savedAudioLanguage(): String? = PreferenceManager.getDefaultSharedPreferences(this)
        .getString(AUDIO_LANGUAGE_KEY, null)

    private fun saveAudioLanguage(language: String?) {
        if (language.isNullOrEmpty()) {
            return
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(AUDIO_LANGUAGE_KEY, language)
            .apply()
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
        private const val EXTRA_REQUEST = "request"

        private const val ERROR_TRANSITION_NOT_AVAILABLE = 701
        private const val PLAY_ATTEMPTS = 15
        private const val FAILED_POLLS_BEFORE_ERROR = 3
        private const val MAX_RESUMES = 3
        private const val SAVE_EVERY_POLLS = 5
        private const val END_MARGIN_SECONDS = 15
        private const val VOLUME_STEP = 2
        private const val MAX_VOLUME = 100
        private const val DEFAULT_MAX_HEIGHT = 1080
        private const val POLL_SECONDS = 2L
        private const val LIVE_MIME_TYPE = "video/mp2t"
        private const val AUDIO_LANGUAGE_KEY = "cast_audio_language"

        @Volatile
        private var currentState = CastState()
        private val mutableState = MutableLiveData(CastState())

        /** The latest seek requested by the controller; older queued ones are dropped. */
        private val seekRequest = AtomicLong()

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
                    item.startSeconds = position / 1000
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
            url = item.videoUrl,
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

        /** @param height the highest resolution wanted; the Lumia app has no "automatic" */
        fun setQuality(context: Context, height: Int) = command(context, ACTION_SET_QUALITY) { putExtra(EXTRA_INDEX, height) }

        fun setAudioTrack(context: Context, index: Int) = command(context, ACTION_SET_AUDIO_TRACK) { putExtra(EXTRA_INDEX, index) }

        /** @param index the index in [CastState.subtitles], -1 for none */
        fun setSubtitles(context: Context, index: Int) = command(context, ACTION_SET_SUBTITLES) { putExtra(EXTRA_INDEX, index) }

        fun volumeUp(context: Context) = command(context, ACTION_VOLUME_UP)

        fun volumeDown(context: Context) = command(context, ACTION_VOLUME_DOWN)

        /**
         * Asks for a seek. Only the last request made from the controller is executed: taps in
         * a row add up without restarting the stream for each one (the Lumia `seekto` counter).
         */
        fun seek(context: Context, positionSeconds: Long) {
            if (positionSeconds < 0) {
                return
            }
            val request = seekRequest.incrementAndGet()
            command(context, ACTION_SEEK) {
                putExtra(EXTRA_POSITION, positionSeconds)
                putExtra(EXTRA_REQUEST, request)
            }
        }

        private fun command(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            if (!currentState.active) {
                return
            }
            context.startService(Intent(context, CastService::class.java).setAction(action).apply(extras))
        }
    }
}

/** The status text for the log, like `CastController::StatusText`. */
private object CastControllerStatus {
    fun text(state: TransportState): String = when (state) {
        TransportState.PLAYING -> "Playing"
        TransportState.PAUSED -> "Paused"
        TransportState.TRANSITIONING -> "Buffering"
        TransportState.STOPPED, TransportState.NO_MEDIA -> "Stopped"
        else -> "Unknown"
    }
}
