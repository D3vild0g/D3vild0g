package com.d3vildog.sportspredictor

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Fetches a URL's text, with a simple on-disk cache (file + .ts sidecar)
 * so repeat app opens don't re-download large nflverse/NHL API responses
 * every time. All network calls here are expected to run off the main
 * thread (see LiveDataRepository's coroutine dispatchers).
 */
object HttpCache {

    private const val TIMEOUT_MS = 20_000

    fun fetchText(url: String, gzip: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "sports-predictor-android")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
            val raw = conn.inputStream
            val stream = if (gzip) GZIPInputStream(raw) else raw
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns cached text if younger than [maxAgeMillis], otherwise fetches
     * fresh via [fetch], caches it, and returns that. Falls back to a stale
     * cache if the network call fails (better than crashing offline).
     */
    fun cachedFetch(context: Context, cacheName: String, maxAgeMillis: Long, fetch: () -> String): String {
        val file = File(context.cacheDir, cacheName)
        val age = System.currentTimeMillis() - file.lastModified()
        if (file.exists() && age in 0..maxAgeMillis) {
            return file.readText()
        }
        return try {
            val text = fetch()
            file.writeText(text)
            text
        } catch (e: Exception) {
            if (file.exists()) file.readText() else throw e
        }
    }
}
