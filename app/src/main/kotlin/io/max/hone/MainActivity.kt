@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package io.max.hone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val hone by lazy { Hone.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HoneTheme(hone.looks.look) { App(hone) } }
    }
}

private data class Tab(val title: String, val icon: ImageVector, val outline: ImageVector)

private val tabs = listOf(
    Tab("Health", Sym.MonitorHeart, Sym.MonitorHeart),
    Tab("Battery", Sym.BatteryCharging, Sym.BatteryCharging),
    Tab("Storage", Sym.Storage, Sym.Storage),
    Tab("Speed", Sym.Speed, Sym.Speed),
    Tab("Apps", Sym.Apps, Sym.Apps),
)

/** Asks for Shizuku: the permission when it runs, otherwise opens it. */
fun Hone.connect() = if (access == Access.Ask) askPermission() else openShizuku()

@Composable
fun App(hone: Hone) {
    if (!hone.full && !hone.limited) {
        Gate(hone)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showLook by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { msg -> android.util.Log.i(TAG, "say: $msg"); scope.launch { snackbar.showSnackbar(msg) } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val look = LocalLook.current
    val cs = MaterialTheme.colorScheme

    if (showLook) LookSheet(hone) { showLook = false }

    Scaffold(
        modifier = if (look.mono) Modifier else Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = cs.surface,
        topBar = {
            if (look.mono) DotHeader(tabs[tab].title, if (hone.full) hone.deviceName else "${hone.deviceName}  ·  without Shizuku") {
                if (!hone.full) TextButton(onClick = { hone.connect() }) {
                    Icon(Sym.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CONNECT", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { showLook = true }) { Icon(Sym.Palette, "Look") }
            } else LargeFlexibleTopAppBar(
                title = { Text(if (look.mono) tabs[tab].title.uppercase() else tabs[tab].title) },
                subtitle = { Text(if (hone.full) hone.deviceName else "${hone.deviceName}  ·  without Shizuku", maxLines = 1) },
                actions = {
                    if (!hone.full) {
                        TextButton(onClick = { hone.connect() }, shapes = ButtonDefaults.shapes()) {
                            Icon(Sym.Bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Connect Shizuku")
                        }
                    }
                    IconButton(onClick = { showLook = true }, shapes = IconButtonDefaults.shapes()) { Icon(Sym.Palette, "Look") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface, scrolledContainerColor = if (look.amoled) cs.surface else cs.surfaceContainer),
                scrollBehavior = scroll,
            )
        },
        bottomBar = {
            if (look.mono) DotNav(tab) { tab = it } else ShortNavigationBar(containerColor = if (look.amoled || look.mono) cs.surface else ShortNavigationBarDefaults.containerColor) {
                tabs.forEachIndexed { i, t ->
                    ShortNavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        AnimatedContent(
            tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                when (look.style) {
                    Style.Expressive ->
                        (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 5 * dir } + fadeIn(tween(220)) + scaleIn(initialScale = 0.97f)) togetherWith
                            (slideOutHorizontally(tween(180)) { -it / 5 * dir } + fadeOut(tween(150)))
                    Style.Calm -> (slideInHorizontally(tween(260)) { it / 12 * dir } + fadeIn(tween(260))) togetherWith fadeOut(tween(160))
                    Style.Mono -> (fadeIn(tween(180, delayMillis = 60)) + scaleIn(tween(220), initialScale = 0.985f)) togetherWith fadeOut(tween(90))
                }
            },
            label = "tab",
        ) { t ->
            val opened = remember(t) { System.currentTimeMillis() }
            CompositionLocalProvider(LocalEnterStart provides opened) {
            when (t) {
                0 -> HealthScreen(hone, pad, say) { tab = it }
                1 -> BatteryScreen(hone, pad, say)
                2 -> StorageScreen(hone, pad, say)
                3 -> SpeedScreen(hone, pad, say)
                else -> AppsScreen(hone, pad, say)
            }
            }
        }
    }
}

/** Dot-matrix title over a small mono line, actions on the right. */
@Composable
private fun DotHeader(title: String, sub: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface).statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DotText(title, 26.sp, cs.onSurface) }
            actions()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            sub.uppercase(), maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
            color = cs.onSurfaceVariant,
        )
    }
}

/** Bottom bar: rounded icons, mono labels, a red dot over the open tab. */
@Composable
private fun DotNav(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(68.dp)) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) cs.error else Color.Transparent))
                    Spacer(Modifier.height(5.dp))
                    Icon(t.icon, null, Modifier.size(24.dp), tint = if (on) cs.onSurface else cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp),
                        maxLines = 1, softWrap = false,
                        color = if (on) cs.onSurface else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Card shown where a feature needs Shizuku, with the one button that gets it. */
@Composable
fun NeedsShizuku(hone: Hone, title: String, text: String, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        CardHeader(Sym.Bolt, title, text, MaterialShapes.Sunny)
        FilledTonalButton(onClick = { hone.connect() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
            Text(if (hone.access == Access.Ask) "Allow in Shizuku" else "Open Shizuku")
        }
    }
}

@Composable
private fun LookSheet(hone: Hone, onClose: () -> Unit) {
    val current = hone.looks.look
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetColor()) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Look", style = MaterialTheme.typography.headlineSmallEmphasized)
            Style.entries.forEach { s ->
                val selected = current.style == s
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = if (selected) cs.secondaryContainer else cs.surfaceContainer),
                    border = if (selected) BorderStroke(2.dp, cs.primary) else null,
                    modifier = Modifier.fillMaxWidth().clickable { hone.looks.set(current.copy(style = s)) },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        // A live sample of the style, drawn by the real components.
                        CompositionLocalProvider(LocalLook provides current.copy(style = s)) {
                            Ring(0.7f, Modifier.size(64.dp), 7.dp, listOf(cs.primary, cs.tertiary), cs.surfaceContainerHighest) {
                                BigValue("70", MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium)
                            Text(s.blurb, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        RadioButton(selected = selected, onClick = { hone.looks.set(current.copy(style = s)) })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { hone.looks.set(current.copy(amoled = !current.amoled)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AMOLED black", style = MaterialTheme.typography.titleMedium)
                    Text("Pure black everywhere. Saves power on this OLED screen and turns dark mode on.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = current.amoled, onCheckedChange = { hone.looks.set(current.copy(amoled = it)) })
            }
            if (hone.limited && hone.full) {
                TextButton(onClick = { hone.useLimited(false) }) { Text("Shizuku is connected: full mode is on") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Gate(hone: Hone) {
    val running = hone.access == Access.Ask
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.surface).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (LocalLook.current.mono) {
                Ring(1f, Modifier.size(160.dp), 10.dp, listOf(cs.onSurface), cs.surfaceContainerHighest)
            } else {
                MorphBlob(MaterialShapes.Sunny, MaterialShapes.Cookie9Sided, Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer)), Modifier.size(160.dp))
            }
            Icon(if (running) Sym.Key else Sym.Bolt, null, Modifier.size(56.dp), tint = cs.onSurface)
        }
        Spacer(Modifier.size(32.dp))
        BigValue("Hone", MaterialTheme.typography.displayMediumEmphasized)
        Spacer(Modifier.size(12.dp))
        Text(
            if (running) "Allow Hone in Shizuku for everything: cache cleaning, app optimisation and debloat."
            else "Start Shizuku for everything Hone can do, or go on with what works without it.",
            textAlign = TextAlign.Center,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.size(32.dp))
        Button(
            onClick = { hone.connect() },
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
        ) {
            Text(if (running) "Allow" else "Open Shizuku", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
        }
        Spacer(Modifier.size(12.dp))
        TextButton(onClick = { hone.useLimited(true) }, shapes = ButtonDefaults.shapes()) { Text("Continue without Shizuku") }
    }
}
