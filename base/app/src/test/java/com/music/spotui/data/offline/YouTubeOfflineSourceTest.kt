package com.music.spotui.data.offline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeOfflineSourceTest {
    @Test fun originalFormatsAreRankedByAdvertisedRateWithoutUpsampling() {
        val candidates = listOf(
            YouTubeOfflineSource.Candidate("m4a", "AAC", 128),
            YouTubeOfflineSource.Candidate("webm", "Opus", 160),
            YouTubeOfflineSource.Candidate("mp3", "MP3", 320),
            YouTubeOfflineSource.Candidate("m4a", "AAC", 0),
        )
        val selected = YouTubeOfflineSource.ranked(candidates)
        assertEquals(listOf(160, 128), selected.map { it.bitrateKbps })
        assertEquals("webm", selected.first().extension)
    }

    @Test fun premiumAacBeatsLowerAdvertisedOpusRate() {
        val selected = YouTubeOfflineSource.ranked(listOf(
            YouTubeOfflineSource.Candidate("webm", "Opus", 160),
            YouTubeOfflineSource.Candidate("m4a", "AAC", 256),
        ))
        assertEquals("AAC", selected.first().codec)
        assertEquals(256, selected.first().bitrateKbps)
    }

    @Test fun provenanceSurvivesRestartAndDoesNotClaimLosslessOrAudioVerification() {
        val track = OfflineTrack("id", "Song", "Artist", "Album", "album", 200000,
            "", "id.webm", "", "YouTube Music", false, 1234567, 1,
            codec = "Opus", bitrateKbps = 160, sourceUrl = "https://www.youtube.com/watch?v=example")
        val restored = OfflineTrack.fromJson(JSONObject(track.toJson().toString()))
        assertEquals(track, restored)
        assertTrue(restored.isYouTubeFallback)
        assertFalse(restored.verified)
        assertEquals("YouTube Music · Opus · ~160 kbps", restored.qualityLabel)
    }

    @Test fun oldIndexesRemainReadable() {
        val old = JSONObject().put("id", "old").put("file", "old.flac")
        val restored = OfflineTrack.fromJson(old)
        assertEquals("", restored.codec)
        assertEquals(0, restored.bitrateKbps)
        assertFalse(restored.isYouTubeFallback)
        assertFalse(restored.isEpisode)
    }
}
