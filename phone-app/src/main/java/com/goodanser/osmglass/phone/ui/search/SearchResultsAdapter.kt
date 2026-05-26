package com.goodanser.osmglass.phone.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.goodanser.osmglass.phone.R

class SearchResultsAdapter(
    private val onClick: (Place) -> Unit,
) : ListAdapter<Place, SearchResultsAdapter.ViewHolder>(DIFF) {

    fun submit(items: List<Place>) = submitList(items)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val place = getItem(position)
        holder.text.text = place.displayName
        holder.itemView.setOnClickListener { onClick(place) }
    }

    class ViewHolder(view: TextView) : RecyclerView.ViewHolder(view) {
        val text: TextView = view
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Place>() {
            override fun areItemsTheSame(o: Place, n: Place) =
                o.displayName == n.displayName && o.location == n.location
            override fun areContentsTheSame(o: Place, n: Place) = o == n
        }
    }
}
