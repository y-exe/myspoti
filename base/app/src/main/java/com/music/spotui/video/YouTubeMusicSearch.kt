package com.music.spotui.video

import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Read structured song/artist/duration fields, as spotDL's ytmusicapi provider does. */
internal object YouTubeMusicSearch {
    private val cache = SingleFlightCache<String, List<YouTubeSource.Hit>>(64, 30 * 60_000L)

    fun songs(query: String, title: String): List<YouTubeSource.Hit> {
        // YouTube translates some Japanese titles to romaji under English metadata.
        // Request the script of the song; do not change NewPipe's shared localization.
        val japanese = title.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
        val language = if (japanese) "ja" else Locale.getDefault().language
        return cache.get("$language\u0000$query") {
            val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20251013.03.00")
                .put("hl", language).put("gl", Locale.getDefault().country.ifBlank { "JP" })
            val body = JSONObject().put("context", JSONObject().put("client", client))
                .put("query", query).put("params", "EgWKAQIIAWoKEAkQChAFEAMQBA%3D%3D")
            val request = okhttp3.Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("Origin", "https://music.youtube.com")
                .post(body.toString().toRequestBody()).build()
            val json = InterruptibleHttp.read(YouTubeSource.http.newCall(request)) {
                if (!it.isSuccessful) throw IOException("YouTube Music search HTTP ${it.code}")
                JSONObject(it.body?.string().orEmpty())
            }
            if (json.has("error")) throw IOException("YouTube Music search refused")
            parse(json)
        }
    }

    internal fun parse(json: JSONObject): List<YouTubeSource.Hit> {
        val found = linkedMapOf<String, YouTubeSource.Hit>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                is JSONObject -> {
                    val row = value.optJSONObject("musicResponsiveListItemRenderer")
                    if (row != null) {
                        val columns = row.optJSONArray("flexColumns") ?: JSONArray()
                        fun runs(i: Int) = columns.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")?.optJSONArray("runs") ?: JSONArray()
                        val titleRuns = runs(0)
                        val first = titleRuns.optJSONObject(0)
                        val watch = first?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                        val type = watch?.optJSONObject("watchEndpointMusicSupportedConfigs")
                            ?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")
                        val id = row.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                        val name = (0 until titleRuns.length()).joinToString("") { titleRuns.optJSONObject(it)?.optString("text").orEmpty() }
                        val metadata = runs(1)
                        val artists = mutableListOf<String>()
                        var seconds = 0L
                        for (i in 0 until metadata.length()) {
                            val run = metadata.optJSONObject(i) ?: continue
                            val text = run.optString("text")
                            val pageType = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
                            if (pageType == "MUSIC_PAGE_TYPE_ARTIST" && text.isNotBlank()) artists += text
                            if (text.matches(Regex("\\d{1,3}:\\d{2}(?::\\d{2})?")))
                                seconds = text.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
                        }
                        if (type == "MUSIC_VIDEO_TYPE_ATV" && id.matches(Regex("[A-Za-z0-9_-]{11}")) &&
                            name.isNotBlank() && artists.isNotEmpty() && seconds > 0)
                            found[id] = YouTubeSource.Hit("https://www.youtube.com/watch?v=$id", name, artists.joinToString(", "), seconds)
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
            }
        }
        visit(json)
        return found.values.toList()
    }
}
