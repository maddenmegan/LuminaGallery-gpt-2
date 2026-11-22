package com.lumina.gallery.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.data.FileActions
import com.lumina.gallery.data.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val ViewerBg = Color(0xFF050506)
private val ViewerPanel = Color(0xFF14131A)

@Composable
fun PhotoViewerScreen(
    asset: MediaAsset, favorite: Boolean, onBack: () -> Unit, onStudio: () -> Unit,
    onShare: () -> Unit, onRename: (String) -> Unit, onCopy: (Boolean) -> Unit,
    onWallpaper: () -> Unit, onSafeShare: () -> Unit,
    onOcr: (Bitmap, (String, String) -> Unit) -> Unit, onTrash: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = asset.uri) {
        value = withContext(Dispatchers.IO) { loadViewerBitmap(context, asset) }
    }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offset by remember(asset.id) { mutableStateOf(Offset.Zero) }
    var menu by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var rename by remember(asset.id) { mutableStateOf(asset.name) }
    var ocrOpen by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    var ocrDetails by remember { mutableStateOf("") }
    var ocrShareText by remember { mutableStateOf("") }
    var metadataOpen by remember { mutableStateOf(false) }
    var metadataText by remember { mutableStateOf("") }
    var palette by remember { mutableStateOf<List<String>>(emptyList()) }
    var qrValue by remember(asset.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(asset.uri, bitmap) {
        if (asset.isImage && bitmap != null) {
            qrValue = withContext(Dispatchers.IO) {
                runCatching {
                    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    val found = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap!!, 0)), 8, TimeUnit.SECONDS)
                    scanner.close()
                    found.firstOrNull()?.rawValue
                }.getOrNull()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(ViewerBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(asset.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${asset.folder} · ${formatViewerSize(asset.size)}", color = Color(0xFF9B96A8), fontSize = 10.sp)
            }
            IconButton(onClick = onToggleFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, "Favorite", tint = if (favorite) Color(0xFFFF7EA5) else Color.White) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More tools", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = ViewerPanel) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { rename = asset.name; renameOpen = true; menu = false }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem(text = { Text("Copy to Folder") }, onClick = { onCopy(false); menu = false }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    DropdownMenuItem(text = { Text("Move to Folder") }, onClick = { onCopy(true); menu = false }, leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) })
                    DropdownMenuItem(text = { Text("Set as Wallpaper") }, onClick = { onWallpaper(); menu = false }, leadingIcon = { Icon(Icons.Outlined.Wallpaper, null) })
                    DropdownMenuItem(text = { Text("EXIF X-Ray & HEX palette") }, onClick = {
                        menu = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { inspectMetadata(context, asset, bitmap) }
                            metadataText = result.first
                            palette = result.second
                            metadataOpen = true
                        }
                    }, leadingIcon = { Icon(Icons.Outlined.Info, null) })
                    DropdownMenuItem(text = { Text("Remove EXIF · Safe Share copy") }, onClick = { onSafeShare(); menu = false }, leadingIcon = { Icon(Icons.Outlined.PrivacyTip, null) })
                    DropdownMenuItem(text = { Text("Share original") }, onClick = { onShare(); menu = false }, leadingIcon = { Icon(Icons.Outlined.Share, null) })
                    DropdownMenuItem(text = { Text(if (asset.isTrashed) "Restore from Recycle Bin" else "Move to Recycle Bin") }, onClick = { onTrash(); menu = false }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                asset.isImage && bitmap != null -> Image(
                    bitmap!!.asImageBitmap(), contentDescription = asset.name,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                        .pointerInput(asset.id) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedPointers = event.changes.count { it.pressed }
                                    if (pressedPointers > 0 && (pressedPointers >= 2 || scale > 1f)) {
                                        val next = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                        val pan = event.calculatePan()
                                        scale = next
                                        offset = if (next > 1f) offset + pan else Offset.Zero
                                        event.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(asset.id) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            })
                        }
                )
                asset.isImage -> Icon(Icons.Outlined.ImageNotSupported, null, tint = Color(0xFF898395), modifier = Modifier.size(64.dp))
                asset.isVideo -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.VideoLibrary, null, tint = Color(0xFFB7A1FF), modifier = Modifier.size(64.dp))
                    Text("Video preview", color = Color.White)
                    Text("Use Studio Pro to open the available trim workflow.", color = Color(0xFF9B96A8), fontSize = 11.sp)
                }
                asset.isPdf -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PictureAsPdf, null, tint = Color(0xFFB7A1FF), modifier = Modifier.size(64.dp))
                    Text("PDF document", color = Color.White)
                    Text("PDF page preview is not included in this build.", color = Color(0xFF9B96A8), fontSize = 11.sp)
                }
            }
            Text("${(scale * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), fontSize = 10.sp)
        }
        if (!qrValue.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF211B2B)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCode2, null, tint = Color(0xFFB7A1FF))
                Text("QR detected: ${qrValue!!.take(48)}", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qrValue))) }
                }) { Text("Open") }
            }
        }
        Row(Modifier.fillMaxWidth().background(ViewerPanel).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ViewerTool("Studio Pro", Icons.Outlined.Tune, onStudio)
            ViewerTool(if (ocrBusy) "Reading…" else "Extract Text", Icons.Outlined.TextFields) {
                val source = bitmap
                if (source == null) {
                    ocrText = "This file does not have a supported image preview for OCR."
                    ocrDetails = ""
                    ocrOpen = true
                } else {
                    ocrBusy = true
                    onOcr(source) { text, details ->
                        ocrText = text
                        ocrDetails = details
                        ocrBusy = false
                        ocrOpen = true
                    }
                }
            }
            ViewerTool("Share", Icons.Outlined.Share, onShare)
        }
    }

    if (renameOpen) AlertDialog(
        onDismissRequest = { renameOpen = false }, title = { Text("Rename file") },
        text = { OutlinedTextField(value = rename, onValueChange = { rename = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(rename); renameOpen = false }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } }, containerColor = ViewerPanel
    )
    if (ocrOpen) AlertDialog(
        onDismissRequest = { ocrOpen = false },
        title = { Text("Extracted text", color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 500.dp)) {
                Text(ocrDetails, color = Color(0xFFB7A1FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    OutlinedTextField(value = ocrText, onValueChange = { ocrText = it }, modifier = Modifier.fillMaxWidth().weight(1f), minLines = 8, maxLines = 16)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(ocrText)) }) { Text("Copy All") }
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ocrText) }
                    context.startActivity(Intent.createChooser(intent, "Share extracted text"))
                }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = { ocrOpen = false }) { Text("Close") } }, containerColor = ViewerPanel
    )
    if (metadataOpen) AlertDialog(
        onDismissRequest = { metadataOpen = false },
        title = { Text("EXIF X-Ray · HEX palette") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(metadataText, color = Color.White, fontSize = 11.sp) }
                Spacer(Modifier.height(10.dp))
                Text("Dominant sampled colours", color = Color.White, fontWeight = FontWeight.SemiBold)
                palette.forEach { hex -> Text(hex, color = Color(0xFFB7A1FF), fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { metadataOpen = false }) { Text("Close") } },
        containerColor = ViewerPanel
    )
}


private fun inspectMetadata(context: android.content.Context, asset: MediaAsset, bitmap: Bitmap?): Pair<String, List<String>> {
    val tags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE
    )
    val exifLines = runCatching {
        context.contentResolver.openInputStream(asset.uri)?.use { stream ->
            val exif = ExifInterface(stream)
            tags.mapNotNull { tag -> exif.getAttribute(tag)?.let { "$tag: $it" } }
        }.orEmpty()
    }.getOrDefault(emptyList())
    val colors = mutableMapOf<Int, Int>()
    if (bitmap != null) {
        val stepX = maxOf(1, bitmap.width / 60)
        val stepY = maxOf(1, bitmap.height / 60)
        for (y in 0 until bitmap.height step stepY) for (x in 0 until bitmap.width step stepX) {
            val color = bitmap.getPixel(x, y)
            val quantized = (color and 0x00F0F0F0) or (0xFF shl 24)
            colors[quantized] = (colors[quantized] ?: 0) + 1
        }
    }
    val palette = colors.entries.sortedByDescending { it.value }.take(8).map { entry -> "#%06X".format(entry.key and 0x00FFFFFF) }
    val text = buildString {
        appendLine("File: ${asset.name}")
        appendLine("MIME: ${asset.mimeType}")
        appendLine("Size: ${asset.size} bytes")
        appendLine("Folder: ${asset.relativePath}")
        appendLine("Modified: ${asset.modifiedSeconds}")
        appendLine()
        if (exifLines.isEmpty()) append("No readable EXIF fields were found.") else append(exifLines.joinToString("\n"))
    }
    return text to palette
}

@Composable
private fun ViewerTool(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, null, tint = Color(0xFFB7A1FF)) }
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

private fun loadViewerBitmap(context: android.content.Context, asset: MediaAsset): Bitmap? {
    if (!asset.isImage) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(asset.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        context.contentResolver.openInputStream(asset.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}

@Composable
fun StudioEditorScreen(asset: MediaAsset, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = asset.uri) {
        value = withContext(Dispatchers.IO) { loadViewerBitmap(context, asset) }
    }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }
    var cropMode by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Original") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(ViewerBg)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) { Text("Studio Pro", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text(asset.name, color = Color(0xFF9994A6), fontSize = 10.sp) }
            TextButton(onClick = {
                val source = bitmap
                if (source == null) message = "This media type cannot be edited as a still image."
                else {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val edited = renderEdit(source, cropLeft, cropTop, cropRight, cropBottom, brightness, contrast, saturation, preset)
                        val uri = FileActions.saveBitmap(context, edited, "Lumina_${System.currentTimeMillis()}.jpg")
                        withContext(Dispatchers.Main) {
                            busy = false
                            if (uri != null) onSaved("Edited copy saved to Pictures/Lumina Gallery/Edited.") else message = "Could not export the edited image."
                            edited.recycle()
                        }
                    }
                }
            }, enabled = !busy) { Text(if (busy) "Saving…" else "Export") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(12.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap!!.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(saturation) }))
                if (cropMode) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().pointerInput(cropMode) {
                        var activeHandle = 3
                        detectDragGestures(
                            onDragStart = { touch ->
                                val handles = listOf(
                                    Offset(cropLeft, cropTop), Offset((cropLeft + cropRight) / 2f, cropTop), Offset(cropRight, cropTop),
                                    Offset(cropRight, (cropTop + cropBottom) / 2f), Offset(cropRight, cropBottom),
                                    Offset((cropLeft + cropRight) / 2f, cropBottom), Offset(cropLeft, cropBottom), Offset(cropLeft, (cropTop + cropBottom) / 2f)
                                )
                                val normalized = Offset(touch.x / size.width, touch.y / size.height)
                                activeHandle = handles.indices.minByOrNull { index ->
                                    val dx = (handles[index].x - normalized.x) * size.width
                                    val dy = (handles[index].y - normalized.y) * size.height
                                    dx * dx + dy * dy
                                } ?: 3
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val dx = drag.x / size.width
                                val dy = drag.y / size.height
                                when (activeHandle) {
                                    0 -> { cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - 0.15f); cropTop = (cropTop + dy).coerceIn(0f, cropBottom - 0.15f) }
                                    1 -> cropTop = (cropTop + dy).coerceIn(0f, cropBottom - 0.15f)
                                    2 -> { cropRight = (cropRight + dx).coerceIn(cropLeft + 0.15f, 1f); cropTop = (cropTop + dy).coerceIn(0f, cropBottom - 0.15f) }
                                    3 -> cropRight = (cropRight + dx).coerceIn(cropLeft + 0.15f, 1f)
                                    4 -> { cropRight = (cropRight + dx).coerceIn(cropLeft + 0.15f, 1f); cropBottom = (cropBottom + dy).coerceIn(cropTop + 0.15f, 1f) }
                                    5 -> cropBottom = (cropBottom + dy).coerceIn(cropTop + 0.15f, 1f)
                                    6 -> { cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - 0.15f); cropBottom = (cropBottom + dy).coerceIn(cropTop + 0.15f, 1f) }
                                    else -> cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - 0.15f)
                                }
                            }
                        )
                    }) {
                        val rect = RectF(size.width * cropLeft, size.height * cropTop, size.width * cropRight, size.height * cropBottom)
                        drawRect(Color.White.copy(alpha = 0.55f), topLeft = Offset.Zero, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                        drawRect(Color(0xFFB7A1FF), topLeft = Offset(rect.left, rect.top), size = androidx.compose.ui.geometry.Size(rect.width(), rect.height()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                        val handle = 8.dp.toPx()
                        listOf(
                            Offset(rect.left, rect.top), Offset(rect.centerX(), rect.top), Offset(rect.right, rect.top),
                            Offset(rect.right, rect.centerY()), Offset(rect.right, rect.bottom), Offset(rect.centerX(), rect.bottom),
                            Offset(rect.left, rect.bottom), Offset(rect.left, rect.centerY())
                        ).forEach { drawCircle(Color.White, handle, it) }
                    }
                }
            } else Icon(Icons.Outlined.ImageNotSupported, null, tint = Color.Gray, modifier = Modifier.size(60.dp))
        }
        Column(Modifier.fillMaxWidth().background(ViewerPanel).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = cropMode, onClick = { cropMode = !cropMode }, label = { Text("Freeform crop") }, leadingIcon = { Icon(Icons.Outlined.Crop, null) })
                FilterChip(selected = !cropMode, onClick = { cropMode = false }, label = { Text("Adjust") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) })
            }
            Text("Presets", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Original", "Vivid", "Warm", "Noir").forEach { value -> FilterChip(selected = preset == value, onClick = { preset = value; if (value == "Noir") saturation = 0f else if (value == "Original") saturation = 1f else if (value == "Vivid") saturation = 1.45f else saturation = 1.1f }, label = { Text(value) }) }
            }
            SliderRow("Brightness", brightness, -0.25f, 0.25f) { brightness = it }
            SliderRow("Contrast", contrast, 0.6f, 1.6f) { contrast = it }
            SliderRow("Saturation", saturation, 0f, 2f) { saturation = it }
            HorizontalDivider(color = Color(0xFF34313D), modifier = Modifier.padding(vertical = 9.dp))
            Text("AI & pro tools", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("These operations require model packages or a configured inference endpoint and are not simulated in this build.", color = Color(0xFF9994A6), fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { message = "Subject segmentation model is not bundled. Add a verified ML Kit subject-segmentation model before enabling PNG cutout." }, modifier = Modifier.weight(1f)) { Text("Cutout") }
                OutlinedButton(onClick = { message = "Natural Beauty Retouch needs a face-retouch model. No synthetic retouch is applied." }, modifier = Modifier.weight(1f)) { Text("Beauty AI") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { message = "Magic Eraser needs an inpainting model or remote endpoint." }, modifier = Modifier.weight(1f)) { Text("Magic Eraser") }
                OutlinedButton(onClick = { message = "AI HD Enhance needs a super-resolution model." }, modifier = Modifier.weight(1f)) { Text("AI HD") }
            }
            if (asset.isVideo) OutlinedButton(onClick = { message = "Lossless hardware trimmer is not implemented. Use a dedicated video editor; Lumina does not re-encode or claim lossless trimming." }, modifier = Modifier.fillMaxWidth()) { Text("Video trim capability") }
        }
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, text = { Text(it) }, containerColor = ViewerPanel) }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(84.dp))
        Slider(value = value, onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), color = Color(0xFF9994A6), fontSize = 10.sp, modifier = Modifier.width(42.dp))
    }
}

private fun renderEdit(source: Bitmap, l: Float, t: Float, r: Float, b: Float, brightness: Float, contrast: Float, saturation: Float, preset: String): Bitmap {
    val left = (source.width * l).toInt().coerceIn(0, source.width - 1)
    val top = (source.height * t).toInt().coerceIn(0, source.height - 1)
    val right = (source.width * r).toInt().coerceIn(left + 1, source.width)
    val bottom = (source.height * b).toInt().coerceIn(top + 1, source.height)
    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    val output = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val satMatrix = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
    val values = satMatrix.array
    for (row in 0..3) {
        val base = row * 5
        for (col in 0..3) values[base + col] *= contrast
        values[base + 4] += brightness * 255f + if (row < 3) (1f - contrast) * 127f else 0f
    }
    if (preset == "Noir") satMatrix.setSaturation(0f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(satMatrix) }
    Canvas(output).drawBitmap(cropped, 0f, 0f, paint)
    if (cropped !== source) cropped.recycle()
    return output
}

private fun formatViewerSize(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    else -> "%.0f KB".format(value / 1024.0)
}
