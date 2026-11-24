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
    val jobRunning: Boolean = false,
)
@Serializable data class Freed(val freed: Long = 0, val free: Long = 0, val available: Long = 0, val apps: Int = 0)
@Serializable data class Pkg(val pkg: String, val tag: String, val note: String, val state: String)
@Serializable data class Pkgs(val packages: List<Pkg> = emptyList())
@Serializable data class Changed(val ok: Boolean = false, val output: String = "", val status: String = "", val why: String = "", val detail: String = "")
@Serializable data class Compiled(val results: List<Changed> = emptyList())
@Serializable data class CacheResult(val cleared: Int = 0, val failed: Int = 0, val stuck: Boolean = false, val error: String? = null, val unsupported: Boolean = false)
@Serializable data class DrainApp(val pkg: String, val mah: Double = 0.0, val pct: Double = 0.0, val awakeMs: Long = 0, val wakeups: Long = 0)
@Serializable data class Drain(val apps: List<DrainApp> = emptyList(), val totalMah: Double = 0.0, val measured: Boolean = false)
@Serializable data class OpUse(val op: String, val agoMs: Long = 0)
@Serializable data class PrivacyApp(val pkg: String, val ops: List<OpUse> = emptyList(), val user: Boolean = false)
@Serializable data class Privacy(val apps: List<PrivacyApp> = emptyList())
@Serializable data class DisplayState(val settings: Map<String, Double?> = emptyMap(), val modes: List<Double> = emptyList())
@Serializable data class Bench(
    val writeMBs: Double = 0.0, val readMBs: Double = 0.0, val randomIops: Double = 0.0,
    val cpuSingle: Double = 0.0, val cpuMulti: Double = 0.0, val at: Long = 0, val error: String? = null,
)

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
@Serializable data class Entry(val path: String, val name: String, val dir: Boolean, val bytes: Long = 0, val items: Long = 0, val modified: Long = 0, val locked: Boolean = false, val sized: Boolean = true)
@Serializable data class DirSize(val path: String = "", val bytes: Long = 0, val items: Long = 0)
@Serializable data class DirSizes(val sizes: List<DirSize> = emptyList())
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
const val SHIZUKU_REPO = "RikkaApps/Shizuku"

/** Talks to the Rust engine through a Shizuku shell service, and keeps app-wide jobs alive across tabs. */
class Hone(private val context: Context) {
    val json = Json { ignoreUnknownKeys = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pm: PackageManager = context.packageManager

    var access by mutableStateOf(readAccess())
        private set

    val looks = LookStore(context)
    val prefs = context.getSharedPreferences("mode", Context.MODE_PRIVATE)

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

    private fun shizukuLaunch(): Intent? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkg = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }
            .firstOrNull { it.startsWith("moe.shizuku.privileged.api") } ?: "moe.shizuku.privileged.api"
        return pm.getLaunchIntentForPackage(pkg)
    }

    /** Whether Shizuku is installed; re-read each time Hone comes back, since it may have just been installed. */
    var shizukuInstalled by mutableStateOf(shizukuLaunch() != null)
        private set

    fun recheckShizuku() {
        shizukuInstalled = shizukuLaunch() != null
        access = readAccess()
    }

    fun openShizuku() {
        shizukuLaunch()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
    }

    /** Download progress of Shizuku's own APK (0..1) while it is fetched; null otherwise. */
    var shizukuDownload by mutableStateOf<Float?>(null)
        private set
    var shizukuError by mutableStateOf<String?>(null)
        private set

    /** Fetches the latest Shizuku release from its GitHub page and hands it to Android's installer, which asks first. */
    fun installShizuku() {
        if (shizukuDownload != null) return
        shizukuDownload = 0f
        shizukuError = null
        scope.launch {
            runCatching {
                val r = Updater.latest(SHIZUKU_REPO) ?: error("no release")
                val url = r.apkUrl ?: error("no APK")
                Updater.download(context, url, "shizuku.apk") { shizukuDownload = it }
            }.onSuccess { Updater.install(context, it) }
                .onFailure { Log.w(TAG, "shizuku download", it); shizukuError = "Could not download Shizuku." }
            shizukuDownload = null
        }
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
    private var volumesCache: Pair<Long, List<Volume>>? = null

    /** Volumes, re-read at most every five seconds: screens ask on every redraw. */
    fun volumes(): List<Volume> {
        val now = System.currentTimeMillis()
        volumesCache?.let { (at, list) -> if (now - at < 5000) return list }
        return readVolumes().also { volumesCache = now to it }
    }

    private fun readVolumes(): List<Volume> {
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

    /**
     * Clears caches with `pm clear --cache-only` where Android has it (14 and newer). Older builds fall
     * back to the hidden package-manager call, which on some of them stops answering until a restart.
     */
    suspend fun clearCache(packages: List<String>): CacheResult = withContext(Dispatchers.IO) {
        val viaPm = runCatching { call<CacheResult>("clear-cache", *packages.toTypedArray()) }.getOrNull()
        if (viaPm != null && !viaPm.unsupported) return@withContext viaPm
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

    // ---- keep warm: apps Free memory leaves alone ----

    private val keepPrefs = context.getSharedPreferences("keep", Context.MODE_PRIVATE)
    var keepWarm by mutableStateOf(keepPrefs.getStringSet("apps", emptySet()).orEmpty().toSet())
        private set

    fun updateKeepWarm(apps: Set<String>) {
        keepWarm = apps
        keepPrefs.edit().putStringSet("apps", apps).apply()
    }

    /** Launcher, keyboard and Hone: closing them only makes the phone feel slower. */
    fun alwaysKept(): Set<String> = setOfNotNull(
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/'),
        context.packageName,
    )

    suspend fun freeRam(): Freed =
        if (full) call("free-ram", *(keepWarm + alwaysKept()).toTypedArray()) else freeRamLocal()

    /** Asks Android to close every app's background processes; allowed to any app with a normal permission. */
    suspend fun freeRamLocal(): Freed = withContext(Dispatchers.IO) {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        fun avail() = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        val keep = keepWarm + alwaysKept()
        pm.getInstalledApplications(0).forEach { if (it.packageName !in keep) am.killBackgroundProcesses(it.packageName) }
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

    /** What the storage widget shows: 0 total, 1 internal, 2 SD card. Remembered between launches. */
    var storageScope by mutableStateOf(prefs.getInt("storageScope", 0))
        private set

    fun nextStorageScope(hasSd: Boolean) {
        storageScope = if (hasSd) (storageScope + 1) % 3 else if (storageScope == 0) 1 else 0
        prefs.edit().putInt("storageScope", storageScope).apply()
    }

    var sdScan by mutableStateOf<Scan?>(null)
        private set
    private var sdScanning = false

    // ---- trash upkeep and similar photos ----

    /** Opt-in: trash batches older than 30 days are deleted for good on the next scan or overnight. */
    var autoEmpty by mutableStateOf(prefs.getBoolean("autoEmpty", false))
        private set

    fun updateAutoEmpty(on: Boolean) {
        autoEmpty = on
        prefs.edit().putBoolean("autoEmpty", on).apply()
    }

    suspend fun expireTrash(): Freed = if (autoEmpty) call("trash-expire", "30") else Freed()

    /** Near-identical photos, found on demand; kept apart from the scan so a rescan does not drop them. */
    var similar by mutableStateOf<CleanGroup?>(null)
        private set
    var similarProgress by mutableStateOf<Float?>(null)
        private set

    fun findSimilar() {
        if (similarProgress != null) return
        similarProgress = 0f
        scope.launch {
            runCatching { similarPhotos(context) { similarProgress = it } }.onSuccess { similar = it }.onFailure { Log.w(TAG, "similar", it) }
            similarProgress = null
        }
    }

    /** Every cleanup group: similar photos when found, then the scan's own. */
    fun groups(): List<CleanGroup> = listOfNotNull(similar) + scan?.groups.orEmpty()

    fun scanSd(path: String) {
        if (sdScanning) return
        sdScanning = true
        scope.launch {
            runCatching { call<Scan>("scan", path) }.onSuccess { sdScan = it }
            sdScanning = false
        }
    }

    /** Which part of the Storage tab is open: 0 clean, 1 files, 2 apps. */
    var storageSection by mutableStateOf(0)
    /** Apps tab: 0 speed, 1 app tools. */
    var appsSection by mutableStateOf(0)
    /** Network tab: the detailed live view is open. */
    var netLive by mutableStateOf(false)

    /** Total size of installed apps, from the last app-size reading, for the space breakdown. */
    var appsBytes by mutableStateOf(0L)

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
            progress(((i + 1) * 8f / paths.size).coerceAtMost(1f))
        }
        val done = paths.toSet()
        similar = similar?.let { g ->
            val left = g.files.filterNot { it.path in done }
            g.copy(files = left, count = left.size, bytes = left.sumOf { it.bytes })
        }
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
            runCatching { expireTrash() }
            runCatching { call<Scan>("scan") }.onSuccess { scan = it }.onFailure { Log.w(TAG, "scan", it) }
            scanning = false
        }
    }

    // ---- per-app switches kept between launches ----

    /** Apps whose network Hone blocked; Android forgets the block on restart, and so does this list. */
    private val netPrefs = context.getSharedPreferences("net", Context.MODE_PRIVATE)
    var netBlocked by mutableStateOf(netPrefs.getStringSet("blocked", emptySet()).orEmpty().toSet())
        private set

    suspend fun setNetwork(pkg: String, allow: Boolean): Changed {
        val r = call<Changed>(if (allow) "net-allow" else "net-block", pkg)
        if (r.ok) {
            netBlocked = if (allow) netBlocked - pkg else netBlocked + pkg
            netPrefs.edit().putStringSet("blocked", netBlocked).apply()
        }
        return r
    }

    fun forgetNetBlocks() {
        netBlocked = emptySet()
        netPrefs.edit().remove("blocked").apply()
    }

    /** Last speed test, and the one before it, to compare a cleanup's effect. */
    private val benchPrefs = context.getSharedPreferences("bench", Context.MODE_PRIVATE)
    var benchLast by mutableStateOf(readBench("last"))
        private set
    var benchBefore by mutableStateOf(readBench("before"))
        private set

    private fun readBench(key: String): Bench? = benchPrefs.getString(key, null)?.let { runCatching { json.decodeFromString<Bench>(it) }.getOrNull() }

    suspend fun runBench(): Bench {
        val b = json.decodeFromString<Bench>(runLocalEngine(context, "bench", context.cacheDir.path)).copy(at = System.currentTimeMillis())
        if (b.error == null) {
            benchBefore = benchLast
            benchLast = b
            benchPrefs.edit().putString("last", json.encodeToString(Bench.serializer(), b))
                .apply { benchBefore?.let { putString("before", json.encodeToString(Bench.serializer(), it)) } }.apply()
        }
        return b
    }

    // ---- labels ----

    private val labels = HashMap<String, String>()

    /**
     * Android keeps the record of a use after the permission is taken away, and the app op stays "allow",
     * so drop camera, microphone and location uses whose permission is gone. Clipboard is filtered by the engine.
     */
    fun stillAllowed(p: Privacy): Privacy {
        val loc = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val perms = mapOf("CAMERA" to listOf(android.Manifest.permission.CAMERA), "RECORD_AUDIO" to listOf(android.Manifest.permission.RECORD_AUDIO), "FINE_LOCATION" to loc, "COARSE_LOCATION" to loc)
        return Privacy(p.apps.mapNotNull { a ->
            val ops = a.ops.filter { o -> perms[o.op]?.any { pm.checkPermission(it, a.pkg) == PackageManager.PERMISSION_GRANTED } ?: true }
            if (ops.isEmpty()) null else a.copy(ops = ops)
        })
    }

    private fun label(info: ApplicationInfo) = labels.getOrPut(info.packageName) { info.loadLabel(pm).toString() }

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString() }.getOrDefault(pkg)
    }

    private val systemCache = HashMap<String, Boolean>()
    private val debugCache = HashMap<String, Boolean>()

    fun isSystem(pkg: String): Boolean = systemCache.getOrPut(pkg) { isSystemUncached(pkg) }

    private fun isSystemUncached(pkg: String): Boolean = runCatching {
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

    /** Apps the running job has worked on; most are re-compiles of apps already fast, with fresh usage data. */
    val processedRun = androidx.compose.runtime.mutableStateListOf<String>()

    fun fastCount(d: Dexopt?) = d?.counts?.filterKeys { it in setOf("speed-profile", "speed", "everything") }?.values?.sum() ?: 0

    /** Apps a full compile did not take on (broken install, no code); remembered per version, not retried. */
    val failedCompile = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** dex2oat's own words for why it refused an app, when it said any. */
    val compileDetail = androidx.compose.runtime.mutableStateMapOf<String, String>()

    init {
        // Entries are pkg@version#why#detail; an unknown reason is never kept, so it is tried again next time.
        prefs.getStringSet("cantCompile2", emptySet()).orEmpty().forEach { entry ->
            val pkg = entry.substringBefore('@')
            val ver = entry.substringAfter('@').substringBefore('#').toLongOrNull()
            val rest = entry.substringAfter('#', "")
            if (ver != null && rest.isNotEmpty() && versionOf(pkg) == ver) {
                failedCompile[pkg] = rest.substringBefore('#')
                rest.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { compileDetail[pkg] = it }
            }
        }
    }

    private fun versionOf(pkg: String): Long? = runCatching {
        val pi = pm.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull()

    private fun rememberCantCompile(pkg: String, why: String, detail: String) {
        failedCompile[pkg] = why.ifEmpty { "other" }
        if (detail.isNotEmpty()) compileDetail[pkg] = detail else compileDetail.remove(pkg)
        val entries = failedCompile.filterValues { it != "other" }
            .mapNotNull { (p, w) -> versionOf(p)?.let { "$p@$it#$w#${compileDetail[p].orEmpty()}" } }.toSet()
        prefs.edit().putStringSet("cantCompile2", entries).remove("cantCompile").apply()
    }

    fun isDebuggable(pkg: String): Boolean = debugCache.getOrPut(pkg) { isDebuggableUncached(pkg) }

    private fun isDebuggableUncached(pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }.getOrDefault(false)

    /** Full compile of one app; on success the counts and list update at once, without a 15-second rescan. */
    suspend fun compile(pkg: String): Changed {
        val r = call<Compiled>("compile", "speed", pkg).results.firstOrNull() ?: Changed(false, "no answer")
        if (!r.ok) rememberCantCompile(pkg, r.why, r.detail)
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

    /** What the last finished run did, so a run that ends while you are away still says so. */
    var lastRun by mutableStateOf<String?>(null)
        private set
    var batchDone by mutableStateOf(0)
        private set
    var batchTotal by mutableStateOf(0)
        private set
    private var batchJob: kotlinx.coroutines.Job? = null

    /** Basic apps a full compile can still help: not debug builds, not already known to fail. */
    fun compilable(): List<String> = dexopt?.slow.orEmpty().filter { it !in failedCompile && !isDebuggable(it) }

    /**
     * Fully compiles every remaining basic app, one at a time, so the count really moves. Android's own
     * optimiser only refreshes apps that already have usage data and leaves the rest basic.
     */
    fun optimizeAll() {
        if (optimizing) return
        val queue = compilable()
        if (queue.isEmpty()) {
            lastRun = "Nothing left that can be compiled"
            return
        }
        optimizing = true
        batchTotal = queue.size
        batchDone = 0
        processedRun.clear()
        runStartFast = fastCount(dexopt)
        val started = System.currentTimeMillis()
        batchJob = scope.launch {
            try {
                for (pkg in queue) {
                    compilingNow = pkg
                    runCatching { compile(pkg) }
                    processedRun.add(pkg)
                    batchDone++
                }
            } finally {
                compilingNow = null
                val mins = ((System.currentTimeMillis() - started) / 60_000).coerceAtLeast(1)
                lastRun = "Last run took $mins min: ${processedRun.size} apps processed, ${(fastCount(dexopt) - runStartFast).coerceAtLeast(0)} newly compiled"
                batchTotal = 0
                optimizing = false
            }
        }
    }

    fun cancelOptimize() {
        batchJob?.cancel()
    }

    companion object {
        @Volatile private var instance: Hone? = null

        fun get(context: Context): Hone =
            instance ?: synchronized(this) { instance ?: Hone(context.applicationContext).also { instance = it } }
    }
}
