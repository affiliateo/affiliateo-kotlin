package com.affiliateo.sdk

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small WebSocket client (RFC 6455) for Live Chat's live sockets: the
 * thread's red number and the app channel. Android has no WebSocket of its
 * own and the SDK adds no library for one, so this is all of it:
 *
 * - TLS through the platform's own SSLSocketFactory, with the host name sent
 *   (SNI) and checked against the certificate;
 * - the HTTP/1.1 upgrade, with its Sec-WebSocket-Key / Accept check;
 * - masked text frames out; text, binary (ignored), ping, pong, close and
 *   fragmented frames in; anything else closes the socket;
 * - a read timeout: nothing heard for [readTimeoutMs] (the SDK pings every
 *   30 s, the server answers) is a dead socket.
 *
 * One background thread connects and reads; writes go through one more, so
 * nothing touches the network on the caller's thread (Android refuses that on
 * the main thread). The listener hears from the reading thread.
 */
internal class ChatWebSocket(
    private val url: String,
    private val headers: Map<String, String>,
    private val listener: Listener,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 75_000,
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val hostnameVerifier: HostnameVerifier? = null,
) {
    interface Listener {
        fun onOpen(socket: ChatWebSocket) {}

        fun onText(socket: ChatWebSocket, text: String)

        /**
         * The socket ended without close() being called: the server closed
         * it, the network dropped, it timed out, or the upgrade failed.
         * [httpStatus] is the server's answer when it refused the upgrade
         * (404, say), null otherwise. Called at most once.
         */
        fun onClosed(socket: ChatWebSocket, httpStatus: Int?, error: Throwable?)
    }

    /** Set by close() or by the end of the socket, whichever comes first. */
    private val ended = AtomicBoolean(false)
    private val random = SecureRandom()
    private val writeLock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "affiliateo-chat-socket-write").apply { isDaemon = true }
    }

    @Volatile private var socket: Socket? = null

    /** Set once the upgrade succeeded: frames go out only after that. */
    @Volatile private var output: OutputStream? = null

    /** Connects on a background thread. */
    fun connect() {
        Thread({ run() }, "affiliateo-chat-socket").apply { isDaemon = true }.start()
    }

    /** Sends a text frame, off the caller's thread. False once the socket has ended. */
    fun send(text: String): Boolean = enqueue { writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8)) }

    /** Ends the socket: a close frame (1000) to the server, then the connection. No onClosed follows. */
    fun close() {
        if (!ended.compareAndSet(false, true)) return
        val queued = enqueue {
            try {
                writeFrame(OP_CLOSE, closePayload(1000))
            } catch (_: Exception) {
                // Going anyway.
            }
            shutdown()
        }
        if (!queued) shutdown()
        writer.shutdown()
    }

    private fun enqueue(block: () -> Unit): Boolean = try {
        writer.execute {
            try {
                block()
            } catch (_: Exception) {
                // A write that fails ends the socket through the reader.
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun shutdown() {
        try {
            socket?.close()
        } catch (_: Exception) {
            // Already closed.
        }
    }

    // ── Connecting ────────────────────────────────────────────────────────

    private fun run() {
        var refused: Int? = null
        try {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> throw ProtocolException("not a WebSocket address")
            }
            val hostHeader = uri.host ?: throw ProtocolException("no host")
            val host = hostHeader.removePrefix("[").removeSuffix("]")
            val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
            val raw = Socket()
            socket = raw
            // close() before this socket was known: nothing to do.
            if (ended.get()) {
                shutdown()
                return
            }
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = connectTimeoutMs
            raw.tcpNoDelay = true
            val connected = if (secure) tls(raw, host, port) else raw
            socket = connected
            if (ended.get()) {
                shutdown()
                return
            }
            val input = BufferedInputStream(connected.getInputStream())
            val out = connected.getOutputStream()
            val key = base64(ByteArray(16).also { random.nextBytes(it) })
            val defaultPort = port == (if (secure) 443 else 80)
            out.write(request(uri, if (defaultPort) hostHeader else "$hostHeader:$port", key).toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val head = readHead(input)
            if (head.status != 101) {
                refused = head.status
                throw ProtocolException("the server answered ${head.status}, not 101")
            }
            checkUpgrade(head, key)
            connected.soTimeout = readTimeoutMs
            output = out
            if (ended.get()) return
            listener.onOpen(this)
            readFrames(input)
        } catch (e: Exception) {
            if (e is Failure) sendCloseQuietly(e.code)
            finish(refused, e)
        }
    }

    /** TLS over the connected socket: the platform's own trust, SNI, and the certificate checked for [host]. */
    private fun tls(raw: Socket, host: String, port: Int): SSLSocket {
        val factory = sslSocketFactory ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        if (!isIpAddress(host)) {
            try {
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            } catch (_: Exception) {
                // The platform names the host from createSocket(host) itself.
            }
        }
        val identified = try {
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.sslParameters.endpointIdentificationAlgorithm == "HTTPS"
        } catch (_: Exception) {
            // Not on this platform: the check after the handshake stays.
            false
        }
        ssl.startHandshake()
        val verifier = hostnameVerifier ?: HttpsURLConnection.getDefaultHostnameVerifier()
        // A plain JVM's default verifier says no to everything (it is only a
        // fallback there); when the handshake already checked the name, it is
        // skipped. Android's default (OkHostnameVerifier) checks for real.
        val denyAllFallback = hostnameVerifier == null &&
            verifier.javaClass.name == "javax.net.ssl.HttpsURLConnection\$DefaultHostnameVerifier"
        if (!(identified && denyAllFallback) && !verifier.verify(host, ssl.session)) {
            ssl.close()
            throw SSLPeerUnverifiedException("the certificate is not for $host")
        }
        return ssl
    }

    private fun request(uri: URI, host: String, key: String): String {
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val text = StringBuilder()
        text.append("GET ").append(path).append(" HTTP/1.1\r\n")
        text.append("Host: ").append(host).append("\r\n")
        text.append("Upgrade: websocket\r\n")
        text.append("Connection: Upgrade\r\n")
        text.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        text.append("Sec-WebSocket-Version: 13\r\n")
        for ((name, value) in headers) {
            // No line breaks smuggled into the request.
            if (name.any { it == '\r' || it == '\n' || it == ':' } || value.any { it == '\r' || it == '\n' }) continue
            text.append(name).append(": ").append(value).append("\r\n")
        }
        text.append("\r\n")
        return text.toString()
    }

    private class Head(val status: Int, val headers: Map<String, String>)

    /** The server's status line and headers, up to the blank line (16 KB at most). */
    private fun readHead(input: InputStream): Head {
        val bytes = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("the server closed during the upgrade")
            bytes.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (bytes.size() > 16_384) throw ProtocolException("the upgrade answer is too long")
        }
        val lines = String(bytes.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val status = Regex("^HTTP/1\\.[01] (\\d{3})").find(lines.first())?.groupValues?.get(1)?.toInt()
            ?: throw ProtocolException("not an HTTP answer")
        val found = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            found[name] = found[name]?.let { "$it, $value" } ?: value
        }
        return Head(status, found)
    }

    private fun checkUpgrade(head: Head, key: String) {
        if (head.headers["upgrade"]?.equals("websocket", ignoreCase = true) != true) throw ProtocolException("no Upgrade: websocket")
        if (head.headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } != true) {
            throw ProtocolException("no Connection: Upgrade")
        }
        if (head.headers["sec-websocket-accept"] != acceptFor(key)) throw ProtocolException("wrong Sec-WebSocket-Accept")
        // Nothing was asked for, so nothing may be agreed to.
        if (!head.headers["sec-websocket-extensions"].isNullOrEmpty()) throw ProtocolException("an extension nobody asked for")
        if (!head.headers["sec-websocket-protocol"].isNullOrEmpty()) throw ProtocolException("a subprotocol nobody asked for")
    }

    // ── Frames ────────────────────────────────────────────────────────────

    /** A broken rule on the server's side: the socket closes with [code]. */
    private class Failure(val code: Int, message: String) : IOException(message)

    private fun readFrames(input: InputStream) {
        var message: ByteArrayOutputStream? = null
        var messageOp = 0
        while (!ended.get()) {
            val b0 = input.read()
            if (b0 < 0) throw EOFException("the server went away")
            val b1 = readByte(input)
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0F
            if (b0 and 0x70 != 0) throw Failure(1002, "reserved bits set")
            if (b1 and 0x80 != 0) throw Failure(1002, "a masked frame from the server")
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = readNumber(input, 2)
            } else if (length == 127L) {
                length = readNumber(input, 8)
            }
            if (op >= OP_CLOSE && (!fin || length > 125)) throw Failure(1002, "a broken control frame")
            if (length < 0 || length > MAX_MESSAGE) throw Failure(1009, "a frame too big")
            val payload = readFully(input, length.toInt())
            when (op) {
                OP_CONTINUATION -> {
                    val parts = message ?: throw Failure(1002, "a continuation with nothing to continue")
                    if (parts.size() + payload.size > MAX_MESSAGE) throw Failure(1009, "a message too big")
                    parts.write(payload)
                    if (fin) {
                        message = null
                        deliver(messageOp, parts.toByteArray())
                    }
                }
                OP_TEXT, OP_BINARY -> {
                    if (message != null) throw Failure(1002, "a new message inside another")
                    if (fin) {
                        deliver(op, payload)
                    } else {
                        message = ByteArrayOutputStream().also { it.write(payload) }
                        messageOp = op
                    }
                }
                OP_PING -> writeFrame(OP_PONG, payload)
                OP_PONG -> {}
                OP_CLOSE -> {
                    // Answered with its own code, then the connection ends.
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1000
                    sendCloseQuietly(code)
                    finish(null, null)
                    return
                }
                else -> throw Failure(1002, "an unknown frame type $op")
            }
        }
    }

    private fun deliver(op: Int, payload: ByteArray) {
        if (op != OP_TEXT || ended.get()) return
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (e: CharacterCodingException) {
            throw Failure(1007, "a text frame that is not UTF-8")
        }
        listener.onText(this, text)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("the server went away")
        return b
    }

    private fun readNumber(input: InputStream, bytes: Int): Long {
        var value = 0L
        repeat(bytes) { value = (value shl 8) or readByte(input).toLong() }
        return value
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("the server went away")
            read += n
        }
        return buffer
    }

    /** One masked frame out; from the writer, or from the reader for pongs and the close answer. */
    private fun writeFrame(op: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val out = output ?: return
            val head = ByteArrayOutputStream(14)
            head.write(0x80 or op)
            val size = payload.size
            when {
                size < 126 -> head.write(0x80 or size)
                size <= 0xFFFF -> {
                    head.write(0x80 or 126)
                    head.write(size shr 8)
                    head.write(size and 0xFF)
                }
                else -> {
                    head.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) head.write(((size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            val mask = ByteArray(4).also { random.nextBytes(it) }
            head.write(mask)
            val masked = ByteArray(size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
            out.write(head.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    private fun sendCloseQuietly(code: Int) {
        try {
            writeFrame(OP_CLOSE, closePayload(code))
        } catch (_: Exception) {
            // Gone already.
        }
    }

    private fun closePayload(code: Int) = byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte())

    private fun finish(refused: Int?, error: Throwable?) {
        val first = ended.compareAndSet(false, true)
        shutdown()
        writer.shutdown()
        if (first) listener.onClosed(this, refused, error)
    }

    companion object {
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        /** The biggest message taken in (the frames here are a few KB). */
        const val MAX_MESSAGE = 1 shl 20

        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** The Sec-WebSocket-Accept the server must answer for [key]. */
        fun acceptFor(key: String): String =
            base64(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)))

        /** Standard Base64 with padding (java.util.Base64 needs API 26; android.util.Base64 is not in JVM tests). */
        fun base64(data: ByteArray): String {
            val out = StringBuilder((data.size + 2) / 3 * 4)
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
                out.append(B64[b0 shr 2])
                out.append(B64[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
                out.append(if (b1 < 0) '=' else B64[((b1 and 0x0F) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
                out.append(if (b2 < 0) '=' else B64[b2 and 0x3F])
                i += 3
            }
            return out.toString()
        }

        private fun isIpAddress(host: String) = host.contains(':') || host.all { it.isDigit() || it == '.' }
    }
}
