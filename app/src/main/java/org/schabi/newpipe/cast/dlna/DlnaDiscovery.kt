/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds DLNA MediaRenderers: SSDP search, then download and parse of the description of every
 * device which answered.
 */
object DlnaDiscovery {
    private const val DESCRIPTION_THREADS = 4

    /**
     * Blocks for about [timeoutMillis] (plus the time needed to download the last descriptions).
     *
     * @param onDevice called (from a background thread) for every renderer found
     */
    fun discover(
        networkInterface: NetworkInterface?,
        timeoutMillis: Long = 4000,
        onDevice: (DlnaDevice) -> Unit
    ) {
        val seenLocations = Collections.synchronizedSet(HashSet<String>())
        val seenDevices = Collections.synchronizedSet(HashSet<String>())
        val executor = Executors.newFixedThreadPool(DESCRIPTION_THREADS)
        try {
            Ssdp.search(networkInterface, timeoutMillis = timeoutMillis) { response ->
                if (!seenLocations.add(response.location)) {
                    return@search
                }
                executor.execute {
                    try {
                        val xml = DlnaClient.fetchDescription(response.location)
                        val device = DeviceDescriptionParser.parse(xml, response.location)
                        if (device == null) {
                            CastLog.d("${response.location} has no AVTransport service, ignored")
                        } else if (seenDevices.add(device.udn)) {
                            CastLog.d(
                                "Found renderer \"${device.friendlyName}\" (${device.manufacturer} " +
                                    "${device.modelName}): AVTransport ${device.avTransportControlUrl}, " +
                                    "RenderingControl ${device.renderingControlUrl}"
                            )
                            onDevice(device)
                        }
                    } catch (e: Exception) {
                        CastLog.e("Could not read device description ${response.location}", e)
                    }
                }
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(DESCRIPTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private const val DESCRIPTION_TIMEOUT_SECONDS = 15L
}
