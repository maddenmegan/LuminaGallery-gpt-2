package com.lumina.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumina.gallery.data.FaceFinder
import com.lumina.gallery.data.FileActions
import com.lumina.gallery.data.MediaAsset
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.security.VaultStore
import com.lumina.gallery.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

enum class MainTab(val label: String) { Albums("Albums"), Find("AI Find"), People("People"), Clean("Clean"), Vault("Vault") }
enum class SortMode(val label: String) { Modified("Last modified"), Name("Name"), Path("Path"), Size("Size"), Taken("Date taken"), Random("Random") }
private data class PendingTransfer(val assets: List<MediaAsset>, val move: Boolean)

@Composable
fun LuminaGalleryApp(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("lumina_gallery", Context.MODE_PRIVATE) }

    var media by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var trash by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var permissionGranted by remember { mutableStateOf(activity.hasMediaAccess()) }
    var tab by remember { mutableStateOf(MainTab.Albums) }
    var activeFolder by remember { mutableStateOf<String?>(null) }
    var recycleBinOpen by remember { mutableStateOf(false) }
    var viewerAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var studioOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var exitDialog by remember { mutableStateOf(false) }
    var pendingTransfer by remember { mutableStateOf<PendingTransfer?>(null) }
    var createFolderParent by remember { mutableStateOf<Uri?>(null) }
    var createFolderName by remember { mutableStateOf("New Folder") }
    var createFolderDialog by remember { mutableStateOf(false) }
    var treePurpose by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All Media") }
    var sortMode by remember { mutableStateOf(SortMode.Modified) }
    var ascending by remember { mutableStateOf(false) }
    var timeline by remember { mutableStateOf(false) }
    var columns by remember { mutableIntStateOf(prefs.getInt("columns", 3).coerceIn(1, 5)) }
    var showHidden by remember { mutableStateOf(prefs.getBoolean("show_hidden", false)) }
    var favoriteIds by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()) }
    var pinnedFolders by remember { mutableStateOf(prefs.getStringSet("pinned_folders", emptySet()).orEmpty()) }
    var hiddenFolders by remember { mutableStateOf(prefs.getStringSet("hidden_folders", emptySet()).orEmpty()) }

    fun refresh() {
        if (!activity.hasMediaAccess()) {
            permissionGranted = false
            isLoading = false
            return
        }
        permissionGranted = true
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // Keep the current grid composed during observer refreshes so stable lazy keys preserve scroll.
            if (media.isEmpty() && trash.isEmpty()) isLoading = true
            val pair = withContext(Dispatchers.IO) {
                MediaRepository.query(context, includeTrash = false) to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaRepository.query(context, includeTrash = true) else emptyList()
            }
            media = pair.first
            trash = pair.second
            isLoading = false
            // Re-index after a debounced library refresh; REPLACE cancels an older pass and
            // the worker resumes from its last committed batch when the library size is unchanged.
            val indexPrefs = context.getSharedPreferences("lumina_index", Context.MODE_PRIVATE)
            if (!indexPrefs.getBoolean("paused", false)) {
                val request = androidx.work.OneTimeWorkRequestBuilder<com.lumina.gallery.worker.MediaIndexWorker>().build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    "lumina-media-index", androidx.work.ExistingWorkPolicy.REPLACE, request
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(activity) {
        val observer = MediaRepository.observe(context) { refresh() }
        activity.onMediaAccessChanged = { refresh() }
        activity.onMediaPicked = { uris ->
            scope.launch(Dispatchers.IO) {
                val vault = VaultStore(context)
                var count = 0
                uris.forEach { uri ->
                    val name = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "Private file" } }.getOrNull() ?: "Private file"
                    if (vault.add(uri, name)) count++
                }
                withContext(Dispatchers.Main) { statusMessage = "Added $count file(s) to the encrypted vault." }
            }
        }
        activity.onFolderPicked = { uri ->
            if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            when (treePurpose) {
                "transfer" -> {
                    val transfer = pendingTransfer
                    if (uri != null && transfer != null) {
                        scope.launch(Dispatchers.IO) {
                            val result = FileActions.transferToTree(context, transfer.assets, uri, transfer.move)
                            withContext(Dispatchers.Main) {
                                statusMessage = result.second ?: "${if (transfer.move) "Moved" else "Copied"} ${result.first} item(s)."
                                if (transfer.move && result.first == transfer.assets.size) activity.requestTrash(transfer.assets.map { it.uri }, true)
                                selectedIds = emptySet()
                                refresh()
                            }
                        }
                    }
                    pendingTransfer = null
                }
                "thumbnails" -> {
                    val root = uri?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it) }
                    val cache = root?.let { findDocumentFolder(it, ".thumbnails", 0) }
                    val deleted = cache?.let(::deleteDocumentTree) ?: false
                    statusMessage = if (deleted) "Thumbnail cache folder removed." else "No accessible .thumbnails folder was found."
                }
                "statuses" -> {
                    val root = uri?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it) }
                    val source = root?.let { findDocumentFolder(it, ".Statuses", 0) } ?: root
                    val files = source?.listFiles()?.filter { it.isFile && (it.type?.startsWith("image/") == true || it.type?.startsWith("video/") == true) }.orEmpty()
                    var saved = 0
                    files.forEach { file ->
                        runCatching {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Status_${System.currentTimeMillis()}_${file.name}")
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, file.type ?: "image/jpeg")
                                if (Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lumina Gallery/Statuses")
                            }
                            val collection = if (file.type?.startsWith("video/") == true) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            val outUri = context.contentResolver.insert(collection, values) ?: return@runCatching
                            val copied = context.contentResolver.openInputStream(file.uri)?.use { input ->
                                context.contentResolver.openOutputStream(outUri)?.use { output -> input.copyTo(output) }
                            } != null
                            if (copied) saved++ else context.contentResolver.delete(outUri, null, null)
                        }
                    }
                    statusMessage = "Saved $saved WhatsApp status file(s)."
                    refresh()
                }
                "new_folder" -> {
                    if (uri != null) { createFolderParent = uri; createFolderName = "New Folder"; createFolderDialog = true }
                    else statusMessage = "Folder selection cancelled."
                }
            }
            treePurpose = ""
        }
        onDispose {
            MediaRepository.unregister(context, observer)
            activity.onMediaAccessChanged = null
            activity.onMediaPicked = null
            activity.onFolderPicked = null
        }
    }

    fun toggleFavorite(asset: MediaAsset) {
        favoriteIds = if (asset.id in favoriteIds) favoriteIds - asset.id else favoriteIds + asset.id
        prefs.edit().putStringSet("favorites", favoriteIds.map(Long::toString).toSet()).apply()
    }



    BackHandler(enabled = true) {
        when {
            createFolderDialog -> createFolderDialog = false
            statusMessage != null -> statusMessage = null
            exitDialog -> exitDialog = false
            studioOpen -> studioOpen = false
            viewerAsset != null -> viewerAsset = null
            recycleBinOpen -> recycleBinOpen = false
            activeFolder != null -> activeFolder = null
            selectedIds.isNotEmpty() -> selectedIds = emptySet()
            tab != MainTab.Albums -> tab = MainTab.Albums
            else -> exitDialog = true
        }
    }

    LuminaTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF050506)) {
            Box(Modifier.fillMaxSize()) {
                when {
                studioOpen && viewerAsset != null -> StudioEditorScreen(
                    asset = viewerAsset!!,
                    onBack = { studioOpen = false },
                    onSaved = { message -> statusMessage = message; refresh() }
                )
                viewerAsset != null -> PhotoViewerScreen(
                    asset = viewerAsset!!,
                    favorite = viewerAsset!!.id in favoriteIds,
                    onBack = { viewerAsset = null },
                    onStudio = { studioOpen = true },
                    onShare = { activity.shareAssets(listOf(viewerAsset!!.uri)) },
                    onRename = { newName ->
                        val target = viewerAsset!!
                        activity.requestWriteAccess(listOf(target.uri)) {
                            val ok = MediaRepository.updateDisplayName(context, target, newName)
                            statusMessage = if (ok) "File renamed." else "Android did not allow this file to be renamed."
                            refresh()
                        }
                    },
                    onCopy = { move -> pendingTransfer = PendingTransfer(listOf(viewerAsset!!), move); treePurpose = "transfer"; activity.pickFolder() },
                    onWallpaper = {
                        val ok = FileActions.setWallpaper(context, viewerAsset!!)
                        statusMessage = if (ok) "Wallpaper applied." else "Android could not apply this image as wallpaper."
                    },
                    onSafeShare = {
                        scope.launch(Dispatchers.IO) {
                            val uri = FileActions.cleanExifCopy(context, viewerAsset!!)
                            withContext(Dispatchers.Main) {
                                if (uri != null) activity.shareAssets(listOf(uri)) else statusMessage = "Could not create an EXIF-clean copy."
                            }
                        }
                    },
                    onOcr = { bitmap, callback ->
                        scope.launch(Dispatchers.IO) {
                            val key = com.lumina.gallery.security.SecretStore.get(context, "gemini_api_key")
                            val result = com.lumina.gallery.data.OcrEngine.extract(bitmap, key)
                            withContext(Dispatchers.Main) { callback(result.text, "${result.source}\n${result.details}".trim()) }
                        }
                    },
                    onTrash = { if (viewerAsset!!.isTrashed) activity.requestRestore(listOf(viewerAsset!!.uri)) else activity.requestTrash(listOf(viewerAsset!!.uri), true); viewerAsset = null; refresh() },
                    onToggleFavorite = { viewerAsset?.let(::toggleFavorite) }
                )
                recycleBinOpen -> {
                    FolderMediaScreen(
                        title = "Recycle Bin",
                        assets = trash,
                        columns = columns,
                        selectedIds = selectedIds,
                        onAssetClick = { asset -> if (selectedIds.isNotEmpty()) selectedIds = selectedIds.toggle(asset.id) else viewerAsset = asset },
                        onAssetLongClick = { selectedIds = selectedIds + it.id },
                        onBack = { recycleBinOpen = false },
                        onShare = { activity.shareAssets(it.map(MediaAsset::uri)) },
                        onTrash = { activity.requestTrash(it.map(MediaAsset::uri), false); selectedIds = emptySet() },
                        onRestore = { if (selectedIds.isNotEmpty()) { activity.requestRestore(trash.filter { it.id in selectedIds }.map { it.uri }); selectedIds = emptySet() } },
                        onSelectAll = { selectedIds = trash.map { it.id }.toSet() },
                        isRecycleBin = true
                    )
                }
                activeFolder != null -> {
                    val contents = media.filter { it.relativePath.trimEnd('/') == activeFolder }
                    FolderMediaScreen(
                        title = activeFolder!!.substringAfterLast('/').ifBlank { "Internal storage" },
                        assets = contents,
                        columns = columns,
                        selectedIds = selectedIds,
                        onAssetClick = { asset -> if (selectedIds.isNotEmpty()) selectedIds = selectedIds.toggle(asset.id) else viewerAsset = asset },
                        onAssetLongClick = { selectedIds = selectedIds + it.id },
                        onBack = { activeFolder = null },
                        onShare = { activity.shareAssets(it.map(MediaAsset::uri)) },
                        onTrash = { activity.requestTrash(it.map(MediaAsset::uri), true); selectedIds = emptySet() },
                        onRestore = {},
                        onSelectAll = { selectedIds = contents.map { it.id }.toSet() }
                    )
                }
                else -> {
                    Column(Modifier.fillMaxSize().background(Color(0xFF050506))) {
                        Box(Modifier.weight(1f)) {
                        when (tab) {
                            MainTab.Albums -> AlbumsScreen(
                                media = media,
                                trashCount = trash.size,
                                loading = isLoading,
                                permissionGranted = permissionGranted,
                                searchQuery = searchQuery,
                                onSearchChange = { searchQuery = it },
                                onRequestPermission = { activity.requestMediaAccess() },
                                filter = filter,
                                onFilterChange = { filter = it },
                                sortMode = sortMode,
                                onSortChange = { sortMode = it },
                                ascending = ascending,
                                onAscendingChange = { ascending = it },
                                timeline = timeline,
                                onTimelineChange = { timeline = it },
                                columns = columns,
                                onColumnsChange = { columns = it; prefs.edit().putInt("columns", it).apply() },
                                showHidden = showHidden,
                                onShowHiddenChange = { showHidden = it; prefs.edit().putBoolean("show_hidden", it).apply() },
                                favorites = favoriteIds,
                                pinnedFolders = pinnedFolders,
                                hiddenFolders = hiddenFolders,
                                onOpenFolder = { activeFolder = it },
                                onOpenTrash = { recycleBinOpen = true },
                                selectedIds = selectedIds,
                                onAssetClick = { asset -> if (selectedIds.isNotEmpty()) selectedIds = selectedIds.toggle(asset.id) else viewerAsset = asset },
                                onAssetLongClick = { selectedIds = selectedIds + it.id },
                                onOpenAsset = { viewerAsset = it },
                                onToggleFavorite = ::toggleFavorite,
                                onPinFolder = { key -> pinnedFolders = if (key in pinnedFolders) pinnedFolders - key else pinnedFolders + key; prefs.edit().putStringSet("pinned_folders", pinnedFolders).apply() },
                                onHideFolder = { key -> hiddenFolders = hiddenFolders + key; prefs.edit().putStringSet("hidden_folders", hiddenFolders).apply() },
                                onQr = { activity.scanQr { statusMessage = it } },
                                onCamera = { activity.launchCamera() },
                                onCreateFolder = { treePurpose = "new_folder"; activity.pickFolder() },
                                onGeminiKey = { tab = MainTab.Find }
                            )
                            MainTab.Find -> AiFindScreen(
                                assets = media,
                                onOpenAsset = { viewerAsset = it },
                                onSaveKey = { key ->
                                    com.lumina.gallery.security.SecretStore.put(context, "gemini_api_key", key)
                                    statusMessage = "Gemini API key saved in Android Keystore-encrypted storage."
                                },
                                hasKey = !com.lumina.gallery.security.SecretStore.get(context, "gemini_api_key").isNullOrBlank()
                            )
                            MainTab.People -> PeopleScreen(
                                assets = media,
                                onOpenAsset = { viewerAsset = it },
                                onScan = { onFinished ->
                                    scope.launch(Dispatchers.IO) {
                                        val groups = FaceFinder.scan(media, { asset -> loadBitmap(context, asset) })
                                        withContext(Dispatchers.Main) { onFinished(groups) }
                                    }
                                }
                            )
                            MainTab.Clean -> CleanScreen(
                                assets = media,
                                onTrash = { activity.requestTrash(listOf(it.uri), true); refresh() },
                                onBulkTrash = { assets -> activity.requestTrash(assets.map(MediaAsset::uri), true); refresh() },
                                onChooseThumbnails = { treePurpose = "thumbnails"; activity.pickFolder() },
                                onChooseStatuses = { treePurpose = "statuses"; activity.pickFolder() },
                                onMessage = { statusMessage = it }
                            )
                            MainTab.Vault -> VaultScreen(
                                vault = remember { VaultStore(context) },
                                onAddFiles = { activity.pickDocuments() },
                                onShare = { file ->
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    activity.shareAssets(listOf(uri))
                                },
                                onMessage = { statusMessage = it },
                                onBiometric = { callback -> activity.authenticateBiometric(callback) }
                            )
                        }
                        }
                        BottomNavigationBar(tab = tab, onSelect = { selectedIds = emptySet(); tab = it })
                    }
                }
            }
                if (selectedIds.isNotEmpty() && viewerAsset == null && !studioOpen) {
                    val selected = (media + trash).filter { it.id in selectedIds }
                    SelectionActionBar(
                        count = selectedIds.size,
                        allCount = (if (recycleBinOpen) trash else if (activeFolder != null) media.filter { it.relativePath.trimEnd('/') == activeFolder } else media).size,
                        onSelectAll = {
                            val visible = if (recycleBinOpen) trash else if (activeFolder != null) media.filter { it.relativePath.trimEnd('/') == activeFolder } else media
                            selectedIds = visible.map { it.id }.toSet()
                        },
                        onShare = { activity.shareAssets(selected.map { it.uri }) },
                        onTrash = { if (recycleBinOpen) activity.requestRestore(selected.map { it.uri }) else activity.requestTrash(selected.map { it.uri }, true); selectedIds = emptySet() },
                        onDelete = { activity.requestTrash(selected.map { it.uri }, false); selectedIds = emptySet() },
                        onMove = { pendingTransfer = PendingTransfer(selected, true); treePurpose = "transfer"; activity.pickFolder() },
                        onCopy = { pendingTransfer = PendingTransfer(selected, false); treePurpose = "transfer"; activity.pickFolder() },
                        onClose = { selectedIds = emptySet() },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).then(
                            if (activeFolder == null && !recycleBinOpen) Modifier.padding(bottom = 64.dp) else Modifier
                        )
                    )
                }
            }
        }
    }

    LuminaTheme {
    if (exitDialog) AlertDialog(
        onDismissRequest = { exitDialog = false },
        title = { Text("Exit Lumina Gallery?") },
        text = { Text("Your media library and encrypted vault remain on this device.") },
        confirmButton = { TextButton(onClick = { activity.finish() }) { Text("Exit") } },
        dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("Stay") } },
        containerColor = Color(0xFF17171D)
    )
    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            confirmButton = { TextButton(onClick = { statusMessage = null }) { Text("OK") } },
            text = { Text(message) },
            containerColor = Color(0xFF17171D)
        )
    }
    if (createFolderDialog) AlertDialog(
        onDismissRequest = { createFolderDialog = false },
        title = { Text("Create folder") },
        text = { androidx.compose.material3.OutlinedTextField(value = createFolderName, onValueChange = { createFolderName = it }, singleLine = true, label = { Text("Folder name") }) },
        confirmButton = { TextButton(onClick = {
            val parent = createFolderParent?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it) }
            val created = runCatching { parent?.createDirectory(createFolderName.trim()) != null }.getOrDefault(false)
            statusMessage = if (created) "Folder created: ${createFolderName.trim()}" else "Could not create that folder in the selected location."
            createFolderDialog = false
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { createFolderDialog = false }) { Text("Cancel") } },
        containerColor = Color(0xFF17171D)
    )
    }
}

private fun Set<Long>.toggle(value: Long): Set<Long> = if (value in this) this - value else this + value

fun loadBitmap(context: Context, asset: MediaAsset): Bitmap? {
    if (!asset.isImage) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(asset.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        context.contentResolver.openInputStream(asset.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}

private fun findDocumentFolder(root: androidx.documentfile.provider.DocumentFile, name: String, depth: Int): androidx.documentfile.provider.DocumentFile? {
    if (depth > 6) return null
    if (root.name.equals(name, ignoreCase = true) && root.isDirectory) return root
    val children = runCatching { root.listFiles() }.getOrNull().orEmpty()
    children.firstOrNull { it.name.equals(name, ignoreCase = true) && it.isDirectory }?.let { return it }
    children.filter { it.isDirectory }.forEach { child -> findDocumentFolder(child, name, depth + 1)?.let { return it } }
    return null
}

private fun deleteDocumentTree(folder: androidx.documentfile.provider.DocumentFile): Boolean {
    runCatching { folder.listFiles().forEach { child -> if (child.isDirectory) deleteDocumentTree(child) else child.delete() } }
    return folder.delete()
}
