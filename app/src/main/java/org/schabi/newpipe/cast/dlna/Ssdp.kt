/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * One answer to an SSDP M-SEARCH, i.e. one device (or service) announcing itself.
 *
 * @param location the URL of the device description XML
 * @param searchTarget the ST header, e.g. `urn:schemas-upnp-org:device:MediaRenderer:1`
 * @param usn the unique service name, used to recognize duplicates
 * @param server the SERVER header, only useful for the log
 */
data class SsdpResponse(
    val location: String,
    val searchTarget: String?,
    val usn: String?,
    val server: String?
)

/**
 * SSDP (Simple Service Discovery Protocol), the discovery part of UPnP: a M-SEARCH request is
 * sent via UDP multicast to 239.255.255.250:1900 and devices answer via unicast to the sender.
 */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** How long a device can wait before answering, in seconds (the MX header). */
    const val MX_SECONDS = 2

    /**
     * Builds a M-SEARCH request. The trailing empty line is required by the HTTP-over-UDP syntax.
     */
    fun buildSearchRequest(searchTarget: String, mxSeconds: Int = MX_SECONDS): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $searchTarget\r\n" +
            "USER-AGENT: Android UPnP/1.1 NewPipe/1.0\r\n" +
            "\r\n"
    }

    /**
     * Parses an answer to a M-SEARCH (`HTTP/1.1 200 OK`) or a NOTIFY announcement.
     * Header names are case insensitive, as some devices write `Location` or `location`.
     *
     * @return the parsed response, or null if it is not a valid answer with a LOCATION header
     */
    fun parseResponse(message: String): SsdpResponse? {
        val lines = message.split("\r\n", "\n")
        val statusLine = lines.firstOrNull()?.trim() ?: return null
        val isSearchAnswer = statusLine.startsWith("HTTP/", ignoreCase = true) &&
            statusLine.split(' ').getOrNull(1) == "200"
        val isNotify = statusLine.startsWith("NOTIFY", ignoreCase = true)
        if (!isSearchAnswer && !isNotify) {
            return null
        }

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                continue
            }
            val name = line.substring(0, colon).trim().uppercase()
            headers.putIfAbsent(name, line.substring(colon + 1).trim())
        }

        if (isNotify && headers["NTS"]?.equals("ssdp:byebye", ignoreCase = true) == true) {
            return null
        }
        val location = headers["LOCATION"]?.takeIf { it.isNotEmpty() } ?: return null
        return SsdpResponse(
            location = location,
            searchTarget = headers["ST"] ?: headers["NT"],
            usn = headers["USN"],
            server = headers["SERVER"]
        )
    }

    /**
     * Sends M-SEARCH requests and collects the answers for [timeoutMillis].
     *
     * This method blocks, call it from a background thread. On Android a
     * `WifiManager.MulticastLock` must be held while it runs, otherwise many devices drop the
     * incoming packets.
     *
     * @param networkInterface the interface to send the requests on (the Wi-Fi one), or null to
     * let the system choose
     * @param searchTargets the ST values to search for
     * @param onResponse called on the calling thread for every new (not duplicated) answer
     */
    fun search(
        networkInterface: NetworkInterface?,
        searchTargets: List<String> = listOf(MEDIA_RENDERER, AV_TRANSPORT),
        timeoutMillis: Long = 4000,
        onResponse: (SsdpResponse) -> Unit
    ) {
        val seen = HashSet<String>()
        MulticastSocket(null as InetSocketAddress?).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.timeToLive = 4
            if (networkInterface != null) {
                try {
                    socket.networkInterface = networkInterface
                } catch (e: Exception) {
                    CastLog.w("SSDP: could not use interface ${networkInterface.name}", e)
                }
            }
            val group = InetAddress.getByName(MULTICAST_ADDRESS)

            val deadline = System.currentTimeMillis() + timeoutMillis
            // UDP is unreliable: send the requests a few times during the search
            val sendTimes = listOf(0L, 1000L, 2000L).map { deadline - timeoutMillis + it }
            var nextSend = 0
            val buffer = ByteArray(4096)

            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) {
                    break
                }
                if (nextSend < sendTimes.size && now >= sendTimes[nextSend]) {
                    for (target in searchTargets) {
                        val data = buildSearchRequest(target).toByteArray(StandardCharsets.UTF_8)
                        socket.send(DatagramPacket(data, data.size, group, PORT))
                    }
                    CastLog.d("SSDP: sent M-SEARCH #${nextSend + 1} for $searchTargets")
                    nextSend++
                }

                val waitUntil = if (nextSend < sendTimes.size) {
                    minOf(deadline, sendTimes[nextSend])
                } else {
                    deadline
                }
                socket.soTimeout = (waitUntil - now).coerceAtLeast(1).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                CastLog.d("SSDP: answer from ${packet.address?.hostAddress}:\n${text.trim()}")
                val response = parseResponse(text) ?: continue
                if (seen.add(response.location)) {
                    onResponse(response)
                }
            }
        }
    }
}
