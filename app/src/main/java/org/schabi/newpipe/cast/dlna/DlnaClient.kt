/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** State of the renderer, from GetTransportInfo. */
enum class TransportState {
    PLAYING,
    PAUSED,
    STOPPED,
    TRANSITIONING,
    NO_MEDIA,
    UNKNOWN;

    companion object {
        fun parse(value: String?): TransportState = when (value?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PAUSED
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            "NO_MEDIA_PRESENT" -> NO_MEDIA
            else -> UNKNOWN
        }
    }
}

/** Position of the renderer, from GetPositionInfo. Values are in seconds, -1 if unknown. */
data class PositionInfo(val positionSeconds: Long, val durationSeconds: Long)

/**
 * Blocking client for the AVTransport and RenderingControl services of a [DlnaDevice].
 * Every request and answer is written to [CastLog]. Call the methods from a background thread.
 */
class DlnaClient(
    private val device: DlnaDevice,
    private val httpClient: OkHttpClient = defaultHttpClient
) {
    fun setAvTransportUri(media: CastMedia) {
        val metadata = DidlLite.build(media)
        avTransport(
            "SetAVTransportURI",
            listOf(
                INSTANCE_ID,
                "CurrentURI" to media.url,
                "CurrentURIMetaData" to metadata
            )
        )
    }

    fun play() {
        avTransport("Play", listOf(INSTANCE_ID, "Speed" to "1"))
    }

    fun pause() {
        avTransport("Pause", listOf(INSTANCE_ID))
    }

    fun stop() {
        avTransport("Stop", listOf(INSTANCE_ID))
    }

    fun seek(positionSeconds: Long) {
        avTransport(
            "Seek",
            listOf(INSTANCE_ID, "Unit" to "REL_TIME", "Target" to UpnpTime.format(positionSeconds))
        )
    }

    fun getPositionInfo(): PositionInfo {
        val result = avTransport("GetPositionInfo", listOf(INSTANCE_ID), logAnswer = false)
        return PositionInfo(UpnpTime.parse(result["RelTime"]), UpnpTime.parse(result["TrackDuration"]))
    }

    fun getTransportInfo(): TransportState {
        val result = avTransport("GetTransportInfo", listOf(INSTANCE_ID), logAnswer = false)
        return TransportState.parse(result["CurrentTransportState"])
    }

    /** @return true if the device supports volume control */
    val hasVolumeControl: Boolean
        get() = device.renderingControlUrl != null && device.renderingControlServiceType != null

    /** @return the volume, usually 0..100 */
    fun getVolume(): Int {
        val result = renderingControl("GetVolume", listOf(INSTANCE_ID, CHANNEL))
        return result["CurrentVolume"]?.toIntOrNull()
            ?: throw UpnpException("GetVolume", 0, "No CurrentVolume in answer")
    }

    fun setVolume(volume: Int) {
        renderingControl("SetVolume", listOf(INSTANCE_ID, CHANNEL, "DesiredVolume" to volume.toString()))
    }

    private fun avTransport(
        action: String,
        arguments: List<Pair<String, String>>,
        logAnswer: Boolean = true
    ): Map<String, String> {
        return call(device.avTransportControlUrl, device.avTransportServiceType, action, arguments, logAnswer)
    }

    private fun renderingControl(action: String, arguments: List<Pair<String, String>>): Map<String, String> {
        val url = device.renderingControlUrl
        val serviceType = device.renderingControlServiceType
        if (url == null || serviceType == null) {
            throw UpnpException(action, 0, "The device has no RenderingControl service")
        }
        return call(url, serviceType, action, arguments, true)
    }

    private fun call(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>>,
        logAnswer: Boolean
    ): Map<String, String> {
        val envelope = Soap.buildEnvelope(serviceType, action, arguments)
        if (logAnswer) {
            CastLog.d("SOAP -> $action @ $controlUrl\n$envelope")
        }
        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", Soap.soapActionHeader(serviceType, action))
            .header("User-Agent", USER_AGENT)
            .header("Connection", "close")
            .post(envelope.toByteArray(Charsets.UTF_8).toRequestBody(SOAP_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (logAnswer || !response.isSuccessful) {
                    CastLog.d("SOAP <- $action HTTP ${response.code}\n$body")
                }
                return Soap.parseResponse(action, response.code, body)
            }
        } catch (e: UpnpException) {
            CastLog.e("SOAP $action: UPnP error ${e.errorCode} ${e.errorDescription}")
            throw e
        } catch (e: IOException) {
            CastLog.e("SOAP $action: network error", e)
            throw e
        }
    }

    companion object {
        private val INSTANCE_ID = "InstanceID" to "0"
        private val CHANNEL = "Channel" to "Master"
        private val SOAP_MEDIA_TYPE = Soap.CONTENT_TYPE.toMediaType()
        const val USER_AGENT = "Android/1.0 UPnP/1.0 NewPipe/1.0"

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** Downloads a device description and logs it. */
        fun fetchDescription(location: String, httpClient: OkHttpClient = defaultHttpClient): String {
            val request = Request.Builder()
                .url(location)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                CastLog.d("Device description from $location (HTTP ${response.code}):\n$body")
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while downloading $location")
                }
                return body
            }
        }
    }
}
