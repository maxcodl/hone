@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Storage and CPU speed, with the previous run beside it to see what a cleanup changed. */
@Composable
fun BenchCard(hone: Hone, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val last = hone.benchLast
    val before = hone.benchBefore
    SectionCard(modifier) {
        CardHeader(Sym.Speed, "Speed test", "Storage write and read, random reads and CPU. About 10 seconds; run it before and after a cleanup.", MaterialShapes.Cookie7Sided)
        if (last != null) {
            BenchLine("Write", last.writeMBs, before?.writeMBs, "MB/s")
            BenchLine("Read", last.readMBs, before?.readMBs, "MB/s")
            BenchLine("Random reads", last.randomIops, before?.randomIops, "/s")
            BenchLine("CPU, one core", last.cpuSingle, before?.cpuSingle, "")
            BenchLine("CPU, all cores", last.cpuMulti, before?.cpuMulti, "")
            if (before != null) Text("Change against the run before.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(
            onClick = {
                running = true
                scope.launch {
                    runCatching { hone.runBench() }.onSuccess { if (it.error != null) say("Speed test failed: ${it.error}") }.onFailure { say("Speed test failed: ${it.message}") }
                    running = false
                }
            },
            enabled = !running, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End),
        ) {
            if (running) LoadingIndicator(Modifier.size(20.dp)) else Text(if (last == null) "Run test" else "Run again")
        }
    }
}

@Composable
private fun BenchLine(label: String, now: Double, before: Double?, unit: String) {
    val change = before?.takeIf { it > 0 }?.let { (now - it) / it * 100 }
    Line(label, "${fmt(now, 0)} $unit".trim() + (change?.let { "  ${if (it >= 0) "+" else ""}${fmt(it, 0)}%" } ?: ""))
}

private val animationSteps = listOf(0.0 to "Off", 0.5 to "Fast", 1.0 to "Normal", 1.5 to "Slow")
private val fontSteps = listOf(0.85, 1.0, 1.1, 1.2, 1.3)

/** Animation speed, refresh rate and text size in one place; written through the shell. */
@Composable
fun DisplayCard(hone: Hone, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DisplayState?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(hone.full) { if (hone.full) state = runCatching { hone.call<DisplayState>("display") }.getOrNull() }

    fun set(pairs: List<Pair<String, Double>>, done: String) = scope.launch {
        busy = true
        val ok = pairs.all { (k, v) -> runCatching { hone.call<Changed>("set-display", k, v.toString()).ok }.getOrDefault(false) }
        state = runCatching { hone.call<DisplayState>("display") }.getOrNull() ?: state
        busy = false
        say(if (ok) done else "Android did not take the change")
    }

    SectionCard(modifier) {
        CardHeader(Sym.Palette, "Display", "Animation speed, refresh rate and text size.", MaterialShapes.Gem)
        if (!hone.full) {
            Text("Changing these takes Shizuku. Android's own settings have them spread over several pages.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                Text("Open display settings")
            }
            return@SectionCard
        }
        val s = state?.settings ?: return@SectionCard Text("Reading settings…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val anim = s["animator_duration_scale"] ?: 1.0
        RowLabel("Animations")
        Chips(animationSteps.map { it.second }, animationSteps.indexOfFirst { it.first == anim }, !busy) { i ->
            val v = animationSteps[i].first
            set(listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale").map { it to v }, "Animations: ${animationSteps[i].second.lowercase()}")
        }
        val modes = state?.modes.orEmpty()
        if (modes.size > 1) {
            val peak = s["peak_refresh_rate"]
            RowLabel("Top refresh rate")
            Chips(modes.map { "${it.toInt()} Hz" }, modes.indexOfFirst { peak != null && kotlin.math.abs(it - peak) < 1 }, !busy) { i ->
                set(listOf("peak_refresh_rate" to modes[i]), "Screen tops out at ${modes[i].toInt()} Hz")
            }
        }
        val font = s["font_scale"] ?: 1.0
        RowLabel("Text size")
        Chips(fontSteps.map { "${(it * 100).toInt()}%" }, fontSteps.indexOfFirst { kotlin.math.abs(it - font) < 0.01 }, !busy) { i ->
            set(listOf("font_scale" to fontSteps[i]), "Text size ${(fontSteps[i] * 100).toInt()}%")
        }
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> FilterChip(selected = i == selected, onClick = { onPick(i) }, enabled = enabled, label = { Text(l) }) }
    }
}

/** The overnight upkeep switch, with what the last night did. */
@Composable
fun NightCard(hone: Hone, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NightPrefs.on(context)) }
    SectionCard(modifier) {
        CardHeader(Sym.Bedtime, "Overnight upkeep", "While the phone charges at night: compile apps still basic and run storage upkeep (with Shizuku), and clear trash older than 30 days if auto-empty is on. A quiet summary waits in the morning.", MaterialShapes.Arch)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowLabel(if (on) "On" else "Off")
                NightPrefs.last(context)?.let { Text("Last night: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = { NightPrefs.set(context, it); on = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowLabel("Auto-empty trash")
                Text("Delete trash items for good after 30 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = hone.autoEmpty, onCheckedChange = { hone.updateAutoEmpty(it) })
        }
    }
}
