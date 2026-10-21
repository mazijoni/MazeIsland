package com.mazedev.island.island

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mazedev.island.Prefs
import com.mazedev.island.R
import com.mazedev.island.util.Controls

/** A short iOS-style system alert: the island widens for a moment (charging, silent mode, …). */
data class SystemAlert(
    @DrawableRes val icon: Int,
    val title: String,
    val trailing: String = "",
    val color: Int,
    /** 0..1 shows a little ring (e.g. battery level), null for none. */
    val level: Float? = null,
    val id: Long = System.nanoTime(),
)

/** Listens for system events and turns them into [SystemAlert]s. */
class SystemAlerts(private val context: Context, private val onAlert: (SystemAlert) -> Unit) {
    private var lastBatteryPct = -1
    private var lastRinger = -1
    private var lastDnd: Boolean? = null
    private val lastBtAlert = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val cfg = Prefs.island.value
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> if (cfg.alertCharging) {
                    val b = Controls.battery(context)
                    onAlert(SystemAlert(R.drawable.ic_charging, "Charging", "${b.percent}%", GREEN, b.percent / 100f))
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val pct = level * 100 / scale
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (cfg.alertLowBattery && !plugged && lastBatteryPct > 0 &&
                        pct < lastBatteryPct && (pct == 20 || pct == 10 || pct == 5)
                    ) {
                        onAlert(SystemAlert(R.drawable.ic_battery, "Low Battery", "$pct%", RED, pct / 100f))
                    }
                    lastBatteryPct = pct
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                    if (cfg.alertRinger && lastRinger != -1 && mode != lastRinger) {
                        onAlert(
                            when (mode) {
                                AudioManager.RINGER_MODE_SILENT -> SystemAlert(R.drawable.ic_bell_off, "Silent", "", RED)
                                AudioManager.RINGER_MODE_VIBRATE -> SystemAlert(R.drawable.ic_vibrate, "Vibrate", "", ORANGE)
                                else -> SystemAlert(R.drawable.ic_bell, "Ring", "", WHITE)
                            }
                        )
                    }
                    lastRinger = mode
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val on = Controls.dndOn(context)
                    if (cfg.alertDnd && lastDnd != null && on != lastDnd) {
                        onAlert(SystemAlert(R.drawable.ic_moon, "Do Not Disturb", if (on) "On" else "Off", PURPLE))
                    }
                    lastDnd = on
                }
                Intent.ACTION_USER_PRESENT -> if (cfg.alertUnlock) {
                    onAlert(SystemAlert(R.drawable.ic_unlock, "Unlocked", "", WHITE))
                }
                // Only when headphones/speakers actually connect for audio (calls or media), not when
                // a device just shows up nearby or makes a brief background link.
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> if (cfg.alertBluetooth && canReadBluetooth()) {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val address = device?.address
                    if (state == BluetoothProfile.STATE_CONNECTED && address != null) {
                        // Both profiles report the same connection; show it once.
                        val now = System.currentTimeMillis()
                        if (now - (lastBtAlert[address] ?: 0L) > 15_000L) {
                            lastBtAlert[address] = now
                            val name = runCatching { device.name }.getOrNull() ?: "Bluetooth device"
                            onAlert(SystemAlert(R.drawable.ic_headphones, name, "Connected", BLUE))
                        }
                    }
                }
            }
        }
    }

    private fun canReadBluetooth(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        lastRinger = context.getSystemService(AudioManager::class.java).ringerMode
        lastDnd = Controls.dndOn(context)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            if (canReadBluetooth()) {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object {
        const val GREEN = 0xFF34C759.toInt()
        const val RED = 0xFFFF453A.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val PURPLE = 0xFF8E8CF8.toInt()
        const val BLUE = 0xFF64A8FF.toInt()
        const val WHITE = 0xFFE8E8EE.toInt()
    }
}
