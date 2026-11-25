package com.louca.island.ui

import android.app.WallpaperManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.louca.island.Act
import com.louca.island.ActBtn
import com.louca.island.IslandStore
import com.louca.island.Kind
import com.louca.island.Settings
import com.louca.island.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ colors

data class IslandColors(
    val bg: Color, val fg: Color, val dim: Color, val track: Color, val btn: Color, val dark: Boolean,
) {
    val orange get() = if (dark) Color(0xFFFF9F0A) else Color(0xFFC86400)
    val red get() = if (dark) Color(0xFFFF453A) else Color(0xFFD02A20)
    val green get() = if (dark) Color(0xFF30D158) else Color(0xFF1C8C3E)
}

private fun darkColors(bg: Color) =
    IslandColors(bg, Color.White, Color(0xFFAAB0BA), Color.White.copy(alpha = .2f), Color.White.copy(alpha = .13f), true)

private fun lightColors(bg: Color) =
    IslandColors(bg, Color(0xFF101114), Color(0xFF555A64), Color.Black.copy(alpha = .13f), Color.Black.copy(alpha = .07f), false)

fun islandColors(s: Settings, wall: Int?): IslandColors = when (s.theme) {
    ThemeMode.BLACK -> darkColors(Color.Black)
    ThemeMode.GRAPHITE -> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(wall ?: 0xFF3E91FF.toInt(), hsv)
        val tint = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], min(hsv[1], .55f).coerceAtLeast(.25f), .85f)))
        darkColors(lerp(Color(0xFF16171B), tint, .13f))
    }
    ThemeMode.COLOR -> Color(s.color).let { if (it.luminance() > .45f) lightColors(it) else darkColors(it) }
    ThemeMode.LIGHT -> lightColors(Color(0xFFF1F2F5))
}

@Composable
fun rememberIslandColors(s: Settings): IslandColors {
    val ctx = LocalContext.current
    val tick by IslandStore.wallTick.collectAsState()
    val wall = remember(tick, s.theme) { wallColor(ctx) }
    return remember(s, wall) { islandColors(s, wall) }
}

fun wallColor(ctx: Context): Int? = runCatching {
    WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
}.getOrNull()

private fun musicAccent(a: Act, c: IslandColors): Color {
    if (a.accent == 0) return c.fg
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(a.accent, hsv)
    hsv[1] = max(hsv[1], .45f)
    hsv[2] = if (c.dark) max(hsv[2], .88f) else min(hsv[2], .5f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// ------------------------------------------------------------------ geometry

data class Spec(val w: Dp, val h: Dp, val r: Dp)

fun compactH(s: Settings) = s.pillH.dp

object Ticker { val now = androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }

data class Mask(val on: Boolean, val size: Dp, val offsetX: Dp, val pillH: Dp, val guide: Boolean)
val LocalMask = androidx.compose.runtime.staticCompositionLocalOf { Mask(false, 0.dp, 0.dp, 34.dp, false) }

fun maskExtra(s: Settings): Dp =
    if (s.camMask) (s.pillH / 2 + s.camSize / 2 + 6f - 12f).coerceAtLeast(0f).dp else 0.dp

fun notifH(a: Act): Dp =
    (100 + (if (a.actions.isNotEmpty()) 52 else 0) + (if (IslandStore.replying.value == a.key) 54 else 0)).dp

fun isExpanded(a: Act?, flag: Boolean): Boolean = when (a?.kind) {
    null, Kind.CHARGE, Kind.LOCK, Kind.SYSTEM, Kind.OTP, Kind.AUDIO -> false
    Kind.NOTIF, Kind.ALARM -> true
    Kind.CALL -> a.incoming || flag
    else -> flag
}

fun spec(a: Act?, flag: Boolean, s: Settings, maxW: Dp): Spec {
    val h = compactH(s)
    val big = min(s.expW, maxW.value - 16f).dp
    fun pill(w: Float) = Spec((w * s.pillW).dp, h, h / 2)
    if (a == null) return if (s.showIdle) pill(96f) else Spec(h, h, h / 2)
    val ex = isExpanded(a, flag)
    val base = when (a.kind) {
        Kind.MUSIC -> if (ex) Spec(big, (178 + if (IslandStore.lyricsOpen.value && IslandStore.lyrics.value?.key == a.lyricsKey) 150 else 0).dp, 42.dp) else pill(212f)
        Kind.TIMER -> if (ex) Spec(big, 96.dp, 42.dp) else pill(196f)
        Kind.RECORD -> if (ex) Spec(big, 84.dp, 40.dp) else pill(212f)
        Kind.CALL -> if (ex) Spec(big, 84.dp, 42.dp) else pill(196f)
        Kind.NOTIF -> Spec(big, notifH(a), 34.dp)
        Kind.INBOX -> if (ex) Spec(big, IslandStore.currentNotif()?.let { notifH(it) } ?: 100.dp, 34.dp) else pill(150f)
        Kind.CHARGE -> pill(if (a.sub.isNotBlank()) 270f else 222f)
        Kind.OTP -> pill(236f)
        Kind.ALARM -> Spec(big, 84.dp, 42.dp)
        Kind.EVENT -> if (ex) Spec(big, 88.dp, 40.dp) else pill(184f)
        Kind.AUDIO -> pill(240f)
        Kind.LOCK -> pill(128f)
        Kind.SYSTEM -> pill(204f)
        Kind.NAV -> if (ex) Spec(big, 92.dp, 40.dp) else pill(196f)
        Kind.LIVE -> if (ex) Spec(big, if (a.isSport) 92.dp else if (a.progress != -1f) 112.dp else 92.dp, 40.dp) else pill(200f)
    }
    return if (ex) base.copy(h = base.h + maskExtra(s)) else base
}

// ------------------------------------------------------------------ root

private data class CKey(val id: String?, val kind: Kind?, val ex: Boolean)

/**
 * The whole island: pill/card + side bubbles. Used by the overlay and by the in-app preview.
 * [onWindowSize] receives the window size in px the island needs (0,0 = nothing to show).
 */
@Composable
fun IslandRoot(
    acts: List<Act>,
    pick: String?,
    expandedFlag: Boolean,
    s: Settings,
    c: IslandColors,
    hidden: Boolean,
    maxW: Dp,
    shiftX: Dp = 0.dp,
    onTouchZone: (android.graphics.Rect?) -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val primary = IslandStore.primary(acts, pick)
    val ex = isExpanded(primary, expandedFlag)
    val notifList by IslandStore.notifs.collectAsState()
    val inboxIdx by IslandStore.inboxIndex.collectAsState()
    val replyKey by IslandStore.replying.collectAsState()
    val lyr by IslandStore.lyrics.collectAsState()
    val lyrOpen by IslandStore.lyricsOpen.collectAsState()
    lyr?.key; lyrOpen
    notifList.size + inboxIdx + (replyKey?.length ?: 0)
    val bubbles = if (primary?.kind == Kind.NOTIF) emptyList() else IslandStore.others(acts, primary)
    val sp = spec(primary, expandedFlag, s, maxW)
    val h0 = compactH(s)
    val visible = !hidden && (primary != null || s.showIdle)

    val grow = spring<Dp>(dampingRatio = 1f, stiffness = 380f)
    val shrink = spring<Dp>(dampingRatio = 1f, stiffness = 520f)
    val w by animateDpAsState(sp.w, if (ex) grow else shrink, label = "w")
    val h by animateDpAsState(sp.h, if (ex) grow else shrink, label = "h")
    val r by animateDpAsState(sp.r, if (ex) grow else shrink, label = "r")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 160 else 240), label = "a")
    val vScale by animateFloatAsState(if (visible) 1f else .55f, spring(1f, 500f), label = "vs")

    var pressed by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val press by animateFloatAsState(if (pressed && !ex) .95f else 1f, spring(1f, 900f), label = "p")
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }

    // --- touch zone: the visual window never resizes; only an invisible touch window follows the island.
    //     Grow at once, shrink once the animation has settled.
    val side = if (ex) 0f else bubbles.size * (h0.value + 6f)
    val zw = min(max(sp.w.value + side * 2, bubbles.size * (h0.value + 6f)) + 16f, maxW.value)
    val zh = max(sp.h.value, h0.value) + 8f + if (ex && bubbles.isNotEmpty()) h0.value + 10f else 0f
    var zone by remember { mutableStateOf(0f to 0f) }
    val zoneCb by rememberUpdatedState(onTouchZone)
    LaunchedEffect(zw, zh, visible) {
        if (!visible) { zone = 0f to 0f; zoneCb(null); return@LaunchedEffect }
        val uw = max(zw, zone.first); val uh = max(zh, zone.second)
        zone = uw to uh
        if (uw != zw || uh != zh) { delay(450); zone = zw to zh }
    }

    // --- auto-collapse after a while
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(ex, primary?.id, poke) {
        if (ex && primary?.kind != Kind.NOTIF && primary?.kind != Kind.INBOX && replyKey == null &&
            !(primary?.kind == Kind.CALL && primary.incoming)) {
            delay(9000); IslandStore.expanded.value = false
        }
    }

    val dir by IslandStore.swipeDir.collectAsState()
    val primaryState by rememberUpdatedState(primary)

    val anyChrono = acts.any { it.chrono }
    LaunchedEffect(anyChrono) {
        while (anyChrono) withFrameMillis { Ticker.now.longValue = System.currentTimeMillis() }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMask provides Mask(s.camMask, s.camSize.dp, s.offsetX.dp, h0, s.camGuide)
    ) {
    Box(Modifier.fillMaxSize().offset(x = shiftX).pointerInput(Unit) { detectTapGestures { IslandStore.expanded.value = false } }) {
        if (zone.first > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(zone.first.dp, zone.second.dp)
                    .onGloballyPositioned { co ->
                        val b = co.boundsInWindow()
                        zoneCb(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
                    }
            )
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    this.alpha = alpha
                    val sc = vScale * press
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                .size(w, h)
                .clip(RoundedCornerShape(r))
                .background(c.bg)
                .semantics { contentDescription = primary?.let { "${it.appName} ${it.title}" } ?: "Island" }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                        onTap = {
                            val p = primaryState ?: return@detectTapGestures
                            poke++
                            when (p.kind) {
                                Kind.NOTIF -> if (IslandStore.replying.value == null) { p.open(); IslandStore.remove(p.id) }
                                Kind.INBOX -> if (IslandStore.expanded.value) {
                                    if (IslandStore.replying.value == null) IslandStore.currentNotif()?.let {
                                        it.open(); IslandStore.expanded.value = false
                                    }
                                } else IslandStore.expanded.value = true
                                Kind.LOCK, Kind.CHARGE, Kind.ALARM, Kind.AUDIO, Kind.SYSTEM -> {}
                                Kind.OTP -> IslandStore.copyOtp(p)
                                Kind.CALL -> if (!p.incoming) IslandStore.expanded.value = !IslandStore.expanded.value
                                else -> IslandStore.expanded.value = !IslandStore.expanded.value
                            }
                        },
                        onLongPress = {
                            val p = primaryState ?: return@detectTapGestures
                            if (p.kind == Kind.LOCK) return@detectTapGestures
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            val target = if (p.kind == Kind.INBOX) IslandStore.currentNotif() ?: p else p
                            target.open()
                            IslandStore.replying.value = null
                            IslandStore.expanded.value = false
                            if (p.kind == Kind.NOTIF) IslandStore.remove(p.id)
                        },
                    )
                }
                .pointerInput(Unit) {
                    var dx = 0f; var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            val t = 44.dp.toPx()
                            val p = primaryState
                            when {
                                abs(dx) > abs(dy) && abs(dx) > t -> IslandStore.cycle(if (dx < 0) 1 else -1)
                                dy < -t * .7f -> if (p?.kind == Kind.NOTIF) IslandStore.remove(p.id) else IslandStore.expanded.value = false
                                dy > t * .7f && p != null && p.kind != Kind.NOTIF -> IslandStore.expanded.value = true
                            }
                            scope.launch { dragX.animateTo(0f, spring(1f, 500f)) }
                            scope.launch { dragY.animateTo(0f, spring(1f, 500f)) }
                        },
                        onDragCancel = {
                            scope.launch { dragX.animateTo(0f, spring(1f, 500f)) }
                            scope.launch { dragY.animateTo(0f, spring(1f, 500f)) }
                        },
                    ) { ch, d ->
                        val k = primaryState?.kind
                        if (k == Kind.LOCK || k == Kind.CHARGE || k == Kind.SYSTEM) return@detectDragGestures
                        ch.consume(); dx += d.x; dy += d.y; poke++
                    }
                },
        ) {
            val key = CKey(primary?.id, primary?.kind, ex)
            AnimatedContent(
                targetState = key,
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val same = initialState.id == targetState.id
                    val lateral = !same && (initialState.kind?.prio ?: 9) < 5 && (targetState.kind?.prio ?: 9) < 5
                    val d = if (lateral) dir else 0
                    (fadeIn(tween(200, 70)) +
                        scaleIn(tween(320, 30, FastOutSlowInEasing), if (same) .96f else .9f, TransformOrigin(.5f, .1f)) +
                        slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it * d / 4 })
                        .togetherWith(
                            fadeOut(tween(110)) + scaleOut(tween(200), .92f, TransformOrigin(.5f, .1f)) +
                                slideOutHorizontally(tween(260)) { -it * d / 4 }
                        )
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "content",
            ) { k ->
                val blur by transition.animateDp(label = "blur") { if (it == EnterExitState.Visible) 0.dp else 10.dp }
                val all by IslandStore.acts.collectAsState()
                val rk by IslandStore.replying.collectAsState()
                val nl by IslandStore.notifs.collectAsState()
                val ix by IslandStore.inboxIndex.collectAsState()
                val ly by IslandStore.lyrics.collectAsState()
                val lo by IslandStore.lyricsOpen.collectAsState()
                remember(rk, nl, ix, ly, lo) { 0 }
                val cache = remember { arrayOfNulls<Act>(1) }
                val a = all.find { it.id == k.id } ?: cache[0]
                if (a != null) cache[0] = a
                val sp2 = spec(a, k.ex, s, maxW)
                val extra = if (k.ex) maskExtra(s) else 0.dp
                Box(
                    Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.TopCenter, unbounded = true)
                        .requiredSize(sp2.w, sp2.h)
                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                        .padding(top = extra)
                ) {
                    if (a != null) Content(a, k.ex, c, h0) { poke++ }
                }
            }
            if (s.camGuide) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = h0 / 2 - s.camSize.dp / 2)
                        .size(s.camSize.dp)
                        .border(1.5.dp, c.dim, CircleShape)
                )
            }
        }

        bubbles.forEachIndexed { i, b ->
            androidx.compose.runtime.key(b.id) {
                // compact: to the right of the pill · expanded: a row under the card
                val tx = if (ex) (h0 + 6.dp) * (i - (bubbles.size - 1) / 2f) else w / 2 + 6.dp + h0 / 2 + (h0 + 6.dp) * i
                val ty = if (ex) h + 10.dp else 0.dp
                val offX by animateDpAsState(tx, spring(1f, 500f), label = "bx")
                val offY by animateDpAsState(ty, spring(1f, 500f), label = "by")
                Bubble(
                    b, c, h0,
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = offX, y = offY)
                        .graphicsLayer { this.alpha = alpha }
                ) { IslandStore.select(b.id) }
            }
        }
    }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Bubble(a: Act, c: IslandColors, size: Dp, modifier: Modifier, onTap: () -> Unit) {
    Box(modifier) {
        BubbleCore(a, c, size, onTap)
        if (a.kind == Kind.INBOX) CountBadge(a.level, c, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 2.dp))
    }
}

@Composable
private fun CountBadge(n: Int, c: IslandColors, modifier: Modifier = Modifier) {
    Box(
        modifier.height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.red).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { T(if (n > 99) "99+" else "$n", Color.White, 9, FontWeight.Bold, style = Num) }
}

@Composable
private fun BubbleCore(a: Act, c: IslandColors, size: Dp, onTap: () -> Unit) {
    val modifier = Modifier
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier
            .graphicsLayer {
                scaleX = appear.value; scaleY = appear.value
                alpha = appear.value.coerceIn(0f, 1f)
                translationX = (1f - appear.value) * -size.toPx() * .6f
            }
            .size(size)
            .clip(CircleShape)
            .background(c.bg)
            .semantics { contentDescription = "Afficher ${a.appName}"; role = Role.Button }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        val inner = size * .58f
        when (a.kind) {
            Kind.MUSIC -> if (a.art != null) {
                Image(
                    a.art.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size * .72f).clip(CircleShape)
                )
            } else Equalizer(a.playing, musicAccent(a, c), inner * .8f)
            Kind.TIMER -> StopwatchIcon(rememberSeconds(a), c.orange, inner)
            Kind.RECORD -> RecDot(c.red, inner)
            Kind.CALL -> HandsetIcon(c.green, inner * .85f)
            Kind.INBOX -> SmallAppIcon(a, c, size * .72f)
            Kind.EVENT -> CalendarIcon(c.fg, inner)
            Kind.NAV -> NavIcon(a, c, inner)
            Kind.LIVE -> when {
                a.isSport -> T(a.score.replace(" ", ""), c.fg, 10, FontWeight.Bold, style = Num)
                a.progress != -1f -> ProgressRing(a.progress, c.fg, c.track, inner)
                else -> SmallAppIcon(a, c, size * .72f)
            }
            else -> {}
        }
    }
}

@Composable
private fun rememberSeconds(a: Act): Float {
    val now = Ticker.now.longValue
    if (!a.chrono) return 0f
    return abs(now - a.base) / 1000f
}

@Composable
private fun rememberClock(a: Act, tenths: Boolean): String {
    val now = Ticker.now.longValue
    if (!a.chrono) return a.timeText.ifEmpty { "--:--" }
    val ms = if (a.countDown) (a.base - now).coerceAtLeast(0) else (now - a.base).coerceAtLeast(0)
    return fmt(ms, tenths)
}

fun fmt(ms: Long, tenths: Boolean = false): String {
    val t = ms / 1000
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    return if (tenths && h == 0L) base + ",%d".format((ms % 1000) / 100) else base
}

private val Num = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun T(
    text: String, color: Color, size: Int, weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1, modifier: Modifier = Modifier, style: TextStyle = TextStyle.Default, align: TextAlign? = null,
) = Text(
    text, modifier, color = color, fontSize = size.sp, fontWeight = weight, textAlign = align,
    maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style, lineHeight = (size + 4).sp,
)

/** Bouncy tap target. */
@Composable
fun Tap(size: Dp, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var down by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (down) .88f else 1f, spring(1f, 900f), label = "tap")
    val click by rememberUpdatedState(onClick)
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { click() })
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (down) .95f else 1f, spring(1f, 900f), label = "pill")
    val click by rememberUpdatedState(onClick)
    Box(
        modifier
            .height(40.dp)
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { click() })
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, fg, 14, FontWeight.SemiBold) }
}

private fun List<ActBtn>.match(re: String): ActBtn? {
    val r = Regex(re, RegexOption.IGNORE_CASE)
    return firstOrNull { r.containsMatchIn(it.label) }
}

@Composable
private fun AppAvatar(a: Act, size: Dp, c: IslandColors) {
    val pop = remember { Animatable(.7f) }
    LaunchedEffect(a.key) { pop.snapTo(.7f); pop.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    Box(Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        val main = a.icon ?: a.appIcon
        if (main != null) {
            Image(main.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(c.btn), contentAlignment = Alignment.Center) {
                if (a.title.isNotBlank() && a.kind == Kind.CALL) T(a.title.take(1).uppercase(), c.fg, 18, FontWeight.SemiBold)
                else BubbleIcon(c.fg, size * .5f)
            }
        }
        if (a.icon != null && a.appIcon != null) {
            Image(
                a.appIcon.asImageBitmap(), null,
                modifier = Modifier.align(Alignment.BottomEnd).size(size * .4f).clip(CircleShape).background(c.bg).padding(1.5.dp).clip(CircleShape)
            )
        }
    }
}

// ------------------------------------------------------------------ contents

@Composable
private fun Content(a: Act, ex: Boolean, c: IslandColors, h0: Dp, poke: () -> Unit) {
    when (a.kind) {
        Kind.MUSIC -> if (ex) MusicExpanded(a, c, poke) else CompactRow(h0, {
            Crossfade(a.art, label = "art") { art ->
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(h0 - 12.dp).clip(RoundedCornerShape(7.dp)))
                else Box(Modifier.size(h0 - 12.dp).clip(RoundedCornerShape(7.dp)).background(c.btn))
            }
        }, { Equalizer(a.playing, musicAccent(a, c), 18.dp, Modifier.padding(end = 5.dp)) })

        Kind.TIMER -> if (ex) TimerExpanded(a, c, poke) else CompactRow(h0, {
            if (a.variant == 1) SmallAppIcon(a, c, h0 - 12.dp)
            else StopwatchIcon(rememberSeconds(a), c.orange, 19.dp, Modifier.padding(start = 5.dp))
        }, {
            T(rememberClock(a, false), if (a.variant == 1) c.green else c.orange, 15,
                FontWeight.SemiBold, style = Num, modifier = Modifier.padding(end = 6.dp))
        })

        Kind.RECORD -> if (ex) RecordExpanded(a, c) else CompactRow(h0, {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.variant == 1) {
                    MicIcon(c.red, 18.dp, Modifier.padding(start = 5.dp))
                } else {
                    RecDot(c.red, 18.dp, Modifier.padding(start = 3.dp))
                    Spacer(Modifier.width(4.dp)); T("REC", c.red, 13, FontWeight.Bold)
                }
            }
        }, {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(rememberClock(a, false), c.fg, 14, FontWeight.SemiBold, style = Num)
                Spacer(Modifier.width(8.dp))
                Tap(h0 - 10.dp, "Arrêter l'enregistrement", { stopRecord(a) }) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(c.red.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.5.dp)).background(c.red))
                    }
                }
            }
        })

        Kind.CALL -> if (ex) CallExpanded(a, c) else CompactRow(h0, {
            HandsetIcon(c.green, 17.dp, modifier = Modifier.padding(start = 6.dp))
        }, {
            T(rememberClock(a, false), c.green, 15, FontWeight.SemiBold, style = Num, modifier = Modifier.padding(end = 7.dp))
        })

        Kind.NOTIF -> NotifBody(a, c, poke, counter = null, onAction = { IslandStore.remove(a.id) })

        Kind.INBOX -> if (ex) InboxCard(c, poke) else CompactRow(h0, {
            Box(Modifier.padding(start = 0.dp)) {
                val l = IslandStore.notifs.collectAsState().value
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    l.take(3).forEach { SmallAppIcon(it, c, h0 - 12.dp) }
                }
            }
        }, {
            Box(
                Modifier.padding(end = 4.dp).height(h0 - 14.dp).clip(RoundedCornerShape(10.dp)).background(c.btn).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { T("${a.level}", c.fg, 13, FontWeight.SemiBold, style = Num) }
        })

        Kind.CHARGE -> CompactRow(h0, {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoltIcon(c.green, 18.dp, Modifier.padding(start = 4.dp))
                if (a.sub.isBlank()) { Spacer(Modifier.width(5.dp)); T("En charge", c.fg, 13, FontWeight.Medium) }
            }
        }, {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                if (a.sub.isNotBlank()) { T(a.sub, c.dim, 12, style = Num); Spacer(Modifier.width(6.dp)) }
                T("${a.level} %", c.green, 14, FontWeight.SemiBold, style = Num)
                Spacer(Modifier.width(6.dp))
                BatteryGlyph(a.level, c.fg, c.green, 24.dp)
            }
        })

        Kind.LOCK -> CompactRow(h0, {
            LockIcon(a.unlocked, c.fg, 19.dp, Modifier.padding(start = 6.dp))
        }, {})

        Kind.NAV -> if (ex) NavExpanded(a, c) else CompactRow(h0, {
            Box(Modifier.padding(start = 6.dp)) { NavIcon(a, c, 20.dp) }
        }, {
            T(a.title, c.fg, 14, FontWeight.SemiBold, style = Num, modifier = Modifier.padding(end = 6.dp))
        })

        Kind.LIVE -> if (ex) LiveExpanded(a, c) else CompactRow(h0, {
            SmallAppIcon(a, c, h0 - 12.dp)
        }, {
            Box(Modifier.padding(end = 6.dp)) {
                when {
                    a.isSport -> T(a.score, c.fg, 15, FontWeight.SemiBold, style = Num)
                    a.short.isNotBlank() -> T(a.short, c.fg, 14, FontWeight.SemiBold, style = Num)
                    a.progress != -1f -> ProgressRing(a.progress, c.fg, c.track, 20.dp)
                    else -> T(a.title, c.fg, 13, FontWeight.Medium)
                }
            }
        })

        Kind.OTP -> CompactRow(h0, {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                if (a.variant == 1) CheckIcon(c.green, 18.dp) else KeyIcon(c.fg, 19.dp)
                Spacer(Modifier.width(6.dp)); T(a.title, if (a.variant == 1) c.green else c.dim, 13, FontWeight.Medium)
            }
        }, {
            val pretty = if (a.short.length == 6) a.short.substring(0, 3) + " " + a.short.substring(3) else a.short
            T(pretty, c.fg, 16, FontWeight.SemiBold, style = Num, modifier = Modifier.padding(end = 8.dp))
        })

        Kind.ALARM -> {
            val snooze = a.actions.match("r[ée]p[ée]t|snooze|rappel|plus tard|report")
            val stop = a.actions.match("arr[eê]t|ignor|dismiss|stop|fermer|d[ée]sactiv") ?: a.actions.firstOrNull { it !== snooze }
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(c.orange.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                    AlarmClockIcon(c.orange, 28.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(a.title, c.fg, 16, FontWeight.SemiBold)
                    if (a.text.isNotBlank()) T(a.text, c.dim, 13)
                }
                if (snooze != null) { Pill(snooze.label, c.btn, c.fg) { snooze.fire() }; Spacer(Modifier.width(8.dp)) }
                if (stop != null) Pill(stop.label, c.orange, Color.Black) { stop.fire() }
            }
        }

        Kind.EVENT -> if (ex) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(c.btn), contentAlignment = Alignment.Center) {
                    CalendarIcon(c.fg, 26.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(a.title, c.fg, 16, FontWeight.SemiBold)
                    T(a.text, c.dim, 13)
                }
                T(if (a.short == "maintenant") "maintenant" else "dans ${a.short}", c.fg, 14, FontWeight.SemiBold, style = Num)
            }
        } else CompactRow(h0, {
            Box(Modifier.padding(start = 6.dp)) { CalendarIcon(c.fg, 18.dp) }
        }, {
            T(a.short, c.fg, 14, FontWeight.SemiBold, style = Num, modifier = Modifier.padding(end = 7.dp))
        })

        Kind.AUDIO -> CompactRow(h0, {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                HeadphonesIcon(if (a.variant == 1) c.dim else c.fg, 18.dp)
                Spacer(Modifier.width(6.dp))
                T(a.title, if (a.variant == 1) c.dim else c.fg, 13, FontWeight.Medium)
            }
        }, {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                when {
                    a.variant == 1 -> T("Déconnecté", c.dim, 12)
                    a.level >= 0 -> {
                        T("${a.level} %", if (a.level <= 20) c.red else c.green, 13, FontWeight.SemiBold, style = Num)
                        Spacer(Modifier.width(5.dp))
                        BatteryGlyph(a.level, c.fg, if (a.level <= 20) c.red else c.green, 22.dp)
                    }
                    else -> T("Connecté", c.green, 12, FontWeight.Medium)
                }
            }
        })

        Kind.SYSTEM -> CompactRow(h0, {
            Box(Modifier.padding(start = 5.dp)) {
                when (a.variant) {
                    0 -> BellIcon(c.fg, 18.dp, ring = true)
                    1 -> VibrateIcon(c.fg, 20.dp)
                    2 -> BellIcon(c.red, 18.dp, slash = true)
                    3 -> MoonIcon(Color(0xFFBF5AF2), c.bg, 18.dp)
                    4 -> MoonIcon(c.dim, c.bg, 18.dp)
                    6 -> CheckIcon(c.green, 18.dp)
                    else -> BatteryGlyph(a.level, c.fg, c.red, 22.dp)
                }
            }
        }, {
            T(a.title, if (a.variant == 2 || a.variant == 5) c.red else c.fg, 13, FontWeight.SemiBold,
                modifier = Modifier.padding(end = 7.dp))
        })
    }
}

private fun stopRecord(a: Act) {
    (a.actions.match("arr[eê]t|stop|termin|fin") ?: a.actions.firstOrNull())?.fire() ?: a.open()
}

@Composable
private fun CompactRow(h0: Dp, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    val m = LocalMask.current
    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { left() }
        if (m.on) Spacer(Modifier.width(m.size + 10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { right() }
    }
}

@Composable
private fun SmallAppIcon(a: Act, c: IslandColors, size: Dp) {
    val b = a.appIcon ?: a.icon
    if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(c.btn), contentAlignment = Alignment.Center) { BubbleIcon(c.fg, size * .6f) }
}

@Composable
private fun NavIcon(a: Act, c: IslandColors, size: Dp) {
    if (a.icon != null) Image(a.icon.asImageBitmap(), null, colorFilter = ColorFilter.tint(c.fg), modifier = Modifier.size(size))
    else NavArrow(c.fg, size)
}

@Composable
private fun LiveBar(progress: Float, c: IslandColors, color: Color) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400, easing = FastOutSlowInEasing), label = "lb")
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "ind")
    val x by inf.animateFloat(-.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "x")
    BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.track)) {
        if (progress < 0f) {
            Box(Modifier.offset(x = maxWidth * x).width(maxWidth * .3f).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(color))
        } else {
            Box(Modifier.fillMaxWidth(p).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
private fun NavExpanded(a: Act, c: IslandColors) {
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(c.btn), contentAlignment = Alignment.Center) {
            NavIcon(a, c, 32.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(a.title, c.fg, 22, FontWeight.SemiBold)
            T(a.text, c.dim, 14)
            if (a.sub.isNotBlank()) T(a.sub, c.dim, 12)
        }
    }
}

@Composable
private fun LiveExpanded(a: Act, c: IslandColors) {
    if (a.isSport) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(a.teamA, c.fg, 15, FontWeight.SemiBold, align = TextAlign.End, modifier = Modifier.weight(1f))
                T(a.score, c.fg, 28, FontWeight.SemiBold, style = Num, modifier = Modifier.padding(horizontal = 14.dp))
                T(a.teamB, c.fg, 15, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            if (a.sub.isNotBlank()) T(a.sub, c.dim, 12, align = TextAlign.Center)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(a, 40.dp, c)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(a.appName + if (a.sub.isNotBlank()) " · ${a.sub}" else "", c.dim, 12)
                T(a.title, c.fg, 15, FontWeight.SemiBold)
                if (a.text.isNotBlank()) T(a.text, c.fg.copy(alpha = .86f), 13)
            }
            if (a.short.isNotBlank()) {
                Spacer(Modifier.width(8.dp)); T(a.short, c.fg, 17, FontWeight.SemiBold, style = Num)
            }
        }
        if (a.progress != -1f) {
            Spacer(Modifier.weight(1f))
            LiveBar(a.progress, c, c.fg)
        }
    }
}

@Composable
private fun MusicExpanded(a: Act, c: IslandColors, poke: () -> Unit) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(a.playing) { while (a.playing) withFrameMillis { tick = it } }
    tick.let { }
    val dur = a.duration.coerceAtLeast(1)
    val pos = a.livePosition().coerceIn(0, dur)
    var scrub by remember { mutableFloatStateOf(-1f) }
    val frac = if (scrub >= 0f) scrub else pos.toFloat() / dur
    var nextT by remember { mutableIntStateOf(0) }
    var prevT by remember { mutableIntStateOf(0) }
    val accent = musicAccent(a, c)

    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(a.art, label = "artx") { art ->
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)))
                else Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(c.btn))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Crossfade(a.title, label = "ti") { T(it, c.fg, 16, FontWeight.SemiBold, modifier = Modifier.basicMarquee()) }
                T(a.text, c.dim, 13)
            }
            Spacer(Modifier.width(8.dp))
            val lyx by IslandStore.lyrics.collectAsState()
            val open by IslandStore.lyricsOpen.collectAsState()
            if (lyx?.key == a.lyricsKey) {
                val bg by androidx.compose.animation.animateColorAsState(if (open) c.fg else c.btn, tween(220), label = "lb")
                val fgc by androidx.compose.animation.animateColorAsState(if (open) c.bg else c.fg, tween(220), label = "lf")
                Tap(40.dp, if (open) "Masquer les paroles" else "Afficher les paroles", { poke(); IslandStore.lyricsOpen.value = !open }) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        LyricsIcon(fgc, 18.dp)
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            Equalizer(a.playing, accent, 20.dp)
        }
        val ly by IslandStore.lyrics.collectAsState()
        val lyOpen by IslandStore.lyricsOpen.collectAsState()
        val lines = ly?.takeIf { it.key == a.lyricsKey && lyOpen }?.lines
        androidx.compose.animation.AnimatedVisibility(
            visible = lines != null,
            enter = fadeIn(tween(260, 80)) + androidx.compose.animation.expandVertically(tween(320, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween(140)) + androidx.compose.animation.shrinkVertically(tween(260, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
        ) {
            val l = lines ?: ly?.lines ?: emptyList()
            LyricsView(l, (frac * dur).toLong(), c, accent) { t -> poke(); IslandStore.seek(t) }
        }
        Spacer(Modifier.height(if (lines != null) 6.dp else 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(fmt((frac * dur).toLong()), c.dim, 11, style = Num, modifier = Modifier.width(38.dp))
            val barH by animateDpAsState(if (scrub >= 0f) 9.dp else 5.dp, tween(180), label = "bar")
            Box(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .pointerInput(dur) {
                        detectTapGestures { o -> poke(); IslandStore.seek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
                    }
                    .pointerInput(dur) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { IslandStore.seek((scrub * dur).toLong()); scrub = -1f },
                            onDragCancel = { scrub = -1f },
                        ) { ch, d -> ch.consume(); poke(); scrub = (scrub + d / size.width).coerceIn(0f, 1f) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(barH).clip(RoundedCornerShape(5.dp)).background(c.track)) {
                    Box(Modifier.fillMaxWidth(frac).height(barH).clip(RoundedCornerShape(5.dp)).background(c.fg))
                }
            }
            T("-" + fmt(dur - (frac * dur).toLong()), c.dim, 11, style = Num,
                modifier = Modifier.width(44.dp).padding(start = 6.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (a.shuffle != null || a.pkg == IslandStore.DEMO) {
                Tap(44.dp, if (a.shuffle == true) "Désactiver l'aléatoire" else "Lecture aléatoire", { poke(); IslandStore.toggleShuffle() }) {
                    ShuffleIcon(a.shuffle == true, c.dim, accent, 24.dp)
                }
            }
            Tap(50.dp, "Précédent", { prevT++; poke(); IslandStore.prev() }) { SkipIcon(false, prevT, c.fg, 30.dp) }
            Tap(56.dp, if (a.playing) "Pause" else "Lecture", { poke(); IslandStore.playPause() }) { PlayPauseIcon(a.playing, c.fg, 36.dp) }
            Tap(50.dp, "Suivant", { nextT++; poke(); IslandStore.next() }) { SkipIcon(true, nextT, c.fg, 30.dp) }
            if (a.liked != null || a.pkg == IslandStore.DEMO) {
                Tap(44.dp, if (a.liked == true) "Retirer des favoris" else "Ajouter aux favoris", { poke(); IslandStore.toggleLike() }) {
                    HeartIcon(a.liked == true, c.dim, accent, 22.dp)
                }
            }
        }
    }
}

@Composable
private fun TimerExpanded(a: Act, c: IslandColors, poke: () -> Unit) {
    val l = a.actions
    val play = l.match("reprend|resume|continu|d[ée]marr|start|lancer")
    val pause = if (play == null) l.match("pause") ?: (if (a.chrono) l.match("arr[eê]t|stop") else null) else null
    val toggle = play ?: pause
    val running = pause != null
    val second = l.firstOrNull { it !== toggle }
    val secondKind = when {
        second == null -> 0
        Regex("tour|lap", RegexOption.IGNORE_CASE).containsMatchIn(second.label) -> 1
        Regex("r[ée]init|reset", RegexOption.IGNORE_CASE).containsMatchIn(second.label) -> 2
        else -> 3
    }
    Row(Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StopwatchIcon(rememberSeconds(a), c.orange, 14.dp)
                Spacer(Modifier.width(6.dp))
                T(a.title.ifBlank { a.appName }, c.dim, 12)
            }
            T(rememberClock(a, a.chrono && !a.countDown), c.orange, 34, FontWeight.Medium, style = Num)
        }
        if (second != null) {
            Tap(52.dp, second.label, { poke(); second.fire() }) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(c.btn), contentAlignment = Alignment.Center) {
                    when (secondKind) {
                        1 -> LapIcon(c.fg, 20.dp)
                        2 -> ResetIcon(c.fg, 20.dp)
                        else -> Box(Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).background(c.fg))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        if (toggle != null) {
            Tap(52.dp, toggle.label, { poke(); toggle.fire() }) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(c.orange.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                    PlayPauseIcon(running, c.orange, 24.dp)
                }
            }
        }
    }
}

@Composable
private fun RecordExpanded(a: Act, c: IslandColors) {
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.red.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            RecDot(c.red, 26.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(if (a.variant == 1) "Enregistrement vocal" else "Enregistrement d'écran", c.fg, 15, FontWeight.SemiBold)
            T(rememberClock(a, false), c.dim, 13, style = Num)
        }
        Pill("Arrêter", c.red, Color.White) { stopRecord(a) }
    }
}

@Composable
private fun CallExpanded(a: Act, c: IslandColors) {
    val decline = a.actions.match("refus|d[ée]clin|reject|raccroch|hang|end|termin|ignor")
    val accept = a.actions.match("r[ée]pond|answer|accept|d[ée]croch")
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AppAvatar(a, 52.dp, c)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(if (a.incoming) "Appel entrant" else rememberClock(a, false), if (a.incoming) c.dim else c.green, 12, style = Num)
            T(a.title, c.fg, 16, FontWeight.SemiBold)
        }
        Tap(52.dp, if (a.incoming) "Refuser" else "Raccrocher", { (decline ?: a.actions.lastOrNull())?.fire() }) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFFFF453A)), contentAlignment = Alignment.Center) {
                HandsetIcon(Color.White, 24.dp, hangUp = true)
            }
        }
        if (a.incoming) {
            Spacer(Modifier.width(10.dp))
            Tap(52.dp, "Décrocher", { (accept ?: a.actions.firstOrNull())?.fire() ?: a.open() }) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF30D158)), contentAlignment = Alignment.Center) {
                    HandsetIcon(Color.White, 24.dp, ring = true)
                }
            }
        }
    }
}

@Composable
private fun NotifBody(a: Act, c: IslandColors, poke: () -> Unit, counter: String?, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    val replyKey by IslandStore.replying.collectAsState()
    val lyr by IslandStore.lyrics.collectAsState()
    val lyrOpen by IslandStore.lyricsOpen.collectAsState()
    lyr?.key; lyrOpen
    val replying = replyKey == a.key && a.key.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            AppAvatar(a, 42.dp, c)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${a.appName} · ${a.ago()}", c.dim, 12, modifier = Modifier.weight(1f))
                    if (counter != null) T(counter, c.dim, 12, style = Num)
                    if (onDismiss != null) {
                        Tap(26.dp, "Supprimer la notification", { poke(); onDismiss() }) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(c.btn), contentAlignment = Alignment.Center) {
                                CrossIcon(c.dim, 9.dp)
                            }
                        }
                    }
                }
                T(a.title, c.fg, 15, FontWeight.SemiBold)
                T(a.text, c.fg.copy(alpha = .86f), 13, maxLines = 2)
            }
        }
        if (a.actions.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.actions.forEach { b ->
                    val on = replying && b.isReply
                    Pill(b.label, if (on) c.fg else c.btn, if (on) c.bg else c.fg, Modifier.weight(1f)) {
                        poke()
                        if (b.isReply) {
                            IslandStore.replying.value = if (replying) null else a.key
                            IslandStore.extend(a.id, 60_000)
                        } else { b.fire(); onAction() }
                    }
                }
            }
        }
        if (replying) {
            Spacer(Modifier.height(10.dp))
            ReplyRow(a, c, poke)
        }
    }
}

@Composable
private fun ReplyRow(a: Act, c: IslandColors, poke: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(180); runCatching { fr.requestFocus() }; kb?.show() }
    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        a.actions.firstOrNull { it.isReply }?.fire(t)
        kb?.hide()
        IslandStore.replying.value = null
        IslandStore.acts.value.find { it.kind == Kind.NOTIF && it.key == a.key }?.let { IslandStore.remove(it.id) }
        IslandStore.upsert(Act(id = "sys", kind = Kind.SYSTEM, title = "Message envoyé", variant = 6, stamp = System.currentTimeMillis()), ttl = 1800)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it; poke() },
            singleLine = true,
            textStyle = TextStyle(color = c.fg, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.fg),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).height(42.dp).focusRequester(fr),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(21.dp)).background(c.btn).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) T("Message…", c.dim, 15)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        val ready = text.isNotBlank()
        val bg by androidx.compose.animation.animateColorAsState(if (ready) c.fg else c.btn, tween(200), label = "send")
        Tap(42.dp, "Envoyer", { send() }) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                SendArrow(if (ready) c.bg else c.dim, 18.dp)
            }
        }
    }
}

/** One notification at a time; drag down = next, drag up = previous, swipe sideways = delete. */
@Composable
private fun InboxCard(c: IslandColors, poke: () -> Unit) {
    val list by IslandStore.notifs.collectAsState()
    val idx by IslandStore.inboxIndex.collectAsState()
    if (list.isEmpty()) return
    val i = idx.coerceIn(0, list.lastIndex)
    val item = list[i]
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(1) }
    val cache = remember { mutableMapOf<String, Act>() }
    list.forEach { cache[it.key] = it }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var tx = 0f; var ty = 0f
                detectDragGestures(
                    onDragStart = { tx = 0f; ty = 0f },
                    onDragEnd = {
                        val l = IslandStore.notifs.value
                        val cur = IslandStore.inboxIndex.value.coerceIn(0, (l.size - 1).coerceAtLeast(0))
                        val t = 44.dp.toPx()
                        scope.launch {
                            when {
                                abs(tx) > abs(ty) && abs(tx) > 90.dp.toPx() -> {
                                    dx.animateTo(if (tx > 0) size.width * 1.2f else -size.width * 1.2f, tween(180))
                                    l.getOrNull(cur)?.let { IslandStore.dismissNotif(it.key) }
                                    dx.snapTo(0f); dy.snapTo(0f)
                                }
                                abs(ty) > abs(tx) && ty > t && cur < l.lastIndex -> {
                                    dir = 1; IslandStore.inboxIndex.value = cur + 1; IslandStore.replying.value = null
                                    dy.snapTo(0f); dx.snapTo(0f)
                                }
                                abs(ty) > abs(tx) && ty < -t && cur > 0 -> {
                                    dir = -1; IslandStore.inboxIndex.value = cur - 1; IslandStore.replying.value = null
                                    dy.snapTo(0f); dx.snapTo(0f)
                                }
                                abs(ty) > abs(tx) && ty < -t && cur == 0 -> {
                                    IslandStore.expanded.value = false
                                    dy.snapTo(0f); dx.snapTo(0f)
                                }
                                else -> {
                                    launch { dx.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
                                    dy.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                                }
                            }
                        }
                    },
                ) { ch, d ->
                    ch.consume(); poke(); tx += d.x; ty += d.y
                }
            },
    ) {
        AnimatedContent(
            targetState = item.key,
            transitionSpec = {
                val d = dir
                (fadeIn(tween(220, 60)) + androidx.compose.animation.slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -d * it / 3 })
                    .togetherWith(fadeOut(tween(120)) + androidx.compose.animation.slideOutVertically(tween(260)) { d * it / 3 })
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            modifier = Modifier.fillMaxSize(),
            label = "inbox",
        ) { k ->
            val a = list.find { it.key == k } ?: cache[k]
            if (a != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = dx.value
                            translationY = 0f
                            alpha = 1f - (abs(dx.value) / (size.width * 1.2f)).coerceIn(0f, .9f)
                        }
                ) {
                    NotifBody(
                        a, c, poke,
                        counter = if (list.size > 1) "${list.indexOfFirst { it.key == k } + 1}/${list.size}" else null,
                        onAction = {},
                        onDismiss = { IslandStore.dismissNotif(a.key) },
                    )
                }
            }
        }
        if (list.size > 1) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val n = min(list.size, 7)
                val pos = if (list.size <= 7) i else (i * (n - 1) / (list.size - 1))
                repeat(n) { j ->
                    val hh by animateDpAsState(if (j == pos) 12.dp else 4.dp, tween(220), label = "dot")
                    Box(Modifier.width(3.dp).height(hh).clip(RoundedCornerShape(2.dp)).background(if (j == pos) c.fg else c.track))
                }
            }
        }
    }
}


/** Karaoke-style lyrics: smooth scroll, current line bright and slightly larger, faded edges, tap a line to seek. */
@Composable
private fun LyricsView(lines: List<Pair<Long, String>>, pos: Long, c: IslandColors, accent: Color, onSeek: (Long) -> Unit) {
    val idx = lines.indexOfLast { it.first <= pos + 250 }.coerceAtLeast(0)
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(idx, lines) {
        state.animateScrollToItem((idx - 1).coerceAtLeast(0))
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = state,
        userScrollEnabled = false,
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .height(140.dp)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent, .14f to Color.Black, .78f to Color.Black, 1f to Color.Transparent
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                )
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(lines.size) { i ->
            val cur = i == idx
            val past = i < idx
            val col by androidx.compose.animation.animateColorAsState(
                when { cur -> c.fg; past -> c.dim.copy(alpha = .5f); else -> c.dim.copy(alpha = .8f) },
                tween(320), label = "lc"
            )
            val sc by animateFloatAsState(if (cur) 1f else .92f, tween(320, easing = FastOutSlowInEasing), label = "ls")
            Text(
                lines[i].second, color = col, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, .5f) }
                    .pointerInput(i) { detectTapGestures { onSeek(lines[i].first) } },
            )
        }
    }
}
