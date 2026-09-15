package com.d3vildog.sportspredictor

import org.json.JSONObject
import kotlin.math.exp

/**
 * Forward-pass-only port of src/nfl/model.py's NFLModel.predict(). All
 * training (Elo history, logistic/linear regression fit) happens offline in
 * Python via scripts/export_android_model.py; this just re-applies the
 * fitted coefficients to bundled state, exactly like NFLModel.predict does.
 */
class NflModel(json: JSONObject) {
    private val homeAdvantage = json.getDouble("homeAdvantage")
    private val eloRatings = json.getJSONObject("eloRatings").toDoubleMap()
    private val teamForm = json.getJSONObject("teamForm").toDoubleListMap()
    private val winCoef = json.getJSONObject("winClf").getJSONArray("coef").toDoubleArray()
    private val winIntercept = json.getJSONObject("winClf").getDouble("intercept")
    private val marginCoef = json.getJSONObject("marginReg").getJSONArray("coef").toDoubleArray()
    private val marginIntercept = json.getJSONObject("marginReg").getDouble("intercept")

    val teams: List<String> get() = eloRatings.keys.sorted()

    fun eloOf(team: String) = eloRatings[team] ?: 1500.0

    fun predict(home: String, away: String, homeRest: Int, awayRest: Int): Result {
        val eloDiff = eloOf(home) + homeAdvantage - eloOf(away)
        val restDiff = (homeRest - awayRest).toDouble()
        val homeForm = teamForm[home]?.average() ?: 0.0
        val awayForm = teamForm[away]?.average() ?: 0.0
        val formDiff = homeForm - awayForm

        val x = doubleArrayOf(eloDiff, restDiff, formDiff)
        val winProb = sigmoid(dot(winCoef, x) + winIntercept)
        val margin = dot(marginCoef, x) + marginIntercept

        return Result(
            homeTeam = home,
            awayTeam = away,
            homeWinProb = winProb,
            awayWinProb = 1.0 - winProb,
            predictedMarginHome = margin,
            homeElo = eloOf(home),
            awayElo = eloOf(away),
        )
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
    private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

    data class Result(
        val homeTeam: String,
        val awayTeam: String,
        val homeWinProb: Double,
        val awayWinProb: Double,
        val predictedMarginHome: Double,
        val homeElo: Double,
        val awayElo: Double,
    )
}
