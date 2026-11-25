package com.louca.island

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        IslandStore.appContext = this
    }
}

enum class ThemeMode { BLACK, GRAPHITE, COLOR, LIGHT }

data class Settings(
    val theme: ThemeMode = ThemeMode.GRAPHITE,
    val color: Int = 0xFF26344F.toInt(),
    val offsetY: Float = 0f,
    val offsetX: Float = 0f,
    val pillH: Float = 34f,
    val pillW: Float = 1f,
    val expW: Float = 352f,
    val showIdle: Boolean = false,
    val lockAnim: Boolean = true,
    val peekNotifs: Boolean = true,
    val hideFullscreen: Boolean = true,
    val hideShade: Boolean = true,
    val camMask: Boolean = true,
    val camSize: Float = 22f,
    val camGuide: Boolean = false,
    val showNav: Boolean = true,
    val showLive: Boolean = true,
    val showSystem: Boolean = true,
    val blocked: Set<String> = emptySet(),
    val showOtp: Boolean = true,
    val showAlarm: Boolean = true,
    val showEvents: Boolean = true,
    val eventLead: Float = 15f,
    val showBuds: Boolean = true,
    val showLyrics: Boolean = true,
    val showShots: Boolean = true,
    /** Master switch: off hides the island everywhere; StandBy on charge keeps working. */
    val islandOn: Boolean = true,
    /** Island turned the Samsung pop-ups back on when it was switched off: put them off again when it comes back. */
    val popByIsland: Boolean = false,
    val showConn: Boolean = true,
    val showPay: Boolean = true,
    val updateRepo: String = "",
    val guideSeen: Boolean = false,
    /** Dead zones along the status bar: (start dp from the left edge, width dp). */
    val deadZones: List<Pair<Float, Float>> = emptyList(),
    /** Same, used while the lock screen is showing (operator name, etc.). */
    val deadZonesLock: List<Pair<Float, Float>> = emptyList(),
    val showZones: Boolean = false,
    val standbyOnCharge: Boolean = true,
    val standbyWirelessOnly: Boolean = true,
    val standbyLockOnly: Boolean = true,
)

private fun parseZones(v: String?): List<Pair<Float, Float>> = (v ?: "").split(';').mapNotNull {
    val p = it.split(':')
    if (p.size != 2) return@mapNotNull null
    val x = p[0].toFloatOrNull() ?: return@mapNotNull null
    val w = p[1].toFloatOrNull() ?: return@mapNotNull null
    x to w
}

object Prefs {
    private lateinit var sp: SharedPreferences
    val state = MutableStateFlow(Settings())

    fun init(c: Context) {
        appCtx = c.applicationContext
        sp = c.getSharedPreferences("island", 0)
        state.value = Settings(
            theme = ThemeMode.entries.getOrElse(sp.getInt("theme", 1)) { ThemeMode.GRAPHITE },
            color = sp.getInt("color", 0xFF26344F.toInt()),
            offsetY = sp.getFloat("offY", 0f),
            offsetX = sp.getFloat("offX", 0f),
            pillH = sp.getFloat("pillH", 34f),
            pillW = sp.getFloat("pillW", 1f),
            expW = sp.getFloat("expW", 352f),
            showIdle = sp.getBoolean("idle", false),
            lockAnim = sp.getBoolean("lock", true),
            peekNotifs = sp.getBoolean("peek", true),
            hideFullscreen = sp.getBoolean("fs", true),
            hideShade = sp.getBoolean("shade", true),
            camMask = sp.getBoolean("camMask", true),
            camSize = sp.getFloat("camSize", 22f),
            camGuide = sp.getBoolean("camGuide", false),
            showNav = sp.getBoolean("nav", true),
            showLive = sp.getBoolean("live", true),
            showSystem = sp.getBoolean("sys", true),
            blocked = sp.getStringSet("blocked", emptySet())?.toSet() ?: emptySet(),
            showOtp = sp.getBoolean("otp", true),
            showAlarm = sp.getBoolean("alarm", true),
            showEvents = sp.getBoolean("events", true),
            eventLead = sp.getFloat("eventLead", 15f),
            showBuds = sp.getBoolean("buds", true),
            showLyrics = sp.getBoolean("lyrics", true),
            showShots = sp.getBoolean("shots", true),
            islandOn = sp.getBoolean("on", true),
            popByIsland = sp.getBoolean("popByIsland", false),
            showConn = sp.getBoolean("conn", true),
            showPay = sp.getBoolean("pay", true),
            updateRepo = sp.getString("repo", "Louca90/Island-")?.let { if (it == "Louca90/Island") "Louca90/Island-" else it } ?: "Louca90/Island-",
            guideSeen = sp.getBoolean("guideSeen", false),
            deadZones = parseZones(sp.getString("dz", "")),
            deadZonesLock = parseZones(sp.getString("dzLock", "")),
            showZones = sp.getBoolean("dzShow", false),
            standbyOnCharge = sp.getBoolean("standby", true),
            standbyWirelessOnly = sp.getBoolean("standbyWireless", true),
            standbyLockOnly = sp.getBoolean("standbyLock", true),
        )
    }

    private lateinit var appCtx: Context

    fun exportJson(): String {
        val o = org.json.JSONObject()
        sp.all.forEach { (k, v) ->
            when (v) {
                is Set<*> -> o.put(k, org.json.JSONArray(v.toList()))
                is Float -> o.put(k, org.json.JSONObject().put("f", v.toDouble()))
                else -> o.put(k, v)
            }
        }
        return o.toString(2)
    }

    fun importJson(txt: String): Boolean = runCatching {
        val o = org.json.JSONObject(txt)
        val e = sp.edit().clear()
        o.keys().forEach { k ->
            when (val v = o.get(k)) {
                is org.json.JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                is org.json.JSONObject -> e.putFloat(k, v.getDouble("f").toFloat())
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putInt(k, v.toInt())
                is String -> e.putString(k, v)
                is Double -> e.putFloat(k, v.toFloat())
            }
        }
        e.commit()
        init(appCtx)
    }.isSuccess

    fun update(f: (Settings) -> Settings) {
        val n = f(state.value)
        state.value = n
        sp.edit()
            .putInt("theme", n.theme.ordinal).putInt("color", n.color)
            .putFloat("offY", n.offsetY).putFloat("offX", n.offsetX)
            .putFloat("pillH", n.pillH).putFloat("pillW", n.pillW).putFloat("expW", n.expW)
            .putBoolean("idle", n.showIdle).putBoolean("lock", n.lockAnim)
            .putBoolean("peek", n.peekNotifs).putBoolean("fs", n.hideFullscreen)
            .putBoolean("shade", n.hideShade)
            .putBoolean("camMask", n.camMask).putFloat("camSize", n.camSize).putBoolean("camGuide", n.camGuide)
            .putBoolean("nav", n.showNav).putBoolean("live", n.showLive).putBoolean("sys", n.showSystem)
            .putStringSet("blocked", n.blocked).putBoolean("otp", n.showOtp).putBoolean("alarm", n.showAlarm)
            .putBoolean("events", n.showEvents).putFloat("eventLead", n.eventLead).putBoolean("buds", n.showBuds)
            .putBoolean("lyrics", n.showLyrics).putBoolean("shots", n.showShots).putBoolean("on", n.islandOn).putBoolean("popByIsland", n.popByIsland).putBoolean("conn", n.showConn).putBoolean("pay", n.showPay).putString("repo", n.updateRepo).putBoolean("guideSeen", n.guideSeen)
            .putString("dz", n.deadZones.joinToString(";") { "${it.first}:${it.second}" }).putBoolean("dzShow", n.showZones).putBoolean("standby", n.standbyOnCharge)
            .putBoolean("standbyWireless", n.standbyWirelessOnly).putBoolean("standbyLock", n.standbyLockOnly)
            .putString("dzLock", n.deadZonesLock.joinToString(";") { "${it.first}:${it.second}" })
            .apply()
    }
}

enum class Kind(val prio: Int) {
    INBOX(0), MUSIC(1), LIVE(2), TIMER(2), EVENT(2), TORCH(3), RECORD(3), NAV(4),
    CHARGE(5), SYSTEM(5), AUDIO(5), SHOT(5), PAY(7), NOTIF(6), OTP(6), LOCK(7), CALL(8), ALARM(9)
}

data class Lyrics(val key: String, val lines: List<Pair<Long, String>>)

fun sendPending(pi: PendingIntent?, fill: android.content.Intent? = null): Boolean {
    if (pi == null) return false
    val ctx = IslandStore.appContext
    val opts = android.app.ActivityOptions.makeBasic()
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        @Suppress("DEPRECATION")
        opts.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
    return runCatching { pi.send(ctx, 0, fill, null, null, null, opts.toBundle()) }.isSuccess
}

class ActBtn(
    val label: String,
    val intent: PendingIntent?,
    val inputs: Array<android.app.RemoteInput>? = null,
    val onReply: ((String) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
) {
    val isReply get() = !inputs.isNullOrEmpty() || onReply != null

    fun fire(text: String? = null) {
        if (text != null && onReply != null) { onReply.invoke(text); return }
        onClick?.let { it(); return }
        val fill = if (text != null && !inputs.isNullOrEmpty()) {
            android.content.Intent().also { i ->
                val b = android.os.Bundle()
                inputs.forEach { b.putCharSequence(it.resultKey, text) }
                android.app.RemoteInput.addResultsToIntent(inputs, i, b)
            }
        } else null
        sendPending(intent, fill)
    }
}

data class Act(
    val id: String,
    val kind: Kind,
    val key: String = "",
    val pkg: String = "",
    val appName: String = "",
    val title: String = "",
    val text: String = "",
    val icon: Bitmap? = null,
    val appIcon: Bitmap? = null,
    val art: Bitmap? = null,
    val accent: Int = 0,
    val playing: Boolean = false,
    val position: Long = 0,
    val posUpdatedAt: Long = 0,
    val speed: Float = 1f,
    val duration: Long = 0,
    val base: Long = 0,
    val chrono: Boolean = false,
    val countDown: Boolean = false,
    val timeText: String = "",
    val incoming: Boolean = false,
    val level: Int = 0,
    val unlocked: Boolean = false,
    val actions: List<ActBtn> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val sub: String = "",
    val short: String = "",
    val progress: Float = -1f,
    val variant: Int = 0,
    val teamA: String = "",
    val teamB: String = "",
    val score: String = "",
    val liked: Boolean? = null,
    /** Status pop-ups: shown large until this time (ms), then small. */
    val bigUntil: Long = 0,
    val uri: String = "",
    val shuffle: Boolean? = null,
    val stamp: Long = System.currentTimeMillis(),
) {
    val urgent get() = kind == Kind.CALL || kind == Kind.NOTIF || kind == Kind.CHARGE || kind == Kind.LOCK ||
        kind == Kind.SYSTEM || kind == Kind.OTP || kind == Kind.ALARM || kind == Kind.AUDIO || kind == Kind.SHOT || kind == Kind.PAY
    val lyricsKey get() = "$title|$text"
    val isSport get() = score.isNotEmpty()

    fun livePosition(): Long =
        if (playing) position + ((SystemClock.elapsedRealtime() - posUpdatedAt) * speed).toLong() else position

    fun open() {
        val sys = when (kind) {
            Kind.CHARGE -> android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY)
            Kind.SYSTEM -> when (variant) {
                7, 8 -> android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                9, 10 -> android.content.Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                11, 12 -> android.content.Intent("android.settings.TETHER_SETTINGS")
                13, 14 -> android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                else -> android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
            }
            Kind.PAY -> IslandStore.appContext.packageManager.getLaunchIntentForPackage(pkg)
            else -> null
        }
        if (sys != null && contentIntent == null) {
            runCatching { IslandStore.appContext.startActivity(sys.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        if (!sendPending(contentIntent)) {
            val ctx = IslandStore.appContext
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(it) }
            }
        }
    }

    fun ago(): String {
        val m = (System.currentTimeMillis() - stamp) / 60000
        return when { m < 1 -> "maintenant"; m < 60 -> "$m min"; m < 1440 -> "${m / 60} h"; else -> "${m / 1440} j" }
    }
}

object IslandStore {
    lateinit var appContext: Context
    private val scope = MainScope()
    private val timers = mutableMapOf<String, Job>()

    val acts = MutableStateFlow<List<Act>>(emptyList())
    val pick = MutableStateFlow<String?>(null)
    val expanded = MutableStateFlow(false)
    val swipeDir = MutableStateFlow(0)
    val wallTick = MutableStateFlow(0)
    /** True while the lock screen is showing (picks the lock-screen dead zones). */
    val locked = MutableStateFlow(false)
    /** True while the charging StandBy screen is showing (the island hides). */
    val standby = MutableStateFlow(false)

    /** True when the StandBy screen was opened automatically (so unlocking closes it). */
    var standbyAuto = false
    var closeStandby: (() -> Unit)? = null
    /** Turns the accessibility service off from inside (disableSelf): the island disappears for good. */
    var stopIsland: (() -> Unit)? = null

    fun openStandby(auto: Boolean = false) {
        standbyAuto = auto
        runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, StandbyActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    val lyrics = MutableStateFlow<Lyrics?>(null)
    val lyricsOpen = MutableStateFlow(false)

    fun copyOtp(a: Act) {
        val cm = appContext.getSystemService(android.content.ClipboardManager::class.java)
        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("code", a.short)) }
        upsert(a.copy(title = "Copié", variant = 1), ttl = 1400)
    }

    // ---- notification inbox (newest first)
    val notifs = MutableStateFlow<List<Act>>(emptyList())
    val inboxIndex = MutableStateFlow(0)
    val replying = MutableStateFlow<String?>(null)
    var replyTarget: Act? = null

    /** Opens the reply sheet (a real window, so the keyboard works). */
    fun startReply(a: Act) {
        replyTarget = a
        replying.value = a.key
        extend(a.id, 60_000)
        runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, ReplyActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    // ---- torch (set by the accessibility service)
    var torchOff: (() -> Unit)? = null
    var torchLevel: ((Int) -> Unit)? = null
    var cancelNotif: ((String) -> Unit)? = null

    private fun syncInbox() {
        val l = notifs.value
        inboxIndex.value = inboxIndex.value.coerceIn(0, (l.size - 1).coerceAtLeast(0))
        if (l.isEmpty()) remove("inbox")
        else upsert(Act(id = "inbox", kind = Kind.INBOX, title = "${l.size}", appIcon = l.first().appIcon, icon = l.first().icon,
            level = l.size, stamp = 0))
    }

    fun putNotif(a: Act) {
        notifs.update { l -> (listOf(a) + l.filterNot { it.key == a.key }).take(60) }
        inboxIndex.value = 0
        syncInbox()
    }

    fun dropNotif(key: String) {
        notifs.update { l -> l.filterNot { it.key == key } }
        if (replying.value == key) replying.value = null
        syncInbox()
    }

    fun dismissNotif(key: String) {
        cancelNotif?.invoke(key)
        dropNotif(key)
        acts.value.find { it.kind == Kind.NOTIF && it.key == key }?.let { remove(it.id) }
    }

    fun currentNotif(): Act? = notifs.value.getOrNull(inboxIndex.value)

    var media: MediaController? = null
    var compat: android.support.v4.media.session.MediaControllerCompat? = null
    var likeAction: String? = null
    var shuffleAction: String? = null

    fun upsert(a: Act, ttl: Long = 0) {
        acts.update { l ->
            val old = l.find { it.id == a.id }
            l.filterNot { it.id == a.id } + (if (old != null) a.copy(stamp = old.stamp) else a)
        }
        timers.remove(a.id)?.cancel()
        if (ttl > 0) timers[a.id] = scope.launch { delay(ttl); remove(a.id) }
    }

    fun extend(id: String, ttl: Long) {
        if (acts.value.none { it.id == id }) return
        timers.remove(id)?.cancel()
        timers[id] = scope.launch { delay(ttl); remove(id) }
    }

    private val statusJobs = mutableMapOf<String, Job>()

    /** Apps able to pay contactless (they declare a card-emulation service) + the default wallet. */
    @Volatile var payPkgs: Set<String> = emptySet()
    /** Last time a contactless app was on screen: a payment notification right after it is a payment. */
    @Volatile var payContextAt = 0L

    /**
     * Status pop-ups (sound mode, charging, low battery, earbuds, screenshot):
     * large for [bigMs] and impossible to dismiss, then small for [smallMs], then gone.
     * A refresh while still large only updates the content.
     */
    fun showStatus(a: Act, bigMs: Long = 3_000, smallMs: Long = 5_000) {
        val now = System.currentTimeMillis()
        val cur = acts.value.find { it.id == a.id }
        if (cur != null && cur.bigUntil > now) {
            acts.update { l -> l.map { if (it.id == a.id) a.copy(bigUntil = cur.bigUntil, stamp = it.stamp) else it } }
            return
        }
        upsert(a.copy(bigUntil = now + bigMs))
        statusJobs.remove(a.id)?.cancel()
        statusJobs[a.id] = scope.launch {
            delay(bigMs)
            acts.value.find { it.id == a.id }?.let { upsert(it.copy(bigUntil = 0), ttl = smallMs) }
        }
    }

    fun remove(id: String) {
        statusJobs.remove(id)?.cancel()
        timers.remove(id)?.cancel()
        acts.update { l -> l.filterNot { it.id == id } }
        if (acts.value.none { !it.urgent }) expanded.value = false
    }

    fun removeIf(p: (Act) -> Boolean) {
        acts.value.filter(p).forEach { remove(it.id) }
    }

    fun clear() {
        timers.values.forEach { it.cancel() }; timers.clear()
        acts.value = emptyList(); expanded.value = false
        notifs.value = emptyList(); replying.value = null; inboxIndex.value = 0
    }

    fun primary(l: List<Act>, pk: String?): Act? {
        l.filter { it.urgent }.maxWithOrNull(compareBy({ it.kind.prio }, { it.stamp }))?.let { return it }
        l.find { it.id == pk }?.let { return it }
        return l.maxWithOrNull(compareBy({ it.kind.prio }, { it.stamp }))
    }

    fun others(l: List<Act>, p: Act?): List<Act> =
        l.filter { it.id != p?.id && !it.urgent }
            .sortedWith(compareByDescending<Act> { it.kind == Kind.INBOX }.thenByDescending { it.kind.prio })
            .take(3)

    /** Live activities you swipe between (notifications keep their own bubble). */
    private fun swipeable() = acts.value.filterNot { it.urgent || it.kind == Kind.INBOX }.sortedBy { it.kind.prio }

    /** Next live activity; [vertical] = pulled down from the stack behind the pill. */
    fun cycle(dir: Int, vertical: Boolean = false) {
        val n = neighbor(dir) ?: return
        swipeDir.value = if (vertical) 2 else dir
        pick.value = n.id
        expanded.value = false
    }

    /** The activity a horizontal swipe would bring in ([dir] = 1: from the right). */
    fun neighbor(dir: Int): Act? {
        val l = swipeable()
        if (l.isEmpty()) return null
        val cur = primary(acts.value, pick.value) ?: return l.first()
        val i = l.indexOfFirst { it.id == cur.id }
        // looking at the notifications: the way back is the first live activity
        if (i < 0) return if (cur.urgent) null else if (dir > 0) l.first() else l.last()
        if (l.size < 2) return null
        return l[(i + dir + l.size) % l.size]
    }

    fun select(id: String) {
        val cur = primary(acts.value, pick.value)
        val l = acts.value.filterNot { it.urgent }.sortedBy { it.kind.prio }
        val a = l.indexOfFirst { it.id == cur?.id }
        val b = l.indexOfFirst { it.id == id }
        swipeDir.value = if (b >= a) 1 else -1
        pick.value = id
        expanded.value = false
    }

    // ---- media controls ----
    fun playPause() {
        val m = acts.value.find { it.id == "music" } ?: return
        if (m.pkg == DEMO) {
            upsert(m.copy(playing = !m.playing, position = m.livePosition(), posUpdatedAt = SystemClock.elapsedRealtime()))
            return
        }
        media?.transportControls?.let { if (m.playing) it.pause() else it.play() }
    }

    fun next() {
        val m = acts.value.find { it.id == "music" } ?: return
        if (m.pkg == DEMO) { upsert(m.copy(position = 0, posUpdatedAt = SystemClock.elapsedRealtime())); return }
        media?.transportControls?.skipToNext()
    }

    fun prev() {
        val m = acts.value.find { it.id == "music" } ?: return
        if (m.pkg == DEMO) { upsert(m.copy(position = 0, posUpdatedAt = SystemClock.elapsedRealtime())); return }
        media?.transportControls?.skipToPrevious()
    }

    fun toggleLike() {
        val m = acts.value.find { it.id == "music" } ?: return
        val now = !(m.liked ?: false)
        if (m.pkg != DEMO) likeAction?.let { media?.transportControls?.sendCustomAction(it, null) } ?: return
        upsert(m.copy(liked = now))
    }

    fun toggleShuffle() {
        val m = acts.value.find { it.id == "music" } ?: return
        val now = !(m.shuffle ?: false)
        if (m.pkg != DEMO) {
            val c = compat
            val mode = c?.shuffleMode ?: -1
            if (c != null && mode >= 0) {
                c.transportControls.setShuffleMode(
                    if (now) android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL
                    else android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_NONE
                )
            } else shuffleAction?.let { media?.transportControls?.sendCustomAction(it, null) } ?: return
        }
        upsert(m.copy(shuffle = now))
    }

    // ---- queue & repeat (only if the player shares them through its media session)
    data class QItem(val id: Long, val title: String, val subtitle: String, val icon: android.graphics.Bitmap?)

    private val demoQueue = (1..8).map { QItem(it.toLong(), "Titre suivant n°$it", "Artiste de démo", null) }
    private var demoActive = 1L
    private var demoRepeat = 0

    fun queue(): List<QItem> {
        val m = acts.value.find { it.id == "music" }
        if (m?.pkg == DEMO) return demoQueue
        return runCatching {
            media?.queue?.map { q ->
                val d = q.description
                QItem(q.queueId, d.title?.toString() ?: "", d.subtitle?.toString() ?: "", d.iconBitmap)
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun activeQueueId(): Long {
        val m = acts.value.find { it.id == "music" }
        if (m?.pkg == DEMO) return demoActive
        return media?.playbackState?.activeQueueItemId ?: -1L
    }

    fun playQueueItem(id: Long) {
        val m = acts.value.find { it.id == "music" } ?: return
        if (m.pkg == DEMO) {
            demoActive = id
            demoQueue.find { it.id == id }?.let { q ->
                upsert(m.copy(title = q.title, position = 0, posUpdatedAt = SystemClock.elapsedRealtime(), playing = true))
            }
            return
        }
        media?.transportControls?.skipToQueueItem(id)
    }

    /** 0 = off, 1 = all, 2 = one, -1 = not supported by the player. */
    fun repeatMode(): Int {
        val m = acts.value.find { it.id == "music" }
        if (m?.pkg == DEMO) return demoRepeat
        return when (runCatching { compat?.repeatMode ?: -1 }.getOrDefault(-1)) {
            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_NONE -> 0
            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ALL,
            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_GROUP -> 1
            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ONE -> 2
            else -> -1
        }
    }

    fun cycleRepeat() {
        val m = acts.value.find { it.id == "music" } ?: return
        if (m.pkg == DEMO) { demoRepeat = (demoRepeat + 1) % 3; return }
        val c = compat ?: return
        val next = when (repeatMode()) {
            0 -> android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ALL
            1 -> android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ONE
            else -> android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_NONE
        }
        c.transportControls.setRepeatMode(next)
    }

    fun seek(ms: Long) {
        val m = acts.value.find { it.id == "music" } ?: return
        if (m.pkg == DEMO) { upsert(m.copy(position = ms, posUpdatedAt = SystemClock.elapsedRealtime())); return }
        media?.transportControls?.seekTo(ms)
    }

    const val DEMO = "demo"
}
