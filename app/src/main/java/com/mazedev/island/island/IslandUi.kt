package com.mazedev.island.island

import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazedev.island.IslandConfig
import com.mazedev.island.IslandPreview
import com.mazedev.island.PreviewMode
import com.mazedev.island.IslandStyle
import com.mazedev.island.Prefs
import com.mazedev.island.R
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.media.MediaState
import com.mazedev.island.ui.MainActivity
import com.mazedev.island.util.AppEntry
import com.mazedev.island.util.Apps
import com.mazedev.island.util.sendCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Face { IDLE, MINI, EXPANDED, NOTIF }

private val Dim = Color(0xFFA6A6AE)
private val Soft = Color(0xE0FFFFFF)

/** Height of the swipeable media/app area: fits the media controls, or the app grid if taller. */
private fun pageHeight(cfg: IslandConfig): Dp {
    val cols = cfg.appColumns.coerceIn(3, 7)
    val tiles = minOf(cfg.menuApps.size, cols * 2 - 1) + 1 // + Settings tile
    val rows = (tiles + cols - 1) / cols
    val tileH = cfg.appIconSize + (if (cfg.appLabels) 19f else 0f)
    val grid = rows * tileH + (rows - 1) * 10f + 14f
    return maxOf(150f, grid).dp
}

@Composable
fun IslandUi(controller: IslandController) {
    val cfg by Prefs.island.collectAsState()
    val media by MediaRepository.media.collectAsState()
    val sessions by MediaRepository.sessions.collectAsState()
    val mode by controller.mode.collectAsState()
    val density = LocalDensity.current

    val face = when (mode) {
        is IslandMode.Notification -> Face.NOTIF
        is IslandMode.Expanded -> Face.EXPANDED
        IslandMode.Collapsed -> if (media?.isPlaying == true) Face.MINI else Face.IDLE
    }
    val collapsed = face == Face.IDLE || face == Face.MINI
    val cardWidth = IslandService.expandedWidthDp(LocalConfiguration.current.screenWidthDp, cfg.expandedMargin).dp
    val pageH = pageHeight(cfg)
    var notifHeightPx by remember { mutableStateOf(0) }

    // Every state has an exact size. The black shape animates between them, always centered on
    // the camera (top center); the content inside just crossfades. Nothing is ever positioned
    // from a corner, so nothing can slide in from the side.
    val pillH = cfg.height.dp
    val targetW = when (face) {
        Face.IDLE -> if (cfg.style == IslandStyle.HIDDEN) pillH else cfg.width.dp
        Face.MINI -> cfg.width.dp + IslandService.MINI_EXTRA_DP.dp
        Face.EXPANDED, Face.NOTIF -> cardWidth
    }
    val targetH = when (face) {
        Face.IDLE, Face.MINI -> pillH
        Face.EXPANDED -> pillH + 2.dp + pageH + (if (sessions.isNotEmpty()) 12.dp else 0.dp) + 10.dp
        Face.NOTIF -> if (notifHeightPx > 0) with(density) { notifHeightPx.toDp() } else pillH + 92.dp
    }
    // Opening gets a little bounce; closing settles without overshoot, so it never dips below the pill size.
    val sizeSpec = if (collapsed) spring<Dp>(dampingRatio = 1f, stiffness = 420f)
    else spring(dampingRatio = 0.8f, stiffness = 360f)
    val w by animateDpAsState(targetW, sizeSpec, label = "w")
    val h by animateDpAsState(targetH, sizeSpec, label = "h")
    val corner by animateDpAsState(
        targetValue = if (collapsed) pillH / 2 else cfg.expandedCorner.dp,
        animationSpec = sizeSpec,
        label = "corner",
    )

    val preview by IslandPreview.mode.collectAsState()
    val glow by animateFloatAsState(
        targetValue = if (preview != PreviewMode.NONE) 1f else 0f,
        animationSpec = tween(250),
        label = "glow",
    )

    // Window-sized layer. When expanded, tapping the empty area around the card closes it.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (collapsed) Modifier else Modifier.noRippleClick { controller.collapse() }),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(w, h)
                // White glow while adjusting the island in Settings, so you can see where it is.
                .drawBehind {
                    if (glow <= 0f) return@drawBehind
                    val r = corner.toPx()
                    for (i in 1..8) {
                        val grow = i * 2.5.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.10f * glow * (1f - i / 9f)),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + grow * 2, size.height + grow * 2),
                            cornerRadius = CornerRadius(r + grow, r + grow),
                        )
                    }
                }
                .clip(RoundedCornerShape(corner))
                .background(Color.Black)
                .then(if (collapsed) Modifier else Modifier.noRippleClick { controller.poke() }),
        ) {
            Crossfade(
                targetState = face,
                animationSpec = tween(180),
                label = "face",
                modifier = Modifier.fillMaxSize(),
            ) { f ->
                // Each face is laid out at its own natural size, centered at the top, and clipped by
                // the animated shape: it gets revealed from the camera outwards.
                Box(
                    Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.TopCenter, unbounded = true)
                ) {
                    when (f) {
                        Face.IDLE -> Box(Modifier.size(targetW.coerceAtLeast(pillH), pillH))
                        Face.MINI -> MiniMedia(cfg, media)
                        Face.EXPANDED -> {
                            val startOnApps = (mode as? IslandMode.Expanded)?.startOnApps ?: false
                            key(startOnApps) { ExpandedCard(cfg, cardWidth, pageH, startOnApps, controller) }
                        }
                        Face.NOTIF -> {
                            val n = mode as? IslandMode.Notification
                            if (n != null) {
                                NotificationCard(
                                    cfg, n, cardWidth, controller,
                                    Modifier.onSizeChanged { notifHeightPx = it.height },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- collapsed

@Composable
private fun MiniMedia(cfg: IslandConfig, media: MediaState?) {
    val h = cfg.height.dp
    Row(
        modifier = Modifier
            .size(cfg.width.dp + IslandService.MINI_EXTRA_DP.dp, h)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Cover(media?.art, size = h - 10.dp, corner = (h - 10.dp) / 2)
        Bars(
            color = Color(media?.accent ?: 0xFF1DB954.toInt()),
            playing = media?.isPlaying == true,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

// ---------------------------------------------------------------- expanded

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandedCard(cfg: IslandConfig, width: Dp, pageHeight: Dp, startOnApps: Boolean, controller: IslandController) {
    val sessions by MediaRepository.sessions.collectAsState()
    val appsPage = sessions.size
    val initialPage = remember {
        if (startOnApps) appsPage
        else sessions.indexOfFirst { it.isRecent() }.takeIf { it >= 0 } ?: 0
    }
    val pager = rememberPagerState(initialPage = initialPage) { sessions.size + 1 }
    LaunchedEffect(pager.currentPage) { controller.poke() }

    val bg: Bitmap? = sessions.getOrNull(pager.currentPage)?.blurred
    val camera = cfg.height.dp

    Box(modifier = Modifier.width(width)) {
        // Blurred cover behind the whole card…
        Crossfade(targetState = bg, animationSpec = tween(350), label = "bg", modifier = Modifier.matchParentSize()) { b ->
            if (b != null) {
                Image(
                    bitmap = remember(b) { b.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // …fading to black at the edges and around the camera, so it blends into the bezel.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    if (bg == null) return@drawBehind
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            0.28f to Color.Black.copy(alpha = 0.55f),
                            0.6f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.92f),
                        )
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.9f),
                            0.2f to Color.Transparent,
                            0.8f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.9f),
                        )
                    )
                    val center = Offset(size.width / 2f, camera.toPx() / 2f)
                    val r = camera.toPx() * 2.4f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to Color.Black,
                            0.45f to Color.Black.copy(alpha = 0.85f),
                            1f to Color.Transparent,
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                        center = center,
                    )
                }
        )

        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(camera + 2.dp)) // keep content below the camera hole
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().height(pageHeight),
            ) { page ->
                val s = sessions.getOrNull(page)
                if (s != null) MediaPage(s, controller) else AppsPage(cfg, controller)
            }
            if (sessions.isNotEmpty()) PageDots(count = sessions.size + 1, current = pager.currentPage)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MediaPage(s: MediaState, controller: IslandController) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.noRippleClick {
                MediaRepository.openPlayer(context, s.packageName)
                controller.collapse()
            }) {
                Cover(s.art, size = 58.dp, corner = 14.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.appName, color = Dim, fontSize = 11.sp, maxLines = 1)
                Text(
                    text = s.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = s.artist,
                    color = Dim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Bars(Color(s.accent), s.isPlaying)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(R.drawable.ic_prev, 42.dp) {
                MediaRepository.previous(context, s.packageName); controller.poke()
            }
            GlassButton(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, 52.dp, strong = true) {
                MediaRepository.playPause(context, s.packageName); controller.poke()
            }
            GlassButton(R.drawable.ic_next, 42.dp) {
                MediaRepository.next(context, s.packageName); controller.poke()
            }
        }
    }
}

private class MenuTile(val label: String, val icon: ImageBitmap?, val onClick: () -> Unit)

@Composable
private fun AppsPage(cfg: IslandConfig, controller: IslandController) {
    val context = LocalContext.current
    val cols = cfg.appColumns.coerceIn(3, 7)
    val maxApps = cols * 2 - 1
    val apps by produceState(initialValue = emptyList<AppEntry>(), cfg.menuApps, cfg.themedIcons, maxApps) {
        value = withContext(Dispatchers.IO) { Apps.load(context, cfg.menuApps.take(maxApps), cfg.themedIcons) }
    }
    val tiles = apps.map { app ->
        MenuTile(app.label, app.icon) {
            Apps.launch(context, app.packageName)
            controller.collapse()
        }
    } + MenuTile("Settings", null) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        controller.collapse()
    }

    // Brightness + optional black & white, applied to every icon.
    val filter = remember(cfg.appBrightness, cfg.appGrayscale) {
        val m = ColorMatrix()
        if (cfg.appGrayscale) m.setToSaturation(0f)
        val b = cfg.appBrightness
        m.timesAssign(ColorMatrix().apply { setToScale(b, b, b, 1f) })
        ColorFilter.colorMatrix(m)
    }
    val iconSize = cfg.appIconSize.dp
    val tileWidth = maxOf(iconSize + 12.dp, 52.dp)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        tiles.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { AppTile(it, iconSize, tileWidth, cfg.appLabels, filter) }
                repeat(cols - row.size) { Spacer(Modifier.width(tileWidth)) }
            }
        }
    }
}

@Composable
private fun AppTile(tile: MenuTile, iconSize: Dp, tileWidth: Dp, showLabel: Boolean, filter: ColorFilter) {
    Column(
        modifier = Modifier.width(tileWidth).noRippleClick(tile.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tile.icon != null) {
            Image(
                bitmap = tile.icon,
                contentDescription = tile.label,
                modifier = Modifier.size(iconSize),
                colorFilter = filter,
            )
        } else {
            Box(
                Modifier.size(iconSize).glass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize / 2),
                    colorFilter = ColorFilter.tint(Soft),
                )
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(tile.label, color = Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count <= 1) return
    Row(
        modifier = Modifier.fillMaxWidth().height(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (i == current) 6.dp else 5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.8f else 0.25f))
            )
        }
    }
}

// ------------------------------------------------------------ notification

@Composable
private fun NotificationCard(
    cfg: IslandConfig,
    n: IslandMode.Notification,
    width: Dp,
    controller: IslandController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val event = n.event
    val canReply = event.replyAction != null

    Column(
        modifier = modifier
            .width(width)
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = cfg.height.dp + 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().noRippleClick {
                if (canReply && !n.replying && !n.sent) {
                    controller.startReply()
                } else {
                    event.contentIntent?.sendCompat()
                    controller.collapse()
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = event.icon
            if (icon != null) {
                Image(
                    bitmap = remember(icon) { icon.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                )
            } else {
                Box(Modifier.size(42.dp).glass(CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.appName, color = Dim, fontSize = 11.sp, maxLines = 1)
                Text(
                    event.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    event.text,
                    color = Color(0xFFD8D8DE),
                    fontSize = 13.sp,
                    maxLines = if (n.replying) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                n.sent -> Text("Sent", color = Dim, fontSize = 12.sp)
                n.replying -> GlassButton(R.drawable.ic_open, 32.dp) {
                    event.contentIntent?.sendCompat()
                    controller.collapse()
                }
                canReply -> Box(
                    Modifier
                        .glass(RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Reply", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Camera snapshot (e.g. Google Home doorbell) or any picture notification.
        val picture = event.picture
        if (picture != null && !n.replying) {
            Spacer(Modifier.height(10.dp))
            Image(
                bitmap = remember(picture) { picture.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .noRippleClick {
                        event.contentIntent?.sendCompat()
                        controller.collapse()
                    },
            )
        }

        if (n.replying && event.replyAction != null) {
            Spacer(Modifier.height(12.dp))
            ReplyBox { text ->
                if (MediaRepository.sendReply(context, event.replyAction, text)) controller.replySent()
            }
        }
    }
}

@Composable
private fun ReplyBox(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(180) // wait for the window to become focusable
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            maxLines = 3,
            modifier = Modifier
                .weight(1f)
                .glass(RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("Reply…", color = Dim, fontSize = 15.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        GlassButton(R.drawable.ic_send, 40.dp, strong = text.isNotBlank()) {
            val t = text.trim()
            if (t.isNotEmpty()) onSend(t)
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Cover(art: Bitmap?, size: Dp, corner: Dp) {
    if (art != null) {
        Image(
            bitmap = remember(art) { art.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
        )
    } else {
        Box(
            Modifier.size(size).glass(RoundedCornerShape(corner)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_music),
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                colorFilter = ColorFilter.tint(Dim),
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
                    .background(color.copy(alpha = 0.9f))
            )
        }
    }
}

/** Subtle frosted-glass circle button: translucent fill + hairline edge, no solid colors. */
@Composable
private fun GlassButton(@DrawableRes res: Int, size: Dp, strong: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .glass(CircleShape, strong)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(size * 0.46f),
            colorFilter = ColorFilter.tint(Soft),
        )
    }
}

private fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier =
    this
        .clip(shape)
        .background(Color.White.copy(alpha = if (strong) 0.16f else 0.08f))
        .border(0.75.dp, Color.White.copy(alpha = if (strong) 0.22f else 0.12f), shape)

@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
