package io.max.hone

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.IBinder
import android.os.Process
import android.os.StatFs
import android.util.Log
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

// ---- what the engine prints ----

@Serializable data class Health(
    val score: Double = 100.0,
    val memory: Memory,
    val storage: Disk,
    val load: List<Double> = emptyList(),
    val cpuBusy: Double = 0.0,
    val uptime: Double = 0.0,
    val temps: List<Temp> = emptyList(),
    val thermalStatus: Int = 0,
    val battery: Battery,
    val cpus: List<Cpu> = emptyList(),
    val processes: List<Proc> = emptyList(),
    val display: Display,
    val findings: List<Finding> = emptyList(),
)
@Serializable data class Live(val cpuBusy: Double = 0.0, val cpus: List<Cpu> = emptyList(), val memory: Memory)
@Serializable data class Memory(val total: Long, val available: Long, val swapTotal: Long = 0, val swapFree: Long = 0)
@Serializable data class Disk(val total: Long, val free: Long)
@Serializable data class Temp(val name: String, val celsius: Double)
@Serializable data class Battery(val level: Double, val celsius: Double, val volts: Double, val charging: Boolean, val health: Double)
@Serializable data class Cpu(val cur: Long, val max: Long, val busy: Double = 0.0)
@Serializable data class Proc(val pid: Int, val name: String, val cpu: Double, val rss: Long)
@Serializable data class Display(val peakRefresh: Double, val animation: Double)
@Serializable data class Finding(val level: String, val title: String, val detail: String, val action: String = "")
@Serializable data class Dexopt(
    val counts: Map<String, Int> = emptyMap(),
    val slow: List<String> = emptyList(),
    val compiling: String? = null,
    val status: Map<String, String> = emptyMap(),
)
@Serializable data class Freed(val freed: Long = 0, val free: Long = 0, val available: Long = 0)
@Serializable data class Pkg(val pkg: String, val tag: String, val note: String, val state: String)
@Serializable data class Pkgs(val packages: List<Pkg> = emptyList())
@Serializable data class Changed(val ok: Boolean = false, val output: String = "", val status: String = "")
@Serializable data class Compiled(val results: List<Changed> = emptyList())
@Serializable data class CacheResult(val cleared: Int = 0, val failed: Int = 0, val stuck: Boolean = false, val error: String? = null)

/** What fills the phone, for the storage ring. */
data class Breakdown(val images: Long, val video: Long, val audio: Long, val apps: Long, val other: Long, val free: Long)

@Serializable data class FileItem(val path: String, val bytes: Long = 0, val modified: Long = 0)
@Serializable data class Category(val name: String, val bytes: Long = 0, val count: Long = 0)
@Serializable data class Folder(val path: String, val bytes: Long = 0, val files: Long = 0)
@Serializable data class CleanGroup(val id: String, val title: String, val detail: String, val bytes: Long = 0, val count: Int = 0, val files: List<FileItem> = emptyList())
@Serializable data class Scan(
    val total: Long = 0,
    val files: Long = 0,
    val categories: List<Category> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val largest: List<FileItem> = emptyList(),
    val groups: List<CleanGroup> = emptyList(),
    val trash: Long = 0,
)
@Serializable data class Entry(val path: String, val name: String, val dir: Boolean, val bytes: Long = 0, val items: Long = 0, val modified: Long = 0, val locked: Boolean = false)
@Serializable data class Listing(val path: String = "", val entries: List<Entry> = emptyList(), val error: String? = null)
@Serializable data class TrashError(val path: String = "", val error: String = "")
@Serializable data class Trashed(val moved: Int = 0, val bytes: Long = 0, val gone: Int = 0, val errors: List<TrashError> = emptyList()) {
    fun summary() = buildString {
        append("Moved $moved to trash")
        if (gone > 0) append(", $gone already gone")
        if (errors.isNotEmpty()) append(", ${errors.size} skipped: ${errors.first().error}")
    }
}
@Serializable data class TrashItem(val id: String, val original: String, val bytes: Long = 0, val trashed: Long = 0)
@Serializable data class TrashList(val items: List<TrashItem> = emptyList(), val bytes: Long = 0)
@Serializable data class Restored(val restored: Int = 0)

data class AppSize(val pkg: String, val label: String, val system: Boolean, val app: Long, val data: Long, val cache: Long) {
    val total get() = app + data
}

enum class Access { NoShizuku, Ask, Ready }

const val TAG = "Hone"

/** Talks to the Rust engine through a Shizuku shell service, and keeps app-wide jobs alive across tabs. */
class Hone(private val context: Context) {
    val json = Json { ignoreUnknownKeys = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pm: PackageManager = context.packageManager

    var access by mutableStateOf(readAccess())
        private set

    val looks = LookStore(context)
    private val prefs = context.getSharedPreferences("mode", Context.MODE_PRIVATE)

    /** The user chose to go on without Shizuku; everything that needs the shell is hidden or explained. */
    var limited by mutableStateOf(prefs.getBoolean("limited", false))
        private set

    fun useLimited(on: Boolean) {
        limited = on
        prefs.edit().putBoolean("limited", on).apply()
    }

    /** Shell access through Shizuku is ready. */
    val full get() = access == Access.Ready

    private val binderListener = Shizuku.OnBinderReceivedListener { access = readAccess() }
    private val deadListener = Shizuku.OnBinderDeadListener { service = null; access = readAccess() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> access = readAccess() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    private fun readAccess() = when {
        !Shizuku.pingBinder() -> Access.NoShizuku
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Access.Ready
        else -> Access.Ask
    }

    fun askPermission() = Shizuku.requestPermission(7)

    fun openShizuku() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkg = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }
            .firstOrNull { it.startsWith("moe.shizuku.privileged.api") } ?: "moe.shizuku.privileged.api"
        pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
    }

    // ---- the shell service ----

    private var service: IShell? = null
    private val lock = Mutex()
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .processNameSuffix("engine").daemon(false).version(2)

    private suspend fun shell(): IShell = lock.withLock {
        service?.takeIf { it.asBinder().isBinderAlive } ?: suspendCancellableCoroutine { cont ->
            Shizuku.bindUserService(serviceArgs, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val s = IShell.Stub.asInterface(binder)
                    service = s
                    if (cont.isActive) cont.resume(s)
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }
            })
        }
    }

    private val engine get() = context.applicationInfo.nativeLibraryDir + "/libhone.so"

    /** Runs the engine: as the shell user through Shizuku, or as Hone itself when there is no Shizuku. */
    suspend fun raw(vararg args: String): String = withContext(Dispatchers.IO) {
        try {
            if (full) shell().exec(arrayOf(engine, *args))
            else ProcessBuilder(engine, *args).redirectError(java.io.File("/dev/null")).start().let { p ->
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "engine ${args.firstOrNull()} failed", e)
            throw e
        }
    }

    suspend inline fun <reified T> call(vararg args: String): T {
        val out = raw(*args)
        return try {
            json.decodeFromString<T>(out)
        } catch (e: Exception) {
            val msg = "${args.firstOrNull()}: ${e.message?.take(160)} | output: ${out.take(160).ifBlank { "(empty)" }}"
            Log.w(TAG, msg)
            throw IllegalStateException(msg, e)
        }
    }

    // ---- storage ----

    data class Volume(val name: String, val path: String, val total: Long, val free: Long, val removable: Boolean)

    /** Internal storage and every mounted SD card or USB drive. */
    fun volumes(): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            val dir = (if (android.os.Build.VERSION.SDK_INT >= 30) v.directory else null) ?: return@mapNotNull null
            runCatching {
                val s = StatFs(dir.path)
                Volume(if (v.isPrimary) "Internal storage" else v.getDescription(context), dir.path, s.totalBytes, s.availableBytes, v.isRemovable)
            }.getOrNull()
        }
    }

    fun disk(): Disk = StatFs(Environment.getDataDirectory().path).let { Disk(it.totalBytes, it.availableBytes) }

    fun openUsageAccess() {
        context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun usageAllowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    val deviceName: String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name") ?: android.os.Build.MODEL

    suspend fun appSizes(): List<AppSize> {
        if (!usageAllowed()) {
            if (full) raw("grant-usage", context.packageName) else error("Usage access is off")
        }
        return withContext(Dispatchers.IO) {
            val stats = context.getSystemService(StorageStatsManager::class.java)
            val user = Process.myUserHandle()
            pm.getInstalledApplications(0).chunked(24).map { chunk ->
                async {
                    chunk.mapNotNull { info ->
                        runCatching {
                            val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, user)
                            AppSize(info.packageName, label(info), info.flags and ApplicationInfo.FLAG_SYSTEM != 0, s.appBytes, s.dataBytes, s.cacheBytes)
                        }.getOrNull()
                    }
                }
            }.awaitAll().flatten().sortedByDescending { it.total }
        }
    }

    suspend fun breakdown(apps: List<AppSize>): Breakdown = withContext(Dispatchers.IO) {
        val d = disk()
        val ext = runCatching {
            context.getSystemService(StorageStatsManager::class.java).queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
        }.getOrNull()
        val images = ext?.imageBytes ?: 0
        val video = ext?.videoBytes ?: 0
        val audio = ext?.audioBytes ?: 0
        val appBytes = apps.sumOf { it.total }
        val other = (d.total - d.free - images - video - audio - appBytes).coerceAtLeast(0)
        Breakdown(images, video, audio, appBytes, other, d.free)
    }

    suspend fun clearCache(packages: List<String>): CacheResult = withContext(Dispatchers.IO) {
        val out = shell().clearCache(packages.toTypedArray())
        Log.i(TAG, "clearCache ${packages.size} apps: $out")
        json.decodeFromString(out)
    }

    // ---- readings that work without Shizuku ----

    /**
     * The engine's health reading. Without the shell it cannot ask system services, so battery,
     * heat and display come from Android's own APIs instead.
     */
    suspend fun health(): Health {
        val h = call<Health>("health")
        if (full) return h
        return withContext(Dispatchers.IO) {
            val b = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.let { it.getIntExtra("level", 0) * 100.0 / it.getIntExtra("scale", 100).coerceAtLeast(1) } ?: 0.0
            val battery = Battery(
                level = level,
                celsius = (b?.getIntExtra("temperature", 0) ?: 0) / 10.0,
                volts = (b?.getIntExtra("voltage", 0) ?: 0) / 1000.0,
                charging = b?.getIntExtra("status", 0) == android.os.BatteryManager.BATTERY_STATUS_CHARGING,
                health = (b?.getIntExtra("health", 2) ?: 2).toDouble(),
            )
            val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus else 0
            val display = Display(
                peakRefresh = (context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)?.refreshRate ?: 60f).toDouble(),
                animation = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f).toDouble(),
            )
            val extra = buildList {
                if (thermal >= 2) add(Finding("bad", "Phone is hot", "Android reports it is slowing down to cool off.", ""))
                // 2 is good; 1 means the phone does not report it, which is not a fault.
                if (battery.health > 2.0) add(Finding("warn", "Battery health is not good", "The battery reports a fault or wear.", ""))
            }
            h.copy(
                battery = battery, thermalStatus = thermal, display = display,
                temps = listOf(Temp("BATTERY", battery.celsius)),
                processes = emptyList(),
                findings = h.findings.filter { it.action != "reboot" && !it.title.startsWith("Battery") && !it.title.startsWith("Phone is hot") } + extra,
            )
        }
    }

    /** Asks Android to close every app's background processes; allowed to any app with a normal permission. */
    suspend fun freeRamLocal(): Freed = withContext(Dispatchers.IO) {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        fun avail() = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        pm.getInstalledApplications(0).forEach { if (it.packageName != context.packageName) am.killBackgroundProcesses(it.packageName) }
        delay(1500)
        val after = avail()
        Freed(freed = (after - before).coerceAtLeast(0), available = after)
    }

    /** The removable preinstalled apps with their state on this phone. */
    suspend fun debloatList(): List<Pkg> {
        if (full) return call<Pkgs>("packages").packages
        val tags = call<Pkgs>("tags").packages
        return withContext(Dispatchers.IO) {
            tags.mapNotNull { t ->
                val info = runCatching { pm.getApplicationInfo(t.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull() ?: return@mapNotNull null
                val state = when {
                    info.flags and ApplicationInfo.FLAG_INSTALLED == 0 -> "removed"
                    !info.enabled -> "disabled"
                    else -> "installed"
                }
                t.copy(state = state)
            }
        }
    }

    // ---- files ----

    fun filesAllowed(): Boolean = full || canReadFiles()

    fun canReadFiles(): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** With Shizuku the engine grants Hone all-files access, which previews need; harmless when already on. */
    suspend fun ensureFileAccess() {
        if (full && !canReadFiles()) runCatching { raw("grant-files", context.packageName) }
    }

    fun openFilesAccess() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 30) {
            Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Last storage scan, kept here so it survives switching tabs. */
    var scan by mutableStateOf<Scan?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set

    /**
     * Moves [paths] to the trash and takes them off every list at once, before the next scan
     * catches up, so nothing stale can be picked again.
     */
    suspend fun trash(paths: List<String>, progress: (Float) -> Unit = {}): Trashed {
        // Small batches so the progress bar moves and one slow folder does not hold up the rest.
        var r = Trashed()
        paths.chunked(8).forEachIndexed { i, batch ->
            val part = call<Trashed>("trash", *batch.toTypedArray())
            r = Trashed(r.moved + part.moved, r.bytes + part.bytes, r.gone + part.gone, r.errors + part.errors)
            progress((i + 1) * 8f / paths.size)
        }
        val done = paths.toSet()
        scan = scan?.let { s ->
            s.copy(
                groups = s.groups.map { g ->
                    val left = g.files.filterNot { it.path in done }
                    val removed = g.files - left.toSet()
                    g.copy(files = left, count = (g.count - removed.size).coerceAtLeast(0), bytes = (g.bytes - removed.sumOf { it.bytes }).coerceAtLeast(0))
                },
                largest = s.largest.filterNot { it.path in done },
                trash = s.trash + r.bytes,
            )
        }
        rescan()
        return r
    }

    fun rescan() {
        if (scanning) return
        scanning = true
        scope.launch {
            runCatching { call<Scan>("scan") }.onSuccess { scan = it }.onFailure { Log.w(TAG, "scan", it) }
            scanning = false
        }
    }

    // ---- labels ----

    private val labels = HashMap<String, String>()

    private fun label(info: ApplicationInfo) = labels.getOrPut(info.packageName) { info.loadLabel(pm).toString() }

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString() }.getOrDefault(pkg)
    }

    fun isSystem(pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(true)

    fun openAppInfo(pkg: String) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // ---- app optimisation, kept here so it keeps running while the user switches tabs ----

    var dexopt by mutableStateOf<Dexopt?>(null)
        private set
    var optimizing by mutableStateOf(false)
        private set
    var compilingNow by mutableStateOf<String?>(null)
        private set

    /** Fully compiled apps when the running optimisation started; the live count minus this is its progress. */
    var runStartFast by mutableStateOf(0)
        private set

    fun fastCount(d: Dexopt?) = d?.counts?.filterKeys { it in setOf("speed-profile", "speed", "everything") }?.values?.sum() ?: 0

    /** Apps a full compile did not take on (broken install, no code); remembered per version, not retried. */
    val failedCompile = androidx.compose.runtime.mutableStateListOf<String>().apply {
        val saved = prefs.getStringSet("cantCompile", emptySet()).orEmpty()
        saved.forEach { entry ->
            val (pkg, ver) = entry.substringBefore('@') to entry.substringAfter('@').toLongOrNull()
            if (ver != null && versionOf(pkg) == ver) add(pkg)
        }
    }

    private fun versionOf(pkg: String): Long? = runCatching {
        val pi = pm.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull()

    private fun rememberCantCompile(pkg: String) {
        if (pkg !in failedCompile) failedCompile.add(pkg)
        val entries = failedCompile.mapNotNull { p -> versionOf(p)?.let { "$p@$it" } }.toSet()
        prefs.edit().putStringSet("cantCompile", entries).apply()
    }

    fun isDebuggable(pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }.getOrDefault(false)

    /** Full compile of one app; on success the counts and list update at once, without a 15-second rescan. */
    suspend fun compile(pkg: String): Changed {
        val r = call<Compiled>("compile", "speed", pkg).results.firstOrNull() ?: Changed(false, "no answer")
        if (!r.ok) rememberCantCompile(pkg)
        val d = dexopt
        if (r.ok && d != null) {
            val old = d.status[pkg]
            val counts = d.counts.toMutableMap()
            if (old != null) counts[old] = ((counts[old] ?: 1) - 1).coerceAtLeast(0)
            counts[r.status] = (counts[r.status] ?: 0) + 1
            dexopt = d.copy(counts = counts, slow = d.slow - pkg, status = d.status + (pkg to r.status))
        }
        return r
    }

    fun refreshDexopt() = scope.launch { dexopt = runCatching { call<Dexopt>("dexopt") }.getOrNull() ?: dexopt }

    fun optimize() {
        if (optimizing) return
        optimizing = true
        val watcher = scope.launch {
            if (dexopt == null) dexopt = runCatching { call<Dexopt>("dexopt") }.getOrNull()
            runStartFast = fastCount(dexopt)
            var tick = 0
            while (true) {
                compilingNow = runCatching { call<Dexopt>("compiling").compiling }.getOrNull()
                // The full state takes about two seconds to read; every sixth second keeps the ring honest.
                if (tick++ % 6 == 5) runCatching { call<Dexopt>("dexopt") }.onSuccess { dexopt = it }
                delay(1000)
            }
        }
        scope.launch {
            runCatching { dexopt = call<Dexopt>("optimize") }
            watcher.cancel()
            compilingNow = null
            optimizing = false
        }
    }

    fun cancelOptimize() = scope.launch { runCatching { raw("optimize-cancel") } }
}
