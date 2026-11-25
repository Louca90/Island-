package com.louca.island.ui

import android.graphics.CornerPathEffect
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.sin

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Fills a polygon with rounded joins, drawn natively for crisp anti-aliasing. */
private fun DrawScope.roundPoly(pts: List<Offset>, color: Color, corner: Float) {
    if (pts.isEmpty()) return
    val p = android.graphics.Path()
    p.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
    p.close()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = corner * 0.9f
        pathEffect = CornerPathEffect(corner)
    }
    drawContext.canvas.nativeCanvas.drawPath(p, paint)
}

/** Play ⇄ Pause: the two bars morph into the two halves of the triangle. No overshoot. */
@Composable
fun PlayPauseIcon(playing: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(
        if (playing) 0f else 1f,
        tween(300, easing = FastOutSlowInEasing), label = "pp"
    )
    Canvas(modifier.size(size)) {
        val t = p.coerceIn(0f, 1f)
        val w = this.size.width
        val corner = w * 0.075f
        fun o(x: Float, y: Float) = Offset(x * w, y * w)
        if (t >= 0.995f) {
            roundPoly(listOf(o(.27f, .15f), o(.87f, .5f), o(.27f, .85f)), color, corner)
            return@Canvas
        }
        val pauseL = listOf(o(.22f, .17f), o(.415f, .17f), o(.415f, .83f), o(.22f, .83f))
        val pauseR = listOf(o(.585f, .17f), o(.78f, .17f), o(.78f, .83f), o(.585f, .83f))
        val playL = listOf(o(.27f, .15f), o(.57f, .3225f), o(.57f, .6775f), o(.27f, .85f))
        val playR = listOf(o(.57f, .3225f), o(.87f, .495f), o(.87f, .505f), o(.57f, .6775f))
        val l = pauseL.indices.map { Offset(lerp(pauseL[it].x, playL[it].x, t), lerp(pauseL[it].y, playL[it].y, t)) }
        val r = pauseR.indices.map { Offset(lerp(pauseR[it].x, playR[it].x, t), lerp(pauseR[it].y, playR[it].y, t)) }
        roundPoly(l, color, corner)
        roundPoly(r, color, corner)
    }
}

/** Next / previous: one triangle grows in, the pair slides, the last one shrinks out. */
@Composable
fun SkipIcon(forward: Boolean, trigger: Int, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        t.snapTo(0f)
    }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val k = t.value
        fun tri(x0: Float, s: Float, pivot: Offset): List<Offset> {
            val pts = listOf(Offset(x0, .24f), Offset(x0 + .40f, .5f), Offset(x0, .76f))
            return pts.map { Offset((pivot.x + (it.x - pivot.x) * s) * w, (pivot.y + (it.y - pivot.y) * s) * w) }
        }
        withTransform({ if (!forward) scale(-1f, 1f, Offset(w / 2, w / 2)) }) {
            if (k > 0.001f) roundPoly(tri(.10f, k, Offset(.10f, .5f)), color, w * .06f)
            val x = lerp(.10f, .50f, k)
            roundPoly(tri(x, 1f, Offset(x, .5f)), color, w * .06f)
            val s = 1f - k
            if (s > 0.001f) roundPoly(tri(.50f, s, Offset(.90f, .5f)), color, w * .06f)
        }
    }
}

/** Padlock whose shackle lifts and swings open on unlock. */
@Composable
fun LockIcon(unlocked: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val u by animateFloatAsState(
        if (unlocked) 1f else 0f,
        tween(520, easing = FastOutSlowInEasing), label = "lock"
    )
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val lift = (u * 1.7f).coerceIn(0f, 1f)
        val swing = ((u - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val shackle = Path().apply {
            moveTo(.335f * w, .50f * w)
            lineTo(.335f * w, .33f * w)
            cubicTo(.335f * w, .13f * w, .665f * w, .13f * w, .665f * w, .33f * w)
            lineTo(.665f * w, .50f * w)
        }
        translate(0f, -0.11f * w * lift) {
            rotate(24f * swing, Offset(.665f * w, .50f * w)) {
                drawPath(shackle, color, style = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        drawRoundRect(
            color, topLeft = Offset(.20f * w, .45f * w), size = Size(.60f * w, .44f * w),
            cornerRadius = CornerRadius(.11f * w)
        )
    }
}

/** Four bars that dance while playing and settle when paused. */
@Composable
fun Equalizer(playing: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "eq")
    val durs = intArrayOf(430, 560, 380, 510)
    val bars = durs.mapIndexed { i, d ->
        inf.animateFloat(
            0.28f, 1f,
            infiniteRepeatable(tween(d, delayMillis = 0, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset(i * 120)),
            label = "b$i"
        )
    }
    val amt by animateFloatAsState(if (playing) 1f else 0f, tween(350, easing = FastOutSlowInEasing), label = "amt")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val bw = w * .13f
        val gap = (w - bw * 4) / 3f
        bars.forEachIndexed { i, b ->
            val h = w * lerp(0.22f, b.value * 0.86f, amt).coerceAtLeast(0.2f)
            val x = i * (bw + gap)
            drawRoundRect(color, Offset(x, (w - h) / 2f), Size(bw, h), CornerRadius(bw / 2))
        }
    }
}

/** Stopwatch with a sweeping hand. */
@Composable
fun StopwatchIcon(seconds: Float, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .095f, cap = StrokeCap.Round)
        val c = Offset(w / 2, w * .56f)
        drawCircle(color, w * .34f, c, style = st)
        drawLine(color, Offset(w * .42f, w * .08f), Offset(w * .58f, w * .08f), w * .095f, StrokeCap.Round)
        drawLine(color, Offset(w / 2, w * .1f), Offset(w / 2, w * .2f), w * .095f, StrokeCap.Round)
        rotate(seconds * 6f, c) {
            drawLine(color, c, Offset(c.x, c.y - w * .22f), w * .095f, StrokeCap.Round)
        }
        drawCircle(color, w * .055f, c)
    }
}

/** Breathing record dot. */
@Composable
fun RecDot(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "rec")
    val a by inf.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val r by inf.animateFloat(1f, 1.9f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "r")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(color.copy(alpha = (1.9f - r) / 0.9f * 0.35f), w * .22f * r)
        drawCircle(color.copy(alpha = 0.55f + 0.45f * a), w * .22f)
    }
}

private val boltPts = listOf(.58f to .05f, .22f to .56f, .47f to .56f, .40f to .95f, .78f to .42f, .53f to .42f)

/** Lightning bolt that draws itself, then fills. */
@Composable
fun BoltIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(boltPts[0].first * w, boltPts[0].second * w)
            boltPts.drop(1).forEach { lineTo(it.first * w, it.second * w) }
            close()
        }
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * (t.value / 0.75f).coerceAtMost(1f), seg, true)
        drawPath(seg, color, style = Stroke(w * .08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val fill = ((t.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (fill > 0f) drawPath(path, color.copy(alpha = fill))
    }
}

/** Battery outline with animated fill. */
@Composable
fun BatteryGlyph(level: Int, color: Color, fill: Color, size: Dp, modifier: Modifier = Modifier) {
    val l by animateFloatAsState(level / 100f, tween(900, easing = FastOutSlowInEasing), label = "bat")
    Canvas(modifier.size(size, size * 0.5f)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(color.copy(alpha = .45f), Offset(0f, 0f), Size(w * .9f, h), CornerRadius(h * .3f), style = Stroke(w * .05f))
        drawRoundRect(color.copy(alpha = .45f), Offset(w * .93f, h * .3f), Size(w * .07f, h * .4f), CornerRadius(w * .03f))
        val pad = w * .07f
        drawRoundRect(fill, Offset(pad, pad), Size((w * .9f - pad * 2) * l, h - pad * 2), CornerRadius(h * .18f))
    }
}

private val handsetPath by lazy {
    PathParser().parsePathString(
        "M5.2,3.5h3.3c0.5,0 0.9,0.3 1,0.8l0.9,3.4c0.1,0.4 0,0.8 -0.3,1.1l-1.7,1.5c1.3,2.5 3.3,4.5 5.8,5.8l1.5,-1.7c0.3,-0.3 0.7,-0.4 1.1,-0.3l3.4,0.9c0.5,0.1 0.8,0.5 0.8,1v3.3c0,0.9 -0.8,1.7 -1.7,1.7C10.6,21 3.5,13.9 3.5,5.2C3.5,4.3 4.3,3.5 5.2,3.5z"
    ).toPath()
}

/** Phone handset; [hangUp] turns it 135° and [ring] makes it wiggle. */
@Composable
fun HandsetIcon(color: Color, size: Dp, hangUp: Boolean = false, ring: Boolean = false, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "ring")
    val wig by inf.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(90, easing = LinearEasing), RepeatMode.Reverse), label = "w"
    )
    val burst by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "bu")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val angle = (if (hangUp) 135f else 0f) + if (ring && burst < 0.35f) wig * 12f else 0f
        rotate(angle) {
            scale(w / 24f, w / 24f, Offset.Zero) {
                drawPath(handsetPath, color)
            }
        }
    }
}

/** Speech bubble used as fallback app icon. */
@Composable
fun BubbleIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w * .12f, w * .16f, w * .88f, w * .7f, CornerRadius(w * .18f)))
            moveTo(w * .3f, w * .66f); lineTo(w * .26f, w * .88f); lineTo(w * .48f, w * .68f); close()
        }
        drawPath(p, color)
    }
}

/** Bell; [slash] draws a diagonal line on, [ring] swings it briefly. */
@Composable
fun BellIcon(color: Color, size: Dp, slash: Boolean = false, ring: Boolean = false, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(slash, ring) { t.snapTo(0f); t.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val swing = if (ring) sin(t.value * PI.toFloat() * 4f) * (1f - t.value) * 18f else 0f
        rotate(swing, Offset(w / 2, w * .12f)) {
            val p = Path().apply {
                moveTo(w * .2f, w * .7f)
                cubicTo(w * .27f, w * .6f, w * .27f, w * .52f, w * .27f, w * .42f)
                cubicTo(w * .27f, w * .22f, w * .73f, w * .22f, w * .73f, w * .42f)
                cubicTo(w * .73f, w * .52f, w * .73f, w * .6f, w * .8f, w * .7f)
                close()
            }
            drawPath(p, color)
            drawCircle(color, w * .08f, Offset(w / 2, w * .79f))
            drawCircle(color, w * .05f, Offset(w / 2, w * .2f))
        }
        if (slash) {
            val k = t.value
            drawLine(
                color, Offset(w * .16f, w * .12f), Offset(w * (.16f + .68f * k), w * (.12f + .76f * k)),
                w * .09f, StrokeCap.Round
            )
        }
    }
}

/** Phone with vibration ticks moving out. */
@Composable
fun VibrateIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "vib")
    val x by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(70, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        translate(x * w * .03f, 0f) {
            drawRoundRect(color, Offset(w * .33f, w * .14f), Size(w * .34f, w * .72f), CornerRadius(w * .08f), style = Stroke(w * .08f))
        }
        val st = w * .07f
        drawLine(color, Offset(w * .17f, w * .36f), Offset(w * .17f, w * .64f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .83f, w * .36f), Offset(w * .83f, w * .64f), st, StrokeCap.Round)
    }
}

/** Crescent moon (Do Not Disturb). */
@Composable
fun MoonIcon(color: Color, cut: Color, size: Dp, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(color, w * .36f, Offset(w / 2, w / 2))
        drawCircle(cut, w * .32f, Offset(w * (.5f + .55f * t.value), w * (.5f - .25f * t.value)))
    }
}

/** Microphone for voice recordings. */
@Composable
fun MicIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(color, Offset(w * .36f, w * .1f), Size(w * .28f, w * .48f), CornerRadius(w * .14f))
        val arc = Path().apply {
            moveTo(w * .24f, w * .44f)
            cubicTo(w * .24f, w * .78f, w * .76f, w * .78f, w * .76f, w * .44f)
        }
        drawPath(arc, color, style = Stroke(w * .08f, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, w * .7f), Offset(w / 2, w * .88f), w * .08f, StrokeCap.Round)
    }
}

/** Fallback turn arrow for navigation. */
@Composable
fun NavArrow(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * .3f, w * .9f); lineTo(w * .3f, w * .5f)
            cubicTo(w * .3f, w * .38f, w * .38f, w * .32f, w * .5f, w * .32f)
            lineTo(w * .7f, w * .32f)
        }
        drawPath(p, color, style = Stroke(w * .11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        roundPoly(listOf(Offset(w * .64f, w * .14f), Offset(w * .88f, w * .32f), Offset(w * .64f, w * .5f)), color, w * .05f)
    }
}

/** Determinate ring; progress < 0 spins as indeterminate. */
@Composable
fun ProgressRing(progress: Float, color: Color, track: Color, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "ring")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "s")
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400, easing = FastOutSlowInEasing), label = "p")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .14f, cap = StrokeCap.Round)
        val inset = w * .07f
        val tl = Offset(inset, inset); val sz = Size(w - inset * 2, w - inset * 2)
        drawArc(track, 0f, 360f, false, tl, sz, style = st)
        if (progress < 0f) drawArc(color, spin - 90f, 90f, false, tl, sz, style = st)
        else drawArc(color, -90f, 360f * p, false, tl, sz, style = st)
    }
}


/** Heart that fills from the bottom when liked. */
@Composable
fun HeartIcon(liked: Boolean, color: Color, fillColor: Color, size: Dp, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(if (liked) 1f else 0f, tween(320, easing = FastOutSlowInEasing), label = "heart")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * .5f, w * .84f)
            cubicTo(w * .14f, w * .6f, w * .06f, w * .38f, w * .2f, w * .24f)
            cubicTo(w * .32f, w * .12f, w * .46f, w * .18f, w * .5f, w * .3f)
            cubicTo(w * .54f, w * .18f, w * .68f, w * .12f, w * .8f, w * .24f)
            cubicTo(w * .94f, w * .38f, w * .86f, w * .6f, w * .5f, w * .84f)
            close()
        }
        if (f > 0f) {
            clipRect(top = w * (.86f - .74f * f)) { drawPath(p, fillColor) }
        }
        drawPath(p, androidx.compose.ui.graphics.lerp(color, fillColor, f), style = Stroke(w * .085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Shuffle arrows; active state gets the accent and a dot. */
@Composable
fun ShuffleIcon(active: Boolean, color: Color, activeColor: Color, size: Dp, modifier: Modifier = Modifier) {
    val k by animateFloatAsState(if (active) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "sh")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val col = androidx.compose.ui.graphics.lerp(color, activeColor, k)
        val st = Stroke(w * .08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val a = Path().apply {
            moveTo(w * .14f, w * .3f); lineTo(w * .3f, w * .3f)
            cubicTo(w * .5f, w * .3f, w * .5f, w * .66f, w * .7f, w * .66f); lineTo(w * .84f, w * .66f)
        }
        val b = Path().apply {
            moveTo(w * .14f, w * .66f); lineTo(w * .3f, w * .66f)
            cubicTo(w * .5f, w * .66f, w * .5f, w * .3f, w * .7f, w * .3f); lineTo(w * .84f, w * .3f)
        }
        drawPath(a, col, style = st); drawPath(b, col, style = st)
        listOf(.3f, .66f).forEach { y ->
            val head = Path().apply {
                moveTo(w * .74f, w * (y - .09f)); lineTo(w * .85f, w * y); lineTo(w * .74f, w * (y + .09f))
            }
            drawPath(head, col, style = st)
        }
        if (k > 0f) drawCircle(activeColor.copy(alpha = k), w * .05f, Offset(w * .5f, w * .9f))
    }
}

@Composable
fun CrossIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawLine(color, Offset(0f, 0f), Offset(w, w), w * .2f, StrokeCap.Round)
        drawLine(color, Offset(w, 0f), Offset(0f, w), w * .2f, StrokeCap.Round)
    }
}

@Composable
fun SendArrow(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(w / 2, w * .85f), Offset(w / 2, w * .18f), w * .13f, StrokeCap.Round)
        val head = Path().apply { moveTo(w * .2f, w * .46f); lineTo(w / 2, w * .16f); lineTo(w * .8f, w * .46f) }
        drawPath(head, color, style = st)
    }
}

/** Check mark that draws itself. */
@Composable
fun CheckIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply { moveTo(w * .18f, w * .52f); lineTo(w * .42f, w * .74f); lineTo(w * .84f, w * .28f) }
        val pm = PathMeasure().apply { setPath(p, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * t.value, seg, true)
        drawPath(seg, color, style = Stroke(w * .13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Lap flag. */
@Composable
fun LapIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawLine(color, Offset(w * .26f, w * .12f), Offset(w * .26f, w * .9f), w * .1f, StrokeCap.Round)
        roundPoly(listOf(Offset(w * .3f, w * .14f), Offset(w * .8f, w * .3f), Offset(w * .3f, w * .5f)), color, w * .06f)
    }
}

/** Circular arrow (reset). */
@Composable
fun ResetIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val inset = w * .16f
        drawArc(color, -60f, 300f, false, Offset(inset, inset), Size(w - inset * 2, w - inset * 2),
            style = Stroke(w * .1f, cap = StrokeCap.Round))
        roundPoly(listOf(Offset(w * .6f, w * .06f), Offset(w * .86f, w * .2f), Offset(w * .6f, w * .36f)), color, w * .04f)
    }
}

@Composable
fun CalendarIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(color, Offset(w * .14f, w * .2f), Size(w * .72f, w * .66f), CornerRadius(w * .14f), style = Stroke(w * .09f))
        drawLine(color, Offset(w * .14f, w * .4f), Offset(w * .86f, w * .4f), w * .09f)
        drawLine(color, Offset(w * .34f, w * .1f), Offset(w * .34f, w * .26f), w * .09f, StrokeCap.Round)
        drawLine(color, Offset(w * .66f, w * .1f), Offset(w * .66f, w * .26f), w * .09f, StrokeCap.Round)
        drawCircle(color, w * .06f, Offset(w * .38f, w * .62f))
    }
}

@Composable
fun HeadphonesIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawArc(color, 180f, 180f, false, Offset(w * .14f, w * .16f), Size(w * .72f, w * .7f), style = Stroke(w * .09f, cap = StrokeCap.Round))
        drawRoundRect(color, Offset(w * .1f, w * .52f), Size(w * .2f, w * .34f), CornerRadius(w * .08f))
        drawRoundRect(color, Offset(w * .7f, w * .52f), Size(w * .2f, w * .34f), CornerRadius(w * .08f))
    }
}

@Composable
fun KeyIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(color, w * .2f, Offset(w * .32f, w * .5f), style = Stroke(w * .1f))
        drawLine(color, Offset(w * .52f, w * .5f), Offset(w * .9f, w * .5f), w * .1f, StrokeCap.Round)
        drawLine(color, Offset(w * .78f, w * .5f), Offset(w * .78f, w * .66f), w * .1f, StrokeCap.Round)
        drawLine(color, Offset(w * .9f, w * .5f), Offset(w * .9f, w * .62f), w * .1f, StrokeCap.Round)
    }
}

/** Alarm clock that keeps shaking while it rings. */
@Composable
fun AlarmClockIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "al")
    val wig by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(80, easing = LinearEasing), RepeatMode.Reverse), label = "w")
    val burst by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "b")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        rotate(if (burst < .4f) wig * 10f else 0f) {
            val c = Offset(w / 2, w * .55f)
            drawCircle(color, w * .32f, c, style = Stroke(w * .09f))
            drawLine(color, c, Offset(c.x, c.y - w * .17f), w * .08f, StrokeCap.Round)
            drawLine(color, c, Offset(c.x + w * .12f, c.y + w * .06f), w * .08f, StrokeCap.Round)
            drawArc(color, 200f, 70f, false, Offset(w * .04f, w * .06f), Size(w * .34f, w * .34f), style = Stroke(w * .09f, cap = StrokeCap.Round))
            drawArc(color, 270f, 70f, false, Offset(w * .62f, w * .06f), Size(w * .34f, w * .34f), style = Stroke(w * .09f, cap = StrokeCap.Round))
        }
    }
}

/** Speech bubble with text lines (lyrics). */
@Composable
fun LyricsIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w * .1f, w * .14f, w * .9f, w * .72f, CornerRadius(w * .16f)))
        }
        drawPath(p, color, style = st)
        drawLine(color, Offset(w * .3f, w * .72f), Offset(w * .24f, w * .9f), w * .09f, StrokeCap.Round)
        drawLine(color, Offset(w * .28f, w * .36f), Offset(w * .72f, w * .36f), w * .08f, StrokeCap.Round)
        drawLine(color, Offset(w * .28f, w * .5f), Offset(w * .56f, w * .5f), w * .08f, StrokeCap.Round)
    }
}

/** Screen recording: the classic record button — a ring with a pulsing solid dot. */
@Composable
fun ScreenRecIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "sr")
    val p by inf.animateFloat(1f, .72f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(color, w * .42f, style = Stroke(w * .1f))
        drawCircle(color, w * .24f * p)
    }
}

/** Flashlight; [on] adds a soft animated beam. */
@Composable
fun FlashIcon(color: Color, size: Dp, on: Boolean = false, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "fl")
    val g by inf.animateFloat(.55f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val head = Path().apply {
            moveTo(w * .3f, w * .3f); lineTo(w * .7f, w * .3f); lineTo(w * .62f, w * .46f); lineTo(w * .38f, w * .46f); close()
        }
        drawPath(head, color)
        drawRoundRect(color, Offset(w * .39f, w * .48f), Size(w * .22f, w * .44f), CornerRadius(w * .06f))
        if (on) {
            val st = w * .07f
            val c = color.copy(alpha = g)
            drawLine(c, Offset(w * .5f, w * .2f), Offset(w * .5f, w * .06f), st, StrokeCap.Round)
            drawLine(c, Offset(w * .3f, w * .22f), Offset(w * .2f, w * .1f), st, StrokeCap.Round)
            drawLine(c, Offset(w * .7f, w * .22f), Offset(w * .8f, w * .1f), st, StrokeCap.Round)
        }
    }
}

@Composable
fun TrashIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(w * .16f, w * .26f), Offset(w * .84f, w * .26f), w * .09f, StrokeCap.Round)
        drawLine(color, Offset(w * .4f, w * .14f), Offset(w * .6f, w * .14f), w * .09f, StrokeCap.Round)
        val body = Path().apply {
            moveTo(w * .24f, w * .32f); lineTo(w * .3f, w * .86f); lineTo(w * .7f, w * .86f); lineTo(w * .76f, w * .32f)
        }
        drawPath(body, color, style = st)
    }
}

/** Hourglass (countdown timer). */
@Composable
fun HourglassIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(w * .2f, w * .1f), Offset(w * .8f, w * .1f), w * .1f, StrokeCap.Round)
        drawLine(color, Offset(w * .2f, w * .9f), Offset(w * .8f, w * .9f), w * .1f, StrokeCap.Round)
        val body = Path().apply {
            moveTo(w * .28f, w * .12f); lineTo(w * .28f, w * .26f); lineTo(w * .5f, w * .5f); lineTo(w * .28f, w * .74f); lineTo(w * .28f, w * .88f)
            moveTo(w * .72f, w * .12f); lineTo(w * .72f, w * .26f); lineTo(w * .5f, w * .5f); lineTo(w * .72f, w * .74f); lineTo(w * .72f, w * .88f)
        }
        drawPath(body, color, style = st)
        val sand = Path().apply { moveTo(w * .36f, w * .84f); lineTo(w * .5f, w * .66f); lineTo(w * .64f, w * .84f); close() }
        drawPath(sand, color)
    }
}
