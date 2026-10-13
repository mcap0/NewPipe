/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * The phone's address on the local network (usually Wi-Fi), which the TV uses to reach the
 * proxy, and the interface SSDP requests are sent on.
 */
data class LocalAddress(val networkInterface: NetworkInterface, val address: InetAddress)

object LocalNetwork {
    /**
     * Finds the IPv4 address of the phone on the local network. Wi-Fi interfaces (`wlan*`) are
     * preferred, then other interfaces with a private address (Ethernet, hotspot, ...).
     * Mobile data and VPN interfaces are skipped: carriers and VPNs often use private addresses
     * too, but the TV cannot reach them.
     *
     * @return the address, or null if the phone is not connected to a local network
     */
    fun find(): LocalAddress? {
        val candidates = ArrayList<LocalAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
        } catch (e: Exception) {
            CastLog.e("Could not list network interfaces", e)
            return null
        }
        for (networkInterface in interfaces) {
            try {
                if (!networkInterface.isUp || networkInterface.isLoopback || isExcluded(networkInterface.name)) {
                    continue
                }
                for (address in Collections.list(networkInterface.inetAddresses)) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        candidates.add(LocalAddress(networkInterface, address))
                    }
                }
            } catch (e: Exception) {
                CastLog.w("Skipping interface ${networkInterface.name}", e)
            }
        }
        CastLog.d(
            "Local addresses: " +
                candidates.joinToString { "${it.networkInterface.name}=${it.address.hostAddress}" }
        )
        return candidates.sortedBy { priorityOf(it.networkInterface.name) }.firstOrNull()
    }

    private val EXCLUDED_PREFIXES = listOf("rmnet", "rev_rmnet", "ccmni", "pdp", "v4-", "tun", "ppp", "dummy", "ipsec")

    private fun isExcluded(name: String) = EXCLUDED_PREFIXES.any { name.startsWith(it) }

    private fun priorityOf(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        name.startsWith("ap") || name.startsWith("swlan") -> 2
        else -> 3
    }
}
