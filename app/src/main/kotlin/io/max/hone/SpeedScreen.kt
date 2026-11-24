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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun SpeedScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit, top: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var askReboot by remember { mutableStateOf(false) }
    val compiling = remember { mutableStateListOf<String>() }
    var batchTotal by remember { mutableStateOf(0) }
    var batchDone by remember { mutableStateOf(0) }
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { if (hone.full && hone.dexopt == null) hone.refreshDexopt() }
    var askOptimize by remember { mutableStateOf(false) }
    var showKeep by remember { mutableStateOf(false) }
    if (showKeep) KeepWarmSheet(hone) { showKeep = false }
    if (askOptimize) {
        val count = hone.compilable().size
        if (count == 0) androidx.compose.material3.AlertDialog(
            onDismissRequest = { askOptimize = false },
            title = { Text("Already optimised") },
            text = { Text("Every app that can be compiled is. The ones still basic are debug builds or apps Android cannot compile; they are marked in the list below.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askOptimize = false }) { Text("OK") } },
        ) else Confirm(
            "Compile $count apps for speed?",
            "Android normally compiles only the parts of apps you use, so these stay basic. This compiles each of them fully: " +
                "faster to open, but it takes more storage, roughly 1 to 3 MB per app (${bytes(hone.disk().free)} free now). " +
                "It runs one app at a time and you can stop it whenever you like.",
            "Compile",
            onConfirm = { hone.optimizeAll() }, onDismiss = { askOptimize = false },
        )
    }

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
        top()
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
                                when {
                                    d == null -> "Reading compile state, about 15 seconds"
                                    hone.optimizing -> "Compiling ${hone.batchDone + 1} of ${hone.batchTotal}, ${(fast - hone.runStartFast).coerceAtLeast(0)} newly compiled"
                                    hone.compilable().isEmpty() -> "$fast compiled for speed. The $basic basic ones are debug builds or cannot be compiled."
                                    else -> "$fast apps compiled for speed, $basic still basic"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer,
                            )
                        }
                    }
                    if (!hone.optimizing) hone.lastRun?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer) }
                    if (hone.optimizing) {
                        androidx.compose.material3.LinearWavyProgressIndicator(
                            progress = { hone.batchDone.toFloat() / hone.batchTotal.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AnimatedContent(
                            hone.compilingNow?.let { hone.label(it) } ?: "Between apps",
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "app",
                        ) { Pill(it, cs.primary, cs.surface.copy(alpha = 0.6f)) }
                        OutlinedButton(onClick = { hone.cancelOptimize() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { askOptimize = true }, shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                        ) { Text("Optimise apps", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
                    }
                }
            }
        }
        item(key = "tiles") {
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min).enter(1), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.weight(1f).fillMaxHeight(), Sym.Memory, MaterialShapes.Cookie9Sided, "Free memory", "Close background apps", busy == "ram", busy == null, extra = {
                    androidx.compose.material3.TextButton(onClick = { showKeep = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Keep warm: ${hone.keepWarm.size + hone.alwaysKept().size} apps", style = MaterialTheme.typography.labelLarge)
                    }
                }) {
                    job("ram") { hone.freeRam().also { HoneWidget.refresh(context) }.let { "Closed ${if (it.apps > 0) "${it.apps} apps, " else ""}freed ${bytes(it.freed)}, ${bytes(it.available)} now free" } }
                }
                if (hone.full) Tile(Modifier.weight(1f).fillMaxHeight(), Sym.Build, MaterialShapes.Gem, "Storage upkeep", "Trim and tidy storage", busy == "trim", busy == null) {
                    job("trim") { hone.raw("maintenance"); "Storage upkeep started" }
                }
            }
        }
        item(key = "bench") { BenchCard(hone, say, Modifier.enter(2)) }
        item(key = "display") { DisplayCard(hone, say, Modifier.enter(2)) }
        item(key = "night") { NightCard(hone, Modifier.enter(2)) }
        if (hone.full) item(key = "restart") {
            SectionCard(Modifier.enter(2)) {
                CardHeader(Sym.Restart, "Restart", "Clears memory built up over days and resets stuck services.", MaterialShapes.Arch)
                FilledTonalButton(onClick = { askReboot = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                    Text("Restart")
                }
            }
        }

        val mine = if (!hone.full) emptyList() else d?.slow.orEmpty().filter { !hone.isSystem(it) }
        // Android never fully compiles debug builds; listing them only invites taps that cannot work.
        val debugBuilds = mine.filter { hone.isDebuggable(it) }
        val slowApps = mine - debugBuilds.toSet() - hone.failedCompile.keys
        if (mine.isNotEmpty()) {
            item(key = "slowHead") {
                SectionCard(Modifier.enter(3)) {
                    Text("Your apps not fully optimised", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        "Android compiles only the parts of an app you use. A full compile makes every part fast, but uses more space.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                    if (debugBuilds.isNotEmpty()) Text(
                        "Debug builds always run uncompiled; Android does not allow it for them.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    if (batchTotal > 0) {
                        Text("Compiling ${batchDone + 1} of $batchTotal", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.material3.LinearWavyProgressIndicator(progress = { batchDone.toFloat() / batchTotal }, modifier = Modifier.fillMaxWidth())
                    }
                    if (slowApps.isNotEmpty()) FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val queue = slowApps.toList()
                                batchTotal = queue.size
                                var ok = 0
                                queue.forEachIndexed { i, pkg ->
                                    batchDone = i
                                    compiling.add(pkg)
                                    if (runCatching { hone.compile(pkg).ok }.getOrDefault(false)) ok++
                                    compiling.remove(pkg)
                                }
                                batchTotal = 0
                                batchDone = 0
                                say("Compiled $ok of ${queue.size} apps")
                            }
                        },
                        enabled = compiling.isEmpty(), shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End),
                    ) { Text("Compile all ${slowApps.size}") }
                }
            }
            itemsIndexed(slowApps + mine.filter { it !in slowApps }, key = { _, p -> p }) { i, pkg ->
                RowCard(Modifier.animateItem().enter(i + 4)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(hone, pkg, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hone.label(pkg), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val why = when {
                                pkg in compiling -> null
                                pkg in debugBuilds -> "Android never compiles debug builds"
                                hone.failedCompile[pkg] == "rejected" -> hone.compileDetail[pkg]?.let { "Android's compiler: $it" } ?: "Android's compiler refused its code"
                                hone.failedCompile[pkg] == "play" -> "Runs from a file Play supplied; Android keeps it"
                                pkg in hone.failedCompile -> "Android gave no reason"
                                else -> null
                            }
                            if (why != null) Text(why, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        if (pkg in compiling) {
                            LoadingIndicator(Modifier.size(40.dp))
                        } else if (pkg in debugBuilds || pkg in hone.failedCompile) {
                            Text(
                                when {
                                    pkg in debugBuilds -> "Debug build"
                                    hone.failedCompile[pkg] == "rejected" -> "Faulty code"
                                    hone.failedCompile[pkg] == "play" -> "Play's file"
                                    else -> "Not compiled"
                                },
                                style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        compiling.add(pkg)
                                        val r = runCatching { hone.compile(pkg) }.getOrNull()
                                        compiling.remove(pkg)
                                        say(
                                            when {
                                                r?.ok == true -> "${hone.label(pkg)} compiled for speed"
                                                r != null -> "${hone.label(pkg)} stayed ${r.status.ifEmpty { "basic" }}"
                                                else -> "Could not compile ${hone.label(pkg)}"
                                            },
                                        )
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
    extra: @Composable () -> Unit = {},
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
        extra()
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Working" else "Run")
        }
    }
}
