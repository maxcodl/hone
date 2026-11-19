@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package io.max.hone

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class InstalledApp(
    val pkg: String,
    val label: String,
    val version: String,
    val apkBytes: Long,
    val split: Boolean,
    val system: Boolean,
    val enabled: Boolean,
    val archived: Boolean,
    val installed: Long,
    val updated: Long,
    val dataBytes: Long?,
    val lastUsed: Long?,
    val running: Boolean,
    val bgRestricted: Boolean = false,
) {
    val totalBytes get() = apkBytes + (dataBytes ?: 0)
}

enum class AppSort(val title: String) { Name("Name"), Size("Size"), Data("Data"), LastUsed("Last used"), Newest("Newest"), Running("Running") }

@Serializable data class Running(val packages: List<String> = emptyList())

/** Apps the user installed (and system apps they updated), with what Android lets Hone know about each. */
suspend fun installedApps(hone: Hone, context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val infos = if (Build.VERSION.SDK_INT >= 35) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
    } else {
        pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
    }
    val usage = hone.usageAllowed()
    val stats = context.getSystemService(StorageStatsManager::class.java)
    val lastUsed: Map<String, Long> = if (!usage) emptyMap() else runCatching {
        val now = System.currentTimeMillis()
        context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 60L * 86_400_000, now).mapValues { it.value.lastTimeUsed }
    }.getOrDefault(emptyMap())
    val running: Set<String> = if (hone.full) runCatching { hone.call<Running>("running").packages.toSet() }.getOrDefault(emptySet()) else emptySet()
    val restricted: Set<String> = if (hone.full) runCatching { hone.call<Running>("bg-restricted").packages.toSet() }.getOrDefault(emptySet()) else emptySet()
    infos
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
        .map { info ->
            val pi = runCatching { pm.getPackageInfo(info.packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
            val archived = Build.VERSION.SDK_INT >= 35 && info.isArchived
            InstalledApp(
                pkg = info.packageName,
                label = info.loadLabel(pm).toString(),
                version = pi?.versionName.orEmpty(),
                apkBytes = if (archived) 0 else (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { File(it ?: "").length() },
                split = info.splitSourceDirs.orEmpty().isNotEmpty(),
                system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                enabled = info.enabled,
                archived = archived,
                installed = pi?.firstInstallTime ?: 0,
                updated = pi?.lastUpdateTime ?: 0,
                dataBytes = if (!usage) null else runCatching {
                    stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, Process.myUserHandle()).dataBytes
                }.getOrNull(),
                lastUsed = lastUsed[info.packageName]?.takeIf { it > 0 },
                running = info.packageName in running,
                bgRestricted = info.packageName in restricted,
            )
        }
}

fun List<InstalledApp>.sortedFor(sort: AppSort): List<InstalledApp> = when (sort) {
    AppSort.Name -> sortedBy { it.label.lowercase() }
    AppSort.Size -> sortedByDescending { it.totalBytes }
    AppSort.Data -> sortedByDescending { it.dataBytes ?: 0 }
    AppSort.LastUsed -> sortedByDescending { it.lastUsed ?: 0 }
    AppSort.Newest -> sortedByDescending { it.installed }
    AppSort.Running -> sortedWith(compareByDescending<InstalledApp> { it.running }.thenBy { it.label.lowercase() })
}

/** The app's install files as one file in Hone's cache: the APK, or a .apks zip of all its parts. */
private suspend fun packageFile(context: Context, app: InstalledApp): File = withContext(Dispatchers.IO) {
    val info = context.packageManager.getApplicationInfo(app.pkg, 0)
    val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
    val name = "${app.label} ${app.version}".replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { app.pkg }
    val splits = info.splitSourceDirs.orEmpty()
    if (splits.isEmpty()) {
        File(dir, "$name.apk").also { File(info.sourceDir).copyTo(it) }
    } else {
        File(dir, "$name.apks").also { out ->
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                (listOf(info.sourceDir) + splits).forEach { path ->
                    zip.putNextEntry(ZipEntry(if (path == info.sourceDir) "base.apk" else File(path).name))
                    File(path).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}

private fun mime(file: File) = if (file.extension == "apk") "application/vnd.android.package-archive" else "application/octet-stream"

suspend fun shareApp(context: Context, app: InstalledApp) {
    val file = packageFile(context, app)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime(file)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${app.label}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Saves the app's install file to Download/Hone through MediaStore, which needs no permission. */
suspend fun saveApp(context: Context, app: InstalledApp): String {
    val file = packageFile(context, app)
    check(Build.VERSION.SDK_INT >= 29) { "Needs Android 10 or newer" }
    return withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime(file))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hone")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Download folder not available")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        "Download/Hone/${file.name}"
    }
}

/** The installed-apps list, as items of the Apps tab's list. */
fun LazyListScope.appItems(
    hone: Hone,
    apps: List<InstalledApp>?,
    query: String,
    onQuery: (String) -> Unit,
    sort: AppSort,
    onSort: (AppSort) -> Unit,
    onOpen: (InstalledApp) -> Unit,
) {
    item(key = "search") {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            leadingIcon = { Icon(Sym.Search, null) },
            placeholder = { Text("Search apps") },
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "sort") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { onSort(s) }, label = { Text(s.title) }) }
        }
    }
    if (apps == null) {
        item(key = "loading") { Loading(PaddingValues(32.dp), "Listing apps") }
        return
    }
    if (!hone.usageAllowed() && sort in setOf(AppSort.Size, AppSort.Data, AppSort.LastUsed)) item(key = "usage") {
        Text("Turn on usage access for Hone to sort by ${sort.title.lowercase()}.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
    }
    if (sort == AppSort.Running && !hone.full) item(key = "run") {
        Text("Only Shizuku can see which apps are running.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
    }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }.sortedFor(sort)
    items(shown, key = { "app:" + it.pkg }) { app ->
        RowCard(Modifier.animateItem().clickable { onOpen(app) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(hone, app.pkg, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(detail(app, sort), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    app.pkg == "io.max.hone" -> Pill("This app", MaterialTheme.colorScheme.primary)
                    app.archived -> Pill("Archived", MaterialTheme.colorScheme.tertiary)
                    !app.enabled -> Pill("Off", MaterialTheme.colorScheme.error)
                    app.bgRestricted -> Pill("No background", MaterialTheme.colorScheme.outline)
                    app.running -> Pill("Running", Color(0xFF4CAF50))
                }
            }
        }
    }
}

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}

private fun detail(app: InstalledApp, sort: AppSort): String = listOfNotNull(
    app.version.ifEmpty { null },
    when (sort) {
        AppSort.Data -> app.dataBytes?.let { "data ${bytes(it)}" }
        AppSort.LastUsed -> app.lastUsed?.let { "used ${ago(it)}" } ?: "not used lately"
        AppSort.Newest -> "installed ${ago(app.installed)}"
        else -> bytes(app.totalBytes)
    },
    if (app.split) "split" else null,
).joinToString("  ·  ")

/** Everything that can be done to one app. Shell actions need Shizuku; otherwise Android's own screens do it. */
@Composable
fun AppSheet(hone: Hone, app: InstalledApp, say: (String) -> Unit, onClose: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val cs = MaterialTheme.colorScheme

    fun run(key: String, block: suspend () -> String) = scope.launch {
        busy = key
        say(runCatching { block() }.getOrElse { "Failed: ${it.message}" })
        busy = null
        onChanged()
    }
    fun shell(cmd: String, done: String) = run(cmd) {
        val r = hone.call<Changed>(cmd, app.pkg)
        if (r.ok) done else r.output.ifBlank { "No change" }
    }

    ask?.let { (cmd, title, text) ->
        Confirm(title, text, when (cmd) { "disable" -> "Turn off"; "archive" -> "Archive"; else -> "Uninstall" }, onConfirm = {
            when (cmd) {
                "uninstall" -> shell("uninstall", "${app.label} uninstalled").also { onClose() }
                "disable" -> shell("disable", "${app.label} turned off")
                "archive" -> shell("archive", "${app.label} archived")
            }
        }, onDismiss = { ask = null })
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = if (LocalLook.current.amoled || LocalLook.current.mono) Color.Black else cs.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(hone, app.pkg, 52.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(app.version.ifEmpty { null }, bytes(app.apkBytes) + " app", app.dataBytes?.let { bytes(it) + " data" }).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    Text(app.pkg, style = MaterialTheme.typography.labelSmall, color = cs.outline)
                }
                if (busy != null) LoadingIndicator(Modifier.size(40.dp))
            }
            Spacer(Modifier.height(8.dp))
            val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
            if (launch != null && app.enabled && !app.archived) Action(Sym.OpenInNew, "Open") {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); onClose()
            }
            Action(Sym.Info, "App info") { hone.openAppInfo(app.pkg) }
            if (!app.archived) {
                Action(Sym.Share, "Share APK", if (app.split) "As one .apks file" else null) { run("share") { shareApp(context, app); "Choose where to send it" } }
                Action(Sym.Download, "Save APK", "To Download/Hone") { run("save") { "Saved to ${saveApp(context, app)}" } }
            }
            val self = app.pkg == context.packageName
            if (!self) when {
                !app.enabled -> Action(Sym.CheckCircle, "Turn on") { if (hone.full) shell("enable", "${app.label} turned on") else hone.openAppInfo(app.pkg) }
                else -> Action(Sym.Block, "Turn off", "Hidden and stopped until turned on again") {
                    if (hone.full) ask = Triple("disable", "Turn off ${app.label}?", "It disappears from the launcher and stops running. Turn it on again here any time.")
                    else hone.openAppInfo(app.pkg)
                }
            }
            if (!self && app.enabled && !app.archived) {
                if (app.bgRestricted) Action(Sym.PlayCircle, "Allow background", "Let it run when you are not using it") {
                    if (hone.full) shell("bg-allow", "${app.label} may run in the background") else hone.openAppInfo(app.pkg)
                } else Action(Sym.Bedtime, "Restrict background", "Stops it running when you are not using it; notifications may come late") {
                    if (hone.full) shell("bg-restrict", "${app.label} restricted in the background") else hone.openAppInfo(app.pkg)
                }
                if (app.running || !hone.full) Action(Sym.StopCircle, "Force stop", "Closes it now; it may start again later") {
                    if (hone.full) shell("force-stop", "${app.label} stopped") else hone.openAppInfo(app.pkg)
                }
            }
            if (Build.VERSION.SDK_INT >= 35 && !self) {
                if (app.archived) Action(Sym.Unarchive, "Restore from archive", "Downloads the app again, data kept") {
                    if (hone.full) shell("unarchive", "Restoring ${app.label}") else hone.openAppInfo(app.pkg)
                } else Action(Sym.Archive, "Archive", "Removes the app, keeps its data and icon") {
                    if (hone.full) ask = Triple("archive", "Archive ${app.label}?", "The app is removed but its data and icon stay. Tap the icon to download it again.")
                    else hone.openAppInfo(app.pkg)
                }
            }
            Action(Sym.Delete, "Uninstall", tint = cs.error) {
                if (hone.full && !self) ask = Triple("uninstall", "Uninstall ${app.label}?", "The app and all its data are removed.")
                else context.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, sub: String? = null, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
