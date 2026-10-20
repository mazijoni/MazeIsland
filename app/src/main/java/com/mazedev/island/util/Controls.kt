package com.mazedev.island.util

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.DrawableRes
import com.mazedev.island.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow
import kotlin.math.roundToInt

/** Quick controls you can put in the island's app tab. */
enum class ControlKind(val label: String, @DrawableRes val icon: Int, val isSlider: Boolean = false) {
    BRIGHTNESS("Brightness", R.drawable.ic_brightness, isSlider = true),
    VOLUME("Media volume", R.drawable.ic_volume, isSlider = true),
    FLASHLIGHT("Flashlight", R.drawable.ic_flashlight),
    DND("Do Not Disturb", R.drawable.ic_dnd),
    RINGER("Sound / vibrate", R.drawable.ic_bell),
    AUTO_ROTATE("Auto-rotate", R.drawable.ic_rotate),
    BATTERY("Battery (headphones, or phone)", R.drawable.ic_battery, isSlider = true),
    INTERNET("Internet", R.drawable.ic_wifi),
    BLUETOOTH("Bluetooth device", R.drawable.ic_bluetooth, isSlider = true),
    ;

    /** Extra permission this control needs, if any. */
    val needsWriteSettings: Boolean get() = this == BRIGHTNESS || this == AUTO_ROTATE
    val needsPolicyAccess: Boolean get() = this == DND
    val needsBluetooth: Boolean get() = this == BATTERY || this == BLUETOOTH
}

object Controls {

    // ------------------------------------------------------------ permissions

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun writeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasPolicyAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun policyAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------- brightness

    // The slider uses a perceptual curve (like the system one), so the low end isn't all crammed together.
    private const val GAMMA = 2.2

    /** 0..1 slider position. */
    fun brightness(context: Context): Float {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (raw / 255.0).pow(1 / GAMMA).toFloat().coerceIn(0f, 1f)
    }

    /** Returns false if the "Modify system settings" permission is missing. */
    fun setBrightness(context: Context, position: Float): Boolean {
        if (!canWriteSettings(context)) return false
        val raw = (position.coerceIn(0f, 1f).toDouble().pow(GAMMA) * 255).roundToInt().coerceIn(1, 255)
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        }.getOrDefault(false)
    }

    // ----------------------------------------------------------------- volume

    fun volume(context: Context): Float {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(context: Context, position: Float) {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (position.coerceIn(0f, 1f) * max).roundToInt(), 0) }
    }

    // ------------------------------------------------------------- flashlight

    private val _torch = MutableStateFlow(false)
    /** Whether the flashlight is on (kept in sync with the system, also when toggled elsewhere). */
    val torch: StateFlow<Boolean> = _torch.asStateFlow()
    val torchOn: Boolean get() = _torch.value

    private val _torchLevel = MutableStateFlow(1)
    val torchLevel: StateFlow<Int> = _torchLevel.asStateFlow()

    private var torchCameraId: String? = null
    private var torchRegistered = false
    private var torchMax = 1

    private fun torchId(context: Context): String? {
        torchCameraId?.let { return it }
        val cm = context.getSystemService(CameraManager::class.java)
        torchCameraId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        torchCameraId?.let { id ->
            if (Build.VERSION.SDK_INT >= 33) {
                torchMax = runCatching {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                }.getOrNull() ?: 1
                _torchLevel.value = torchMax
            }
        }
        if (!torchRegistered) {
            torchRegistered = true
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId) _torch.value = enabled
                }

                override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                    if (cameraId == torchCameraId) _torchLevel.value = newStrengthLevel
                }
            }, Handler(Looper.getMainLooper()))
        }
        return torchCameraId
    }

    fun prepare(context: Context) {
        torchId(context)
    }

    /** How many brightness steps the flashlight has (1 = on/off only). */
    fun torchMaxLevel(context: Context): Int {
        torchId(context)
        return torchMax
    }

    fun setTorch(context: Context, on: Boolean) {
        val id = torchId(context) ?: return
        val cm = context.getSystemService(CameraManager::class.java)
        runCatching {
            if (on && Build.VERSION.SDK_INT >= 33 && torchMax > 1) {
                cm.turnOnTorchWithStrengthLevel(id, _torchLevel.value.coerceIn(1, torchMax))
            } else {
                cm.setTorchMode(id, on)
            }
        }.onSuccess { _torch.value = on }
    }

    fun toggleTorch(context: Context) = setTorch(context, !torchOn)

    /** [fraction] 0..1. 0 turns it off; otherwise picks the nearest strength step. */
    fun setTorchFraction(context: Context, fraction: Float) {
        val id = torchId(context) ?: return
        if (fraction <= 0.02f) {
            setTorch(context, false)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && torchMax > 1) {
            val level = (fraction * torchMax).roundToInt().coerceIn(1, torchMax)
            runCatching {
                context.getSystemService(CameraManager::class.java).turnOnTorchWithStrengthLevel(id, level)
            }.onSuccess {
                _torchLevel.value = level
                _torch.value = true
            }
        } else if (!torchOn) {
            setTorch(context, true)
        }
    }

    fun torchFraction(): Float = if (!torchOn) 0f else _torchLevel.value.toFloat() / torchMax.coerceAtLeast(1)

    // ---------------------------------------------------------- Do Not Disturb

    fun dndOn(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
            NotificationManager.INTERRUPTION_FILTER_ALL

    /** Returns false if access is missing. */
    fun toggleDnd(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false
        nm.setInterruptionFilter(
            if (dndOn(context)) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        return true
    }

    // ----------------------------------------------------------------- ringer

    fun ringerMode(context: Context): Int = context.getSystemService(AudioManager::class.java).ringerMode

    /** Sound → vibrate → silent (silent only with DND access) → sound. */
    fun cycleRinger(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (hasPolicyAccess(context)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        runCatching { am.ringerMode = next }
    }

    // ------------------------------------------------------------ auto-rotate

    fun autoRotate(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
    }.getOrDefault(false)

    fun toggleAutoRotate(context: Context): Boolean {
        if (!canWriteSettings(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (autoRotate(context)) 0 else 1,
            )
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- battery

    data class Battery(val percent: Int, val charging: Boolean)

    fun battery(context: Context): Battery {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(pct, charging)
    }

    // ------------------------------------------------------------- shortcuts

    fun internetPanel(): Intent =
        (if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        else Intent(Settings.ACTION_WIFI_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun bluetoothSettings(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batterySettings(): Intent =
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
