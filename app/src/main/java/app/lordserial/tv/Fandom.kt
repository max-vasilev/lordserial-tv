package app.lordserial.tv

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// The Russian Stranger Things Wiki on Fandom (CC BY-SA). MediaWiki asks API clients to name themselves.
private const val FANDOM_API = "https://strangerthings.fandom.com/ru/api.php"
private const val FANDOM_AGENT = "LordSerialTV/0.5 (personal Android TV app)"
private const val SERIES_PAGE = "Очень странные дела"

data class EpisodeInfo(val title: String?, val synopsis: String?)

/** What the wiki says about the series and its episodes, keyed "season:episode". */
data class SeriesInfo(val description: String?, val episodes: Map<String, EpisodeInfo>) {
    companion object {
        val EMPTY = SeriesInfo(null, emptyMap())
    }
}

/**
 * Reads the series page, the season pages and the episode pages of the wiki, in two requests.
 * A season page's table lists its episodes in order, some with a short description; an episode
 * page names its season and number and may carry a "Синопсис" section, which comes first. Null
 * when the wiki cannot be read; an episode it says nothing about is left out.
 */
fun loadSeriesInfo(seasons: List<Int>): SeriesInfo? = runCatching {
    val seasonPages = pages(listOf(SERIES_PAGE) + seasons.map { "Сезон $it" })
    // Table rows: (season, number in the table, episode page, short description or null).
    val rows = seasons.flatMap { season ->
        seasonPages["Сезон $season"]?.let(::seasonTable).orEmpty().mapIndexed { i, (page, short) ->
            Row(season, i + 1, page, short)
        }
    }
    val episodePages = if (rows.isEmpty()) emptyMap() else pages(rows.map { it.page }.distinct())
    val episodes = mutableMapOf<String, EpisodeInfo>()
    for (row in rows) {
        val text = episodePages[row.page]
        // The page's own numbering wins over the row order, should a table skip or repeat a row.
        val season = text?.let { field(it, "Сезон") } ?: row.season
        val number = text?.let { field(it, "Эпизод") } ?: row.number
        val synopsis = text?.let(::synopsis) ?: row.short
        episodes["$season:$number"] = EpisodeInfo(episodeTitle(row.page), synopsis?.takeIf { it.isNotEmpty() })
    }
    SeriesInfo(seasonPages[SERIES_PAGE]?.let(::seriesDescription), episodes)
        .takeIf { it.description != null || it.episodes.isNotEmpty() }
}.onFailure { Log.w("LordSerial", "Stranger Things Wiki not read", it) }.getOrNull()

private data class Row(val season: Int, val number: Int, val page: String, val short: String?)

/** Wikitext of the given pages, keyed by the title asked for, redirects followed. */
private fun pages(titles: List<String>): Map<String, String> {
    val form = mapOf(
        "action" to "query",
        "prop" to "revisions",
        "rvprop" to "content",
        "rvslots" to "main",
        "redirects" to "1",
        "format" to "json",
        "formatversion" to "2",
        "titles" to titles.joinToString("|")
    ).entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
    // POST: forty-odd Cyrillic titles make too long a URL.
    val connection = (URL(FANDOM_API).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("User-Agent", FANDOM_AGENT)
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    val body = try {
        connection.outputStream.use { it.write(form.toByteArray()) }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
    val query = JSONObject(body).getJSONObject("query")
    // Asked title -> title of the page that answers it.
    val target = titles.associateWith { it }.toMutableMap()
    for (list in listOf("normalized", "redirects")) {
        val pairs = query.optJSONArray(list) ?: continue
        val map = (0 until pairs.length()).associate {
            pairs.getJSONObject(it).getString("from") to pairs.getJSONObject(it).getString("to")
        }
        target.replaceAll { _, title -> map[title] ?: title }
    }
    val content = mutableMapOf<String, String>()
    val list = query.getJSONArray("pages")
    for (i in 0 until list.length()) {
        val page = list.getJSONObject(i)
        val text = page.optJSONArray("revisions")?.optJSONObject(0)
            ?.optJSONObject("slots")?.optJSONObject("main")?.optString("content") ?: continue
        content[page.getString("title")] = text
    }
    return target.mapNotNull { (asked, title) -> content[title]?.let { asked to it } }.toMap()
}

/** The episode table of a season page: each episode's page and the short description under it, if any. */
private fun seasonTable(text: String): List<Pair<String, String?>> {
    val table = Regex("""\{\|.*?\n\|\}""", RegexOption.DOT_MATCHES_ALL).findAll(text)
        .map { it.value }
        .maxByOrNull { Regex("""\[\[Глава """).findAll(it).count() } ?: return emptyList()
    val rows = mutableListOf<Pair<String, String?>>()
    val item = Regex("""\[\[(Глава [^\]|]+)(?:\|[^\]]*)?]]|colspan="?\d"?\s*\|(.+)""")
    for (match in item.findAll(table)) {
        val page = match.groupValues[1]
        if (page.isNotEmpty()) {
            rows += page.trim() to null
        } else if (rows.isNotEmpty() && rows.last().second == null) {
            rows[rows.lastIndex] = rows.last().first to plainText(match.groupValues[2]).ifEmpty { null }
        }
    }
    return rows
}

/** "Глава седьмая. Мост" -> "Мост": the card already gives the number. */
private fun episodeTitle(page: String) = page.replace(Regex("""^Глава \S+[.:]\s*"""), "").trim()

private fun field(text: String, name: String) =
    Regex("""\|\s*$name\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()

private fun synopsis(text: String): String? =
    Regex("""^==\s*Синопсис\s*==\s*$(.*?)(?=^==|\z)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        .find(text)?.groupValues?.get(1)?.let(::plainText)?.takeIf { it.isNotEmpty() }

/**
 * The plot paragraph of the series page's lead: the lead's first paragraph only names the series,
 * the next tells what it is about. Cut to whole sentences that fit in the header.
 */
private fun seriesDescription(text: String): String? {
    val lead = text.substringBefore("\n==")
    val paragraphs = withoutTemplates(lead).split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("'''") && !it.startsWith("[[") }
    val paragraph = plainText(paragraphs.firstOrNull() ?: return null)
    val sentences = paragraph.split(Regex("""(?<=[.!?])\s+(?=[А-ЯЁA-Z«])"""))
    val kept = StringBuilder(sentences.first())
    for (sentence in sentences.drop(1)) {
        if (kept.length + 1 + sentence.length > 220) break
        kept.append(' ').append(sentence)
    }
    return kept.toString().takeIf { it.isNotEmpty() }
}

// Android's regex engine (ICU) wants braces escaped, closing ones too.
private fun withoutTemplates(text: String): String {
    var result = text
    while (true) {
        val next = result.replace(Regex("""\{\{[^{}]*\}\}"""), "")
        if (next == result) return result
        result = next
    }
}

private fun plainText(wiki: String): String = withoutTemplates(wiki)
    .replace(Regex("""<ref[^>]*/>|<ref[^>]*>.*?</ref>""", RegexOption.DOT_MATCHES_ALL), "")
    .replace(Regex("""<br\s*/?>"""), " ")
    .replace(Regex("""<[^>]+>"""), "")
    .replace(Regex("""\[\[(?:Файл|File|Категория):[^\]]*]]"""), "")
    .replace(Regex("""\[\[(?:[^|\]]*\|)?([^\]]*)]]"""), "$1")
    .replace(Regex("""\[https?://\S+\s([^\]]*)]"""), "$1")
    .replace(Regex("""\[https?://\S+]"""), "")
    .replace("'''", "")
    .replace("''", "")
    .replace("&nbsp;", " ")
    .replace(' ', ' ')
    .replace(Regex("""\s+"""), " ")
    .trim()

fun saveSeriesInfo(file: File, info: SeriesInfo) {
    runCatching {
        val episodes = JSONObject()
        for ((key, episode) in info.episodes) {
            episodes.put(key, JSONObject().put("title", episode.title).put("synopsis", episode.synopsis))
        }
        val part = File(file.path + ".part")
        part.writeText(JSONObject().put("description", info.description).put("episodes", episodes).toString())
        part.renameTo(file)
    }
}

fun readSeriesInfo(file: File): SeriesInfo = runCatching {
    val json = JSONObject(file.readText())
    val episodes = json.getJSONObject("episodes")
    SeriesInfo(
        json.optString("description").ifEmpty { null },
        episodes.keys().asSequence().associateWith {
            val episode = episodes.getJSONObject(it)
            EpisodeInfo(episode.optString("title").ifEmpty { null }, episode.optString("synopsis").ifEmpty { null })
        }
    )
}.getOrDefault(SeriesInfo.EMPTY)
