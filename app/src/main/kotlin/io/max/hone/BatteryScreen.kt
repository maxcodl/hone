@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.max.hone

import android.Manifest
import android.os.BatteryManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun BatteryScreen(hone: Hone, pad: PaddingValues, say: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(readBattery(context)) }
    var info by remember { mutableStateOf<BatteryInfo?>(null) }
    var history by remember { mutableStateOf<BatteryHistory?>(null) }
    var monitoring by remember { mutableStateOf(BatteryPrefs.monitoring(context)) }
    var alerts by remember { mutableStateOf(BatteryPrefs.alerts(context)) }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            if (info == null) info = runCatching { hone.call<BatteryInfo>("battery-info") }.getOrNull()
            var tick = 0
            while (true) {
                now = readBattery(context)
                if (tick % 15 == 0) history = runCatching { batteryHistory(context) }.getOrNull()
                tick++
                delay(2000)
            }
        }
    }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun setMonitoring(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        BatteryPrefs.setMonitoring(context, on)
        monitoring = on
        say(if (on) "Monitoring on. History builds from now." else "Monitoring off")
    }
    fun update(a: AlertSettings) {
        alerts = a
        BatteryPrefs.setAlerts(context, a)
        if (!monitoring && listOf(a.chargeLimit, a.slowCharge, a.low, a.fastDrain, a.hot).any { it.on }) setMonitoring(true)
    }

    val healthWord = when (now.health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Hot"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Overvolt"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Fault"
        else -> "Unknown"
    }
    val cycles = info?.cycles?.toInt() ?: now.cycles
    val source = when (now.plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> "Battery"
    }

    LazyColumn(contentPadding = pad + 16.dp, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hero") {
            HeroCard(MaterialShapes.Pill, MaterialShapes.Cookie9Sided, Modifier.enter(0)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (now.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, null, Modifier.size(40.dp), tint = cs.onPrimaryContainer)
                    BigValue(healthWord, MaterialTheme.typography.displayMediumEmphasized, color = cs.onPrimaryContainer)
                    RowLabel("Battery health")
                    val pct = info?.healthPercent
                    if (pct != null) {
                        Text(
                            "${fmt(pct)}% of new capacity  ·  ${info?.chargeFullMah?.toInt()} of ${info?.designMah?.toInt()} mAh",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        item(key = "row1") {
            Row(Modifier.height(IntrinsicSize.Min).enter(1), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile("Status", if (now.charging) (if (now.status == BatteryManager.BATTERY_STATUS_FULL) "Full" else "Charging") else "On battery", Modifier.weight(1f).fillMaxHeight()) {
                    Icon(if (now.charging) Icons.Rounded.Bolt else Icons.Rounded.BatteryStd, null, Modifier.size(28.dp), tint = cs.primary)
                }
                Tile("Level", "${now.level}%", Modifier.weight(1f).fillMaxHeight()) {
                    GradientBar(now.level / 100f, listOf(cs.tertiary, if (now.level <= 15) cs.error else cs.primary), cs.surfaceContainerHighest)
                }
            }
        }
        item(key = "row2") {
            Row(Modifier.height(IntrinsicSize.Min).enter(2), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile("Source", source, Modifier.weight(1f).fillMaxHeight()) {
                    Icon(Icons.Rounded.Power, null, Modifier.size(28.dp), tint = cs.primary)
                }
                Tile("Cycles", cycles?.toString() ?: "Hidden", Modifier.weight(1f).fillMaxHeight()) {
                    Text(if (cycles == null) "This phone does not share it" else "Full charges so far", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        item(key = "row3") {
            Row(Modifier.height(IntrinsicSize.Min).enter(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Type", info?.technology ?: now.technology ?: "?", Modifier.weight(1f).fillMaxHeight(), small = true)
                Tile("Temp", "${fmt(now.celsius)}°", Modifier.weight(1f).fillMaxHeight(), small = true)
                Tile("Voltage", "${now.mv} mV", Modifier.weight(1f).fillMaxHeight(), small = true)
            }
        }
        item(key = "current") {
            SectionCard(Modifier.enter(4)) {
                CardHeader(Icons.Rounded.Bolt, "Current", if (now.charging) "Flowing into the battery" else "Being drawn from the battery", MaterialShapes.Burst)
                Row(verticalAlignment = Alignment.Bottom) {
                    BigValue("${abs(now.ma)} mA", MaterialTheme.typography.headlineMediumEmphasized)
                    Spacer(Modifier.width(12.dp))
                    Text("${fmt(now.watts)} W", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                }
            }
        }

        item(key = "monitor") {
            SectionCard(Modifier.enter(5)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowLabel("Monitoring")
                        Text(
                            "Records battery history, charge sessions and charger quality, and sends your alerts. Shows a quiet notification while on.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = monitoring, onCheckedChange = ::setMonitoring)
                }
            }
        }

        val h = history
        if (h != null && h.samples > 0) {
            item(key = "chartTitle") { SectionTitle("Last 24 hours") }
            item(key = "chart") {
                SectionCard {
                    LevelChart(h.points, Modifier.fillMaxWidth().height(180.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Used ${fmt(h.used24h, 0)}%")
                        h.screenOnPerHour?.let { Pill("Screen on ${fmt(it)}%/h") }
                        h.screenOffPerHour?.let { Pill("Screen off ${fmt(it)}%/h") }
                    }
                }
            }
            item(key = "chargersTitle") { SectionTitle("Charger quality") }
            item(key = "chargers") {
                SectionCard {
                    if (h.chargers.isEmpty()) Text("No charging recorded yet. Plug in with monitoring on.", color = cs.onSurfaceVariant)
                    h.chargers.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                RowLabel(c.source)
                                Text("${c.avgMa} mA average  ·  ${c.sessions} sessions", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Pill(c.verdict, if (c.verdict == "Slow") cs.error else cs.primary)
                        }
                    }
                }
            }
            item(key = "sessionsTitle") { SectionTitle("Recent charges") }
            if (h.sessions.isEmpty()) item(key = "noSessions") {
                SectionCard { Text("No sessions recorded yet.", color = cs.onSurfaceVariant) }
            }
            items(h.sessions, key = { "s:" + it.start }) { s ->
                RowCard {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RowLabel("${fmt(s.from, 0)}% to ${fmt(s.to, 0)}%  ·  ${s.source}")
                            Text(
                                "${time(s.start)}  ·  ${s.minutes.toInt()} min  ·  ${s.avgMa} mA  ·  ${fmt(s.watts)} W  ·  max ${fmt(s.maxCelsius)}°",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "alertsTitle") { SectionTitle("Alerts") }
        item(key = "alerts") {
            SectionCard {
                AlertRow("Charge limit", "Tell me when charging reaches", alerts.chargeLimit, "%", 50.0..100.0, 5.0) { update(alerts.copy(chargeLimit = it)) }
                AlertRow("Slow charging", "Tell me when charging stays under", alerts.slowCharge, " mA", 200.0..1500.0, 100.0) { update(alerts.copy(slowCharge = it)) }
                AlertRow("Low battery", "Tell me when the battery drops to", alerts.low, "%", 5.0..50.0, 5.0) { update(alerts.copy(low = it)) }
                AlertRow("Fast drain", "Screen off and losing more than", alerts.fastDrain, "%/h", 2.0..20.0, 1.0) { update(alerts.copy(fastDrain = it)) }
                AlertRow("Hot battery", "Tell me when the battery reaches", alerts.hot, "°C", 35.0..50.0, 1.0) { update(alerts.copy(hot = it)) }
                if (!monitoring) Text("Alerts need monitoring; turning one on starts it.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** A small labelled reading, the building block of the battery grid. */
@Composable
private fun Tile(label: String, value: String, modifier: Modifier, small: Boolean = false, extra: @Composable () -> Unit = {}) {
    SectionCard(modifier) {
        RowLabel(label)
        BigValue(value, if (small) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmallEmphasized)
        Spacer(Modifier.weight(1f))
        extra()
    }
}

/** One alert: switch plus a stepper for its threshold. */
@Composable
private fun AlertRow(
    title: String,
    detail: String,
    setting: AlertSetting,
    unit: String,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    onChange: (AlertSetting) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowLabel(title)
                Text("$detail ${fmt(setting.value, 0)}$unit", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = setting.on, onCheckedChange = { onChange(setting.copy(on = it)) })
        }
        if (setting.on) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalIconButton(
                onClick = { onChange(setting.copy(value = (setting.value - step).coerceIn(range))) },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.Remove, "Lower") }
            Text("${fmt(setting.value, 0)}$unit", style = MaterialTheme.typography.titleMedium)
            FilledTonalIconButton(
                onClick = { onChange(setting.copy(value = (setting.value + step).coerceIn(range))) },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.Add, "Higher") }
        }
    }
}

/** Battery level over the last day: a line with soft fill, or dots in the Dot look. */
@Composable
private fun LevelChart(points: List<List<Double>>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val mono = LocalLook.current.mono
    val now = System.currentTimeMillis() / 1000.0
    Canvas(modifier) {
        val grid = cs.outlineVariant
        for (f in listOf(0f, 0.5f, 1f)) drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx())
        if (points.isEmpty()) return@Canvas
        fun x(ts: Double) = ((ts - (now - 86_400)) / 86_400 * size.width).toFloat().coerceIn(0f, size.width)
        fun y(l: Double) = (size.height * (1 - l / 100)).toFloat()
        if (mono) {
            points.forEach { p -> drawCircle(cs.onSurface, 2.dp.toPx(), Offset(x(p[0]), y(p[1]))) }
            return@Canvas
        }
        val line = Path().apply {
            moveTo(x(points[0][0]), y(points[0][1]))
            points.drop(1).forEach { lineTo(x(it[0]), y(it[1])) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(points.last()[0]), size.height)
            lineTo(x(points[0][0]), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.3f), cs.primary.copy(alpha = 0f))))
        drawPath(line, cs.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

private val timeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
private fun time(seconds: Long) = timeFormat.format(Date(seconds * 1000))
