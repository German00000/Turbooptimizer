package com.screencastpro.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP mínimo para la red local. Rutas (todas con el token de sesión como primer segmento):
 *   /{token}/live.ts      MPEG-TS continuo (VLC, reproductores HTTP, Android TV con reproductor compatible)
 *   /{token}/live.m3u8    Lista HLS en vivo (Chromecast / Google Cast Default Media Receiver)
 *   /{token}/seg/N.ts     Segmento HLS
 *   /{token}/status       Estado en JSON
 *
 * El token evita que otros dispositivos de la misma Wi-Fi vean la pantalla sin conocer la URL.
 * Sin HTTPS: el tráfico queda en la red local; no se publica en Internet.
 */
class StreamServer(
    private val hub: StreamHub,
    private val port: Int,
    private val token: String,
    private val mp4Hub: StreamHub? = null,
    private val maxClients: Int = 4,
    private val log: (String) -> Unit = {},
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val active = AtomicInteger(0)

    /** Arranca el servidor y devuelve el puerto real (útil si se pasa 0). */
    @Synchronized
    fun start(): Int {
        if (running.get()) return server!!.localPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        pool = Executors.newCachedThreadPool { r -> Thread(r, "stream-http").apply { isDaemon = true } }
        running.set(true)
        acceptThread = Thread({ acceptLoop(ss) }, "stream-accept").apply { isDaemon = true; start() }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: IOException) {}
        server = null
        pool?.shutdownNow()
        pool = null
        acceptThread = null
    }

    fun isRunning(): Boolean = running.get()

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try { ss.accept() } catch (_: IOException) { break }
            val p = pool ?: break
            if (active.get() >= maxClients + 8) { // margen para peticiones HLS cortas
                respondSimple(sock, 503, "Service Unavailable")
                try { sock.close() } catch (_: IOException) {}
                continue
            }
            active.incrementAndGet()
            p.execute {
                try {
                    handle(sock)
                } catch (_: Exception) {
                    // Un cliente con datos raros no debe tumbar el proceso.
                    log("error de cliente ignorado")
                } finally {
                    active.decrementAndGet()
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respondSimple(s, 400, "Bad Request")
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            log("HTTP $method ${path.replace(token, "***")}")
            if (method != "GET" && method != "HEAD") return respondSimple(s, 405, "Method Not Allowed")

            hub.touch()
            val prefix = "/$token/"
            if (!path.startsWith(prefix)) return respondSimple(s, 404, "Not Found")
            val rest = path.substring(prefix.length)
            val out = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
            when {
                rest == "live.ts" -> serveLive(s, out, hub, contentType = "video/mp2t", init = null)
                rest == "live.mp4" -> {
                    val m = mp4Hub
                    val init = m?.initSegment
                    if (m == null || init == null) respond(out, 503, "text/plain", "stream not ready".toByteArray(), method)
                    else serveLive(s, out, m, contentType = "video/mp4", init = init)
                }
                rest == "live.m3u8" -> respond(out, 200, "application/vnd.apple.mpegurl", hub.segmenter.playlist().toByteArray(), method)
                rest.startsWith("seg/") && rest.endsWith(".ts") -> {
                    val seq = rest.removePrefix("seg/").removeSuffix(".ts").toLongOrNull()
                    val data = seq?.let { hub.segmenter.segment(it) }
                    if (data == null) respond(out, 404, "text/plain", "not found".toByteArray(), method)
                    else respond(out, 200, "video/mp2t", data, method)
                }
                rest == "status" -> respond(out, 200, "application/json", hub.statusJson().toByteArray(), method)
                else -> respond(out, 404, "text/plain", "not found".toByteArray(), method)
            }
        }
    }

    private fun serveLive(s: Socket, out: OutputStream, h: StreamHub, contentType: String, init: ByteArray?) {
        if (h.subscriberCount >= maxClients) {
            return respondSimple(s, 503, "Too many viewers")
        }
        val sub = h.subscribe()
        log("espectador conectado ($contentType)")
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            if (init != null) out.write(init)
            out.flush()
            while (running.get() && !s.isClosed && !sub.closed) {
                val pkt: StreamHub.Packet? = try {
                    sub.poll(1000)
                } catch (_: InterruptedException) {
                    break // parada del servidor
                }
                if (pkt == null) continue
                val waitedMs = (System.nanoTime() - pkt.enqueuedNs) / 1_000_000.0
                h.recordQueueWait(waitedMs)
                val t0 = System.nanoTime()
                out.write(pkt.data)
                out.flush()
                h.recordWrite((System.nanoTime() - t0) / 1_000_000.0)
                h.touch()
            }
        } catch (_: IOException) {
            // Cliente desconectado: normal en streaming.
        } catch (_: RuntimeException) {
            // Error inesperado de un cliente: se cierra solo esa conexión.
        } finally {
            h.unsubscribe(sub)
            log("espectador desconectado ($contentType)")
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, method: String) {
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    private fun respondSimple(s: Socket, code: Int, msg: String) {
        try {
            val body = msg.toByteArray(Charsets.US_ASCII)
            val head = "HTTP/1.1 $code $msg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII) + body)
            s.getOutputStream().flush()
        } catch (_: IOException) {}
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 503 -> "Service Unavailable"; else -> "Error"
    }

    /** Lee una línea terminada en CRLF (o LF). Devuelve null al cerrarse la conexión. */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }
}
