package com.mazedev.island.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

@Composable
fun MazeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            secondary = SpotifyGreen,
            background = Color(0xFF0B0B0F),
            surface = Color(0xFF0B0B0F),
            surfaceContainer = Color(0xFF16161C),
            surfaceVariant = Color(0xFF1C1C24),
        ),
        content = content,
    )
}
