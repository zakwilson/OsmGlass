package dev.glass.phone.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import dev.glass.phone.R
import dev.glass.phone.ui.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment(R.layout.fragment_search) {

    private val viewModel: RideViewModel by activityViewModels()
    private val client = GeocodingClient()
    private var query: String = ""
    private var debounce: Job? = null
    private lateinit var adapter: SearchResultsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val input = view.findViewById<TextInputEditText>(R.id.search_input)
        val results = view.findViewById<RecyclerView>(R.id.results)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val status = view.findViewById<TextView>(R.id.status)

        adapter = SearchResultsAdapter { place ->
            viewModel.pickDestination(place, origin = null)
        }
        results.layoutManager = LinearLayoutManager(requireContext())
        results.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                debounce?.cancel()
                if (query.isEmpty()) {
                    progress.visibility = View.GONE
                    status.visibility = View.GONE
                    adapter.submit(emptyList())
                    return
                }
                progress.visibility = View.VISIBLE
                status.visibility = View.GONE
                debounce = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    val q = query
                    val res = try {
                        withContext(Dispatchers.IO) { client.search(q) }
                    } catch (t: Throwable) {
                        progress.visibility = View.GONE
                        status.text = getString(R.string.search_error, t.message ?: "?")
                        status.visibility = View.VISIBLE
                        adapter.submit(emptyList())
                        return@launch
                    }
                    progress.visibility = View.GONE
                    if (res.isEmpty()) {
                        status.setText(R.string.search_no_results)
                        status.visibility = View.VISIBLE
                    } else {
                        status.visibility = View.GONE
                    }
                    adapter.submit(res)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return super.onCreateView(inflater, container, savedInstanceState)!!
    }
}
