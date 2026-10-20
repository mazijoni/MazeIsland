package com.mazedev.island.island

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mazedev.island.IslandConfig
import com.mazedev.island.R
import com.mazedev.island.util.Contact
import com.mazedev.island.util.Contacts
import com.mazedev.island.util.Weather
import com.mazedev.island.util.WeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional tabs you can swipe to in the open island. */
enum class ExtraTab { WEATHER, CONTACTS, CAMERA }

// ------------------------------------------------------------------ weather

@Composable
internal fun WeatherPage(cfg: IslandConfig) {
    val context = LocalContext.current
    val result by produceState<Weather.Result?>(null, cfg.weatherCity) {
        while (true) {
            value = withContext(Dispatchers.IO) { Weather.load(context, cfg.weatherCity) }
            delay(10 * 60_000L)
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        when (val r = result) {
            null -> Text("Loading weather…", color = Dim, fontSize = 13.sp)
            is Weather.Result.Problem -> Text(r.message, color = Dim, fontSize = 13.sp)
            is Weather.Result.Ok -> WeatherContent(r.info)
        }
    }
}

@Composable
private fun WeatherContent(w: WeatherInfo) {
    val (label, emoji) = Weather.describe(w.code, w.isDay)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 40.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.place, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("H:${w.high}°  L:${w.low}°", color = Dim, fontSize = 12.sp)
            }
            Text("${w.temp}°", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.hours.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.label, color = Dim, fontSize = 11.sp)
                    Text(Weather.describe(h.code).second, fontSize = 18.sp)
                    Text("${h.temp}°", color = Soft, fontSize = 12.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------------------- contacts

/** Favorites first, then everyone with a number. Scroll down; tap call or text. */
@Composable
internal fun ContactsPage(controller: IslandController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by produceState<List<Contact>?>(null) {
        value = withContext(Dispatchers.IO) { Contacts.load(context) }
    }
    if (!Contacts.hasPermission(context)) {
        CenterNote("Allow contacts in Maze Island settings")
        return
    }
    val list = contacts
    when {
        list == null -> CenterNote("Loading…")
        list.isEmpty() -> CenterNote("No contacts with a phone number")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list, key = { it.id }) { c ->
                ContactRow(
                    c,
                    onCall = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { Contacts.number(context, c.id) } ?: return@launch
                            runCatching { context.startActivity(Contacts.callIntent(context, n)) }
                            controller.collapse()
                        }
                    },
                    onText = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { Contacts.number(context, c.id) } ?: return@launch
                            runCatching { context.startActivity(Contacts.textIntent(n)) }
                            controller.collapse()
                        }
                    },
                    onScroll = { controller.poke() },
                )
            }
        }
    }
}

@Composable
private fun ContactRow(c: Contact, onCall: () -> Unit, onText: () -> Unit, onScroll: () -> Unit) {
    val context = LocalContext.current
    val photo by produceState<android.graphics.Bitmap?>(null, c.photo) {
        value = c.photo?.let { withContext(Dispatchers.IO) { Contacts.photo(context, it) } }
    }
    LaunchedEffect(Unit) { onScroll() } // rows appearing = scrolling: keep the island open
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val p = photo
        if (p != null) {
            Image(
                bitmap = remember(p) { p.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(CircleShape),
            )
        } else {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(c.name.take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (if (c.starred) "★ " else "") + c.name,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GlassButton(R.drawable.ic_message, 32.dp, onClick = onText)
        Spacer(Modifier.width(8.dp))
        GlassButton(R.drawable.ic_call, 32.dp, strong = true, onClick = onCall)
    }
}

// ------------------------------------------------------------------- camera

/**
 * Front camera as a mirror, fading into the black island at the edges. The shutter saves a
 * selfie to the gallery. The camera only runs while this tab is on screen.
 */
@Composable
internal fun CameraPage(active: Boolean, controller: IslandController) {
    val context = LocalContext.current
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        CenterNote("Allow the camera in Maze Island settings")
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // so it can fade + clip
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var error by remember { mutableStateOf<String?>(null) }
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    DisposableEffect(active) {
        var provider: ProcessCameraProvider? = null
        if (active) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture)
                    error = null
                }.onFailure { error = "Camera isn't available right now" }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { runCatching { provider?.unbindAll() } }
    }

    // clip = true: the camera image is scaled to fill and would otherwise spill outside the tab.
    Box(Modifier.fillMaxSize().graphicsLayer { clip = true }) {
        AndroidView(
            factory = {
                previewView.also {
                    it.outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                    it.clipToOutline = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Fade into the black island on every side (drawn on top of the camera view).
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.22f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.Black))
                    drawRect(Brush.horizontalGradient(0f to Color.Black, 0.16f to Color.Transparent, 0.84f to Color.Transparent, 1f to Color.Black))
                    drawRect(
                        Brush.radialGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = maxOf(size.width, size.height) * 0.62f,
                        )
                    )
                },
        )
        error?.let { CenterNote(it) }
        // Shutter: fires the moment your finger touches it (like a camera app), so a slight slide
        // can't turn the tap into a swipe between tabs and get it ignored.
        val view = LocalView.current
        val pressScale = remember { Animatable(1f) }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .size(64.dp) // bigger touch area than the visible button
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        controller.poke()
                        takeSelfie(context, capture) { ok ->
                            if (!ok) Toast.makeText(context, "Couldn't save the photo", Toast.LENGTH_SHORT).show()
                        }
                        scope.launch {
                            flash.snapTo(0.8f)
                            flash.animateTo(0f, tween(350))
                        }
                        scope.launch { pressScale.animateTo(0.85f, tween(80)) }
                        // Swallow the rest of this touch so the pager doesn't treat it as a swipe.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        scope.launch { pressScale.animateTo(1f, tween(120)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .graphicsLayer { scaleX = pressScale.value; scaleY = pressScale.value }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)))
            }
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
    }
}

private fun takeSelfie(context: Context, capture: ImageCapture, done: (Boolean) -> Unit) {
    val name = "Selfie_${System.currentTimeMillis()}"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Maze Island")
    }
    val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = true } // save it as you saw it
    val options = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
    ).setMetadata(metadata).build()
    capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(true)
        override fun onError(exception: ImageCaptureException) = done(false)
    })
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Dim, fontSize = 13.sp)
    }
}

