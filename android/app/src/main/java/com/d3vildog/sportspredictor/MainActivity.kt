package com.d3vildog.sportspredictor

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.d3vildog.sportspredictor.databinding.ActivityMainBinding
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nflModel: NflModel
    private lateinit var nhlModel: NhlModel

    private val sports = listOf("NFL", "NHL")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nflModel = NflModel(loadJsonAsset("nfl_model.json"))
        nhlModel = NhlModel(loadJsonAsset("nhl_model.json"))

        binding.sportSpinner.adapter = simpleAdapter(sports)
        binding.sportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                onSportChanged(sports[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        onSportChanged(sports[0])

        binding.predictButton.setOnClickListener { runPrediction() }
    }

    private fun onSportChanged(sport: String) {
        val teams = if (sport == "NFL") nflModel.teams else nhlModel.teams
        binding.homeSpinner.adapter = simpleAdapter(teams)
        binding.awaySpinner.adapter = simpleAdapter(teams)
        if (teams.size > 1) binding.awaySpinner.setSelection(1)
        binding.restDaysRow.visibility = if (sport == "NFL") android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun runPrediction() {
        val sport = sports[binding.sportSpinner.selectedItemPosition]
        val home = binding.homeSpinner.selectedItem as? String ?: return
        val away = binding.awaySpinner.selectedItem as? String ?: return

        if (home == away) {
            binding.resultText.text = "Pick two different teams."
            return
        }

        binding.resultText.text = if (sport == "NFL") {
            val homeRest = binding.homeRestInput.text.toString().toIntOrNull() ?: 7
            val awayRest = binding.awayRestInput.text.toString().toIntOrNull() ?: 7
            formatNfl(nflModel.predict(home, away, homeRest, awayRest))
        } else {
            formatNhl(nhlModel.predict(home, away))
        }
    }

    private fun formatNfl(r: NflModel.Result): String = buildString {
        appendLine("${r.awayTeam} @ ${r.homeTeam}")
        appendLine()
        appendLine("${r.homeTeam} win prob: ${pct(r.homeWinProb)}")
        appendLine("${r.awayTeam} win prob: ${pct(r.awayWinProb)}")
        appendLine()
        appendLine("Predicted margin (home - away): ${signed(r.predictedMarginHome)}")
        appendLine()
        appendLine("Elo: ${r.homeTeam} ${round1(r.homeElo)} vs ${r.awayTeam} ${round1(r.awayElo)}")
    }

    private fun formatNhl(r: NhlModel.Result): String = buildString {
        appendLine("${r.awayTeam} @ ${r.homeTeam}")
        appendLine()
        appendLine("${r.homeTeam} win prob: ${pct(r.homeWinProb)}")
        appendLine("${r.awayTeam} win prob: ${pct(r.awayWinProb)}")
        appendLine()
        appendLine(
            "Predicted score: ${r.homeTeam} ${round2(r.predictedHomeGoals)} - " +
                "${round2(r.predictedAwayGoals)} ${r.awayTeam}"
        )
        appendLine()
        appendLine("Elo: ${r.homeTeam} ${round1(r.homeElo)} vs ${r.awayTeam} ${round1(r.awayElo)}")
    }

    private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f", v)
    private fun round1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun round2(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun loadJsonAsset(name: String): JSONObject {
        val text = assets.open(name).bufferedReader().use { it.readText() }
        return JSONObject(text)
    }
}
