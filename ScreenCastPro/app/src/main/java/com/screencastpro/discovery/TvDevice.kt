package com.screencastpro.discovery

enum class Protocol(val label: String) {
    /** Google Cast: Chromecast, Google TV y Android TV con Cast integrado. */
    CAST("Google Cast"),

    /** DLNA/UPnP MediaRenderer: televisores y reproductores de red compatibles. */
    DLNA("DLNA/UPnP"),
}

/**
 * Televisor o receptor encontrado en la red local. Se identifica por la IP, así un mismo equipo que
 * anuncie varios protocolos aparece una sola vez.
 */
data class TvDevice(
    val host: String,
    val name: String,
    val protocols: Set<Protocol>,
    val avTransportUrl: String? = null,
) {
    val id: String get() = host

    /** Cast tiene prioridad: es el camino con reproductor HLS oficial. */
    val preferredProtocol: Protocol
        get() = if (Protocol.CAST in protocols) Protocol.CAST else Protocol.DLNA

    val protocolsLabel: String
        get() = protocols.joinToString(" · ") { it.label } + " · $host"

    fun merged(other: TvDevice): TvDevice = TvDevice(
        host = host,
        name = if (Protocol.CAST in protocols) name else other.name.ifBlank { name },
        protocols = protocols + other.protocols,
        avTransportUrl = other.avTransportUrl ?: avTransportUrl,
    )
}
