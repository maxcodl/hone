@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** The three looks the user can pick from. */
enum class Style(val title: String, val blurb: String) {
    Mono("Dot", "Black, white and one red. Dot-matrix type, thin lines, quiet motion."),
    Calm("Calm", "Flat cards, quiet colours from your wallpaper, gentle motion."),
    Expressive("Expressive", "Gradients, morphing shapes and bouncy motion."),
}

data class Look(val style: Style = Style.Mono, val amoled: Boolean = false) {
    val mono get() = style == Style.Mono
    val playful get() = style == Style.Expressive
}

val LocalLook = staticCompositionLocalOf { Look() }

/** Remembers the chosen look across launches. */
class LookStore(context: Context) {
    private val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
    var look by mutableStateOf(
        Look(
            runCatching { Style.valueOf(prefs.getString("style", null) ?: "") }.getOrDefault(Style.Mono),
            prefs.getBoolean("amoled", false),
        ),
    )
        private set

    fun set(new: Look) {
        look = new
        prefs.edit().putString("style", new.style.name).putBoolean("amoled", new.amoled).apply()
    }
}

@Composable
fun HoneTheme(look: Look, content: @Composable () -> Unit) {
    // Pure black only makes sense dark, so AMOLED turns dark mode on.
    val dark = look.amoled || isSystemInDarkTheme()
    val context = LocalContext.current
    val base = when {
        look.mono -> if (dark) monoDark() else monoLight()
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val scheme = if (look.amoled) base.pitchBlack() else base
    val type = androidx.compose.material3.Typography().let { t ->
        if (!look.mono) t else {
            val m = androidx.compose.ui.text.font.FontFamily.Monospace
            t.copy(
                titleLarge = t.titleLarge.copy(fontFamily = m), titleMedium = t.titleMedium.copy(fontFamily = m),
                titleSmall = t.titleSmall.copy(fontFamily = m), labelLarge = t.labelLarge.copy(fontFamily = m),
                labelMedium = t.labelMedium.copy(fontFamily = m), labelSmall = t.labelSmall.copy(fontFamily = m),
            )
        }
    }
    CompositionLocalProvider(LocalLook provides look) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = if (look.playful) MotionScheme.expressive() else MotionScheme.standard(),
            typography = type,
            content = content,
        )
    }
}

/** Every background surface black; cards step up just enough to be seen. */
private fun ColorScheme.pitchBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF1C1C1C),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1B1B1B),
)

private val NothingRed = Color(0xFFD71921)

private fun monoDark() = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF1F1F1F), onPrimaryContainer = Color.White,
    secondary = Color(0xFFBDBDBD), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF262626), onSecondaryContainer = Color.White,
    tertiary = Color(0xFF8A8A8A), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF2A2A2A), onTertiaryContainer = Color.White,
    error = NothingRed, onError = Color.White,
    errorContainer = Color(0xFF3A0A0C), onErrorContainer = Color(0xFFFFB4B0),
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF111111), surfaceContainerHigh = Color(0xFF171717),
    surfaceContainerHighest = Color(0xFF1F1F1F),
    outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF262626),
    inversePrimary = Color(0xFF5E5E5E), inverseSurface = Color.White, inverseOnSurface = Color.Black,
)

private fun monoLight() = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF4A4A4A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E2E2), onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF7A7A7A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDADADA), onTertiaryContainer = Color.Black,
    error = NothingRed, onError = Color.White,
    errorContainer = Color(0xFFFFE1E0), onErrorContainer = Color(0xFF5C0006),
    background = Color(0xFFF7F7F7), onBackground = Color.Black,
    surface = Color(0xFFF7F7F7), onSurface = Color.Black,
    surfaceVariant = Color(0xFFE6E6E6), onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F1F1),
    surfaceContainer = Color(0xFFECECEC), surfaceContainerHigh = Color(0xFFE6E6E6),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    outline = Color(0xFFC4C4C4), outlineVariant = Color(0xFFDDDDDD),
    inversePrimary = Color(0xFFA8A8A8), inverseSurface = Color.Black, inverseOnSurface = Color.White,
)

// ---- dot-matrix digits for the Mono look ----

private val glyphs: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    '%' to listOf("11000", "11001", "00010", "00100", "01000", "10011", "00011"),
    '°' to listOf("01100", "10010", "10010", "01100", "00000", "00000", "00000"),
    '.' to listOf("00000", "00000", "00000", "00000", "00000", "01100", "01100"),
    'G' to listOf("01110", "10001", "10000", "10111", "10001", "10001", "01111"),
    'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    'M' to listOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    'K' to listOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    'C' to listOf("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
    'D' to listOf("11100", "10010", "10001", "10001", "10001", "10010", "11100"),
    'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    'F' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    'I' to listOf("01110", "00100", "00100", "00100", "00100", "00100", "01110"),
    'J' to listOf("00111", "00010", "00010", "00010", "00010", "10010", "01100"),
    'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    'N' to listOf("10001", "10001", "11001", "10101", "10011", "10001", "10001"),
    'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    'P' to listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    'Q' to listOf("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    'S' to listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    'T' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    'U' to listOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    'V' to listOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    'W' to listOf("10001", "10001", "10001", "10101", "10101", "10101", "01010"),
    'X' to listOf("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    'Y' to listOf("10001", "10001", "10001", "01010", "00100", "00100", "00100"),
    'Z' to listOf("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
    '-' to listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
    '/' to listOf("00001", "00010", "00010", "00100", "01000", "01000", "10000"),
    ':' to listOf("00000", "01100", "01100", "00000", "01100", "01100", "00000"),
)

/** Text drawn as a 5x7 dot matrix, unlit dots faintly visible, the way Nothing draws numbers. */
@Composable
fun DotText(text: String, height: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val cell = with(LocalDensity.current) { height.toDp() } / 7f
    val chars = text.uppercase()
    fun cols(ch: Char) = when (ch) { '.', ':' -> 2; ' ' -> 1; else -> 5 }
    val width = cell * (chars.sumOf { cols(it) + 1 } - 1).coerceAtLeast(1)
    Canvas(modifier.size(width, cell * 7)) {
        val c = cell.toPx()
        val r = c * 0.36f
        var x0 = 0f
        for (ch in chars) {
            val g = glyphs[ch]
            val cols = cols(ch)
            val skip = if (ch == '.' || ch == ':') 1 else 0
            if (ch != ' ') for (row in 0 until 7) for (col in 0 until cols) {
                val on = g?.get(row)?.get(col + skip) == '1'
                drawCircle(if (on) color else color.copy(alpha = 0.05f), r, Offset(x0 + col * c + c / 2, row * c + c / 2))
            }
            x0 += (cols + 1) * c
        }
    }
}

val Look.cardBorder get() = mono || amoled
val Look.corner get() = if (mono) 20.dp else 28.dp
