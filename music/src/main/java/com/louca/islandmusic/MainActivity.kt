package com.louca.islandmusic

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.louca.islandmusic.ui.Equalizer
import com.louca.islandmusic.ui.HeartIcon
import com.louca.islandmusic.ui.PlayPauseIcon
import com.louca.islandmusic.ui.RepeatIcon
import com.louca.islandmusic.ui.ShuffleIcon
import com.louca.islandmusic.ui.SkipIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private const val SPOTIFY = "com.spotify.music"
private val Num = TextStyle(fontFeatureSettings = "tnum")
private val Green = Color(0xFF1ED760)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Spotify.init(this)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        onIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Color.Black, surface = Color(0xFF121212))) {
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); onIntent(intent) }

    private var unplug: android.content.BroadcastReceiver? = null

    /** Opened by Island on the wireless charger: wake the screen, stay on, close when unplugged. */
    private fun onIntent(i: Intent?) {
        if (i?.getBooleanExtra("standby", false) != true) return
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (unplug == null) {
            unplug = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, it: Intent) { finish() }
            }
            registerReceiver(unplug, android.content.IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        }
    }

    override fun onDestroy() { unplug?.let { runCatching { unregisterReceiver(it) } }; super.onDestroy() }

    /** No status bar, no navigation bar: swipe from an edge to see them for a moment. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Volume keys move the volume of the device that's playing (PC, speaker…) — not the phone's.
     * When the phone itself plays, Android's own volume handling is kept.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if ((up || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && remoteVolume(this) != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val v = Spotify.playback.value?.device?.volume ?: 50
                Spotify.setVolume(v + if (up) 5 else -5)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** The playing device's volume can be set through Spotify (it's not this phone, and Spotify allows it). */
private fun remoteVolume(ctx: Context): Device? {
    val d = Spotify.playback.value?.device ?: return null
    if (isThisPhone(ctx, d)) return null
    return d.takeIf { it.supportsVolume && it.volume != null }
}

/** Spotify gives no id for "this phone": a smartphone that's active while this phone is playing music is us. */
private fun isThisPhone(ctx: Context, d: Device): Boolean =
    d.type.equals("Smartphone", true) && ctx.getSystemService(AudioManager::class.java).isMusicActive

// ------------------------------------------------------------------ app

@Composable
private fun App() {
    val signed by Spotify.signedIn.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(signed, animationSpec = tween(400), label = "root") { s ->
            if (s) PlayerScreen() else Setup()
        }
    }
}

// ------------------------------------------------------------------ setup

@Composable
private fun Setup() {
    val ctx = LocalContext.current
    val id by Spotify.clientId.collectAsState()
    val err by Spotify.authError.collectAsState()
    var field by remember { mutableStateOf(id) }
    val clip = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("Island Music", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Contrôle Spotify sur n'importe quel appareil : volume du PC, paroles, file d'attente.",
            color = Color.White.copy(alpha = .6f), fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        StepCard("1", "Crée ton app Spotify", "Une seule fois. Spotify impose un compte Premium pour ça.") {
            Bullet("Ouvre le tableau de bord, connecte-toi, puis « Create app ».")
            Bullet("Nom et description : ce que tu veux.")
            Bullet("Redirect URI : colle exactement l'adresse ci-dessous, puis « Add ».")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .08f))
                    .pointerInput(Unit) { detectTapGestures { clip.setText(AnnotatedString(Spotify.REDIRECT)) } }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Spotify.REDIRECT, color = Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Copier", color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
            }
            Bullet("Coche « Web API », accepte les conditions, « Save ».")
            Pill("Ouvrir le tableau de bord", Color.White, Color.Black) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard")))
            }
        }
        StepCard("2", "Colle le Client ID", "Dans ton app Spotify › Settings › Client ID.") {
            OutlinedTextField(
                value = field, onValueChange = { field = it.trim(); Spotify.setClientId(field) },
                singleLine = true, placeholder = { Text("ex. 3f9a…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StepCard("3", "Connecte-toi", "Spotify s'ouvre dans le navigateur, accepte, puis reviens ici.") {
            Pill("Se connecter à Spotify", Green, Color.Black, enabled = field.length >= 20) { Spotify.signIn(ctx) }
            err?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StepCard(n: String, title: String, sub: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = .06f)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Green), contentAlignment = Alignment.Center) {
                Text(n, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Color.White.copy(alpha = .55f), fontSize = 13.sp)
            }
        }
        content()
    }
}

@Composable
private fun Bullet(t: String) = Row {
    Text("•", color = Green, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
    Text(t, color = Color.White.copy(alpha = .85f), fontSize = 15.sp)
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (down) .95f else 1f, spring(1f, 900f), label = "p")
    Box(
        modifier.heightIn(min = 46.dp).graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (enabled) 1f else .4f }
            .clip(RoundedCornerShape(23.dp)).background(bg)
            .pointerInput(enabled) { if (enabled) detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { onClick() }) }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Tap(size: Dp, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var down by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (down) .88f else 1f, spring(1f, 900f), label = "tap")
    val click by rememberUpdatedState(onClick)
    Box(
        modifier.size(size).graphicsLayer { scaleX = sc; scaleY = sc }
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { click() }) },
        contentAlignment = Alignment.Center, content = content,
    )
}

// ------------------------------------------------------------------ player

@Composable
private fun PlayerScreen() {
    val ctx = LocalContext.current
    val pb by Spotify.playback.collectAsState()
    val problem by Spotify.problem.collectAsState()
    var devicesOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Poll while the app is on screen. The Web API works whatever Android does to the Spotify app on this phone.
    LifecycleResumeEffect(Unit) {
        val job = Spotify.scope.launch {
            var n = 0
            while (true) {
                Spotify.refresh()
                if (n % 15 == 0) Spotify.refreshQueue()
                if (Spotify.playback.value == null && n % 5 == 0) Spotify.refreshDevices()
                n++
                delay(1000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    val track = pb?.track
    val cover by produceState(Covers.cached(track?.image), track?.image) { value = Covers.load(track?.image) }
    val accent = remember(cover) { Color(Covers.accent(cover)) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // keep the screen on while it's shown in landscape (desk / charging stand)
    val act = ctx as? ComponentActivity
    LaunchedEffect(landscape) {
        if (landscape) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    Box(Modifier.fillMaxSize()) {
        MovingBackdrop(accent, pb?.playing == true)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = if (landscape) 28.dp else 20.dp)) {
            val openDevices = { devicesOpen = true; Spotify.scope.launch { Spotify.refreshDevices() }; Unit }
            val extras: @Composable () -> Unit = { DeviceButton(pb?.device, openDevices); MenuButton { menu = true } }
            if (pb == null || track == null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { extras() }
                NothingPlaying(problem, openDevices)
            } else if (landscape) Landscape(pb!!, track, cover, accent, extras) else Portrait(pb!!, track, cover, accent, extras)
        }
        AnimatedVisibility(devicesOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            DeviceSheet { devicesOpen = false }
        }
        AnimatedVisibility(menu, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Sheet({ menu = false }) {
                Text("Island Music", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Android ferme Spotify ? Mets-le en batterie « Non restreinte » : il ne sera plus tué en arrière-plan.",
                    color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
                Pill("Batterie de Spotify", Color.White.copy(alpha = .12f), Color.White, modifier = Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$SPOTIFY")))
                }
                Pill("Se déconnecter", Color(0xFFFF453A).copy(alpha = .2f), Color(0xFFFF6B6B), modifier = Modifier.fillMaxWidth()) {
                    menu = false; Spotify.signOut()
                }
            }
        }
    }
}

/** The device that plays, as a round button on the tab row (doesn't push the player down). */
@Composable
private fun DeviceButton(device: Device?, onClick: () -> Unit) {
    Tap(40.dp, "Appareil : ${device?.name ?: "aucun"}", onClick) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(if (device != null) Green.copy(alpha = .18f) else Color.White.copy(alpha = .1f)),
            contentAlignment = Alignment.Center) {
            DeviceIcon(device?.type ?: "", if (device != null) Green else Color.White.copy(alpha = .7f), 18.dp)
        }
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    Tap(40.dp, "Menu", onClick) {
        Canvas(Modifier.size(20.dp)) {
            listOf(.2f, .5f, .8f).forEach { drawCircle(Color.White.copy(alpha = .8f), size.width * .09f, Offset(size.width * it, size.height / 2)) }
        }
    }
}

@Composable
private fun NothingPlaying(problem: Problem, openDevices: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reviving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (problem) {
                Problem.PREMIUM -> "Spotify Premium requis"
                Problem.NETWORK -> "Pas de connexion"
                else -> "Rien ne joue"
            },
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when (problem) {
                Problem.PREMIUM -> "Spotify ne laisse contrôler la lecture qu'avec un compte Premium."
                Problem.NETWORK -> "Island Music réessaie toute seule."
                else -> "Spotify est fermé partout, ou Android l'a coupé sur ce téléphone."
            },
            color = Color.White.copy(alpha = .6f), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(22.dp))
        if (problem != Problem.PREMIUM) {
            Pill(if (reviving) "Relance en cours…" else "Relancer Spotify sur ce téléphone", Green, Color.Black, enabled = !reviving) {
                reviving = true
                scope.launch { revive(ctx); reviving = false }
            }
            Spacer(Modifier.height(10.dp))
            Pill("Choisir un appareil", Color.White.copy(alpha = .12f), Color.White, onClick = openDevices)
        }
    }
}

/**
 * Brings Spotify back after Android killed it, without leaving this app: a "play" media button sent
 * straight to Spotify restarts its player service and resumes. If it still doesn't show up, open it.
 */
private suspend fun revive(ctx: Context) {
    fun key(action: Int) = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(SPOTIFY)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
    runCatching { ctx.sendBroadcast(key(KeyEvent.ACTION_DOWN)); ctx.sendBroadcast(key(KeyEvent.ACTION_UP)) }
    repeat(8) {
        delay(800)
        Spotify.refresh()
        if (Spotify.playback.value != null) return
    }
    Spotify.refreshDevices()
    val phone = Spotify.devices.value.firstOrNull { it.type.equals("Smartphone", true) }
    if (phone != null) { Spotify.transfer(phone); return }
    ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---- layouts

@Composable
private fun Portrait(pb: Playback, t: Track, cover: android.graphics.Bitmap?, accent: Color, extras: @Composable () -> Unit) {
    var view by remember { mutableIntStateOf(0) } // 0 cover, 1 lyrics, 2 queue
    BackHandler(view != 0) { view = 0 }
    Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Crossfade(view, animationSpec = tween(320), label = "view") { v ->
                when (v) {
                    1 -> LyricsPanel(t, pb, big = false)
                    2 -> QueuePanel(pb)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Cover(cover, pb.playing, Modifier.fillMaxWidth()) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TitleRow(t)
        Spacer(Modifier.height(12.dp))
        Scrubber(pb, t.durationMs)
        Spacer(Modifier.height(4.dp))
        Controls(pb, accent)
        Spacer(Modifier.height(10.dp))
        VolumeBar(pb)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TabPill("Paroles", view == 1) { view = if (view == 1) 0 else 1 }
            TabPill("File d'attente", view == 2) { view = if (view == 2) 0 else 2 }
            Spacer(Modifier.weight(1f))
            extras()
        }
    }
}

@Composable
private fun Landscape(pb: Playback, t: Track, cover: android.graphics.Bitmap?, accent: Color, extras: @Composable () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.fillMaxHeight().weight(.46f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(cover, pb.playing, Modifier.size(140.dp))
                Spacer(Modifier.width(18.dp))
                Box(Modifier.weight(1f)) { TitleRow(t) }
            }
            Spacer(Modifier.height(18.dp))
            Scrubber(pb, t.durationMs)
            Spacer(Modifier.height(4.dp))
            Controls(pb, accent)
            Spacer(Modifier.height(10.dp))
            VolumeBar(pb)
        }
        Spacer(Modifier.width(36.dp))
        Column(Modifier.fillMaxHeight().weight(.54f)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TabPill("Paroles", tab == 0) { tab = 0 }
                TabPill("File d'attente", tab == 1) { tab = 1 }
                Spacer(Modifier.weight(1f))
                extras()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(tab, animationSpec = tween(300), label = "tab") { if (it == 1) QueuePanel(pb) else LyricsPanel(t, pb, big = true) }
            }
        }
    }
}

@Composable
private fun Cover(b: android.graphics.Bitmap?, playing: Boolean, modifier: Modifier) {
    val sc by animateFloatAsState(if (playing) 1f else .9f, tween(450, easing = FastOutSlowInEasing), label = "cs")
    Crossfade(b, animationSpec = tween(450), label = "cover", modifier = modifier) { bmp ->
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }
                .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .08f))
        ) { if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun TitleRow(t: Track) {
    val liked by Spotify.liked.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(t.artists.joinToString(", "), color = Color.White.copy(alpha = .62f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Tap(46.dp, if (liked == true) "Retirer des titres likés" else "Ajouter aux titres likés", { Spotify.toggleLike() }) {
            HeartIcon(liked == true, Color.White.copy(alpha = .6f), Green, 26.dp)
        }
    }
}

@Composable
private fun Scrubber(pb: Playback, dur: Long) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pb.playing) { while (pb.playing) withFrameMillis { frame = it } }
    frame.let { }
    val d = dur.coerceAtLeast(1)
    val pos = pb.position()
    var scrub by remember { mutableFloatStateOf(-1f) }
    val frac = if (scrub >= 0f) scrub else pos.toFloat() / d
    val h by animateFloatAsState(if (scrub >= 0f) 10f else 6f, tween(160), label = "sh")
    Column {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(d) { detectTapGestures { o -> Spotify.seek(((o.x / size.width).coerceIn(0f, 1f) * d).toLong()) } }
                .pointerInput(d) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { Spotify.seek((scrub * d).toLong()); scrub = -1f },
                        onDragCancel = { scrub = -1f },
                    ) { ch, dx -> ch.consume(); scrub = (scrub + dx / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .18f))) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((frac * d).toLong()), color = Color.White.copy(alpha = .5f), fontSize = 12.sp, style = Num)
            Text("-" + fmt(d - (frac * d).toLong()), color = Color.White.copy(alpha = .5f), fontSize = 12.sp, style = Num)
        }
    }
}

@Composable
private fun Controls(pb: Playback, accent: Color) {
    var nextT by remember { mutableIntStateOf(0) }
    var prevT by remember { mutableIntStateOf(0) }
    val on = lerp(accent, Color.White, .3f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Tap(52.dp, "Aléatoire", { Spotify.toggleShuffle() }) { ShuffleIcon(pb.shuffle, Color.White.copy(alpha = .55f), on, 28.dp) }
        Tap(64.dp, "Précédent", { prevT++; Spotify.prev() }) { SkipIcon(false, prevT, Color.White, 38.dp) }
        Tap(78.dp, if (pb.playing) "Pause" else "Lecture", { Spotify.playPause() }) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                PlayPauseIcon(pb.playing, Color.Black, 34.dp)
            }
        }
        Tap(64.dp, "Suivant", { nextT++; Spotify.next() }) { SkipIcon(true, nextT, Color.White, 38.dp) }
        Tap(52.dp, "Répéter", { Spotify.cycleRepeat() }) { RepeatIcon(pb.repeat, Color.White.copy(alpha = .55f), on, 26.dp) }
    }
}

/**
 * Volume of the device that's actually playing: the PC / speaker / TV through Spotify Connect,
 * or the phone's media volume when the phone itself plays.
 */
@Composable
private fun VolumeBar(pb: Playback) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val dev = pb.device ?: return
    val local = isThisPhone(ctx, dev)
    val canRemote = !local && dev.supportsVolume && dev.volume != null
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var localVol by remember { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(local) { while (local) { if (!dragging) localVol = am.getStreamVolume(AudioManager.STREAM_MUSIC); delay(500) } }
    val target = when {
        local -> localVol.toFloat() / max
        canRemote -> (dev.volume ?: 0) / 100f
        else -> 0f
    }
    val f by animateFloatAsState(target, tween(120), label = "v")
    fun set(x: Float, w: Float) {
        val k = (x / w).coerceIn(0f, 1f)
        if (local) { localVol = (k * max).toInt(); am.setStreamVolume(AudioManager.STREAM_MUSIC, localVol, 0) }
        else if (canRemote) Spotify.setVolume((k * 100).toInt())
    }
    Column(Modifier.graphicsLayer { alpha = if (local || canRemote) 1f else .4f }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakerIcon(Color.White.copy(alpha = .55f), 18.dp, 0)
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(
                Modifier.weight(1f).height(28.dp)
                    .pointerInput(local, canRemote) { detectTapGestures { o -> set(o.x, size.width.toFloat()) } }
                    .pointerInput(local, canRemote) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                        ) { ch, _ -> ch.consume(); set(ch.position.x, size.width.toFloat()) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .9f)))
                }
            }
            Spacer(Modifier.width(12.dp))
            SpeakerIcon(Color.White.copy(alpha = .55f), 18.dp, 2)
        }
        Text(
            when {
                local -> "Volume du téléphone"
                canRemote -> "Volume de ${dev.name}"
                else -> "Spotify ne laisse pas régler le volume de ${dev.name}"
            },
            color = Color.White.copy(alpha = .45f), fontSize = 12.sp, modifier = Modifier.padding(start = 30.dp),
        )
    }
}

@Composable
private fun TabPill(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = .1f), tween(220), label = "tp")
    val fg by animateColorAsState(if (on) Color.Black else Color.White.copy(alpha = .75f), tween(220), label = "tf")
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .pointerInput(Unit) { detectTapGestures { onClick() } }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

// ---- lyrics

@Composable
private fun LyricsPanel(t: Track, pb: Playback, big: Boolean) {
    val state by produceState<Pair<String, Lyrics?>?>(null, t.id) { value = t.id to LyricsFinder.find(t) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pb.playing) { while (pb.playing) withFrameMillis { frame = it } }
    frame.let { }
    val ly = state?.takeIf { it.first == t.id }?.second
    when {
        state == null || state?.first != t.id -> Center("Recherche des paroles…")
        ly == null -> Center("Pas de paroles trouvées pour ce titre")
        ly.instrumental -> Center("Instrumental ♪")
        ly.synced != null -> Karaoke(ly.synced, pb.position(), big)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text("Paroles non synchronisées", color = Color.White.copy(alpha = .4f), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text(ly.plain ?: "", color = Color.White.copy(alpha = .85f), fontSize = if (big) 22.sp else 19.sp, lineHeight = if (big) 32.sp else 28.sp,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Center(t: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(t, color = Color.White.copy(alpha = .4f), fontSize = 17.sp)
}

/** Karaoke: current line bright, auto-scroll, edges faded, tap a line to jump there. */
@Composable
private fun Karaoke(lines: List<Pair<Long, String>>, pos: Long, big: Boolean) {
    val idx = max(0, lines.indexOfLast { it.first <= pos + 250 })
    val state = rememberLazyListState()
    LaunchedEffect(idx, lines) { state.animateScrollToItem(max(0, idx - 1)) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .12f to Color.Black, .82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        items(lines.size) { i ->
            val cur = i == idx
            val col by animateColorAsState(
                when { cur -> Color.White; i < idx -> Color.White.copy(alpha = .25f); else -> Color.White.copy(alpha = .45f) }, tween(380), label = "c")
            val sc by animateFloatAsState(if (cur) 1f else .92f, tween(380, easing = FastOutSlowInEasing), label = "s")
            Text(
                lines[i].second.ifBlank { "♪" }, color = col, fontSize = if (big) 30.sp else 26.sp, fontWeight = FontWeight.Bold,
                lineHeight = if (big) 36.sp else 32.sp,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, .5f) }
                    .pointerInput(i) { detectTapGestures { Spotify.seek(lines[i].first) } },
            )
        }
    }
}

// ---- queue, with covers

@Composable
private fun QueuePanel(pb: Playback) {
    val q by Spotify.queue.collectAsState()
    LaunchedEffect(pb.track?.id) { Spotify.refreshQueue() }
    if (q.isEmpty()) { Center("File d'attente vide"); return }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item {
            pb.track?.let { cur ->
                Text("En cours", color = Color.White.copy(alpha = .5f), fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
                QueueRow(cur, current = true, playing = pb.playing) {}
                Text("À suivre", color = Color.White.copy(alpha = .5f), fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp))
            }
        }
        items(q.size) { i -> QueueRow(q[i], current = false, playing = false) { Spotify.playFromQueue(i) } }
    }
}

@Composable
private fun QueueRow(t: Track, current: Boolean, playing: Boolean, onTap: () -> Unit) {
    val img by produceState(Covers.cached(t.thumb), t.thumb) { value = Covers.load(t.thumb) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current) Color.White.copy(alpha = .1f) else Color.Transparent)
            .pointerInput(t.id) { detectTapGestures { onTap() } }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .08f))) {
            Crossfade(img, animationSpec = tween(250), label = "qi") { b ->
                if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = Color.White, fontSize = 16.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artists.joinToString(", "), color = Color.White.copy(alpha = .5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Equalizer(playing, Green, 18.dp)
    }
}

// ---- devices

@Composable
private fun DeviceSheet(close: () -> Unit) {
    val ctx = LocalContext.current
    val list by Spotify.devices.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { Spotify.refreshDevices(); delay(3000) } }
    Sheet(close) {
        Text("Écouter sur", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (list.isEmpty()) Text("Aucun appareil Spotify ouvert. Ouvre Spotify sur un appareil, ou relance-le ici.",
            color = Color.White.copy(alpha = .6f), fontSize = 14.sp)
        list.forEach { d ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (d.active) Green.copy(alpha = .15f) else Color.White.copy(alpha = .06f))
                    .pointerInput(d.id) { detectTapGestures { Spotify.transfer(d); close() } }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceIcon(d.type, if (d.active) Green else Color.White, 22.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = if (d.active) Green else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (d.active) "En cours d'écoute" else d.type, color = Color.White.copy(alpha = .5f), fontSize = 13.sp)
                }
                if (d.active) Equalizer(true, Green, 18.dp)
            }
        }
        Pill("Relancer Spotify sur ce téléphone", Color.White.copy(alpha = .12f), Color.White, modifier = Modifier.fillMaxWidth()) {
            scope.launch { revive(ctx) }
        }
    }
}

@Composable
private fun Sheet(close: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { close() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(10.dp)
                .clip(RoundedCornerShape(30.dp)).background(Color(0xFF1A1B1F))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

// ---- small drawings

@Composable
private fun DeviceIcon(type: String, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(w * .09f, cap = StrokeCap.Round)
        when (type.lowercase()) {
            "computer" -> {
                drawRoundRect(color, Offset(w * .1f, w * .16f), Size(w * .8f, w * .52f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = st)
                drawLine(color, Offset(w * .3f, w * .84f), Offset(w * .7f, w * .84f), w * .09f, StrokeCap.Round)
            }
            "smartphone" -> drawRoundRect(color, Offset(w * .28f, w * .08f), Size(w * .44f, w * .84f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style = st)
            "tv" -> {
                drawRoundRect(color, Offset(w * .06f, w * .18f), Size(w * .88f, w * .56f), androidx.compose.ui.geometry.CornerRadius(w * .06f), style = st)
                drawLine(color, Offset(w * .36f, w * .88f), Offset(w * .64f, w * .88f), w * .09f, StrokeCap.Round)
            }
            "" -> drawCircle(color, w * .3f, style = st)
            else -> { // speaker & co
                drawRoundRect(color, Offset(w * .22f, w * .08f), Size(w * .56f, w * .84f), androidx.compose.ui.geometry.CornerRadius(w * .12f), style = st)
                drawCircle(color, w * .14f, Offset(w * .5f, w * .6f), style = st)
                drawCircle(color, w * .05f, Offset(w * .5f, w * .26f))
            }
        }
    }
}

@Composable
private fun SpeakerIcon(color: Color, size: Dp, waves: Int) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * .1f, w * .38f); lineTo(w * .3f, w * .38f); lineTo(w * .52f, w * .18f)
            lineTo(w * .52f, w * .82f); lineTo(w * .3f, w * .62f); lineTo(w * .1f, w * .62f); close()
        }
        drawPath(p, color)
        val st = Stroke(w * .08f, cap = StrokeCap.Round)
        if (waves >= 1) drawArc(color, -45f, 90f, false, Offset(w * .44f, w * .3f), Size(w * .4f, w * .4f), style = st)
        if (waves >= 2) drawArc(color, -45f, 90f, false, Offset(w * .36f, w * .14f), Size(w * .72f, w * .72f), style = st)
    }
}

/** Soft blobs in the cover's colour drifting slowly while music plays. */
@Composable
private fun MovingBackdrop(accent: Color, playing: Boolean) {
    val inf = rememberInfiniteTransition(label = "bd")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "t")
    val pulse by inf.animateFloat(.9f, 1.08f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val amt by animateFloatAsState(if (playing) 1f else .35f, tween(1200), label = "amt")
    val col by animateColorAsState(accent, tween(1200), label = "col")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val a = t * 2f * Math.PI.toFloat()
        val r = max(w, h)
        fun blob(cx: Float, cy: Float, rad: Float, alpha: Float) =
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), rad), rad, Offset(cx, cy))
        val k = 1f + (pulse - 1f) * amt
        blob(w * (.22f + .08f * kotlin.math.cos(a) * amt), h * (.3f + .08f * kotlin.math.sin(a) * amt), r * .6f * k, .38f)
        blob(w * (.8f + .07f * kotlin.math.sin(a * 1.3f) * amt), h * (.72f + .06f * kotlin.math.cos(a * .8f) * amt), r * .55f * (2f - k), .26f)
        blob(w * (.55f + .1f * kotlin.math.cos(a * .7f + 1f) * amt), h * (.1f + .05f * kotlin.math.sin(a * 1.1f) * amt), r * .4f, .18f)
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
