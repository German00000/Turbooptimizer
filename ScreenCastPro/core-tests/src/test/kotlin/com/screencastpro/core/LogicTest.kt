package com.screencastpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMathTest {

    @Test
    fun fitKeepsAspectRatioLandscape() {
        assertEquals(1280 to 720, ProfileMath.fitSize(1920, 1080, 720))
        // 1920 * 480 / 1080 = 853,3 → par inferior 852
        assertEquals(852 to 480, ProfileMath.fitSize(1920, 1080, 480))
    }

    @Test
    fun fitKeepsAspectRatioPortraitPhone() {
        val (w, h) = ProfileMath.fitSize(1080, 2400, 480)
        assertEquals(480, w)
        // 2400 * 480 / 1080 = 1066,6 → par inferior 1066
        assertEquals(1066, h)
        // La relación de aspecto se mantiene dentro de un 0,5 %
        val original = 2400.0 / 1080.0
        assertTrue(Math.abs((h.toDouble() / w) - original) / original < 0.005)
    }

    @Test
    fun fitDoesNotUpscale() {
        assertEquals(640 to 360, ProfileMath.fitSize(640, 360, 1080))
    }

    @Test
    fun outputDimensionsAreEven() {
        for (short in listOf(480, 720, 1080)) {
            val (w, h) = ProfileMath.fitSize(1079, 2337, short)
            assertEquals(0, w % 2)
            assertEquals(0, h % 2)
        }
    }

    @Test
    fun ladderStartsAtRequestAndDescends() {
        val l = ProfileMath.ladder(1080, 60)
        assertEquals(Level(1080, 60), l.first())
        assertEquals(Level(480, 24), l.last())
        assertEquals(l.distinct(), l)
        assertTrue(l.none { it.shortSide > 1080 })
    }

    @Test
    fun ladderFor480IsShort() {
        val l = ProfileMath.ladder(480, 24)
        assertEquals(listOf(Level(480, 24)), l)
    }

    @Test
    fun batterySaverLowersBitrate() {
        val normal = ProfileMath.defaultBitrate(1920, 1080, 30, false)
        val saver = ProfileMath.defaultBitrate(1920, 1080, 30, true)
        assertTrue(saver < normal)
    }

    @Test
    fun bitrateIsClamped() {
        assertEquals(800_000, ProfileMath.defaultBitrate(160, 90, 24, false))
        assertEquals(10_000_000, ProfileMath.defaultBitrate(3840, 2160, 60, false))
    }
}

class AdaptiveControllerTest {

    private fun controller() = AdaptiveController(
        levelCount = 5,
        topLevel = 0,
        bitrateFor = { lvl -> listOf(6_000_000, 4_000_000, 3_000_000, 2_000_000, 1_000_000)[lvl] },
        minBps = 400_000,
    )

    private val calm = Sample(lagEvents = 0, avgWriteMs = 5.0, thermal = ThermalLevel.NORMAL)
    private val lag = Sample(lagEvents = 2, avgWriteMs = 5.0, thermal = ThermalLevel.NORMAL)

    @Test
    fun congestionLowersBitrateFirst() {
        val c = controller()
        val d = c.update(lag)
        assertTrue(d.bitrateChanged)
        assertEquals(0, d.level)
        assertTrue(d.bitrateBps < 6_000_000)
    }

    @Test
    fun persistentCongestionEventuallyLowersLevelAndStopsAtLowest() {
        val c = controller()
        repeat(60) { c.update(lag) }
        assertEquals(4, c.level)
        assertEquals(400_000, c.bitrate)
    }

    @Test
    fun criticalHeatGoesToLowestAndMinimumBitrate() {
        val c = controller()
        val d = c.update(Sample(0, 0.0, ThermalLevel.CRITICAL))
        assertEquals(4, d.level)
        assertEquals(400_000, d.bitrateBps)
    }

    @Test
    fun recoversTowardsRequestedTopLevelWhenCalm() {
        val c = controller()
        repeat(10) { c.update(Sample(0, 0.0, ThermalLevel.SEVERE)) }
        assertTrue(c.level > 0)
        repeat(200) { c.update(calm) }
        assertEquals(0, c.level)
        assertEquals(6_000_000, c.bitrate)
    }

    @Test
    fun neverExceedsTopLevelBitrate() {
        val c = controller()
        repeat(100) { c.update(calm) }
        assertEquals(6_000_000, c.bitrate)
        assertFalse(c.update(calm).bitrateChanged)
    }
}

class HlsSegmenterTest {

    @Test
    fun segmentsStartOnKeyframesAndRespectTarget() {
        // Keyframe cada 30 fotogramas (~1 s); objetivo 0,9 s → un segmento por keyframe
        val seg = HlsSegmenter(targetUs = 900_000L, keep = 3)
        // Frames cada 33 ms, keyframe cada 1 s
        var t = 0L
        var i = 0
        while (t <= 4_200_000L) {
            seg.append(byteArrayOf(i.toByte()), t, keyframe = i % 30 == 0)
            t += 33_333L
            i++
        }
        assertEquals(3, seg.segmentCount())
        val pl = seg.playlist()
        assertTrue(pl.startsWith("#EXTM3U"))
        assertTrue(pl.contains("#EXT-X-TARGETDURATION:"))
        assertTrue(pl.contains("seg/"))
        assertNotNull(seg.segment(2))
        assertNull(seg.segment(0)) // el más antiguo se descartó
    }

    @Test
    fun ignoresDataBeforeFirstKeyframe() {
        val seg = HlsSegmenter()
        seg.append(byteArrayOf(1), 0, keyframe = false)
        assertEquals(0, seg.segmentCount())
        assertTrue(seg.playlist().contains("#EXT-X-MEDIA-SEQUENCE:0"))
    }
}

class StreamHubTest {

    @Test
    fun slowClientIsResyncedOnKeyframe() {
        val hub = StreamHub(maxQueue = 2)
        val sub = hub.subscribe()
        hub.publish(byteArrayOf(1), 0, keyframe = true)
        hub.publish(byteArrayOf(2), 33_000, keyframe = false)
        hub.publish(byteArrayOf(3), 66_000, keyframe = false) // cola llena → lag
        assertEquals(1, hub.drainLagEvents())
        // El cliente ya no está sincronizado: los no-keyframe se descartan
        hub.publish(byteArrayOf(4), 99_000, keyframe = false)
        assertNull(sub.poll(10))
        // Un keyframe lo vuelve a sincronizar y se entrega
        hub.publish(byteArrayOf(5), 1_000_000, keyframe = true)
        assertEquals(5, sub.poll(100)!!.data[0].toInt())
        hub.unsubscribe(sub)
        assertEquals(0, hub.subscriberCount)
    }

    @Test
    fun newClientReceivesNothingUntilKeyframeAndRequestsOne() {
        val hub = StreamHub()
        var requested = 0
        hub.onKeyframeRequested = { requested++ }
        val sub = hub.subscribe()
        assertEquals(1, requested)
        hub.publish(byteArrayOf(9), 0, keyframe = false)
        assertNull(sub.poll(10))
        hub.publish(byteArrayOf(7), 0, keyframe = true)
        assertEquals(7, sub.poll(100)!!.data[0].toInt())
    }
}

class UpnpTest {

    @Test
    fun parsesSsdpHeaders() {
        val resp = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.50:49152/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val h = Upnp.parseSsdpHeaders(resp)
        assertEquals("http://192.168.1.50:49152/desc.xml", h["LOCATION"])
    }

    @Test
    fun parsesDeviceDescriptionAndResolvesControlUrl() {
        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <friendlyName>TV del living</friendlyName>
              <serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/avt</controlURL></service>
              </serviceList></device></root>"""
        val info = Upnp.parseDeviceDescription(xml, "http://192.168.1.50:49152/desc.xml")
        assertEquals("TV del living", info.friendlyName)
        assertEquals("http://192.168.1.50:49152/upnp/control/avt", info.avTransportControlUrl)
    }

    @Test
    fun soapEscapesUrlInsideMetadata() {
        val (action, body) = Upnp.setAvTransportUri("http://10.0.0.2:8765/t/live.ts?a=1&b=2", "ScreenCast & Pro")
        assertEquals("SetAVTransportURI", action)
        assertTrue(body.contains("<u:SetAVTransportURI"))
        // El DIDL-Lite es XML dentro de un valor XML: se escapa dos veces (& → &amp; → &amp;amp;).
        assertFalse(body.contains("a=1&b=2"))
        assertFalse(body.contains("ScreenCast & Pro"))
        assertTrue(body.contains("a=1&amp;amp;b=2"))
        assertTrue(body.contains("ScreenCast &amp;amp; Pro"))
    }

    @Test
    fun parsesManufacturerAndModel() {
        val xml = "<root><device><friendlyName></friendlyName><manufacturer>Hisense</manufacturer>" +
            "<modelName>VIDAA U</modelName><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1" +
            "</serviceType><controlURL>/avt</controlURL></service></serviceList></device></root>"
        val info = Upnp.parseDeviceDescription(xml, "http://10.0.0.5:8000/d.xml")
        assertEquals("Hisense", info.manufacturer)
        assertEquals("VIDAA U", info.modelName)
        assertEquals("http://10.0.0.5:8000/avt", info.avTransportControlUrl)
    }

    @Test
    fun setAvTransportUsesRequestedProtocolInfo() {
        val (_, body) = Upnp.setAvTransportUri("http://10.0.0.2:8765/t/live.ts", "x", "http-get:*:video/mpeg:*")
        assertTrue(body.contains("video/mpeg"))
        assertFalse(body.contains("video/mp2t"))
    }

    @Test
    fun toleratesMalformedXmlFromRealTv() {
        // XML real de un televisor DLNA: <controlURL> sin cerrar. El DOM falla; debe funcionar el respaldo por texto.
        val xml = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>TV de la sala</friendlyName><manufacturer>Hisense</manufacturer>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/upnp/control/minguscmr</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/mingusavtr<controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/mingusrcr</controlURL></service>
            </serviceList></device></root>"""
        val info = Upnp.parseDeviceDescription(xml, "http://10.0.20.121:18400/MediaServer/rendererdevicedesc.xml")
        assertEquals("TV de la sala", info.friendlyName)
        assertEquals("http://10.0.20.121:18400/upnp/control/mingusavtr", info.avTransportControlUrl)
    }

    @Test
    fun parsesTransportState() {
        val xml = "<CurrentTransportState>PLAYING</CurrentTransportState>"
        assertEquals("PLAYING", Upnp.parseTransportState(xml))
    }
}

class StreamServerTest {

    /** Lee cabeceras y, como mucho, [readBytes] bytes de cuerpo (o hasta cerrarse la conexión). */
    private fun get(port: Int, path: String, readBytes: Int = 4096, timeoutMs: Int = 3000): Pair<String, ByteArray> {
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = timeoutMs
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            try {
                while (true) {
                    val all0 = buf.toByteArray()
                    val he = String(all0, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                    if (he >= 0 && all0.size - (he + 4) >= readBytes) break
                    val n = s.getInputStream().read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
            } catch (_: java.net.SocketTimeoutException) {}
            val all = buf.toByteArray()
            val headEnd = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = if (headEnd >= 0) String(all, 0, headEnd, Charsets.ISO_8859_1) else String(all, Charsets.ISO_8859_1)
            val body = if (headEnd >= 0) all.copyOfRange(headEnd + 4, all.size) else ByteArray(0)
            return head to body
        }
    }

    @Test
    fun rejectsWrongTokenAndServesStatus() {
        val hub = StreamHub()
        val server = StreamServer(hub, port = 0, token = "tok123")
        val port = server.start()
        try {
            assertTrue(get(port, "/wrong/status").first.startsWith("HTTP/1.1 404"))
            val (head, body) = get(port, "/tok123/status")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(String(body).contains("\"clients\":0"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesPlaylistAndLiveTsFromKeyframe() {
        val hub = StreamHub()
        val server = StreamServer(hub, port = 0, token = "abc")
        val port = server.start()
        try {
            val payload = ByteArray(188) { 0x47.toByte() }
            // Publicar tras conectarse: la conexión live se suscribe y espera keyframe.
            val reader = Thread {
                Thread.sleep(200)
                hub.publish(payload, 0, keyframe = true)
                hub.publish(payload, 1_100_000, keyframe = true)
            }
            reader.start()
            val (head, body) = get(port, "/abc/live.ts", readBytes = 188, timeoutMs = 3000)
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.contains("video/mp2t"))
            assertTrue(body.size >= 188)
            assertEquals(0x47.toByte(), body[0])
            reader.join()

            val (plHead, pl) = get(port, "/abc/live.m3u8")
            assertTrue(plHead.startsWith("HTTP/1.1 200"))
            assertTrue(String(pl).startsWith("#EXTM3U"))
        } finally {
            server.stop()
        }
    }
}
