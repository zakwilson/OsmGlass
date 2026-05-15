package dev.glass.phone.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    private lateinit var resultsAdapter: SearchResultsAdapter
    private lateinit var historyAdapter: DestinationHistoryAdapter
    private lateinit var history: DestinationHistoryStore
    private lateinit var results: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val input = view.findViewById<TextInputEditText>(R.id.search_input)
        results = view.findViewById(R.id.results)
        progress = view.findViewById(R.id.progress)
        status = view.findViewById(R.id.status)

        history = DestinationHistoryStore(requireContext())

        resultsAdapter = SearchResultsAdapter { place ->
            history.add(place)
            viewModel.pickDestination(place, origin = null)
        }
        historyAdapter = DestinationHistoryAdapter(
            onPick = { place ->
                history.add(place)
                viewModel.pickDestination(place, origin = null)
            },
            onRemove = { place ->
                history.remove(place)
                refreshHistory()
            },
            onClear = { confirmClearHistory() },
        )
        results.layoutManager = LinearLayoutManager(requireContext())

        showHistory()

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                debounce?.cancel()
                if (query.isEmpty()) {
                    progress.visibility = View.GONE
                    status.visibility = View.GONE
                    showHistory()
                    return
                }
                progress.visibility = View.VISIBLE
                status.visibility = View.GONE
                if (results.adapter !== resultsAdapter) {
                    results.adapter = resultsAdapter
                }
                debounce = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    val q = query
                    val res = try {
                        withContext(Dispatchers.IO) { client.search(q) }
                    } catch (t: Throwable) {
                        progress.visibility = View.GONE
                        status.text = getString(R.string.search_error, t.message ?: "?")
                        status.visibility = View.VISIBLE
                        resultsAdapter.submit(emptyList())
                        return@launch
                    }
                    progress.visibility = View.GONE
                    if (res.isEmpty()) {
                        status.setText(R.string.search_no_results)
                        status.visibility = View.VISIBLE
                    } else {
                        status.visibility = View.GONE
                    }
                    resultsAdapter.submit(res)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        if (query.isEmpty()) refreshHistory()
    }

    private fun showHistory() {
        if (results.adapter !== historyAdapter) {
            results.adapter = historyAdapter
        }
        refreshHistory()
    }

    private fun refreshHistory() {
        historyAdapter.submit(history.list())
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear_confirm_title)
            .setMessage(R.string.history_clear_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.history_clear_confirm_ok) { _, _ ->
                history.clear()
                refreshHistory()
            }
            .show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return super.onCreateView(inflater, container, savedInstanceState)!!
    }
}
