package com.music.spotui.video

import org.junit.Assert.*
import org.junit.Test

class OfflineSongMatcherTest {
    private fun score(name: String, uploader: String = "halca - Topic", seconds: Long = 227,
        wanted: String = "あれこれドラスティック", artist: String = "halca, 鈴木愛奈") =
        OfflineSongMatcher.score(name, uploader, seconds, wanted, artist, 227000)

    @Test fun isrcSearchPrecedesTextAndDuplicateQueriesAreRemoved() {
        assertEquals(listOf("JPSR02212345", "あれこれドラスティック halca"),
            OfflineSongMatcher.queries("あれこれドラスティック", "halca, 鈴木愛奈", "JPSR02212345"))
    }

    @Test fun japaneseTitleWithFeaturedArtistMatchesTheOfficialSong() {
        assertNotNull(score("あれこれドラスティック (feat. 鈴木愛奈)"))
        assertNotNull(score("あれこれドラスティック", uploader = "鈴木愛奈 - Topic"))
    }

    @Test fun fullWidthArtistAndHalfWidthKanaNormalizeWithoutLosingVoicing() {
        assertNotNull(score("あれこれドラスティック", uploader = "ＨＡＬＣＡ - Topic"))
        assertNotNull(score("ﾊﾞﾅﾅ", wanted = "バナナ"))
        assertNull(score("はな", wanted = "ばな"))
    }

    @Test fun sameTitleFromAnotherArtistAndTvSizeAreRejected() {
        assertNull(score("あれこれドラスティック", uploader = "Unrelated Artist"))
        assertNull(score("あれこれドラスティック", seconds = 90))
    }

    @Test fun unwantedVersionsAreRejectedButRequestedVersionsAreAllowed() {
        for (suffix in listOf(" (Live)", " (Cover)", " (Slowed)", " 歌ってみた"))
            assertNull(score("あれこれドラスティック$suffix"))
        assertNotNull(score("あれこれドラスティック (Live)", wanted = "あれこれドラスティック (Live)"))
    }

    @Test fun closerDurationRanksAheadOfOtherwiseIdenticalCandidate() {
        assertTrue(score("あれこれドラスティック", seconds = 227)!! >
            score("あれこれドラスティック", seconds = 229)!!)
    }
}
