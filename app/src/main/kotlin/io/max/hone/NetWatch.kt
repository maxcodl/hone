package io.max.hone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [NetWatch.check] every 15 minutes while new-app alerts are on. */
class NetWatchJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { NetWatch.check(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}

/**
 * Tells when an app moves data for the first time. Reads Android's own data-use records (usage
 * access, no shell); those come in blocks and are saved now and then, so an alert can come an hour
 * or two after the app first went online.
 */
object NetWatch {
    private const val JOB = 43
    private const val CHANNEL = "firstnet"
    private const val BLOCK = 2 * 3_600_000L

    private fun prefs(c: Context) = c.getSharedPreferences("netwatch", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("on", false)

    /** Turning it on takes every app already in the records as known, so only apps new from now on alert. */
    fun set(c: Context, on: Boolean) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (!on) {
            prefs(c).edit().putBoolean("on", false).apply()
            return js.cancel(JOB)
        }
        val now = System.currentTimeMillis()
        val seen = Net.byUid(c, 0, now + BLOCK).keys.map(Int::toString).toSet()
        prefs(c).edit().putBoolean("on", true).putStringSet("seen", seen).putLong("last", now).apply()
        js.schedule(
            JobInfo.Builder(JOB, ComponentName(c, NetWatchJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build(),
        )
    }

    /** Alerts for every app UID that shows traffic now and never did before. */
    fun check(c: Context) {
        if (!on(c)) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        // From the block the last look fell in, so records saved late are not missed.
        val from = (p.getLong("last", now) - BLOCK) / BLOCK * BLOCK
        val seen = p.getStringSet("seen", emptySet()).orEmpty()
        val fresh = Net.byUid(c, from, now + BLOCK).filter { (uid, t) -> t.total > 0 && uid.toString() !in seen }
        p.edit().putStringSet("seen", seen + fresh.keys.map(Int::toString)).putLong("last", now).apply()
        val pm = c.packageManager
        fresh.forEach { (uid, t) ->
            if (uid < Process.FIRST_APPLICATION_UID) return@forEach
            val pkg = pm.getPackagesForUid(uid)?.singleOrNull() ?: return@forEach
            notify(c, uid, pkg, t)
        }
    }

    private fun notify(c: Context, uid: Int, pkg: String, t: Traffic) {
        val pm = c.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
        val label = info.loadLabel(pm).toString()
        val via = listOfNotNull("Wi-Fi".takeIf { t.wifi > 0 }, "mobile data".takeIf { t.mobile > 0 }).joinToString(" and ")
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New app online", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, uid, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = runCatching { Icon.createWithBitmap(info.loadIcon(pm).toBitmap(128, 128)) }.getOrNull()
        nm.notify(
            "firstnet", uid,
            Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_hone)
                .setContentTitle("First network activity")
                .setContentText("$label went online for the first time" + if (via.isNotEmpty()) ", over $via." else ".")
                .apply { if (icon != null) setLargeIcon(icon) }
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}
