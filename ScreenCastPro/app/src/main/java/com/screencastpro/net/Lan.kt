package com.screencastpro.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Dirección IPv4 de la red local (Wi-Fi/hotspot). Prefiere direcciones privadas RFC 1918. */
object Lan {
    /**
     * IPv4 de la interfaz Wi-Fi (wlan*). Con datos móviles activos, la IP privada que devuelve localIpv4()
     * puede ser la de la red móvil, y la TV no podría llegar al teléfono.
     */
    fun wifiIpv4(): String? {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return null
        }
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback || !ni.name.contains("wlan")) continue
            for (addr in Collections.list(ni.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
            }
        }
        return null
    }

    fun localIpv4(): String? {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return null
        }
        var fallback: String? = null
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in Collections.list(ni.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    if (addr.isSiteLocalAddress) return addr.hostAddress
                    if (fallback == null) fallback = addr.hostAddress
                }
            }
        }
        return fallback
    }
}
