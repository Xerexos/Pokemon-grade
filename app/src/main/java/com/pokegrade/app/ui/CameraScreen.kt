package com.pokegrade.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import com.pokegrade.app.camera.BackCamera
import com.pokegrade.app.camera.BackCameras
import java.util.concurrent.TimeUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

/** Card guide: 63:88 aspect, this share of the preview width. */
private const val GUIDE_WIDTH = 0.74f
private const val CARD_ASPECT = 63f / 88f

@Composable
fun CameraScreen(
    onPhoto: (Bitmap) -> Unit,
    onSettings: () -> Unit,
    hint: String = "Take the card out of any sleeve or case and place it inside the frame on a dark, plain surface.\nAvoid glare and hold the phone flat.",
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadBitmap(context, it) }?.let(onPhoto)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(onPhoto, hint)
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera access is needed to scan cards.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 36.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Default.PhotoLibrary, "Pick from gallery", tint = Color.White) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
        }
    }
}

@Composable
private fun CameraPreview(onPhoto: (Bitmap) -> Unit, hint: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Sharpest 4:3 photo up to ~12 MP: plenty for a card, and full 50-200 MP
            // frames would use hundreds of MB of memory.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var cameras by remember { mutableStateOf<List<BackCamera>>(emptyList()) }
    var selectedId by remember { mutableStateOf(BackCameras.savedId(context)) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var torch by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, selectedId) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val list = runCatching { BackCameras.list(provider) }.getOrDefault(emptyList())
            cameras = list
            val chosen = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
            if (chosen != null && selectedId != chosen.id) selectedId = chosen.id
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(lifecycleOwner, chosen?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.getOrElse {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }
            zoom = 1f
            torch = false
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    fun focusAt(x: Float, y: Float, then: (() -> Unit)? = null) {
        val cam = camera
        if (cam == null) {
            then?.invoke()
            return
        }
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        val result = cam.cameraControl.startFocusAndMetering(action)
        if (then != null) {
            // Capture once focus settles, or after 1.5 s if the lens never reports back.
            var done = false
            val go = Runnable { if (!done) { done = true; then() } }
            result.addListener(go, ContextCompat.getMainExecutor(context))
            previewView.postDelayed(go, 1500)
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        GuideOverlay()
        // Transparent layer above the preview: tap anywhere to focus there.
        Box(Modifier.fillMaxSize().pointerInput(camera) { detectTapGestures { focusAt(it.x, it.y) } })

        // Camera picker + torch, below the gallery/settings row.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cameras.size > 1) {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Cameraswitch, null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(cameras.firstOrNull { it.id == selectedId }?.label ?: "Camera", color = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        cameras.forEach { cam ->
                            DropdownMenuItem(
                                text = { Text(cam.label + if (cam.id == selectedId) "  ✓" else "") },
                                onClick = {
                                    menuOpen = false
                                    BackCameras.save(context, cam.id)
                                    selectedId = cam.id
                                },
                            )
                        }
                    }
                }
            } else {
                cameras.firstOrNull()?.let { Text(it.label, color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Light", tint = Color.White)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Zoom presets. 2x often switches to the telephoto lens, which gives a
            // flatter, less distorted card photo from a bit further away.
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            if (maxZoom >= 1.5f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 1.5f, 2f, 3f).filter { it <= maxZoom }.forEach { z ->
                        FilterChip(
                            selected = zoom == z,
                            onClick = { zoom = z; camera?.cameraControl?.setZoomRatio(z) },
                            label = { Text("${if (z % 1f == 0f) z.toInt().toString() else z.toString()}x") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(hint, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Text("Tap the card to focus.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .background(if (busy) Color.Gray else Color.White, CircleShape),
            ) {
                IconButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        // Focus on the card (centre of the guide) before taking the photo.
                        focusAt(viewSize.width / 2f, viewSize.height / 2f) {
                            capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val full = image.toUprightBitmap()
                                    image.close()
                                    busy = false
                                    onPhoto(cropToGuide(full, viewSize))
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
    }
}

@Composable
private fun GuideOverlay() {
    Canvas(
        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val gw = size.width * GUIDE_WIDTH
        val gh = gw / CARD_ASPECT
        val tl = Offset((size.width - gw) / 2, (size.height - gh) / 2)
        val corner = CornerRadius(gw * 0.048f)
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, tl, GeoSize(gw, gh), corner, blendMode = BlendMode.Clear)
        drawRoundRect(Color(0xFFF5CD28), tl, GeoSize(gw, gh), corner, style = Stroke(width = 3.dp.toPx()))
    }
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bmp = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bmp
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/**
 * The preview shows the camera image scaled to fill the view (center-crop).
 * Map the on-screen guide back to image pixels and crop it with a margin so
 * the card's outline is surrounded by some background.
 */
private fun cropToGuide(img: Bitmap, view: IntSize): Bitmap {
    if (view.width == 0 || view.height == 0) return img
    val scale = max(view.width.toFloat() / img.width, view.height.toFloat() / img.height)
    val gw = view.width * GUIDE_WIDTH
    val gh = gw / CARD_ASPECT
    val margin = 0.14f
    val vw = gw * (1 + 2 * margin)
    val vh = gh * (1 + 2 * margin)
    fun toImgX(x: Float) = (x - view.width / 2f) / scale + img.width / 2f
    fun toImgY(y: Float) = (y - view.height / 2f) / scale + img.height / 2f
    val l = toImgX(view.width / 2f - vw / 2).roundToInt().coerceIn(0, img.width - 1)
    val t = toImgY(view.height / 2f - vh / 2).roundToInt().coerceIn(0, img.height - 1)
    val r = toImgX(view.width / 2f + vw / 2).roundToInt().coerceIn(l + 1, img.width)
    val b = toImgY(view.height / 2f + vh / 2).roundToInt().coerceIn(t + 1, img.height)
    return Bitmap.createBitmap(img, l, t, r - l, b - t)
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }
}.getOrNull()
