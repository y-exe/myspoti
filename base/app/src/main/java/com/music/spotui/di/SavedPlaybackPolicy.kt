package com.music.spotui.di

/** Playback may use a saved song without declaring the whole library offline. */
internal object SavedPlaybackPolicy {
    const val MAX_WAIT_MS = 3_000L
    const val SLOW_KBPS = 1_000

    data class Link(
        val internet: Boolean? = null,
        val validated: Boolean? = null,
        val downstreamKbps: Int = 0,
        val browserDownlinkMbps: Double? = null,
    )

    fun preferSaved(link: Link): Boolean = link.internet == false || link.validated == false ||
        link.downstreamKbps in 1..SLOW_KBPS ||
        link.browserDownlinkMbps?.let { it.isFinite() && it >= 0.0 && it <= SLOW_KBPS / 1000.0 } == true

    fun remainingMs(link: Link, elapsedMs: Long): Long =
        if (preferSaved(link)) 0L else (MAX_WAIT_MS - elapsedMs.coerceAtLeast(0)).coerceAtLeast(0)
}
