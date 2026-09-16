package com.d3vildog.sportspredictor

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Forward-pass-only port of src/nhl/model.py's NHLModel.predict(): Elo win
 * probability blended 50/50 with a Poisson-goals model run through the
 * Skellam distribution (goal-difference distribution), same as the
 * server-side model. Poisson/Skellam PMFs are computed directly (log-space
 * factorials) since Android has no scipy.
 */
class NhlModel(json: JSONObject) {
    private val homeAdvantage = json.getDouble("homeAdvantage")
    private val eloBlendWeight = json.getDouble("eloBlendWeight")
    private val leagueAvgGoals = json.getDouble("leagueAvgGoals")
    private val eloRatings = json.getJSONObject("eloRatings").toDoubleMap()
    private val teamGoalHistory = parseGoalHistory(json.getJSONObject("teamGoalHistory"))
    private val poisson = json.getJSONObject("poissonModel")
    private val pIntercept = poisson.getDouble("intercept")
    private val pTeamGf = poisson.getDouble("teamGf")
    private val pOppGa = poisson.getDouble("oppGa")
    private val pIsHome = poisson.getDouble("isHome")

    val teams: List<String> get() = eloRatings.keys.sorted()

    fun eloOf(team: String) = eloRatings[team] ?: 1500.0

    private fun parseGoalHistory(obj: JSONObject): Map<String, List<Pair<Double, Double>>> =
        obj.keys().asSequence().associateWith { key ->
            val arr = obj.getJSONArray(key)
            (0 until arr.length()).map {
                val pair = arr.getJSONArray(it)
                pair.getDouble(0) to pair.getDouble(1)
            }
        }

    private fun expectedGoals(team: String, opponent: String, isHome: Int): Double {
        val hist = teamGoalHistory[team]
        val oppHist = teamGoalHistory[opponent]
        val teamGf = if (!hist.isNullOrEmpty()) hist.map { it.first }.average() else leagueAvgGoals
        val oppGa = if (!oppHist.isNullOrEmpty()) oppHist.map { it.second }.average() else leagueAvgGoals
        val linear = pIntercept + pTeamGf * teamGf + pOppGa * oppGa + pIsHome * isHome
        return exp(linear)
    }

    private fun eloWinProbability(home: String, away: String): Double {
        val ra = eloOf(home) + homeAdvantage
        val rb = eloOf(away)
        return 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / 400.0))
    }

    private fun logFactorial(n: Int): DoubleArray {
        val out = DoubleArray(n + 1)
        for (k in 1..n) out[k] = out[k - 1] + ln(k.toDouble())
        return out
    }

    private fun poissonPmf(k: Int, mu: Double, logFact: DoubleArray): Double {
        if (mu <= 0.0) return if (k == 0) 1.0 else 0.0
        val logP = -mu + k * ln(mu) - logFact[k]
        return exp(logP)
    }

    /** P(home wins) + half tie mass, via direct double-Poisson summation instead of scipy.skellam. */
    private fun skellamHomeWinProb(homeXg: Double, awayXg: Double): Double {
        val maxGoals = max(20, (max(homeXg, awayXg) * 6).toInt() + 10)
        val logFact = logFactorial(maxGoals)
        var homeWin = 0.0
        var tie = 0.0
        for (h in 0..maxGoals) {
            val ph = poissonPmf(h, homeXg, logFact)
            if (ph <= 0.0) continue
            for (a in 0..maxGoals) {
                val pa = poissonPmf(a, awayXg, logFact)
                if (pa <= 0.0) continue
                val joint = ph * pa
                if (h > a) homeWin += joint else if (h == a) tie += joint
            }
        }
        return homeWin + 0.5 * tie
    }

    fun predict(home: String, away: String): Result {
        val homeXg = expectedGoals(home, away, isHome = 1)
        val awayXg = expectedGoals(away, home, isHome = 0)

        val skellamProb = skellamHomeWinProb(homeXg, awayXg)
        val eloProb = eloWinProbability(home, away)
        val blended = eloBlendWeight * eloProb + (1 - eloBlendWeight) * skellamProb

        return Result(
            homeTeam = home,
            awayTeam = away,
            homeWinProb = blended,
            awayWinProb = 1.0 - blended,
            predictedHomeGoals = homeXg,
            predictedAwayGoals = awayXg,
            homeElo = eloOf(home),
            awayElo = eloOf(away),
        )
    }

    data class Result(
        val homeTeam: String,
        val awayTeam: String,
        val homeWinProb: Double,
        val awayWinProb: Double,
        val predictedHomeGoals: Double,
        val predictedAwayGoals: Double,
        val homeElo: Double,
        val awayElo: Double,
    )
}
