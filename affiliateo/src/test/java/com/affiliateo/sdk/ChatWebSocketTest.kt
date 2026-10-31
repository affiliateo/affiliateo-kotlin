package com.affiliateo.sdk

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ChatWebSocket against a tiny WebSocket server on this machine: the upgrade
 * and its key check, frames both ways, fragments, ping, close and timeouts.
 */
class ChatWebSocketTest {
    /** One connection, run by [script] on its own thread. */
    private class Server(private val script: Server.(Conn) -> Unit) {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port get() = socket.localPort
        val failures = LinkedBlockingQueue<Throwable>()
        private val thread = Thread {
            try {
                socket.accept().use { script(Conn(it)) }
            } catch (e: Throwable) {
                failures.add(e)
            }
        }.apply { isDaemon = true; start() }

        fun stop() {
            socket.close()
            thread.join(2_000)
        }
    }

    private class Frame(val fin: Boolean, val op: Int, val masked: Boolean, val payload: ByteArray) {
        val text get() = String(payload, Charsets.UTF_8)
    }

    private class Conn(val socket: Socket) {
        val input: InputStream = BufferedInputStream(socket.getInputStream())
        val output: OutputStream = socket.getOutputStream()

        init {
            socket.soTimeout = 5_000
        }

        /** The request line and headers (names lowercased). */
        fun readRequest(): Pair<String, Map<String, String>> {
            val bytes = ByteArrayOutputStream()
            while (!bytes.toString("ISO-8859-1").endsWith("\r\n\r\n")) bytes.write(input.read().also { check(it >= 0) })
            val lines = bytes.toString("ISO-8859-1").trimEnd().split("\r\n")
            return lines.first() to lines.drop(1).associate {
                val colon = it.indexOf(':')
                it.substring(0, colon).lowercase() to it.substring(colon + 1).trim()
            }
        }

        fun write(text: String) {
            output.write(text.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        /** Reads the upgrade and agrees to it; the request's headers. */
        fun accept(): Map<String, String> {
            val (_, headers) = readRequest()
            write(
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${ChatWebSocket.acceptFor(headers.getValue("sec-websocket-key"))}\r\n\r\n",
            )
            return headers
        }

        fun send(op: Int, payload: ByteArray, fin: Boolean = true, mask: Boolean = false) {
            val head = ByteArrayOutputStream()
            head.write((if (fin) 0x80 else 0) or op)
            val maskBit = if (mask) 0x80 else 0
            when {
                payload.size < 126 -> head.write(maskBit or payload.size)
                payload.size <= 0xFFFF -> {
                    head.write(maskBit or 126)
                    head.write(payload.size shr 8)
                    head.write(payload.size and 0xFF)
                }
                else -> {
                    head.write(maskBit or 127)
                    for (shift in 56 downTo 0 step 8) head.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            val key = byteArrayOf(1, 2, 3, 4)
            if (mask) head.write(key)
            output.write(head.toByteArray())
            output.write(if (mask) ByteArray(payload.size) { (payload[it].toInt() xor key[it and 3].toInt()).toByte() } else payload)
            output.flush()
        }

        fun sendText(text: String, fin: Boolean = true) = send(ChatWebSocket.OP_TEXT, text.toByteArray(Charsets.UTF_8), fin)

        /** One frame from the client, unmasked. */
        fun read(): Frame {
            val b0 = input.read()
            val b1 = input.read()
            check(b0 >= 0 && b1 >= 0) { "the client went away" }
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
            if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            val key = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            if (key != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor key[i and 3].toInt()).toByte()
            return Frame(b0 and 0x80 != 0, b0 and 0x0F, key != null, payload)
        }

        private fun readFully(buffer: ByteArray) {
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                check(n >= 0) { "the client went away" }
                read += n
            }
        }
    }

    /** What the client heard, in order. */
    private class Events : ChatWebSocket.Listener {
        val queue = LinkedBlockingQueue<String>()
        var closedError: Throwable? = null

        override fun onOpen(socket: ChatWebSocket) {
            queue.add("open")
        }

        override fun onText(socket: ChatWebSocket, text: String) {
            queue.add("text:$text")
        }

        override fun onClosed(socket: ChatWebSocket, httpStatus: Int?, error: Throwable?) {
            closedError = error
            queue.add("closed:$httpStatus")
        }

        fun next(seconds: Long = 5): String? = queue.poll(seconds, TimeUnit.SECONDS)
    }

    private val servers = mutableListOf<Server>()
    private val clients = mutableListOf<ChatWebSocket>()

    private fun server(script: Server.(Conn) -> Unit) = Server(script).also { servers.add(it) }

    private fun client(server: Server, events: Events, path: String = "/s", headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 75_000) =
        ChatWebSocket("ws://127.0.0.1:${server.port}$path", headers, events, connectTimeoutMs = 5_000, readTimeoutMs = readTimeoutMs)
            .also { clients.add(it); it.connect() }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop() }
        for (server in servers) server.failures.poll()?.let { throw it }
    }

    @Test
    fun theUpgradeSendsTheKeyAndTheHeadersAndChecksTheAnswer() {
        val request = LinkedBlockingQueue<Pair<String, Map<String, String>>>()
        val heard = LinkedBlockingQueue<String>()
        val events = Events()
        val srv = server { conn ->
            val (line, headers) = conn.readRequest()
            request.add(line to headers)
            conn.write(
                "HTTP/1.1 101 Switching Protocols\r\nupgrade: WebSocket\r\nConnection: keep-alive, Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${ChatWebSocket.acceptFor(headers.getValue("sec-websocket-key"))}\r\n\r\n",
            )
            conn.sendText("hello")
            val frame = conn.read()
            assertTrue("client frames are masked", frame.masked)
            assertEquals(ChatWebSocket.OP_TEXT, frame.op)
            heard.add(frame.text)
        }
        val socket = client(srv, events, path = "/api/live-chat/stream?app=my%20app", headers = mapOf("Authorization" to "Bearer lct_x", "Bad" to "a\r\nX-Evil: 1"))
        assertEquals("open", events.next())
        assertEquals("text:hello", events.next())
        assertTrue(socket.send("ping"))
        assertEquals("ping", heard.poll(5, TimeUnit.SECONDS))

        val (line, headers) = request.poll(5, TimeUnit.SECONDS)!!
        assertEquals("GET /api/live-chat/stream?app=my%20app HTTP/1.1", line)
        assertEquals("127.0.0.1:${srv.port}", headers["host"])
        assertEquals("websocket", headers["upgrade"])
        assertEquals("Upgrade", headers["connection"])
        assertEquals("13", headers["sec-websocket-version"])
        assertEquals("Bearer lct_x", headers["authorization"])
        assertEquals(16, Base64.getDecoder().decode(headers["sec-websocket-key"]).size)
        // A header with a line break in it is left out, not smuggled in.
        assertNull(headers["bad"])
        assertNull(headers["x-evil"])
    }

    @Test
    fun aWrongAcceptKeyFailsTheUpgrade() {
        val events = Events()
        val srv = server { conn ->
            conn.readRequest()
            conn.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: bm9wZQ==\r\n\r\n")
            conn.input.read()
        }
        client(srv, events)
        assertEquals("closed:null", events.next())
        assertTrue(events.closedError is java.net.ProtocolException)
    }

    @Test
    fun aRefusalCarriesTheHttpStatus() {
        val events = Events()
        val srv = server { conn ->
            conn.readRequest()
            conn.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
        }
        client(srv, events)
        assertEquals("closed:404", events.next())
    }

    @Test
    fun longFramesUseTheLongerLengths() {
        val events = Events()
        val medium = "m".repeat(300)
        val long = "l".repeat(70_000)
        val srv = server { conn ->
            conn.accept()
            conn.sendText(medium)
            conn.sendText(long)
            conn.read()
        }
        val socket = client(srv, events)
        assertEquals("open", events.next())
        assertEquals("text:$medium", events.next())
        assertEquals("text:$long", events.next())
        socket.close()
    }

    @Test
    fun fragmentsMakeOneMessageAndAPingBetweenThemIsAnswered() {
        val events = Events()
        val pong = LinkedBlockingQueue<Frame>()
        val srv = server { conn ->
            conn.accept()
            conn.sendText("hel", fin = false)
            conn.send(ChatWebSocket.OP_PING, "p1".toByteArray())
            conn.send(ChatWebSocket.OP_CONTINUATION, "lo ".toByteArray(), fin = false)
            conn.send(ChatWebSocket.OP_CONTINUATION, "world".toByteArray(), fin = true)
            pong.add(conn.read())
            conn.read()
        }
        client(srv, events)
        assertEquals("open", events.next())
        assertEquals("text:hello world", events.next())
        val frame = pong.poll(5, TimeUnit.SECONDS)!!
        assertEquals(ChatWebSocket.OP_PONG, frame.op)
        assertTrue(frame.masked)
        assertEquals("p1", frame.text)
    }

    @Test
    fun theServersCloseIsAnsweredAndReported() {
        val events = Events()
        val answer = LinkedBlockingQueue<Frame>()
        val srv = server { conn ->
            conn.accept()
            conn.send(ChatWebSocket.OP_CLOSE, byteArrayOf(0x03, 0xE9.toByte()))
            answer.add(conn.read())
        }
        client(srv, events)
        assertEquals("open", events.next())
        assertEquals("closed:null", events.next())
        assertNull(events.closedError)
        val frame = answer.poll(5, TimeUnit.SECONDS)!!
        assertEquals(ChatWebSocket.OP_CLOSE, frame.op)
        assertArrayEquals(byteArrayOf(0x03, 0xE9.toByte()), frame.payload)
    }

    @Test
    fun silenceEndsTheSocketAfterTheReadTimeout() {
        val events = Events()
        val srv = server { conn ->
            conn.accept()
            Thread.sleep(1_500)
        }
        client(srv, events, readTimeoutMs = 300)
        assertEquals("open", events.next())
        assertEquals("closed:null", events.next())
        assertTrue(events.closedError is SocketTimeoutException)
    }

    @Test
    fun aMaskedFrameFromTheServerClosesWith1002() {
        val events = Events()
        val answer = LinkedBlockingQueue<Frame>()
        val srv = server { conn ->
            conn.accept()
            conn.send(ChatWebSocket.OP_TEXT, "x".toByteArray(), mask = true)
            answer.add(conn.read())
        }
        client(srv, events)
        assertEquals("open", events.next())
        assertEquals("closed:null", events.next())
        val frame = answer.poll(5, TimeUnit.SECONDS)!!
        assertEquals(ChatWebSocket.OP_CLOSE, frame.op)
        assertArrayEquals(byteArrayOf(0x03, 0xEA.toByte()), frame.payload)
    }

    @Test
    fun textThatIsNotUtf8ClosesWith1007() {
        val events = Events()
        val answer = LinkedBlockingQueue<Frame>()
        val srv = server { conn ->
            conn.accept()
            conn.send(ChatWebSocket.OP_TEXT, byteArrayOf(0xC3.toByte(), 0x28))
            answer.add(conn.read())
        }
        client(srv, events)
        assertEquals("open", events.next())
        assertEquals("closed:null", events.next())
        assertArrayEquals(byteArrayOf(0x03, 0xEF.toByte()), answer.poll(5, TimeUnit.SECONDS)!!.payload)
    }

    @Test
    fun closeSendsACloseFrameAndReportsNothing() {
        val events = Events()
        val answer = LinkedBlockingQueue<Frame>()
        val srv = server { conn ->
            conn.accept()
            answer.add(conn.read())
        }
        val socket = client(srv, events)
        assertEquals("open", events.next())
        socket.close()
        val frame = answer.poll(5, TimeUnit.SECONDS)!!
        assertEquals(ChatWebSocket.OP_CLOSE, frame.op)
        assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), frame.payload)
        assertNull(events.next(1))
        assertEquals(false, socket.send("late"))
    }

    @Test
    fun nobodyListeningIsReportedOnce() {
        val events = Events()
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        ChatWebSocket("ws://127.0.0.1:$free/s", emptyMap(), events, connectTimeoutMs = 2_000).also { clients.add(it) }.connect()
        assertEquals("closed:null", events.next())
        assertNull(events.next(1))
    }

    @Test
    fun theAcceptKeyAndBase64MatchTheRfc() {
        // RFC 6455, section 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", ChatWebSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
        val random = Random(7)
        for (size in 0..40) {
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            assertEquals(Base64.getEncoder().encodeToString(bytes), ChatWebSocket.base64(bytes))
        }
    }
}
