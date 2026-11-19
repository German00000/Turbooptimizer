package com.screencastpro.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * Descubrimiento combinado en la red local:
 *  - NsdManager con el tipo `_googlecast._tcp` (Chromecast, Google TV, Android TV con Cast).
 *  - SSDP/UPnP (`MediaRenderer`) para televisores DLNA.
 *
 * Compatible con API 21: no se usa Map.merge ni otras APIs de Java 8 que requieren API 24.
 * Los resultados se entregan en el hilo principal, ya fusionados por IP.
 */
class DeviceDiscovery(
    context: Context,
    private val onChanged: (List<TvDevice>) -> Unit,
) {
    companion object {
        private const val TAG = "DeviceDiscovery"
        private const val CAST_TYPE = "_googlecast._tcp"
        private const val SSDP_INTERVAL_MS = 15_000L
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val found = ConcurrentHashMap<String, TvDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    private var ssdpThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        found.clear()
        acquireMulticast()
        startNsd()
        ssdpThread = Thread({
            // Sin interrupt(): parar = cambiar running. Una InterruptedException no capturada
            // mataría el proceso y, con él, la transmisión.
            while (running) {
                try {
                    for (location in SsdpDiscovery.search()) {
                        if (!running) break
                        SsdpDiscovery.describe(location)?.let { addDevice(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSDP falló", e)
                }
                var waited = 0L
                while (running && waited < SSDP_INTERVAL_MS) {
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        running = false
                        break
                    }
                    waited += 500
                }
            }
        }, "ssdp-discovery").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        stopNsd()
        releaseMulticast()
        ssdpThread = null
    }

    private fun acquireMulticast() {
        try {
            multicastLock = wifi.createMulticastLock("screencast-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin MulticastLock: el descubrimiento puede tardar más", e)
        }
    }

    private fun releaseMulticast() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    // ------------------------------------------------------------ NSD

    private fun startNsd() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD no pudo iniciar: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains(CAST_TYPE)) enqueueResolve(serviceInfo)
            }
        }
        nsdListener = listener
        try {
            nsd.discoverServices(CAST_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar NSD", e)
        }
    }

    private fun stopNsd() {
        val l = nsdListener ?: return
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        nsdListener = null
        synchronized(resolveQueue) { resolveQueue.clear() }
        resolving = false
    }

    /** Android no admite resolver varios servicios a la vez: se encolan de uno en uno. */
    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        main.post { resolveNext() }
    }

    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) null
            else {
                resolving = true
                resolveQueue.removeFirst()
            }
        } ?: return
        try {
            @Suppress("DEPRECATION") // resolveService sigue disponible; registerServiceInfoCallback es API 34+.
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "No se resolvió ${serviceInfo.serviceName}: $errorCode")
                    resolveDone()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    if (host is Inet4Address) {
                        val friendly = serviceInfo.attributes["fn"]?.let { String(it, Charsets.UTF_8) }
                        val name = friendly?.takeIf { it.isNotBlank() } ?: serviceInfo.serviceName
                        addDevice(TvDevice(host.hostAddress ?: "", name, setOf(Protocol.CAST)))
                    }
                    resolveDone()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService falló", e)
            resolveDone()
        }
    }

    private fun resolveDone() {
        synchronized(resolveQueue) { resolving = false }
        main.post { resolveNext() }
    }

    // ------------------------------------------------------------ fusión

    private fun addDevice(device: TvDevice) {
        if (device.host.isEmpty()) return
        val previous = found[device.host]
        found[device.host] = previous?.merged(device) ?: device
        main.post { onChanged(found.values.sortedBy { it.name.lowercase() }) }
    }
}
