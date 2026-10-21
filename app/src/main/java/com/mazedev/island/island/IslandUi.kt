package com.mazedev.island.island

import android.content.Intent
import android.graphics.Bitmap
import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import android.media.AudioManager
import android.os.SystemClock
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
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
import com.mazedev.island.underStatusBar
import com.mazedev.island.R
import com.mazedev.island.media.MediaListenerService
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.media.LiveActivity
import com.mazedev.island.media.MediaState
import com.mazedev.island.media.formatDuration
import com.mazedev.island.ui.MainActivity
import com.mazedev.island.util.AppEntry
import com.mazedev.island.util.Apps
import com.mazedev.island.util.BtDevices
import com.mazedev.island.util.ControlKind
import com.mazedev.island.util.Controls
import com.mazedev.island.util.sendCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.withContext

private enum class Face { COLLAPSED, EXPANDED, NOTIF, FLASHLIGHT, ALERT, ACTIVITY, VIDEO }

internal val Dim = Color(0xFFA6A6AE)
internal val Soft = Color(0xE0FFFFFF)

/** What the app tab contains, derived from settings. */
private class AppTab(cfg: IslandConfig) {
    val kinds = cfg.controls.mapNotNull { runCatching { ControlKind.valueOf(it) }.getOrNull() }
    val sliders = kinds.filter { it.isSlider }
    val toggles = kinds.filterNot { it.isSlider }
    val cols = cfg.appColumns.coerceIn(3, 7)
    private val fixedTiles = toggles.size + (if (cfg.showSettingsTile) 1 else 0)
    val maxApps = (cols * 3 - fixedTiles).coerceAtLeast(0)
    private val items = fixedTiles + minOf(cfg.menuApps.size, maxApps)
    val rows = (items + cols - 1) / cols
    private val tileH = cfg.appIconSize + (if (cfg.appLabels) 19f else 0f)
    val height: Dp = run {
        // Battery + Bluetooth share one row.
        val sliderRows = sliders.count { it == ControlKind.BRIGHTNESS || it == ControlKind.VOLUME } +
            (if (sliders.any { it == ControlKind.BATTERY || it == ControlKind.BLUETOOTH }) 1 else 0)
        val sliderH = sliderRows * SLIDER_H + (if (sliderRows > 0 && rows > 0) 6f else 0f)
        val grid = if (rows > 0) rows * tileH + (rows - 1) * 10f else 0f
        maxOf(MEDIA_PAGE_H, sliderH + grid + 14f).dp
    }

    companion object {
        const val SLIDER_H = 48f
        const val MEDIA_PAGE_H = 186f
    }
}

/** Height of the swipeable media/app area: fits the media controls, or the app tab if taller. */
private fun pageHeight(cfg: IslandConfig): Dp = AppTab(cfg).height

@Composable
fun IslandUi(controller: IslandController) {
    val cfg by Prefs.island.collectAsState()
    val media by MediaRepository.media.collectAsState()
    val sessions by MediaRepository.sessions.collectAsState()
    val activities by MediaRepository.activities.collectAsState()
    val torch by Controls.torch.collectAsState()
    val mode by controller.mode.collectAsState()
    val nudge by controller.nudge.collectAsState()
    val density = LocalDensity.current

    val layout = CollapsedLayout.of(media?.isPlaying == true, activities, torch)
    val face = when (mode) {
        is IslandMode.Notification -> Face.NOTIF
        is IslandMode.Expanded -> Face.EXPANDED
        IslandMode.Flashlight -> Face.FLASHLIGHT
        is IslandMode.Alert -> Face.ALERT
        is IslandMode.Activity -> Face.ACTIVITY
        is IslandMode.Video -> Face.VIDEO
        IslandMode.Collapsed -> Face.COLLAPSED
    }
    val pinned by controller.pinned.collectAsState()
    val collapsed = face == Face.COLLAPSED
    val cardWidth = IslandService.expandedWidthDp(LocalConfiguration.current.screenWidthDp, cfg.expandedMargin).dp
    val pageH = pageHeight(cfg)
    var measuredPx by remember { mutableStateOf(0) }

    // Every state has an exact size. The black shape animates between them, always centered on
    // the camera (top center); the content inside just crossfades. Nothing is ever positioned
    // from a corner, so nothing can slide in from the side.
    val pillH = cfg.height.dp
    // Corner camera: the pill grows out of the camera towards the middle, wide cards sit at the margin.
    val camSide = cfg.cameraPosition
    val cornerCam = camSide == "LEFT" || camSide == "RIGHT"
    val cameraXRaw by IslandGeometry.cameraXDp.collectAsState()
    val screenWdp = LocalConfiguration.current.screenWidthDp.toFloat()
    val camX = (if (cameraXRaw > 0f) cameraXRaw else if (camSide == "LEFT") 36f else screenWdp - 36f) + cfg.offsetX
    val measured = if (measuredPx > 0) with(density) { measuredPx.toDp() } else pillH + 92.dp
    val targetW = when (face) {
        Face.COLLAPSED -> collapsedWidthDp(cfg, layout.main).dp
        Face.ALERT -> alertWidth(cfg, (mode as? IslandMode.Alert)?.alert, cardWidth)
        Face.FLASHLIGHT -> FLASH_W
        Face.EXPANDED, Face.NOTIF, Face.ACTIVITY, Face.VIDEO -> cardWidth
    }
    val targetH = when (face) {
        Face.COLLAPSED -> pillH
        Face.ALERT -> pillH + 8.dp
        Face.FLASHLIGHT -> pillH + FLASH_H
        Face.EXPANDED -> pillH + 2.dp + pageH +
            (if (sessions.size + 1 + listOf(cfg.tabWeather, cfg.tabContacts, cfg.tabCamera).count { it } > 1) 12.dp else 0.dp) + 10.dp
        Face.NOTIF, Face.ACTIVITY -> measured
        Face.VIDEO -> pillH + 6.dp + (cardWidth - 24.dp) * 9f / 16f + VIDEO_BAR_H
    }
    // Pinned: tell the service the card's height so it can cut the window to it.
    LaunchedEffect(pinned, face, targetH) {
        if (pinned && (face == Face.EXPANDED || face == Face.VIDEO)) {
            controller.reportPinnedHeight(with(density) { targetH.roundToPx() })
        }
    }
    // Opening gets a little bounce; closing settles without overshoot, so it never dips below the pill size.
    val sizeSpec = if (collapsed) spring<Dp>(dampingRatio = 1f, stiffness = 420f)
    else spring(dampingRatio = 0.8f, stiffness = 360f)
    val w by animateDpAsState(targetW, sizeSpec, label = "w")
    val wideCard = targetW >= cardWidth - 1.dp
    val sideMargin = (screenWdp.dp - cardWidth) / 2
    val edgeTarget = when (camSide) {
        "LEFT" -> if (wideCard) sideMargin else (camX.dp - pillH / 2).coerceAtLeast(0.dp)
        "RIGHT" -> if (wideCard) screenWdp.dp - sideMargin else (camX.dp + pillH / 2).coerceAtMost(screenWdp.dp)
        else -> 0.dp
    }
    val edge by animateDpAsState(edgeTarget, sizeSpec, label = "edge")
    // Left edge of the island (corner camera only; centered layouts use 0 and TopCenter).
    val shapeX = when (camSide) {
        "LEFT" -> edge
        "RIGHT" -> edge - w
        else -> 0.dp
    }
    val h by animateDpAsState(targetH, sizeSpec, label = "h")
    val corner by animateDpAsState(
        targetValue = when {
            collapsed || face == Face.ALERT -> targetH / 2
            face == Face.FLASHLIGHT -> (cfg.expandedCorner + 18f).dp
            else -> cfg.expandedCorner.dp
        },
        animationSpec = sizeSpec,
        label = "corner",
    )
    val nudgeX by animateDpAsState(
        targetValue = (nudge * 10).dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "nudge",
    )
    // Detached bubble (second live activity), like iOS.
    val bubbleShown = collapsed && layout.bubble != Compact.NONE
    val bubbleScale by animateFloatAsState(
        targetValue = if (bubbleShown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "bubble",
    )
    var lastBubble by remember { mutableStateOf(Compact.NONE) }
    if (layout.bubble != Compact.NONE) lastBubble = layout.bubble

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
            .then(if (collapsed) Modifier else Modifier.noRippleClick { controller.dismiss() }),
        contentAlignment = if (cornerCam) Alignment.TopStart else Alignment.TopCenter,
    ) {
        if (bubbleScale > 0.01f) {
            Box(
                Modifier
                    .offset(
                        x = when (camSide) {
                            "LEFT" -> shapeX + w + BUBBLE_GAP_DP.dp
                            "RIGHT" -> shapeX - BUBBLE_GAP_DP.dp - pillH
                            else -> w / 2 + BUBBLE_GAP_DP.dp + pillH / 2
                        }
                    )
                    .graphicsLayer {
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                        alpha = bubbleScale.coerceIn(0f, 1f)
                    }
                    .size(pillH)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when (lastBubble) {
                    Compact.MEDIA -> Cover(media?.art, size = pillH - 8.dp, corner = pillH)
                    Compact.TORCH -> Image(
                        painter = painterResource(R.drawable.ic_flashlight),
                        contentDescription = null,
                        modifier = Modifier.size(pillH * 0.5f),
                        colorFilter = ColorFilter.tint(mono(TorchYellow)),
                    )
                    else -> Unit
                }
            }
        }
        // Swipe a notification away: sideways dismisses it, up just tucks it back into the island.
        val swipeScope = rememberCoroutineScope()
        val dragX = remember { Animatable(0f) }
        val dragY = remember { Animatable(0f) }
        val swipeAlpha = remember { Animatable(1f) }
        val notifMode = mode as? IslandMode.Notification
        LaunchedEffect(notifMode?.event?.key) {
            dragX.snapTo(0f)
            dragY.snapTo(0f)
        }
        val widthPx = with(density) { cardWidth.toPx() }
        val swipeModifier = if (notifMode != null && !notifMode.replying) {
            Modifier.pointerInput(notifMode.event.key) {
                detectDragGestures(
                    onDragStart = { controller.keepOpen(10_000) },
                    onDragCancel = {
                        swipeScope.launch { dragX.animateTo(0f, spring()) }
                        swipeScope.launch { dragY.animateTo(0f, spring()) }
                    },
                    onDragEnd = {
                        val key = notifMode.event.key
                        when {
                            abs(dragX.value) > widthPx * 0.28f -> swipeScope.launch {
                                // Fly out to the side, remove it, then let the pill fade back in.
                                val target = if (dragX.value > 0) widthPx * 1.1f else -widthPx * 1.1f
                                launch { swipeAlpha.animateTo(0f, tween(160)) }
                                dragX.animateTo(target, tween(180))
                                MediaListenerService.dismissNotification(key)
                                controller.dismiss()
                                dragX.snapTo(0f)
                                dragY.snapTo(0f)
                                swipeAlpha.animateTo(1f, tween(260))
                            }
                            dragY.value < -with(density) { 36.dp.toPx() } -> swipeScope.launch {
                                controller.dismiss()
                                dragY.animateTo(0f, tween(220))
                                dragX.snapTo(0f)
                            }
                            else -> {
                                swipeScope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.7f)) }
                                swipeScope.launch { dragY.animateTo(0f, spring(dampingRatio = 0.7f)) }
                                controller.keepOpen(4_000)
                            }
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    swipeScope.launch {
                        dragX.snapTo(dragX.value + amount.x)
                        // Up moves freely; down only stretches a little.
                        val y = dragY.value + amount.y
                        dragY.snapTo(if (y > 0) y * 0.3f else y)
                    }
                }
            }
        } else Modifier
        Box(
            modifier = Modifier
                .offset(x = nudgeX + shapeX)
                .graphicsLayer {
                    translationX = dragX.value
                    translationY = dragY.value
                    val fade = (abs(dragX.value) / widthPx).coerceIn(0f, 1f)
                    alpha = swipeAlpha.value * (1f - fade * 0.7f)
                }
                .then(swipeModifier)
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
            val faceKey: Any = if (face == Face.COLLAPSED) layout.main else face
            Crossfade(
                targetState = faceKey,
                animationSpec = tween(180),
                label = "face",
                modifier = Modifier.fillMaxSize(),
            ) { k ->
                // Each face is laid out at its own natural size, centered at the top, and clipped by
                // the animated shape: it gets revealed from the camera outwards.
                Box(
                    Modifier
                        .fillMaxSize()
                        .wrapContentSize(
                            when (camSide) {
                                "LEFT" -> Alignment.TopStart
                                "RIGHT" -> Alignment.TopEnd
                                else -> Alignment.TopCenter
                            },
                            unbounded = true,
                        )
                ) {
                    when (k) {
                        Compact.NONE -> Box(Modifier.size(targetW.coerceAtLeast(pillH), pillH))
                        Compact.MEDIA -> CameraGap(cfg) { MiniMedia(cfg, media) }
                        Compact.ACTIVITY -> activities.firstOrNull()?.let { a -> CameraGap(cfg) { CompactActivity(cfg, a) } }
                        Compact.TORCH -> CameraGap(cfg) { CompactTorch(cfg) }
                        Face.EXPANDED -> {
                            val startOnApps = (mode as? IslandMode.Expanded)?.startOnApps ?: false
                            key(startOnApps) { ExpandedCard(cfg, cardWidth, pageH, startOnApps, controller) }
                        }
                        Face.NOTIF -> {
                            val n = mode as? IslandMode.Notification
                            if (n != null) {
                                NotificationCard(
                                    cfg, n, cardWidth, controller,
                                    Modifier.onSizeChanged { measuredPx = it.height },
                                )
                            }
                        }
                        Face.VIDEO -> (mode as? IslandMode.Video)?.let {
                            VideoCard(cfg, it.videoId, cardWidth, pinned, controller)
                        }
                        Face.FLASHLIGHT -> FlashlightCard(cfg, FLASH_W, controller)
                        Face.ALERT -> (mode as? IslandMode.Alert)?.let {
                            AlertPill(cfg, it.alert, alertWidth(cfg, it.alert, cardWidth))
                        }
                        Face.ACTIVITY -> {
                            val key = (mode as? IslandMode.Activity)?.key
                            activities.firstOrNull { it.key == key }?.let {
                                ActivityCard(
                                    cfg, it, cardWidth, controller,
                                    Modifier.onSizeChanged { sz -> measuredPx = sz.height },
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
            // The open island covers the real clock and battery, so draw them beside the camera.
            val ctx = LocalContext.current
            val realStatus = remember(cfg) { cfg.underStatusBar(ctx) }
            val showStatus = cfg.statusInIsland && !realStatus &&
                (face == Face.EXPANDED || face == Face.NOTIF || face == Face.ACTIVITY)
            val statusAlpha by animateFloatAsState(if (showStatus) 1f else 0f, tween(200), label = "status")
            if (statusAlpha > 0.01f) {
                StatusRow(cfg, w, Modifier.graphicsLayer { alpha = statusAlpha })
            }
        }
    }
}

/** With a corner camera, leaves room for the camera hole at that end of the pill. */
@Composable
private fun CameraGap(cfg: IslandConfig, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cfg.cameraPosition == "LEFT") Spacer(Modifier.width(cfg.height.dp))
        content()
        if (cfg.cameraPosition == "RIGHT") Spacer(Modifier.width(cfg.height.dp))
    }
}

/**
 * Minimal clock (left of the camera) and battery % (right), in the empty corners of the open
 * island.
 */
@Composable
private fun StatusRow(cfg: IslandConfig, @Suppress("UNUSED_PARAMETER") cardWidth: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = rememberWallClock()
    val time = remember(now / 15_000) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now))
    }
    val battery by produceState(Controls.battery(context)) {
        while (true) {
            delay(20_000)
            value = Controls.battery(context)
        }
    }
    val batteryColor = when {
        battery.charging -> Color(0xFF7FE39A)
        battery.percent <= 20 -> Color(0xFFFF8A80)
        else -> Soft
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(cfg.height.dp)
            // Starts right where the rounded corner meets the straight top edge.
            .padding(horizontal = cfg.expandedCorner.coerceAtLeast(14f).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Corner camera on the left: keep that side free and put both on the right (and vice versa).
        if (cfg.cameraPosition == "LEFT") Spacer(Modifier.weight(1f))
        Text(time, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (cfg.cameraPosition == "CENTER") Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(12.dp))
        Text(
            "${battery.percent}%",
            color = if (battery.percent <= 20 && !battery.charging) batteryColor else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(5.dp))
        MiniBattery(battery.percent, battery.charging, batteryColor)
        if (cfg.cameraPosition == "RIGHT") Spacer(Modifier.weight(1f))
    }
}

/** Small minimal battery: outline, fill to the level, a bolt while charging. */
@Composable
private fun MiniBattery(percent: Int, charging: Boolean, fill: Color) {
    Box(
        Modifier
            .size(width = 23.dp, height = 12.dp)
            .drawBehind {
                val nubW = 1.6.dp.toPx()
                val bodyW = size.width - nubW - 1.dp.toPx()
                val stroke = 1.3.dp.toPx()
                val r = 3.2.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(bodyW - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r, r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                )
                val inset = stroke + 1.2.dp.toPx()
                val innerW = (bodyW - inset * 2) * (percent / 100f).coerceIn(0.04f, 1f)
                drawRoundRect(
                    color = if (charging) fill else if (percent <= 20) fill else Color.White,
                    topLeft = Offset(inset, inset),
                    size = Size(innerW, size.height - inset * 2),
                    cornerRadius = CornerRadius(r / 2, r / 2),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(bodyW + 1.dp.toPx() - nubW / 2, size.height * 0.32f),
                    size = Size(nubW, size.height * 0.36f),
                    cornerRadius = CornerRadius(nubW, nubW),
                )
                if (charging) {
                    // Little bolt in the middle
                    val cx = bodyW / 2
                    val h = size.height
                    val bolt = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx + h * 0.08f, h * 0.12f)
                        lineTo(cx - h * 0.22f, h * 0.56f)
                        lineTo(cx - h * 0.02f, h * 0.56f)
                        lineTo(cx - h * 0.1f, h * 0.88f)
                        lineTo(cx + h * 0.22f, h * 0.42f)
                        lineTo(cx + h * 0.02f, h * 0.42f)
                        close()
                    }
                    drawPath(bolt, Color.Black)
                }
            }
    )
}

private val TorchYellow = Color(0xFFFFD60A)

/** Alerts with only an icon + number (charging) stay compact. */
private fun alertWidth(cfg: IslandConfig, alert: SystemAlert?, cardWidth: Dp): Dp =
    minOf(cardWidth, cfg.width.dp + if (alert?.title.isNullOrBlank()) 100.dp else 170.dp)

private val VIDEO_BAR_H = 54.dp

// ------------------------------------------------------------------ video

/**
 * A YouTube video playing inside the island through YouTube's official embedded player.
 * Opened by sharing a video to "Play in island"; starts pinned so it stays open.
 */
@Composable
private fun VideoCard(cfg: IslandConfig, videoId: String, width: Dp, pinned: Boolean, controller: IslandController) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(width)
            .padding(start = 12.dp, end = 12.dp, top = cfg.height.dp + 6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape((cfg.expandedCorner - 12f).coerceAtLeast(8f).dp))
                .background(Color(0xFF111114)),
        ) {
            key(videoId) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> YouTubeWebView.create(ctx, videoId) },
                    onRelease = { it.destroy() },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(VIDEO_BAR_H),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(R.drawable.ic_pin, 36.dp, strong = pinned) { controller.togglePin() }
            GlassButton(R.drawable.ic_open, 36.dp) {
                // Continue in the YouTube app.
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                controller.collapse()
            }
            GlassButton(R.drawable.ic_close, 36.dp) { controller.collapse() }
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

/** Timer / call / navigation in the collapsed pill: icon on the left, live time on the right. */
@Composable
private fun CompactActivity(cfg: IslandConfig, a: LiveActivity) {
    val h = cfg.height.dp
    if (a.kind == LiveActivity.Kind.GEMINI) {
        GeminiCompact(cfg)
        return
    }
    val now = rememberWallClock()
    val accent = mono(Color(a.accent))
    val callGreen = mono(CallGreen)
    Row(
        modifier = Modifier
            .size(cfg.width.dp + IslandService.MINI_EXTRA_DP.dp, h)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ActivityIcon(a, h - 12.dp)
        Text(
            text = a.timerText(now) ?: a.text.ifBlank { a.title },
            color = if (a.kind == LiveActivity.Kind.CALL) callGreen else accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 90.dp),
        )
    }
}

@Composable
private fun CompactTorch(cfg: IslandConfig) {
    val h = cfg.height.dp
    val level by Controls.torchLevel.collectAsState()
    val context = LocalContext.current
    val max = remember { Controls.torchMaxLevel(context) }
    Row(
        modifier = Modifier
            .size(cfg.width.dp + 56.dp, h)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_flashlight),
            contentDescription = null,
            modifier = Modifier.size(h * 0.5f),
            colorFilter = ColorFilter.tint(mono(TorchYellow)),
        )
        if (max > 1) {
            Text("${(level * 100 / max)}%", color = mono(TorchYellow), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActivityIcon(a: LiveActivity, size: Dp) {
    if (a.kind == LiveActivity.Kind.GEMINI) {
        GeminiSparkle(size * 0.85f)
        return
    }
    val res = when (a.kind) {
        LiveActivity.Kind.CALL -> R.drawable.ic_call
        LiveActivity.Kind.TIMER -> R.drawable.ic_timer
        else -> null
    }
    if (res != null && a.kind != LiveActivity.Kind.CALL) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(size * 0.8f),
            colorFilter = ColorFilter.tint(mono(Color(a.accent))),
        )
    } else if (a.icon != null) {
        Image(
            bitmap = remember(a.icon) { a.icon.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(size * 0.8f),
            colorFilter = ColorFilter.tint(mono(CallGreen)),
        )
    }
}

private val CallGreen = Color(0xFF34C759)
private val CallRed = Color(0xFFFF453A)

/** Gemini Live colors: blue → violet → rose. */
private val GeminiColors = listOf(Color(0xFF4F8BFF), Color(0xFF9B72F3), Color(0xFFD96570))

/** A soft four-point sparkle that slowly turns and shimmers through the Gemini colors. */
@Composable
private fun GeminiSparkle(size: Dp) {
    val t by rememberInfiniteTransition(label = "gem").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3_000, easing = androidx.compose.animation.core.LinearEasing)), label = "gt",
    )
    val colors = GeminiColors.map { mono(it) }
    Box(
        Modifier.size(size).drawBehind {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = this.size.minDimension / 2
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y - r)
                quadraticBezierTo(c.x, c.y, c.x + r, c.y)
                quadraticBezierTo(c.x, c.y, c.x, c.y + r)
                quadraticBezierTo(c.x, c.y, c.x - r, c.y)
                quadraticBezierTo(c.x, c.y, c.x, c.y - r)
                close()
            }
            rotate(t * 90f, c) {
                drawPath(p, Brush.sweepGradient(colors + colors.first(), center = c))
            }
        }
    )
}

/** Gemini Live in the closed island: sparkle on the left, a flowing voice wave on the right. */
@Composable
private fun GeminiCompact(cfg: IslandConfig) {
    val h = cfg.height.dp
    val phase by rememberInfiniteTransition(label = "gw").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1_400, easing = androidx.compose.animation.core.LinearEasing)), label = "gp",
    )
    val colors = GeminiColors.map { mono(it) }
    Row(
        modifier = Modifier.size(cfg.width.dp + IslandService.MINI_EXTRA_DP.dp, h).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GeminiSparkle(h - 14.dp)
        Box(
            Modifier.size(width = 34.dp, height = 16.dp).drawBehind {
                val bars = 5
                val gap = 3.dp.toPx()
                val bw = (size.width - gap * (bars - 1)) / bars
                for (i in 0 until bars) {
                    val amp = 0.35f + 0.65f * ((kotlin.math.sin(phase + i * 1.1f) + 1f) / 2f)
                    val bh = size.height * amp
                    drawRoundRect(
                        brush = Brush.verticalGradient(colors, startY = 0f, endY = size.height),
                        topLeft = Offset(i * (bw + gap), (size.height - bh) / 2),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(bw / 2, bw / 2),
                    )
                }
            }
        )
    }
}

/** Wall-clock time that updates every half second (for timers). */
@Composable
private fun rememberWallClock(): Long {
    val t by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    return t
}

// --------------------------------------------------------- flashlight card

private val FLASH_W = 210.dp
private val FLASH_H = 250.dp

/**
 * iOS-style flashlight: a tall box with a glowing beam above the torch. Swipe up / down anywhere
 * to change the brightness (the beam grows and brightens), tap the torch to switch it on/off.
 */
@Composable
private fun FlashlightCard(cfg: IslandConfig, width: Dp, controller: IslandController) {
    val context = LocalContext.current
    val on by Controls.torch.collectAsState()
    val level by Controls.torchLevel.collectAsState()
    val max = remember { Controls.torchMaxLevel(context) }
    val target = if (!on) 0f else (level.toFloat() / max.coerceAtLeast(1)).coerceIn(0.15f, 1f)
    val beam by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = 300f), label = "beam")
    var heightPx by remember { mutableStateOf(1) }
    var dragStart by remember { mutableStateOf(0f) }
    val iconTint = mono(if (on) Color.White else Color.White.copy(alpha = 0.35f))
    val beamColor = mono(Color(0xFFF2EEFF))

    Box(
        modifier = Modifier
            .size(width, cfg.height.dp + FLASH_H)
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragStart = Controls.torchFraction() },
                ) { change, _ ->
                    change.consume()
                    val moved = (change.position.y - change.previousPosition.y) / (heightPx * 0.7f)
                    dragStart = (dragStart - moved).coerceIn(0f, 1f)
                    Controls.setTorchFraction(context, dragStart)
                    controller.poke()
                }
            }
            .drawBehind {
                if (beam <= 0.01f) return@drawBehind
                val cx = size.width / 2f
                val iconTop = size.height - 92.dp.toPx()
                // Soft wide glow
                val glowH = size.height * (0.35f + 0.45f * beam)
                drawOval(
                    brush = Brush.radialGradient(
                        0f to beamColor.copy(alpha = 0.32f * beam),
                        1f to Color.Transparent,
                        center = Offset(cx, iconTop - glowH * 0.35f),
                        radius = glowH * 0.6f,
                    ),
                    topLeft = Offset(cx - size.width * 0.42f, iconTop - glowH),
                    size = Size(size.width * 0.84f, glowH * 1.05f),
                )
                // Bright core beam
                val coreW = 26.dp.toPx() + 18.dp.toPx() * beam
                val coreH = glowH * 0.75f
                drawOval(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to beamColor.copy(alpha = 0.55f * beam),
                        0.85f to Color.White.copy(alpha = 0.95f * beam),
                        1f to Color.White.copy(alpha = beam),
                        startY = iconTop - coreH,
                        endY = iconTop,
                    ),
                    topLeft = Offset(cx - coreW / 2, iconTop - coreH),
                    size = Size(coreW, coreH + 6.dp.toPx()),
                )
            },
    ) {
        // Top hint: beam line + brightness sun, like iOS
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = cfg.height.dp + 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(26.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.3f)))
            Spacer(Modifier.width(14.dp))
            Image(
                painter = painterResource(R.drawable.ic_brightness),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.35f)),
            )
        }
        // The torch: tap to switch on/off
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
                .size(64.dp)
                .noRippleClick {
                    Controls.toggleTorch(context)
                    controller.poke()
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_flashlight),
                contentDescription = if (on) "Turn flashlight off" else "Turn flashlight on",
                modifier = Modifier.size(54.dp),
                colorFilter = ColorFilter.tint(iconTint),
            )
        }
        if (max <= 1) {
            Text(
                "No dimming on this phone",
                color = Dim,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
        }
    }
}

/** Accent colors turn white-ish when the "monochrome island" setting is on. */
@Composable
internal fun mono(c: Color): Color {
    val cfg by Prefs.island.collectAsState()
    return if (cfg.monochrome) Color.White.copy(alpha = (c.alpha * 0.92f).coerceAtMost(0.92f)) else c
}

// --------------------------------------------------------------- alerts

@Composable
private fun AlertPill(cfg: IslandConfig, alert: SystemAlert, width: Dp) {
    val color = if (alert.keepColor) Color(alert.color) else mono(Color(alert.color))
    Row(
        modifier = Modifier
            .size(width, cfg.height.dp + 8.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(alert.icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            alert.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        ) // (empty for charging: just the bolt and the percentage)
        // Leave the middle free for the camera.
        if (alert.trailing.isNotBlank()) {
            Text(alert.trailing, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        alert.level?.let { lvl ->
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(16.dp)
                    .drawBehind {
                        val stroke = 2.5.dp.toPx()
                        drawCircle(color.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        drawArc(
                            color = color,
                            startAngle = -90f,
                            sweepAngle = 360f * lvl,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
            )
        }
    }
}

// ------------------------------------------------------- activity card

@Composable
private fun ActivityCard(
    cfg: IslandConfig,
    a: LiveActivity,
    width: Dp,
    controller: IslandController,
    modifier: Modifier = Modifier,
) {
    val now = rememberWallClock()
    val timer = a.timerText(now)
    val geminiGlow = if (a.kind == LiveActivity.Kind.GEMINI) GeminiColors.map { mono(it) } else null
    Column(
        modifier = modifier
            .drawBehind {
                // Gemini Live: soft colored glow rising from the bottom of the card
                geminiGlow?.let { cs ->
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to cs[0].copy(alpha = 0.10f),
                            0.8f to cs[1].copy(alpha = 0.22f),
                            1f to cs[2].copy(alpha = 0.32f),
                        )
                    )
                }
            }
            .width(width)
            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp, top = cfg.height.dp + 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().noRippleClick {
                a.contentIntent?.sendCompat()
                controller.collapse()
            },
        ) {
            ActivityIcon(a, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (a.ringing) "Incoming call" else a.appName,
                    color = Dim,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Text(
                    a.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (a.text.isNotBlank() && a.text != timer) {
                    Text(a.text, color = Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (timer != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    timer,
                    color = mono(if (a.kind == LiveActivity.Kind.CALL) CallGreen else Color(a.accent)),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                )
            }
        }
        if (a.actions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                a.actions.forEach { (label, intent) ->
                    val tint = when (label) {
                        "Answer" -> mono(CallGreen)
                        "Decline", "End" -> mono(CallRed).copy(alpha = if (Prefs.island.value.monochrome) 0.3f else 1f)
                        else -> null
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(tint?.copy(alpha = 0.85f) ?: Color.White.copy(alpha = 0.1f))
                            .clickable {
                                runCatching { intent.send() }
                                controller.poke()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tint != null) {
                            Image(
                                painter = painterResource(if (label == "Answer") R.drawable.ic_call else R.drawable.ic_call_end),
                                contentDescription = label,
                                modifier = Modifier.size(20.dp),
                                colorFilter = ColorFilter.tint(Color.White),
                            )
                        } else {
                            Text(label, color = Soft, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
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
    val extras = buildList {
        if (cfg.tabWeather) add(ExtraTab.WEATHER)
        if (cfg.tabContacts) add(ExtraTab.CONTACTS)
        if (cfg.tabCamera) add(ExtraTab.CAMERA)
    }
    // Pages: one per media app, then the app menu, then the optional tabs.
    val pageCount = sessions.size + 1 + extras.size
    val pager = rememberPagerState(initialPage = initialPage) { pageCount }
    LaunchedEffect(pager.currentPage) { controller.poke() }

    val camera = cfg.height.dp

    Box(modifier = Modifier.width(width)) {
        // Blurred cover behind the whole card. Each media page's cover fades with the swipe
        // (read in the draw phase, so it follows your finger without a flash).
        sessions.forEachIndexed { i, s ->
            val b = s.blurred ?: return@forEachIndexed
            Image(
                bitmap = remember(b) { b.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val pos = pager.currentPage + pager.currentPageOffsetFraction
                        alpha = (1f - abs(pos - i)).coerceIn(0f, 1f)
                    },
            )
        }
        // …fading to black at the edges and around the camera, so it blends into the bezel.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    // Always drawn (it's just black over black on non-media tabs), so it never
                    // disappears before the cover has faded out.
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
                when {
                    s != null -> MediaPage(s, controller)
                    page == appsPage -> AppsPage(cfg, controller)
                    else -> when (extras.getOrNull(page - appsPage - 1)) {
                        ExtraTab.WEATHER -> WeatherPage(cfg)
                        ExtraTab.CONTACTS -> ContactsPage(controller)
                        ExtraTab.CAMERA -> CameraPage(active = pager.currentPage == page, controller = controller)
                        null -> Unit
                    }
                }
            }
            if (pageCount > 1) PageDots(count = pageCount, current = pager.currentPage)
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
        if (s.durationMs > 0) ScrubBar(s, controller)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isPinned by controller.pinned.collectAsState()
            GlassButton(R.drawable.ic_pip, 34.dp) { controller.popOut() }
            GlassButton(R.drawable.ic_prev, 42.dp) {
                MediaRepository.previous(context, s.packageName); controller.poke()
            }
            GlassButton(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, 52.dp, strong = true) {
                MediaRepository.playPause(context, s.packageName); controller.poke()
            }
            GlassButton(R.drawable.ic_next, 42.dp) {
                MediaRepository.next(context, s.packageName); controller.poke()
            }
            GlassButton(R.drawable.ic_pin, 34.dp, strong = isPinned) { controller.togglePin() }
        }
    }
}

private class MenuTile(
    val label: String,
    val icon: ImageBitmap? = null,
    @DrawableRes val res: Int? = null,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun AppsPage(cfg: IslandConfig, controller: IslandController) {
    val context = LocalContext.current
    val tab = remember(cfg) { AppTab(cfg) }
    val apps by produceState(initialValue = emptyList<AppEntry>(), cfg.menuApps, cfg.themedIcons, tab.maxApps) {
        value = withContext(Dispatchers.IO) { Apps.load(context, cfg.menuApps.take(tab.maxApps), cfg.themedIcons) }
    }

    // Toggle states are re-read every second while the tab is open (they can change elsewhere).
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        Controls.prepare(context)
        while (true) {
            delay(1_000)
            tick++
        }
    }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
        controller.collapse()
    }

    val controlTiles = tab.toggles.map { kind ->
        check(tick >= 0) // reading tick makes these tiles refresh every second
        when (kind) {
            ControlKind.FLASHLIGHT -> MenuTile(kind.label, res = kind.icon, active = Controls.torchOn) {
                Controls.toggleTorch(context); tick++; controller.poke()
            }
            ControlKind.DND -> MenuTile(kind.label, res = kind.icon, active = Controls.dndOn(context)) {
                if (Controls.toggleDnd(context)) { tick++; controller.poke() } else open(Controls.policyAccessIntent())
            }
            ControlKind.RINGER -> {
                val mode = Controls.ringerMode(context)
                val (label, icon) = when (mode) {
                    AudioManager.RINGER_MODE_VIBRATE -> "Vibrate" to R.drawable.ic_vibrate
                    AudioManager.RINGER_MODE_SILENT -> "Silent" to R.drawable.ic_bell_off
                    else -> "Sound" to R.drawable.ic_bell
                }
                MenuTile(label, res = icon, active = mode != AudioManager.RINGER_MODE_NORMAL) {
                    Controls.cycleRinger(context); tick++; controller.poke()
                }
            }
            ControlKind.AUTO_ROTATE -> MenuTile(kind.label, res = kind.icon, active = Controls.autoRotate(context)) {
                if (Controls.toggleAutoRotate(context)) { tick++; controller.poke() } else open(Controls.writeSettingsIntent(context))
            }
            ControlKind.BATTERY -> {
                val b = Controls.battery(context)
                MenuTile((if (b.charging) "⚡" else "") + "${b.percent}%", res = kind.icon, active = b.charging) {
                    open(Controls.batterySettings())
                }
            }
            ControlKind.INTERNET -> MenuTile(kind.label, res = kind.icon) { open(Controls.internetPanel()) }
            ControlKind.BRIGHTNESS, ControlKind.VOLUME, ControlKind.BLUETOOTH -> MenuTile(kind.label, res = kind.icon) {}
        }
    }
    val appTiles = apps.map { app ->
        MenuTile(app.label, icon = app.icon) {
            Apps.launch(context, app.packageName)
            controller.collapse()
        }
    }
    val settingsTile = if (cfg.showSettingsTile) {
        listOf(MenuTile("Settings", res = R.drawable.ic_settings) {
            open(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    } else emptyList()
    val tiles = controlTiles + appTiles + settingsTile

    // Brightness + optional black & white, applied to every app icon.
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        tab.sliders.forEach { kind ->
            when (kind) {
                ControlKind.BRIGHTNESS -> {
                    var value by remember { mutableStateOf(Controls.brightness(context)) }
                    GlassSlider(kind.icon, value) { v ->
                        controller.poke()
                        if (Controls.setBrightness(context, v)) value = v
                        else open(Controls.writeSettingsIntent(context))
                    }
                }
                ControlKind.VOLUME -> {
                    var value by remember { mutableStateOf(Controls.volume(context)) }
                    GlassSlider(kind.icon, value) { v ->
                        controller.poke()
                        Controls.setVolume(context, v)
                        value = v
                    }
                }
                else -> Unit
            }
        }
        val showBattery = ControlKind.BATTERY in tab.sliders
        val showBt = ControlKind.BLUETOOTH in tab.sliders
        if (showBattery || showBt) {
            DeviceRow(showBattery, showBt) { open(Controls.bluetoothSettings()) }
        }
        tiles.chunked(tab.cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { AppTile(it, iconSize, tileWidth, cfg.appLabels, filter) }
                repeat(tab.cols - row.size) { Spacer(Modifier.width(tileWidth)) }
            }
        }
    }
}

/**
 * Headphone battery as a filled bar (phone battery when no headphones report one), and/or the
 * connected Bluetooth device. With both on they split the row in half.
 */
@Composable
private fun DeviceRow(showBattery: Boolean, showBt: Boolean, onOpenBluetooth: () -> Unit) {
    val context = LocalContext.current
    val devices by BtDevices.devices.collectAsState()
    val btOn by BtDevices.enabled.collectAsState()
    val permitted = remember { BtDevices.hasPermission(context) }
    val phone by produceState(Controls.battery(context)) {
        while (true) {
            value = Controls.battery(context)
            delay(15_000)
        }
    }
    val withBattery = devices.firstOrNull { it.battery >= 0 }
    val main = withBattery ?: devices.firstOrNull()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showBattery) {
            val (icon, pct, label) = if (withBattery != null) {
                Triple(R.drawable.ic_headphones, withBattery.battery, if (showBt) "" else withBattery.name)
            } else {
                Triple(if (phone.charging) R.drawable.ic_charging else R.drawable.ic_battery, phone.percent, if (showBt) "" else "Phone")
            }
            InfoBar(
                icon = icon,
                fill = pct / 100f,
                title = label,
                trailing = "$pct%",
                fillColor = if (pct <= 20) Color(0xFFFF453A).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.26f),
                modifier = Modifier.weight(1f),
                onClick = onOpenBluetooth,
            )
        }
        if (showBt) {
            val (title, sub) = when {
                !permitted -> "Bluetooth" to "Allow in Maze Island settings"
                !btOn -> "Bluetooth" to "Off"
                main != null -> main.name to buildString {
                    append(main.profiles.joinToString(" + ").ifBlank { "Connected" })
                    if (devices.size > 1) append(" · +${devices.size - 1} more")
                }
                else -> "Bluetooth" to "Not connected"
            }
            InfoBar(
                icon = R.drawable.ic_bluetooth,
                fill = if (main != null) 1f else 0f,
                title = title,
                subtitle = sub,
                trailing = if (!showBattery && main != null && main.battery >= 0) "${main.battery}%" else "",
                fillColor = Color(0xFF64A8FF).copy(alpha = 0.22f),
                modifier = Modifier.weight(1f),
                onClick = onOpenBluetooth,
            )
        }
    }
}

/** Read-only bar in the same style as the sliders. */
@Composable
private fun InfoBar(
    @DrawableRes icon: Int,
    fill: Float,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    trailing: String = "",
    fillColor: Color = Color.White.copy(alpha = 0.26f),
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .height((AppTab.SLIDER_H - 8f).dp)
            .glass(shape)
            .noRippleClick(onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fill > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill.coerceIn(0.06f, 1f))
                    .clip(shape)
                    .background(fillColor)
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(Soft),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (title.isNotBlank()) {
                    Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing.isNotBlank()) {
                Text(trailing, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Frosted pill slider (like Control Center): drag or tap anywhere along it. */
@Composable
private fun GlassSlider(@DrawableRes icon: Int, value: Float, onChange: (Float) -> Unit) {
    var widthPx by remember { mutableStateOf(1) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((AppTab.SLIDER_H - 8f).dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .glass(shape)
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0.06f, 1f))
                .clip(shape)
                .background(Color.White.copy(alpha = 0.26f))
        )
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.padding(start = 12.dp).size(18.dp),
            colorFilter = ColorFilter.tint(Soft),
        )
    }
}

@Composable
private fun AppTile(tile: MenuTile, iconSize: Dp, tileWidth: Dp, showLabel: Boolean, filter: ColorFilter) {
    Column(
        modifier = Modifier.width(tileWidth).noRippleClick(tile.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            tile.icon != null -> Image(
                bitmap = tile.icon,
                contentDescription = tile.label,
                modifier = Modifier.size(iconSize),
                colorFilter = filter,
            )
            tile.res != null -> Box(
                Modifier
                    .size(iconSize)
                    .glass(CircleShape, strong = tile.active)
                    .then(if (tile.active) Modifier.background(Color.White.copy(alpha = 0.18f)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(tile.res),
                    contentDescription = tile.label,
                    modifier = Modifier.size(iconSize * 0.46f),
                    colorFilter = ColorFilter.tint(if (tile.active) Color.White else Soft.copy(alpha = 0.75f)),
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
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = cfg.height.dp + 14.dp),
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
                    modifier = Modifier.size(46.dp).clip(CircleShape),
                )
            } else {
                Box(Modifier.size(46.dp).glass(CircleShape))
            }
            Spacer(Modifier.width(14.dp))
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
                Spacer(Modifier.height(2.dp))
                Text(
                    event.text,
                    color = Color(0xFFD8D8DE),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
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

// ------------------------------------------------------------ scrubbing

/** Elapsed / remaining time with a bar you drag to seek (iOS style: the bar thickens while dragging). */
@Composable
private fun ScrubBar(s: MediaState, controller: IslandController) {
    val context = LocalContext.current
    val nowElapsed by produceState(SystemClock.elapsedRealtime(), s.isPlaying) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(if (s.isPlaying) 250 else 1_000)
        }
    }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // After seeking, hold the new position until the player reports it (avoids a jump back).
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    val livePos = s.livePosition(nowElapsed)
    LaunchedEffect(s.positionMs, s.positionUpdatedAt) { pendingSeek = null }

    val pos = dragFraction?.let { (it * s.durationMs).toLong() } ?: pendingSeek ?: livePos
    val fraction = (pos.toFloat() / s.durationMs).coerceIn(0f, 1f)
    var widthPx by remember { mutableStateOf(1) }
    val thickness by animateDpAsState(if (dragFraction != null) 6.dp else 3.5.dp, label = "thick")

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(s.packageName, s.durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / widthPx).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let {
                                val target = (it * s.durationMs).toLong()
                                pendingSeek = target
                                MediaRepository.seekTo(context, s.packageName, target)
                            }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                        controller.poke()
                    }
                }
                .pointerInput(s.packageName, s.durationMs) {
                    detectTapGestures {
                        val target = ((it.x / widthPx).coerceIn(0f, 1f) * s.durationMs).toLong()
                        pendingSeek = target
                        MediaRepository.seekTo(context, s.packageName, target)
                        controller.poke()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            WavyProgress(
                fraction = fraction,
                // Wavy while playing; flattens out when paused or while you drag.
                waving = s.isPlaying && dragFraction == null,
                thickness = thickness,
                active = dragFraction != null,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(pos), color = Dim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + formatDuration((s.durationMs - pos).coerceAtLeast(0)), color = Dim, fontSize = 11.sp)
        }
    }
}

/**
 * Progress line where the played part is a gently moving wave while music plays, and the
 * rest is a flat, dim line. The wave smoothly flattens when paused.
 */
@Composable
private fun WavyProgress(fraction: Float, waving: Boolean, thickness: Dp, active: Boolean) {
    val amplitude by animateFloatAsState(if (waving) 1f else 0f, tween(400), label = "amp")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing)),
        label = "phase",
    )
    val playedColor = Color.White.copy(alpha = if (active) 0.95f else 0.85f)
    val restColor = Color.White.copy(alpha = 0.2f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .drawBehind {
                val stroke = thickness.toPx().coerceAtMost(size.height / 2)
                val midY = size.height / 2f
                val endX = size.width * fraction.coerceIn(0f, 1f)
                val amp = (size.height / 2f - stroke / 2f) * 0.75f * amplitude
                val wavelength = 22.dp.toPx()

                // Remaining part: flat line
                if (endX < size.width) {
                    drawLine(
                        color = restColor,
                        start = Offset(endX + stroke, midY),
                        end = Offset(size.width - stroke / 2, midY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                // Played part: wave (a flat line when amplitude is 0)
                if (endX > stroke / 2) {
                    val path = androidx.compose.ui.graphics.Path()
                    val step = 2f
                    var x = stroke / 2
                    path.moveTo(x, midY + amp * kotlin.math.sin(x / wavelength * 2 * Math.PI.toFloat() - phase))
                    while (x < endX) {
                        x = minOf(x + step, endX)
                        // Wave tapers off near the playhead so it meets the flat line cleanly.
                        val taper = ((endX - x) / (wavelength * 0.6f)).coerceIn(0f, 1f)
                        path.lineTo(x, midY + amp * taper * kotlin.math.sin(x / wavelength * 2 * Math.PI.toFloat() - phase))
                    }
                    drawPath(
                        path,
                        color = playedColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    // Playhead
                    drawCircle(playedColor, radius = stroke * 0.9f, center = Offset(endX, midY))
                }
            }
    )
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
internal fun GlassButton(@DrawableRes res: Int, size: Dp, strong: Boolean = false, onClick: () -> Unit) {
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

internal fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier =
    this
        .clip(shape)
        .background(Color.White.copy(alpha = if (strong) 0.16f else 0.08f))
        .border(0.75.dp, Color.White.copy(alpha = if (strong) 0.22f else 0.12f), shape)

@Composable
internal fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
