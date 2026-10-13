/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.schabi.newpipe.cast.dlna.CastLog

/**
 * The answer of the real server (e.g. googlevideo) to a request made by the proxy.
 *
 * @param contentLength the length of [body], -1 if unknown
 * @param body the content; null for answers without body
 */
class UpstreamResponse(
    val code: Int,
    val contentLength: Long,
    val contentRange: String?,
    val contentType: String?,
    val body: InputStream?,
    private val onClose: () -> Unit = { body?.close() }
) : Closeable {
    override fun close() = onClose()
}

/**
 * Downloads the content the proxy forwards to the TV.
 */
fun interface Upstream {
    /**
     * @param url the real URL of the video
     * @param range the range to request, or null for the whole file
     */
    @Throws(IOException::class)
    fun open(url: String, range: HttpRange?): UpstreamResponse
}

/**
 * A tiny HTTP/1.1 server running on the phone, which forwards requests from the TV to the real
 * video URL.
 *
 * It is needed because many DLNA renderers cannot play HTTPS URLs, have problems with very long
 * URLs (googlevideo ones are more than 1000 characters long) or cannot send the extra headers
 * YouTube requires. The TV gets a short `http://<phone ip>:<port>/v/<token>.mp4` URL instead.
 *
 * `GET` and `HEAD` are supported, as well as `Range` requests (answered with
 * `206 Partial Content`), which TVs use to seek. Only registered URLs are served, under a random
 * token, so the server cannot be used by others on the network as an open proxy.
 *
 * @param upstream how to download the real content
 * @param bindAddress the local address to listen on (the Wi-Fi address of the phone)
 */
class CastProxyServer(
    private val upstream: Upstream,
    private val bindAddress: InetAddress
) {
    private class Entry(val url: String, val mimeType: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    /**
     * Starts listening on a free port. Does nothing if already running.
     */
    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (isRunning) {
            return
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, 0))
        serverSocket = socket
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "CastProxy").apply { isDaemon = true }
        }
        executor = pool
        pool.execute { acceptLoop(socket, pool) }
        CastLog.d("Proxy: listening on ${bindAddress.hostAddress}:${socket.localPort}")
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // already closed
        }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        entries.clear()
        CastLog.d("Proxy: stopped")
    }

    /**
     * Makes [url] available through the proxy.
     *
     * @return the URL to give to the TV
     */
    fun register(url: String, mimeType: String): String {
        val token = ByteArray(12).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(Locale.US, it) }
        entries[token] = Entry(url, mimeType)
        return "http://${hostForUrl()}:$port/v/$token.${extensionFor(mimeType)}"
    }

    private fun hostForUrl(): String {
        val host = bindAddress.hostAddress.orEmpty()
        return if (host.contains(':')) "[$host]" else host
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                pool.execute { handleClient(client) }
            } catch (e: SocketException) {
                if (!socket.isClosed) {
                    CastLog.e("Proxy: accept failed", e)
                }
            } catch (e: Exception) {
                CastLog.e("Proxy: accept failed", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = CLIENT_TIMEOUT_MILLIS
                val input = BufferedInputStream(socket.getInputStream())
                val request = HttpRequest.read(input) ?: return
                handle(request, socket.inetAddress?.hostAddress, socket.getOutputStream())
            } catch (e: SocketException) {
                // the TV closed the connection, e.g. because it seeks: this is normal
                CastLog.d("Proxy: connection closed by client (${e.message})")
            } catch (e: Exception) {
                CastLog.e("Proxy: error while handling a request", e)
            }
        }
    }

    /**
     * Handles one request. Internal so that it can be tested without sockets.
     */
    internal fun handle(request: HttpRequest, clientAddress: String?, output: OutputStream) {
        CastLog.d(
            "Proxy <- ${request.method} ${request.path} from $clientAddress\n" +
                request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
        if (request.method != "GET" && request.method != "HEAD") {
            writeSimple(output, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n")
            return
        }
        val token = request.path.substringBefore('?').removePrefix("/v/").substringBefore('.')
        val entry = entries[token]
        if (!request.path.startsWith("/v/") || entry == null) {
            writeSimple(output, 404, "Not Found")
            return
        }

        val range = try {
            HttpRange.parse(request.header("Range"))
        } catch (e: IllegalArgumentException) {
            CastLog.w("Proxy: ${e.message}")
            writeSimple(output, 416, "Range Not Satisfiable")
            return
        }
        val isHead = request.method == "HEAD"

        // For HEAD requests without range only the first byte is asked, to learn the total
        // length from Content-Range without downloading the whole file
        val upstreamRange = if (isHead && range == null) HttpRange(0, 0) else range
        val upstreamResponse = try {
            upstream.open(entry.url, upstreamRange)
        } catch (e: IOException) {
            CastLog.e("Proxy: could not reach the video server", e)
            writeSimple(output, 502, "Bad Gateway")
            return
        }
        upstreamResponse.use { response ->
            CastLog.d(
                "Proxy: upstream HTTP ${response.code}, length ${response.contentLength}, " +
                    "range ${response.contentRange}, type ${response.contentType}"
            )
            when {
                response.code == 416 -> {
                    val total = HttpRange.totalLengthFromContentRange(response.contentRange)
                    val extra = if (total >= 0) "Content-Range: bytes */$total\r\n" else ""
                    writeSimple(output, 416, "Range Not Satisfiable", extra)
                }

                response.code !in 200..299 -> writeSimple(output, 502, "Bad Gateway")

                isHead && range == null -> {
                    val total = if (response.code == 206) {
                        HttpRange.totalLengthFromContentRange(response.contentRange)
                    } else {
                        response.contentLength
                    }
                    writeHeaders(output, 200, "OK", entry.mimeType, total, null)
                }

                else -> {
                    val partial = response.code == 206
                    // if the upstream ignored the range, the whole file is sent with 200
                    writeHeaders(
                        output,
                        if (partial) 206 else 200,
                        if (partial) "Partial Content" else "OK",
                        entry.mimeType,
                        response.contentLength,
                        if (partial) response.contentRange else null
                    )
                    if (!isHead) {
                        response.body?.let { copy(it, output) }
                    }
                }
            }
            output.flush()
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        contentLength: Long,
        contentRange: String?
    ) {
        val headers = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
        if (contentLength >= 0) {
            headers.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        if (contentRange != null) {
            headers.append("Content-Range: ").append(contentRange).append("\r\n")
        }
        headers.append("transferMode.dlna.org: Streaming\r\n")
            .append("contentFeatures.dlna.org: ").append(DLNA_CONTENT_FEATURES).append("\r\n")
            .append("Server: ").append(SERVER).append("\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
        CastLog.d("Proxy -> ${headers.toString().trim()}")
        output.write(headers.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeSimple(output: OutputStream, code: Int, reason: String, extraHeaders: String = "") {
        CastLog.d("Proxy -> HTTP $code $reason")
        val text = "HTTP/1.1 $code $reason\r\n" +
            extraHeaders +
            "Content-Length: 0\r\n" +
            "Server: $SERVER\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        private const val CLIENT_TIMEOUT_MILLIS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val SERVER = "Android UPnP/1.0 NewPipe-Cast/1.0"

        /** DLNA.ORG_OP=01: seeking by byte range is supported; FLAGS: streaming transfer mode. */
        const val DLNA_CONTENT_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        fun extensionFor(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "audio/mp4", "audio/m4a" -> "m4a"
            "video/3gpp" -> "3gp"
            else -> "bin"
        }
    }
}

/**
 * A parsed HTTP request: only what the proxy needs.
 *
 * @param headers the headers, with lower case names
 */
class HttpRequest(val method: String, val path: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024

        /**
         * Reads the request line and headers from [input].
         * @return the request, or null if the connection was closed before a request arrived
         * @throws IOException if the request is malformed or too big
         */
        @Throws(IOException::class)
        fun read(input: InputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                throw IOException("Malformed request line: $requestLine")
            }
            val headers = LinkedHashMap<String, String>()
            var total = requestLine.length
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) {
                    break
                }
                total += line.length
                if (total > MAX_HEADER_BYTES) {
                    throw IOException("Request headers too big")
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }
            return HttpRequest(parts[0].uppercase(Locale.US), parts[1], headers)
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) {
                    return if (line.isEmpty()) null else line.toString()
                }
                if (c == '\n'.code) {
                    return line.toString().removeSuffix("\r")
                }
                line.append(c.toChar())
                if (line.length > MAX_HEADER_BYTES) {
                    throw IOException("Request line too long")
                }
            }
        }
    }
}
