package com.screencastpro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.media.projection.MediaProjectionManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.screencastpro.capture.ScreenCaptureService
import com.screencastpro.connect.TvConnection
import com.screencastpro.core.BitrateChoice
import com.screencastpro.core.Resolution
import com.screencastpro.databinding.ActivityMainBinding
import com.screencastpro.discovery.DeviceDiscovery
import com.screencastpro.discovery.Protocol
import com.screencastpro.discovery.TvDevice
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var discovery: DeviceDiscovery
    private lateinit var adapter: DeviceAdapter
    private var lastDevices: List<TvDevice> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { requestProjection() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startStreamService(result.resultCode, data)
        } else {
            toast(getString(R.string.permission_denied_projection))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = AppSettings(this)

        setupSettingsControls()
        adapter = DeviceAdapter { device, protocol, connected ->
            if (connected) TvConnection.disconnect() else onConnectClicked(device, protocol)
        }
        b.rvDevices.layoutManager = LinearLayoutManager(this)
        b.rvDevices.adapter = adapter
        discovery = DeviceDiscovery(this) { list ->
            lastDevices = list
            adapter.submit(list)
            b.tvNoDevices.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnStop.setOnClickListener { stopStream() }
        b.btnScan.setOnClickListener {
            discovery.stop()
            discovery.start()
        }
        b.btnMiracast.setOnClickListener { openCastSettings() }
        b.btnCopyUrl.setOnClickListener { copyUrls() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionState.stats.collect { renderStats(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TvConnection.state.collect { renderConnection(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    // ------------------------------------------------------------ ajustes

    private fun setupSettingsControls() {
        val resolutions = Resolution.values()
        b.spResolution.adapter = spinnerAdapter(resolutions.map { it.label })
        b.spResolution.setSelection(resolutions.indexOf(settings.resolution).coerceAtLeast(0), false)
        b.spResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.resolution = resolutions[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val fpsOptions = AppSettings.FPS_OPTIONS
        b.spFps.adapter = spinnerAdapter(fpsOptions.map { "$it FPS" })
        b.spFps.setSelection(fpsOptions.indexOf(settings.fps).coerceAtLeast(0), false)
        b.spFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.fps = fpsOptions[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val bitrates = BitrateChoice.values()
        b.spBitrate.adapter = spinnerAdapter(bitrates.map { it.label })
        b.spBitrate.setSelection(bitrates.indexOf(settings.bitrate).coerceAtLeast(0), false)
        b.spBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.bitrate = bitrates[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.swLowLatency.isChecked = settings.lowLatency
        b.swLowLatency.setOnCheckedChangeListener { _, v -> settings.lowLatency = v }
        b.swBattery.isChecked = settings.batterySaver
        b.swBattery.setOnCheckedChangeListener { _, v -> settings.batterySaver = v }
        b.swReconnect.isChecked = settings.autoReconnect
        b.swReconnect.setOnCheckedChangeListener { _, v -> settings.autoReconnect = v }
        b.swAudio.isChecked = settings.internalAudio
        b.swAudio.setOnCheckedChangeListener { _, v -> settings.internalAudio = v }
        if (Build.VERSION.SDK_INT < 29) {
            b.swAudio.isEnabled = false
            b.swAudio.text = getString(R.string.audio_unsupported_os)
        }
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ------------------------------------------------------------ transmisión

    private fun onStartClicked() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (settings.internalAudio && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else requestProjection()
    }

    /** Consentimiento de captura: obligatorio en cada sesión, Android no lo recuerda. */
    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startStreamService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(ScreenCaptureService.EXTRA_SHORT_SIDE, settings.resolution.shortSide)
            .putExtra(ScreenCaptureService.EXTRA_FPS, settings.fps)
            .putExtra(ScreenCaptureService.EXTRA_AUDIO, settings.internalAudio)
            .putExtra(ScreenCaptureService.EXTRA_LOW_LATENCY, settings.lowLatency)
            .putExtra(ScreenCaptureService.EXTRA_BATTERY, settings.batterySaver)
            .putExtra(ScreenCaptureService.EXTRA_BITRATE, settings.bitrate.ordinal)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStream() {
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
    }

    private fun onConnectClicked(device: TvDevice, protocol: Protocol) {
        val s = SessionState.stats.value
        val ts = s.tsUrl
        val hls = s.hlsUrl
        if (!s.running || ts == null || hls == null) {
            toast(getString(R.string.need_stream_first))
            return
        }
        TvConnection.connect(this, device, protocol, ts, hls, settings.autoReconnect)
    }

    // ------------------------------------------------------------ render

    private fun renderStats(s: StreamStats) {
        b.btnStart.isEnabled = !s.running
        b.btnStop.isEnabled = s.running
        b.tvState.text = when {
            s.error != null -> s.error
            s.running -> s.status
            else -> getString(R.string.state_idle)
        }
        if (!s.running) {
            b.tvFps.text = "—"
            b.tvResolution.text = "—"
            b.tvBitrate.text = "—"
            b.tvLatency.text = "—"
            b.tvAudio.text = "—"
            b.tvClients.text = "0"
            b.tvUrls.visibility = View.GONE
            b.btnCopyUrl.visibility = View.GONE
            b.tvWarning.visibility = View.GONE
            return
        }
        b.tvFps.text = String.format(Locale.US, "%.0f", s.fps)
        b.tvResolution.text = "${s.width}×${s.height} · ${s.qualityLabel}"
        b.tvBitrate.text = String.format(
            Locale.US, "%d kbps medidos · objetivo %d kbps",
            s.measuredBitrateBps / 1000, s.targetBitrateBps / 1000,
        )
        b.tvLatency.text = "≈ ${s.latencyMs} ms"
        b.tvClients.text = s.clients.toString()
        b.tvAudio.text = if (s.audioActive) getString(R.string.label_audio) + " ✔" else "Solo vídeo"

        val warnings = mutableListOf<String>()
        if (!s.audioActive && settings.internalAudio) {
            warnings += if (Build.VERSION.SDK_INT < 29) getString(R.string.audio_unsupported_os)
                        else getString(R.string.audio_fallback)
        }
        if (s.thermal.isNotEmpty() && s.thermal != "Normal") {
            warnings += "Temperatura: ${s.thermal}. Se reduce la calidad para proteger el dispositivo."
        }
        b.tvWarning.text = warnings.joinToString("\n")
        b.tvWarning.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE

        if (s.tsUrl != null && s.hlsUrl != null) {
            b.tvUrls.text = "Reproductor HTTP (VLC, etc.):\n${s.tsUrl}\n\nChromecast (HLS):\n${s.hlsUrl}"
            b.tvUrls.visibility = View.VISIBLE
            b.btnCopyUrl.visibility = View.VISIBLE
        }
    }

    private fun renderConnection(state: TvConnection.State) {
        when (state) {
            is TvConnection.State.Idle -> {
                b.tvConnection.text = "Sin televisor conectado"
                b.tvDevice.text = "—"
                adapter.connectedHost = null
            }
            is TvConnection.State.Connecting -> {
                b.tvConnection.text = getString(R.string.state_connecting, state.device.name)
                b.tvDevice.text = state.device.protocolsLabel
                adapter.connectedHost = state.device.host
            }
            is TvConnection.State.Connected -> {
                b.tvConnection.text = getString(R.string.state_connected, state.device.name)
                b.tvDevice.text = state.device.protocolsLabel
                adapter.connectedHost = state.device.host
            }
            is TvConnection.State.Error -> {
                b.tvConnection.text = "Error: ${state.message}"
                b.tvDevice.text = state.device?.name ?: "—"
                adapter.connectedHost = null
            }
        }
    }

    // ------------------------------------------------------------ acciones auxiliares

    private fun openCastSettings() {
        try {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            toast("Este dispositivo no tiene ajustes de transmisión")
        }
    }

    private fun copyUrls() {
        val s = SessionState.stats.value
        val text = "TS: ${s.tsUrl}\nHLS: ${s.hlsUrl}"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ScreenCast Pro", text))
        toast("URL copiada")
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
