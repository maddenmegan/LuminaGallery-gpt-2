package com.lumina.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.data.MediaAsset
import com.lumina.gallery.MainTab
import com.lumina.gallery.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Panel = Color(0xFF121218)
private val Muted = Color(0xFF9894A5)

private data class AlbumGroup(val key: String, val title: String, val assets: List<MediaAsset>)

@Composable
fun AlbumsScreen(
    media: List<MediaAsset>, trashCount: Int, loading: Boolean, permissionGranted: Boolean,
    searchQuery: String, onSearchChange: (String) -> Unit, onRequestPermission: () -> Unit,
    filter: String, onFilterChange: (String) -> Unit,
    sortMode: SortMode, onSortChange: (SortMode) -> Unit,
    ascending: Boolean, onAscendingChange: (Boolean) -> Unit,
    timeline: Boolean, onTimelineChange: (Boolean) -> Unit,
    columns: Int, onColumnsChange: (Int) -> Unit,
    showHidden: Boolean, onShowHiddenChange: (Boolean) -> Unit,
    favorites: Set<Long>, pinnedFolders: Set<String>, hiddenFolders: Set<String>, selectedIds: Set<Long>,
    onAssetClick: (MediaAsset) -> Unit, onAssetLongClick: (MediaAsset) -> Unit, onOpenFolder: (String) -> Unit, onOpenTrash: () -> Unit, onOpenAsset: (MediaAsset) -> Unit,
    onToggleFavorite: (MediaAsset) -> Unit, onPinFolder: (String) -> Unit, onHideFolder: (String) -> Unit,
    onQr: () -> Unit, onCamera: () -> Unit, onCreateFolder: () -> Unit, onGeminiKey: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var columnMenuOpen by remember { mutableStateOf(false) }
    var viewMenuOpen by remember { mutableStateOf(false) }
    val filters = listOf("All Media", "Photos", "Videos", "GIFs", "Screenshots", "Favorites")

    Column(Modifier.fillMaxSize().background(Color(0xFF050506))) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LUMINA", color = Color(0xFFB7A1FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Your moments, in focus.", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onQr) { Icon(Icons.Outlined.QrCodeScanner, "Live QR scanner", tint = Color.White) }
                IconButton(onClick = onCamera) { Icon(Icons.Outlined.CameraAlt, "Camera", tint = Color.White) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options", tint = Color.White) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Panel) {
                        DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden albums" else "Temporarily show hidden") }, onClick = { onShowHiddenChange(!showHidden); menuOpen = false }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
                        DropdownMenuItem(text = { Text("View type") }, onClick = { viewMenuOpen = true; menuOpen = false }, leadingIcon = { Icon(Icons.Outlined.ViewModule, null) })
                        DropdownMenuItem(text = { Text("Column count") }, onClick = { columnMenuOpen = true; menuOpen = false }, leadingIcon = { Icon(Icons.Outlined.GridView, null) })
                        DropdownMenuItem(text = { Text("Create folder") }, onClick = { onCreateFolder(); menuOpen = false }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                        DropdownMenuItem(text = { Text("AI OCR Language Key") }, onClick = { onGeminiKey(); menuOpen = false }, leadingIcon = { Icon(Icons.Outlined.Key, null) })
                    }
                    DropdownMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }, containerColor = Panel) {
                        DropdownMenuItem(text = { Text("Grid view") }, onClick = { onColumnsChange(3); viewMenuOpen = false })
                        DropdownMenuItem(text = { Text("Compact list") }, onClick = { onColumnsChange(1); viewMenuOpen = false })
                    }
                    DropdownMenu(expanded = columnMenuOpen, onDismissRequest = { columnMenuOpen = false }, containerColor = Panel) {
                        (2..5).forEach { count -> DropdownMenuItem(text = { Text("$count columns") }, onClick = { onColumnsChange(count); columnMenuOpen = false }) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = Muted)
                Spacer(Modifier.width(9.dp))
                androidx.compose.material3.TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search folders", color = Muted) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFB7A1FF)
                    )
                )
                IconButton(onClick = onQr) { Icon(Icons.Outlined.QrCodeScanner, "Scan QR", tint = Color(0xFFB7A1FF)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onTimelineChange(!timeline) }) {
                    Icon(if (timeline) Icons.Outlined.FolderOpen else Icons.Outlined.Timeline, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (timeline) "Albums" else "Timeline")
                }
                Box {
                    TextButton(onClick = { sortOpen = true }) { Icon(Icons.Outlined.Sort, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Sort by") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }, containerColor = Panel) {
                        listOf("Last modified", "Name", "Path", "Size", "Date taken", "Random").forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { onSortChange(when (label) { "Name" -> SortMode.Name; "Path" -> SortMode.Path; "Size" -> SortMode.Size; "Date taken" -> SortMode.Taken; "Random" -> SortMode.Random; else -> SortMode.Modified }); sortOpen = false })
                        }
                        HorizontalDivider(color = Color(0xFF34313D))
                        DropdownMenuItem(text = { Text(if (ascending) "Ascending ✓" else "Ascending") }, onClick = { onAscendingChange(true); sortOpen = false })
                        DropdownMenuItem(text = { Text(if (!ascending) "Descending ✓" else "Descending") }, onClick = { onAscendingChange(false); sortOpen = false })
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("${media.size} items", color = Muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { value ->
                    FilterChip(selected = filter == value, onClick = { onFilterChange(value) }, label = { Text(value, fontSize = 12.sp) })
                }
            }
        }

        if (!permissionGranted) {
            EmptyState("Media access needed", "Allow access to photos and videos to build your local library.", "Grant access", onRequestPermission)
        } else if (loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFB7A1FF)) }
        } else if (timeline) {
            val filtered = filterAssets(media, filter, favorites, searchQuery)
            val sorted = remember(filtered, sortMode, ascending) { sortAssets(filtered, sortMode.name, ascending) }
            MediaGrid(
                assets = sorted, columns = columns,
                selectedIds = selectedIds, onClick = onAssetClick, onLongClick = onAssetLongClick, onFavorite = onToggleFavorite,
                favorites = favorites, timeline = true, modifier = Modifier.weight(1f)
            )
        } else {
            val groups = media.groupBy { it.relativePath.trimEnd('/').ifBlank { "Internal storage" } }
                .map { (path, assets) -> AlbumGroup(path, path.substringAfterLast('/'), filterAssets(assets, filter, favorites, "")) }
                .filter { it.assets.isNotEmpty() }
                .filter { showHidden || it.key !in hiddenFolders }
                .filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.key.contains(searchQuery, true) }
                .sortedWith(compareByDescending<AlbumGroup> { it.key in pinnedFolders }.thenBy { it.title.lowercase() })
            val orderedGroups = remember(groups, sortMode, ascending) {
                val sortedGroups = when (sortMode) {
                    SortMode.Name -> groups.sortedBy { it.title.lowercase() }
                    SortMode.Path -> groups.sortedBy { it.key.lowercase() }
                    SortMode.Size -> groups.sortedBy { it.assets.sumOf(MediaAsset::size) }
                    SortMode.Taken -> groups.sortedBy { group -> group.assets.maxOfOrNull { it.dateTakenMillis } ?: 0L }
                    SortMode.Random -> groups.shuffled()
                    else -> groups.sortedBy { group -> group.assets.maxOfOrNull { it.modifiedSeconds } ?: 0L }
                }
                val direction = if (ascending) sortedGroups else sortedGroups.reversed()
                direction.sortedByDescending { it.key in pinnedFolders }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns.coerceIn(1, 5)),
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (trashCount > 0) {
                    item(key = "__recycle_bin") { RecycleBinCard(count = trashCount, onClick = onOpenTrash) }
                }
                items(orderedGroups, key = { it.key }) { group ->
                    AlbumCard(
                        group = group,
                        pinned = group.key in pinnedFolders,
                        onClick = { onOpenFolder(group.key) },
                        onPin = { onPinFolder(group.key) },
                        onHide = { onHideFolder(group.key) }
                    )
                }
            }
        }
    }
}

private fun filterAssets(assets: List<MediaAsset>, filter: String, favorites: Set<Long>, query: String): List<MediaAsset> = assets.filter { asset ->
    val byType = when (filter) {
        "Photos" -> asset.isImage && !asset.isGif
        "Videos" -> asset.isVideo
        "GIFs" -> asset.isGif
        "Screenshots" -> asset.isScreenshot
        "Favorites" -> asset.id in favorites
        else -> asset.isImage || asset.isVideo || asset.isPdf
    }
    byType && (query.isBlank() || asset.name.contains(query, true) || asset.relativePath.contains(query, true))
}

private fun sortAssets(assets: List<MediaAsset>, mode: String, ascending: Boolean): List<MediaAsset> {
    val sorted = when (mode) {
        "Name" -> assets.sortedBy { it.name.lowercase() }
        "Path" -> assets.sortedBy { it.relativePath.lowercase() }
        "Size" -> assets.sortedBy { it.size }
        "Taken" -> assets.sortedBy { it.dateTakenMillis.takeIf { value -> value > 0L } ?: (it.modifiedSeconds * 1000L) }
        "Random" -> assets.shuffled()
        else -> assets.sortedWith(compareBy<MediaAsset> { it.modifiedSeconds }.thenBy { it.id })
    }
    return if (ascending) sorted else sorted.reversed()
}

@Composable
private fun RecycleBinCard(count: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().height(166.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF211A29))) {
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            Icon(Icons.Outlined.DeleteSweep, null, tint = Color(0xFFE3C2FF), modifier = Modifier.size(27.dp).align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart)) {
                Text("Recycle Bin", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("$count trashed items", color = Color(0xFFC2B6CD), fontSize = 11.sp)
            }
            Text("↗", modifier = Modifier.align(Alignment.TopEnd), color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(group: AlbumGroup, pinned: Boolean, onClick: () -> Unit, onPin: () -> Unit, onHide: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().height(190.dp).combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Box(Modifier.fillMaxSize()) {
            MediaThumbnail(group.assets.first(), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.43f)))
            Column(Modifier.align(Alignment.BottomStart).padding(13.dp)) {
                Text(group.title.ifBlank { "Internal storage" }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.assets.size} items", color = Color(0xFFD0CDDA), fontSize = 11.sp)
            }
            if (pinned) Icon(Icons.Default.PushPin, null, tint = Color(0xFFB7A1FF), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(17.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Panel) {
                DropdownMenuItem(text = { Text(if (pinned) "Unpin album" else "Pin to Top") }, onClick = { onPin(); menu = false }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) })
                DropdownMenuItem(text = { Text("Hide Album") }, onClick = { onHide(); menu = false }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
            }
        }
    }
}

@Composable
fun FolderMediaScreen(
    title: String, assets: List<MediaAsset>, columns: Int, selectedIds: Set<Long>,
    onAssetClick: (MediaAsset) -> Unit, onAssetLongClick: (MediaAsset) -> Unit, onBack: () -> Unit,
    onShare: (List<MediaAsset>) -> Unit, onTrash: (List<MediaAsset>) -> Unit,
    onRestore: () -> Unit, onSelectAll: () -> Unit, isRecycleBin: Boolean = false
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF050506))) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${assets.size} items", color = Muted, fontSize = 11.sp)
            }
            if (selectedIds.isNotEmpty()) {
                IconButton(onClick = { onShare(assets.filter { it.id in selectedIds }) }) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
                IconButton(onClick = { onTrash(assets.filter { it.id in selectedIds }) }) { Icon(Icons.Outlined.Delete, "Delete", tint = Color.White) }
            }
            TextButton(onClick = if (isRecycleBin) onRestore else onSelectAll) {
                Text(if (isRecycleBin) "Restore" else "Select all", color = Color(0xFFB7A1FF))
            }
        }
        if (assets.isEmpty()) EmptyState("Nothing here", "This space is empty right now.", null) {}
        else MediaGrid(
            assets = assets, columns = columns, selectedIds = selectedIds, onClick = onAssetClick,
            onLongClick = onAssetLongClick, onFavorite = {}, favorites = emptySet(), timeline = false,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
    }
}

@Composable
fun MediaGrid(
    assets: List<MediaAsset>, columns: Int, selectedIds: Set<Long>, onClick: (MediaAsset) -> Unit,
    onLongClick: (MediaAsset) -> Unit, onFavorite: (MediaAsset) -> Unit, favorites: Set<Long>,
    timeline: Boolean, modifier: Modifier = Modifier
) {
    val display = remember(assets, timeline) { assets }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(1, 5)),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (!timeline) {
            items(display, key = { it.id }) { asset ->
                MediaTile(asset, selected = asset.id in selectedIds, favorite = asset.id in favorites, onClick = { onClick(asset) }, onLongClick = { onLongClick(asset) }, onFavorite = { onFavorite(asset) })
            }
        } else {
            val groups = display.groupBy { SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date(it.modifiedSeconds * 1000)) }
            groups.forEach { (day, dayAssets) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "day:$day") {
                    Text(day, color = Color(0xFFB7A1FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
                }
                items(dayAssets, key = { it.id }) { asset ->
                    MediaTile(asset, selected = asset.id in selectedIds, favorite = asset.id in favorites, onClick = { onClick(asset) }, onLongClick = { onLongClick(asset) }, onFavorite = { onFavorite(asset) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(asset: MediaAsset, selected: Boolean, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onFavorite: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Panel)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        MediaThumbnail(asset, Modifier.fillMaxSize())
        if (asset.isVideo || asset.isPdf) {
            Box(Modifier.align(Alignment.BottomStart).padding(7.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 7.dp, vertical = 4.dp)) {
                Icon(if (asset.isVideo) Icons.Default.PlayArrow else Icons.Default.PictureAsPdf, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (favorite) Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF7EA5), modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(17.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x667B61C7)))
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFFB7A1FF), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
fun MediaThumbnail(asset: MediaAsset, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = asset.uri) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, asset) }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = asset.name, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color(0xFF25242D)), contentAlignment = Alignment.Center) {
        Icon(if (asset.isVideo) Icons.Outlined.VideoLibrary else if (asset.isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Image, null, tint = Color(0xFF9C96AB), modifier = Modifier.size(30.dp))
    }
}

private fun decodeThumbnail(context: Context, asset: MediaAsset): Bitmap? = runCatching {
    if (asset.isPdf) return null
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(asset.uri, android.util.Size(400, 400), null)
    } else if (asset.isVideo) {
        MediaMetadataRetriever().let { retriever ->
            try { retriever.setDataSource(context, asset.uri); retriever.getFrameAtTime(0) }
            finally { retriever.release() }
        }
    } else {
        context.contentResolver.openInputStream(asset.uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeStream(stream, null, options)
        }
    }
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomNavigationBar(tab: MainTab, onSelect: (MainTab) -> Unit) {
    val items = listOf(
        MainTab.Albums to Icons.Outlined.PhotoLibrary,
        MainTab.Find to Icons.Outlined.AutoAwesome,
        MainTab.People to Icons.Outlined.Face,
        MainTab.Clean to Icons.Outlined.CleaningServices,
        MainTab.Vault to Icons.Outlined.Lock
    )
    Row(Modifier.fillMaxWidth().background(Color(0xFF101014)).padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceAround) {
        items.forEach { (item, icon) ->
            val selected = item == tab
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { onSelect(item) }).padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, tint = if (selected) Color(0xFFB7A1FF) else Muted, modifier = Modifier.size(20.dp))
                Text(item.label, color = if (selected) Color(0xFFB7A1FF) else Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun SelectionActionBar(
    count: Int, allCount: Int, onSelectAll: () -> Unit, onShare: () -> Unit,
    onTrash: () -> Unit, onDelete: () -> Unit, onMove: () -> Unit, onCopy: () -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier
) {
    Surface(color = Color(0xFF211B2B), shadowElevation = 10.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Clear selection", tint = Color.White) }
            Text("$count selected", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onSelectAll) { Text("Select all", fontSize = 11.sp) }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "Copy", tint = Color.White) }
            IconButton(onClick = onMove) { Icon(Icons.Outlined.DriveFileMove, "Move", tint = Color.White) }
            IconButton(onClick = onTrash) { Icon(Icons.Outlined.Delete, "Trash", tint = Color.White) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteForever, "Delete permanently", tint = Color(0xFFFF8B8B)) }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Collections, null, tint = Color(0xFF827A98), modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(description, color = Muted, fontSize = 12.sp)
        if (action != null) { Spacer(Modifier.height(14.dp)); Button(onClick = onAction) { Text(action) } }
    }
}
