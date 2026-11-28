package com.louca.islandmusic

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

// ------------------------------------------------------------------ models

data class Track(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<String>,
    val album: String,
    val image: String?,        // large cover
    val thumb: String?,        // small cover (queue)
    val durationMs: Long,
)

data class Device(
    val id: String,
    val name: String,
    val type: String,          // Computer, Smartphone, Speaker, TV…
    val active: Boolean,
    val volume: Int?,          // 0..100, null when unknown
    val supportsVolume: Boolean,
)

data class Playback(
    val track: Track?,
    val playing: Boolean,
    val progressMs: Long,
    val at: Long,              // local time the progress was read
    val shuffle: Boolean,
    val repeat: Int,           // 0 off, 1 context, 2 track
    val device: Device?,
) {
    fun position(now: Long = System.currentTimeMillis()): Long {
        val d = track?.durationMs ?: 0
        val p = if (playing) progressMs + (now - at) else progressMs
        return if (d > 0) p.coerceIn(0, d) else p
    }
}

/** Why the player can't be shown right now. */
enum class Problem { NONE, NO_DEVICE, PREMIUM, NETWORK, AUTH }

// ------------------------------------------------------------------ store

object Spotify {
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val clientId = MutableStateFlow("")
    val signedIn = MutableStateFlow(false)
    val playback = MutableStateFlow<Playback?>(null)
    val problem = MutableStateFlow(Problem.NONE)
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val liked = MutableStateFlow<Boolean?>(null)
    val authError = MutableStateFlow<String?>(null)

    private var access = ""
    private var expires = 0L
    private val tokenLock = Mutex()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("spotify", Context.MODE_PRIVATE)
        clientId.value = prefs.getString("client", "") ?: ""
        access = prefs.getString("access", "") ?: ""
        expires = prefs.getLong("expires", 0)
        signedIn.value = !prefs.getString("refresh", null).isNullOrBlank()
    }

    fun setClientId(id: String) {
        clientId.value = id.trim()
        prefs.edit().putString("client", id.trim()).apply()
    }

    fun signOut() {
        prefs.edit().remove("refresh").remove("access").remove("expires").apply()
        access = ""; expires = 0
        signedIn.value = false
        playback.value = null
    }

    // ---------------------------------------------------------------- sign-in (PKCE + loopback)

    private const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
        "user-library-read user-library-modify"

    /**
     * Opens the Spotify sign-in page. Spotify only accepts https or a loopback IP as redirect, so the app
     * listens on 127.0.0.1 (random port — allowed for loopback) for the browser coming back with the code.
     */
    fun signIn(ctx: Context) {
        val id = clientId.value
        if (id.isBlank()) { authError.value = "Colle d'abord ton Client ID."; return }
        authError.value = null
        val verifier = randomString(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = randomString(16)
        scope.launch(Dispatchers.IO) {
            val server = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }.getOrElse {
                authError.value = "Impossible d'ouvrir le retour de connexion."; return@launch
            }
            val redirect = "http://127.0.0.1:${server.localPort}/callback"
            val url = "https://accounts.spotify.com/authorize?response_type=code" +
                "&client_id=${enc(id)}&scope=${enc(SCOPES)}&redirect_uri=${enc(redirect)}" +
                "&code_challenge_method=S256&code_challenge=$challenge&state=$state"
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            try {
                server.soTimeout = 5 * 60_000
                server.accept().use { sock ->
                    val line = sock.getInputStream().bufferedReader().readLine() ?: ""
                    val q = Uri.parse("http://x" + line.substringAfter(' ').substringBefore(' '))
                    val code = q.getQueryParameter("code")
                    val ok = code != null && q.getQueryParameter("state") == state
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                        <style>body{background:#0e0f12;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;
                        justify-content:center;height:90vh;text-align:center}a{color:#000;background:#1ed760;padding:14px 26px;border-radius:30px;
                        text-decoration:none;font-weight:bold;margin-top:18px}</style></head><body>
                        <h2>${if (ok) "Connecté à Spotify" else "Connexion refusée"}</h2>
                        <p>${if (ok) "Tu peux revenir dans Island Music." else "Réessaie depuis l'app."}</p>
                        <a href="islandmusic://auth">Revenir dans l'app</a>
                        <script>setTimeout(function(){location.href='islandmusic://auth'},400)</script></body></html>"""
                    val bytes = html.toByteArray()
                    sock.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                    if (!ok) { authError.value = q.getQueryParameter("error")?.let { "Spotify a refusé : $it" } ?: "Connexion annulée."; return@launch }
                    exchange(id, code!!, redirect, verifier)
                }
            } catch (e: Exception) {
                authError.value = "Connexion pas terminée (délai dépassé)."
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private fun exchange(id: String, code: String, redirect: String, verifier: String) {
        val r = form(
            "https://accounts.spotify.com/api/token",
            "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(redirect)}&client_id=${enc(id)}&code_verifier=${enc(verifier)}",
        )
        if (r == null || !r.has("access_token")) {
            authError.value = "Échec de la connexion : " + (r?.optString("error_description")?.ifBlank { null } ?: "réseau")
            return
        }
        saveTokens(r)
        signedIn.value = true
        authError.value = null
    }

    private fun saveTokens(r: JSONObject) {
        access = r.getString("access_token")
        expires = System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60_000
        val e = prefs.edit().putString("access", access).putLong("expires", expires)
        r.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh", it) }
        e.apply()
    }

    private suspend fun token(force: Boolean = false): String? = tokenLock.withLock {
        if (!force && access.isNotBlank() && System.currentTimeMillis() < expires) return access
        val refresh = prefs.getString("refresh", null) ?: return null
        val r = withContext(Dispatchers.IO) {
            form("https://accounts.spotify.com/api/token",
                "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(clientId.value)}")
        }
        if (r == null) { problem.value = Problem.NETWORK; return null }
        if (!r.has("access_token")) { signOut(); problem.value = Problem.AUTH; return null }
        saveTokens(r)
        access
    }

    // ---------------------------------------------------------------- Web API

    private class Res(val code: Int, val body: String)

    private suspend fun call(method: String, path: String, body: String? = null, retry: Boolean = true): Res? {
        val t = token() ?: return null
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
                c.requestMethod = method
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Authorization", "Bearer $t")
                if (method != "GET") {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write((body ?: "").toByteArray()) }
                }
                val code = c.responseCode
                val txt = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429) delay((c.getHeaderField("Retry-After")?.toLongOrNull() ?: 2) * 1000)
                Res(code, txt)
            }.getOrNull()
        }
        if (res == null) { problem.value = Problem.NETWORK; return null }
        if (res.code == 401 && retry) { token(force = true); return call(method, path, body, false) }
        if (res.code == 403 && res.body.contains("PREMIUM", true)) problem.value = Problem.PREMIUM
        return res
    }

    /** Reads the player. 204 = nothing playing anywhere (Spotify closed or killed on every device). */
    suspend fun refresh() {
        val r = call("GET", "/me/player?additional_types=episode") ?: return
        when (r.code) {
            204 -> { playback.value = null; problem.value = Problem.NO_DEVICE }
            200 -> {
                val j = JSONObject(r.body)
                val pb = Playback(
                    track = j.optJSONObject("item")?.let(::parseTrack),
                    playing = j.optBoolean("is_playing"),
                    progressMs = j.optLong("progress_ms"),
                    at = System.currentTimeMillis(),
                    shuffle = j.optBoolean("shuffle_state"),
                    repeat = when (j.optString("repeat_state")) { "context" -> 1; "track" -> 2; else -> 0 },
                    device = j.optJSONObject("device")?.let(::parseDevice),
                )
                val old = playback.value
                if (!holdingVolume() || pb.device?.volume == old?.device?.volume) playback.value = pb
                else playback.value = pb.copy(device = pb.device?.copy(volume = old?.device?.volume))
                problem.value = Problem.NONE
                if (old?.track?.id != pb.track?.id) { refreshQueue(); refreshLiked() }
            }
        }
    }

    suspend fun refreshQueue() {
        val r = call("GET", "/me/player/queue") ?: return
        if (r.code != 200) return
        val a = JSONObject(r.body).optJSONArray("queue") ?: JSONArray()
        queue.value = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseTrack) }
    }

    suspend fun refreshDevices() {
        val r = call("GET", "/me/player/devices") ?: return
        if (r.code != 200) return
        val a = JSONObject(r.body).optJSONArray("devices") ?: JSONArray()
        devices.value = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseDevice) }
    }

    private suspend fun refreshLiked() {
        val uri = playback.value?.track?.uri ?: return
        val r = call("GET", "/me/library/contains?uris=${enc(uri)}") ?: return
        if (r.code == 200) liked.value = runCatching { JSONArray(r.body).getBoolean(0) }.getOrNull()
    }

    // ---------------------------------------------------------------- commands (optimistic, then re-read)

    private fun after(ms: Long = 350) = scope.launch { delay(ms); refresh() }

    fun playPause() {
        val pb = playback.value ?: return
        playback.value = pb.copy(playing = !pb.playing, progressMs = pb.position(), at = System.currentTimeMillis())
        scope.launch { call("PUT", if (pb.playing) "/me/player/pause" else "/me/player/play"); after() }
    }

    fun next() = scope.launch { call("POST", "/me/player/next"); after(450) }
    fun prev() = scope.launch {
        // like Spotify: past 3 s, "previous" restarts the song
        val pb = playback.value
        if (pb != null && pb.position() > 3000) seek(0) else { call("POST", "/me/player/previous"); after(450) }
    }

    fun seek(ms: Long) {
        val pb = playback.value ?: return
        playback.value = pb.copy(progressMs = ms, at = System.currentTimeMillis())
        scope.launch { call("PUT", "/me/player/seek?position_ms=$ms"); after(600) }
    }

    fun toggleShuffle() {
        val pb = playback.value ?: return
        playback.value = pb.copy(shuffle = !pb.shuffle)
        scope.launch { call("PUT", "/me/player/shuffle?state=${!pb.shuffle}"); refreshQueue() }
    }

    fun cycleRepeat() {
        val pb = playback.value ?: return
        val n = (pb.repeat + 1) % 3
        playback.value = pb.copy(repeat = n)
        scope.launch { call("PUT", "/me/player/repeat?state=" + when (n) { 1 -> "context"; 2 -> "track"; else -> "off" }) }
    }

    fun toggleLike() {
        val uri = playback.value?.track?.uri ?: return
        val now = liked.value ?: false
        liked.value = !now
        scope.launch {
            val r = call(if (now) "DELETE" else "PUT", "/me/library?uris=${enc(uri)}")
            if (r == null || r.code >= 300) liked.value = now
        }
    }

    /** Jumps to the [index]-th song of the queue (the Web API can only skip, so it skips index + 1 times). */
    fun playFromQueue(index: Int) = scope.launch {
        repeat(index + 1) { call("POST", "/me/player/next"); delay(120) }
        after(500)
    }

    fun transfer(d: Device, play: Boolean = true) = scope.launch {
        call("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(d.id)).put("play", play).toString())
        delay(800); refresh(); refreshDevices()
    }

    // ---- volume of the device that is actually playing (PC, speaker, TV…), throttled while dragging
    private var volHoldUntil = 0L
    private var volJob: kotlinx.coroutines.Job? = null
    private fun holdingVolume() = System.currentTimeMillis() < volHoldUntil

    fun setVolume(percent: Int) {
        val pb = playback.value ?: return
        val dev = pb.device ?: return
        val v = percent.coerceIn(0, 100)
        playback.value = pb.copy(device = dev.copy(volume = v))
        volHoldUntil = System.currentTimeMillis() + 1500
        if (volJob?.isActive == true) return
        volJob = scope.launch {
            var sent = -1
            while (true) {
                val want = playback.value?.device?.volume ?: break
                if (want == sent) break
                call("PUT", "/me/player/volume?volume_percent=$want")
                sent = want
                delay(160)
            }
        }
    }

    // ---------------------------------------------------------------- parsing & helpers

    private fun parseTrack(j: JSONObject): Track {
        val imgs = j.optJSONObject("album")?.optJSONArray("images") ?: j.optJSONArray("images") ?: JSONArray()
        val list = (0 until imgs.length()).mapNotNull { imgs.optJSONObject(it) }.sortedBy { it.optInt("width") }
        val artists = j.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }
            ?: listOfNotNull(j.optJSONObject("show")?.optString("name"))
        return Track(
            id = j.optString("id"), uri = j.optString("uri"), name = j.optString("name"),
            artists = artists,
            album = j.optJSONObject("album")?.optString("name") ?: "",
            image = list.lastOrNull()?.optString("url"),
            thumb = (list.firstOrNull { it.optInt("width") >= 100 } ?: list.lastOrNull())?.optString("url"),
            durationMs = j.optLong("duration_ms"),
        )
    }

    private fun parseDevice(j: JSONObject) = Device(
        id = j.optString("id"), name = j.optString("name"), type = j.optString("type"),
        active = j.optBoolean("is_active"),
        volume = if (j.isNull("volume_percent")) null else j.optInt("volume_percent"),
        supportsVolume = j.optBoolean("supports_volume", true),
    )

    private fun form(url: String, body: String): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val txt = (if (c.responseCode >= 400) c.errorStream else c.inputStream).bufferedReader().use { it.readText() }
        JSONObject(txt)
    }.getOrNull()

    private fun randomString(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ------------------------------------------------------------------ covers

object Covers {
    private val cache = LruCache<String, Bitmap>(60)
    private val lock = Mutex()

    fun cached(url: String?): Bitmap? = url?.let { cache.get(it) }

    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.also { lock.withLock { cache.put(url, it) } }
        }
    }

    /** A lively colour from the cover for the background (most saturated of a 12×12 thumbnail). */
    fun accent(b: Bitmap?): Int {
        if (b == null) return 0xFF1ED760.toInt()
        val s = Bitmap.createScaledBitmap(b, 12, 12, true)
        var best = 0xFF808080.toInt(); var score = -1f
        val hsv = FloatArray(3)
        for (x in 0 until 12) for (y in 0 until 12) {
            val p = s.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            val sc = hsv[1] * (if (hsv[2] < .25f) .2f else hsv[2])
            if (sc > score) { score = sc; best = p }
        }
        return best
    }
}
