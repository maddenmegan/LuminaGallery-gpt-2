package com.lumina.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.data.FaceFinder
import com.lumina.gallery.data.FileActions
import com.lumina.gallery.data.MediaAsset
import com.lumina.gallery.security.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lumina.gallery.worker.MediaIndexWorker
import kotlinx.coroutines.withContext
import java.io.File

private val ScreenPanel = Color(0xFF121218)
private val ScreenMuted = Color(0xFF9994A6)

@Composable
fun AiFindScreen(assets: List<MediaAsset>, onOpenAsset: (MediaAsset) -> Unit, onSaveKey: (String) -> Unit, hasKey: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var scanningColor by remember { mutableStateOf(false) }
    var colorResults by remember { mutableStateOf<Set<Long>?>(null) }
    var colorProgress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val indexPrefs = remember { context.getSharedPreferences("lumina_index", Context.MODE_PRIVATE) }
    var indexedCount by remember { mutableIntStateOf(indexPrefs.getInt("indexed", 0)) }
    var indexTotal by remember { mutableIntStateOf(indexPrefs.getInt("total", assets.size)) }
    var indexComplete by remember { mutableStateOf(indexPrefs.getBoolean("complete", false)) }
    var indexPaused by remember { mutableStateOf(indexPrefs.getBoolean("paused", false)) }
    LaunchedEffect(Unit) {
        while (true) {
            indexedCount = indexPrefs.getInt("indexed", 0)
            indexTotal = indexPrefs.getInt("total", assets.size)
            indexComplete = indexPrefs.getBoolean("complete", false)
            delay(800)
        }
    }
    val hintMap = remember {
        mapOf(
            "lal" to "red", "লাল" to "red", "nil" to "blue", "নীল" to "blue", "sobuj" to "green", "সবুজ" to "green",
            "holud" to "yellow", "হলুদ" to "yellow", "shada" to "white", "সাদা" to "white", "kalo" to "black", "কালো" to "black",
            "manush" to "person", "মানুষ" to "person", "biral" to "cat", "বিড়াল" to "cat", "kukur" to "dog", "কুকুর" to "dog"
        )
    }
    val expandedQuery = remember(query) {
        val words = query.lowercase().split(Regex("\\s+"))
        (words + words.mapNotNull { hintMap[it] }).filter { it.isNotBlank() }
    }
    val isColor = query.trim().lowercase() in setOf("red", "blue", "green", "yellow", "orange", "purple", "pink", "black", "white", "brown")
    val fileResults = remember(assets, expandedQuery, colorResults) {
        if (expandedQuery.isEmpty()) emptyList() else assets.filter { asset ->
            (asset.name + " " + asset.relativePath + " " + asset.mimeType).lowercase().let { haystack -> expandedQuery.any { word -> haystack.contains(word) } } ||
                (colorResults?.contains(asset.id) == true)
        }.sortedWith(compareByDescending<MediaAsset> { it.isPdf || it.isScreenshot }.thenByDescending { it.modifiedSeconds })
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF050506)).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("AI Find", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Text("Search your whole library, not just the first few hundred files.", color = ScreenMuted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it; colorResults = null },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Try “red car”, “Screenshots”, or natural-language hints") },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFB7A1FF), unfocusedBorderColor = Color(0xFF393642))
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { query = "Screenshots" }, label = { Text("Screenshots") }, leadingIcon = { Icon(Icons.Outlined.Screenshot, null, modifier = Modifier.size(16.dp)) })
            AssistChip(onClick = { query = "PDF" }, label = { Text("PDFs") }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null, modifier = Modifier.size(16.dp)) })
            AssistChip(onClick = { query = "red"; colorResults = null }, label = { Text("Dominant color") }, leadingIcon = { Icon(Icons.Outlined.Palette, null, modifier = Modifier.size(16.dp)) })
        }
        if (isColor && colorResults == null) {
            TextButton(onClick = {
                scanningColor = true
                colorProgress = 0
                scope.launch(Dispatchers.IO) {
                    val matches = mutableSetOf<Long>()
                    val candidates = assets.filter { it.isImage }
                    candidates.forEachIndexed { index, asset ->
                        val bitmap = decodeSmallBitmap(context, asset)
                        if (bitmap != null) {
                            if (matchesColor(bitmap, query)) matches += asset.id
                            bitmap.recycle()
                        }
                        if (index % 25 == 0 || index == candidates.lastIndex) {
                            withContext(Dispatchers.Main) { colorProgress = index + 1 }
                        }
                    }
                    withContext(Dispatchers.Main) { colorResults = matches; scanningColor = false }
                }
            }) { Text(if (scanningColor) "Scanning $colorProgress / ${assets.size}…" else "Scan all images for $query") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (query.isBlank()) 0 else fileResults.size} matches", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${assets.size} local items", color = ScreenMuted, fontSize = 11.sp)
        }
        if (query.isNotBlank()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(fileResults, key = { it.id }) { asset ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ScreenPanel).clickable(onClick = { onOpenAsset(asset) }), verticalAlignment = Alignment.CenterVertically) {
                        MediaThumbnail(asset, Modifier.size(66.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(asset.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(asset.relativePath, color = ScreenMuted, fontSize = 10.sp, maxLines = 1)
                            Text(if (asset.isPdf) "PDF document" else if (asset.isScreenshot) "Screenshot" else asset.mimeType, color = Color(0xFFB7A1FF), fontSize = 10.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = ScreenMuted, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolFeatureCard("Unlimited local search", "Filename and folder metadata search runs across the complete MediaStore query; no fixed 300-item cap.", Icons.Outlined.AllInclusive)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ScreenPanel).padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Background metadata index", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (indexComplete) "Complete" else if (indexPaused) "Paused" else "Running", color = Color(0xFFB7A1FF), fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (indexComplete) 1f else if (indexTotal > 0) indexedCount.toFloat() / indexTotal else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("$indexedCount / $indexTotal · PDFs and screenshots first", color = ScreenMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    TextButton(onClick = {
                        if (!indexComplete && !indexPaused) {
                            indexPaused = true
                            indexPrefs.edit().putBoolean("paused", true).apply()
                            WorkManager.getInstance(context).cancelUniqueWork("lumina-media-index")
                        } else {
                            indexPaused = false
                            indexPrefs.edit().putBoolean("paused", false).putBoolean("complete", false).apply()
                            val request = OneTimeWorkRequestBuilder<MediaIndexWorker>().build()
                            WorkManager.getInstance(context).enqueueUniqueWork("lumina-media-index", ExistingWorkPolicy.REPLACE, request)
                        }
                    }) { Text(if (indexComplete || indexPaused) "Resume / Rebuild index" else "Pause indexing") }
                }
                ToolFeatureCard("Index scope", "This index stores all media metadata. Embedded PDF text extraction and OCR-history full-text search still need their extraction pipeline.", Icons.Outlined.ManageSearch)
                ToolFeatureCard("Visual understanding", "Dominant-colour scan is performed on demand across local images. Object/scene recognition is not claimed without a bundled vision model.", Icons.Outlined.ColorLens)
                OutlinedButton(onClick = { showKey = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(8.dp)); Text(if (hasKey) "Update Gemini API key" else "Add Gemini API key for multi-language OCR")
                }
                Text("Gemini fallback order: 2.5 Flash → 2.5 Flash Lite → 2.0 Flash. Errors are shown, not silently replaced.", color = ScreenMuted, fontSize = 10.sp)
            }
        }
    }

    if (showKey) AlertDialog(
        onDismissRequest = { showKey = false },
        title = { Text("Gemini Vision API key") },
        text = {
            Column {
                Text("The key is encrypted with Android Keystore before it is saved locally.", color = ScreenMuted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text("API key") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { if (keyInput.isNotBlank()) onSaveKey(keyInput.trim()); keyInput = ""; showKey = false }) { Text("Save key") } },
        dismissButton = { TextButton(onClick = { showKey = false }) { Text("Cancel") } },
        containerColor = ScreenPanel
    )
}

private fun decodeSmallBitmap(context: Context, asset: MediaAsset): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(asset.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
    context.contentResolver.openInputStream(asset.uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) })
    }
}.getOrNull()

private fun matchesColor(bitmap: Bitmap, color: String): Boolean {
    // A 4-bit-per-channel histogram is cheap to build and gives a coarse dominant colour.
    val histogram = HashMap<Int, Int>()
    val sx = maxOf(1, bitmap.width / 32); val sy = maxOf(1, bitmap.height / 32)
    for (y in 0 until bitmap.height step sy) for (x in 0 until bitmap.width step sx) {
        val pixel = bitmap.getPixel(x, y)
        val bucket = (((pixel shr 20) and 0xF) shl 8) or (((pixel shr 12) and 0xF) shl 4) or ((pixel shr 4) and 0xF)
        histogram[bucket] = (histogram[bucket] ?: 0) + 1
    }
    val dominant = histogram.maxByOrNull { it.value }?.key ?: return false
    val rr = (((dominant shr 8) and 0xF) * 17 + 8).coerceAtMost(255)
    val gg = (((dominant shr 4) and 0xF) * 17 + 8).coerceAtMost(255)
    val bb = ((dominant and 0xF) * 17 + 8).coerceAtMost(255)
    val max = maxOf(rr, gg, bb); val min = minOf(rr, gg, bb)
    return when (color.lowercase()) {
        "red" -> rr > gg * 1.25 && rr > bb * 1.2
        "blue" -> bb > rr * 1.2 && bb > gg * 1.12
        "green" -> gg > rr * 1.12 && gg > bb * 1.12
        "yellow" -> rr > 120 && gg > 100 && bb < minOf(rr, gg) * 0.75
        "orange" -> rr > 135 && gg > 55 && gg < rr * 0.8 && bb < 100
        "purple" -> bb > gg * 1.25 && rr > gg * 1.2
        "pink" -> rr > 145 && bb > 100 && gg < rr * 0.88
        "black" -> max < 65
        "white" -> min > 205
        "brown" -> rr > gg && gg > bb && rr < 160
        else -> false
    }
}

@Composable
fun PeopleScreen(assets: List<MediaAsset>, onOpenAsset: (MediaAsset) -> Unit, onScan: (((List<FaceFinder.Group>) -> Unit) -> Unit)) {
    var scanning by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<FaceFinder.Group>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var hiddenIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var mergeSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var groupNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    Column(Modifier.fillMaxSize().background(Color(0xFF050506)).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("People", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Text("Face-detection candidates · 2+ photos per group", color = ScreenMuted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        ToolFeatureCard("Accurate face scan", "ML Kit accurate mode, five landmark checks, minimum face size, focus and YCbCr skin-tone heuristics. Results are filtered to reduce obvious false positives.", Icons.Outlined.Face)
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            scanning = true; error = null
            onScan { found -> groups = found; scanning = false }
        }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
            if (scanning) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Face, null)
            Spacer(Modifier.width(8.dp)); Text(if (scanning) "Scanning photos…" else "Scan all photos")
        }
        Text("Identity matching uses a lightweight aligned-crop visual hash in this build, not a trained face-embedding model. Verify groups before merging.", color = ScreenMuted, fontSize = 10.sp)
        Spacer(Modifier.height(12.dp))
        if (groups.isEmpty() && !scanning) EmptyState("No people grouped yet", "Run a face scan. Only groups appearing in at least two photos are shown.", null) {}
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
            items(groups.filterNot { it.id in hiddenIds }, key = { it.id }) { group ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ScreenPanel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (group.hits.isNotEmpty()) MediaThumbnail(group.hits.first().asset, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(groupNames[group.id] ?: "Person ${group.id + 1}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${group.hits.size} photos · similar face crops", color = ScreenMuted, fontSize = 11.sp)
                        TextButton(onClick = { group.hits.firstOrNull()?.asset?.let(onOpenAsset) }) { Text("View photos") }
                    }
                    Checkbox(checked = group.id in mergeSelection, onCheckedChange = { checked -> mergeSelection = if (checked) mergeSelection + group.id else mergeSelection - group.id })
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = ScreenPanel) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { renameTarget = group.id; renameText = groupNames[group.id] ?: "Person ${group.id + 1}"; menu = false })
                            DropdownMenuItem(text = { Text("Hide person") }, onClick = { hiddenIds = hiddenIds + group.id; menu = false })
                        }
                    }
                }
            }
        }
        if (mergeSelection.size >= 2) TextButton(onClick = {
            val chosen = groups.filter { it.id in mergeSelection }
            val mergedHits = chosen.flatMap { it.hits }.distinctBy { it.asset.id }
            val newId = (groups.maxOfOrNull { it.id } ?: 0L) + 1
            groups = groups.filterNot { it.id in mergeSelection } + FaceFinder.Group(newId, mergedHits)
            groupNames = groupNames + (newId to "Merged person")
            mergeSelection = emptySet()
        }) { Text("Merge ${mergeSelection.size} people") }
    }
    if (renameTarget != null) AlertDialog(
        onDismissRequest = { renameTarget = null }, title = { Text("Rename person") },
        text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { groupNames = groupNames + (renameTarget!! to renameText); renameTarget = null }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }, containerColor = ScreenPanel
    )
}

@Composable
fun CleanScreen(
    assets: List<MediaAsset>, onTrash: (MediaAsset) -> Unit, onBulkTrash: (List<MediaAsset>) -> Unit,
    onChooseThumbnails: () -> Unit, onChooseStatuses: () -> Unit, onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Swipe Clean") }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var duplicates by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var heavy by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var scanSummary by remember { mutableStateOf<String?>(null) }
    val tabs = listOf("Swipe Clean", "Radar", "Junk")

    Column(Modifier.fillMaxSize().background(Color(0xFF050506)).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Clean", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Text("Find clutter before it takes over.", color = ScreenMuted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { label -> FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(label) }) }
        }
        when (tab) {
            "Swipe Clean" -> {
                val candidates = assets.filter { it.isScreenshot || it.isGif || it.isVideo }
                if (candidates.isEmpty()) EmptyState("All clear", "No screenshots, GIFs or videos are waiting in this quick-clean queue.", null) {}
                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                    items(candidates, key = { it.id }) { asset ->
                        var dismissed by remember { mutableStateOf(false) }
                        var dragOffset by remember { mutableFloatStateOf(0f) }
                        if (!dismissed) Row(Modifier.fillMaxWidth().offset { IntOffset(dragOffset.roundToInt(), 0) }
                            .pointerInput(asset.id) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { _, amount -> dragOffset = (dragOffset + amount).coerceIn(-size.width.toFloat(), 0f) },
                                    onDragEnd = { if (dragOffset < -size.width * 0.28f) { onTrash(asset); dismissed = true }; dragOffset = 0f }
                                )
                            }.clip(RoundedCornerShape(15.dp)).background(ScreenPanel).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            MediaThumbnail(asset, Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(asset.name, color = Color.White, maxLines = 1)
                                Text("${asset.folder} · ${formatBytes(asset.size)}", color = ScreenMuted, fontSize = 10.sp)
                            }
                            IconButton(onClick = { onTrash(asset); dismissed = true }) { Icon(Icons.Outlined.Delete, "Trash", tint = Color(0xFFFF8B8B)) }
                        }
                    }
                }
                Text("Swipe left to move an item to Recycle Bin, or use the trash icon. Nothing is removed without an Android confirmation.", color = ScreenMuted, fontSize = 10.sp)
            }
            "Radar" -> {
                Button(onClick = {
                    scanning = true; progress = 0; duplicates = emptyList(); heavy = emptyList(); scanSummary = null
                    scope.launch(Dispatchers.IO) {
                        val big = assets.filter { it.size >= 50L * 1024 * 1024 }.sortedByDescending { it.size }
                        val candidates = assets.filter { it.size > 0L }.groupBy { it.size }.values.filter { it.size > 1 }.flatten()
                        val hashes = mutableMapOf<String, MutableList<MediaAsset>>()
                        candidates.forEachIndexed { index, asset ->
                            FileActions.sha256(context, asset)?.let { hashes.getOrPut(it) { mutableListOf() }.add(asset) }
                            if (index % 10 == 0) withContext(Dispatchers.Main) { progress = index + 1 }
                        }
                        val dupes = hashes.values.filter { it.size > 1 }.flatMap { it.drop(1) }.distinctBy { it.id }
                        withContext(Dispatchers.Main) {
                            duplicates = dupes; heavy = big; scanning = false
                            scanSummary = "Checked ${candidates.size} same-size candidates. ${dupes.size} exact duplicate(s) and ${big.size} file(s) over 50 MB found."
                        }
                    }
                }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Radar, null)
                    Spacer(Modifier.width(7.dp)); Text(if (scanning) "Hashing candidates · $progress…" else "Run Duplicate & Heavy File Radar")
                }
                scanSummary?.let { Text(it, color = ScreenMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                    if (duplicates.isNotEmpty()) {
                        item { Text("Exact duplicates", color = Color.White, fontWeight = FontWeight.SemiBold) }
                        items(duplicates, key = { "d${it.id}" }) { asset -> CleanFileRow(asset, onTrash) }
                        item { Button(onClick = { onBulkTrash(duplicates) }) { Text("Trash duplicate candidates") } }
                    }
                    if (heavy.isNotEmpty()) {
                        item { Text("Heavy files · 50 MB+", color = Color.White, fontWeight = FontWeight.SemiBold) }
                        items(heavy, key = { "h${it.id}" }) { asset -> CleanFileRow(asset, onTrash) }
                    }
                    if (!scanning && duplicates.isEmpty() && heavy.isEmpty() && scanSummary != null) item { Text("No duplicate or heavy files found.", color = ScreenMuted) }
                }
            }
            else -> {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolFeatureCard("Thumbnail cache", "Choose the storage root or DCIM parent in the system folder picker. Lumina will remove an accessible .thumbnails directory only.", Icons.Outlined.Cached)
                    Button(onClick = onChooseThumbnails, modifier = Modifier.fillMaxWidth()) { Text("Choose folder to clean .thumbnails") }
                    ToolFeatureCard("WhatsApp Status Saver", "Choose WhatsApp/Media/.Statuses in the folder picker. Visible status images and videos are copied to Pictures/Lumina Gallery/Statuses.", Icons.Outlined.Download)
                    OutlinedButton(onClick = onChooseStatuses, modifier = Modifier.fillMaxWidth()) { Text("Choose .Statuses folder") }
                }
            }
        }
    }
}

@Composable
private fun CleanFileRow(asset: MediaAsset, onTrash: (MediaAsset) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ScreenPanel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MediaThumbnail(asset, Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(asset.name, color = Color.White, maxLines = 1)
            Text("${asset.folder} · ${formatBytes(asset.size)}", color = ScreenMuted, fontSize = 10.sp)
        }
        IconButton(onClick = { onTrash(asset) }) { Icon(Icons.Outlined.Delete, "Trash", tint = Color(0xFFFF8B8B)) }
    }
}

@Composable
fun VaultScreen(vault: VaultStore, onAddFiles: () -> Unit, onShare: (File) -> Unit, onMessage: (String) -> Unit, onBiometric: ((Boolean) -> Unit) -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(vault.entries()) }
    var showNewPin by remember { mutableStateOf(!vault.hasPin()) }
    var showBiometricInfo by remember { mutableStateOf(false) }
    LaunchedEffect(unlocked) {
        while (unlocked) {
            entries = vault.entries()
            delay(900)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF050506)).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Vault", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Text("Private files are encrypted at rest with Android Keystore AES-GCM.", color = ScreenMuted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        if (!unlocked) {
            ToolFeatureCard("Private by design", "Files imported here are encrypted into app-private storage. The PIN gate is separate from the Keystore encryption key.", Icons.Outlined.Shield)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(), label = { Text(if (showNewPin) "Create PIN (4–12 digits)" else "Enter PIN") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            if (showNewPin) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = confirm, onValueChange = { confirm = it.filter(Char::isDigit).take(12) }, modifier = Modifier.fillMaxWidth(), label = { Text("Confirm PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (showNewPin) {
                    if (pin.length < 4 || pin != confirm) onMessage("Use a 4–12 digit PIN and confirm it exactly.")
                    else { vault.setPin(pin); showNewPin = false; unlocked = true; pin = ""; confirm = ""; entries = vault.entries() }
                } else {
                    if (vault.verifyPin(pin)) { unlocked = true; entries = vault.entries(); pin = "" }
                    else onMessage("Incorrect PIN. Your vault remains locked.")
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (showNewPin) "Create encrypted vault" else "Unlock vault") }
            if (!showNewPin) TextButton(onClick = { onBiometric { success -> if (success) { unlocked = true; entries = vault.entries() } else onMessage("Biometric authentication was cancelled or unavailable. Use your PIN.") } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Biometric unlock") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} encrypted files", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { unlocked = false }) { Text("Lock now") }
            }
            Button(onClick = onAddFiles, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add files to vault") }
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) EmptyState("Your vault is empty", "Add photos, videos or documents to encrypt a private copy.", null) {}
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(entries, key = { it.file.name }) { entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ScreenPanel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Encrypted, null, tint = Color(0xFFB7A1FF), modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(entry.originalName, color = Color.White, maxLines = 1)
                            Text("Encrypted · ${formatBytes(entry.file.length())}", color = ScreenMuted, fontSize = 10.sp)
                        }
                        IconButton(onClick = {
                            val file = vault.export(entry)
                            if (file != null) onShare(file) else onMessage("Could not decrypt this vault item. The file may be damaged or the Keystore key is unavailable.")
                        }) { Icon(Icons.Outlined.Share, "Share decrypted copy", tint = Color.White) }
                        IconButton(onClick = { vault.delete(entry); entries = vault.entries() }) { Icon(Icons.Outlined.Delete, "Delete from vault", tint = Color(0xFFFF8B8B)) }
                    }
                }
            }
        }
    }
    if (showBiometricInfo) AlertDialog(
        onDismissRequest = { showBiometricInfo = false },
        title = { Text("Biometric unlock") },
        text = { Text("Use the device's system biometric prompt to unlock this screen. PIN unlock remains available as a fallback.") },
        confirmButton = { TextButton(onClick = { showBiometricInfo = false; onMessage("Biometric prompt integration is not enabled in this build; use your vault PIN.") }) { Text("OK") } },
        containerColor = ScreenPanel
    )
}

@Composable
fun ToolFeatureCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ScreenPanel).padding(13.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Color(0xFFB7A1FF), modifier = Modifier.size(23.dp))
        Column(Modifier.padding(start = 11.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(description, color = ScreenMuted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    value >= 1024 -> "%.0f KB".format(value / 1024.0)
    else -> "$value B"
}
