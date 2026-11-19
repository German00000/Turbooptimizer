package com.screencastpro.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP mínimo para hablar con televisores en la LAN (HttpURLConnection, sin dependencias). */
object Http {
    private const val TIMEOUT_MS = 5000

    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code al leer $url")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Acción SOAP (UPnP AVTransport). Devuelve el cuerpo de la respuesta o lanza IOException. */
    fun soap(url: String, soapAction: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", soapAction)
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("SOAP HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
