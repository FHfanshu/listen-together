package app.listentogether.output

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class RelayedAudio(val url: String, val mime: String)

/**
 * A current-track-only LAN relay. Speakers need neither HTTPS support nor the user's API token.
 * This is not a general HTTP proxy: only the randomly named, currently published track is served.
 */
class LocalAudioRelay(
    private val address: InetAddress,
    private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : AutoCloseable {
    private data class Source(val path: String, val url: String, val mime: String)
    @Volatile private var source: Source? = null
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 4, address)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val upstreams = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "dlna-audio-relay").apply { isDaemon = true } }
    private val acceptor = Thread({
        while (!closed.get()) {
            try {
                val socket = server.accept()
                if (closed.get()) { socket.close(); break }
                socket.soTimeout = 6_000
                if (sockets.size >= 4) { socket.close(); continue }
                sockets.add(socket)
                try {
                    workers.submit { try { serve(socket) } finally { sockets.remove(socket); runCatching { socket.close() } } }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            } catch (_: Exception) { if (!closed.get()) Thread.sleep(100) }
        }
    }, "dlna-relay-listener").apply { isDaemon = true; start() }

    /** Blocking network probe; the caller runs this off the main thread. */
    fun publish(url: String): RelayedAudio {
        check(!closed.get())
        DlnaProtocol.httpUrl(url)
        val c = open(url)
        val mime = try {
            c.setRequestProperty("Range", "bytes=0-0")
            require(c.responseCode in 200..299) { "暂时无法取得歌曲音频，请刷新播放" }
            c.contentType?.substringBefore(';')?.trim()?.takeIf { it.startsWith("audio/") }
                ?: if (url.contains("quality=lossless")) "audio/flac" else "audio/mpeg"
        } finally { c.disconnect() }
        check(!closed.get())
        clear()
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val path = "/audio/$random"
        source = Source(path, url, mime)
        return RelayedAudio("http://${address.hostAddress}:${server.localPort}$path", mime)
    }

    fun clear() {
        source = null
        sockets.toList().forEach { runCatching { it.close() } }
        upstreams.toList().forEach { runCatching { it.disconnect() } }
    }

    private fun serve(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val request = readLine(input) ?: return
            val parts = request.split(' ')
            val method = parts.firstOrNull()
            val current = source
            if (parts.size != 3 || method !in setOf("GET", "HEAD")) { respond(socket, 405); return }
            if (current == null || parts[1] != current.path) { respond(socket, 404); return }
            var range: String? = null
            var headerSize = request.length
            while (true) {
                val line = readLine(input) ?: return
                headerSize += line.length
                if (headerSize > 16_384) { respond(socket, 431); return }
                if (line.isBlank()) break
                if (line.substringBefore(':').equals("Range", true)) range = line.substringAfter(':').trim()
            }
            if (range != null && (!range.matches(Regex("bytes=(?:\\d+-\\d*|-\\d+)")))) { respond(socket, 416); return }
            val c = open(current.url)
            upstreams.add(c)
            try {
                // Most music APIs expose GET, not HEAD. A one-byte ranged GET supplies full-size HEAD metadata.
                if (method == "HEAD") c.setRequestProperty("Range", "bytes=0-0")
                else range?.let { c.setRequestProperty("Range", it) }
                val status = c.responseCode
                if (status !in setOf(200, 206, 416)) { respond(socket, 502); return }
                val output = socket.getOutputStream()
                val headers = linkedMapOf("Content-Type" to current.mime, "Accept-Ranges" to "bytes", "Connection" to "close")
                for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")) {
                    c.getHeaderField(name)?.takeUnless { it.contains('\r') || it.contains('\n') }?.let { headers[name] = it }
                }
                val responseStatus = if (method == "HEAD" && status in 200..299) 200 else status
                if (method == "HEAD" && status == 206) {
                    val size = headers.remove("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    if (size != null) headers["Content-Length"] = size.toString() else headers.remove("Content-Length")
                }
                val header = "HTTP/1.1 $responseStatus ${if (responseStatus == 206) "Partial Content" else "OK"}\r\n" +
                    headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                output.write(header.toByteArray(Charsets.US_ASCII))
                if (method == "GET" && status in 200..299) c.inputStream.use { audio ->
                    val buffer = ByteArray(32_768)
                    while (source === current && !closed.get()) {
                        val n = audio.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
                output.flush()
            } finally { upstreams.remove(c); c.disconnect() }
        } catch (_: Exception) {
            // Receivers routinely close a stream when seeking or switching songs. No URL/token logging.
        }
    }

    private fun open(url: String): HttpURLConnection = connection(URL(url)).apply {
        connectTimeout = 8_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (out.length < 4096) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return out.toString().trimEnd('\r')
            out.append(b.toChar())
        }
        return null
    }

    private fun respond(socket: Socket, status: Int) {
        socket.getOutputStream().write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clear()
        server.close()
        workers.shutdownNow()
    }
}
