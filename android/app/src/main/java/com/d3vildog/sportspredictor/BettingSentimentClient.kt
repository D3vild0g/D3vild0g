package com.d3vildog.sportspredictor

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BettingSentiment(
    val available: Boolean,
    val summary: String?,
    val sources: List<Pair<String, String>>, // title to url
    val reason: String?,
)

/**
 * Talks to the small backend in server/ (see server/README.md) that does a
 * live web search for NHL betting sentiment and injury/starter chatter --
 * there's no free structured odds or injury API for NHL, unlike NFL. The
 * backend URL is user-configurable (Settings screen) since this sandbox
 * can't host it; nothing is called until the user points the app at their
 * own deployment.
 */
object BettingSentimentClient {

    private const val PREFS = "sports_predictor_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    // Generous: free-tier hosts (e.g. Render) can take 30-60s to wake from a cold start.
    private const val TIMEOUT_MS = 45_000

    fun getBackendUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKEND_URL, "") ?: ""

    fun setBackendUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BACKEND_URL, url.trim()).apply()
    }

    /** Runs on the calling thread -- caller is expected to invoke this from a background dispatcher. */
    fun fetch(context: Context, league: String, home: String, away: String): BettingSentiment {
        val base = getBackendUrl(context)
        if (base.isBlank()) {
            return BettingSentiment(false, null, emptyList(), "No backend URL configured. Set one in Settings.")
        }
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val url = "${base.trimEnd('/')}/betting-sentiment?league=${enc(league)}&home=${enc(home)}&away=${enc(away)}"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299 || body.isBlank()) {
                return BettingSentiment(false, null, emptyList(), "Backend returned HTTP $code")
            }
            val obj = JSONObject(body)
            val available = obj.optBoolean("available", false)
            if (!available) {
                return BettingSentiment(false, null, emptyList(), obj.optString("reason", "Not available"))
            }
            val sources = mutableListOf<Pair<String, String>>()
            obj.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    sources.add(s.optString("title") to s.optString("url"))
                }
            }
            return BettingSentiment(true, obj.optString("summary"), sources, null)
        } catch (e: Exception) {
            return BettingSentiment(false, null, emptyList(), "Couldn't reach backend: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
