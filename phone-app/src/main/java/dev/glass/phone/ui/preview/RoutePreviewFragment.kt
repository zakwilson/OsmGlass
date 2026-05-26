package dev.glass.phone.ui.preview

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dev.glass.phone.R
import dev.glass.phone.gps.LocationProvider
import dev.glass.phone.osmand.OsmAndAidlClient
import dev.glass.phone.ride.RideService
import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.NavigationMode
import dev.glass.phone.routing.RoutingException
import dev.glass.phone.routing.computeOsmAndRoute
import dev.glass.phone.ui.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutePreviewFragment : Fragment(R.layout.fragment_route_preview) {

    private val viewModel: RideViewModel by activityViewModels()
    private var prefetchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val destinationLabel = view.findViewById<TextView>(R.id.destination)
        val status = view.findViewById<TextView>(R.id.status)
        val backBtn = view.findViewById<MaterialButton>(R.id.back_button)
        val startBtn = view.findViewById<MaterialButton>(R.id.start_button)
        val openOsmAndBtn = view.findViewById<MaterialButton>(R.id.open_osmand_button)
        val modeToggle = view.findViewById<MaterialButtonToggleGroup>(R.id.mode_toggle)

        val initialModeId = when (viewModel.mode.value) {
            NavigationMode.CYCLING -> R.id.mode_cycle
            NavigationMode.WALKING -> R.id.mode_walk
            NavigationMode.DRIVING -> R.id.mode_drive
        }
        modeToggle.check(initialModeId)
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.mode_walk -> NavigationMode.WALKING
                R.id.mode_drive -> NavigationMode.DRIVING
                else -> NavigationMode.CYCLING
            }
            if (newMode == viewModel.mode.value) return@addOnButtonCheckedListener
            viewModel.setMode(newMode)
            val current = viewModel.route.value
            val destination = when (current) {
                is RideViewModel.RouteState.Ready -> current.destination
                is RideViewModel.RouteState.Selected -> current.destination
                else -> null
            }
            val origin = when (current) {
                is RideViewModel.RouteState.Ready -> current.origin
                is RideViewModel.RouteState.Selected -> current.origin
                else -> null
            }
            if (destination != null) computeRoute(destination, origin)
        }

        backBtn.setOnClickListener { viewModel.onRideStopped() }
        startBtn.setOnClickListener {
            val state = viewModel.route.value
            if (state is RideViewModel.RouteState.Ready) {
                // If a previous ride left the service+transport alive, push directly so the new
                // route reaches Glass without paying for a Bluetooth reconnect. Otherwise spin
                // up the service the usual way.
                if (!RideService.startRoute(state)) {
                    RideService.pendingRoute = state
                    requireActivity().startService(Intent(requireContext(), RideService::class.java))
                }
                viewModel.onRideStarted()
            }
        }
        openOsmAndBtn.setOnClickListener { launchOsmAnd() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.route.collect { state ->
                    when (state) {
                        is RideViewModel.RouteState.Selected -> {
                            destinationLabel.text = state.destination.displayName
                            startBtn.isEnabled = false
                            status.text = "Tap a result, then 'Compute route'…"
                            computeRoute(state.destination, state.origin)
                        }
                        is RideViewModel.RouteState.Computing -> {
                            status.text = "Computing route…"
                            startBtn.isEnabled = false
                        }
                        is RideViewModel.RouteState.Ready -> {
                            status.text = "Route ready: ${state.turns.size} turns"
                            startBtn.isEnabled = true
                        }
                        is RideViewModel.RouteState.Failed -> {
                            status.text = state.message
                            startBtn.isEnabled = false
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun computeRoute(destination: dev.glass.phone.ui.search.Place, origin: LatLng?) {
        viewModel.onComputing()
        prefetchJob?.cancel()
        prefetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val from = origin ?: run {
                val located = withContext(Dispatchers.IO) {
                    LocationProvider(requireContext().applicationContext).getCurrentLocation()
                }
                if (located == null) {
                    viewModel.onFailed(
                        "No GPS fix. Grant location permission, enable GPS, and step outside (or near a window).",
                    )
                    return@launch
                }
                located
            }

            try {
                val mode = viewModel.mode.value
                val computed = computeOsmAndRoute(
                    context = requireContext().applicationContext,
                    start = from,
                    end = destination.location,
                    mode = mode,
                    destName = destination.displayName,
                )
                viewModel.onReady(
                    RideViewModel.RouteState.Ready(
                        origin = from,
                        destination = destination,
                        track = computed.track,
                        turns = computed.turns,
                        mode = mode,
                    ),
                )
            } catch (t: RoutingException) {
                Log.w("RoutePreview", "OsmAnd routing failed", t)
                viewModel.onFailed(getString(R.string.route_failed, t.message ?: "?"))
            } catch (t: Throwable) {
                Log.w("RoutePreview", "Compute failed", t)
                viewModel.onFailed(getString(R.string.route_failed, t.message ?: "?"))
            }
        }
    }

    private fun launchOsmAnd() {
        val ctx = requireContext()
        val pkg = OsmAndAidlClient.resolveOsmAndPackage(ctx)
        if (pkg == null) {
            Log.w("RoutePreview", "OsmAnd not installed; cannot open map view")
            return
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prefetchJob?.cancel()
        prefetchJob = null
    }
}
