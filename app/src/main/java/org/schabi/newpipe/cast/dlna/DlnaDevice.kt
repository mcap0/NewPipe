/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.Serializable
import java.net.URI
import org.w3c.dom.Element

/**
 * A DLNA/UPnP MediaRenderer (e.g. a smart TV) which can be controlled via SOAP.
 *
 * @param udn the unique device name (`uuid:...`), used to tell devices apart
 * @param friendlyName the name to show to the user, e.g. `[TV] Samsung 6 Series (43)`
 * @param location the URL of the device description, from SSDP
 * @param avTransportControlUrl absolute URL of the AVTransport control endpoint
 * @param renderingControlUrl absolute URL of the RenderingControl control endpoint, if any
 * @param connectionManagerUrl absolute URL of the ConnectionManager control endpoint, if any
 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val location: String,
    val avTransportControlUrl: String,
    val avTransportServiceType: String,
    val renderingControlUrl: String?,
    val renderingControlServiceType: String?,
    val connectionManagerUrl: String? = null,
    val connectionManagerServiceType: String? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Parses UPnP device description documents (the XML found at the SSDP LOCATION).
 */
object DeviceDescriptionParser {
    private const val AV_TRANSPORT_PREFIX = "urn:schemas-upnp-org:service:AVTransport:"
    private const val RENDERING_CONTROL_PREFIX = "urn:schemas-upnp-org:service:RenderingControl:"
    private const val CONNECTION_MANAGER_PREFIX = "urn:schemas-upnp-org:service:ConnectionManager:"

    /**
     * @param xml the device description
     * @param location the URL the description was downloaded from, used to resolve relative
     * URLs when the document has no `URLBase`
     * @return the device, or null if it has no AVTransport service (so it cannot play videos)
     */
    fun parse(xml: String, location: String): DlnaDevice? {
        val root = XmlUtils.parse(xml).documentElement
        val urlBase = XmlUtils.childText(root, "URLBase")?.takeIf { it.isNotEmpty() }
        val base = urlBase ?: location
        val rootDevice = XmlUtils.child(root, "device") ?: return null

        // the renderer may be an embedded device (e.g. inside a media server), look at all of them
        for (device in allDevices(rootDevice)) {
            val services = XmlUtils.child(device, "serviceList")
                ?.let { XmlUtils.children(it, "service") }
                .orEmpty()
            val avTransport = services.firstOrNull { serviceType(it).startsWith(AV_TRANSPORT_PREFIX) }
                ?: continue
            val renderingControl = services.firstOrNull {
                serviceType(it).startsWith(RENDERING_CONTROL_PREFIX)
            }
            val connectionManager = services.firstOrNull {
                serviceType(it).startsWith(CONNECTION_MANAGER_PREFIX)
            }
            val avTransportUrl = XmlUtils.childText(avTransport, "controlURL") ?: continue

            return DlnaDevice(
                udn = XmlUtils.childText(device, "UDN") ?: location,
                friendlyName = XmlUtils.childText(device, "friendlyName")
                    ?.takeIf { it.isNotEmpty() }
                    ?: hostOf(location),
                manufacturer = XmlUtils.childText(device, "manufacturer"),
                modelName = XmlUtils.childText(device, "modelName"),
                location = location,
                avTransportControlUrl = resolveUrl(base, avTransportUrl),
                avTransportServiceType = serviceType(avTransport),
                renderingControlUrl = renderingControl
                    ?.let { XmlUtils.childText(it, "controlURL") }
                    ?.let { resolveUrl(base, it) },
                renderingControlServiceType = renderingControl?.let { serviceType(it) },
                connectionManagerUrl = connectionManager
                    ?.let { XmlUtils.childText(it, "controlURL") }
                    ?.let { resolveUrl(base, it) },
                connectionManagerServiceType = connectionManager?.let { serviceType(it) }
            )
        }
        return null
    }

    private fun allDevices(device: Element): List<Element> {
        val result = arrayListOf(device)
        XmlUtils.child(device, "deviceList")
            ?.let { XmlUtils.children(it, "device") }
            ?.forEach { result.addAll(allDevices(it)) }
        return result
    }

    private fun serviceType(service: Element) = XmlUtils.childText(service, "serviceType").orEmpty()

    private fun hostOf(url: String): String = try {
        URI(url).host ?: url
    } catch (_: Exception) {
        url
    }

    /**
     * Resolves a URL from a device description against the base URL, following RFC 3986.
     *
     * Devices use all kinds of forms: absolute URLs, absolute paths (`/upnp/control/AVT`) and
     * relative paths (`AVTransport/control`). A base without any path (`http://1.2.3.4:9197`)
     * is treated as the root directory, which the JDK would otherwise get wrong.
     */
    fun resolveUrl(base: String, url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val baseUri = URI(base.trim())
        val directoryBase = if (baseUri.path.isNullOrEmpty()) {
            URI(baseUri.scheme, baseUri.authority, "/", null, null)
        } else {
            baseUri
        }
        return directoryBase.resolve(trimmed).toString()
    }
}
