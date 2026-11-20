@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Page {
    data object Overview : Page
    data class Group(val id: String) : Page
    data class Browse(val path: String) : Page
    data object Trash : Page
}

@Composable
fun FilesScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit, top: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    var page by remember { mutableStateOf<Page>(Page.Overview) }
    var allowed by remember { mutableStateOf(hone.filesAllowed()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            hone.ensureFileAccess()
            allowed = hone.filesAllowed()
            if (allowed && hone.scan == null && !hone.scanning) hone.rescan()
        }
    }

    BackHandler(page != Page.Overview) {
        page = when (val p = page) {
            is Page.Browse -> p.path.substringBeforeLast('/').takeIf { p.path != ROOT && p.path.count { c -> c == '/' } > 2 }?.let { Page.Browse(it) } ?: Page.Overview
            else -> Page.Overview
        }
    }

    if (!allowed) {
        LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            top()
            item(key = "filesTitle") { SectionTitle("Files") }
            item {
                SectionCard(Modifier.enter(0)) {
                    CardHeader(Sym.SdCard, "Allow all files access", "Hone needs it to measure folders and move unwanted files to its trash. Turn it on for Hone, then come back.", MaterialShapes.Clover4Leaf)
                    Button(onClick = { hone.openFilesAccess() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) { Text("Open settings") }
                }
            }
            item {
                NeedsShizuku(hone, "Or connect Shizuku", "With Shizuku, Hone also sees leftovers in Android/data from removed apps.")
            }
        }
        return
    }

    when (val p = page) {
        Page.Overview -> Overview(hone, pad, say, top, open = { page = it })
        is Page.Group -> GroupPage(hone, pad, p.id, say) { page = Page.Overview }
        is Page.Browse -> BrowsePage(hone, pad, p.path, say, open = { page = Page.Browse(it) }, up = {
            page = if (p.path == ROOT || p.path.count { it == '/' } <= 2) Page.Overview else Page.Browse(p.path.substringBeforeLast('/'))
        })
        Page.Trash -> TrashPage(hone, pad, say) { page = Page.Overview }
    }
}

const val ROOT = "/storage/emulated/0"

@Composable
private fun Overview(hone: Hone, pad: PaddingValues, say: (String) -> Unit, top: androidx.compose.foundation.lazy.LazyListScope.() -> Unit, open: (Page) -> Unit) {
    val scan = hone.scan
    val cs = MaterialTheme.colorScheme
    val colors = categoryColors()
    val chosen = remember { mutableStateListOf<String>() }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        top()
        if (hone.storageSection != 2 && !hone.canReadFiles()) item(key = "filesAccess") {
            SectionCard(Modifier.enter(1)) {
                CardHeader(Sym.SdCard, "Allow all files access", "Lets Hone see every file, show previews and move files to its trash and back, also without Shizuku.", MaterialShapes.Clover4Leaf)
                Button(onClick = { hone.openFilesAccess() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) { Text("Open settings") }
            }
        }
        if (hone.storageSection == 0) {
            if (scan != null) {
                item(key = "cleanTitle") { SectionTitle("Cleanup") }
                if (hone.similar == null && hone.canReadFiles()) item(key = "similar") { SimilarCard(hone) }
                hone.groups().forEachIndexed { i, g ->
                    item(key = "g:" + g.id) {
                        RowCard(Modifier.enter(i + 3).clickable(enabled = g.count > 0) { open(Page.Group(g.id)) }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                ShapeIcon(groupIcon(g.id), MaterialShapes.Cookie9Sided, size = 44.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(g.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (g.count == 0) "Nothing found" else "${g.count} ${if (g.id == "empty" || g.id == "leftovers") "folder" else "file"}${if (g.count == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                }
                                if (g.count > 0) {
                                    Text(bytes(g.bytes), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                                    Icon(Sym.ChevronRight, null, tint = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item(key = "trash") { TrashRow(scan.trash) { open(Page.Trash) } }
            }
        }
        if (hone.storageSection == 1) {
            item(key = "browse") {
                SectionCard(Modifier.enter(2)) {
                    CardHeader(Sym.Folder, "Browse", "Sorted by size. Removing goes to Hone's trash.", MaterialShapes.Cookie9Sided)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { open(Page.Browse(ROOT)) }, shapes = ButtonDefaults.shapes()) { Text("Internal storage") }
                        hone.volumes().firstOrNull { it.removable }?.let { sd ->
                            FilledTonalButton(onClick = { open(Page.Browse(sd.path)) }, shapes = ButtonDefaults.shapes()) { Text("SD card") }
                        }
                    }
                }
            }
            if (scan != null) {
                item(key = "trash") { TrashRow(scan.trash) { open(Page.Trash) } }
                item(key = "foldersTitle") { SectionTitle("Biggest folders") }
                item(key = "folders") {
                    SectionCard(Modifier.enter(3)) {
                        val top = scan.folders.firstOrNull()?.bytes?.coerceAtLeast(1) ?: 1
                        scan.folders.take(12).forEach { f ->
                            // Rounded, not pill-clipped: a pill corner cut into the first letter and the end dots.
                            Column(
                                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .clickable { open(Page.Browse(f.path)) }.padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.path.removePrefix("$ROOT/"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    Text(bytes(f.bytes), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                                }
                                GradientBar(f.bytes.toFloat() / top, listOf(cs.tertiary, cs.primary), cs.surfaceContainerHighest, height = 6.dp)
                            }
                        }
                    }
                }
                item(key = "largestTitle") { SectionTitle("Biggest files") }
                items(scan.largest.take(15), key = { "l:" + it.path }) { f ->
                    FileRow(
                        f.path, f.bytes, f.modified, selected = f.path in chosen,
                        onToggle = { if (f.path in chosen) chosen.remove(f.path) else chosen.add(f.path) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
    TrashBar(hone, chosen, scan?.largest?.filter { it.path in chosen }?.sumOf { it.bytes } ?: 0, say, Modifier.align(Alignment.BottomCenter).padding(pad))
    }
}

/** Offers the near-identical photo search; it reads every camera photo once, so it runs on request. */
@Composable
private fun SimilarCard(hone: Hone) {
    val cs = MaterialTheme.colorScheme
    RowCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(Sym.Image, MaterialShapes.Cookie9Sided, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Similar photos", style = MaterialTheme.typography.titleMedium)
                    Text("Bursts and retakes of the same shot in your camera and pictures folders.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            val p = hone.similarProgress
            if (p != null) {
                Text("Comparing photos  ·  ${(p * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            FilledTonalButton(onClick = { hone.findSimilar() }, enabled = p == null, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                Text(if (p == null) "Find" else "Looking")
            }
        }
    }
}

@Composable
private fun TrashRow(size: Long, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    RowCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(Sym.Delete, MaterialShapes.Arch, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Hone trash", style = MaterialTheme.typography.titleMedium)
                Text("Removed files wait here until you empty it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(bytes(size), style = MaterialTheme.typography.titleSmall, color = cs.primary)
            Icon(Sym.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupPage(hone: Hone, pad: PaddingValues, id: String, say: (String) -> Unit, back: () -> Unit) {
    val group = hone.groups().firstOrNull { it.id == id } ?: return back()
    val chosen = remember(id) { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val chosenBytes = group.files.filter { it.path in chosen }.sumOf { it.bytes }
    val sections = remember(group) {
        group.files.groupBy { sectionOf(id, it.path) }.toList().sortedByDescending { (_, f) -> f.sumOf { it.bytes } }
    }
    // One section opens by itself; several start folded so the kinds are visible at a glance.
    val expanded = remember(id) { mutableStateListOf<String>().apply { if (sections.size == 1) add(sections.first().first) } }


    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "head") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = back) { Icon(Sym.ArrowBack, "Back") }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(group.title, style = MaterialTheme.typography.titleLarge)
                            Text("${group.count} found, ${bytes(group.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(group.detail, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { chosen.clear(); chosen.addAll(group.files.map { it.path }) }, shapes = ButtonDefaults.shapes()) { Text("Select all") }
                        TextButton(onClick = { chosen.clear() }, shapes = ButtonDefaults.shapes()) { Text("Clear") }
                    }
                    if (group.count > group.files.size) Text("Showing the ${group.files.size} biggest.", style = MaterialTheme.typography.labelMedium)
                }
            }
            sections.forEach { (title, files) ->
                val open = title in expanded
                item(key = "s:$title") {
                    SectionHeader(
                        title, files.size, files.sumOf { it.bytes }, open,
                        picked = files.count { it.path in chosen },
                        onOpen = { if (open) expanded.remove(title) else expanded.add(title) },
                        onPick = { all ->
                            files.forEach { chosen.remove(it.path) }
                            if (all) chosen.addAll(files.map { it.path })
                        },
                    )
                }
                if (open) items(files, key = { it.path }) { f ->
                    FileRow(
                        f.path, f.bytes, f.modified, dir = f.path.endsWith("/.thumbnails") || id == "empty" || id == "leftovers",
                        selected = f.path in chosen, onToggle = { if (f.path in chosen) chosen.remove(f.path) else chosen.add(f.path) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        TrashBar(hone, chosen, chosenBytes, say, Modifier.align(Alignment.BottomCenter).padding(pad))
    }
}

@Composable
private fun BrowsePage(hone: Hone, pad: PaddingValues, path: String, say: (String) -> Unit, open: (String) -> Unit, up: () -> Unit) {
    var listing by remember(path) { mutableStateOf<Listing?>(null) }
    val chosen = remember(path) { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    suspend fun load() { listing = runCatching { hone.call<Listing>("ls", path) }.getOrElse { Listing(path, error = it.message) } }
    LaunchedEffect(path) { load() }
    val entries = listing?.entries.orEmpty()
    val chosenBytes = entries.filter { it.path in chosen }.sumOf { it.bytes }


    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "head") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = up) { Icon(Sym.ArrowBack, "Up") }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (path == ROOT) "Internal storage" else if (path.count { it == '/' } == 2) "SD card" else path.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path.removePrefix(ROOT).ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (listing != null) Text(bytes(entries.sumOf { it.bytes }), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            when {
                listing == null -> item { Loading(PaddingValues(32.dp), "Measuring") }
                entries.isEmpty() -> item { Text(listing?.error ?: "Empty folder", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries, key = { it.path }) { e ->
                FileRow(
                    e.path, e.bytes, e.modified, dir = e.dir, items = e.items, locked = e.locked,
                    selected = if (e.locked) null else e.path in chosen,
                    onToggle = if (e.locked) null else ({ if (e.path in chosen) chosen.remove(e.path) else chosen.add(e.path) }),
                    onClick = if (e.dir) ({ open(e.path) }) else null,
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        TrashBar(hone, chosen, chosenBytes, say, Modifier.align(Alignment.BottomCenter).padding(pad)) { load() }
    }
}

@Composable
private fun TrashPage(hone: Hone, pad: PaddingValues, say: (String) -> Unit, back: () -> Unit) {
    var list by remember { mutableStateOf<TrashList?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    var askEmpty by remember { mutableStateOf(false) }
    var emptying by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() { list = runCatching { hone.call<TrashList>("trash-list") }.getOrElse { TrashList() } }
    LaunchedEffect(Unit) { load() }
    val cs = MaterialTheme.colorScheme

    if (askEmpty) Confirm(
        "Empty the trash?", "${bytes(list?.bytes ?: 0)} is deleted for good. This cannot be undone.", "Delete for good",
        onConfirm = {
            scope.launch {
                val ids = list?.items?.map { it.id }.orEmpty()
                var freed = 0L
                emptying = 0f
                runCatching {
                    ids.chunked(20).forEachIndexed { i, batch ->
                        freed += hone.call<Freed>("trash-purge", *batch.toTypedArray()).freed
                        emptying = ((i + 1) * 20f / ids.size).coerceAtMost(1f)
                    }
                    hone.call<Freed>("empty-trash") // sweeps anything left without a manifest
                }.onSuccess { say("Freed ${bytes(freed)}") }.onFailure { say("Could not empty: ${it.message}") }
                emptying = null
                load()
                hone.rescan()
            }
        },
        onDismiss = { askEmpty = false },
    )

    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Sym.ArrowBack, "Back") }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Hone trash", style = MaterialTheme.typography.titleLarge)
                        Text("${list?.items?.size ?: 0} items, ${bytes(list?.bytes ?: 0)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Text("Files here still use space. Restore anything you need, then empty the trash to free it.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowLabel("Auto-empty")
                        Text("Delete items for good once they have been here 30 days.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Switch(checked = hone.autoEmpty, onCheckedChange = { on ->
                        hone.updateAutoEmpty(on)
                        if (on) scope.launch {
                            runCatching { hone.expireTrash() }.onSuccess { if (it.freed > 0) say("Deleted ${bytes(it.freed)} older than 30 days") }
                            load()
                        }
                    })
                }
                emptying?.let { p ->
                    Text("Deleting  ·  ${(p * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.material3.LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = cs.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                runCatching { hone.call<Restored>("trash-restore", *chosen.toTypedArray()) }
                                    .onSuccess { say("Restored ${it.restored}") }.onFailure { say("Could not restore: ${it.message}") }
                                chosen.clear()
                                load()
                                hone.rescan()
                            }
                        },
                        enabled = chosen.isNotEmpty() && emptying == null, shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Sym.Restore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restore ${chosen.size}")
                    }
                    Button(
                        onClick = { askEmpty = true }, enabled = (list?.items?.size ?: 0) > 0 && emptying == null, shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                    ) { Text(if (emptying != null) "Emptying" else "Empty trash") }
                }
            }
        }
        if (list?.items?.isEmpty() == true) item { Text("The trash is empty.", Modifier.padding(24.dp), color = cs.onSurfaceVariant) }
        items(list?.items.orEmpty(), key = { it.id }) { t ->
            FileRow(t.original, t.bytes, t.trashed, selected = t.id in chosen, onToggle = { if (t.id in chosen) chosen.remove(t.id) else chosen.add(t.id) })
        }
    }
}

/** Bottom bar for a selection: confirm, then a progress bar while files move to the trash. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrashBar(
    hone: Hone,
    chosen: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    size: Long,
    say: (String) -> Unit,
    modifier: Modifier,
    after: suspend () -> Unit = {},
) {
    var confirm by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    if (confirm) Confirm(
        "Move ${chosen.size} to the trash?",
        "${bytes(size)} goes to Hone's trash. You can restore it until you empty the trash.",
        "Move to trash",
        onConfirm = {
            scope.launch {
                val paths = chosen.toList()
                progress = 0f
                val r = runCatching { hone.trash(paths) { progress = it.coerceAtMost(1f) } }
                progress = null
                r.onSuccess {
                    say(it.summary())
                    chosen.removeAll(paths)
                    after()
                }.onFailure { say("Could not move: ${it.message}") }
            }
        },
        onDismiss = { confirm = false },
    )
    AnimatedVisibility(chosen.isNotEmpty() || progress != null, modifier, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = cs.inverseSurface, contentColor = cs.inverseOnSurface, modifier = Modifier.padding(16.dp)) {
            val p = progress
            if (p != null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Moving to trash  ·  ${(p * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.material3.LinearWavyProgressIndicator(
                        progress = { p }, modifier = Modifier.fillMaxWidth(),
                        color = cs.error, trackColor = cs.inverseOnSurface.copy(alpha = 0.2f),
                    )
                }
            } else {
                Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${chosen.size} selected  ·  ${bytes(size)}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = { confirm = true }, shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                    ) { Text("Move to trash") }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    path: String,
    size: Long,
    modified: Long,
    dir: Boolean = false,
    items: Long = 0,
    locked: Boolean = false,
    selected: Boolean?,
    onToggle: (() -> Unit)?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val name = path.substringAfterLast('/')
    var preview by remember { mutableStateOf(false) }
    if (preview) PreviewDialog(path, size, modified.takeIf { it > 0 }?.let { date(it) }) { preview = false }
    val click: () -> Unit = onClick ?: { preview = true }
    RowCard(modifier.clickable { if (dir && onClick == null) onToggle?.invoke() else click() }) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dir) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(Sym.Folder, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
                }
            } else {
                FileThumb(path, fileIcon(name), 44.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        bytes(size),
                        if (dir) "$items files" else null,
                        modified.takeIf { it > 0 }?.let { date(it) },
                        path.substringBeforeLast('/').removePrefix(ROOT).removePrefix("/").takeIf { !dir && it.isNotEmpty() },
                    ).joinToString("  ·  "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            when {
                locked -> Icon(Sym.Lock, "Protected", Modifier.padding(12.dp).size(18.dp), tint = cs.outline)
                selected != null && onToggle != null -> Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
        }
    }
}

/** Fold-out header for one kind of file inside a cleanup group. */
@Composable
private fun SectionHeader(title: String, count: Int, size: Long, open: Boolean, picked: Int, onOpen: () -> Unit, onPick: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "fold")
    RowCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Sym.ChevronRight, if (open) "Fold" else "Unfold",
                Modifier.size(24.dp).graphicsLayer { rotationZ = turn }, tint = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text("$count  ·  ${bytes(size)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            androidx.compose.material3.TriStateCheckbox(
                state = when (picked) {
                    0 -> androidx.compose.ui.state.ToggleableState.Off
                    count -> androidx.compose.ui.state.ToggleableState.On
                    else -> androidx.compose.ui.state.ToggleableState.Indeterminate
                },
                onClick = { onPick(picked < count) },
            )
        }
    }
}

/** Slices as one rounded bar; Mono draws it as dots. */
@Composable
private fun CategoryBar(slices: List<Pair<Float, Color>>) {
    val total = slices.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
    if (LocalLook.current.mono) {
        val dots = 32
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val colorAt = List(dots) { i ->
                val at = (i + 0.5f) / dots * total
                var sum = 0f
                slices.firstOrNull { sum += it.first; at <= sum }?.second ?: slices.lastOrNull()?.second ?: Color.Gray
            }
            colorAt.forEach { c -> Box(Modifier.size(8.dp).clip(CircleShape).background(c)) }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
        slices.filter { it.first > 0 }.forEach { (v, c) -> Box(Modifier.weight(v / total).fillMaxHeight().background(c)) }
    }
}

@Composable
private fun categoryColors(): Map<String, Color> {
    val cs = MaterialTheme.colorScheme
    return mapOf(
        "Images" to cs.primary, "Videos" to cs.tertiary, "Audio" to cs.secondary, "Documents" to cs.inversePrimary,
        "APKs" to cs.error, "Archives" to cs.outline, "Other" to cs.surfaceContainerHighest,
    )
}

private fun groupIcon(id: String): ImageVector = when (id) {
    "apks" -> Sym.Android
    "wabackups" -> Sym.Restore
    "dupes" -> Sym.Copy
    "leftovers" -> Sym.Widgets
    "temp" -> Sym.Schedule
    "large" -> Sym.SdCard
    "whatsapp", "telegram" -> Sym.Share
    "screenshots", "similar" -> Sym.Image
    else -> Sym.FolderOff
}

private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "heic", "gif", "dng" -> Sym.Image
    "mp4", "mkv", "mov", "webm", "3gp" -> Sym.VideoFile
    "mp3", "m4a", "flac", "wav", "ogg", "opus" -> Sym.AudioFile
    "pdf", "doc", "docx", "txt", "xls", "xlsx" -> Sym.Description
    "zip", "rar", "7z", "tar", "gz" -> Sym.Archive
    "apk", "apks", "xapk" -> Sym.Android
    else -> Sym.File
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun date(seconds: Long) = dateFormat.format(Date(seconds * 1000))
