@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What the Battery tab knows about each app's drain, loaded once per visit. */
class DrainState {
    var drain by mutableStateOf<Drain?>(null)
    var restricted by mutableStateOf<Set<String>>(emptySet())
    var failed by mutableStateOf(false)
}

@Composable
fun rememberDrain(hone: Hone): DrainState {
    val s = remember { DrainState() }
    LaunchedEffect(hone.full) {
        if (!hone.full) return@LaunchedEffect
        runCatching { hone.call<Drain>("drain") }.onSuccess { s.drain = it }.onFailure { s.failed = true }
        s.restricted = runCatching { hone.call<Running>("bg-restricted").packages.toSet() }.getOrDefault(emptySet())
    }
    return s
}

/**
 * Battery use by app since the last full charge, and the apps that keep waking the phone while the
 * screen is off. Both come from Android's battery and alarm records, so both need Shizuku.
 */
fun LazyListScope.drainItems(hone: Hone, state: DrainState, say: (String) -> Unit) {
    item(key = "drainTitle") { SectionTitle("Battery use by app") }
    if (!hone.full) {
        item(key = "drainNeeds") { NeedsShizuku(hone, "Needs Shizuku", "Android shares which apps used the battery and woke the phone only with the shell.") }
        return
    }
    val d = state.drain
    if (d == null) {
        item(key = "drainLoading") {
            SectionCard { Text(if (state.failed) "Could not read Android's battery records." else "Reading Android's battery records…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        return
    }
    val users = d.apps.filter { !hone.isSystem(it.pkg) || it.mah >= 5 }
    item(key = "drainList") {
        SectionCard {
            Text(
                if (d.measured) "Since the last full charge. Share of what Android measured, ${d.totalMah.toInt()} mAh in all."
                else "This phone does not estimate battery use per app; awake time and wake-ups below are still real.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val top = users.filter { it.mah > 0 }.take(12)
            if (top.isEmpty()) Text("Nothing stands out.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            top.forEach { a -> DrainRow(hone, state, a, "${fmt(a.pct)}%  ·  ${fmt(a.mah, 0)} mAh" + awake(a), say) }
        }
    }
    val wakers = users.filter { it.wakeups >= 20 || it.awakeMs >= 10 * 60_000 }.sortedByDescending { it.wakeups * 60_000 + it.awakeMs }.take(10)
    item(key = "wakeTitle") { SectionTitle("Wakes the phone") }
    item(key = "wakeList") {
        SectionCard {
            Text(
                "Apps that woke the phone with alarms since it started, or held it awake with the screen off. Restricting background use stops most of it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (wakers.isEmpty()) Text("No app wakes the phone often.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            wakers.forEach { a -> DrainRow(hone, state, a, "${a.wakeups} wake-ups" + awake(a), say) }
        }
    }
}

private fun awake(a: DrainApp) = if (a.awakeMs >= 60_000) "  ·  awake ${duration(a.awakeMs / 1000.0)}" else ""

@Composable
private fun DrainRow(hone: Hone, state: DrainState, a: DrainApp, detail: String, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    val restricted = a.pkg in state.restricted
    val label = hone.label(a.pkg)
    if (ask) Confirm(
        "Restrict $label in the background?", "It stops running when you are not using it. Notifications may come late. Undo here or in the Apps tab.", "Restrict",
        onConfirm = {
            scope.launch {
                busy = true
                val r = runCatching { hone.call<Changed>("bg-restrict", a.pkg) }.getOrNull()
                if (r?.ok == true) state.restricted += a.pkg
                say(if (r?.ok == true) "$label restricted in the background" else "Could not restrict $label")
                busy = false
            }
        },
        onDismiss = { ask = false },
    )
    Row(Modifier.fillMaxWidth().clickable { hone.openAppInfo(a.pkg) }, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(hone, a.pkg, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            busy -> LoadingIndicator(Modifier.size(32.dp))
            restricted -> Pill("Restricted")
            !hone.isSystem(a.pkg) -> OutlinedButton(onClick = { ask = true }, shapes = ButtonDefaults.shapes()) { Text("Restrict") }
        }
    }
}
