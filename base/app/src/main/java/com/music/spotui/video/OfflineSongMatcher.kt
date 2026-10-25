package com.music.spotui.video

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Independently implemented, inspired by spotDL's ISRC-first, multi-signal matching. */
internal object OfflineSongMatcher {
    private val versions = Regex("(?i)\\b(live|cover|remix|remaster(?:ed)?|acoustic|instrumental|karaoke|slowed|sped[ -]?up|nightcore|reverb|8d)\\b|ライブ|カバー|歌ってみた|弾いてみた|カラオケ|オルゴール")
    private val artistCredits = Regex("""\s*[〔【][^〕】]*cv\s*[:：][^〕】]*[〕】]""", RegexOption.IGNORE_CASE)
    private fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun title(s: String) = normalized(s)
        .replace(Regex("(?i)\\s*(?:\\(|\\[)?(?:feat|ft)\\.?\\s+.*"), "")
        .replace(Regex("(?i)\\b(official|music|video|audio|lyrics?)\\b"), "")
        .filter { it.isLetterOrDigit() }
    private fun artist(s: String) = normalized(s).replace(artistCredits, "").replace(Regex("(?i)\\s*-\\s*topic$|vevo$|\\s+official(?:\\s+(?:channel|music))?$"), "")
        .filter { it.isLetterOrDigit() }

    fun queries(title: String, artists: String, isrc: String): List<String> {
        val queries = mutableListOf<String>()
        val main = artists.substringBefore(',').replace(artistCredits, "").trim()
        if (isrc.matches(Regex("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}"))) queries += isrc
        queries += "$title $main".trim()
        val bare = title.replace(Regex("(?i)\\s*(?:\\(|\\[)?(?:feat|ft)\\.?\\s+.*"), "").trim()
        queries += "$bare $main".trim()
        // Apostrophes and repeated punctuation can send Music search to unrelated results.
        val words = bare.replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (words.isNotBlank() && words != bare) queries += "$words $main".trim()
        // A long or differently indexed artist name can hide an otherwise exact song.
        // Candidate validation still checks the artist, recording version and duration.
        queries += bare
        return queries.filter { it.isNotBlank() }.distinct()
    }

    fun score(name: String, uploader: String, seconds: Long, wanted: String, artists: String, durationMs: Long, canVerifyAudio: Boolean = false, musicVideo: Boolean = false, allowTitleCredit: Boolean = false): Double? {
        if (durationMs <= 0 || seconds <= 0 || abs(seconds * 1000 - durationMs) > if (canVerifyAudio) 10000 else 3000) return null
        val target = normalized(wanted)
        if (versions.findAll(normalized(name)).any { it.value !in target }) return null
        // Official videos often append a translated title or vocalist after a separator.
        val a = title(if (musicVideo) name.substringBefore(" / ") else name); val b = title(wanted)
        if (a.isBlank() || b.isBlank()) return null
        val nameScore = similarity(a, b)
        if (nameScore < 0.85 && !canVerifyAudio) return null
        val channels = (listOf(uploader) + uploader.split(Regex("\\s+[/|&]\\s+|,\\s*|、")))
            .map(::artist).filter { it.isNotBlank() }.distinct()
        val names = artists.split(',').map(::artist).filter { it.isNotBlank() }
        val artistScore = names.maxOfOrNull { n ->
            channels.maxOfOrNull { channel -> when {
                channel == n -> 1.0
                musicVideo && canVerifyAudio && (n.length >= 2 && artist(name).startsWith(n) ||
                    allowTitleCredit && n.length >= 4 && artist(name).contains(n)) -> 0.9
                minOf(channel.length, n.length) >= 4 && (channel.contains(n) || n.contains(channel)) -> 0.9
                else -> 0.0
            } } ?: 0.0
        } ?: 0.0
        if (artistScore < 0.9) return null
        val timeScore = 1.0 - abs(seconds * 1000 - durationMs) / 10_000.0
        return nameScore * 0.6 + artistScore * 0.25 + timeScore * 0.15
    }

    private fun similarity(a: String, b: String): Double {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
