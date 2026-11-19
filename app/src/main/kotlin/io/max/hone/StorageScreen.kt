@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StorageScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit) {
    var sizes by remember { mutableStateOf<List<AppSize>?>(null) }
    var parts by remember { mutableStateOf<Breakdown?>(null) }
    var disk by remember { mutableStateOf(hone.disk()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    suspend fun reload() {
        val list = runCatching { hone.appSizes() }.onFailure { say("Could not read app sizes: ${it.message}") }.getOrNull()
        if (list != null) {
            sizes = list
            parts = hone.breakdown(list)
            hone.appsBytes = list.sumOf { it.total }
        }
        disk = hone.disk()
    }
    // Without Shizuku, sizes need usage access granted by hand; check again whenever Hone comes back.
    var allowed by remember { mutableStateOf(hone.full || hone.usageAllowed()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            allowed = hone.full || hone.usageAllowed()
            if (allowed && sizes == null) reload()
        }
    }
    LaunchedEffect(result) { if (result != null) { delay(6000); result = null } }

    fun finish(text: String) {
        result = text
        say(text)
    }

    /** Clears [targets] one app at a time, then reports how much space came back. */
    fun clean(key: String, targets: List<AppSize>) = scope.launch {
        busy = key
        val before = hone.disk().free
        val r = runCatching { hone.clearCache(targets.map { it.pkg }) }
        reload()
        val freed = (disk.free - before).coerceAtLeast(0)
        busy = null
        r.onSuccess {
            finish(
                when {
                    it.error != null -> "Could not clear: ${it.error}"
                    it.stuck -> "Android's cache service stopped answering after ${it.cleared} apps. Restart the phone and try again."
                    else -> "Cleared ${it.cleared} apps, ${bytes(freed)} back"
                },
            )
        }.onFailure { finish("Could not clear: ${it.message}") }
    }

    fun deepClean() = scope.launch {
        busy = "all"
        val r = runCatching { hone.call<Freed>("trim-caches") }
        reload()
        busy = null
        r.onSuccess { finish("Deep clean freed ${bytes(it.freed)}") }.onFailure { finish("Deep clean failed: ${it.message}") }
    }

    val list = sizes
    val vols = remember(disk) { hone.volumes() }
    val totalAll = vols.sumOf { it.total }.coerceAtLeast(disk.total)
    val freeAll = vols.sumOf { it.free }.takeIf { vols.isNotEmpty() } ?: disk.free
    val sdUsed = vols.filter { it.removable }.sumOf { it.total - it.free }
    val legend = parts?.let {
        listOf(
            Triple("Photos", it.images, cs.primary),
            Triple("Videos", it.video, cs.tertiary),
            Triple("Audio", it.audio, cs.secondary),
            Triple("Apps", it.apps, cs.inversePrimary),
            Triple("Other", it.other, cs.outline),
            Triple("SD card", sdUsed, cs.secondaryContainer),
            Triple("Free", freeAll, cs.surfaceContainerHighest),
        )
    }

    FilesScreen(hone, pad, say, top = {
        item(key = "space") { SpaceWidget(hone, vols, disk, Modifier.enter(0)) }
        item(key = "sections") {
            val labels = listOf("Clean", "Files", "Apps")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                labels.forEachIndexed { i, label ->
                    ToggleButton(
                        checked = hone.storageSection == i,
                        onCheckedChange = { hone.storageSection = i },
                        modifier = Modifier.weight(1f),
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) { Text(label) }
                }
            }
        }
        if (hone.storageSection == 0) {
        item(key = "result") {
            AnimatedVisibility(result != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SectionCard(container = cs.secondaryContainer) {
                    Text(result.orEmpty(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (!allowed) item(key = "usage") {
            SectionCard(Modifier.enter(1)) {
                CardHeader(Sym.Person, "Allow usage access", "Android needs this before Hone can measure each app. Turn it on for Hone, then come back.", MaterialShapes.Clover4Leaf)
                Button(onClick = { hone.openUsageAccess() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) { Text("Open settings") }
            }
        }
        if (!hone.full) item(key = "needs") {
            NeedsShizuku(
                hone, "Cache cleaning needs Shizuku",
                "Android lets only the system clear other apps' cache. Without Shizuku, tap an app below and use Clear cache on its page.",
                Modifier.enter(2),
            )
        }
        if (hone.full) item(key = "cleaners") {
            val user = list?.filter { !it.system && it.cache > 0 && it.pkg != "io.max.hone" }.orEmpty()
            val system = list?.filter { it.system && it.cache > 0 }.orEmpty()
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min).enter(1), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Cleaner(
                    Modifier.weight(1f).fillMaxHeight(), Sym.Person, MaterialShapes.Clover4Leaf, "Your apps",
                    user.sumOf { it.cache }, list?.let { "${user.size} apps" } ?: "Measuring",
                    busy == "user", busy == null && user.isNotEmpty(),
                ) { clean("user", user) }
                Cleaner(
                    Modifier.weight(1f).fillMaxHeight(), Sym.Android, MaterialShapes.Cookie7Sided, "System apps",
                    system.sumOf { it.cache }, list?.let { "${system.size} apps" } ?: "Measuring",
                    busy == "system", busy == null && system.isNotEmpty(),
                ) { clean("system", system) }
            }
        }
        if (hone.full) item(key = "deep") {
            SectionCard(Modifier.enter(2)) {
                CardHeader(Sym.Cleaning, "Deep clean", "Asks Android to drop every cache it can, all at once.", MaterialShapes.Flower)
                Button(
                    onClick = { deepClean() }, enabled = busy == null, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (busy == "all") LoadingIndicator(Modifier.size(20.dp), color = cs.onPrimary) else Text("Run deep clean")
                }
            }
        }

        }
        if (hone.storageSection == 2) {
        item(key = "toggle") {
            Column(Modifier.enter(3), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Biggest apps")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    listOf("Your apps", "System apps").forEachIndexed { i, label ->
                        ToggleButton(
                            checked = group == i,
                            onCheckedChange = { group = i },
                            modifier = Modifier.weight(1f),
                            shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        ) { Text(label) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("App", cs.primary)
                    Pill("Data", cs.tertiary)
                    Pill("Cache", cs.error)
                }
            }
        }
        if (list == null && allowed) {
            item(key = "loading") { Loading(PaddingValues(32.dp), "Measuring every app") }
        } else if (list != null) {
            val shown = list.filter { it.system == (group == 1) }.take(60)
            val biggest = shown.firstOrNull()?.total?.coerceAtLeast(1) ?: 1
            itemsIndexed(shown, key = { _, a -> a.pkg }) { i, app ->
                AppSizeRow(hone, app, biggest, busy == null && hone.full, Modifier.animateItem().enter(i + 4)) { clean("one", listOf(app)) }
            }
        }
        }
    })
}

@Composable
private fun Cleaner(
    modifier: Modifier,
    icon: ImageVector,
    shape: RoundedPolygon,
    title: String,
    cache: Long,
    sub: String,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SectionCard(modifier, container = cs.surfaceContainerHigh) {
        Box(contentAlignment = Alignment.Center) {
            if (running) {
                MorphBlob(shape, MaterialShapes.Sunny, androidx.compose.ui.graphics.SolidColor(cs.secondaryContainer), Modifier.size(64.dp), periodMs = 900)
                Icon(icon, null, tint = cs.onSecondaryContainer)
            } else {
                ShapeIcon(icon, shape, cs.secondaryContainer, cs.onSecondaryContainer, 64.dp)
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            BigValue(bytes(cache), MaterialTheme.typography.headlineSmallEmphasized, color = cs.primary)
            Text("cache in $sub", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Clearing" else "Clear")
        }
    }
}

@Composable
private fun AppSizeRow(hone: Hone, app: AppSize, biggest: Long, canClear: Boolean, modifier: Modifier, onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    RowCard(modifier.clip(RoundedCornerShape(24.dp)).clickable { hone.openAppInfo(app.pkg) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(hone, app.pkg, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(bytes(app.total), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                }
                // One bar per app, split into code, data and cache, scaled to the biggest app.
                val frac = app.total.toFloat() / biggest
                Row(Modifier.fillMaxWidth(frac.coerceAtLeast(0.04f)).height(10.dp).clip(CircleShape)) {
                    val total = app.total.coerceAtLeast(1).toFloat()
                    Segment(app.app / total, cs.primary)
                    Segment((app.data - app.cache).coerceAtLeast(0) / total, cs.tertiary)
                    Segment(app.cache / total, cs.error)
                }
                Text(
                    "App ${bytes(app.app)}  ·  Data ${bytes(app.data - app.cache)}  ·  Cache ${bytes(app.cache)}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.cache > 0 && canClear) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onClear, enabled = canClear, shapes = IconButtonDefaults.shapes()) {
                    Icon(Sym.AutoDelete, "Clear cache of ${app.label}")
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(weight: Float, color: Color) {
    if (weight > 0.001f) Box(Modifier.weight(weight).fillMaxHeight().background(color))
}
