@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val HISTORY = 40

@Composable
fun HealthScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit, goTo: (Int) -> Unit) {
    var health by remember { mutableStateOf<Health?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askReboot by remember { mutableStateOf(false) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }
    val scope = rememberCoroutineScope()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Only while Hone is on screen. The full reading asks system services for heat and battery, so it runs
    // every 10 s; the live graphs read /proc alone every 2 s, so watching does not warm the phone it measures.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var tick = 0
            while (true) {
                val full = tick % 5 == 0 || health == null
                runCatching {
                    if (full) {
                        health = hone.health()
                    } else {
                        val l = hone.call<Live>("live")
                        health = health?.copy(cpuBusy = l.cpuBusy, cpus = l.cpus, memory = health!!.memory.copy(available = l.memory.available))
                    }
                }.onSuccess {
                    val h = health!!
                    error = null
                    cpuHistory += cpuNow(hone, h).toFloat()
                    memHistory += (1f - h.memory.available.toFloat() / h.memory.total.coerceAtLeast(1)) * 100f
                    while (cpuHistory.size > HISTORY) cpuHistory.removeAt(0)
                    while (memHistory.size > HISTORY) memHistory.removeAt(0)
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    error = it.message
                    android.util.Log.w(TAG, "health", it)
                }
                tick++
                delay(2000)
            }
        }
    }
    val h = health ?: return Loading(pad, error ?: "Reading the phone")
    val cs = MaterialTheme.colorScheme

    if (askReboot) Confirm(
        "Restart the phone?", "Open apps close. It takes about a minute.", "Restart",
        onConfirm = { scope.launch { runCatching { hone.raw("reboot") } } }, onDismiss = { askReboot = false },
    )
    val act: (String) -> Unit = { action ->
        when (action) {
            "storage" -> goTo(2)
            "reboot" -> askReboot = true
            "free-ram" -> scope.launch {
                runCatching { hone.call<Freed>("free-ram") }.onSuccess { say("Freed ${bytes(it.freed)} of memory") }
                    .onFailure { say("Could not free memory: ${it.message}") }
            }
        }
    }

    val usedDisk = 1f - h.storage.free.toFloat() / h.storage.total.coerceAtLeast(1)
    val usedMem = 1f - h.memory.available.toFloat() / h.memory.total.coerceAtLeast(1)
    val hottest = h.temps.maxByOrNull { it.celsius }

    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hero") {
            HeroCard(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, Modifier.enter(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val score = h.score.toInt()
                    Ring(
                        h.score.toFloat() / 100f, Modifier.size(148.dp), 16.dp,
                        if (score >= 75) listOf(cs.primary, cs.tertiary) else listOf(cs.error, cs.tertiary),
                        cs.onPrimaryContainer.copy(alpha = 0.12f),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Counter(score, MaterialTheme.typography.displayMediumEmphasized, color = cs.onPrimaryContainer)
                            Text("score", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedContent(
                            when {
                                score >= 90 -> "Running great"
                                score >= 75 -> "Running well"
                                score >= 55 -> "Could be faster"
                                else -> "Needs attention"
                            },
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "verdict",
                        ) { Text(it, style = MaterialTheme.typography.headlineSmallEmphasized, color = cs.onPrimaryContainer) }
                        Pill("Storage ${(usedDisk * 100).toInt()}%", if (usedDisk > 0.9f) cs.error else cs.primary)
                        Pill("Memory ${(usedMem * 100).toInt()}%", cs.tertiary)
                        if (hottest != null) Pill("${fmt(hottest.celsius)}° hottest", if (hottest.celsius >= 60) cs.error else cs.secondary)
                    }
                }
            }
        }

        itemsIndexed(h.findings, key = { _, f -> "f:" + f.title.takeWhile { !it.isDigit() } }) { i, f ->
            FindingCard(f, act, Modifier.animateItem().enter(i + 1))
        }

        item(key = "live") {
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min).enter(2), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LiveCard("CPU", "${fmt(cpuNow(hone, h), 0)}%", if (hone.full) "busy" else "clock", cpuHistory, 100f, cs.primary, Modifier.weight(1f).fillMaxHeight())
                LiveCard("Memory", bytes(h.memory.available), "free", memHistory, 100f, cs.tertiary, Modifier.weight(1f).fillMaxHeight())
            }
        }

        item(key = "cores") {
            SectionCard(Modifier.enter(3)) {
                CardHeader(Icons.Rounded.DeveloperBoard, "${h.cpus.size} cores", if (hone.full) "How busy each core is, live" else "Clock speed of each core, live", MaterialShapes.Square)
                Equalizer(h.cpus.map { if (hone.full) (it.busy / 100).toFloat() else it.cur.toFloat() / it.max.coerceAtLeast(1) }, Modifier.fillMaxWidth().height(110.dp), cs.primary, cs.surfaceContainerHighest)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    h.cpus.forEach { c ->
                        Text(
                            if (c.cur >= 1000) "${fmt(c.cur / 1000.0)}G" else "${c.cur}M",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        item(key = "battery") {
            SectionCard(Modifier.enter(4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring((h.battery.level / 100).toFloat(), Modifier.size(84.dp), 10.dp, listOf(cs.tertiary, cs.primary), cs.surfaceContainerHighest) {
                        Icon(if (h.battery.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, null, tint = cs.primary)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Counter(h.battery.level.toInt(), MaterialTheme.typography.headlineMediumEmphasized, suffix = "%")
                        Text(
                            listOf(
                                if (h.battery.charging) "Charging" else "On battery",
                                "${fmt(h.battery.celsius)} °C",
                                "${fmt(h.battery.volts, 2)} V",
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                        Text(
                            if (h.battery.health == 2.0) "Battery health good" else "Battery health needs a look",
                            style = MaterialTheme.typography.labelLarge, color = if (h.battery.health == 2.0) cs.primary else cs.error,
                        )
                    }
                }
            }
        }

        item(key = "heat") {
            SectionCard(Modifier.enter(5)) {
                CardHeader(Icons.Rounded.Thermostat, "Heat", thermalText(h.thermalStatus), MaterialShapes.Sunny)
                mergeChips(h.temps).sortedByDescending { it.celsius }.forEach { t ->
                    val limit = if (t.name == "BATTERY" || t.name == "SKIN") 45.0 else 75.0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            RowLabel(sensorName(t.name), Modifier.weight(1f))
                            Text("${fmt(t.celsius)}°", style = MaterialTheme.typography.titleSmall)
                        }
                        GradientBar(
                            ((t.celsius - 20) / (limit - 20)).toFloat(),
                            listOf(cs.tertiary, if (t.celsius >= limit - 10) cs.error else cs.primary),
                            cs.surfaceContainerHighest, height = 8.dp,
                        )
                    }
                }
            }
        }

        item(key = "system") {
            SectionCard(Modifier.enter(6)) {
                CardHeader(Icons.Rounded.Timeline, "System", null, MaterialShapes.Gem)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Up ${duration(h.uptime)}")
                    Pill("Swap ${bytes(h.memory.swapTotal - h.memory.swapFree)} / ${bytes(h.memory.swapTotal)}")
                    Pill("${h.display.peakRefresh.toInt()} Hz")
                    Pill("Animations ${fmt(h.display.animation)}x")
                }
            }
        }

        if (h.processes.isNotEmpty()) item(key = "procs") {
            SectionCard(Modifier.enter(7)) {
                CardHeader(Icons.Rounded.Memory, "Busiest right now", "Share of the whole CPU", MaterialShapes.Cookie6Sided)
                val busy = h.processes.filter { it.name != "libhone.so" && it.name != "hone" && it.rss > 0 }
                if (busy.none { it.cpu >= 2.0 }) {
                    RowLabel("Nothing busy, the phone is idle")
                }
                busy.filter { it.cpu >= 0.5 }.take(6).forEach { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowLabel(processName(hone, p.name), Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text("${fmt(p.cpu)}%  ·  ${bytes(p.rss)}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        }
                        // Half the CPU fills the bar, so a sliver really is a sliver.
                        GradientBar((p.cpu / 50).toFloat(), listOf(cs.secondary, if (p.cpu >= 40) cs.error else cs.primary), cs.surfaceContainerHighest, height = 6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCard(title: String, value: String, unit: String, history: List<Float>, max: Float, color: Color, modifier: Modifier) {
    SectionCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigValue(value, MaterialTheme.typography.headlineSmallEmphasized)
        Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Sparkline(history, max, color, Modifier.fillMaxWidth().height(64.dp))
        }
    }
}

@Composable
private fun FindingCard(f: Finding, act: (String) -> Unit, modifier: Modifier) {
    val bad = f.level == "bad"
    val cs = MaterialTheme.colorScheme
    SectionCard(modifier, container = if (bad) cs.errorContainer else cs.tertiaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                if (bad) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber,
                MaterialShapes.Burst,
                container = if (bad) cs.error else cs.tertiary,
                content = if (bad) cs.onError else cs.onTertiary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(f.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val label = when (f.action) {
            "storage" -> "Free up space"
            "free-ram" -> "Free memory"
            "reboot" -> "Restart"
            else -> null
        }
        if (label != null) {
            FilledTonalButton(onClick = { act(f.action) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                Text(label)
            }
        }
    }
}

private fun cpuNow(hone: Hone, h: Health): Double =
    if (hone.full) h.cpuBusy else h.cpus.map { it.cur.toDouble() / it.max.coerceAtLeast(1) }.average().takeIf { !it.isNaN() }?.times(100) ?: 0.0

private val CHIP = setOf("CPU", "GPU", "NPU", "TPU", "SOC")

/** The chip's sensors often read one value; show them as a single "Chip" row then. */
private fun mergeChips(temps: List<Temp>): List<Temp> {
    val chip = temps.filter { it.name in CHIP }
    if (chip.size < 2 || chip.map { it.celsius }.distinct().size > 1) return temps
    return listOf(Temp("CHIP", chip.first().celsius)) + temps.filter { it.name !in CHIP }
}

/** "com.android.systemui:screenshot" -> "System UI · screenshot"; HAL services get a short name. */
private fun processName(hone: Hone, raw: String): String {
    val base = raw.substringBefore(':')
    val part = raw.substringAfter(':', "")
    val label = hone.label(base).takeIf { it != base } ?: when {
        base.startsWith("android.hardware.") || base.startsWith("vendor.") ->
            base.removePrefix("vendor.").removePrefix("android.hardware.").substringBefore('-').substringBefore('@').substringBefore('.')
                .replaceFirstChar { it.titlecase() } + " service"
        else -> base
    }
    return if (part.isNotEmpty()) "$label · $part" else label
}

private fun sensorName(raw: String) = when (raw) {
    "CHIP" -> "Chip"
    "CPU", "GPU", "NPU", "TPU", "SOC" -> raw
    else -> raw.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)
}

private fun thermalText(status: Int) = when (status) {
    0 -> "Cool, running at full speed"
    1 -> "Warm, slightly slowed"
    2 -> "Hot, slowed down"
    3 -> "Very hot, heavily slowed"
    else -> "Overheating, level $status"
}

fun fmt(v: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", v).removeSuffix(".0")
