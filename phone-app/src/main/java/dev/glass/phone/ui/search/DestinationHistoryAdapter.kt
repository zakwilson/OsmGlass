package dev.glass.phone.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.glass.phone.R

class DestinationHistoryAdapter(
    private val onPick: (Place) -> Unit,
    private val onRemove: (Place) -> Unit,
    private val onClear: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Place> = emptyList()

    fun submit(items: List<Place>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else items.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_history_header, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_history, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.clear.setOnClickListener { onClear() }
            is ItemHolder -> {
                val place = items[position - 1]
                holder.text.text = place.displayName
                holder.text.setOnClickListener { onPick(place) }
                holder.remove.setOnClickListener { onRemove(place) }
            }
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val clear: Button = view.findViewById(R.id.clear)
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text)
        val remove: ImageButton = view.findViewById(R.id.remove)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
