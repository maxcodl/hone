package io.max.hone

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Bytes moved, split by direction and by network. */
data class Traffic(val rx: Long = 0, val tx: Long = 0, val wifi: Long = 0, val mobile: Long = 0) {
    val total get() = rx + tx
    operator fun plus(o: Traffic) = Traffic(rx + o.rx, tx + o.tx, wifi + o.wifi, mobile + o.mobile)
}

/** One app (or one of Android's shared groups) and what it moved. */
data class NetApp(val key: String, val pkg: String?, val label: String, val use: Traffic)

data class NetBar(val start: Long, val end: Long, val use: Traffic)

/** Which networks to count: 0 both, 1 Wi-Fi, 2 mobile. */
private fun types(net: Int) = when (net) {
    1 -> listOf(ConnectivityManager.TYPE_WIFI)
    2 -> listOf(ConnectivityManager.TYPE_MOBILE)
    else -> listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)
}

private fun Traffic.of(type: Int, rx: Long, tx: Long) =
    this + Traffic(rx, tx, if (type == ConnectivityManager.TYPE_WIFI) rx + tx else 0, if (type == ConnectivityManager.TYPE_MOBILE) rx + tx else 0)

/** Android's own data-use records (usage access, no shell). Per-app records come in blocks of about two hours. */
object Net {
    private fun nsm(context: Context) = context.getSystemService(NetworkStatsManager::class.java)

    fun byUid(context: Context, from: Long, to: Long, net: Int = 0): Map<Int, Traffic> {
        val out = HashMap<Int, Traffic>()
        for (type in types(net)) {
            val stats = runCatching { nsm(context).querySummary(type, null, from, to) }.getOrNull() ?: continue
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                out[b.uid] = (out[b.uid] ?: Traffic()).of(type, b.rxBytes, b.txBytes)
            }
            stats.close()
        }
        return out
    }

    /** Whole-phone total for one stretch; these records are kept by the hour. */
    fun device(context: Context, from: Long, to: Long, net: Int): Traffic = types(net).fold(Traffic()) { t, type ->
        val b = runCatching { nsm(context).querySummaryForDevice(type, null, from, to) }.getOrNull() ?: return@fold t
        t.of(type, b.rxBytes, b.txBytes)
    }

    suspend fun apps(context: Context, hone: Hone, from: Long, to: Long, net: Int): List<NetApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        byUid(context, from, to, net).entries
            .groupBy { (uid, _) ->
                when {
                    uid == NetworkStats.Bucket.UID_REMOVED -> "Removed apps"
                    uid == NetworkStats.Bucket.UID_TETHERING -> "Hotspot"
                    uid < android.os.Process.FIRST_APPLICATION_UID -> "Android system"
                    else -> pm.getPackagesForUid(uid)?.let { pkgs -> if (pkgs.size == 1) pkgs[0] else "Android system" } ?: "Removed apps"
                }
            }
            .map { (key, list) ->
                val traffic = list.fold(Traffic()) { a, e -> a + e.value }
                val pkg = key.takeIf { '.' in it }
                NetApp(key, pkg, pkg?.let { hone.label(it) } ?: key, traffic)
            }
            .filter { it.use.total > 0 }
            .sortedByDescending { it.use.total }
    }

    /** Bars for a period: 0 day by the hour, 1 week by the day, 2 month by the day. [back] steps into the past. */
    fun range(period: Int, back: Int): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when (period) {
            0 -> c.add(Calendar.DAY_OF_YEAR, -back)
            1 -> c.add(Calendar.DAY_OF_YEAR, -6 - 7 * back)
            else -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, -back) }
        }
        val start = c.timeInMillis
        when (period) {
            0 -> c.add(Calendar.DAY_OF_YEAR, 1)
            1 -> c.add(Calendar.DAY_OF_YEAR, 7)
            else -> c.add(Calendar.MONTH, 1)
        }
        return start to c.timeInMillis
    }

    suspend fun bars(context: Context, period: Int, back: Int, net: Int): List<NetBar> = withContext(Dispatchers.IO) {
        val (start, end) = range(period, back)
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val now = System.currentTimeMillis()
        buildList {
            while (c.timeInMillis < end) {
                val s = c.timeInMillis
                c.add(if (period == 0) Calendar.HOUR_OF_DAY else Calendar.DAY_OF_YEAR, 1)
                val e = c.timeInMillis
                add(NetBar(s, e, if (s > now) Traffic() else device(context, s, minOf(e, now), net)))
            }
        }
    }

    /** Bytes the whole phone has moved since boot, read every second for the live line. */
    fun totalNow(): Long = TrafficStats.getTotalRxBytes().coerceAtLeast(0) + TrafficStats.getTotalTxBytes().coerceAtLeast(0)
}
