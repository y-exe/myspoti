package com.music.spotui.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView

/** Connection evidence for saved playback; no speed-test requests or changes to library filtering. */
internal class SavedPlaybackConnection(context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var network: Network? = cm?.activeNetwork
    private var link = network?.let { read(cm?.getNetworkCapabilities(it)) }
        ?: SavedPlaybackPolicy.Link(internet = if (cm != null) false else null)
    private var blocked = false
    private var generation = 0L
    private var sample = 0L
    private var browserAt = 0L

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    if (network != n) {
                        network = n
                        blocked = false
                        generation++
                        browserAt = 0
                        link = SavedPlaybackPolicy.Link()
                    }
                }

                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (network != n) return
                    link = read(caps).copy(browserDownlinkMbps = link.browserDownlinkMbps)
                    changed()
                }

                override fun onLost(n: Network) {
                    if (network != n) return
                    network = null
                    generation++
                    browserAt = 0
                    link = SavedPlaybackPolicy.Link(internet = false)
                    changed()
                }

                override fun onBlockedStatusChanged(n: Network, isBlocked: Boolean) {
                    if (network != n) return
                    blocked = isBlocked
                    changed()
                }
            }, main)
        }
    }

    fun snapshot(): SavedPlaybackPolicy.Link {
        val current = if (SystemClock.elapsedRealtime() - browserAt <= 10_000L) link
            else link.copy(browserDownlinkMbps = null)
        return if (blocked) current.copy(internet = false) else current
    }

    fun preferSaved(): Boolean = com.music.spotui.data.DataSaver.active || SavedPlaybackPolicy.preferSaved(snapshot())

    /** Chromium estimates end-to-end throughput from recent traffic; this script sends nothing. */
    fun sampleBrowser(view: WebView) {
        if (link.internet == false) return
        val epoch = generation
        val request = ++sample
        runCatching {
            view.evaluateJavascript("(function(){var c=navigator.connection;return c&&typeof c.downlink==='number'?c.downlink:null;})()") { value ->
                if (generation != epoch || sample != request) return@evaluateJavascript
                val downlink = value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                link = link.copy(browserDownlinkMbps = downlink)
                browserAt = SystemClock.elapsedRealtime()
                changed()
            }
        }
    }

    private fun read(caps: NetworkCapabilities?): SavedPlaybackPolicy.Link {
        caps ?: return SavedPlaybackPolicy.Link()
        return SavedPlaybackPolicy.Link(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            downstreamKbps = caps.linkDownstreamBandwidthKbps,
        )
    }
}
