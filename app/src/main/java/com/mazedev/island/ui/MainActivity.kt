package com.mazedev.island.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mazedev.island.Prefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge()
        setContent {
            MazeTheme { SettingsScreen() }
        }
    }
}

val Accent = Color(0xFF8B7CFF)
val SpotifyGreen = Color(0xFF1DB954)

/**
 * Follows the phone: Material You colors from your wallpaper (Android 12+) and the system's
 * light / dark mode, so the settings look like the rest of your Pixel.
 */
@Composable
fun MazeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Accent, secondary = SpotifyGreen)
        else -> lightColorScheme(primary = Color(0xFF5B4BD6), secondary = SpotifyGreen)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
