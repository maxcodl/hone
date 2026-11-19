@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.max.hone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Two expressive shapes melting into each other while slowly turning. */
@Composable
fun MorphBlob(a: RoundedPolygon, b: RoundedPolygon, brush: Brush, modifier: Modifier, periodMs: Int = 7000, spinning: Boolean = true) {
    val morph = remember(a, b) { Morph(a, b) }
    val t = rememberInfiniteTransition(label = "blob")
    val progress by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "morph")
    val turn = if (spinning) t.animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs * 3, easing = LinearEasing)), label = "turn").value else 0f
    val android = remember { android.graphics.Path() }
    Canvas(modifier) {
        android.rewind()
        morph.toPath(progress, android)
        val path = android.asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        rotate(turn) { drawPath(path, brush) }
    }
}

/** Progress ring. Expressive: gradient sweep. Calm: one solid colour. Mono: a circle of dots that light up. */
@Composable
fun Ring(value: Float, modifier: Modifier, stroke: Dp = 18.dp, colors: List<Color>, track: Color, content: @Composable BoxScope.() -> Unit = {}) {
    val look = LocalLook.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(value) {
        shown.animateTo(value.coerceIn(0f, 1f), if (look.playful) spring(dampingRatio = 0.7f, stiffness = 60f) else tween(700, easing = FastOutSlowInEasing))
    }
    val lit = MaterialTheme.colorScheme.onSurface
    val warn = MaterialTheme.colorScheme.error
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            if (look.mono) {
                val dots = 40
                val r = size.minDimension / 2 - w / 2
                val dot = (w * 0.32f).coerceAtMost(r * Math.PI.toFloat() / dots * 0.8f)
                for (i in 0 until dots) {
                    val a = Math.toRadians(-90.0 + i * 360.0 / dots)
                    val on = i < (shown.value * dots).toInt()
                    drawCircle(if (on) (if (colors.first() == warn) warn else lit) else lit.copy(alpha = 0.12f), dot, Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
                }
                return@Canvas
            }
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            rotate(-90f) {
                val brush = if (look.playful) Brush.sweepGradient(colors + colors.first()) else Brush.linearGradient(listOf(colors.first(), colors.first()))
                drawArc(brush, 0f, 360f * shown.value, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Ring of coloured slices that grow in one after another. */
@Composable
fun Donut(slices: List<Pair<Float, Color>>, modifier: Modifier, stroke: Dp = 26.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val look = LocalLook.current
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(if (look.mono) 600 else 1400, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = (if (look.mono) stroke * 0.55f else stroke).toPx()
            val arc = Size(size.width - w, size.height - w)
            val total = slices.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
            val gap = if (look.mono) 4f else 2.5f
            var start = -90f
            for ((v, c) in slices) {
                val sweep = 360f * v / total * grow.value
                if (sweep > gap) drawArc(c, start + gap / 2, sweep - gap, false, Offset(w / 2, w / 2), arc, style = Stroke(w, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        content()
    }
}

/** Smooth line of recent values; Mono drops the fill and draws plain dots at each sample. */
@Composable
fun Sparkline(values: List<Float>, max: Float, color: Color, modifier: Modifier) {
    val mono = LocalLook.current.mono
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        fun y(v: Float) = size.height - (v / max).coerceIn(0f, 1f) * size.height * 0.9f - 2f
        if (mono) {
            values.forEachIndexed { i, v -> drawCircle(color, 2.dp.toPx(), Offset(i * step, y(v))) }
            return@Canvas
        }
        val line = Path().apply {
            moveTo(0f, y(values[0]))
            for (i in 1 until values.size) {
                val x0 = (i - 1) * step
                val x1 = i * step
                cubicTo((x0 + x1) / 2, y(values[i - 1]), (x0 + x1) / 2, y(values[i]), x1, y(values[i]))
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 4.dp.toPx(), Offset(size.width, y(values.last())))
    }
}

/** One column per value (0-1). Bars that bounce, or in Mono, stacks of lit dots. */
@Composable
fun Equalizer(values: List<Float>, modifier: Modifier, color: Color, track: Color) {
    val look = LocalLook.current
    if (look.mono) {
        val anim = values.map { animateFloatAsState(it.coerceIn(0f, 1f), tween(300), label = "dot").value }
        val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        Canvas(modifier) {
            val rows = 10
            val colW = size.width / anim.size
            val rowH = size.height / rows
            val r = minOf(colW, rowH) * 0.28f
            anim.forEachIndexed { c, v ->
                val lit = (v * rows).toInt().coerceAtLeast(1)
                for (row in 0 until rows) {
                    drawCircle(if (row < lit) color else dim, r, Offset(c * colW + colW / 2, size.height - row * rowH - rowH / 2))
                }
            }
        }
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { v ->
            val h by animateFloatAsState(
                v.coerceIn(0.03f, 1f),
                if (look.playful) spring(dampingRatio = 0.55f, stiffness = 300f) else tween(400),
                label = "bar",
            )
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(track), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(h).clip(RoundedCornerShape(10.dp))
                        .background(if (look.playful) Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f))) else Brush.linearGradient(listOf(color, color))),
                )
            }
        }
    }
}

/** Horizontal bar that fills to [value] (0-1). */
@Composable
fun GradientBar(value: Float, colors: List<Color>, track: Color, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val look = LocalLook.current
    val v by animateFloatAsState(value.coerceIn(0f, 1f), spring(dampingRatio = 0.75f, stiffness = 120f), label = "bar")
    if (look.mono) {
        val cs = MaterialTheme.colorScheme
        val on = if (colors.last() == cs.error) cs.error else cs.onSurface
        Canvas(modifier.fillMaxWidth().height(6.dp)) {
            val n = (size.width / 9.dp.toPx()).toInt().coerceAtLeast(4)
            val step = size.width / n
            val lit = (v * n + 0.5f).toInt()
            for (i in 0 until n) drawCircle(if (i < lit) on else on.copy(alpha = 0.12f), size.height / 2, Offset(step * i + step / 2, size.height / 2))
        }
        return
    }
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(
            Modifier.fillMaxWidth(v).fillMaxHeight().clip(CircleShape)
                .background(if (look.playful) Brush.horizontalGradient(colors) else Brush.linearGradient(listOf(colors.last(), colors.last()))),
        )
    }
}

/** A number that counts to its new value; Mono draws it as a dot matrix. */
@Composable
fun Counter(value: Int, style: TextStyle, modifier: Modifier = Modifier, suffix: String = "", color: Color = Color.Unspecified) {
    val shown by animateIntAsState(value, tween(900, easing = FastOutSlowInEasing), label = "count")
    BigValue("$shown$suffix", style, modifier, color)
}

/** A headline value: dot matrix in Mono, the given text style otherwise. */
@Composable
fun BigValue(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val c = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    // A 5x7 matrix needs about 30sp before the dots read as digits; smaller values use a mono font.
    when {
        !LocalLook.current.mono -> Text(text, modifier, style = style, color = c)
        style.fontSize.value >= 30f -> DotText(text, style.fontSize * 0.8f, c, modifier.padding(vertical = 4.dp))
        else -> Text(text, modifier, style = style.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp), color = c)
    }
}

/** Brings cards in: a staggered spring in Expressive, a soft rise in Calm, a quick fade in Mono. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    val look = LocalLook.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        when (look.style) {
            Style.Expressive -> { delay(index.coerceAtMost(8) * 55L); a.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 180f)) }
            Style.Calm -> { delay(index.coerceAtMost(8) * 30L); a.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
            Style.Mono -> a.animateTo(1f, tween(160))
        }
    }
    return graphicsLayer {
        alpha = a.value
        if (!look.mono) {
            translationY = (1f - a.value) * (if (look.playful) 48 else 16).dp.toPx()
            if (look.playful) {
                val s = 0.94f + 0.06f * a.value
                scaleX = s
                scaleY = s
            }
        }
    }
}

/** A small rounded label with a colour dot; outlined in Mono. */
@Composable
fun Pill(text: String, dot: Color? = null, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier: Modifier = Modifier) {
    val mono = LocalLook.current.mono
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (mono) Color.Transparent else container,
        border = if (mono) BorderStroke(1.dp, cs.outline) else null,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(if (mono) 6.dp else 10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (mono) text.uppercase() else text,
                style = if (mono) MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.labelLarge,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

/**
 * The top card of each tab. Expressive: diagonal gradient with a morphing blob. Calm: one flat tonal
 * surface. Mono: black with a hairline edge and a faint dot grid.
 */
@Composable
fun HeroCard(
    a: RoundedPolygon,
    b: RoundedPolygon,
    modifier: Modifier = Modifier,
    spinning: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = LocalLook.current
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (look.mono) 24.dp else 36.dp)
    // Content reads onPrimaryContainer; point it at the right colour for each surface.
    val on = if (look.playful) cs.onPrimaryContainer else cs.onSurface
    val base = when (look.style) {
        Style.Expressive -> modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer)))
        Style.Calm -> modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainerHigh)
        Style.Mono -> modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainerLowest).border(1.dp, cs.outline, shape)
    }
    androidx.compose.material3.MaterialExpressiveTheme(colorScheme = cs.copy(onPrimaryContainer = on), motionScheme = MaterialTheme.motionScheme, typography = MaterialTheme.typography) {
        Box(base) {
            when (look.style) {
                Style.Expressive -> MorphBlob(
                    a, b, Brush.radialGradient(listOf(cs.primary.copy(alpha = 0.28f), cs.primary.copy(alpha = 0.06f))),
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(190.dp).graphicsLayer { translationX = 60f; translationY = -40f },
                    spinning = spinning,
                )
                Style.Mono -> {
                    val dot = cs.onSurface.copy(alpha = 0.06f)
                    Canvas(Modifier.matchParentSize()) {
                        val step = 14.dp.toPx()
                        var y = step / 2
                        while (y < size.height) {
                            var x = step / 2
                            while (x < size.width) { drawCircle(dot, 1.2.dp.toPx(), Offset(x, y)); x += step }
                            y += step
                        }
                    }
                }
                Style.Calm -> Unit
            }
            Box(Modifier.padding(24.dp), content = content)
        }
    }
}
