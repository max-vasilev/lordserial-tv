package app.lordserial.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One episode as the player's playlist lists it. */
data class Episode(
    val season: Int,
    val number: Int,
    /** Seconds; 0 when unknown. */
    val duration: Int,
    /** HLS master playlist; the link expires within minutes. */
    val stream: String? = null
) {
    val key get() = "$season:$number"
}

data class Season(val number: Int, val episodes: List<Episode>)

/** Shown until the playlist has loaded for the first time: numbers only, no durations. */
fun fallbackCatalog(counts: List<Int>): List<Season> = counts.mapIndexed { index, count ->
    Season(index + 1, (1..count).map { Episode(index + 1, it, 0) })
}

/**
 * Reads the seasons from the player's embed page, which carries the whole playlist: every episode
 * with its duration and its stream.
 */
fun loadCatalog(url: String, userAgent: String): List<Season>? = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", userAgent)
    val html = try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
    parseSeasons(html)
}.getOrNull()

private fun parseSeasons(html: String): List<Season>? {
    val playlist = html.indexOf("playlist:")
    if (playlist < 0) return null
    val key = html.indexOf("seasons:", playlist)
    if (key < 0) return null
    val start = html.indexOf('[', key)
    val end = matchingBracket(html, start) ?: return null
    val array = JSONArray(html.substring(start, end + 1))
    val seasons = (0 until array.length()).map { i ->
        val season = array.getJSONObject(i)
        val number = season.getInt("season")
        val episodes = season.getJSONArray("episodes")
        Season(number, (0 until episodes.length()).mapNotNull { j ->
            val episode = episodes.getJSONObject(j)
            val episodeNumber = episode.optString("episode").toIntOrNull() ?: return@mapNotNull null
            Episode(
                number,
                episodeNumber,
                episode.optInt("duration"),
                episode.optString("hls").takeIf { it.startsWith("https://") }
            )
        }.sortedBy { it.number })
    }.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }
    return seasons.ifEmpty { null }
}

/**
 * Keeps the seasons for the next start, so the screen opens complete before the playlist has been
 * fetched again (or when it cannot be). The stream links are left out: they expire within minutes.
 */
fun saveCatalog(file: File, seasons: List<Season>) {
    runCatching {
        val array = JSONArray()
        for (season in seasons) {
            val episodes = JSONArray()
            for (episode in season.episodes) {
                episodes.put(JSONObject().put("episode", episode.number).put("duration", episode.duration))
            }
            array.put(JSONObject().put("season", season.number).put("episodes", episodes))
        }
        val part = File(file.path + ".part")
        part.writeText(array.toString())
        part.renameTo(file)
    }
}

fun readCatalog(file: File): List<Season>? = runCatching {
    val array = JSONArray(file.readText())
    (0 until array.length()).map { i ->
        val season = array.getJSONObject(i)
        val number = season.getInt("season")
        val episodes = season.getJSONArray("episodes")
        Season(number, (0 until episodes.length()).map { j ->
            val episode = episodes.getJSONObject(j)
            Episode(number, episode.getInt("episode"), episode.optInt("duration"))
        })
    }.filter { it.episodes.isNotEmpty() }.ifEmpty { null }
}.getOrNull()

/** Index of the bracket closing the one at [start], skipping brackets inside JSON strings. */
private fun matchingBracket(text: String, start: Int): Int? {
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            inString -> when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '[' || c == '{' -> depth++
            c == ']' || c == '}' -> if (--depth == 0) return i
        }
    }
    return null
}
