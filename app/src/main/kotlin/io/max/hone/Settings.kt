@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Look, alerts, Shizuku and About on one page, over the tabs; back closes it. */
@Composable
fun SettingsPage(hone: Hone, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.surface)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Sym.ArrowBack, "Back") }
            Spacer(Modifier.width(8.dp))
            if (LocalLook.current.mono) DotText("Settings", 26.sp, cs.onSurface)
            else Text("Settings", style = MaterialTheme.typography.headlineMediumEmphasized)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Look")
            LookSection(hone)
            SectionTitle("Alerts")
            NetAlertSection(hone)
            SectionTitle("Shizuku")
            ShizukuSection(hone)
            SectionTitle("About")
            AboutSection()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LookSection(hone: Hone) {
    val current = hone.looks.look
    val cs = MaterialTheme.colorScheme
    Style.entries.forEach { s ->
        val selected = current.style == s
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (selected) cs.secondaryContainer else cs.surfaceContainer),
            border = if (selected) BorderStroke(2.dp, cs.primary) else null,
            modifier = Modifier.fillMaxWidth().clickable { hone.looks.set(current.copy(style = s)) },
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                // A live sample of the style, drawn by the real components.
                CompositionLocalProvider(LocalLook provides current.copy(style = s)) {
                    Ring(0.7f, Modifier.size(64.dp), 7.dp, listOf(cs.primary, cs.tertiary), cs.surfaceContainerHighest) {
                        BigValue("70", MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(s.blurb, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                RadioButton(selected = selected, onClick = { hone.looks.set(current.copy(style = s)) })
            }
        }
    }
    SwitchCard(
        "AMOLED black", "Pure black everywhere. Saves power on an OLED screen and turns dark mode on.",
        current.amoled,
    ) { hone.looks.set(current.copy(amoled = it)) }
}

@Composable
private fun NetAlertSection(hone: Hone) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(NetWatch.on(context)) }
    var allowed by remember { mutableStateOf(hone.usageAllowed()) }
    var busy by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed = hone.usageAllowed() }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun set(want: Boolean) = scope.launch {
        busy = true
        if (want && !hone.usageAllowed() && hone.full) runCatching { hone.raw("grant-usage", context.packageName) }
        allowed = hone.usageAllowed()
        if (want && !allowed) { busy = false; return@launch hone.openUsageAccess() }
        // Turning on reads every app's records once to learn which are already known.
        withContext(Dispatchers.IO) { NetWatch.set(context, want) }
        on = want
        busy = false
        if (want && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SwitchCard(
        "New app online",
        "A notification when an app sends or receives data for the first time. Hone checks Android's data-use records every 15 minutes, so it can come an hour or two late." +
            if (!allowed) " Needs usage access." else "",
        on, enabled = !busy,
    ) { set(it) }
}

@Composable
private fun ShizukuSection(hone: Hone) {
    SectionCard {
        CardHeader(
            Sym.Bolt,
            if (hone.full) "Connected" else "Not connected",
            if (hone.full) "Hone runs its engine as the shell user: cache cleaning, app optimisation and debloat work."
            else "Hone runs as itself. Start Shizuku and allow Hone for cache cleaning, app optimisation and debloat.",
            MaterialShapes.Sunny,
        )
        if (!hone.full) FilledTonalButton(onClick = { hone.connect() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
            Text(if (hone.access == Access.Ask) "Allow in Shizuku" else "Open Shizuku")
        }
    }
}

/** A titled switch in a card; the whole card toggles. */
@Composable
private fun SwitchCard(title: String, text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard(Modifier.clip(RoundedCornerShape(LocalLook.current.corner)).clickable(enabled = enabled) { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        }
    }
}
