package com.screencastpro.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Distribuye los paquetes TS a los clientes HTTP y alimenta el segmentador HLS.
 *
 * Control de congestión: cada cliente tiene una cola acotada. Si se llena, se vacía y el cliente
 * espera al siguiente keyframe para volver a sincronizar. Así un cliente lento nunca retrasa al
 * codificador ni acumula memoria, y se cuenta como evento de congestión para el control adaptativo.
 */
class StreamHub(private val maxQueue: Int = 48) {

    class Packet(val data: ByteArray, val enqueuedNs: Long)

    inner class Subscriber internal constructor(val id: Long) {
        internal val queue = LinkedBlockingQueue<Packet>(maxQueue)
        @Volatile internal var synced = false
        /** Se marca al reiniciar el flujo (p. ej. cambio de calidad): el cliente debe reconectar. */
        @Volatile var closed = false
            internal set
        /** Hasta que llega un keyframe el cliente no recibe datos. */
        fun poll(timeoutMs: Long): Packet? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val nextId = AtomicLong(1)
    val segmenter = HlsSegmenter()

    private val lagEvents = AtomicInteger(0)
    @Volatile private var avgWriteMs = 0.0
    @Volatile private var avgQueueMs = 0.0
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set

    /** Segmento de inicio fMP4 (ftyp+moov). Lo primero que recibe cada cliente MP4. */
    @Volatile var initSegment: ByteArray? = null
        private set

    fun setInitSegment(init: ByteArray?) {
        initSegment = init
    }

    /** Reinicia el flujo: quita el segmento de inicio y cierra a los clientes para que reconecten. */
    fun resetStream() {
        initSegment = null
        for (sub in subscribers) sub.closed = true
        subscribers.clear()
    }

    /** Callback para pedir un keyframe al codificador cuando entra un cliente nuevo. */
    @Volatile var onKeyframeRequested: (() -> Unit)? = null

    val subscriberCount: Int get() = subscribers.size

    fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun subscribe(): Subscriber {
        val s = Subscriber(nextId.getAndIncrement())
        subscribers.add(s)
        onKeyframeRequested?.invoke()
        touch()
        return s
    }

    fun unsubscribe(s: Subscriber) {
        subscribers.remove(s)
    }

    /**
     * Entrega un bloque TS. [keyframe] debe ser true solo para bloques de vídeo que empiezan por keyframe
     * (con PAT/PMT delante). El audio se entrega con keyframe=false.
     */
    fun publish(chunk: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (chunk.isEmpty()) return
        segmenter.append(chunk, ptsUs, keyframe)
        val now = System.nanoTime()
        for (s in subscribers) {
            if (keyframe) s.synced = true
            if (!s.synced) continue
            val pkt = Packet(chunk, now)
            if (!s.queue.offer(pkt)) {
                lagEvents.incrementAndGet()
                s.queue.clear()
                if (keyframe) {
                    s.queue.offer(pkt)          // el keyframe reinicia la sincronización
                } else {
                    s.synced = false            // esperar al siguiente keyframe
                }
            }
        }
    }

    /** Registra un tiempo de escritura al socket (EWMA). */
    fun recordWrite(ms: Double) {
        avgWriteMs = avgWriteMs * 0.8 + ms * 0.2
    }

    /** Registra el tiempo que un paquete esperó en la cola antes de enviarse (EWMA). */
    fun recordQueueWait(ms: Double) {
        avgQueueMs = avgQueueMs * 0.8 + ms * 0.2
    }

    /** Devuelve los eventos de congestión desde la última llamada y los reinicia. */
    fun drainLagEvents(): Int = lagEvents.getAndSet(0)

    fun averageWriteMs(): Double = avgWriteMs
    fun averageQueueMs(): Double = avgQueueMs

    fun statusJson(): String = buildString {
        append("{\"clients\":").append(subscriberCount)
        append(",\"segments\":").append(segmenter.segmentCount())
        append(",\"writeMs\":").append(String.format(java.util.Locale.US, "%.1f", avgWriteMs))
        append(",\"queueMs\":").append(String.format(java.util.Locale.US, "%.1f", avgQueueMs))
        append("}")
    }
}
