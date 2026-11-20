package io.max.hone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

@Serializable data class BatteryInfo(
    val cycles: Double? = null,
    val technology: String? = null,
    val chargeFullMah: Double? = null,
    val designMah: Double? = null,
    val healthPercent: Double? = null,
)
@Serializable data class ChargeSession(
    val start: Long, val end: Long, val from: Double, val to: Double, val source: String,
    val minutes: Double = 0.0, val avgMa: Long = 0, val watts: Double = 0.0, val perHour: Double = 0.0, val maxCelsius: Double = 0.0,
)
@Serializable data class ChargerVerdict(val source: String, val avgMa: Long, val sessions: Int, val verdict: String)
@Serializable data class BatteryHistory(
    val points: List<List<Double>> = emptyList(),
    val sessions: List<ChargeSession> = emptyList(),
    val chargers: List<ChargerVerdict> = emptyList(),
    val used24h: Double = 0.0,
    val screenOnPerHour: Double? = null,
    val screenOffPerHour: Double? = null,
    val since: Long? = null,
    val samples: Int = 0,
)
@Serializable data class Notify(val id: String, val title: String, val text: String)
@Serializable data class LogResult(val notify: List<Notify> = emptyList())

/** One alert's switch and threshold, as the engine reads them from alerts.json. */
@Serializable data class AlertSetting(val on: Boolean = false, val value: Double = 0.0)
@Serializable data class AlertSettings(
    val chargeLimit: AlertSetting = AlertSetting(false, 80.0),
    val slowCharge: AlertSetting = AlertSetting(false, 500.0),
    val low: AlertSetting = AlertSetting(false, 20.0),
    val fastDrain: AlertSetting = AlertSetting(false, 8.0),
    val hot: AlertSetting = AlertSetting(false, 40.0),
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

data class BatteryNow(
    val level: Int, val status: Int, val plugged: Int, val celsius: Double, val mv: Int, val ma: Int,
    val health: Int, val technology: String?, val cycles: Int?,
) {
    val charging get() = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val watts get() = abs(ma) * mv / 1_000_000.0
}

/** Current battery state from Android itself; works without Shizuku. */
fun readBattery(context: Context): BatteryNow {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val bm = context.getSystemService(BatteryManager::class.java)
    // Most phones report microamps, some milliamps; nothing draws under 20 mA while measured, so tell them apart by size.
    val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val ma = if (abs(raw) > 20_000) (raw / 1000).toInt() else raw.toInt()
    val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
    return BatteryNow(
        level = level,
        status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0,
        plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
        celsius = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
        mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
        ma = ma,
        health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1) ?: 1,
        technology = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
        cycles = i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 },
    )
}

/** Runs the engine as Hone itself, for work on Hone's own files. */
suspend fun runLocalEngine(context: Context, vararg args: String): String = withContext(Dispatchers.IO) {
    val engine = context.applicationInfo.nativeLibraryDir + "/libhone.so"
    val p = ProcessBuilder(engine, *args).redirectError(File("/dev/null")).start()
    p.inputStream.bufferedReader().readText().also { p.waitFor() }
}

suspend fun batteryHistory(context: Context): BatteryHistory =
    json.decodeFromString(runLocalEngine(context, "battery-history", context.filesDir.path, (System.currentTimeMillis() / 1000).toString()))

/** Monitoring switch and alert settings, kept in prefs and mirrored to alerts.json for the engine. */
object BatteryPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("battery", Context.MODE_PRIVATE)
    fun monitoring(c: Context) = prefs(c).getBoolean("monitor", false)
    fun setMonitoring(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("monitor", on).apply()
        val intent = Intent(c, MonitorService::class.java)
        if (on) c.startForegroundService(intent) else c.stopService(intent)
    }
    fun alerts(c: Context): AlertSettings =
        runCatching { json.decodeFromString<AlertSettings>(File(c.filesDir, "alerts.json").readText()) }.getOrDefault(AlertSettings())
    fun setAlerts(c: Context, a: AlertSettings) = File(c.filesDir, "alerts.json").writeText(json.encodeToString(a))
}

/**
 * Keeps battery history while monitoring is on: logs each change (and once a minute) through the
 * engine, and shows the alerts it returns. A quiet notification keeps it alive.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastLog = 0L
    private var lastLevel = -1
    private var lastPlugged = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = log(false)
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MONITOR, "Battery monitoring", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Battery alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, MONITOR)
            .setSmallIcon(R.drawable.ic_stat_hone)
            .setContentTitle("Watching the battery")
            .setContentText("Recording history and checking your alerts")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        scope.launch {
            while (true) {
                delay(60_000)
                log(true)
            }
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Battery broadcasts come often; log on a real change or after a minute. */
    private fun log(tick: Boolean) {
        val b = readBattery(this)
        val now = System.currentTimeMillis() / 1000
        val changed = b.level != lastLevel || b.plugged != lastPlugged
        if (!changed && !tick && now - lastLog < 60) return
        lastLog = now
        lastLevel = b.level
        lastPlugged = b.plugged
        val screen = getSystemService(PowerManager::class.java).isInteractive
        scope.launch {
            val out = runCatching {
                runLocalEngine(
                    this@MonitorService, "battery-log", filesDir.path, now.toString(), b.level.toString(), b.status.toString(),
                    b.plugged.toString(), b.celsius.toString(), b.mv.toString(), b.ma.toString(), if (screen) "1" else "0",
                )
            }.getOrNull() ?: return@launch
            val result = runCatching { json.decodeFromString<LogResult>(out) }.getOrNull() ?: return@launch
            val nm = getSystemService(NotificationManager::class.java)
            result.notify.forEach { a ->
                nm.notify(
                    a.id.hashCode(),
                    Notification.Builder(this@MonitorService, ALERTS)
                        .setSmallIcon(R.drawable.ic_stat_hone)
                        .setContentTitle(a.title).setContentText(a.text)
                        .setContentIntent(openApp()).setAutoCancel(true)
                        .build(),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val MONITOR = "monitor"
        const val ALERTS = "alerts"
    }
}

/** Starts monitoring again after a restart, if it was on, and clears network blocks Android has dropped. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (BatteryPrefs.monitoring(c)) c.startForegroundService(Intent(c, MonitorService::class.java))
        // Android drops per-app network blocks on restart; forget them too so the list stays true.
        c.getSharedPreferences("net", Context.MODE_PRIVATE).edit().remove("blocked").apply()
    }
}
