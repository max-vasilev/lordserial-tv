package app.lordserial.tv

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** One episode as the player's playlist lists it. */
data class Episode(
    val season: Int,
    val number: Int,
    /** Seconds; 0 when unknown. */
    val duration: Int,
    /** URL of the player's preview sprites, with SPRITE_PLACEHOLDER for the sprite number. */
    val previewTemplate: String?,
    /** HLS master playlist; the link expires within minutes. */
    val stream: String? = null
) {
    val key get() = "$season:$number"
}

data class Season(val number: Int, val episodes: List<Episode>)

/** Shown until the playlist has loaded, and when it cannot be loaded: numbers only, no stills. */
fun fallbackCatalog(counts: List<Int>): List<Season> = counts.mapIndexed { index, count ->
    Season(index + 1, (1..count).map { Episode(index + 1, it, 0, null) })
}

private const val SPRITE_PLACEHOLDER = "\${spriteNum}"

/**
 * Reads the seasons from the player's embed page, which carries the whole playlist: every episode
 * with its duration and the preview sprites the player shows over its progress bar.
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
            val preview = episode.optJSONObject("preview")?.optString("src").orEmpty()
            Episode(
                number,
                episodeNumber,
                episode.optInt("duration"),
                preview.takeIf { SPRITE_PLACEHOLDER in it },
                episode.optString("hls").takeIf { it.startsWith("https://") }
            )
        }.sortedBy { it.number })
    }.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }
    return seasons.ifEmpty { null }
}

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

// A preview sprite is a 10 x 10 grid of 160 x 90 frames, one frame every 5 seconds.
private const val SPRITE_COLUMNS = 10
private const val SPRITE_FRAMES = 100
private const val FRAME_SECONDS = 5

/** A frame of a preview sprite: a blurry still shown until the sharp one (Stills) is ready. */
data class Still(val url: String, val frame: Int)

/** About ten minutes in (a third in for a short episode): past the cold open, short of the end. */
fun Episode.still(): Still? {
    val template = previewTemplate ?: return null
    val at = if (duration > 0) minOf(600, duration / 3) else 600
    val index = at / FRAME_SECONDS
    val sprite = index / SPRITE_FRAMES + 1
    return Still(template.replace(SPRITE_PLACEHOLDER, sprite.toString()), index % SPRITE_FRAMES)
}

/** Cuts one frame out of a sprite, without the thin letterbox bars the frames carry. */
class SpriteFrame(private val frame: Int) : Transformation {
    override val cacheKey = "sprite-frame-$frame"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width / SPRITE_COLUMNS
        val height = width * 9 / 16
        val bars = height * 6 / 100
        val x = (frame % SPRITE_COLUMNS) * width
        val y = (frame / SPRITE_COLUMNS) * height
        if (x + width > input.width || y + height > input.height) return input
        return Bitmap.createBitmap(input, x, y + bars, width, height - 2 * bars)
    }
}
