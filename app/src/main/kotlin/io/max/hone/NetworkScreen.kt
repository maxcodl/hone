@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val periods = listOf("Day", "Week", "Month")
private val networks = listOf("All", "Wi-Fi", "Mobile")

@Composable
fun NetworkScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(hone.usageAllowed()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!hone.usageAllowed() && hone.full) runCatching { hone.raw("grant-usage", context.packageName) }
            allowed = hone.usageAllowed()
        }
    }
    var period by rememberSaveable { mutableIntStateOf(2) }
    var net by rememberSaveable { mutableIntStateOf(0) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    var picked by remember(period, back, net) { mutableStateOf<NetBar?>(null) }
    var open by remember { mutableStateOf<NetApp?>(null) }

    val bars by produceState<List<NetBar>?>(null, allowed, period, back, net) {
        value = null
        if (allowed) value = Net.bars(context, period, back, net)
    }
    val (from, to) = picked?.let { it.start to it.end } ?: Net.range(period, back)
    val apps by produceState<List<NetApp>?>(null, allowed, from, to, net) {
        value = null
        if (allowed) value = runCatching { Net.apps(context, hone, from, to, net) }.getOrDefault(emptyList())
    }

    // GlassWire-style live tags: every few seconds, the apps that moved data since the last look.
    val live = remember { mutableStateListOf<LiveTag>() }
    LaunchedEffect(lifecycle, allowed, net) {
        if (!allowed) return@LaunchedEffect
        live.clear()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Per-app records come in two-hour blocks; asking for whole blocks keeps the totals growing evenly.
            val block = 2 * 3_600_000L
            val start = (System.currentTimeMillis() - block) / block * block
            val pm = context.packageManager
            var before: Map<Int, Traffic>? = null
            var id = System.nanoTime()
            while (true) {
                if (hone.full) runCatching { hone.raw("net-poll") }
                val now = withContext(Dispatchers.IO) { Net.byUid(context, start, System.currentTimeMillis() + block, net) }
                val born = System.currentTimeMillis()
                before?.let { old ->
                    now.entries
                        .filter { it.key >= android.os.Process.FIRST_APPLICATION_UID }
                        .map { (uid, t) -> Triple(uid, t.rx - (old[uid]?.rx ?: 0), t.tx - (old[uid]?.tx ?: 0)) }
                        .filter { it.second + it.third > 0 }
                        .sortedByDescending { it.second + it.third }
                        .take(4)
                        .forEachIndexed { i, (uid, rx, tx) -> pm.getPackagesForUid(uid)?.singleOrNull()?.let { live.add(0, LiveTag(id++, it, rx, tx, born + i * 700L, if (hone.full) 3 else 15)) } }
                }
                before = now
                while (live.size > 16) live.removeAt(live.lastIndex)
                live.removeAll { born - it.born > RISE_SECONDS * 1000 }
                delay(if (hone.full) 3000 else 15000)
            }
        }
    }

    open?.let { app -> NetAppSheet(hone, app, say) { open = null } }

    if (allowed && hone.netLive) {
        androidx.activity.compose.BackHandler { hone.netLive = false }
        LivePage(hone, pad, live)
        return
    }

    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!allowed) {
            item(key = "usage") {
                SectionCard(Modifier.enter(0)) {
                    CardHeader(Sym.Network, "Allow usage access", "Android keeps a record of what each app sends and receives. Hone reads it with usage access; nothing leaves the phone.")
                    Button(onClick = { hone.openUsageAccess() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) { Text("Open settings") }
                }
            }
            return@LazyColumn
        }
        item(key = "total") {
            val total = picked?.use ?: bars?.fold(Traffic()) { a, b -> a + b.use }
            TotalCard(total, Modifier.enter(1))
        }
        item(key = "filters") {
            Column(Modifier.enter(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggles(periods, period) { period = it; back = 0 }
                Toggles(networks, net) { net = it }
            }
        }
        item(key = "chart") {
            ChartCard(hone, net, period, back, bars, picked, live, Modifier.enter(3), onBack = { back++ }, onForward = { if (back > 0) back-- }) { bar ->
                picked = if (picked == bar) null else bar
            }
        }
        val list = apps
        when {
            list == null -> item(key = "loading") { Loading(PaddingValues(32.dp)) }
            list.isEmpty() -> item(key = "none") {
                Text("No data use recorded here", Modifier.padding(24.dp), color = cs.onSurfaceVariant)
            }
            else -> {
                val top = list.first().use.total.coerceAtLeast(1)
                items(list, key = { it.key }) { app -> AppRow(hone, app, top, Modifier.animateItem()) { open = app } }
            }
        }
    }
}

@Composable
private fun Toggles(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        labels.forEachIndexed { i, label ->
            ToggleButton(
                checked = selected == i, onCheckedChange = { onPick(i) }, modifier = Modifier.weight(1f),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun TotalCard(total: Traffic?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val mono = LocalLook.current.mono
    SectionCard(modifier) {
        val text = total?.let { bytes(it.total) } ?: "—"
        if (mono) DotText(text, 44.sp, cs.onSurface) else Text(text, style = MaterialTheme.typography.displaySmall)
        Row(Modifier.fillMaxWidth()) {
            MiniStat(Sym.ArrowDown, total?.rx, "down", Modifier.weight(1f))
            MiniStat(Sym.ArrowUp, total?.tx, "up", Modifier.weight(1f))
            MiniStat(Sym.Wifi, total?.wifi, "Wi-Fi", Modifier.weight(1f))
            MiniStat(Sym.Cellular, total?.mobile, "mobile", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, value: Long?, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        Text(value?.let { bytes(it) } ?: "—", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/** One app's traffic since the last look, shown as a tag rising above the current bar. */
data class LiveTag(val id: Long, val pkg: String, val rx: Long, val tx: Long, val born: Long, val secs: Int)

private const val LIVE_SECONDS = 60

@Composable
private fun LiveCard(hone: Hone, modifier: Modifier, height: androidx.compose.ui.unit.Dp = 120.dp) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val mono = LocalLook.current.mono
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val down = remember { mutableStateListOf<Long>() }
    val up = remember { mutableStateListOf<Long>() }
    var lastAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                var rx = android.net.TrafficStats.getTotalRxBytes()
                var tx = android.net.TrafficStats.getTotalTxBytes()
                while (true) {
                    delay(1000)
                    val r = android.net.TrafficStats.getTotalRxBytes()
                    val t = android.net.TrafficStats.getTotalTxBytes()
                    down.add((r - rx).coerceAtLeast(0)); up.add((t - tx).coerceAtLeast(0))
                    rx = r; tx = t
                    while (down.size > LIVE_SECONDS) { down.removeAt(0); up.removeAt(0) }
                    lastAt = System.currentTimeMillis()
                }
            }
        }
    }
    val now by produceState(System.currentTimeMillis()) { while (true) withFrameMillis { value = System.currentTimeMillis() } }

    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (mono) "LIVE" else "Live",
                style = if (mono) MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 1.5.sp) else MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Sym.ArrowDown, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            Text(bytes(down.lastOrNull() ?: 0) + "/s", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            Icon(Sym.ArrowUp, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            Text(bytes(up.lastOrNull() ?: 0) + "/s", style = MaterialTheme.typography.titleSmall)
        }
        val density = LocalDensity.current
        val total = down.indices.map { down[it] + up[it] }
        val max = (total.maxOrNull() ?: 0).coerceAtLeast(64 * 1024).toFloat()
        // How far the line has slid since the last reading, so it moves smoothly between seconds.
        val slide = ((now - lastAt) / 1000f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.matchParentSize()) {
                val step = size.width / (LIVE_SECONDS - 1)
                fun x(i: Int) = size.width - (total.size - 1 - i + slide) * step
                if (mono) {
                    val dot = 3.dp.toPx()
                    val rows = (size.height / (dot * 2.2f)).toInt()
                    // Empty columns across the whole width, so the strip reads as a screen even before it fills.
                    for (c in 0 until LIVE_SECONDS) for (r in 0 until rows) drawCircle(
                        cs.onSurface.copy(alpha = 0.06f), dot / 2, Offset(size.width - c * step, size.height - dot - r * dot * 2.2f),
                    )
                    total.forEachIndexed { i, v ->
                        val lit = (v / max * rows).toInt().let { if (v > 0) it.coerceAtLeast(1) else 0 }
                        for (r in 0 until lit) drawCircle(cs.onSurface, dot / 2, Offset(x(i), size.height - dot - r * dot * 2.2f))
                    }
                } else if (total.size > 1) {
                    val line = Path()
                    total.forEachIndexed { i, v ->
                        val p = Offset(x(i), size.height - v / max * size.height)
                        if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                    }
                    val fill = Path().apply { addPath(line); lineTo(x(total.lastIndex), size.height); lineTo(x(0), size.height); close() }
                    drawPath(fill, cs.primary.copy(alpha = 0.15f), style = Fill)
                    drawPath(line, cs.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (!hone.full) Text(
            "Apps appear as Android records them, every few minutes. With Shizuku they show within seconds.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

private fun Modifier.greyscale() = drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    drawIntoCanvas { c ->
        c.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), paint)
        drawContent()
        c.restore()
    }
}

@Composable
private fun ChartCard(
    hone: Hone, net: Int, period: Int, back: Int, bars: List<NetBar>?, picked: NetBar?, live: List<LiveTag>, modifier: Modifier,
    onBack: () -> Unit, onForward: () -> Unit, onPick: (NetBar) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val mono = LocalLook.current.mono
    val context = LocalContext.current
    val (start, end) = Net.range(period, back)
    // A tapped bar shows its top apps above it; otherwise the current bar collects live tags, like GlassWire.
    val focus = picked
    val tops by produceState(emptyList<NetApp>(), focus, net) {
        value = emptyList()
        value = focus?.let { f -> runCatching { Net.apps(context, hone, f.start, f.end, net).filter { it.pkg != null }.take(4) }.getOrNull() }.orEmpty()
    }
    val title = when (period) {
        0 -> when (back) { 0 -> "Today"; 1 -> "Yesterday"; else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(start)) }
        1 -> if (back == 0) "Last 7 days" else SimpleDateFormat("d MMM", Locale.getDefault()).let { "${it.format(Date(start))} – ${it.format(Date(end - 1))}" }
        else -> if (back == 0) "This month" else SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(start))
    }
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Sym.ChevronRight, "Earlier", Modifier.rotate(180f)) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = onForward, enabled = back > 0) { Icon(Sym.ChevronRight, "Later") }
        }
        val list = bars
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        } else {
            val max = (list.maxOfOrNull { it.use.total } ?: 0).coerceAtLeast(1).toFloat()
            val chartH = 140.dp
            val room = 120.dp
            Box(Modifier.fillMaxWidth().height(chartH + room)) {
            // Scale mark, like GlassWire: the tallest bar's amount at the level its top reaches, over a faint dotted line.
            if (list.any { it.use.total > 0 }) {
                Canvas(Modifier.align(Alignment.TopStart).padding(top = room).fillMaxWidth().height(1.dp)) {
                    drawLine(
                        cs.onSurface.copy(alpha = 0.25f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
                    )
                }
                Text(
                    bytes(max.toLong()),
                    Modifier.align(Alignment.TopStart).padding(top = room - 18.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null),
                    color = cs.onSurfaceVariant,
                )
            }
            Canvas(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(chartH).pointerInput(list) {
                    detectTapGestures { p ->
                        val i = (p.x / (size.width.toFloat() / list.size)).toInt().coerceIn(0, list.lastIndex)
                        if (list[i].use.total > 0) onPick(list[i])
                    }
                },
            ) {
                val slot = size.width / list.size
                list.forEachIndexed { i, b ->
                    val dim = picked != null && picked != b
                    val downColor = (if (mono) cs.onSurface else cs.primary).copy(alpha = if (dim) 0.3f else 1f)
                    val upColor = (if (mono) cs.onSurface.copy(alpha = 0.45f) else cs.tertiary).let { it.copy(alpha = it.alpha * if (dim) 0.3f else 1f) }
                    val cx = slot * i + slot / 2
                    if (mono) {
                        val dot = (slot * 0.5f).coerceIn(2.dp.toPx(), 5.dp.toPx())
                        val gap = dot * 1.6f
                        val rows = (size.height / gap).toInt()
                        val lit = (b.use.total / max * rows).toInt().let { if (b.use.total > 0) it.coerceAtLeast(1) else 0 }
                        val litDown = (lit * (b.use.rx.toFloat() / b.use.total.coerceAtLeast(1))).toInt()
                        for (r in 0 until rows) drawCircle(
                            when { r < litDown -> downColor; r < lit -> upColor; else -> cs.onSurface.copy(alpha = 0.06f) }, dot / 2,
                            Offset(cx, size.height - dot / 2 - r * gap),
                        )
                    } else {
                        val w = (slot * 0.6f).coerceAtMost(14.dp.toPx())
                        val hDown = b.use.rx / max * size.height
                        val hUp = b.use.tx / max * size.height
                        val r = CornerRadius(w / 2)
                        if (hDown + hUp > 0) {
                            drawRoundRect(upColor, Offset(cx - w / 2, size.height - hDown - hUp), Size(w, hDown + hUp), r)
                            drawRoundRect(downColor, Offset(cx - w / 2, size.height - hDown), Size(w, hDown), r)
                        }
                    }
                }
            }
            val fi = focus?.let { list.indexOf(it) } ?: -1
            if (fi >= 0) {
                val frac = focus!!.use.total / max
                tops.forEachIndexed { i, app ->
                    key(focus.start, app.key) { Bubble(hone, app.pkg!!, app.use.rx, app.use.tx, fi, list.size, frac, chartH, room, slot = i) }
                }
            } else if (back == 0) {
                val now = System.currentTimeMillis()
                val ci = list.indexOfFirst { now in it.start until it.end }
                if (ci >= 0) {
                    val frac = list[ci].use.total / max
                    val clock by produceState(System.currentTimeMillis()) { while (true) withFrameMillis { value = System.currentTimeMillis() } }
                    live.forEach { t ->
                        val age = (clock - t.born) / 1000f
                        if (age in 0f..RISE_SECONDS) key(t.id) {
                            // A steady per-tag sideways offset, so packets do not sit in one straight column.
                            val jitter = ((t.id % 7) - 3) * 5f
                            Bubble(hone, t.pkg, t.rx, t.tx, ci, list.size, frac, chartH, room, age = age, jitter = jitter)
                        }
                    }
                }
            }
            }
            val ticks = when (period) {
                0 -> listOf("0", "6", "12", "18", "24")
                1 -> list.map { SimpleDateFormat("EEEEE", Locale.getDefault()).format(Date(it.start)) }
                else -> listOf("1", "8", "15", "22", list.size.toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ticks.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(if (mono) cs.onSurface else cs.primary, "Down")
                Legend(if (mono) cs.onSurface.copy(alpha = 0.45f) else cs.tertiary, "Up")
                Spacer(Modifier.weight(1f))
                if (picked != null) Text(
                    SimpleDateFormat(if (period == 0) "HH:mm" else "EEE d MMM", Locale.getDefault()).format(Date(picked.start)) + " · tap to clear",
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AppRow(hone: Hone, app: NetApp, top: Long, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mono = LocalLook.current.mono
    RowCard(modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app.pkg != null) AppIcon(hone, app.pkg, 44.dp) else ShapeIcon(if (app.key == "Hotspot") Sym.Wifi else Sym.Android, MaterialShapes.Cookie9Sided, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (app.pkg != null && app.pkg in hone.netBlocked) Pill("Offline", cs.outline)
                    Spacer(Modifier.width(8.dp))
                    Text(bytes(app.use.total), style = MaterialTheme.typography.titleSmall)
                }
                val downColor = if (mono) cs.onSurface else cs.primary
                val upColor = if (mono) cs.onSurface.copy(alpha = 0.45f) else cs.tertiary
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val full = size.width * (app.use.total.toFloat() / top)
                    val d = full * app.use.rx / app.use.total.coerceAtLeast(1)
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(cs.surfaceContainerHighest, cornerRadius = r)
                    drawRoundRect(upColor, size = Size(full, size.height), cornerRadius = r)
                    drawRoundRect(downColor, size = Size(d, size.height), cornerRadius = r)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetAppSheet(hone: Hone, app: NetApp, say: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetColor()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.pkg != null) AppIcon(hone, app.pkg, 48.dp) else ShapeIcon(Sym.Android, MaterialShapes.Cookie9Sided, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Text(app.label, style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.fillMaxWidth()) {
                MiniStat(Sym.ArrowDown, app.use.rx, "down", Modifier.weight(1f))
                MiniStat(Sym.ArrowUp, app.use.tx, "up", Modifier.weight(1f))
                MiniStat(Sym.Wifi, app.use.wifi, "Wi-Fi", Modifier.weight(1f))
                MiniStat(Sym.Cellular, app.use.mobile, "mobile", Modifier.weight(1f))
            }
            val pkg = app.pkg
            if (pkg != null && pkg != context.packageName && !hone.isSystem(pkg)) {
                if (!hone.full) {
                    Text("Connect Shizuku to block this app's internet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val offline = pkg in hone.netBlocked
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                val r = runCatching { hone.setNetwork(pkg, offline) }.getOrNull()
                                busy = false
                                say(
                                    when {
                                        r?.ok != true -> r?.output?.ifBlank { null } ?: "No change"
                                        offline -> "${app.label} can use the internet"
                                        else -> "${app.label} is offline until restart"
                                    },
                                )
                            }
                        },
                        enabled = !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (offline) Sym.CheckCircle else Sym.Block, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (offline) "Allow internet" else "Block internet until restart")
                    }
                }
            }
        }
    }
}

private const val RISE_SECONDS = 9f

/**
 * A tag (icon and amount) above bar [index]. A live one ([age] set) is born at the top of the bar small and faint,
 * grows to full size, and keeps drifting straight up at a steady pace until it fades out near the top, like
 * GlassWire's packets. A static one sits at place [slot] in a column above the bar.
 */
@Composable
private fun Bubble(
    hone: Hone, pkg: String, rx: Long, tx: Long, index: Int, count: Int, frac: Float,
    chartH: androidx.compose.ui.unit.Dp, room: androidx.compose.ui.unit.Dp,
    slot: Int = 0, age: Float? = null, jitter: Float = 0f,
) {
    val cs = MaterialTheme.colorScheme
    val mono = LocalLook.current.mono
    val downward = rx >= tx
    val tint = when {
        mono -> if (downward) cs.onSurface else cs.onSurfaceVariant
        else -> if (downward) cs.primary else cs.tertiary
    }
    val t = age?.let { it / RISE_SECONDS }
    val grow = t?.let { (it * 6f).coerceIn(0f, 1f) } ?: 1f
    val alpha = t?.let { grow * ((1f - it) / 0.3f).coerceIn(0f, 1f) } ?: 1f
    Row(
        Modifier
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                layout(0, 0) {
                    val w = c.maxWidth.toFloat()
                    val cx = w / count * (index + 0.5f) + jitter.dp.toPx()
                    val barTop = room.toPx() + chartH.toPx() * (1f - frac) - 4.dp.toPx()
                    val y = if (t != null) barTop - p.height - t * (barTop - p.height).coerceAtLeast(0f)
                    else (barTop - (slot + 1) * (p.height + 4.dp.toPx())).coerceAtLeast(0f)
                    val x = (cx - p.width / 2f).coerceIn(0f, (w - p.width).coerceAtLeast(0f))
                    p.placeRelative(x.toInt(), y.toInt())
                }
            }
            .graphicsLayer { scaleX = 0.6f + 0.4f * grow; scaleY = scaleX; this.alpha = alpha }
            .background(cs.surfaceContainerHigh, androidx.compose.foundation.shape.CircleShape)
            .then(if (mono) Modifier.border(1.dp, cs.outlineVariant, androidx.compose.foundation.shape.CircleShape) else Modifier)
            .padding(start = 3.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(if (mono) Modifier.greyscale() else Modifier) { AppIcon(hone, pkg, 16.dp) }
        Spacer(Modifier.width(4.dp))
        Text(
            bytes(rx + tx),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null),
            color = tint, maxLines = 1,
        )
    }
}

/** The detailed live view behind the graph icon: the phone's speed each second, and the apps moving data now. */
@Composable
private fun LivePage(hone: Hone, pad: PaddingValues, live: List<LiveTag>) {
    val cs = MaterialTheme.colorScheme
    // Latest reading per app, newest first; a tag older than its own interval twice over means the app went quiet.
    val now = System.currentTimeMillis()
    val active = live.filter { now - it.born < it.secs * 2000L }.distinctBy { it.pkg }
    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hone.netLive = false }) { Icon(Sym.ArrowBack, "Back") }
                Text("Live graph", style = MaterialTheme.typography.titleLarge)
            }
        }
        item(key = "graph") { LiveCard(hone, Modifier.enter(0), height = 220.dp) }
        item(key = "nowTitle") { SectionTitle("Using data now") }
        if (active.isEmpty()) item(key = "quiet") {
            Text(
                if (hone.full) "No app has moved data in the last few seconds." else "Without Shizuku, Android updates per-app numbers only every few minutes.",
                Modifier.padding(horizontal = 8.dp), color = cs.onSurfaceVariant,
            )
        }
        items(active, key = { it.pkg }) { t ->
            RowCard(Modifier.animateItem()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(hone, t.pkg, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(hone.label(t.pkg), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Icon(Sym.ArrowDown, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                    Text(bytes(t.rx / t.secs) + "/s", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(10.dp))
                    Icon(Sym.ArrowUp, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                    Text(bytes(t.tx / t.secs) + "/s", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
