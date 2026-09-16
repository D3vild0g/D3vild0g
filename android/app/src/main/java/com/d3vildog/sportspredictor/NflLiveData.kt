package com.d3vildog.sportspredictor

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NflGame(
    val gameId: String,
    val season: Int,
    val week: Int,
    val gameday: LocalDate,
    val gametime: LocalTime?,
    val weekday: String,
    val awayTeam: String,
    val homeTeam: String,
    val awayScore: Int?,
    val homeScore: Int?,
    val awayMoneyline: Int?,
    val homeMoneyline: Int?,
    val spreadLine: Double?,
    val totalLine: Double?,
    val awayQbName: String,
    val homeQbName: String,
) {
    val isPlayed: Boolean get() = awayScore != null && homeScore != null
    val kickoffLocal: LocalDateTime? get() = gametime?.let { LocalDateTime.of(gameday, it) }
}

data class StatLeader(val name: String, val team: String, val statLabel: String, val value: Double)

data class NflStatLeaders(
    val season: Int,
    val passing: StatLeader?,
    val rushing: StatLeader?,
    val receiving: StatLeader?,
)

data class NflInjuryRow(val name: String, val position: String, val status: String, val primaryInjury: String)

/**
 * Live NFL data pulled directly from the same free, no-key nflverse sources
 * the Python ingest scripts use (see README "Android app" section):
 *  - schedule + real sportsbook lines: http://www.habitatring.com/games.csv
 *  - season player stats: nflverse-data 'player_stats' release (gzip CSV)
 *  - injury reports: nflverse-data 'injuries' release (per-season CSV)
 */
object NflLiveData {

    private const val SCHEDULE_URL = "http://www.habitatring.com/games.csv"
    private const val PLAYER_STATS_URL =
        "https://github.com/nflverse/nflverse-data/releases/download/player_stats/player_stats.csv.gz"
    private const val INJURIES_URL_FMT =
        "https://github.com/nflverse/nflverse-data/releases/download/injuries/injuries_%d.csv"

    private const val SIX_HOURS = 6 * 60 * 60 * 1000L
    private const val ONE_DAY = 24 * 60 * 60 * 1000L

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFmt = DateTimeFormatter.ofPattern("H:mm")

    fun currentSeasonGuess(): Int {
        val today = LocalDate.now()
        return if (today.monthValue >= 3) today.year else today.year - 1
    }

    fun loadSchedule(context: Context): List<NflGame> {
        val text = HttpCache.cachedFetch(context, "nfl_games.csv", SIX_HOURS) { HttpCache.fetchText(SCHEDULE_URL) }
        val table = CsvUtil.parse(text.lineSequence())
        return table.rows.mapNotNull { row ->
            fun c(name: String) = table.col(row, name)
            val gameday = runCatching { LocalDate.parse(c("gameday"), dateFmt) }.getOrNull() ?: return@mapNotNull null
            val gametime = runCatching { LocalTime.parse(c("gametime"), timeFmt) }.getOrNull()
            NflGame(
                gameId = c("game_id"),
                season = c("season").toIntOrNull() ?: 0,
                week = c("week").toIntOrNull() ?: 0,
                gameday = gameday,
                gametime = gametime,
                weekday = c("weekday"),
                awayTeam = c("away_team"),
                homeTeam = c("home_team"),
                awayScore = c("away_score").toDoubleOrNull()?.toInt(),
                homeScore = c("home_score").toDoubleOrNull()?.toInt(),
                awayMoneyline = c("away_moneyline").toDoubleOrNull()?.toInt(),
                homeMoneyline = c("home_moneyline").toDoubleOrNull()?.toInt(),
                spreadLine = c("spread_line").toDoubleOrNull(),
                totalLine = c("total_line").toDoubleOrNull(),
                awayQbName = c("away_qb_name"),
                homeQbName = c("home_qb_name"),
            )
        }
    }

    fun upcomingGames(schedule: List<NflGame>, limit: Int = 50): List<NflGame> =
        schedule.filter { !it.isPlayed }
            .sortedWith(compareBy({ it.gameday }, { it.gametime ?: LocalTime.MIDNIGHT }))
            .take(limit)

    fun nextGame(schedule: List<NflGame>, team: String): NflGame? =
        schedule.filter { !it.isPlayed && (it.homeTeam == team || it.awayTeam == team) }
            .minByOrNull { (it.gametime?.let { t -> it.gameday.atTime(t) } ?: it.gameday.atStartOfDay()) }

    /** Tries the current season first, then the prior one, for whichever actually has rows for this team. */
    fun statLeaders(context: Context, team: String): NflStatLeaders? {
        val text = HttpCache.cachedFetch(context, "nfl_player_stats.csv", ONE_DAY) {
            HttpCache.fetchText(PLAYER_STATS_URL, gzip = true)
        }
        val table = CsvUtil.parse(text.lineSequence())
        val guess = currentSeasonGuess()

        for (season in intArrayOf(guess, guess - 1)) {
            val totals = HashMap<String, DoubleArray>() // name -> [passYds, rushYds, recYds]
            var found = false
            for (row in table.rows) {
                if (table.col(row, "recent_team") != team) continue
                if (table.col(row, "season").toIntOrNull() != season) continue
                found = true
                val name = table.col(row, "player_display_name")
                if (name.isBlank()) continue
                val arr = totals.getOrPut(name) { DoubleArray(3) }
                arr[0] += table.col(row, "passing_yards").toDoubleOrNull() ?: 0.0
                arr[1] += table.col(row, "rushing_yards").toDoubleOrNull() ?: 0.0
                arr[2] += table.col(row, "receiving_yards").toDoubleOrNull() ?: 0.0
            }
            if (!found) continue
            val passing = totals.entries.maxByOrNull { it.value[0] }
                ?.takeIf { it.value[0] > 0 }?.let { StatLeader(it.key, team, "passing yds", it.value[0]) }
            val rushing = totals.entries.maxByOrNull { it.value[1] }
                ?.takeIf { it.value[1] > 0 }?.let { StatLeader(it.key, team, "rushing yds", it.value[1]) }
            val receiving = totals.entries.maxByOrNull { it.value[2] }
                ?.takeIf { it.value[2] > 0 }?.let { StatLeader(it.key, team, "receiving yds", it.value[2]) }
            return NflStatLeaders(season, passing, rushing, receiving)
        }
        return null
    }

    fun injuryReport(context: Context, team: String): List<NflInjuryRow> {
        val guess = currentSeasonGuess()
        for (season in intArrayOf(guess, guess - 1)) {
            val url = INJURIES_URL_FMT.format(season)
            val text = try {
                HttpCache.cachedFetch(context, "nfl_injuries_$season.csv", SIX_HOURS) { HttpCache.fetchText(url) }
            } catch (e: Exception) {
                continue
            }
            val table = CsvUtil.parse(text.lineSequence())
            val teamRows = table.rows.filter { table.col(it, "team") == team }
            if (teamRows.isEmpty()) continue
            val maxWeek = teamRows.mapNotNull { table.col(it, "week").toIntOrNull() }.maxOrNull() ?: continue
            return teamRows.filter { table.col(it, "week").toIntOrNull() == maxWeek }
                .map {
                    NflInjuryRow(
                        name = table.col(it, "full_name"),
                        position = table.col(it, "position"),
                        status = table.col(it, "report_status").ifBlank { "—" },
                        primaryInjury = table.col(it, "report_primary_injury"),
                    )
                }
                .filter { it.status != "—" }
        }
        return emptyList()
    }
}
