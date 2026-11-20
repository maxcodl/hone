@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- privacy ----

private val opNames = linkedMapOf("CAMERA" to "Camera", "RECORD_AUDIO" to "Microphone", "FINE_LOCATION" to "Location", "COARSE_LOCATION" to "Location", "READ_CLIPBOARD" to "Clipboard")

fun agoText(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}

/**
 * Apps that used the camera, microphone, location or clipboard in the last week, newest first, with
 * one tap to take that access away. Taking it away is undone by the app simply asking again.
 */
fun LazyListScope.privacyItems(hone: Hone, privacy: Privacy?, busy: String?, onRevoke: (PrivacyApp, String) -> Unit) {
    if (!hone.full) {
        item(key = "privNeeds") { NeedsShizuku(hone, "Needs Shizuku", "Android shares who used the camera, microphone, location and clipboard only with the shell.") }
        return
    }
    if (privacy == null) {
        item(key = "privLoading") { Loading(PaddingValues(32.dp), "Reading Android's access records") }
        return
    }
    item(key = "privHead") {
        Text(
            "Used in the last 7 days. Removing an access takes the permission away; the app asks again if it needs it.",
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val shown = privacy.apps.sortedWith(compareBy<PrivacyApp>({ !it.user }, { a -> a.ops.minOf { it.agoMs } }))
    if (shown.isEmpty()) item(key = "privEmpty") { Text("No app used them in the last week.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(shown, key = { "priv:" + it.pkg }) { app ->
        // Location comes in two flavours; one line and one button for both.
        val uses = app.ops.groupBy { opNames[it.op] ?: it.op }.mapValues { (_, v) -> v.minBy { it.agoMs } }
        RowCard(Modifier.animateItem()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(hone, app.pkg, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hone.label(app.pkg), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(uses.entries.joinToString("  ·  ") { (name, u) -> "$name ${agoText(u.agoMs)}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy == app.pkg) LoadingIndicator(Modifier.size(36.dp))
                }
                if (app.user) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uses.forEach { (name, u) ->
                        OutlinedButton(onClick = { onRevoke(app, u.op) }, enabled = busy == null, shapes = ButtonDefaults.shapes()) { Text("Remove ${name.lowercase()}") }
                    }
                } else Text("Part of the phone; left as it is.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ---- start at boot ----

data class BootApp(val pkg: String, val label: String, val components: List<String>, val on: Boolean)

private val bootActions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON")

/** Apps you installed that ask Android to start them after a restart, read from their own manifests. */
suspend fun bootApps(context: Context): List<BootApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val comps = LinkedHashMap<String, MutableMap<String, Boolean>>() // pkg -> component -> on
    for (action in bootActions) {
        @Suppress("DEPRECATION")
        pm.queryBroadcastReceivers(Intent(action), PackageManager.MATCH_DISABLED_COMPONENTS).forEach { r ->
            val ai = r.activityInfo
            if (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 || ai.packageName == context.packageName) return@forEach
            val name = "${ai.packageName}/${ai.name}"
            val on = when (pm.getComponentEnabledSetting(ComponentName(ai.packageName, ai.name))) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> ai.enabled
            }
            comps.getOrPut(ai.packageName) { linkedMapOf() }[name] = on
        }
    }
    // Where Android refuses the per-component switch, the engine restricts background use instead; count that as off.
    val hone = Hone.get(context)
    val restricted = if (hone.full) runCatching { hone.call<Running>("bg-restricted").packages.toSet() }.getOrDefault(emptySet()) else emptySet()
    comps.map { (pkg, c) -> BootApp(pkg, hone.label(pkg), c.keys.toList(), c.values.any { it } && pkg !in restricted) }.sortedBy { it.label.lowercase() }
}

fun LazyListScope.startupItems(hone: Hone, apps: List<BootApp>?, busy: String?, onSet: (BootApp, Boolean) -> Unit) {
    if (!hone.full) {
        item(key = "bootNeeds") { NeedsShizuku(hone, "Needs Shizuku", "Turning off an app's start at boot takes the shell. Without it, use the app's own settings.") }
        return
    }
    if (apps == null) {
        item(key = "bootLoading") { Loading(PaddingValues(32.dp), "Reading which apps start at boot") }
        return
    }
    item(key = "bootHead") {
        Text(
            "Apps that start themselves after a restart. Turning one off stops that (on Android 14 and later, by keeping it from running in the background); it still runs when you open it. Its notifications and reminders may wait until you do.",
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (apps.isEmpty()) item(key = "bootEmpty") { Text("None of your apps start at boot.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(apps, key = { "boot:" + it.pkg }) { app ->
        RowCard(Modifier.animateItem()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(hone, app.pkg, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(if (app.on) "Starts at boot" else "Stays off until opened", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy == app.pkg) LoadingIndicator(Modifier.size(36.dp))
                else Switch(checked = app.on, onCheckedChange = { onSet(app, it) }, enabled = busy == null)
            }
        }
    }
}
