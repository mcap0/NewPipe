/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.w3c.dom.Element

/**
 * Thrown when a device answers a SOAP action with an error, usually a `UPnPError`.
 *
 * @param errorCode the UPnP error code (e.g. 701 "Transition not available"), or the HTTP
 * status code if the answer did not contain a UPnP error
 */
class UpnpException(
    val action: String,
    val errorCode: Int,
    val errorDescription: String
) : Exception("$action failed: $errorCode $errorDescription")

/**
 * Builds SOAP requests for UPnP actions and parses their answers.
 */
object Soap {
    const val CONTENT_TYPE = "text/xml; charset=\"utf-8\""

    /**
     * @param arguments the action arguments, in the order required by the service description
     * (renderers are often strict about the order). Values are escaped here.
     */
    fun buildEnvelope(serviceType: String, action: String, arguments: List<Pair<String, String>>): String {
        val body = StringBuilder()
        for ((name, value) in arguments) {
            body.append('<').append(name).append('>')
                .append(XmlUtils.escape(value))
                .append("</").append(name).append('>')
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$action xmlns:u=\"${XmlUtils.escape(serviceType)}\">" +
            body +
            "</u:$action>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    /** @return the value of the SOAPACTION header, quotes included as required by the spec */
    fun soapActionHeader(serviceType: String, action: String) = "\"$serviceType#$action\""

    /**
     * Parses the answer to an action.
     *
     * @return the output arguments, e.g. `RelTime` -> `0:01:02`
     * @throws UpnpException if the answer is a SOAP fault or the HTTP request failed
     */
    fun parseResponse(action: String, httpCode: Int, xml: String): Map<String, String> {
        val document = try {
            XmlUtils.parse(xml)
        } catch (e: Exception) {
            if (httpCode !in 200..299) {
                throw UpnpException(action, httpCode, "HTTP error without valid SOAP body")
            }
            throw UpnpException(action, httpCode, "Invalid SOAP answer: ${e.message}")
        }
        val root = document.documentElement

        val fault = XmlUtils.findFirst(root, "Fault")
        if (fault != null || httpCode !in 200..299) {
            val upnpError = fault?.let { XmlUtils.findFirst(it, "UPnPError") }
            val code = upnpError?.let { XmlUtils.childText(it, "errorCode")?.toIntOrNull() } ?: httpCode
            val description = upnpError?.let { XmlUtils.childText(it, "errorDescription") }
                ?: fault?.let { XmlUtils.childText(it, "faultstring") }
                ?: describeError(code)
            throw UpnpException(action, code, description)
        }

        val response = XmlUtils.findFirst(root, "${action}Response") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val nodes = response.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) {
                result[node.localName ?: node.tagName] = node.textContent.trim()
            }
        }
        return result
    }

    /** Descriptions of common UPnP error codes, for the log and the user. */
    fun describeError(code: Int): String = when (code) {
        401 -> "Invalid action"
        402 -> "Invalid arguments"
        500 -> "Internal server error"
        501 -> "Action failed"
        701 -> "Transition not available"
        702 -> "No contents"
        703 -> "Read error"
        704 -> "Format not supported for playback"
        705 -> "Transport is locked"
        710 -> "Seek mode not supported"
        711 -> "Illegal seek target"
        714 -> "Illegal MIME-type"
        715 -> "Content busy"
        716 -> "Resource not found"
        718 -> "Invalid InstanceID"
        else -> "Unknown error"
    }
}
