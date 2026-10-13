/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

/** An HTTP answer read by [LanHttp]. Header names are lower case. */
class LanHttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    val isSuccessful: Boolean
        get() = code in 200..299

    fun bodyAsString(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * Minimal blocking HTTP/1.1 client for talking to devices on the local network.
 *
 * DLNA devices only speak plain HTTP, but apps targeting Android 9+ cannot use cleartext HTTP
 * with OkHttp or HttpURLConnection unless cleartext traffic is allowed for the whole app (the
 * network security config cannot express "only private IP addresses"). Instead of weakening
 * the policy for every connection NewPipe makes, this client is used for UPnP only, and it
 * refuses any address that is not local (private, link-local or loopback).
 */
object LanHttp {
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private const val MAX_LINE_LENGTH = 16 * 1024

    @Throws(IOException::class)
    fun request(
        url: String,
        method: String = "GET",
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        connectTimeoutMillis: Int = 5000,
        readTimeoutMillis: Int = 10000
    ): LanHttpResponse {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IOException("Invalid URL: $url", e)
        }
        if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrEmpty()) {
            throw IOException("Only http:// URLs are supported on the local network: $url")
        }
        val address = InetAddress.getByName(uri.host)
        if (!isLocalAddress(address)) {
            throw IOException("Refusing to connect to non-local address ${address.hostAddress}")
        }
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = if (uri.port > 0) "${uri.host}:$port" else uri.host

        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis

            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
            for ((name, value) in headers) {
                head.append(name).append(": ").append(value).append("\r\n")
            }
            if (body != null) {
                head.append("Content-Length: ").append(body.size).append("\r\n")
            }
            head.append("Connection: close\r\n\r\n")

            val output = socket.getOutputStream()
            output.write(head.toString().toByteArray(StandardCharsets.UTF_8))
            body?.let { output.write(it) }
            output.flush()

            return readResponse(BufferedInputStream(socket.getInputStream()), method == "HEAD")
        }
    }

    /**
     * @return true for addresses that can only be on the local network: private IPv4 ranges,
     * link-local, loopback and IPv6 unique local addresses (fc00::/7)
     */
    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress) {
            return true
        }
        return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }

    /** Reads status line, headers and body. Internal so that it can be tested with a stream. */
    @Throws(IOException::class)
    internal fun readResponse(input: InputStream, isHead: Boolean = false): LanHttpResponse {
        val statusLine = readLine(input) ?: throw IOException("Empty answer")
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed status line: $statusLine")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) {
                break
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
        }

        val body = when {
            isHead || code == 204 || code == 304 -> ByteArray(0)

            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)

            headers["content-length"] != null -> {
                val length = headers["content-length"]!!.toIntOrNull()
                    ?: throw IOException("Malformed Content-Length")
                if (length > MAX_BODY_BYTES) throw IOException("Answer too big: $length bytes")
                readExactly(input, length)
            }

            else -> readUntilEnd(input)
        }
        return LanHttpResponse(code, headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("Unexpected end of chunked body")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("Malformed chunk size: $sizeLine")
            if (size == 0) {
                // skip trailers until the empty line
                while (true) {
                    val trailer = readLine(input)
                    if (trailer.isNullOrEmpty()) break
                }
                return result.toByteArray()
            }
            if (result.size() + size > MAX_BODY_BYTES) throw IOException("Answer too big")
            result.write(readExactly(input, size))
            readLine(input) // the CRLF after the chunk
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) throw IOException("Unexpected end of body ($read of $length bytes)")
            read += count
        }
        return buffer
    }

    private fun readUntilEnd(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            result.write(buffer, 0, count)
            if (result.size() > MAX_BODY_BYTES) throw IOException("Answer too big")
        }
        return result.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) {
                return if (line.size() == 0) null else line.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (c == '\n'.code) {
                return line.toString(StandardCharsets.ISO_8859_1.name()).removeSuffix("\r")
            }
            line.write(c)
            if (line.size() > MAX_LINE_LENGTH) throw IOException("Line too long")
        }
    }
}
