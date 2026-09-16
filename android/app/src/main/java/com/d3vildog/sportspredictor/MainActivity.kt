package com.d3vildog.sportspredictor

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.d3vildog.sportspredictor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: GamesAdapter
    private val sports = listOf("NFL", "NHL")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = GamesAdapter { game -> openDetail(game) }
        binding.gamesRecyclerView.adapter = adapter
        binding.gamesRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        binding.sportSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sports).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.sportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                loadGames(sports[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.swipeRefresh.setOnRefreshListener { loadGames(sports[binding.sportSpinner.selectedItemPosition], forceRefresh = true) }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }

        loadGames(sports[0])
    }

    private fun openDetail(game: UpcomingGame) {
        val intent = Intent(this, GameDetailActivity::class.java).apply {
            putExtra("league", game.league)
            putExtra("home", game.homeTeam)
            putExtra("away", game.awayTeam)
            putExtra("kickoff", game.kickoffLocal?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        }
        startActivity(intent)
    }

    private fun loadGames(league: String, forceRefresh: Boolean = false) {
        binding.statusText.text = "Loading upcoming $league games…"
        lifecycleScope.launch {
            try {
                if (forceRefresh) clearScheduleCache(league)
                val games = withContext(Dispatchers.IO) { fetchUpcoming(league) }
                adapter.submit(games)
                binding.statusText.text = if (games.isEmpty()) {
                    "No upcoming $league games found."
                } else {
                    "${games.size} upcoming $league games · live schedule"
                }
            } catch (e: Exception) {
                binding.statusText.text = "Couldn't load $league schedule: ${e.message}"
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun clearScheduleCache(league: String) {
        val name = if (league == "NFL") "nfl_games.csv" else null
        name?.let { java.io.File(cacheDir, it).delete() }
    }

    private suspend fun fetchUpcoming(league: String): List<UpcomingGame> {
        return if (league == "NFL") {
            val schedule = NflLiveData.loadSchedule(this)
            NflLiveData.upcomingGames(schedule).map { g ->
                val extra = buildString {
                    if (g.spreadLine != null) append("Spread: ${g.homeTeam} ${if (g.spreadLine <= 0) g.spreadLine else "+${g.spreadLine}"}")
                    if (g.totalLine != null) append(if (isNotEmpty()) " · O/U ${g.totalLine}" else "O/U ${g.totalLine}")
                }
                UpcomingGame("NFL", g.homeTeam, g.awayTeam, g.kickoffLocal, extra)
            }
        } else {
            val teams = listOf(
                "ANA","ARI","BOS","BUF","CGY","CAR","CHI","COL","CBJ","DAL","DET","EDM","FLA","LAK","MIN",
                "MTL","NSH","NJD","NYI","NYR","OTT","PHI","PIT","SJS","SEA","STL","TBL","TOR","UTA","VAN","VGK","WSH","WPG",
            )
            val nextGames = coroutineScope {
                teams.map { team ->
                    async(Dispatchers.IO) {
                        try {
                            NhlLiveData.nextGame(NhlLiveData.loadSchedule(this@MainActivity, team))
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            val seen = HashSet<Long>()
            nextGames.filterNotNull()
                .filter { seen.add(it.gameId) }
                .map { UpcomingGame("NHL", it.homeTeam, it.awayTeam, it.startsLocal(), it.venue) }
                .sortedBy { it.kickoffLocal }
        }
    }

    private fun showSettingsDialog() {
        val input = EditText(this).apply {
            hint = "https://your-backend.example.com"
            setText(BettingSentimentClient.getBackendUrl(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("NHL betting-sentiment backend URL")
            .setMessage("Used only for NHL games (see server/README.md for how to deploy one). NFL betting lines are already built in.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> BettingSentimentClient.setBackendUrl(this, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
