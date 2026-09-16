package com.d3vildog.sportspredictor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.d3vildog.sportspredictor.databinding.ActivityGameDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class GameDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameDetailBinding
    private val fmt = DateTimeFormatter.ofPattern("EEEE MMM d, yyyy 'at' h:mm a")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val league = intent.getStringExtra("league") ?: "NFL"
        val home = intent.getStringExtra("home") ?: return
        val away = intent.getStringExtra("away") ?: return
        val kickoffStr = intent.getStringExtra("kickoff")
        val kickoff = kickoffStr?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        binding.titleText.text = "$away @ $home"
        binding.kickoffText.text = kickoff?.format(fmt) ?: "Kickoff time TBD"

        binding.predictionText.text = "Computing prediction…"
        lifecycleScope.launch {
            val predictionText = withContext(Dispatchers.Default) { predict(league, home, away) }
            binding.predictionText.text = predictionText
        }

        if (league == "NFL") {
            loadNflExtras(home, away)
        } else {
            loadNhlExtras(home, away)
        }
    }

    private fun predict(league: String, home: String, away: String): String {
        val json = JSONObject(assets.open(if (league == "NFL") "nfl_model.json" else "nhl_model.json")
            .bufferedReader().use { it.readText() })
        return if (league == "NFL") {
            val model = NflModel(json)
            val r = model.predict(home, away, 7, 7)
            "League: NFL\n\n${r.homeTeam} win prob: ${pct(r.homeWinProb)}\n${r.awayTeam} win prob: ${pct(r.awayWinProb)}\n\n" +
                "Predicted margin (home - away): ${signed(r.predictedMarginHome)}\n\n" +
                "Elo: ${r.homeTeam} ${round1(r.homeElo)} vs ${r.awayTeam} ${round1(r.awayElo)}"
        } else {
            val model = NhlModel(json)
            val r = model.predict(home, away)
            "League: NHL\n\n${r.homeTeam} win prob: ${pct(r.homeWinProb)}\n${r.awayTeam} win prob: ${pct(r.awayWinProb)}\n\n" +
                "Predicted score: ${r.homeTeam} ${round2(r.predictedHomeGoals)} - ${round2(r.predictedAwayGoals)} ${r.awayTeam}\n\n" +
                "Elo: ${r.homeTeam} ${round1(r.homeElo)} vs ${r.awayTeam} ${round1(r.awayElo)}"
        }
    }

    private fun loadNflExtras(home: String, away: String) {
        lifecycleScope.launch {
            try {
                val schedule = withContext(Dispatchers.IO) { NflLiveData.loadSchedule(this@GameDetailActivity) }
                val game = schedule.firstOrNull {
                    !it.isPlayed && it.homeTeam == home && it.awayTeam == away
                }
                binding.bettingText.text = if (game == null) {
                    "No line found for this matchup."
                } else buildString {
                    appendLine("Moneyline: ${away} ${fmtOdds(game.awayMoneyline)}  ·  ${home} ${fmtOdds(game.homeMoneyline)}")
                    appendLine("Spread: ${home} ${fmtSpread(game.spreadLine)}")
                    append("Total (O/U): ${game.totalLine ?: "—"}")
                    if (game.homeQbName.isNotBlank() || game.awayQbName.isNotBlank()) {
                        appendLine()
                        appendLine()
                        append("Starting QBs: ${away} ${game.awayQbName.ifBlank { "TBD" }}  ·  ${home} ${game.homeQbName.ifBlank { "TBD" }}")
                    }
                }
            } catch (e: Exception) {
                binding.bettingText.text = "Couldn't load betting lines: ${e.message}"
            }
        }

        lifecycleScope.launch {
            try {
                val (homeStats, awayStats) = withContext(Dispatchers.IO) {
                    NflLiveData.statLeaders(this@GameDetailActivity, home) to NflLiveData.statLeaders(this@GameDetailActivity, away)
                }
                binding.statsText.text = buildString {
                    append(formatNflStats(home, homeStats))
                    appendLine()
                    appendLine()
                    append(formatNflStats(away, awayStats))
                }
            } catch (e: Exception) {
                binding.statsText.text = "Couldn't load player stats: ${e.message}"
            }
        }

        lifecycleScope.launch {
            try {
                val (homeInj, awayInj) = withContext(Dispatchers.IO) {
                    NflLiveData.injuryReport(this@GameDetailActivity, home) to NflLiveData.injuryReport(this@GameDetailActivity, away)
                }
                binding.injuryText.text = buildString {
                    append(formatInjuries(home, homeInj))
                    appendLine()
                    appendLine()
                    append(formatInjuries(away, awayInj))
                }
            } catch (e: Exception) {
                binding.injuryText.text = "Couldn't load injury report: ${e.message}"
            }
        }
    }

    private fun loadNhlExtras(home: String, away: String) {
        binding.bettingText.text = "Checking web for NHL betting sentiment…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BettingSentimentClient.fetch(this@GameDetailActivity, "NHL", home, away)
            }
            binding.bettingText.text = if (result.available) {
                buildString {
                    append(result.summary ?: "No summary returned.")
                    if (result.sources.isNotEmpty()) {
                        appendLine()
                        appendLine()
                        append("Sources:\n" + result.sources.joinToString("\n") { "• ${it.first}" })
                    }
                }
            } else {
                "Not available: ${result.reason}"
            }
        }

        lifecycleScope.launch {
            try {
                val (homeLeaders, awayLeaders) = withContext(Dispatchers.IO) {
                    NhlLiveData.statLeaders(this@GameDetailActivity, home) to NhlLiveData.statLeaders(this@GameDetailActivity, away)
                }
                binding.statsText.text = buildString {
                    append(formatNhlStats(home, homeLeaders))
                    appendLine()
                    appendLine()
                    append(formatNhlStats(away, awayLeaders))
                }
            } catch (e: Exception) {
                binding.statsText.text = "Couldn't load player stats: ${e.message}"
            }
        }

        binding.injuryText.text = "Not available for NHL (no free structured injury-report API). " +
            "Betting-sentiment search above may surface injury/goalie chatter."
    }

    private fun formatNflStats(team: String, stats: NflStatLeaders?): String {
        if (stats == null) return "$team: no stats available yet this season."
        val lines = mutableListOf("$team (${stats.season}):")
        stats.passing?.let { lines.add("  Passing: ${it.name} — ${it.value.toInt()} yds") }
        stats.rushing?.let { lines.add("  Rushing: ${it.name} — ${it.value.toInt()} yds") }
        stats.receiving?.let { lines.add("  Receiving: ${it.name} — ${it.value.toInt()} yds") }
        return lines.joinToString("\n")
    }

    private fun formatNhlStats(team: String, leaders: Pair<NhlSkaterLeader?, NhlGoalieLeader?>): String {
        val (skater, goalie) = leaders
        val lines = mutableListOf("$team:")
        skater?.let { lines.add("  Top scorer: ${it.name} — ${it.points} pts (${it.goals}G ${it.assists}A)") }
        goalie?.let { lines.add("  Goalie: ${it.name} — ${round3(it.savePct)} SV% (${it.gamesStarted} GS)") }
        if (skater == null && goalie == null) lines.add("  No stats available yet this season.")
        return lines.joinToString("\n")
    }

    private fun formatInjuries(team: String, rows: List<NflInjuryRow>): String {
        if (rows.isEmpty()) return "$team: no injury report / nothing listed."
        val lines = mutableListOf("$team:")
        rows.forEach { lines.add("  ${it.name} (${it.position}) — ${it.status}${if (it.primaryInjury.isNotBlank()) " (${it.primaryInjury})" else ""}") }
        return lines.joinToString("\n")
    }

    private fun fmtOdds(v: Int?) = if (v == null) "—" else if (v > 0) "+$v" else "$v"
    private fun fmtSpread(v: Double?) = if (v == null) "—" else if (v > 0) "+$v" else "$v"
    private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f", v)
    private fun round1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun round2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun round3(v: Double) = String.format(Locale.US, "%.3f", v)
}
