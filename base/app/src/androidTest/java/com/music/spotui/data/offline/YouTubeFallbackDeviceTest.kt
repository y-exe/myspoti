package com.music.spotui.data.offline

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Live smoke test; run explicitly on a network-connected device. */
class YouTubeFallbackDeviceTest {
    @Test fun japaneseSongIsKeptInItsOriginalAudioFormat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "youtube-fallback-smoke.part")
        try {
            val source = YouTubeOfflineSource.fetch("あれこれドラスティック", "halca", 227000, file)
            assertNotNull("YouTube Music must find the official song", source)
            assertTrue(source!!.bitrateKbps > 0)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val audio = formats.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                assertTrue(kotlin.math.abs(audio.getLong(MediaFormat.KEY_DURATION) / 1000 - 227000) <= 4000)
                val expected = if (source.codec == "Opus") "audio/opus" else "audio/mp4a-latm"
                assertEquals(expected, audio.getString(MediaFormat.KEY_MIME))
            } finally { extractor.release() }
        } finally { file.delete() }
    }
}
