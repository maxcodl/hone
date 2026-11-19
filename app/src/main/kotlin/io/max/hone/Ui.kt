@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

fun bytes(b: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun duration(seconds: Double): String {
    val s = seconds.toLong()
    val d = s / 86400
    val h = s % 86400 / 3600
    val m = s % 3600 / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

operator fun PaddingValues.plus(extra: Dp) = PaddingValues(
    start = calculateLeftPadding(LayoutDirection.Ltr) + extra,
    end = calculateRightPadding(LayoutDirection.Ltr) + extra,
    top = calculateTopPadding() + 8.dp,
    bottom = calculateBottomPadding() + 24.dp,
)

/**
 * The icon badge on every card. Expressive puts it on a playful shape, Calm on a soft circle,
 * Mono draws only a thin ring around it.
 */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    shape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 48.dp,
) {
    val look = LocalLook.current
    val cs = MaterialTheme.colorScheme
    val mod = when (look.style) {
        Style.Expressive -> Modifier.size(size).clip(shape.toShape()).background(container)
        Style.Calm -> Modifier.size(size).clip(CircleShape).background(container)
        Style.Mono -> Modifier.size(size).border(1.dp, cs.outline, CircleShape)
    }
    Box(mod, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (look.mono) cs.onSurface else content, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalLook.current
    val cs = MaterialTheme.colorScheme
    // Mono and AMOLED cards sit on near-black, so a hairline edge does the separating instead of colour.
    val plain = container == cs.surfaceContainer
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(look.corner),
        colors = CardDefaults.cardColors(containerColor = if (look.mono && plain) cs.surfaceContainerLow else container),
        border = if (look.cardBorder) BorderStroke(1.dp, cs.outlineVariant) else null,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun CardHeader(icon: ImageVector, title: String, subtitle: String? = null, shape: RoundedPolygon = MaterialShapes.Cookie9Sided) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!LocalLook.current.mono) {
            ShapeIcon(icon, shape)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            if (LocalLook.current.mono) {
                Text(title.uppercase(), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp))
            } else {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    if (LocalLook.current.mono) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )
    } else {
        Text(
            text,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )
    }
}

@Composable
fun Loading(pad: PaddingValues, text: String? = null) {
    Column(
        Modifier.fillMaxSize().padding(pad),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator(Modifier.size(72.dp))
        if (text != null) {
            Spacer(Modifier.size(16.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** App icon. Corner 10dp so it nests evenly in the 24dp rows with 14dp padding. */
@Composable
fun AppIcon(hone: Hone, pkg: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(iconCache[pkg], pkg) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(pkg)
                val px = 96
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, px, px)
                d.draw(Canvas(bmp))
                bmp.asImageBitmap().also { iconCache[pkg] = it }
            }.getOrNull()
        }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(10.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}

private val iconCache = HashMap<String, ImageBitmap>()

@Composable
fun Confirm(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }, shapes = ButtonDefaults.shapes()) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
    )
}

@Composable
fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** Name on a reading row: uppercase mono in Dot, plain text otherwise. */
@Composable
fun RowLabel(text: String, modifier: Modifier = Modifier) {
    if (LocalLook.current.mono) {
        Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Text(text, modifier, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row card for lists of apps, consistent across tabs. */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        border = if (look.cardBorder) BorderStroke(1.dp, cs.outlineVariant) else null,
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}
