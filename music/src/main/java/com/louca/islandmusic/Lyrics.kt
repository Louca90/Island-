package com.louca.islandmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** Synced lines (time ms → text) when available, otherwise the plain text, or "instrumental". */
data class Lyrics(val synced: List<Pair<Long, String>>?, val plain: String?, val instrumental: Boolean = false)

/**
 * LRCLIB lookup that doesn't give up at the first miss. Spotify titles often carry extras
 * ("- Remastered 2011", "(feat. X)", "- From the film…", several artists) that make an exact
 * match fail: try exact, then cleaned, then a search ranked by duration, then a free-text search.
 */
object LyricsFinder {
    private val cache = HashMap<String, Lyrics?>()

    suspend fun find(t: Track): Lyrics? {
        if (cache.containsKey(t.id)) return cache[t.id]
        val r = withContext(Dispatchers.IO) { runCatching { lookup(t) }.getOrNull() }
        if (r != null || cache.size < 300) cache[t.id] = r
        return r
    }

    private fun lookup(t: Track): Lyrics? {
        val artist = t.artists.firstOrNull() ?: ""
        val allArtists = t.artists.joinToString(", ")
        val sec = t.durationMs / 1000
        val clean = cleanTitle(t.name)

        // 1. exact (LRCLIB's own matching, tolerant of ±2 s)
        get(t.name, artist, t.album, sec)?.let { return it }
        // 2. cleaned title, no album (albums differ a lot between releases)
        if (clean != t.name) get(clean, artist, null, sec)?.let { return it }
        if (allArtists != artist) get(t.name, allArtists, t.album, sec)?.let { return it }
        // 3. search by fields, keep the closest duration (synced first)
        search("track_name=${Spotify.enc(clean)}&artist_name=${Spotify.enc(artist)}", sec)?.let { return it }
        // 4. free text
        search("q=${Spotify.enc("$clean $artist")}", sec)?.let { return it }
        search("q=${Spotify.enc(clean)}", sec, strict = true)?.let { return it }
        return null
    }

    fun cleanTitle(s: String): String {
        var t = s
        // " - Remastered 2011", " - Radio Edit", " - From \"Film\"", " - Live at…"
        t = t.replace(Regex("""\s+-\s+.*(remaster|version|edit|mix|live|mono|stereo|from|bonus|acoustic|demo|session|instrumental|sped|slowed).*$""", RegexOption.IGNORE_CASE), "")
        // (feat. X) [with Y] (Remastered) …
        t = t.replace(Regex("""\s*[(\[][^)\]]*(feat|ft\.|with|remaster|version|edit|mix|live|from|bonus|prod)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+(feat\.|ft\.)\s+.*$""", RegexOption.IGNORE_CASE), "")
        return t.trim().ifBlank { s }
    }

    private fun get(title: String, artist: String, album: String?, sec: Long): Lyrics? {
        val q = "track_name=${Spotify.enc(title)}&artist_name=${Spotify.enc(artist)}" +
            (album?.takeIf { it.isNotBlank() }?.let { "&album_name=${Spotify.enc(it)}" } ?: "") +
            (if (sec > 0) "&duration=$sec" else "")
        val txt = http("https://lrclib.net/api/get?$q") ?: return null
        return parse(JSONObject(txt))
    }

    private fun search(q: String, sec: Long, strict: Boolean = false): Lyrics? {
        val txt = http("https://lrclib.net/api/search?$q") ?: return null
        val a = JSONArray(txt)
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { sec <= 0 || abs(it.optDouble("duration", 0.0) - sec) <= (if (strict) 2.0 else 5.0) }
            .sortedWith(compareBy<JSONObject>({ it.isNull("syncedLyrics") }, { abs(it.optDouble("duration", 0.0) - sec) }))
        return items.firstNotNullOfOrNull { parse(it) }
    }

    private fun parse(j: JSONObject): Lyrics? {
        if (j.optBoolean("instrumental")) return Lyrics(null, null, instrumental = true)
        val synced = j.optString("syncedLyrics").takeIf { !j.isNull("syncedLyrics") && it.isNotBlank() }?.let(::parseLrc)
        val plain = j.optString("plainLyrics").takeIf { !j.isNull("plainLyrics") && it.isNotBlank() }
        if (synced.isNullOrEmpty() && plain == null) return null
        return Lyrics(synced?.takeIf { it.isNotEmpty() }, plain)
    }

    /** "[01:02.34][01:40.00] text" → one entry per timestamp, sorted. */
    fun parseLrc(lrc: String): List<Pair<Long, String>> {
        val tag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val out = ArrayList<Pair<Long, String>>()
        lrc.lineSequence().forEach { line ->
            val tags = tag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { m ->
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                out += (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms) to text
            }
        }
        return out.sortedBy { it.first }
    }

    private fun http(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "IslandMusic/1.0 (https://github.com/Louca90/Island-)")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}
