package com.music.spotui.video

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Independently implemented, inspired by spotDL's ISRC-first, multi-signal matching. */
internal object OfflineSongMatcher {
    private val versions = Regex("(?i)\\b(live|cover|remix|remaster(?:ed)?|acoustic|instrumental|karaoke|slowed|sped[ -]?up|nightcore|reverb|8d)\\b|ライブ|カバー|歌ってみた|弾いてみた|カラオケ|オルゴール")
    private fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun title(s: String) = normalized(s)
        .replace(Regex("(?i)\\s*(?:\\(|\\[)?(?:feat|ft)\\.?\\s+.*"), "")
        .replace(Regex("(?i)\\b(official|music|video|audio|lyrics?)\\b"), "")
        .filter { it.isLetterOrDigit() }
    private fun artist(s: String) = normalized(s).replace(Regex("(?i)\\s*-\\s*topic$|vevo$"), "")
        .filter { it.isLetterOrDigit() }

    fun queries(title: String, artists: String, isrc: String): List<String> {
        val queries = mutableListOf<String>()
        if (isrc.matches(Regex("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}"))) queries += isrc
        queries += "$title ${artists.substringBefore(',').trim()}".trim()
        val bare = title.replace(Regex("(?i)\\s*(?:\\(|\\[)?(?:feat|ft)\\.?\\s+.*"), "").trim()
        queries += "$bare ${artists.substringBefore(',').trim()}".trim()
        return queries.filter { it.isNotBlank() }.distinct()
    }

    fun score(name: String, uploader: String, seconds: Long, wanted: String, artists: String, durationMs: Long, allowTitleMismatch: Boolean = false): Double? {
        if (durationMs <= 0 || seconds <= 0 || abs(seconds * 1000 - durationMs) > 3000) return null
        val target = normalized(wanted)
        if (versions.findAll(normalized(name)).any { it.value !in target }) return null
        val a = title(name); val b = title(wanted)
        if (a.isBlank() || b.isBlank()) return null
        val nameScore = similarity(a, b)
        if (nameScore < 0.85 && !allowTitleMismatch) return null
        val channel = artist(uploader)
        val names = artists.split(',').map(::artist).filter { it.isNotBlank() }
        val artistScore = names.maxOfOrNull { n ->
            when {
                channel == n -> 1.0
                minOf(channel.length, n.length) >= 4 && (channel.contains(n) || n.contains(channel)) -> 0.9
                else -> 0.0
            }
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
