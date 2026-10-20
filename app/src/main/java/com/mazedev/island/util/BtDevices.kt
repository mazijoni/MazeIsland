package com.mazedev.island.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A connected Bluetooth audio device (headphones, earbuds, speaker, car). */
data class BtDevice(
    val address: String,
    val name: String,
    /** 0..100, or -1 if the device doesn't report it. */
    val battery: Int,
    val profiles: Set<String>,
)

/**
 * Keeps track of connected Bluetooth audio devices and their battery level.
 * Battery comes from the same source the Android status bar uses (the HFP/AVRCP battery report).
 */
@SuppressLint("MissingPermission")
object BtDevices {
    private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    /** Whether Bluetooth itself is on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var started = false
    private var appContext: Context? = null
    private val proxies = HashMap<Int, BluetoothProfile>()
    private val batteries = HashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    @Suppress("DEPRECATION")
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (d != null) batteries[d.address] = level
                    refresh()
                }
                // Profiles connect a moment after the link; check again shortly after.
                else -> {
                    refresh()
                    handler.postDelayed({ refresh() }, 1_500)
                }
            }
        }
    }

    /** Safe to call repeatedly; does nothing until the Bluetooth permission is granted. */
    fun start(context: Context) {
        if (started || !hasPermission(context)) return
        val app = context.applicationContext
        val bt = adapter(app) ?: return
        started = true
        appContext = app
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                refresh()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
                refresh()
            }
        }
        runCatching { bt.getProfileProxy(app, listener, BluetoothProfile.HEADSET) }
        runCatching { bt.getProfileProxy(app, listener, BluetoothProfile.A2DP) }
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        if (!hasPermission(ctx)) return
        _enabled.value = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)
        val byAddress = LinkedHashMap<String, BtDevice>()
        for ((profile, proxy) in proxies) {
            val label = if (profile == BluetoothProfile.A2DP) "Media audio" else "Calls"
            val connected = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
            for (d in connected) {
                val address = d.address
                val name = runCatching { if (Build.VERSION.SDK_INT >= 30) d.alias ?: d.name else d.name }.getOrNull()
                    ?: "Bluetooth device"
                val battery = readBattery(d).takeIf { it >= 0 } ?: batteries[address] ?: -1
                val existing = byAddress[address]
                byAddress[address] = BtDevice(
                    address = address,
                    name = name,
                    battery = maxOf(battery, existing?.battery ?: -1),
                    profiles = (existing?.profiles ?: emptySet()) + label,
                )
            }
        }
        _devices.value = byAddress.values.sortedByDescending { it.battery }
    }

    /** BluetoothDevice.getBatteryLevel() isn't public API, but it's what the system UI uses. */
    private fun readBattery(d: BluetoothDevice): Int = runCatching {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    }.getOrDefault(-1)
}
