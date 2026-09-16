package com.d3vildog.sportspredictor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.d3vildog.sportspredictor.databinding.ItemGameBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class UpcomingGame(
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffLocal: LocalDateTime?,
    val extra: String,
)

class GamesAdapter(
    private val onClick: (UpcomingGame) -> Unit,
) : RecyclerView.Adapter<GamesAdapter.ViewHolder>() {

    private var items: List<UpcomingGame> = emptyList()
    private val fmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

    fun submit(newItems: List<UpcomingGame>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val game = items[position]
        holder.binding.matchupText.text = "${game.awayTeam} @ ${game.homeTeam}"
        holder.binding.kickoffText.text = game.kickoffLocal?.format(fmt) ?: "Time TBD"
        holder.binding.extraText.text = game.extra
        holder.binding.extraText.visibility = if (game.extra.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        holder.itemView.setOnClickListener { onClick(game) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root)
}
