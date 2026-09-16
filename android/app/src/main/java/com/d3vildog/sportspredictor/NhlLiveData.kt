package com.d3vildog.sportspredictor

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NhlGame(
    val gameId: Long,
    val gameState: String,
    val startTimeUtc: LocalDateTime,
    val venue: String,
    val awayTeam: String,
    val homeTeam: String,
) {
    val isFinal: Boolean get() = gameState == "FINAL" || gameState == "OFF"

    fun startsLocal(): LocalDateTime =
        startTimeUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
}

data class NhlSkaterLeader(val name: String, val points: Int, val goals: Int, val assists: Int)
data class NhlGoalieLeader(val name: String, val savePct: Double, val gamesStarted: Int)

/**
 * Live NHL data from the free public NHL API (api-web.nhle.com), same
 * source the Python ingest script uses. No official free source exists for
 * NHL odds or injury reports, so those are covered separately by the
 * web-search backend (see BettingSentimentClient) rather than faked here.
 */
object NhlLiveData {

    private const val SIX_HOURS = 6 * 60 * 60 * 1000L
    private val utcFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun currentSeason(): String {
        val today = LocalDate.now()
        return if (today.monthValue >= 7) "${today.year}${today.year + 1}" else "${today.year - 1}${today.year}"
    }

    fun loadSchedule(context: Context, team: String): List<NhlGame> {
        val season = currentSeason()
        val url = "https://api-web.nhle.com/v1/club-schedule-season/$team/$season"
        val text = HttpCache.cachedFetch(context, "nhl_schedule_$team.json", SIX_HOURS) { HttpCache.fetchText(url) }
        val obj = JSONObject(text)
        val arr = obj.optJSONArray("games") ?: return emptyList()
        val out = mutableListOf<NhlGame>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val startRaw = g.optString("startTimeUTC", "").removeSuffix("Z")
            val start = runCatching { LocalDateTime.parse(startRaw, utcFmt) }.getOrNull() ?: continue
            out.add(
                NhlGame(
                    gameId = g.optLong("id"),
                    gameState = g.optString("gameState"),
                    startTimeUtc = start,
                    venue = g.optJSONObject("venue")?.optString("default") ?: "",
                    awayTeam = g.optJSONObject("awayTeam")?.optString("abbrev") ?: "",
                    homeTeam = g.optJSONObject("homeTeam")?.optString("abbrev") ?: "",
                )
            )
        }
        return out
    }

    fun nextGame(schedule: List<NhlGame>): NhlGame? =
        schedule.filter { !it.isFinal }.minByOrNull { it.startTimeUtc }

    fun statLeaders(context: Context, team: String): Pair<NhlSkaterLeader?, NhlGoalieLeader?> {
        val season = currentSeason()
        val url = "https://api-web.nhle.com/v1/club-stats/$team/$season/2"
        val text = try {
            HttpCache.cachedFetch(context, "nhl_stats_$team.json", SIX_HOURS) { HttpCache.fetchText(url) }
        } catch (e: Exception) {
            return null to null
        }
        val obj = JSONObject(text)

        val skaters = obj.optJSONArray("skaters")
        var topSkater: NhlSkaterLeader? = null
        if (skaters != null) {
            for (i in 0 until skaters.length()) {
                val s = skaters.getJSONObject(i)
                val points = s.optInt("points")
                if (topSkater == null || points > topSkater!!.points) {
                    val first = s.optJSONObject("firstName")?.optString("default") ?: ""
                    val last = s.optJSONObject("lastName")?.optString("default") ?: ""
                    topSkater = NhlSkaterLeader("$first $last".trim(), points, s.optInt("goals"), s.optInt("assists"))
                }
            }
        }

        val goalies = obj.optJSONArray("goalies")
        var topGoalie: NhlGoalieLeader? = null
        if (goalies != null) {
            for (i in 0 until goalies.length()) {
                val g = goalies.getJSONObject(i)
                val starts = g.optInt("gamesStarted")
                if (starts < 5) continue
                val savePct = g.optDouble("savePercentage", 0.0)
                if (topGoalie == null || savePct > topGoalie!!.savePct) {
                    val first = g.optJSONObject("firstName")?.optString("default") ?: ""
                    val last = g.optJSONObject("lastName")?.optString("default") ?: ""
                    topGoalie = NhlGoalieLeader("$first $last".trim(), savePct, starts)
                }
            }
        }

        return topSkater to topGoalie
    }
}
