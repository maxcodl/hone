@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.launch

@Composable
fun SpeedScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var askReboot by remember { mutableStateOf(false) }
    val compiling = remember { mutableStateListOf<String>() }
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { if (hone.full && hone.dexopt == null) hone.refreshDexopt() }

    fun job(key: String, block: suspend () -> String) = scope.launch {
        busy = key
        say(runCatching { block() }.getOrElse { "Failed: ${it.message}" })
        busy = null
    }

    if (askReboot) Confirm(
        "Restart the phone?", "Open apps close. It takes about a minute.", "Restart",
        onConfirm = { scope.launch { runCatching { hone.raw("reboot") } } }, onDismiss = { askReboot = false },
    )

    val d = hone.dexopt
    val fast = d?.let { it.counts["speed-profile"].orZero() + it.counts["speed"].orZero() + it.counts["everything"].orZero() } ?: 0
    val basic = d?.let { x -> x.counts.filterKeys { it in setOf("verify", "run-from-apk", "extract") }.values.sum() } ?: 0

    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!hone.full) item(key = "needs") {
            NeedsShizuku(
                hone, "More with Shizuku",
                "App optimisation, storage upkeep, compiling apps and restart need shell access. Free memory works without it.",
                Modifier.enter(0),
            )
        }
        if (hone.full) item(key = "hero") {
            HeroCard(MaterialShapes.SoftBurst, MaterialShapes.Cookie9Sided, Modifier.enter(0), spinning = true) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(
                            if (d == null) 0f else fast.toFloat() / (fast + basic).coerceAtLeast(1), Modifier.size(132.dp), 14.dp,
                            listOf(cs.primary, cs.tertiary), cs.onPrimaryContainer.copy(alpha = 0.12f),
                        ) {
                            if (hone.optimizing) {
                                MorphBlob(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, SolidColor(cs.primary.copy(alpha = 0.25f)), Modifier.size(80.dp), periodMs = 1200)
                            }
                            if (d == null) LoadingIndicator(Modifier.size(56.dp))
                            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Counter(fast, MaterialTheme.typography.headlineMediumEmphasized, color = cs.onPrimaryContainer)
                                Text("of ${fast + basic}", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("App optimisation", style = MaterialTheme.typography.titleLargeEmphasized, color = cs.onPrimaryContainer)
                            Text(
                                if (d == null) "Reading compile state, about 15 seconds"
                                else "$fast apps compiled for speed, $basic still basic",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer,
                            )
                        }
                    }
                    if (hone.optimizing) {
                        AnimatedContent(
                            hone.compilingNow?.let { hone.label(it) } ?: "Between apps",
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "app",
                        ) { Pill("Now: $it", cs.primary, cs.surface.copy(alpha = 0.6f)) }
                        OutlinedButton(onClick = { hone.cancelOptimize() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { hone.optimize() }, shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                        ) { Text("Optimise now", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
                    }
                }
            }
        }
        item(key = "tiles") {
            Row(Modifier.enter(1), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.weight(1f), Icons.Rounded.Memory, MaterialShapes.Cookie9Sided, "Free memory", "Close background apps", busy == "ram", busy == null) {
                    job("ram") { (if (hone.full) hone.call<Freed>("free-ram") else hone.freeRamLocal()).let { "Freed ${bytes(it.freed)}, ${bytes(it.available)} now free" } }
                }
                if (hone.full) Tile(Modifier.weight(1f), Icons.Rounded.Build, MaterialShapes.Gem, "Storage upkeep", "Trim and tidy storage", busy == "trim", busy == null) {
                    job("trim") { hone.raw("maintenance"); "Storage upkeep started" }
                }
            }
        }
        if (hone.full) item(key = "restart") {
            SectionCard(Modifier.enter(2)) {
                CardHeader(Icons.Rounded.RestartAlt, "Restart", "Clears memory built up over days and resets stuck services.", MaterialShapes.Arch)
                FilledTonalButton(onClick = { askReboot = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                    Text("Restart")
                }
            }
        }

        val slowApps = if (!hone.full) emptyList() else d?.slow.orEmpty().filter { !hone.isSystem(it) }
        if (slowApps.isNotEmpty()) {
            item(key = "slowHead") {
                SectionCard(Modifier.enter(3)) {
                    Text("Your apps not fully optimised", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        "Android compiles only the parts of an app you use. A full compile makes every part fast, but uses more space.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                compiling.addAll(slowApps)
                                val r = runCatching { hone.call<Compiled>("compile", "speed", *slowApps.toTypedArray()) }
                                compiling.clear()
                                hone.refreshDexopt()
                                say(r.map { "Compiled ${it.results.count { x -> x.ok }} of ${slowApps.size} apps" }.getOrElse { "Failed: ${it.message}" })
                            }
                        },
                        enabled = compiling.isEmpty(), shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End),
                    ) { Text("Compile all ${slowApps.size}") }
                }
            }
            itemsIndexed(slowApps, key = { _, p -> p }) { i, pkg ->
                RowCard(Modifier.animateItem().enter(i + 4)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(hone, pkg, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(hone.label(pkg), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (pkg in compiling) {
                            LoadingIndicator(Modifier.size(40.dp))
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        compiling.add(pkg)
                                        val ok = runCatching { hone.call<Compiled>("compile", "speed", pkg).results.firstOrNull()?.ok == true }.getOrDefault(false)
                                        compiling.remove(pkg)
                                        say(if (ok) "${hone.label(pkg)} compiled" else "Could not compile ${hone.label(pkg)}")
                                        hone.refreshDexopt()
                                    }
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("Compile") }
                        }
                    }
                }
            }
        }
    }
}

private fun Int?.orZero() = this ?: 0

@Composable
private fun Tile(
    modifier: Modifier,
    icon: ImageVector,
    shape: RoundedPolygon,
    title: String,
    sub: String,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SectionCard(modifier, container = cs.surfaceContainerHigh) {
        if (running) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                MorphBlob(shape, MaterialShapes.Sunny, SolidColor(cs.secondaryContainer), Modifier.size(64.dp), periodMs = 900)
                Icon(icon, null, tint = cs.onSecondaryContainer)
            }
        } else {
            ShapeIcon(icon, shape, cs.secondaryContainer, cs.onSecondaryContainer, 64.dp)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Working" else "Run")
        }
    }
}
