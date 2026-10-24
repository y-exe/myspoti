package com.music.spotui.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeMusicSearchTest {
    private fun fixture() = JSONObject(javaClass.getResource("/youtube/japanese-song.json")!!.readText())

    @Test fun japaneseSongRetainsStructuredArtistAndDuration() {
        val hit = YouTubeMusicSearch.parse(fixture()).single()
        assertEquals("あれこれドラスティック（feat. 鈴木愛奈）", hit.name)
        assertEquals("halca", hit.uploader)
        assertEquals(226L, hit.durationSec)
        assertNotNull(OfflineSongMatcher.score(hit.name, hit.uploader, hit.durationSec,
            "あれこれドラスティック", "halca, 鈴木愛奈", 227000))
    }

    @Test fun musicVideosAreNotMistakenForSongs() {
        val json = fixture().toString().replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")
        assertTrue(YouTubeMusicSearch.parse(JSONObject(json)).isEmpty())
    }

    @Test fun missingDurationDoesNotProduceAnUnboundedMatch() {
        val json = fixture().toString().replace("3:46", "")
        assertTrue(YouTubeMusicSearch.parse(JSONObject(json)).isEmpty())
    }
}
