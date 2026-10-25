package com.music.spotui.data.offline

import com.music.spotui.video.AudioAlign
import com.music.spotui.video.InterruptibleHttp
import com.music.spotui.video.OfflineSongMatcher
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A recording preview permits translated titles and small timing differences in YouTube matches. */
internal object YouTubeRecordingCheck {
    private val decoding = Mutex()

    class Preview(private val features: Array<DoubleArray>) {
        suspend fun verify(file: File) = decoding.withLock {
            val full = runInterruptible(Dispatchers.Default) { AudioAlign.features(AudioAlign.decode(file)) }
            val match = AudioAlign.align(features, full)
            if (match.segments.isEmpty() || match.score < 0.5)
                throw IOException("Audio did not match the recording preview (score=${match.score})")
        }
    }

    suspend fun load(title: String, artist: String, durationMs: Long, isrc: String, file: File, trackId: String = ""): Preview? {
        try {
            val spotify = spotifyPreview(trackId)
            val downloaded = runInterruptible(Dispatchers.IO) {
                val url = spotify ?: previewUrl(title, artist, durationMs, isrc) ?: return@runInterruptible false
                val request = okhttp3.Request.Builder().url(url).build()
                InterruptibleHttp.read(YouTubeSource.http.newCall(request)) { response ->
                    if (!response.isSuccessful) throw IOException("Preview HTTP ${response.code}")
                    response.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: throw IOException("Empty preview")
                }
                true
            }
            if (!downloaded) return null
            return decoding.withLock {
                Preview(runInterruptible(Dispatchers.Default) { AudioAlign.features(AudioAlign.decode(file)) })
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            android.util.Log.i("Offline", "$title: recording preview unavailable: ${e.message}")
            return null
        } finally { file.delete() }
    }

    /** The public embed's own preview of this exact Spotify track. */
    internal suspend fun spotifyPreview(trackId: String): String? {
        if (!trackId.matches(Regex("[A-Za-z0-9]{22}"))) return null
        return runInterruptible(Dispatchers.IO) {
            runCatching {
                InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder()
                    .url("https://open.spotify.com/embed/track/$trackId").build())) { response ->
                    if (!response.isSuccessful) return@read null
                    spotifyPreviewUrl(response.body?.string().orEmpty(), trackId)
                }
            }.getOrNull()
        }
    }

    internal fun spotifyPreviewUrl(html: String, trackId: String): String? = runCatching {
        val script = Regex("""<script\b[^>]*\bid=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)</script>""")
            .find(html)?.groupValues?.get(1) ?: return@runCatching null
        val entity = JSONObject(script).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        if (entity.optString("uri") != "spotify:track:$trackId") return@runCatching null
        entity.optJSONObject("audioPreview")?.optString("url")?.takeIf { url ->
            val parsed = url.toHttpUrl()
            parsed.isHttps && parsed.host == "p.scdn.co" && parsed.encodedPath.startsWith("/mp3-preview/")
        }
    }.getOrNull()

    private fun json(url: String): JSONObject = InterruptibleHttp.read(
        YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build()),
    ) { response ->
        if (!response.isSuccessful) throw IOException("Preview lookup HTTP ${response.code}")
        JSONObject(response.body?.string().orEmpty())
    }

    private fun previewUrl(title: String, artist: String, durationMs: Long, isrc: String): String? {
        if (isrc.isNotBlank()) {
            val track = json("https://api.deezer.com/track/isrc:$isrc")
            track.optString("preview").takeIf { !track.has("error") && it.startsWith("https://") }?.let { return it }
        }
        val main = artist.substringBefore(',').trim()
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "$main $title").build()
        val data = json(url.toString()).optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { i ->
            val track = data.optJSONObject(i) ?: return@mapNotNull null
            val preview = track.optString("preview").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            OfflineSongMatcher.score(track.optString("title"), track.optJSONObject("artist")?.optString("name").orEmpty(),
                track.optLong("duration"), title, artist, durationMs)?.let { preview to it }
        }.maxByOrNull { it.second }?.first
    }
}
