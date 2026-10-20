package com.mazedev.island.island

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazedev.island.IslandConfig
import com.mazedev.island.Prefs
import com.mazedev.island.R
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.media.MediaState
import com.mazedev.island.media.NotifEvent
import com.mazedev.island.ui.MainActivity
import com.mazedev.island.util.AppEntry
import com.mazedev.island.util.Apps
import com.mazedev.island.util.sendCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private enum class Face { IDLE, MINI, MEDIA, MENU, NOTIF }

private val Gray = Color(0xFFA0A0A8)

@Composable
fun IslandUi(controller: IslandController) {
    val cfg by Prefs.island.collectAsState()
    val media by MediaRepository.media.collectAsState()
    val mode by controller.mode.collectAsState()

    val face = when (mode) {
        is IslandMode.Notification -> Face.NOTIF
        IslandMode.Expanded -> if (media != null) Face.MEDIA else Face.MENU
        IslandMode.Collapsed -> if (media?.isPlaying == true) Face.MINI else Face.IDLE
    }
    val notif = (mode as? IslandMode.Notification)?.event

    val collapsed = face == Face.IDLE || face == Face.MINI
    val corner by animateDpAsState(
        targetValue = if (collapsed) (cfg.height / 2).dp else 32.dp,
        label = "corner",
    )
    val expandedWidth = min(LocalConfiguration.current.screenWidthDp - 16, 400).dp

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(corner))
            .background(Color.Black),
    ) {
        AnimatedContent(
            targetState = face,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f))
                    .togetherWith(fadeOut(tween(90)))
                    .using(
                        SizeTransform(clip = true) { _, _ ->
                            spring<IntSize>(dampingRatio = 0.72f, stiffness = 420f)
                        }
                    )
            },
            label = "island",
        ) { f ->
            when (f) {
                Face.IDLE -> IdlePill(cfg) { controller.expand() }
                Face.MINI -> MiniMedia(cfg, media) { controller.expand() }
                Face.MEDIA -> FullMedia(cfg, media, expandedWidth, controller)
                Face.MENU -> AppMenu(cfg, expandedWidth, controller)
                Face.NOTIF -> NotificationCard(cfg, notif, expandedWidth) {
                    notif?.contentIntent?.sendCompat()
                    controller.collapse()
                }
            }
        }
    }
}

// --------------------------------------------------------------- collapsed

@Composable
private fun IdlePill(cfg: IslandConfig, onTap: () -> Unit) {
    Box(
        Modifier
            .size(cfg.width.dp, cfg.height.dp)
            .noRippleClick(onTap)
    )
}

@Composable
private fun MiniMedia(cfg: IslandConfig, media: MediaState?, onTap: () -> Unit) {
    val h = cfg.height.dp
    Row(
        modifier = Modifier
            .size(cfg.width.dp + 96.dp, h)
            .noRippleClick(onTap)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Cover(media, size = h - 10.dp, corner = 6.dp)
        Bars(
            color = Color(media?.accent ?: 0xFF1DB954.toInt()),
            playing = media?.isPlaying == true,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

// ---------------------------------------------------------------- expanded

@Composable
private fun FullMedia(cfg: IslandConfig, media: MediaState?, width: Dp, controller: IslandController) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(width)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Spacer(Modifier.height(cfg.height.dp + 4.dp)) // keep content below the camera hole
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.noRippleClick {
                MediaRepository.openPlayer(context)
                controller.collapse()
            }) {
                Cover(media, size = 60.dp, corner = 14.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = media?.title ?: "",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = media?.artist ?: "",
                    color = Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Bars(Color(media?.accent ?: 0xFF1DB954.toInt()), media?.isPlaying == true)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(R.drawable.ic_prev, 44.dp) {
                MediaRepository.previous(context); controller.poke()
            }
            ControlButton(
                if (media?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play,
                56.dp,
                filled = true,
            ) {
                MediaRepository.playPause(context); controller.poke()
            }
            ControlButton(R.drawable.ic_next, 44.dp) {
                MediaRepository.next(context); controller.poke()
            }
        }
    }
}

@Composable
private fun AppMenu(cfg: IslandConfig, width: Dp, controller: IslandController) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<AppEntry>(), cfg.menuApps) {
        value = withContext(Dispatchers.IO) { Apps.load(context, cfg.menuApps) }
    }
    Column(
        modifier = Modifier
            .width(width)
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
    ) {
        Spacer(Modifier.height(cfg.height.dp + 4.dp))
        val tiles = apps.map { app ->
            MenuTile(app.label, app.icon) {
                Apps.launch(context, app.packageName)
                controller.collapse()
            }
        } + MenuTile("Settings", null) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            controller.collapse()
        }

        tiles.chunked(5).forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { AppTile(it) }
                repeat(5 - row.size) { Spacer(Modifier.width(60.dp)) }
            }
        }
    }
}

private class MenuTile(val label: String, val icon: ImageBitmap?, val onClick: () -> Unit)

@Composable
private fun AppTile(tile: MenuTile) {
    Column(
        modifier = Modifier.width(60.dp).noRippleClick(tile.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tile.icon != null) {
            Image(
                bitmap = tile.icon,
                contentDescription = tile.label,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A30)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tile.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NotificationCard(cfg: IslandConfig, event: NotifEvent?, width: Dp, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .width(width)
            .noRippleClick(onTap)
            .padding(start = 14.dp, end = 16.dp, bottom = 14.dp, top = cfg.height.dp + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = event?.icon
        if (icon != null) {
            Image(
                bitmap = remember(icon) { icon.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
        } else {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2A30)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event?.appName ?: "", color = Gray, fontSize = 11.sp, maxLines = 1)
            Text(
                event?.title ?: "",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                event?.text ?: "",
                color = Color(0xFFD8D8DE),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Cover(media: MediaState?, size: Dp, corner: Dp) {
    val art = media?.art
    if (art != null) {
        Image(
            bitmap = remember(art) { art.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
        )
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color(0xFF2A2A30)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_music),
                contentDescription = null,
                modifier = Modifier.size(size * 0.55f),
                colorFilter = ColorFilter.tint(Gray),
            )
        }
    }
}

/** Little animated equalizer, tinted with the cover's color. */
@Composable
private fun Bars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val durations = listOf(520, 380, 610, 450)
    val heights = durations.mapIndexed { i, d ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (playing) h.value else 0.25f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ControlButton(@DrawableRes res: Int, size: Dp, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) Color.White else Color(0x22FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(size * 0.5f),
            colorFilter = ColorFilter.tint(if (filled) Color.Black else Color.White),
        )
    }
}

@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
