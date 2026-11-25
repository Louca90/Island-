package com.louca.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.louca.island.ui.BoltIcon
import com.louca.island.ui.HeartIcon
import com.louca.island.ui.PlayPauseIcon
import com.louca.island.ui.ShuffleIcon
import com.louca.island.ui.SkipIcon
import com.louca.island.ui.Tap
import com.louca.island.ui.fmt
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * StandBy-style charging screen: landscape, always on, even over the lock screen.
 * Cover + controls on the left, synced lyrics (or a big clock) on the right.
 */
class StandbyActivity : ComponentActivity() {

    private val unplug = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        registerReceiver(unplug, IntentFilter(Intent.ACTION_POWER_DISCONNECTED), Context.RECEIVER_EXPORTED)
        IslandStore.standby.value = true
        setContent { Standby { finish() } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unplug) }
        IslandStore.standby.value = false
        super.onDestroy()
    }
}

private val Num = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun Standby(close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val acts by IslandStore.acts.collectAsState()
    val music = acts.find { it.id == "music" }

    // Clock + battery, refreshed every second
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        while (true) {
            now = System.currentTimeMillis()
            battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            delay(1000)
        }
    }
    // OLED care: drift the whole layout a few dp every minute
    val minute = now / 60_000
    val driftX = ((minute * 7) % 9 - 4).toInt().dp
    val driftY = ((minute * 5) % 7 - 3).toInt().dp

    // Background takes the cover's colour, very dark; animated between songs
    val accent = music?.accent?.takeIf { it != 0 }?.let { Color(it) } ?: Color(0xFF2A2D36)
    val bg by animateColorAsState(lerp(Color(0xFF07080A), accent, .22f), tween(900), label = "bg")

    Box(
        Modifier.fillMaxSize().background(bg)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { close() }) }
    ) {
        Box(Modifier.fillMaxSize().offset(driftX, driftY).padding(horizontal = 36.dp, vertical = 22.dp)) {
            // top-right status: time + charging
            Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                if (music != null) {
                    Text(SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now)), color = Color.White.copy(alpha = .7f),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = Num)
                    Spacer(Modifier.width(14.dp))
                }
                BoltIcon(Color(0xFF30D158), 16.dp)
                Spacer(Modifier.width(4.dp))
                Text("$battery %", color = Color.White.copy(alpha = .7f), fontSize = 15.sp, fontWeight = FontWeight.Medium, style = Num)
            }

            Crossfade(music != null, animationSpec = tween(500), label = "mode") { hasMusic ->
                if (hasMusic && music != null) Player(music) else BigClock(now)
            }
        }
    }
}

@Composable
private fun BigClock(now: Long) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now)), color = Color.White, fontSize = 150.sp,
            fontWeight = FontWeight.Light, style = Num, lineHeight = 150.sp)
        Text(SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(now)).replaceFirstChar { it.uppercase() },
            color = Color.White.copy(alpha = .6f), fontSize = 22.sp)
    }
}

@Composable
private fun Player(a: Act) {
    val ly by IslandStore.lyrics.collectAsState()
    val lines = ly?.takeIf { it.key == a.lyricsKey }?.lines

    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(a.playing) { while (a.playing) withFrameMillis { tick = it } }
    tick.let { }
    val dur = a.duration.coerceAtLeast(1)
    val pos = a.livePosition().coerceIn(0, dur)

    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        // ---------------- left: cover + info + controls
        Column(Modifier.fillMaxHeight().weight(.46f), verticalArrangement = Arrangement.Center) {
            val coverScale by animateFloatAsState(if (a.playing) 1f else .9f, tween(450, easing = FastOutSlowInEasing), label = "cs")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crossfade(a.art, animationSpec = tween(500), label = "art") { art ->
                    Box(
                        Modifier.size(150.dp).graphicsLayer { scaleX = coverScale; scaleY = coverScale }
                            .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .08f))
                    ) {
                        if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.basicMarquee())
                    Text(a.text, color = Color.White.copy(alpha = .62f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(a.appName, color = Color.White.copy(alpha = .38f), fontSize = 13.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(22.dp))
            Scrubber(pos, dur)
            Spacer(Modifier.height(10.dp))
            Controls(a)
            Spacer(Modifier.height(14.dp))
            VolumeBar()
        }
        Spacer(Modifier.width(40.dp))
        // ---------------- right: lyrics, or a calm placeholder
        Box(Modifier.fillMaxHeight().weight(.54f), contentAlignment = Alignment.CenterStart) {
            if (lines != null) BigLyrics(lines, pos)
            else Text(
                if (Prefs.state.value.showLyrics) "Pas de paroles synchronisées pour ce titre" else "Paroles désactivées",
                color = Color.White.copy(alpha = .35f), fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun Scrubber(pos: Long, dur: Long) {
    var scrub by remember { mutableFloatStateOf(-1f) }
    val frac = if (scrub >= 0f) scrub else pos.toFloat() / dur
    val h by animateFloatAsState(if (scrub >= 0f) 10f else 6f, tween(160), label = "sh")
    Column {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(dur) { detectTapGestures { o -> IslandStore.seek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) } }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { IslandStore.seek((scrub * dur).toLong()); scrub = -1f },
                        onDragCancel = { scrub = -1f },
                    ) { ch, d -> ch.consume(); scrub = (scrub + d / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .18f))) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((frac * dur).toLong()), color = Color.White.copy(alpha = .5f), fontSize = 12.sp, style = Num)
            Text("-" + fmt(dur - (frac * dur).toLong()), color = Color.White.copy(alpha = .5f), fontSize = 12.sp, style = Num)
        }
    }
}

@Composable
private fun Controls(a: Act) {
    var nextT by remember { mutableIntStateOf(0) }
    var prevT by remember { mutableIntStateOf(0) }
    val accent = a.accent.takeIf { it != 0 }?.let { lerp(Color(it), Color.White, .35f) } ?: Color.White
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Tap(52.dp, if (a.shuffle == true) "Désactiver l'aléatoire" else "Lecture aléatoire", { IslandStore.toggleShuffle() }) {
            ShuffleIcon(a.shuffle == true, Color.White.copy(alpha = .55f), accent, 28.dp)
        }
        Tap(64.dp, "Précédent", { prevT++; IslandStore.prev() }) { SkipIcon(false, prevT, Color.White, 38.dp) }
        Tap(76.dp, if (a.playing) "Pause" else "Lecture", { IslandStore.playPause() }) { PlayPauseIcon(a.playing, Color.White, 46.dp) }
        Tap(64.dp, "Suivant", { nextT++; IslandStore.next() }) { SkipIcon(true, nextT, Color.White, 38.dp) }
        Tap(52.dp, if (a.liked == true) "Retirer des favoris" else "Ajouter aux favoris", { IslandStore.toggleLike() }) {
            HeartIcon(a.liked == true, Color.White.copy(alpha = .55f), accent, 26.dp)
        }
    }
}

@Composable
private fun VolumeBar() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { if (!dragging) vol = am.getStreamVolume(AudioManager.STREAM_MUSIC); delay(400) } }
    val f by animateFloatAsState(vol.toFloat() / max, tween(150), label = "vol")
    fun set(x: Float, w: Float) {
        val v = ((x / w).coerceIn(0f, 1f) * max).toInt()
        if (v != vol) { vol = v; am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpeakerIcon(Color.White.copy(alpha = .55f), 18.dp, waves = 0)
        Spacer(Modifier.width(12.dp))
        BoxWithConstraints(
            Modifier.weight(1f).height(26.dp)
                .pointerInput(max) { detectTapGestures { o -> set(o.x, size.width.toFloat()) } }
                .pointerInput(max) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    ) { ch, _ -> ch.consume(); set(ch.position.x, size.width.toFloat()) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .18f))) {
                Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .85f)))
            }
        }
        Spacer(Modifier.width(12.dp))
        SpeakerIcon(Color.White.copy(alpha = .55f), 18.dp, waves = 2)
    }
}

/** Big karaoke lyrics: auto-scroll, current line bright, edges faded, tap a line to jump there. */
@Composable
private fun BigLyrics(lines: List<Pair<Long, String>>, pos: Long) {
    val idx = max(0, lines.indexOfLast { it.first <= pos + 250 })
    val state = rememberLazyListState()
    LaunchedEffect(idx, lines) { state.animateScrollToItem(max(0, idx - 1)) }
    LazyColumn(
        state = state,
        userScrollEnabled = false,
        contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, .12f to Color.Black, .82f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn
                )
            },
    ) {
        items(lines.size) { i ->
            val cur = i == idx
            val col by animateColorAsState(
                when { cur -> Color.White; i < idx -> Color.White.copy(alpha = .22f); else -> Color.White.copy(alpha = .4f) },
                tween(380), label = "c"
            )
            val sc by animateFloatAsState(if (cur) 1f else .9f, tween(380, easing = FastOutSlowInEasing), label = "s")
            Text(
                lines[i].second, color = col, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, .5f) }
                    .pointerInput(i) { detectTapGestures { IslandStore.seek(lines[i].first) } },
            )
        }
    }
}

@Composable
private fun SpeakerIcon(color: Color, size: Dp, waves: Int) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * .1f, w * .38f); lineTo(w * .3f, w * .38f); lineTo(w * .52f, w * .18f)
            lineTo(w * .52f, w * .82f); lineTo(w * .3f, w * .62f); lineTo(w * .1f, w * .62f); close()
        }
        drawPath(p, color)
        val st = androidx.compose.ui.graphics.drawscope.Stroke(w * .08f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (waves >= 1) drawArc(color, -45f, 90f, false, androidx.compose.ui.geometry.Offset(w * .44f, w * .3f),
            androidx.compose.ui.geometry.Size(w * .4f, w * .4f), style = st)
        if (waves >= 2) drawArc(color, -45f, 90f, false, androidx.compose.ui.geometry.Offset(w * .36f, w * .14f),
            androidx.compose.ui.geometry.Size(w * .72f, w * .72f), style = st)
    }
}
