package io.max.hone

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlinx.coroutines.launch

private fun Tile.show(active: Boolean, sub: String?) {
    state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    if (Build.VERSION.SDK_INT >= 29) subtitle = sub
    updateTile()
}

/** Quick Settings: close background apps, keeping the keep-warm list. */
class FreeMemoryTile : TileService() {
    override fun onStartListening() {
        qsTile?.show(false, null)
    }

    override fun onClick() {
        val hone = Hone.get(applicationContext)
        qsTile?.show(true, "Closing apps")
        hone.scope.launch {
            val r = runCatching { hone.freeRam() }.getOrNull()
            qsTile?.show(false, r?.let { "Freed ${bytes(it.freed)}" } ?: "Failed")
        }
    }
}

/** Quick Settings: compile every app still basic, the same run as the Speed tab's button. */
class OptimiseTile : TileService() {
    override fun onStartListening() {
        val hone = Hone.get(applicationContext)
        qsTile?.show(hone.optimizing, if (hone.optimizing) "${hone.batchDone} of ${hone.batchTotal}" else null)
    }

    override fun onClick() {
        val hone = Hone.get(applicationContext)
        if (!hone.full) return qsTile?.show(false, "Needs Shizuku") ?: Unit
        if (hone.optimizing) {
            hone.cancelOptimize()
            return qsTile?.show(false, "Stopped") ?: Unit
        }
        qsTile?.show(true, "Reading apps")
        hone.scope.launch {
            hone.refreshDexopt().join()
            hone.optimizeAll()
            qsTile?.show(hone.optimizing, if (hone.optimizing) "0 of ${hone.batchTotal}" else "All done")
        }
    }
}

/** Home screen widget in the Dot look: battery, free storage and free memory. Tapping opens Hone. */
class HoneWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val b = readBattery(context)
        val disk = StatFs(Environment.getDataDirectory().path)
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val views = RemoteViews(context.packageName, R.layout.widget).apply {
            setTextViewText(R.id.battery, "${b.level}%")
            // Red only as a warning, as in the app.
            setTextColor(R.id.battery, if (b.level <= 15 && !b.charging) 0xFFD71921.toInt() else 0xFFFFFFFF.toInt())
            setTextViewText(R.id.storage, bytes(disk.availableBytes))
            setTextViewText(R.id.memory, bytes(mem.availMem))
            setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
        manager.updateAppWidget(ids, views)
    }

    companion object {
        /** Refreshes every placed widget; called when Hone frees memory or space. */
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, HoneWidget::class.java))
            if (ids.isNotEmpty()) HoneWidget().onUpdate(context, m, ids)
        }
    }
}
