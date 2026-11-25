package com.louca.island

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.louca.island.ui.IslandRoot
import com.louca.island.ui.fmt
import com.louca.island.ui.islandColors
import com.louca.island.ui.rememberIslandColors
import com.louca.island.ui.wallColor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Home()
            }
        }
    }
}

// ------------------------------------------------------------------ demo data

object Demo {
    private val scope = MainScope()
    private fun now() = System.currentTimeMillis()

    private fun art(color: Int): Bitmap {
        val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x40FFFFFF; cv.drawCircle(132f, 64f, 58f, p)
        p.color = 0x30000000; cv.drawCircle(48f, 150f, 72f, p)
        return b
    }

    fun music() {
        musicAct()
        // Placeholder lines only (the real app fetches synced lyrics for the song playing).
        IslandStore.lyrics.value = Lyrics(
            "Titre de démo assez long pour défiler|Island",
            (0 until 40).map { i -> (60_000L + i * 4_000L) to "Ligne de démo n°${i + 1}" }
        )
    }

    private fun musicAct() = IslandStore.upsert(
        Act(
            id = "music", kind = Kind.MUSIC, pkg = IslandStore.DEMO, appName = "Démo",
            title = "Titre de démo assez long pour défiler", text = "Island",
            art = art(0xFFE4572E.toInt()), accent = 0xFFE4572E.toInt(),
            playing = true, position = 72_000, posUpdatedAt = SystemClock.elapsedRealtime(), duration = 228_000,
            liked = false, shuffle = false,
        )
    )

    private var nI = 0
    fun notif() {
        val key = "demo-${now()}"
        val (app, who, msg) = listOf(
            Triple("Messages", "Nom du contact", "Ceci est un aperçu de notification affiché directement dans l'Island."),
            Triple("Discord", "Serveur · #général", "Quelqu'un t'a mentionné dans un message."),
            Triple("Mail", "Expéditeur", "Objet du mail et début du contenu sur deux lignes maximum."),
        )[nI++ % 3]
        val item = Act(
            id = "n:$key", kind = Kind.NOTIF, key = key, pkg = IslandStore.DEMO, appName = app,
            title = who, text = msg, stamp = now(),
            actions = listOf(
                ActBtn("Répondre", null, onReply = { IslandStore.dropNotif(key) }),
                ActBtn("Marquer lu", null, onClick = { IslandStore.dropNotif(key) }),
            ),
        )
        IslandStore.putNotif(item)
        IslandStore.upsert(item.copy(id = "notif"), ttl = 5200)
    }

    fun inbox() { repeat(3) { notif() }; IslandStore.remove("notif") }

    private fun timerAct(running: Boolean, base: Long, frozen: Long): Act = Act(
        id = "timer:demo", kind = Kind.TIMER, pkg = IslandStore.DEMO, appName = "Horloge", title = "Chronomètre",
        base = base, chrono = running, timeText = fmt(frozen, true),
        actions = listOf(
            ActBtn(if (running) "Pause" else "Reprendre", null) {
                if (running) IslandStore.upsert(timerAct(false, base, now() - base))
                else IslandStore.upsert(timerAct(true, now() - frozen, 0))
            },
            ActBtn("Arrêter", null) { IslandStore.remove("timer:demo") },
        ),
    )

    fun timer() = IslandStore.upsert(timerAct(true, now(), 0))

    fun rec() = IslandStore.upsert(
        Act(
            id = "rec:demo", kind = Kind.RECORD, pkg = IslandStore.DEMO, title = "Enregistrement", base = now(), chrono = true,
            actions = listOf(ActBtn("Arrêter", null) { IslandStore.remove("rec:demo") }),
        )
    )

    fun call() {
        val ringing = Act(
            id = "call", kind = Kind.CALL, pkg = IslandStore.DEMO, appName = "Téléphone", title = "Nom du contact",
            incoming = true,
            actions = listOf(
                ActBtn("Refuser", null) { IslandStore.remove("call") },
                ActBtn("Répondre", null) {
                    IslandStore.upsert(
                        Act(
                            id = "call", kind = Kind.CALL, pkg = IslandStore.DEMO, appName = "Téléphone",
                            title = "Nom du contact", chrono = true, base = now(),
                            actions = listOf(ActBtn("Raccrocher", null) { IslandStore.remove("call") }),
                        )
                    )
                },
            ),
        )
        IslandStore.upsert(ringing)
    }

    fun charge() = IslandStore.upsert(Act(id = "charge", kind = Kind.CHARGE, level = 78), ttl = 3400)

    fun lock() {
        val l = Act(id = "lock", kind = Kind.LOCK)
        IslandStore.upsert(l)
        scope.launch { delay(1500); IslandStore.upsert(l.copy(unlocked = true), ttl = 1150) }
    }

    fun multi() { music(); timer(); nav() }

    fun otp() = IslandStore.upsert(
        Act(id = "otp", kind = Kind.OTP, pkg = IslandStore.DEMO, appName = "Messages", title = "Code", short = "482913"),
        ttl = 15_000
    )

    fun alarm() = IslandStore.upsert(
        Act(
            id = "alarm", kind = Kind.ALARM, pkg = IslandStore.DEMO, appName = "Horloge", title = "Alarme", text = "07:30 · Réveil",
            actions = listOf(
                ActBtn("Répéter", null) { IslandStore.remove("alarm") },
                ActBtn("Arrêter", null) { IslandStore.remove("alarm") },
            ),
        )
    )

    fun event() = IslandStore.upsert(
        Act(id = "event", kind = Kind.EVENT, pkg = IslandStore.DEMO, title = "DS d'histoire-géo", text = "14:00 – 16:00 · Salle B12",
            short = "12 min", base = now() + 12 * 60_000)
    )

    fun buds() {
        IslandStore.upsert(Act(id = "buds", kind = Kind.AUDIO, pkg = IslandStore.DEMO, title = "Galaxy Buds", level = -1), ttl = 4500)
        scope.launch { delay(900); IslandStore.upsert(Act(id = "buds", kind = Kind.AUDIO, pkg = IslandStore.DEMO, title = "Galaxy Buds", level = 82), ttl = 3600) }
    }

    fun nav() = IslandStore.upsert(
        Act(id = "nav:demo", kind = Kind.NAV, pkg = IslandStore.DEMO, appName = "Itinéraire",
            title = "250 m", text = "Tourner à droite sur Avenue de Genève", sub = "Arrivée 18:42 · 12 min")
    )

    fun delivery() {
        val a = Act(id = "live:demo", kind = Kind.LIVE, pkg = IslandStore.DEMO, appName = "Livraison",
            title = "Commande en route", text = "Le livreur arrive bientôt", short = "8 min", progress = .35f)
        IslandStore.upsert(a)
        scope.launch {
            for (i in 1..6) { delay(1500); IslandStore.upsert(a.copy(progress = .35f + i * .1f, short = "${8 - i} min")) }
        }
    }

    fun match() = IslandStore.upsert(
        Act(id = "live:sport", kind = Kind.LIVE, pkg = IslandStore.DEMO, appName = "Sport",
            title = "Équipe A 2 – 1 Équipe B", teamA = "Équipe A", teamB = "Équipe B", score = "2 – 1", sub = "67'")
    )

    fun voice() = IslandStore.upsert(
        Act(id = "rec:voice", kind = Kind.RECORD, pkg = IslandStore.DEMO, title = "Vocal", base = now(), chrono = true,
            variant = 1, actions = listOf(ActBtn("Arrêter", null) { IslandStore.remove("rec:voice") }))
    )

    private var sysI = 0
    fun system() {
        val list = listOf(2 to "Silencieux", 1 to "Vibreur", 0 to "Sonnerie", 3 to "Ne pas déranger", 5 to "Batterie faible · 15 %")
        val (v, t) = list[sysI++ % list.size]
        IslandStore.upsert(Act(id = "sys", kind = Kind.SYSTEM, title = t, variant = v, level = 15, stamp = now()), ttl = 2200)
    }
}

// ------------------------------------------------------------------ screen

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Home() {
    val ctx = LocalContext.current
    val s by Prefs.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val a11y = remember(tick) {
        val v = SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        v.contains(ComponentName(ctx, IslandAccessibilityService::class.java).flattenToString()) ||
            v.contains("${ctx.packageName}/.IslandAccessibilityService")
    }
    val listener = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val ready = a11y && listener

    val cs = MaterialTheme.colorScheme
    val bars = WindowInsets.systemBars.asPaddingValues()
    var screen by remember { androidx.compose.runtime.mutableStateOf("home") }
    if (screen == "apps") {
        androidx.activity.compose.BackHandler { screen = "home" }
        AppsScreen(s, bars) { screen = "home" }
        return
    }
    val secOkTop = remember(tick) { ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED }
    var guide by remember { androidx.compose.runtime.mutableStateOf(!s.guideSeen && !secOkTop) }
    if (guide) TermuxGuide(onClose = { guide = false; Prefs.update { it.copy(guideSeen = true) }; tick++ })
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    val calOk = remember(tick) { ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    val btOk = remember(tick) { ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var updMsg by remember { androidx.compose.runtime.mutableStateOf("") }
    val exportL = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Prefs.exportJson().toByteArray()) } } }
    val importL = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()?.let { Prefs.importJson(it) } ?: false
            android.widget.Toast.makeText(ctx, if (ok) "Réglages importés" else "Fichier invalide", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(cs.surface),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().height(190.dp).padding(start = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.Bottom) {
                Text("Island", fontSize = 40.sp, fontWeight = FontWeight.Normal, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ready) "Active sur ton S24" else "Encore quelques réglages",
                    fontSize = 15.sp, color = if (ready) cs.primary else cs.onSurfaceVariant
                )
            }
        }

        item { PreviewCard() }

        item {
            Card("Tester les animations") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Musique") { Demo.music() }
                    Chip("Notification") { Demo.notif() }
                    Chip("3 notifs (pile)") { Demo.inbox() }
                    Chip("Chrono") { Demo.timer() }
                    Chip("Enregistrement") { Demo.rec() }
                    Chip("Appel") { Demo.call() }
                    Chip("Charge") { Demo.charge() }
                    Chip("Déverrouillage") { Demo.lock() }
                    Chip("Itinéraire") { Demo.nav() }
                    Chip("Livraison") { Demo.delivery() }
                    Chip("Match") { Demo.match() }
                    Chip("Vocal") { Demo.voice() }
                    Chip("Silencieux / NPD") { Demo.system() }
                    Chip("Code de vérif") { Demo.otp() }
                    Chip("Alarme") { Demo.alarm() }
                    Chip("Rendez-vous") { Demo.event() }
                    Chip("Écouteurs") { Demo.buds() }
                    Chip("Multi (bulles)") { Demo.multi() }
                    Chip("Tout effacer") { IslandStore.clear() }
                }
                Spacer(Modifier.height(10.dp))
                Hint("Tap : étendre · Appui long : ouvrir l'app · Glisser ← → : changer d'activité · Glisser ↑ : replier · Tap sur une bulle : la mettre devant.")
            }
        }

        item {
            Card("Autorisations") {
                Step(
                    "1. Paramètres restreints",
                    "App installée hors Play Store : Infos de l'app › ⋮ › Autoriser les paramètres restreints (à faire avant l'étape 2).",
                    a11y, "Infos de l'app"
                ) {
                    ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                Step("2. Accessibilité", "Applications installées › Island › Activer. Sert à dessiner l'île sur la barre d'état.", a11y, "Ouvrir") {
                    ctx.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                Step("3. Accès aux notifications", "Pour la musique, les notifs, le chrono, l'enregistreur et les appels.", listener, "Ouvrir") {
                    ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                Step("4. Batterie", "Évite que One UI coupe l'île en arrière-plan.", battery, "Autoriser") {
                    runCatching {
                        ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }
        }

        item { AppearanceCard(s) }

        item {
            Card("Nouveautés") {
                Toggle("Codes de vérification", "Le code s'affiche dans l'île, un tap le copie.", s.showOtp) { v -> Prefs.update { it.copy(showOtp = v) } }
                Toggle("Alarme", "Répéter / Arrêter directement dans l'île.", s.showAlarm) { v -> Prefs.update { it.copy(showAlarm = v) } }
                Toggle("Prochain rendez-vous", "Depuis ton agenda, avant le début.", s.showEvents) { v -> Prefs.update { it.copy(showEvents = v) } }
                if (s.showEvents) {
                    Label("Afficher avant", "%.0f min".format(s.eventLead))
                    Slider(s.eventLead, { v -> Prefs.update { it.copy(eventLead = v) } }, valueRange = 5f..60f, steps = 10)
                    if (!calOk) Step("Accès à l'agenda", "Nécessaire pour lire tes événements.", false, "Autoriser") {
                        permLauncher.launch(arrayOf(android.Manifest.permission.READ_CALENDAR))
                    }
                }
                Toggle("Écouteurs Bluetooth", "Connexion et batterie des Buds et casques.", s.showBuds) { v -> Prefs.update { it.copy(showBuds = v) } }
                if (s.showBuds && !btOk) Step("Accès Bluetooth", "Pour voir le nom et la batterie des écouteurs.", false, "Autoriser") {
                    permLauncher.launch(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT))
                }
                Toggle("Paroles synchronisées", "Dans le lecteur étendu, via LRCLIB (gratuit). Nécessite Internet.", s.showLyrics) { v ->
                    Prefs.update { it.copy(showLyrics = v) }; if (!v) IslandStore.lyrics.value = null
                }
            }
        }

        item {
            val secOk = remember(tick) { ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED }
            var popOff by remember(tick) {
                androidx.compose.runtime.mutableStateOf(
                    SysSettings.Global.getInt(ctx.contentResolver, "heads_up_notifications_enabled", 1) == 0
                )
            }
            Card("Pop-ups Samsung") {
                if (secOk) {
                    Toggle("Désactiver les pop-ups", "Plus aucune bannière Samsung en haut de l'écran : l'île les remplace. Sons et volet inchangés.", popOff) { v ->
                        runCatching { SysSettings.Global.putInt(ctx.contentResolver, "heads_up_notifications_enabled", if (v) 0 else 1) }
                            .onSuccess { popOff = v }
                    }
                } else {
                    Hint("Android exige une autorisation donnée une seule fois par ADB. Dans Termux, une fois connecté (adb connect), lance cette commande :")
                    Spacer(Modifier.height(8.dp))
                    val cmd = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
                    Text(cmd, fontSize = 13.sp, color = cs.onSurface, modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHighest).padding(12.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            ctx.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                        }, shape = RoundedCornerShape(20.dp)) { Text("Copier") }
                        FilledTonalButton(onClick = {
                            ctx.packageManager.getLaunchIntentForPackage("com.termux")?.let { ctx.startActivity(it) }
                        }, shape = RoundedCornerShape(20.dp)) { Text("Ouvrir Termux") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint("Reviens ensuite ici : l'interrupteur apparaît tout seul.")
                    TextButton(onClick = { guide = true }) { Text("Guide détaillé pas à pas") }
                }
            }
        }

        item {
            Card("Apps masquées") {
                Hint(if (s.blocked.isEmpty()) "Aucune app masquée." else "${s.blocked.size} app(s) n'apparaissent jamais dans l'île.")
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { screen = "apps" }, shape = RoundedCornerShape(20.dp)) { Text("Choisir les apps") }
            }
        }

        item {
            Card("Zone caméra") {
                Toggle("Protéger la zone caméra", "Aucun texte ni bouton sous le trou de la caméra, en compact comme en étendu.", s.camMask) { v -> Prefs.update { it.copy(camMask = v) } }
                Toggle("Afficher le repère", "Dessine un cercle à l'emplacement de la caméra pour régler la taille et la position.", s.camGuide) { v -> Prefs.update { it.copy(camGuide = v) } }
                Label("Taille de la zone", "%.0f dp".format(s.camSize))
                Slider(s.camSize, { v -> Prefs.update { it.copy(camSize = v) } }, valueRange = 12f..34f, steps = 21)
            }
        }

        item {
            Card("Intégrations") {
                Toggle("Itinéraires", "Google Maps, Waze… : prochaine direction et distance.", s.showNav) { v -> Prefs.update { it.copy(showNav = v) } }
                Toggle("Activités en direct", "Live Updates Android : livraisons, VTC, téléchargements, Quick Share, scores sportifs.", s.showLive) { v -> Prefs.update { it.copy(showLive = v) } }
                Toggle("Alertes système", "Silencieux / vibreur, Ne pas déranger, batterie faible.", s.showSystem) { v -> Prefs.update { it.copy(showSystem = v) } }
                Hint("Toujours actifs : musique, chrono et minuteur, dictaphone, enregistreur d'écran, entraînements (Samsung Health…), appels, charge, déverrouillage.")
            }
        }

        item {
            Card("Comportement") {
                Toggle("Pilule au repos", "Garde une petite pilule autour de la caméra quand rien n'est actif.", s.showIdle) { v -> Prefs.update { it.copy(showIdle = v) } }
                Toggle("Cadenas sur l'écran verrouillé", "Le cadenas s'ouvre en animation au déverrouillage.", s.lockAnim) { v -> Prefs.update { it.copy(lockAnim = v) } }
                Toggle("Aperçu des notifications", "Les nouvelles notifs se déroulent depuis la caméra.", s.peekNotifs) { v -> Prefs.update { it.copy(peekNotifs = v) } }
                Toggle("Masquer en plein écran", "Vidéos, jeux : l'île disparaît avec la barre d'état.", s.hideFullscreen) { v -> Prefs.update { it.copy(hideFullscreen = v) } }
                Toggle("Masquer avec le volet ouvert", "L'île s'efface quand tu descends le panneau rapide.", s.hideShade) { v -> Prefs.update { it.copy(hideShade = v) } }
            }
        }

        item {
            Card("Mises à jour & sauvegarde") {
                Hint("Dépôt GitHub public où tu publies les APK en release (ex. louca/island). L'app télécharge la dernière et lance l'installation.")
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = s.updateRepo, onValueChange = { v -> Prefs.update { it.copy(updateRepo = v.trim()) } },
                    label = { Text("propriétaire/dépôt") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        updMsg = "Recherche…"
                        scope.launch { updMsg = Updater.check(ctx, s.updateRepo) }
                    }, shape = RoundedCornerShape(20.dp), enabled = s.updateRepo.contains("/")) { Text("Vérifier") }
                    Spacer(Modifier.width(12.dp))
                    Text(updMsg, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { exportL.launch("island-reglages.json") }, shape = RoundedCornerShape(20.dp)) { Text("Exporter") }
                    FilledTonalButton(onClick = { importL.launch(arrayOf("application/json", "*/*")) }, shape = RoundedCornerShape(20.dp)) { Text("Importer") }
                }
            }
        }

        item {
            Card("Tuto QuickStar : barre d'état propre") {
                TutoStep(1, "Ouvre Good Lock, puis le module QuickStar.")
                TutoStep(2, "Va dans les icônes de la barre d'état et décoche tout sauf l'heure et la batterie (Wi-Fi, réseau, alarme, NFC, etc.).")
                TutoStep(3, "Dans QuickStar, désactive l'affichage de la Now Bar / des notifications en direct dans la barre d'état (nouvelle option One UI 9). Elles restent sur l'écran verrouillé et dans le volet.")
                TutoStep(4, "Icônes de notifications à gauche de l'heure : ça ne se règle pas dans QuickStar. Va dans Paramètres › Notifications › Paramètres avancés › Afficher les icônes de notification, et choisis Aucune.")
                TutoStep(5, "Si des icônes réagissent au mauvais interrupteur : c'est un bug connu de QuickStar sous One UI 9. Mets à jour le module depuis Good Lock.")
                TutoStep(6, "Optionnel : Paramètres › Notifications › Notifications en direct, désactive-les pour Horloge et l'enregistreur pour éviter les doublons.")
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    val i = ctx.packageManager.getLaunchIntentForPackage("com.samsung.android.goodlock")
                    if (i != null) ctx.startActivity(i)
                }) { Text("Ouvrir Good Lock") }
            }
        }
    }
}

@Composable
private fun PreviewCard() {
    val s by Prefs.state.collectAsState()
    val acts by IslandStore.acts.collectAsState()
    val pick by IslandStore.pick.collectAsState()
    val expanded by IslandStore.expanded.collectAsState()
    val c = rememberIslandColors(s)
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(30.dp)).background(cs.surfaceContainerHighest)
    ) {
        val maxW = maxWidth
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("9:41", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text("100 %", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        }
        Box(Modifier.fillMaxSize().padding(top = 10.dp)) {
            IslandRoot(acts, pick, expanded, s, c, hidden = false, maxW = maxW)
        }
        if (acts.isEmpty()) {
            Text(
                "Lance une démo ci-dessous", fontSize = 13.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun AppearanceCard(s: Settings) {
    val ctx = LocalContext.current
    val wall = remember { wallColor(ctx) }
    val cs = MaterialTheme.colorScheme
    Card("Apparence") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                ThemeMode.BLACK to "Noir", ThemeMode.GRAPHITE to "Graphite",
                ThemeMode.COLOR to "Couleur", ThemeMode.LIGHT to "Clair",
            ).forEach { (m, label) ->
                val ic = islandColors(s.copy(theme = m), wall)
                val sel = s.theme == m
                val border by animateColorAsState(if (sel) cs.primary else Color.Transparent, label = "b")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .border(2.dp, border, RoundedCornerShape(18.dp))
                        .background(cs.surfaceContainer)
                        .semantics { role = Role.RadioButton; contentDescription = "Thème $label" }
                        .clickable { Prefs.update { it.copy(theme = m) } }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(52.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(ic.bg).border(1.dp, cs.outlineVariant, RoundedCornerShape(9.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text(label, fontSize = 13.sp, color = cs.onSurface, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (s.theme == ThemeMode.COLOR) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                listOf(0xFF26344F, 0xFF3B2D52, 0xFF1F3D36, 0xFF4B2830, 0xFF2D2F33, 0xFF0F4C81, 0xFF5B4636, 0xFFE9E3D7)
                    .map { it.toInt() }
                    .forEach { col ->
                        val sel = s.color == col
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(if (sel) 3.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, CircleShape)
                                .semantics { role = Role.RadioButton; contentDescription = "Couleur" }
                                .clickable { Prefs.update { it.copy(color = col) } }
                        )
                    }
            }
        }
        Spacer(Modifier.height(16.dp))
        Label("Hauteur de la pilule", "%.0f dp".format(s.pillH))
        Slider(s.pillH, { v -> Prefs.update { it.copy(pillH = v) } }, valueRange = 26f..46f, steps = 19)
        Label("Largeur de la pilule", "%.0f %%".format(s.pillW * 100))
        Slider(s.pillW, { v -> Prefs.update { it.copy(pillW = v) } }, valueRange = .7f..1.4f, steps = 13)
        Label("Largeur étendue", "%.0f dp".format(s.expW))
        Slider(s.expW, { v -> Prefs.update { it.copy(expW = v) } }, valueRange = 280f..400f, steps = 11)
        Label("Position verticale", "%+.0f dp".format(s.offsetY))
        Slider(s.offsetY, { v -> Prefs.update { it.copy(offsetY = v) } }, valueRange = -12f..12f, steps = 23)
        Label("Position horizontale", "%+.0f dp".format(s.offsetX))
        Slider(s.offsetX, { v -> Prefs.update { it.copy(offsetX = v) } }, valueRange = -20f..20f, steps = 39)
        TextButton(onClick = {
            Prefs.update { it.copy(pillH = 34f, pillW = 1f, expW = 352f, offsetX = 0f, offsetY = 0f) }
        }) { Text("Réinitialiser la taille et la position") }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerLow).padding(20.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun Hint(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)

@Composable
private fun Label(a: String, b: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(b, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Step(title: String, sub: String, done: Boolean, btn: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (done) {
            Text("OK", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(20.dp)) { Text(btn) }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!value) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, null)
    }
}

@Composable
private fun TutoStep(n: Int, t: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(t, fontSize = 14.sp, color = cs.onSurface, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}


// ------------------------------------------------------------------ app filter

@Composable
private fun AppsScreen(s: Settings, bars: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var q by remember { androidx.compose.runtime.mutableStateOf("") }
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
            .sortedBy { it.second.lowercase() }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(cs.surface),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().height(150.dp).padding(start = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.Bottom) {
                TextButton(onClick = onBack) { Text("‹ Retour") }
                Text("Apps masquées", fontSize = 32.sp, color = cs.onSurface)
            }
        }
        item {
            androidx.compose.material3.OutlinedTextField(
                value = q, onValueChange = { q = it }, label = { Text("Rechercher") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        val list = apps.filter { q.isBlank() || it.second.contains(q, true) }
        items(list.size) { i ->
            val (pkg, label) = list[i]
            val icon = remember(pkg) {
                runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
            val on = pkg in s.blocked
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Switch) { Prefs.update { st -> st.copy(blocked = if (on) st.blocked - pkg else st.blocked + pkg) } }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Text(label, fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                Switch(on, null)
            }
        }
    }
}

// ------------------------------------------------------------------ updates

object Updater {
    suspend fun check(ctx: android.content.Context, repo: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode != 200) return@runCatching "Aucune release trouvée"
            val o = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            val tag = o.optString("tag_name").trimStart('v', 'V')
            val cur = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
            if (!newer(tag, cur)) return@runCatching "À jour ($cur)"
            val assets = o.optJSONArray("assets") ?: return@runCatching "Pas d'APK dans la release"
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                ?: return@runCatching "Pas d'APK dans la release"
            val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
            val id = dm.enqueue(
                android.app.DownloadManager.Request(Uri.parse(url))
                    .setTitle("Island $tag")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Island-$tag.apk")
            )
            ctx.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c2: android.content.Context, i: Intent) {
                    if (i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                    c2.unregisterReceiver(this)
                    val u = dm.getUriForDownloadedFile(id) ?: return
                    runCatching {
                        c2.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }, android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE), android.content.Context.RECEIVER_EXPORTED)
            "Téléchargement de la $tag…"
        }.getOrElse { "Erreur réseau" }
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}


// ------------------------------------------------------------------ Termux guide

private class GStep(val title: String, val body: String, val cmd: String? = null, val btn: String? = null, val action: ((android.content.Context) -> Unit)? = null)

private val guideSteps = listOf(
    GStep("Pourquoi ce guide ?",
        "Pour couper les pop-ups Samsung, Island doit modifier un réglage système. Android n'autorise ça qu'après une autorisation donnée une seule fois par ADB. Avec Termux, tout se fait depuis ton téléphone, sans PC. Compte 5 minutes."),
    GStep("1. Installer Termux",
        "Installe Termux. La version F-Droid est la plus à jour, celle du Play Store marche aussi. Si tu l'as déjà, passe à l'étape suivante.",
        btn = "Ouvrir / installer Termux",
        action = { c ->
            val i = c.packageManager.getLaunchIntentForPackage("com.termux")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }),
    GStep("2. Installer ADB dans Termux",
        "Colle cette commande dans Termux et valide. Réponds « y » si on te le demande.",
        cmd = "pkg update -y && pkg install -y android-tools"),
    GStep("3. Activer le mode développeur",
        "Paramètres › À propos du téléphone › Informations sur le logiciel, puis tape 7 fois sur « Numéro de version » et entre ton code. Un message confirme que le mode développeur est activé.",
        btn = "Ouvrir À propos du téléphone",
        action = { c -> c.startActivity(Intent(SysSettings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }),
    GStep("4. Activer le débogage sans fil",
        "Ouvre Options de développement, descends jusqu'à « Débogage sans fil » et active-le. Tu dois être connecté en Wi-Fi (n'importe lequel, même celui de l'internat).",
        btn = "Ouvrir Options de développement",
        action = { c -> c.startActivity(Intent(SysSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }),
    GStep("5. Passer en écran partagé",
        "Le code d'appairage disparaît dès que tu changes d'app. Ouvre les applis récentes, appuie sur l'icône de Termux › « Ouvrir en vue écran partagé », puis mets Paramètres › Débogage sans fil dans l'autre moitié."),
    GStep("6. Appairer Termux",
        "Dans Débogage sans fil, appuie sur « Associer l'appareil avec un code d'association ». Note le PORT affiché après les « : » (ex. 37451) et le code à 6 chiffres. Dans Termux, tape la commande en remplaçant PORT, puis entre le code.",
        cmd = "adb pair localhost:PORT"),
    GStep("7. Se connecter",
        "Ferme la fenêtre d'appairage. Sur l'écran Débogage sans fil, regarde « Adresse IP et port » : c'est un AUTRE port. Tape la commande avec ce port. « connected » doit s'afficher.",
        cmd = "adb connect localhost:PORT"),
    GStep("8. Donner l'autorisation à Island",
        "Dernière commande, à coller telle quelle. Elle ne renvoie rien si tout va bien.",
        cmd = "adb shell pm grant com.louca.island android.permission.WRITE_SECURE_SETTINGS"),
    GStep("C'est fait",
        "Reviens dans Island : l'interrupteur « Désactiver les pop-ups » est apparu dans la carte Pop-ups Samsung. Tu peux couper le débogage sans fil, l'autorisation reste.\n\nEn cas de souci :\n• « failed to connect » : le port change à chaque activation, reprends-le à l'étape 7.\n• « protocol fault » ou refus : refais l'étape 6.\n• « Unknown package » : vérifie que tu as bien installé Island avant."),
)

@Composable
private fun TermuxGuide(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var i by remember { mutableIntStateOf(0) }
    var dir by remember { mutableIntStateOf(1) }
    var copied by remember { mutableIntStateOf(-1) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(32.dp))
                .background(cs.surfaceContainerHigh).padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Guide Termux", fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${i + 1}/${guideSteps.size}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                TextButton(onClick = onClose) { Text("Fermer") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                guideSteps.indices.forEach { k ->
                    val col by animateColorAsState(if (k <= i) cs.primary else cs.outlineVariant, label = "gd")
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(col))
                }
            }
            androidx.compose.animation.AnimatedContent(
                targetState = i,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, 60)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { dir * it / 5 })
                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240)) { -dir * it / 5 })
                },
                label = "guide",
            ) { k ->
                val st = guideSteps[k]
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(st.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Text(st.body, fontSize = 15.sp, color = cs.onSurfaceVariant, lineHeight = 21.sp)
                    if (st.cmd != null) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111215)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(st.cmd, fontSize = 13.sp, color = Color(0xFFE8EAED),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("cmd", st.cmd))
                                copied = k
                            }) { Text(if (copied == k) "Copié" else "Copier") }
                        }
                    }
                    if (st.btn != null && st.action != null) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { runCatching { st.action.invoke(ctx) } }, shape = RoundedCornerShape(20.dp)) { Text(st.btn) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { dir = -1; i-- }, enabled = i > 0) { Text("Précédent") }
                if (i < guideSteps.lastIndex) {
                    androidx.compose.material3.Button(onClick = { dir = 1; i++ }, shape = RoundedCornerShape(20.dp)) { Text("Suivant") }
                } else {
                    androidx.compose.material3.Button(onClick = onClose, shape = RoundedCornerShape(20.dp)) { Text("Terminer") }
                }
            }
        }
    }
}
