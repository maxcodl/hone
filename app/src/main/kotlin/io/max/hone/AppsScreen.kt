@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val filters = listOf("Safe", "Caution", "Removed")
private val modes = listOf("Debloat", "Installed", "Privacy", "Startup")

@Composable
fun AppsScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit) {
    var pkgs by remember { mutableStateOf<List<Pkg>?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pkg?>(null) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AppSort.Name) }
    var open by remember { mutableStateOf<InstalledApp?>(null) }
    LaunchedEffect(mode) { if (mode == 1 && installed == null) installed = installedApps(hone, context) }
    var privacy by remember { mutableStateOf<Privacy?>(null) }
    var boot by remember { mutableStateOf<List<BootApp>?>(null) }
    var revoke by remember { mutableStateOf<Pair<PrivacyApp, String>?>(null) }
    LaunchedEffect(mode, hone.full) {
        if (!hone.full) return@LaunchedEffect
        if (mode == 2 && privacy == null) privacy = runCatching { hone.stillAllowed(hone.call<Privacy>("privacy")) }.onFailure { say("Could not read access records: ${it.message}") }.getOrNull() ?: Privacy()
        if (mode == 3 && boot == null) boot = runCatching { bootApps(context) }.getOrDefault(emptyList())
    }
    revoke?.let { (app, op) ->
        val what = when (op) { "CAMERA" -> "camera"; "RECORD_AUDIO" -> "microphone"; "READ_CLIPBOARD" -> "clipboard"; else -> "location" }
        Confirm(
            "Remove ${hone.label(app.pkg)}'s $what access?", "It can ask for it again next time it needs it.", "Remove",
            onConfirm = {
                scope.launch {
                    working = app.pkg
                    val r = runCatching { hone.call<Changed>("revoke", app.pkg, op) }.getOrNull()
                    privacy = runCatching { hone.stillAllowed(hone.call<Privacy>("privacy")) }.getOrNull() ?: privacy
                    working = null
                    say(if (r?.ok == true) "${hone.label(app.pkg)} no longer has $what access" else "Could not remove it: ${r?.output?.take(80).orEmpty()}")
                }
            },
            onDismiss = { revoke = null },
        )
    }

    suspend fun reload() {
        pkgs = runCatching { hone.debloatList() }.onFailure { say("Could not list apps: ${it.message}") }.getOrNull() ?: pkgs
    }
    LaunchedEffect(Unit) { reload() }

    fun change(p: Pkg, remove: Boolean) = scope.launch {
        working = p.pkg
        val r = runCatching { hone.call<Changed>(if (remove) "remove" else "restore", p.pkg) }
        reload()
        working = null
        val name = hone.label(p.pkg)
        say(
            r.map { if (it.ok) (if (remove) "$name removed" else "$name restored") else it.output.ifBlank { "No change" } }
                .getOrElse { "Failed: ${it.message}" },
        )
    }

    confirm?.let { p ->
        Confirm(
            "Remove ${hone.label(p.pkg)}?", "${p.note}. You can restore it from the Removed list any time.", "Remove",
            onConfirm = { change(p, true) }, onDismiss = { confirm = null },
        )
    }

    open?.let { app ->
        AppSheet(hone, app, say, onClose = { open = null }, onChanged = {
            scope.launch {
                installed = installedApps(hone, context)
                open = installed?.firstOrNull { it.pkg == app.pkg }
            }
        })
    }

    val list = pkgs ?: return Loading(pad, "Listing preinstalled apps")
    val safe = list.filter { it.tag == "safe" && it.state != "removed" }
    val caution = list.filter { it.tag == "caution" && it.state != "removed" }
    val removed = list.filter { it.state == "removed" }
    val shown = listOf(safe, caution, removed)[filter].sortedBy { hone.label(it.pkg).lowercase() }

    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "hero") {
            HeroCard(MaterialShapes.Pentagon, MaterialShapes.Clover8Leaf, Modifier.enter(0)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Preinstalled extras", style = MaterialTheme.typography.headlineSmallEmphasized, color = cs.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth()) {
                        Stat(safe.size, "safe", cs.primary, Modifier.weight(1f))
                        Stat(caution.size, "caution", cs.tertiary, Modifier.weight(1f))
                        Stat(removed.size, "removed", cs.secondary, Modifier.weight(1f))
                    }
                    Text(
                        if (hone.full) "Removed for your user only and always restorable. Core parts of the phone are never listed."
                        else "Without Shizuku, open an app and tap Disable there. Connect Shizuku to remove and restore in one tap.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer,
                    )
                }
            }
        }
        item(key = "mode") {
            Row(Modifier.fillMaxWidth().enter(1), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                modes.forEachIndexed { i, label ->
                    ToggleButton(
                        checked = mode == i, onCheckedChange = { mode = i }, modifier = Modifier.weight(1f),
                        // Four labels in the Dot monospace do not fit with the default 16dp sides.
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) { Text(label, maxLines = 1, softWrap = false) }
                }
            }
        }
        if (mode == 1) {
            appItems(hone, installed, query, { query = it }, sort, { sort = it }) { open = it }
            return@LazyColumn
        }
        if (mode == 2) {
            privacyItems(hone, privacy, working) { app, op -> revoke = app to op }
            return@LazyColumn
        }
        if (mode == 3) {
            startupItems(hone, boot, working) { app, on ->
                scope.launch {
                    working = app.pkg
                    val r = runCatching { hone.call<Changed>(if (on) "autostart-on" else "autostart-off", app.pkg, *app.components.toTypedArray()) }.getOrNull()
                    boot = runCatching { bootApps(context) }.getOrDefault(boot.orEmpty())
                    working = null
                    say(if (r?.ok == true) "${app.label} ${if (on) "starts at boot again" else "no longer starts at boot"}" else "Could not change it: ${r?.output?.take(80).orEmpty()}")
                }
            }
            return@LazyColumn
        }
        item(key = "filter") {
            Row(Modifier.fillMaxWidth().enter(1), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                filters.forEachIndexed { i, label ->
                    ToggleButton(
                        checked = filter == i,
                        onCheckedChange = { filter = i },
                        modifier = Modifier.weight(1f),
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            filters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) { Text(label) }
                }
            }
        }
        if (shown.isEmpty()) item(key = "empty") {
            Text(
                if (filter == 2) "Nothing removed yet." else "Nothing left here.",
                Modifier.padding(24.dp), color = cs.onSurfaceVariant,
            )
        }
        itemsIndexed(shown, key = { _, p -> p.pkg }) { i, p ->
            RowCard(Modifier.animateItem().enter(i + 2)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(hone, p.pkg, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hone.label(p.pkg), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (p.note.isNotEmpty()) Text(p.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text(p.pkg, style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        // Without the shell, the phone's own App info page can disable most of these.
                        !hone.full -> OutlinedButton(onClick = { hone.openAppInfo(p.pkg) }, shapes = ButtonDefaults.shapes()) {
                            Text(if (p.state == "disabled") "Disabled" else "Open")
                        }
                        working == p.pkg -> LoadingIndicator(Modifier.size(40.dp))
                        p.state == "removed" -> FilledTonalButton(onClick = { change(p, false) }, enabled = working == null, shapes = ButtonDefaults.shapes()) { Text("Restore") }
                        p.tag == "safe" -> FilledTonalButton(onClick = { change(p, true) }, enabled = working == null, shapes = ButtonDefaults.shapes()) { Text("Remove") }
                        else -> OutlinedButton(onClick = { confirm = p }, enabled = working == null, shapes = ButtonDefaults.shapes()) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String, dot: Color, modifier: Modifier) {
    Column(modifier) {
        Counter(n, MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Pill(label, dot, MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
    }
}
