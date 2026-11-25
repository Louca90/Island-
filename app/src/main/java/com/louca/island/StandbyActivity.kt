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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
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
import com.louca.island.ui.RepeatIcon
import androidx.compose.animation.core.animateFloat
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

    val fadeIn = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { fadeIn.animateTo(1f, tween(450)) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { close() }) }
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fadeIn.value }.background(bg))
        MovingBackdrop(accent, music?.playing == true, Modifier.graphicsLayer { alpha = fadeIn.value })
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout)
                .offset(driftX, driftY)
                .padding(start = 56.dp, end = 36.dp, top = 22.dp, bottom = 22.dp)
        ) {
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

    // Opening animation: cover grows in, left column slides from the side, right panel fades in last.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
    val e = enter.value
    var tab by remember { mutableIntStateOf(0) } // 0 = paroles, 1 = file d'attente

    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        // ---------------- left: cover + info + controls
        Column(
            Modifier.fillMaxHeight().weight(.46f).graphicsLayer { translationX = (1f - e) * -60.dp.toPx(); alpha = e },
            verticalArrangement = Arrangement.Center,
        ) {
            val coverScale by animateFloatAsState(if (a.playing) 1f else .9f, tween(450, easing = FastOutSlowInEasing), label = "cs")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crossfade(a.art, animationSpec = tween(500), label = "art") { art ->
                    Box(
                        Modifier.size(150.dp).graphicsLayer {
                            val k = coverScale * (.6f + .4f * e); scaleX = k; scaleY = k
                        }
                            .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .08f))
                    ) {
                        if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.weight(1f).basicMarquee())
                        if (a.liked != null || a.pkg == IslandStore.DEMO) {
                            val accent = a.accent.takeIf { it != 0 }?.let { lerp(Color(it), Color.White, .35f) } ?: Color.White
                            Tap(44.dp, if (a.liked == true) "Retirer des favoris" else "Ajouter aux favoris", { IslandStore.toggleLike() }) {
                                HeartIcon(a.liked == true, Color.White.copy(alpha = .55f), accent, 24.dp)
                            }
                        }
                    }
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
        // ---------------- right: lyrics or queue
        Column(Modifier.fillMaxHeight().weight(.54f).graphicsLayer { alpha = ((e - .35f) / .65f).coerceIn(0f, 1f) }) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabPill("Paroles", tab == 0) { tab = 0 }
                TabPill("File d'attente", tab == 1) { tab = 1 }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Crossfade(tab, animationSpec = tween(300), label = "tab") { t ->
                    if (t == 1) QueuePanel(a)
                    else if (lines != null) BigLyrics(lines, pos)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (Prefs.state.value.showLyrics) "Pas de paroles synchronisées pour ce titre" else "Paroles désactivées",
                            color = Color.White.copy(alpha = .35f), fontSize = 18.sp,
                        )
                    }
                }
            }
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
    var repeat by remember { mutableIntStateOf(IslandStore.repeatMode()) }
    LaunchedEffect(Unit) { while (true) { repeat = IslandStore.repeatMode(); delay(800) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Tap(52.dp, if (a.shuffle == true) "Désactiver l'aléatoire" else "Lecture aléatoire", { IslandStore.toggleShuffle() }) {
            ShuffleIcon(a.shuffle == true, Color.White.copy(alpha = .55f), accent, 28.dp)
        }
        Tap(64.dp, "Précédent", { prevT++; IslandStore.prev() }) { SkipIcon(false, prevT, Color.White, 38.dp) }
        Tap(76.dp, if (a.playing) "Pause" else "Lecture", { IslandStore.playPause() }) { PlayPauseIcon(a.playing, Color.White, 46.dp) }
        Tap(64.dp, "Suivant", { nextT++; IslandStore.next() }) { SkipIcon(true, nextT, Color.White, 38.dp) }
        Tap(52.dp, when (repeat) { 1 -> "Répéter tout"; 2 -> "Répéter le titre"; else -> "Répétition désactivée" },
            { IslandStore.cycleRepeat(); repeat = (repeat + 1) % 3 }) {
            RepeatIcon(repeat, Color.White.copy(alpha = if (repeat < 0) .2f else .55f), accent, 26.dp)
        }
    }
}

@Composable
private fun TabPill(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = .1f), tween(220), label = "tp")
    val fg by animateColorAsState(if (on) Color.Black else Color.White.copy(alpha = .7f), tween(220), label = "tf")
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Up-next list shared by the player's media session; tap an item to jump to it. */
@Composable
private fun QueuePanel(a: Act) {
    var items by remember { mutableStateOf(IslandStore.queue()) }
    var active by remember { mutableLongStateOf(IslandStore.activeQueueId()) }
    LaunchedEffect(a.title) {
        while (true) { items = IslandStore.queue(); active = IslandStore.activeQueueId(); delay(1000) }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text("${a.appName.ifBlank { "Cette app" }} ne partage pas sa file d'attente avec Android.",
                color = Color.White.copy(alpha = .35f), fontSize = 18.sp)
        }
        return
    }
    val state = rememberLazyListState()
    val activeIdx = items.indexOfFirst { it.id == active }
    LaunchedEffect(activeIdx) { if (activeIdx >= 0) state.animateScrollToItem(activeIdx) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items.size) { i ->
            val q = items[i]
            val cur = q.id == active
            val bg by animateColorAsState(if (cur) Color.White.copy(alpha = .12f) else Color.Transparent, tween(250), label = "qb")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
                    .pointerInput(q.id) { detectTapGestures { IslandStore.playQueueItem(q.id); active = q.id } }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .08f))) {
                    if (q.icon != null) Image(q.icon.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(q.title, color = if (cur) Color.White else Color.White.copy(alpha = .8f), fontSize = 16.sp,
                        fontWeight = if (cur) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(q.subtitle, color = Color.White.copy(alpha = .45f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (cur) com.louca.island.ui.Equalizer(a.playing, Color.White, 18.dp)
            }
        }
    }
}

/**
 * Soft blobs in the cover's colour that drift slowly while music plays and settle when paused.
 * Kept faint so it never fights with the text.
 */
@Composable
private fun MovingBackdrop(accent: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "bd")
    val t by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(18_000, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    val pulse by inf.animateFloat(.9f, 1.08f, androidx.compose.animation.core.infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
    val amt by animateFloatAsState(if (playing) 1f else .35f, tween(1200), label = "amt")
    val col by animateColorAsState(accent, tween(1200), label = "col")
    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val a = t * 2f * Math.PI.toFloat()
        fun blob(cx: Float, cy: Float, r: Float, alpha: Float) {
            drawCircle(
                Brush.radialGradient(listOf(col.copy(alpha = alpha), Color.Transparent), androidx.compose.ui.geometry.Offset(cx, cy), r),
                r, androidx.compose.ui.geometry.Offset(cx, cy)
            )
        }
        val k = 1f + (pulse - 1f) * amt
        blob(w * (.22f + .08f * kotlin.math.cos(a) * amt), h * (.35f + .1f * kotlin.math.sin(a) * amt), h * .9f * k, .32f)
        blob(w * (.78f + .07f * kotlin.math.sin(a * 1.3f) * amt), h * (.7f + .08f * kotlin.math.cos(a * .8f) * amt), h * .8f * (2f - k), .22f)
        blob(w * (.55f + .1f * kotlin.math.cos(a * .7f + 1f) * amt), h * (.15f + .06f * kotlin.math.sin(a * 1.1f) * amt), h * .6f, .16f)
    }
}

@Composable
private fun VolumeBar() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    fun remote() = IslandStore.media?.playbackInfo?.takeIf {
        it.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE &&
            it.volumeControl != android.media.VolumeProvider.VOLUME_CONTROL_FIXED
    }
    fun readMax() = remote()?.maxVolume?.coerceAtLeast(1) ?: am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    fun readVol() = remote()?.currentVolume ?: am.getStreamVolume(AudioManager.STREAM_MUSIC)

    var isRemote by remember { mutableStateOf(remote() != null) }
    var max by remember { mutableIntStateOf(readMax()) }
    var vol by remember { mutableIntStateOf(readVol()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) { isRemote = remote() != null; max = readMax(); vol = readVol() }
            delay(400)
        }
    }
    val f by animateFloatAsState(vol.toFloat() / max, tween(150), label = "vol")
    fun set(x: Float, w: Float) {
        val v = ((x / w).coerceIn(0f, 1f) * max).toInt()
        if (v == vol) return
        vol = v
        val c = IslandStore.media
        if (remote() != null && c != null) c.setVolumeTo(v, 0)
        else am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakerIcon(Color.White.copy(alpha = .55f), 18.dp, waves = 0)
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(
                Modifier.weight(1f).height(26.dp)
                    .pointerInput(Unit) { detectTapGestures { o -> set(o.x, size.width.toFloat()) } }
                    .pointerInput(Unit) {
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
        if (isRemote) {
            Text("Volume de l'appareil connecté", color = Color.White.copy(alpha = .4f), fontSize = 12.sp,
                modifier = Modifier.padding(start = 30.dp))
        }
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
