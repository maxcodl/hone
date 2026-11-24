package io.max.hone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-app update from the latest GitHub release: check, download the APK, hand it to Android's installer. */
object Updater {
    const val REPO = "maxcodl/hone"
    const val PAGE = "https://github.com/$REPO"

    data class Release(val version: String, val notes: String, val apkUrl: String?)

    fun installed(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    /** True when [tag] ("v0.1.4") is a higher dotted version than [current] ("0.1.3"). */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(s: String) = s.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest release, or null when the repo has none. Throws on a network failure. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            if (conn.responseCode == 404) return@withContext null
            val o = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            Release(o.str("tag_name"), o.str("body"), apk)
        } finally { conn.disconnect() }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()

    /** Saves the APK into the cache folder the file provider shares; [progress] gets 0..1. */
    suspend fun download(c: Context, url: String, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(c.cacheDir, "share/hone-update.apk").also { it.parentFile?.mkdirs() }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            val total = conn.contentLengthLong
            conn.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally { conn.disconnect() }
        out
    }

    /** False when Android first needs the "install unknown apps" switch for Hone; its settings page is opened. */
    fun install(c: Context, apk: File): Boolean {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Found(val r: Updater.Release) : UpdateState
    data class Downloading(val p: Float) : UpdateState
    data class Ready(val apk: File) : UpdateState
    data class Failed(val why: String) : UpdateState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = runCatching { Updater.latest() }.fold(
                { r -> if (r != null && Updater.isNewer(r.version, Updater.installed(context))) UpdateState.Found(r) else UpdateState.Current },
                { UpdateState.Failed("No connection to GitHub.") },
            )
        }
    }
    fun get(r: Updater.Release) {
        val url = r.apkUrl ?: run { state = UpdateState.Failed("That release has no APK."); return }
        state = UpdateState.Downloading(0f)
        scope.launch {
            state = runCatching { Updater.download(context, url) { state = UpdateState.Downloading(it) } }
                .fold({ UpdateState.Ready(it) }, { UpdateState.Failed("Download failed.") })
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            CardHeader(Sym.Info, "Hone ${Updater.installed(context)}", "Phone tune-up for Android, with a small Rust engine.")
            when (val s = state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Text("Checking GitHub…", color = cs.onSurfaceVariant)
                UpdateState.Current -> Text("You have the latest version.", color = cs.onSurfaceVariant)
                is UpdateState.Found -> {
                    Text("${s.r.version} is available.", style = MaterialTheme.typography.titleSmall)
                    if (s.r.notes.isNotBlank()) Text(s.r.notes.take(600), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { s.p }, modifier = Modifier.fillMaxWidth())
                is UpdateState.Ready -> Text("Downloaded. Android asks before it installs.", color = cs.onSurfaceVariant)
                is UpdateState.Failed -> Text(s.why, color = cs.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("GitHub") }
                FilledTonalButton(
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        when (val s = state) {
                            is UpdateState.Found -> get(s.r)
                            is UpdateState.Ready -> Updater.install(context, s.apk)
                            else -> check()
                        }
                    },
                ) {
                    Icon(Sym.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when (state) { is UpdateState.Found -> "Download"; is UpdateState.Ready -> "Install"; else -> "Check for update" })
                }
            }
        }
        // Themed filled glyphs: they take the colours of whichever look is on.
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val mono = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace)
            Text("Made by Max with ", style = mono, color = cs.onSurfaceVariant)
            Icon(Sym.Heart, "love", Modifier.size(20.dp), tint = cs.primary)
            Text(" and ", style = mono, color = cs.onSurfaceVariant)
            Icon(Sym.Coffee, "coffee", Modifier.size(20.dp), tint = cs.tertiary)
        }
    }
}
