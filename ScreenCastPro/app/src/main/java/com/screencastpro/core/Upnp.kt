package com.screencastpro.core

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Utilidades UPnP/DLNA: SSDP (descubrimiento), descripción del dispositivo y SOAP para AVTransport.
 * Solo usa APIs estándar de Java (disponibles también en Android): se prueban en JVM.
 */
object Upnp {

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Mensaje M-SEARCH para buscar renderizadores de medios (TV, reproductores DLNA). */
    fun msearchRequest(mxSeconds: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $RENDERER_ST\r\n\r\n"

    /** Parsea las cabeceras de una respuesta SSDP. Claves en mayúsculas. */
    fun parseSsdpHeaders(response: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        response.split("\r\n", "\n").drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) map[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
        }
        return map
    }

    /** Resuelve una URL relativa (controlURL) contra la LOCATION de la descripción. */
    fun resolve(base: String, relative: String): String = URI(base).resolve(relative).toString()

    class RendererInfo(
        val friendlyName: String,
        val manufacturer: String,
        val modelName: String,
        val avTransportControlUrl: String?,
    )

    /**
     * Tipos MIME/protocolInfo que los televisores DLNA aceptan para MPEG-TS en vivo.
     * Los renderizadores no son uniformes: si uno rechaza SetAVTransportURI, se prueba el siguiente.
     */
    /** Perfil MP4 fragmentado: el que anuncian los televisores (GetProtocolInfo). */
    val MP4_PROTOCOL_INFO: List<String> = listOf("http-get:*:video/mp4:*")

    val TS_PROTOCOL_INFO: List<String> = listOf(
        // Orden según GetProtocolInfo de televisores reales: primero los que anuncian.
        "http-get:*:video/mp2ts:*",
        "http-get:*:video/m2ts:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/mp2t:*",
    )

    /** Lee friendlyName y la URL de control de AVTransport desde el XML de descripción del dispositivo. */
    /**
     * Lee friendlyName, fabricante, modelo y la URL de control de AVTransport.
     * Muchos televisores publican XML mal formado (p. ej. `<controlURL>...<controlURL>`): si el parser
     * DOM falla, se usa extracción por texto, que sí tolera esos errores.
     */
    fun parseDeviceDescription(xml: String, location: String): RendererInfo {
        return try {
            parseWithDom(xml, location)
        } catch (_: Exception) {
            parseWithText(xml, location)
        }
    }

    private fun parseWithDom(xml: String, location: String): RendererInfo {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim().orEmpty()
        val manufacturer = doc.getElementsByTagName("manufacturer").item(0)?.textContent?.trim().orEmpty()
        val model = doc.getElementsByTagName("modelName").item(0)?.textContent?.trim().orEmpty()
        var control: String? = null
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val children = services.item(i).childNodes
            var type = ""
            var url = ""
            for (j in 0 until children.length) {
                val c = children.item(j)
                when (c.nodeName) {
                    "serviceType" -> type = c.textContent.trim()
                    "controlURL" -> url = c.textContent.trim()
                }
            }
            if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:") && url.isNotEmpty()) {
                control = resolve(location, url)
                break
            }
        }
        return RendererInfo(name, manufacturer, model, control)
    }

    private fun parseWithText(xml: String, location: String): RendererInfo {
        fun tag(t: String): String =
            Regex("<$t>([^<]*)</$t>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        // En el bloque de AVTransport, el primer controlURL que aparece después de su serviceType.
        var control: String? = null
        val marker = "urn:schemas-upnp-org:service:AVTransport:"
        val at = xml.indexOf(marker)
        if (at >= 0) {
            val m = Regex("<controlURL>\\s*([^<\\s]+)").find(xml, at)
            if (m != null) control = resolve(location, m.groupValues[1])
        }
        return RendererInfo(tag("friendlyName"), tag("manufacturer"), tag("modelName"), control)
    }

    /** Escapa texto para incluirlo en XML. */
    fun escapeXml(s: String): String = buildString(s.length + 16) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** Metadatos DIDL-Lite para un ítem de vídeo en vivo. */
    fun didlMetadata(title: String, streamUrl: String, protocolInfo: String = TS_PROTOCOL_INFO[0]): String {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"$protocolInfo\">${escapeXml(streamUrl)}</res>" +
            "</item></DIDL-Lite>"
        return didl
    }

    /** Envelope SOAP 1.1 para una acción de AVTransport. [args] debe contener XML ya escapado. */
    fun soapEnvelope(action: String, args: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AVTRANSPORT\">$args</u:$action></s:Body></s:Envelope>"

    fun setAvTransportUri(streamUrl: String, title: String, protocolInfo: String = TS_PROTOCOL_INFO[0]): Pair<String, String> {
        val args = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escapeXml(streamUrl)}</CurrentURI>" +
            "<CurrentURIMetaData>${escapeXml(didlMetadata(title, streamUrl, protocolInfo))}</CurrentURIMetaData>"
        return "SetAVTransportURI" to soapEnvelope("SetAVTransportURI", args)
    }

    fun play(): Pair<String, String> =
        "Play" to soapEnvelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stop(): Pair<String, String> =
        "Stop" to soapEnvelope("Stop", "<InstanceID>0</InstanceID>")

    fun getTransportInfo(): Pair<String, String> =
        "GetTransportInfo" to soapEnvelope("GetTransportInfo", "<InstanceID>0</InstanceID>")

    /** Cabecera SOAPACTION para la acción dada. */
    fun soapActionHeader(action: String): String = "\"$AVTRANSPORT#$action\""

    /** Extrae CurrentTransportState de una respuesta de GetTransportInfo. */
    fun parseTransportState(xml: String): String? {
        val m = Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>").find(xml)
        return m?.groupValues?.get(1)?.trim()
    }
}
