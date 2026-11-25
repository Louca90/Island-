package com.louca.island

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.toBitmap

class IslandNotificationService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val ctrls = mutableListOf<MediaController>()
    private val seen = LinkedHashSet<String>()
    private val iconCache = HashMap<String, Bitmap?>()
    private val nameCache = HashMap<String, String>()

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
        override fun onSessionDestroyed() = refreshMedia()
    }

    private var compatFor: MediaSession.Token? = null
    private val compatCb = object : android.support.v4.media.session.MediaControllerCompat.Callback() {
        override fun onShuffleModeChanged(shuffleMode: Int) = refreshMedia()
    }

    private fun compatOf(c: MediaController): android.support.v4.media.session.MediaControllerCompat? {
        if (compatFor == c.sessionToken && IslandStore.compat != null) return IslandStore.compat
        runCatching { IslandStore.compat?.unregisterCallback(compatCb) }
        val cc = runCatching {
            android.support.v4.media.session.MediaControllerCompat(
                this, android.support.v4.media.session.MediaSessionCompat.Token.fromToken(c.sessionToken)
            ).also { it.registerCallback(compatCb, handler) }
        }.getOrNull()
        IslandStore.compat = cc
        compatFor = c.sessionToken
        return cc
    }

    private val sessListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list ?: emptyList())
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** Human-readable dump of ongoing notifications (timers, recorders, live updates) for the Diagnostic page. */
    fun dumpOngoing(all: Boolean): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        val list = runCatching { activeNotifications?.toList() }.getOrNull() ?: return "Accès aux notifications indisponible."
        list.filter { all || it.isOngoing }.forEach { sbn ->
            val n = sbn.notification; val ex = n.extras
            sb.append("■ ").append(sbn.packageName).append("  id=").append(sbn.id).append('\n')
            sb.append("  catégorie=").append(n.category).append("  canal=").append(n.channelId).append("  ongoing=").append(sbn.isOngoing).append('\n')
            sb.append("  modèle=").append(ex.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')).append('\n')
            sb.append("  titre=").append(ex.getCharSequence(Notification.EXTRA_TITLE)).append('\n')
            sb.append("  texte=").append(ex.getCharSequence(Notification.EXTRA_TEXT)).append('\n')
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { sb.append("  sous-texte=").append(it).append('\n') }
            sb.append("  chrono=").append(ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                .append("  rebours=").append(ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
                .append("  when=").append((n.`when` - now) / 1000).append("s").append('\n')
            val pm = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
            if (pm > 0) sb.append("  progression=").append(ex.getInt(Notification.EXTRA_PROGRESS)).append('/').append(pm).append('\n')
            sb.append("  boutons=").append(n.actions?.joinToString(" | ") { it.title.toString() } ?: "aucun").append('\n')
            @Suppress("DEPRECATION")
            sb.append("  vue perso=").append(n.contentView != null).append("/").append(n.bigContentView != null)
            findChrono(n)?.let { cv ->
                sb.append("  chronomètre trouvé: base=").append((cv.base - SystemClock.elapsedRealtime()) / 1000).append("s rebours=")
                    .append(cv.isCountDown).append(" texte=").append(cv.text)
            }
            sb.append('\n')
            sb.append("  extras :").append('\n')
            ex.keySet().sorted().forEach { k ->
                val v = runCatching { @Suppress("DEPRECATION") ex.get(k) }.getOrNull()
                val txt = when (v) {
                    null -> "null"
                    is android.graphics.Bitmap, is android.graphics.drawable.Icon -> "[image]"
                    is Long -> "$v" + describeTime(v, now)
                    is Int -> "$v"
                    is LongArray -> v.joinToString(",", "[", "]")
                    is Array<*> -> v.joinToString(",", "[", "]") { it.toString().take(40) }
                    else -> v.toString().take(120)
                }
                sb.append("    ").append(k).append(" = ").append(txt).append('\n')
            }
            IslandStore.acts.value.find { it.key == sbn.key }?.let { a ->
                sb.append("  → Island: ").append(a.kind).append(" chrono=").append(a.chrono).append(" rebours=").append(a.countDown)
                    .append(" base=").append((a.base - now) / 1000).append("s texte=").append(a.timeText)
                    .append(" durée=").append(a.duration / 1000).append("s").append('\n')
            } ?: sb.append("  → Island: non affichée").append('\n')
            sb.append('\n')
        }
        return sb.toString().ifBlank { if (all) "Aucune notification." else "Aucune notification en cours. Lance un minuteur puis actualise." }
    }

    override fun onListenerConnected() {
        IslandStore.cancelNotif = { k -> runCatching { cancelNotification(k) } }
        msm = getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(this, IslandNotificationService::class.java)
        runCatching {
            msm?.addOnActiveSessionsChangedListener(sessListener, cn, handler)
            onSessions(msm?.getActiveSessions(cn) ?: emptyList())
        }
        runCatching { activeNotifications?.forEach { handle(it, initial = true, rm = currentRanking) } }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessListener) }
        ctrls.forEach { runCatching { it.unregisterCallback(cb) } }
        ctrls.clear()
    }

    private fun onSessions(list: List<MediaController>) {
        ctrls.forEach { runCatching { it.unregisterCallback(cb) } }
        ctrls.clear()
        ctrls.addAll(list)
        list.forEach { it.registerCallback(cb, handler) }
        refreshMedia()
    }

    private fun refreshMedia() {
        if (IslandStore.acts.value.any { it.id == "music" && it.pkg == IslandStore.DEMO }) return
        val playing = ctrls.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
        val c = playing ?: ctrls.firstOrNull {
            it.metadata != null && it.playbackState?.state in setOf(
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING
            )
        }
        if (c == null) {
            IslandStore.media = null
            IslandStore.remove("music")
            return
        }
        IslandStore.media = c
        val md = c.metadata!!
        val st = c.playbackState
        val rawArt = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = rawArt?.let { runCatching { Bitmap.createScaledBitmap(it, 192, 192, true) }.getOrNull() }
        val isPlaying = st?.state == PlaybackState.STATE_PLAYING
        run {
            val t = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val ar = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            fetchLyrics(t, ar, md.getLong(MediaMetadata.METADATA_KEY_DURATION), "$t|$ar")
        }
        if (c.packageName in Prefs.state.value.blocked) { IslandStore.remove("music"); return }

        // like + shuffle: custom actions (Spotify, YT Music…) and the compat shuffle mode
        val customs = st?.customActions ?: emptyList()
        fun txt(a: PlaybackState.CustomAction) = "${a.action} ${a.name}".lowercase()
        val like = customs.firstOrNull {
            Regex("like|heart|favou?rite|thumb|aime|collection|library|biblioth|save|enregistr|ajout|retir").containsMatchIn(txt(it)) &&
                !Regex("shuffle|al[ée]atoire|repeat|r[ée]p[ée]t").containsMatchIn(txt(it))
        }
        val shuf = customs.firstOrNull { Regex("shuffle|al[ée]atoire").containsMatchIn(txt(it)) }
        IslandStore.likeAction = like?.action
        IslandStore.shuffleAction = shuf?.action
        val liked = like?.let {
            val t = txt(it)
            Regex("remove|retir|unlike|unfav|supprim|dislike|liked|aim[ée]|saved|added|ajout[ée]").containsMatchIn(t) &&
                !Regex("add_to|add to|ajouter|save to|enregistrer dans").containsMatchIn(t)
        }
        val cc = compatOf(c)
        val mode = runCatching { cc?.shuffleMode ?: -1 }.getOrDefault(-1)
        val shuffle = when {
            mode >= 0 -> mode != android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_NONE
            shuf != null -> Regex("d[ée]sactiv|disable|turn off|shuffle_off|on\\b|activ[ée]e").containsMatchIn(txt(shuf)) &&
                !Regex("activer la|enable|turn on").containsMatchIn(txt(shuf))
            else -> null
        }
        IslandStore.upsert(
            Act(
                id = "music", kind = Kind.MUSIC, pkg = c.packageName, appName = label(c.packageName),
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                text = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
                art = art, accent = accentOf(art),
                playing = isPlaying,
                position = st?.position ?: 0,
                posUpdatedAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                contentIntent = c.sessionActivity,
                appIcon = appIcon(c.packageName),
                liked = liked, shuffle = shuffle,
            ),
            ttl = if (isPlaying) 0 else 5 * 60_000L
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        runCatching { handle(sbn, false, rankingMap) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MODES && sbn.isOngoing && Prefs.state.value.showConn)
            IslandStore.showStatus(Act(id = "sys", kind = Kind.SYSTEM, title = "Mode désactivé", variant = 16, stamp = System.currentTimeMillis()))
        IslandStore.dropNotif(sbn.key)
        IslandStore.removeIf { it.key == sbn.key && it.pkg != IslandStore.DEMO }
    }

    private fun handle(sbn: StatusBarNotification, initial: Boolean, rm: RankingMap?) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val ex = n.extras
        val template = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT) return

        val pkg = sbn.packageName
        if (pkg in Prefs.state.value.blocked && n.category != Notification.CATEGORY_CALL) return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString() ?: ""
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val btns = n.actions?.filter { it.remoteInputs.isNullOrEmpty() && it.title != null }
            ?.map { ActBtn(it.title.toString(), it.actionIntent) } ?: emptyList()
        val allBtns = n.actions?.filter { it.title != null }?.map {
            val ins = it.remoteInputs?.filter { r -> r.allowFreeFormInput }?.toTypedArray()
            ActBtn(it.title.toString(), it.actionIntent, inputs = ins?.takeIf { a -> a.isNotEmpty() })
        }?.sortedByDescending { it.isReply }?.take(3) ?: emptyList()
        val low = "$title $text $pkg ${n.channelId ?: ""}".lowercase()
        val ongoing = sbn.isOngoing
        val st = Prefs.state.value
        // Samsung Modes (Sommeil, Conduite, Travail…): the Modes app posts an ongoing notification while one is on
        if (pkg == MODES) {
            if (!initial && ongoing && st.showConn && title.isNotBlank())
                IslandStore.showStatus(Act(id = "sys", kind = Kind.SYSTEM, title = title, sub = text, variant = 15, stamp = System.currentTimeMillis()))
            return
        }
        val promoted = ex.getBoolean("android.requestPromotedOngoing") || (n.flags and 0x40000) != 0
        val shortText = ex.getCharSequence("android.shortCriticalText")?.toString() ?: ""
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val pMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
        val pVal = ex.getInt(Notification.EXTRA_PROGRESS)
        val indet = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val progress = when {
            pMax > 0 -> (pVal.toFloat() / pMax).coerceIn(0f, 1f)
            indet -> -2f
            else -> -1f
        }
        val sport = Regex("""^(.{2,30}?)\s+(\d{1,3})\s*[-–:]\s*(\d{1,3})\s+(.{2,30})$""").find(title.trim())
        val isClock = pkg.contains("clockpackage") || pkg.contains("deskclock")
        val isRec = pkg.contains("smartcapture") || pkg.contains("screenrecord") ||
            Regex("enregistrement (de l.)?[ée]cran|screen record|arr[eê]ter l.enregistrement").containsMatchIn(low)
        val isNav = !isRec && (n.category == "navigation" || pkg == "com.google.android.apps.maps" || pkg.contains("waze"))

        when {
            n.category == Notification.CATEGORY_CALL || template.contains("CallStyle") -> {
                val callCv = if (!chrono) findChrono(n) else null
                val incoming = !chrono && callCv == null && btns.size >= 2 && !low.contains("en cours") && !low.contains("ongoing") &&
                    btns.any { Regex("r[ée]pond|answer|accept|d[ée]croch").containsMatchIn(it.label) }
                val prevCall = IslandStore.acts.value.find { it.id == "call" }
                val callBase = when {
                    chrono -> n.`when`
                    callCv != null -> System.currentTimeMillis() - (SystemClock.elapsedRealtime() - callCv.base)
                    !incoming && prevCall != null && prevCall.chrono && !prevCall.incoming -> prevCall.base
                    else -> System.currentTimeMillis()
                }
                IslandStore.upsert(
                    Act(
                        id = "call", kind = Kind.CALL, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title.ifBlank { "Appel" }, text = text,
                        icon = largeIcon(n), appIcon = appIcon(pkg),
                        base = callBase, chrono = !incoming, incoming = incoming,
                        actions = btns, contentIntent = n.contentIntent ?: n.fullScreenIntent,
                    )
                )
            }

            ongoing && isRec && !pkg.contains("voicenote") -> {
                IslandStore.upsert(
                    Act(
                        id = "rec:${sbn.key}", kind = Kind.RECORD, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title, text = text, base = n.`when`, chrono = true,
                        timeText = findTime("$title $text"),
                        actions = btns, contentIntent = n.contentIntent,
                    )
                )
            }

            (ongoing || promoted) && isNav && st.showNav -> {
                IslandStore.upsert(
                    Act(
                        id = "nav:${sbn.key}", kind = Kind.NAV, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = shortText.ifBlank { title }, text = text, sub = sub,
                        icon = largeIcon(n), appIcon = appIcon(pkg), contentIntent = n.contentIntent,
                    )
                )
            }

            ongoing && pkg.contains("voicenote") -> {
                IslandStore.upsert(
                    Act(
                        id = "rec:${sbn.key}", kind = Kind.RECORD, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title, text = text, base = n.`when`, chrono = chrono || findTime("$title $text").isEmpty(),
                        timeText = findTime("$title $text"), variant = 1,
                        actions = btns, contentIntent = n.contentIntent,
                    )
                )
            }


            n.category == Notification.CATEGORY_ALARM && (ongoing || n.fullScreenIntent != null) && st.showAlarm -> {
                IslandStore.upsert(
                    Act(
                        id = "alarm", kind = Kind.ALARM, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title.ifBlank { "Alarme" }, text = text, actions = btns,
                        contentIntent = n.contentIntent ?: n.fullScreenIntent,
                    )
                )
            }

            ongoing && isClock -> {
                val tt = findTime("$title $text")
                val cv = if (!chrono) findChrono(n) else null
                val labels = btns.map { it.label.lowercase() }
                // Samsung: running = [Arrêter, Tour] or [Pause, Annuler]; paused = [Reprendre, Réinitialiser]
                val paused = labels.any { Regex("reprend|resume|d[ée]marr|start|continu|lancer").containsMatchIn(it) }
                val running = !paused && labels.any { Regex("pause|tour|lap|arr[eê]t|stop").containsMatchIn(it) }
                val isCount = countDown || Regex("minuteur|timer|compte|restant|remaining").containsMatchIn("$title $text ${n.channelId ?: ""}".lowercase())
                val prev = IslandStore.acts.value.find { it.id == "timer:${sbn.key}" }
                fun total(remaining: Long) = maxOf(prev?.duration ?: 0L, remaining)
                val act = Act(
                    id = "timer:${sbn.key}", kind = Kind.TIMER, key = sbn.key, pkg = pkg, appName = label(pkg),
                    title = title, text = text, actions = btns.take(2), contentIntent = n.contentIntent,
                )
                when {
                    chrono -> IslandStore.upsert(act.copy(
                        base = n.`when`, chrono = true, countDown = isCount,
                        duration = if (isCount) total(n.`when` - System.currentTimeMillis()) else 0L,
                    ))
                    paused -> {
                        val raw = tt.ifEmpty { findTime(cv?.text?.toString() ?: "") }
                        if (raw.isEmpty()) return
                        val ms = parseTime(raw)
                        IslandStore.upsert(act.copy(chrono = false, countDown = isCount, timeText = com.louca.island.ui.fmt(ms),
                            position = if (isCount) ms else 0L,
                            duration = if (isCount) total(ms) else 0L))
                    }
                    cv != null -> {
                        // Chronometer.base is elapsedRealtime-based: convert to wall clock.
                        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - cv.base)
                        val cd = cv.isCountDown || isCount
                        val sTotal = ex.getCharSequence("android.ongoingActivityNoti.secondaryInfo")?.toString()?.let { samsungTotal(it) }
                        IslandStore.upsert(act.copy(base = wall, chrono = true, countDown = cd,
                            duration = if (cd) (sTotal ?: total(wall - System.currentTimeMillis())) else 0L))
                    }
                    running && tt.isNotEmpty() -> {
                        // No live chronometer exposed: run our own clock from the time shown, keep it stable across updates.
                        val ms = parseTime(tt)
                        val now = System.currentTimeMillis()
                        val nb = if (isCount) now + ms else now - ms
                        val old = IslandStore.acts.value.find { it.id == act.id }
                        val base = if (old != null && old.chrono && kotlin.math.abs(old.base - nb) < 2500) old.base else nb
                        IslandStore.upsert(act.copy(base = base, chrono = true, countDown = isCount,
                            duration = if (isCount) total(ms) else 0L))
                    }
                    else -> {
                        val raw = tt.ifEmpty { findTime(cv?.text?.toString() ?: "") }
                        if (raw.isEmpty()) {
                            val (end, sTotal) = scanSamsungTimer(ex)
                            when {
                                end != null && !paused -> IslandStore.upsert(act.copy(base = end, chrono = true, countDown = true,
                                    duration = sTotal ?: total(end - System.currentTimeMillis())))
                                // Nothing usable: still show it (hourglass + buttons) instead of ignoring it.
                                btns.isNotEmpty() -> IslandStore.upsert(act.copy(chrono = false, countDown = true,
                                    timeText = if (paused) "Pause" else "En cours"))
                            }
                            return
                        }
                        IslandStore.upsert(act.copy(chrono = false, timeText = com.louca.island.ui.fmt(parseTime(raw))))
                    }
                }
            }

            sport != null && (ongoing || promoted || pkg.contains("sport")) && st.showLive -> {
                IslandStore.upsert(
                    Act(
                        id = "live:${sbn.key}", kind = Kind.LIVE, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title, text = text, sub = sub.ifBlank { text },
                        teamA = sport.groupValues[1].trim(), teamB = sport.groupValues[4].trim(),
                        score = "${sport.groupValues[2]} – ${sport.groupValues[3]}",
                        appIcon = appIcon(pkg), contentIntent = n.contentIntent,
                    )
                )
            }

            (promoted || template.contains("ProgressStyle") || template.contains("MetricStyle") ||
                (ongoing && progress != -1f)) && st.showLive -> {
                IslandStore.upsert(
                    Act(
                        id = "live:${sbn.key}", kind = Kind.LIVE, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title.ifBlank { label(pkg) }, text = text, sub = sub, short = shortText,
                        progress = progress, icon = largeIcon(n), appIcon = appIcon(pkg),
                        actions = btns.take(2), contentIntent = n.contentIntent,
                    )
                )
            }

            ongoing && chrono -> {
                IslandStore.upsert(
                    Act(
                        id = "timer:${sbn.key}", kind = Kind.TIMER, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title, text = text, base = n.`when`, chrono = true, countDown = countDown,
                        variant = 1, appIcon = appIcon(pkg), actions = btns.take(2), contentIntent = n.contentIntent,
                    )
                )
            }

            !ongoing -> {
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
                if (title.isBlank() && text.isBlank()) return
                val rk = Ranking()
                val imp = if (rm?.getRanking(sbn.key, rk) == true) rk.importance else NotificationManager.IMPORTANCE_DEFAULT
                if (imp <= NotificationManager.IMPORTANCE_MIN) return
                val item = Act(
                    id = "n:${sbn.key}", kind = Kind.NOTIF, key = sbn.key, pkg = pkg, appName = label(pkg),
                    title = title, text = text, icon = largeIcon(n), appIcon = appIcon(pkg),
                    actions = allBtns, contentIntent = n.contentIntent, stamp = sbn.postTime,
                )
                IslandStore.putNotif(item)
                if (initial) return
                if (st.showPay && showPaid(pkg, title, text, n, item)) return
                val otp = findOtp("$title $text")
                if (otp != null && st.showOtp) {
                    IslandStore.upsert(
                        Act(id = "otp", kind = Kind.OTP, key = sbn.key, pkg = pkg, appName = label(pkg),
                            title = "Code", short = otp, appIcon = item.appIcon, contentIntent = n.contentIntent),
                        ttl = 15_000
                    )
                    return
                }
                if (!st.peekNotifs || imp < NotificationManager.IMPORTANCE_DEFAULT) return
                if ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && sbn.key in seen) return
                seen.add(sbn.key)
                if (seen.size > 300) seen.remove(seen.first())
                IslandStore.upsert(
                    Act(
                        id = "notif", kind = Kind.NOTIF, key = sbn.key, pkg = pkg, appName = label(pkg),
                        title = title, text = text, icon = item.icon, appIcon = item.appIcon,
                        actions = allBtns, contentIntent = n.contentIntent,
                        stamp = System.currentTimeMillis(),
                    ),
                    ttl = 10_000
                )
            }
        }
    }

    private fun findOtp(s: String): String? {
        val low = s.lowercase()
        if (!Regex("code|otp|v[ée]rif|validation|connexion|login|2fa|authenti|s[ée]curit|mot de passe|password|pin\\b|confirm").containsMatchIn(low)) return null
        val m = Regex("""(?<![\d+])(\d{3}[ -]\d{3}|\d{4,8})(?!\d)""").findAll(s)
            .map { it.value }
            .firstOrNull { v -> val d = v.filter(Char::isDigit); d.length in 4..8 && !(d.length == 4 && d.startsWith("20")) }
        return m?.filter(Char::isDigit)
    }

    private var lyricsJob: kotlinx.coroutines.Job? = null
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun fetchLyrics(title: String, artist: String, durMs: Long, key: String) {
        if (!Prefs.state.value.showLyrics || title.isBlank()) { IslandStore.lyrics.value = null; return }
        if (IslandStore.lyrics.value?.key == key) return
        lyricsJob?.cancel()
        IslandStore.lyrics.value = null
        lyricsJob = io.launch {
            val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
            fun get(url: String): String? = runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "Island/1.6 (Android)")
                c.connectTimeout = 6000; c.readTimeout = 6000
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
            }.getOrNull()
            var lrc: String? = null
            val d = if (durMs > 0) "&duration=${durMs / 1000}" else ""
            get("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}$d")?.let {
                lrc = runCatching { org.json.JSONObject(it).optString("syncedLyrics", "") }.getOrNull()?.takeIf { s -> s.isNotBlank() }
            }
            if (lrc == null) {
                get("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")?.let {
                    val arr = runCatching { org.json.JSONArray(it) }.getOrNull() ?: return@let
                    for (i in 0 until arr.length()) {
                        val sl = arr.getJSONObject(i).optString("syncedLyrics", "")
                        if (sl.isNotBlank()) { lrc = sl; break }
                    }
                }
            }
            val lines = lrc?.lines()?.mapNotNull { line ->
                val m = Regex("""^\[(\d+):(\d+)(?:[.:](\d+))?]\s*(.*)$""").find(line.trim()) ?: return@mapNotNull null
                val frac = m.groupValues[3].let { f -> if (f.isEmpty()) 0L else (("0.$f").toDouble() * 1000).toLong() }
                val t = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac
                t to m.groupValues[4]
            }?.filter { it.second.isNotBlank() } ?: emptyList()
            if (lines.isNotEmpty()) handler.post { IslandStore.lyrics.value = Lyrics(key, lines) }
        }
    }

    private fun describeTime(v: Long, now: Long): String {
        val el = SystemClock.elapsedRealtime()
        return when {
            v in (now - 86_400_000)..(now + 86_400_000) -> "  (heure murale ${(v - now) / 1000}s)"
            v in (el - 86_400_000)..(el + 86_400_000) && v > 0 -> "  (horloge système ${(v - el) / 1000}s)"
            else -> ""
        }
    }

    /**
     * Samsung's timer puts nothing in the standard fields: look through every extra for
     * an end time (wall clock or elapsedRealtime) or a "mm:ss" text. Returns (endWallMs, frozenRemainingMs).
     */
    /**
     * Samsung timer fallback when no chronometer can be read.
     * secondaryInfo looks like "5 m / 18:11" = total duration / end time of day. Returns (endWallMs, totalMs).
     */
    private fun scanSamsungTimer(ex: android.os.Bundle): Pair<Long?, Long?> {
        val info = ex.getCharSequence("android.ongoingActivityNoti.secondaryInfo")?.toString() ?: return null to null
        return samsungEnd(info) to samsungTotal(info)
    }

    private fun samsungTotal(info: String): Long? {
        var ms = 0L; var found = false
        Regex("""(\d+)\s*(h|m|min|s)\b""").findAll(info.substringBefore('/')).forEach { m ->
            val v = m.groupValues[1].toLong(); found = true
            ms += when (m.groupValues[2]) { "h" -> v * 3_600_000; "s" -> v * 1000; else -> v * 60_000 }
        }
        return if (found) ms else null
    }

    private fun samsungEnd(info: String): Long? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(info.substringAfter('/', "")) ?: return null
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
        cal.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        var t = cal.timeInMillis
        if (t < System.currentTimeMillis() - 60_000) t += 86_400_000
        return t
    }

    private fun findChrono(n: Notification): android.widget.Chronometer? {
        @Suppress("DEPRECATION")
        val extraViews = n.extras.keySet().mapNotNull { k ->
            runCatching { @Suppress("DEPRECATION") n.extras.get(k) as? android.widget.RemoteViews }.getOrNull()
        }
        val views = extraViews + listOfNotNull(n.contentView, n.bigContentView, n.headsUpContentView)
        for (rv in views) {
            val v = runCatching { rv.apply(this, android.widget.FrameLayout(this)) }.getOrNull() ?: continue
            findIn(v)?.let { return it }
        }
        return null
    }

    private fun findIn(v: android.view.View): android.widget.Chronometer? = when (v) {
        is android.widget.Chronometer -> v.takeIf { it.visibility == android.view.View.VISIBLE }
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findIn(v.getChildAt(it)) }
        else -> null
    }

    /** "1:02:03", "02:03", "02:03,45" → ms */
    private fun parseTime(t: String): Long {
        val main = t.split(',', '.')
        val parts = main[0].split(':').mapNotNull { it.trim().toLongOrNull() }
        val frac = main.getOrNull(1)?.let { f -> (("0.$f").toDoubleOrNull() ?: 0.0) * 1000 }?.toLong() ?: 0L
        val sec = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> parts.firstOrNull() ?: 0
        }
        return sec * 1000 + frac
    }

    private fun findTime(s: String): String =
        Regex("""\d{1,2}:\d{2}(:\d{2})?([.,]\d{1,2})?""").find(s)?.value ?: ""

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128)
    }.getOrNull()

    private fun appIcon(pkg: String): Bitmap? = iconCache.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
    }

    // ---- contactless payment receipts
    private val amountRx = Regex(
        """([€$£])\s?(\d{1,3}(?:[ \u00a0\u202f.,]\d{3})*(?:[.,]\d{1,2})?)|(\d{1,3}(?:[ \u00a0\u202f.]\d{3})*(?:[.,]\d{1,2})?)\s?(€|EUR|USD|GBP|CHF|\$|£)""",
        RegexOption.IGNORE_CASE,
    )
    private val payWords = Regex("paiement|payé|payée|achat|paid|payment|purchase|transaction|débit|dépense|sans contact|contactless|carte|card", RegexOption.IGNORE_CASE)
    private val notPaid = Regex("reçu|recu |received|remboursement|refund|virement reçu|vous a envoyé|sent you|refus|declined|échou", RegexOption.IGNORE_CASE)

    /** A payment notification (wallet, or right after a contactless app was open) becomes a "Payé" pill with the amount. */
    private fun showPaid(pkg: String, title: String, text: String, n: Notification, item: Act): Boolean {
        val all = "$title $text"
        val m = amountRx.find(all) ?: return false
        if (notPaid.containsMatchIn(all)) return false
        val chat = n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL || n.category == Notification.CATEGORY_SOCIAL
        val wallet = pkg in IslandStore.payPkgs
        val recent = System.currentTimeMillis() - IslandStore.payContextAt < 5 * 60_000
        if (!(wallet || recent || (payWords.containsMatchIn(all) && !chat))) return false
        val amount = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + m.groupValues[2]
            else m.groupValues[3] + " " + when (m.groupValues[4].uppercase()) { "EUR" -> "€"; "USD" -> "$"; "GBP" -> "£"; else -> m.groupValues[4] }
        val merchant = Regex("(?:\\bchez|\\bat|\\sà|@)\\s+([^,.\\n€$£]{2,32})", RegexOption.IGNORE_CASE).find(all)?.groupValues?.get(1)?.trim()
            ?: title.takeIf { it.isNotBlank() && !amountRx.containsMatchIn(it) && it != label(pkg) && !payWords.matches(it) }
            ?: label(pkg)
        IslandStore.showStatus(
            Act(id = "pay", kind = Kind.PAY, key = item.key, pkg = pkg, appName = label(pkg), appIcon = item.appIcon,
                accent = accentOf(item.appIcon), title = amount, sub = merchant, variant = 1,
                contentIntent = n.contentIntent, stamp = System.currentTimeMillis()),
            bigMs = 3_000, smallMs = 5_000,
        )
        return true
    }

    private fun label(pkg: String): String = nameCache.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    companion object {
        const val MODES = "com.samsung.android.app.routines"
        @Volatile var instance: IslandNotificationService? = null

        fun accentOf(b: Bitmap?): Int {
            if (b == null) return 0
            return runCatching {
                val px = Bitmap.createScaledBitmap(b, 8, 8, true)
                var best = 0; var bestScore = -1f
                val hsv = FloatArray(3)
                for (x in 0 until 8) for (y in 0 until 8) {
                    val c = px.getPixel(x, y)
                    Color.colorToHSV(c, hsv)
                    val score = hsv[1] * (0.35f + hsv[2])
                    if (score > bestScore) { bestScore = score; best = c }
                }
                best
            }.getOrDefault(0)
        }
    }
}
