@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The storage widget: what uses the space, for all storage, internal or the SD card. The button at
 * the top right moves between them and Hone remembers the choice.
 */
@Composable
fun SpaceWidget(hone: Hone, vols: List<Hone.Volume>, disk: Disk, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val sd = vols.firstOrNull { it.removable }
    val internal = vols.firstOrNull { !it.removable }
    // Without an SD card there is nothing to switch to beyond internal.
    val scope = if (sd == null && hone.storageScope != 1) 1 else hone.storageScope
    LaunchedEffect(scope, sd?.path) { if (scope != 1 && sd != null && hone.sdScan == null) hone.scanSd(sd.path) }

    SectionCard(modifier) {
        AnimatedContent(scope, transitionSpec = { fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(100)) }, label = "scope") { s ->
            val total = when (s) {
                1 -> internal?.total ?: disk.total
                2 -> sd?.total ?: 0
                else -> vols.sumOf { it.total }.coerceAtLeast(disk.total)
            }
            val free = when (s) {
                1 -> internal?.free ?: disk.free
                2 -> sd?.free ?: 0
                else -> vols.sumOf { it.free }.takeIf { vols.isNotEmpty() } ?: disk.free
            }
            val used = total - free
            val inScan = hone.scan
            val sdScan = hone.sdScan
            // Internal: files by type, plus installed apps, plus whatever the system itself holds.
            val cats = buildMap<String, Long> {
                if (s != 2) {
                    inScan?.categories?.forEach { put(it.name, (get(it.name) ?: 0) + it.bytes) }
                    if (hone.appsBytes > 0) put("Apps", (get("Apps") ?: 0) + hone.appsBytes)
                }
                if (s != 1) sdScan?.categories?.forEach { put(it.name, (get(it.name) ?: 0) + it.bytes) }
            }.filterValues { it > 0 }
            val system = (used - cats.values.sum()).coerceAtLeast(0)
            val slices = cats.entries.sortedByDescending { it.value }.map { it.key to it.value } +
                listOfNotNull(("System" to system).takeIf { system > total / 100 })
            val files = (if (s != 2) inScan?.files ?: 0 else 0) + (if (s != 1) sdScan?.files ?: 0 else 0)
            val measuring = (s != 2 && inScan == null) || (s != 1 && sd != null && sdScan == null)
            val colors = spaceColors()

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RowLabel(when (s) { 1 -> "Internal storage"; 2 -> "SD card"; else -> "All storage" })
                        BigValue(bytes(used), MaterialTheme.typography.headlineMediumEmphasized)
                        Text(
                            "used of ${bytes(total)}  ·  ${bytes(free)} free  ·  ${(used * 100 / total.coerceAtLeast(1))}%",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { hone.nextStorageScope(sd != null) },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(when (s) { 1 -> Sym.Board; 2 -> Sym.SdCard; else -> Sym.Storage }, "Switch between all storage, internal and SD card")
                    }
                }
                // Space bar: each kind of file, the system's share, then free space.
                SpaceBar(slices.map { it.second to (colors[it.first] ?: cs.outline) } + (free to cs.surfaceContainerHighest), total)
                if (measuring && slices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Measuring every folder", color = cs.onSurfaceVariant)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        slices.forEach { (name, size) -> Pill("$name ${bytes(size)}", colors[name] ?: cs.outline) }
                        Pill("Free ${bytes(free)}", cs.surfaceContainerHighest)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            files.takeIf { it > 0 }?.let { "%,d files".format(it) },
                            inScan?.trash?.takeIf { it > 0 && s != 2 }?.let { "trash ${bytes(it)}" },
                            if (free < total / 10) "low on space" else null,
                        ).joinToString("  ·  "),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = if (free < total / 10) cs.error else cs.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            if (s != 2) hone.rescan()
                            if (s != 1 && sd != null) hone.scanSd(sd.path)
                        },
                        enabled = !hone.scanning, shapes = ButtonDefaults.shapes(),
                    ) { Text(if (hone.scanning) "Scanning" else "Scan again") }
                }
            }
        }
    }
}

/** One row of slices sized by bytes against the whole volume; dots in the Dot look. */
@Composable
private fun SpaceBar(slices: List<Pair<Long, Color>>, total: Long) {
    val whole = total.coerceAtLeast(1).toFloat()
    if (LocalLook.current.mono) {
        val dots = 32
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0 until dots) {
                val at = (i + 0.5f) / dots * whole
                var sum = 0f
                val c = slices.firstOrNull { sum += it.first; at <= sum }?.second ?: cs.surfaceContainerHighest
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
        slices.filter { it.first > 0 }.forEach { (v, c) -> Box(Modifier.weight(v / whole).height(14.dp).background(c)) }
    }
}

@Composable
private fun spaceColors(): Map<String, Color> {
    val cs = MaterialTheme.colorScheme
    return mapOf(
        "Apps" to cs.onSurface, "Images" to cs.primary, "Videos" to cs.secondary, "Audio" to cs.tertiary,
        "Documents" to cs.inversePrimary, "APKs" to cs.error, "Archives" to cs.outline, "Other" to cs.outlineVariant,
        "System" to cs.surfaceVariant,
    )
}
