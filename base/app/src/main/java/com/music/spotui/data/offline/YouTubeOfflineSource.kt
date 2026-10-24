package com.music.spotui.data.offline

import com.music.spotui.video.MusicVideoController
import com.music.spotui.video.RangeAudioDownload
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.File
import java.io.IOException

/** Official YouTube Music songs only. Keep the served container, without transcoding. */
internal object YouTubeOfflineSource {
    data class Audio(val extension: String, val codec: String, val bitrateKbps: Int, val url: String)
    internal data class Candidate(val extension: String, val codec: String, val bitrateKbps: Int)

    // Advertised rates, not a claim of quality equivalence across codecs.
    internal fun ranked(candidates: List<Candidate>): List<Candidate> = candidates
        .filter { it.extension in setOf("webm", "m4a") && it.bitrateKbps > 0 }
        .sortedWith(compareByDescending<Candidate> { it.bitrateKbps }.thenBy { if (it.codec == "Opus") 0 else 1 })

    suspend fun fetch(title: String, artist: String, durationMs: Long, out: File,
        isrc: String = "", canVerifyAudio: Boolean = false, verify: suspend (File) -> Unit = {}): Audio? {
        if (durationMs <= 0) return null
        val hits = runInterruptible(Dispatchers.IO) {
            YouTubeSource.offlineSongs(title, artist, durationMs, isrc, canVerifyAudio)
        }
        var last: Exception? = null
        for (hit in hits) {
            try {
                val audio = fetchHit(hit, durationMs, out, if (canVerifyAudio) 10000 else 3000)
                verify(out)
                return audio
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                last = e
            }
        }
        if (last != null) throw last
        return null
    }

    private suspend fun fetchHit(hit: YouTubeSource.Hit, durationMs: Long, out: File, toleranceMs: Long): Audio {
        var last: Exception? = null
        // Resolve again once if a cached stream URL has expired.
        for (fresh in listOf(false, true)) {
            currentCoroutineContext().ensureActive()
            val info = runInterruptible(Dispatchers.IO) { YouTubeSource.info(hit.url, fresh = fresh) }
            if (kotlin.math.abs(info.duration * 1000 - durationMs) > toleranceMs) throw IOException("YouTube recording duration mismatch")
            val streams = info.audioStreams.filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.content.startsWith("https://") && it.averageBitrate > 0 &&
                    (it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.M4A)
            }
            val candidates = streams.map {
                Candidate(if (it.format == MediaFormat.M4A) "m4a" else "webm",
                    if (it.format == MediaFormat.M4A) "AAC" else "Opus", it.averageBitrate)
            }
            for (choice in ranked(candidates)) {
                val stream = streams.first { it.averageBitrate == choice.bitrateKbps &&
                    (it.format == MediaFormat.M4A) == (choice.extension == "m4a") }
                try {
                    RangeAudioDownload.fetch(YouTubeSource.http, stream.content, out,
                        MusicVideoController.streamHeaders(stream.content))
                    val expected = choice.bitrateKbps * 1000L / 8 * info.duration
                    if (out.length() < 10_000 || out.length() < expected * 0.6)
                        throw IOException("Incomplete YouTube audio")
                    return Audio(choice.extension, choice.codec, choice.bitrateKbps, hit.url)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    last = e
                }
            }
        }
        throw last ?: IOException("No supported YouTube audio stream")
    }
}
