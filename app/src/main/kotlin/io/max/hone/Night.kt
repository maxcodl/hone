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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Overnight upkeep while the phone charges and sits idle: deletes trash older than 30 days (when
 * auto-empty is on) and, with Shizuku, runs storage upkeep and compiles apps still basic. Leaves a
 * silent summary for the morning.
 */
class NightJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 7..22) return false // charging during the day is not "overnight"; the next period tries again
        work = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val hone = Hone.get(applicationContext)
            val done = mutableListOf<String>()
            runCatching { hone.expireTrash() }.getOrNull()?.takeIf { it.freed > 0 }?.let { done += "deleted ${bytes(it.freed)} of old trash" }
            // A fresh process hears from Shizuku a moment after it starts.
            repeat(20) { if (!hone.full) delay(500) }
            if (hone.full) {
                if (runCatching { hone.raw("maintenance") }.isSuccess) done += "ran storage upkeep"
                hone.refreshDexopt().join()
                val started = System.currentTimeMillis()
                var compiled = 0
                for (pkg in hone.compilable()) {
                    if (System.currentTimeMillis() - started > 7 * 60_000) break // jobs get about ten minutes
                    if (runCatching { hone.compile(pkg).ok }.getOrDefault(false)) compiled++
                }
                if (compiled > 0) done += "compiled $compiled apps for speed"
            }
            val text = if (done.isEmpty()) "Nothing needed doing." else done.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
            NightPrefs.setLast(applicationContext, text)
            if (done.isNotEmpty()) summary(text)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }

    private fun summary(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overnight upkeep", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            3,
            Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_hone)
                .setContentTitle("Overnight upkeep").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    private companion object {
        const val CHANNEL = "night"
    }
}

object NightPrefs {
    private const val JOB = 42
    private fun prefs(c: Context) = c.getSharedPreferences("night", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("on", false)
    fun last(c: Context): String? = prefs(c).getString("last", null)
    fun setLast(c: Context, text: String) = prefs(c).edit().putString("last", text).apply()

    fun set(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        val js = c.getSystemService(JobScheduler::class.java)
        if (!on) return js.cancel(JOB)
        js.schedule(
            JobInfo.Builder(JOB, ComponentName(c, NightJob::class.java))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPeriodic(3 * 3_600_000L)
                .setPersisted(true)
                .build(),
        )
    }
}
