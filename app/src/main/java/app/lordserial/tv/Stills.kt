package app.lordserial.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Sharp stills for the episode cards. The player's preview sprites are only 160 x 90, so the still
 * is taken from the episode's own stream instead: one 720p segment about ten minutes in (under a
 * megabyte) is fetched and its first frame kept as a JPEG, once per episode.
 *
 * The stream links in the playlist expire within minutes, so the playlist is read again for them
 * rather than taken from the copy the screen was built from.
 */
class Stills(private val dir: File, private val playlistUrl: String, private val userAgent: () -> String) {
    private val downloads = Semaphore(2)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val streamsLock = Mutex()
    private var streams: Map<String, String> = emptyMap()
    private var streamsAt = 0L

    init {
        dir.mkdirs()
    }

    /** The still if it has been made before. */
    fun cached(episode: Episode): File? = file(episode).takeIf { it.length() > 0 }

    /** The still, made now if needed; null when the stream cannot be read. */
    suspend fun load(episode: Episode): File? = withContext(Dispatchers.IO) {
        cached(episode)?.let { return@withContext it }
        locks.getOrPut(episode.key) { Mutex() }.withLock {
            cached(episode) ?: downloads.withPermit {
                runCatching { extract(episode) }.getOrNull()
            }
        }
    }

    private fun file(episode: Episode) = File(dir, "s${episode.season}e${episode.number}.jpg")

    private suspend fun stream(episode: Episode): String? = streamsLock.withLock {
        if (System.currentTimeMillis() - streamsAt > 60_000) {
            val seasons = loadCatalog(playlistUrl, userAgent()) ?: return@withLock null
            streams = seasons.flatMap { it.episodes }
                .mapNotNull { e -> e.stream?.let { e.key to it } }
                .toMap()
            streamsAt = System.currentTimeMillis()
        }
        streams[episode.key]
    }

    private suspend fun extract(episode: Episode): File? {
        val master = stream(episode) ?: return null
        val variant = pickVariant(master) ?: return null
        val at = if (episode.duration > 0) minOf(600, episode.duration / 3) else 600
        val segment = pickSegment(variant, at) ?: return null
        val video = File(dir, "${file(episode).name}.ts")
        try {
            download(segment, video)
            val frame = MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(video.path)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            } ?: return null
            val still = withoutBars(frame)
            val part = File(dir, "${file(episode).name}.part")
            part.outputStream().use { still.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (still !== frame) still.recycle()
            frame.recycle()
            return part.takeIf { it.renameTo(file(episode)) }?.let { file(episode) }
        } finally {
            video.delete()
        }
    }

    /** The 720p variant, or the closest one to it. */
    private fun pickVariant(master: String): String? {
        val lines = text(master).lines()
        return lines.indices
            .filter { lines[it].startsWith("#EXT-X-STREAM-INF") && it + 1 < lines.size }
            .mapNotNull { i ->
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull()
                height?.let { it to URL(URL(master), lines[i + 1].trim()).toString() }
            }
            .minByOrNull { (height, _) -> kotlin.math.abs(height - 720) }
            ?.second
    }

    /** The segment that plays at [seconds]. */
    private fun pickSegment(variant: String, seconds: Int): String? {
        val lines = text(variant).lines()
        var time = 0.0
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXTINF:") || i + 1 >= lines.size) continue
            time += line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: continue
            if (time >= seconds) return URL(URL(variant), lines[i + 1].trim()).toString()
        }
        return null
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", userAgent())
    }

    private fun text(url: String): String {
        val connection = connect(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val connection = connect(url)
        try {
            connection.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }
}

/** Crops the black letterbox bars, found as near-black rows at the top and bottom. */
private fun withoutBars(frame: Bitmap): Bitmap {
    fun dark(y: Int): Boolean {
        val row = IntArray(frame.width)
        frame.getPixels(row, 0, frame.width, 0, y, frame.width, 1)
        var sum = 0L
        var n = 0
        for (x in row.indices step 8) {
            val c = row[x]
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            n++
        }
        return sum / n < 14
    }
    val limit = frame.height * 15 / 100
    var top = 0
    while (top < limit && dark(top)) top++
    var bottom = 0
    while (bottom < limit && dark(frame.height - 1 - bottom)) bottom++
    if (top == 0 && bottom == 0) return frame
    return Bitmap.createBitmap(frame, 0, top, frame.width, frame.height - top - bottom)
}
