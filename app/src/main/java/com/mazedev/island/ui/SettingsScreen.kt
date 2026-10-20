package com.mazedev.island.ui

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mazedev.island.IslandPreview
import com.mazedev.island.IslandStyle
import com.mazedev.island.PreviewMode
import com.mazedev.island.Prefs
import com.mazedev.island.util.HeadsUp
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.util.AppEntry
import com.mazedev.island.util.Apps
import com.mazedev.island.util.Permissions
import com.mazedev.island.widget.PlaylistLoader
import com.mazedev.island.widget.PlaylistWidgetReceiver
import com.mazedev.island.widget.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Muted = Color(0xFF9A9AA6)

@Composable
fun SettingsScreen() {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    Text("Maze Island", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Dynamic Island + Spotify widget", color = Muted)
                }
            }
            item { SetupCard() }
            item { NowPlayingCard() }
            item { WidgetCard() }
            item { IslandCard() }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

// ------------------------------------------------------------------- setup

@Composable
private fun SetupCard() {
    val context = LocalContext.current
    var notifOk by remember { mutableStateOf(false) }
    var a11yOk by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notifOk = Permissions.notificationAccess(context)
        a11yOk = Permissions.accessibility(context)
        onPauseOrDispose { }
    }

    Section("Setup") {
        PermissionRow(
            title = "Notification access",
            subtitle = "Music info, controls and texts. Needed for both.",
            granted = notifOk,
        ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            title = "Accessibility service",
            subtitle = "Draws the island over the status bar.",
            granted = a11yOk,
        ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        if (!notifOk || !a11yOk) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Switch grayed out or says \"Restricted setting\"? Android blocks this for apps not from " +
                    "the Play Store. Tap below → ⋮ (top right) → Allow restricted settings, then try again.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openAppInfo(context) }) { Text("Open app info") }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: set Battery to \"Unrestricted\" in app info so the island isn't killed in the background.",
            color = Muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Text("✓ On", color = SpotifyGreen, fontWeight = FontWeight.SemiBold)
        } else {
            Button(onClick = onClick) { Text("Turn on") }
        }
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
}

// ------------------------------------------------------------ now playing

@Composable
private fun NowPlayingCard() {
    val media by MediaRepository.media.collectAsState()
    Section("Now playing") {
        val m = media
        if (m == null) {
            Text("Nothing detected. Play something in Spotify (needs notification access).", color = Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(m.art, 56)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (m.isPlaying) "Playing" else "Paused") + " · " + m.packageName,
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverImage(bitmap: Bitmap?, sizeDp: Int) {
    if (bitmap != null) {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A30)))
    }
}

// ------------------------------------------------------------------ widget

@Composable
private fun WidgetCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf(WidgetStore.playlistName(context)) }
    var savedCover by remember { mutableStateOf<Bitmap?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LifecycleResumeEffect(refresh) {
        scope.launch {
            savedName = WidgetStore.playlistName(context)
            savedCover = withContext(Dispatchers.IO) { WidgetStore.playlistCover(context) }
        }
        onPauseOrDispose { }
    }

    Section("Spotify playlist widget") {
        if (savedName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(savedCover, 48)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Current playlist", color = Muted, fontSize = 12.sp)
                    Text(savedName ?: "", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Playlist link") },
            placeholder = { Text("https://open.spotify.com/playlist/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "In Spotify: open a playlist → ⋯ → Share → Copy link, then paste it here.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !busy && link.isNotBlank(),
                onClick = {
                    busy = true
                    status = "Loading…"
                    scope.launch {
                        status = when (val r = PlaylistLoader.save(context, link)) {
                            is PlaylistLoader.SaveResult.Ok -> {
                                link = ""
                                refresh++
                                "Saved \"${r.name}\". The widget updated."
                            }
                            is PlaylistLoader.SaveResult.Error -> r.message
                        }
                        busy = false
                    }
                },
            ) { Text("Save playlist") }
            FilledTonalButton(onClick = { pinWidget(context) }) { Text("Add widget") }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Muted, fontSize = 13.sp)
        }
    }
}

private fun pinWidget(context: Context) {
    val awm = AppWidgetManager.getInstance(context)
    if (awm.isRequestPinAppWidgetSupported) {
        awm.requestPinAppWidget(ComponentName(context, PlaylistWidgetReceiver::class.java), null, null)
    } else {
        Toast.makeText(context, "Long-press your home screen → Widgets → Maze Island", Toast.LENGTH_LONG).show()
    }
}

// ------------------------------------------------------------------ island

@Composable
private fun IslandCard() {
    val cfg by Prefs.island.collectAsState()
    var picking by remember { mutableStateOf(false) }

    Section("Dynamic Island") {
        ToggleRow("Show island", cfg.enabled) { v -> Prefs.updateIsland { it.copy(enabled = v) } }

        Spacer(Modifier.height(6.dp))
        Text("Style", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StyleChip("Always-on bar", cfg.style == IslandStyle.BAR, Modifier.weight(1f)) {
                Prefs.updateIsland { it.copy(style = IslandStyle.BAR) }
            }
            StyleChip("Hidden until needed", cfg.style == IslandStyle.HIDDEN, Modifier.weight(1f)) {
                Prefs.updateIsland { it.copy(style = IslandStyle.HIDDEN) }
            }
        }
        Text(
            if (cfg.style == IslandStyle.BAR) "A black pill always sits around the camera."
            else "Only the camera shows; the island grows out to the sides when something happens.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        ToggleRow("Pop up all notifications (not just messages)", cfg.showAllNotifications) { v ->
            Prefs.updateIsland { it.copy(showAllNotifications = v) }
        }
        PopupReplacementRow(cfg.hideSystemPopups)

        Spacer(Modifier.height(8.dp))
        Text("Position & size", fontWeight = FontWeight.Medium)
        Text("Line it up with your camera hole. Changes apply live.", color = Muted, fontSize = 12.sp)
        LabeledSlider("Left / right", cfg.offsetX, -150f..150f) { v -> Prefs.updateIsland { it.copy(offsetX = v) } }
        LabeledSlider("Up / down", cfg.offsetY, -30f..80f) { v -> Prefs.updateIsland { it.copy(offsetY = v) } }
        LabeledSlider("Width", cfg.width, 60f..240f) { v -> Prefs.updateIsland { it.copy(width = v) } }
        LabeledSlider("Height", cfg.height, 20f..60f) { v -> Prefs.updateIsland { it.copy(height = v) } }

        Spacer(Modifier.height(10.dp))
        Text("Expanded island", fontWeight = FontWeight.Medium)
        Text("The island opens while you drag these.", color = Muted, fontSize = 12.sp)
        LabeledSlider("Side padding", cfg.expandedMargin, 0f..48f, PreviewMode.EXPANDED) { v ->
            Prefs.updateIsland { it.copy(expandedMargin = v) }
        }
        LabeledSlider("Corner bevel", cfg.expandedCorner, 8f..56f, PreviewMode.EXPANDED) { v ->
            Prefs.updateIsland { it.copy(expandedCorner = v) }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("App menu", fontWeight = FontWeight.Medium)
                Text(
                    "Swipe to it from the music tab. ${cfg.menuApps.size} chosen (first 9 shown).",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(onClick = { picking = true }) { Text("Choose") }
        }
        ToggleRow("Themed icons (match Pixel's themed icons)", cfg.themedIcons) { v ->
            Prefs.updateIsland { it.copy(themedIcons = v) }
        }
    }

    if (picking) {
        AppPickerDialog(
            selected = cfg.menuApps,
            themed = cfg.themedIcons,
            onDismiss = { picking = false },
            onSave = { chosen ->
                Prefs.updateIsland { it.copy(menuApps = chosen) }
                picking = false
            },
        )
    }
}

@Composable
private fun StyleChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp, maxLines = 1) }
    }
}

@Composable
private fun PopupReplacementRow(enabled: Boolean) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(HeadsUp.canControl(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = HeadsUp.canControl(context)
        onPauseOrDispose { }
    }
    ToggleRow("Hide the normal notification pop-ups", enabled && allowed) { v ->
        if (!allowed) return@ToggleRow
        Prefs.updateIsland { it.copy(hideSystemPopups = v) }
        if (!v) HeadsUp.setSystemPopups(context, enabled = true)
    }
    if (!allowed) {
        val cmd = HeadsUp.grantCommand(context)
        Text(
            "Android only lets a computer grant this. With your phone plugged in (USB debugging on), run once:",
            color = Muted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            cmd,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF22222A))
                .padding(10.dp),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("adb command", cmd))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy command") }
    } else {
        Text(
            "Notifications still go to the shade and lock screen; only the banner is replaced by the island.",
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    preview: PreviewMode = PreviewMode.GLOW,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${value.toInt()} dp", fontSize = 13.sp, color = Muted)
        }
        Slider(
            value = value,
            onValueChange = {
                IslandPreview.show(preview)
                onChange(it)
            },
            onValueChangeFinished = { IslandPreview.release() },
            valueRange = range,
        )
    }
}

@Composable
private fun AppPickerDialog(
    selected: List<String>,
    themed: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) { Apps.launchable(context, themed) }
    }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Island app menu") },
        text = {
            if (apps.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        val isOn = app.packageName in chosen
                        val toggle = {
                            if (isOn) chosen.remove(app.packageName) else chosen.add(app.packageName)
                            Unit
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(checked = isOn, onCheckedChange = { toggle() })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
