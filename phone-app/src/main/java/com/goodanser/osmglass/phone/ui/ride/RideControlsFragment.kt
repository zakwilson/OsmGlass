package com.goodanser.osmglass.phone.ui.ride

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.goodanser.osmglass.phone.R
import com.goodanser.osmglass.phone.osmand.OsmAndAidlClient
import com.goodanser.osmglass.phone.ride.RideService
import com.goodanser.osmglass.phone.routing.LatLng
import com.goodanser.osmglass.phone.ui.DisplayPrefs
import com.goodanser.osmglass.phone.ui.RideViewModel
import com.goodanser.osmglass.protocol.Packet

/**
 * Active-ride view. Subscribes to {@link RideService} updates via a static callback (no IPC needed
 * since service + activity share the same process). The visual map lives in OsmAnd — use the
 * "Open OsmAnd" button to switch to the OsmAnd map view; the navigation session is already running
 * there in the background.
 */
class RideControlsFragment : Fragment(R.layout.fragment_ride_controls) {

    private val viewModel: RideViewModel by activityViewModels()
    private var lastConnectionStatus: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val turnText = view.findViewById<TextView>(R.id.turn_text)
        val distanceText = view.findViewById<TextView>(R.id.distance_text)
        val statusText = view.findViewById<TextView>(R.id.connection_status)
        val stopBtn = view.findViewById<MaterialButton>(R.id.stop_button)
        val displayBtn = view.findViewById<MaterialButton>(R.id.display_settings)
        val openOsmAndBtn = view.findViewById<MaterialButton>(R.id.open_osmand_button)

        turnText.text = "—"
        distanceText.text = "—"

        displayBtn.setOnClickListener {
            DisplaySettingsDialogFragment()
                .show(parentFragmentManager, DisplaySettingsDialogFragment.TAG)
        }

        stopBtn.setOnClickListener {
            // Keep the service + Bluetooth transport alive so the next route can push to Glass
            // immediately instead of waiting on a BT reconnect.
            RideService.stopRide()
            viewModel.onRideStopped()
        }

        openOsmAndBtn.setOnClickListener { launchOsmAnd() }

        RideService.uiObserver = object : RideService.UiObserver {
            override fun onConnectionStateChange(connected: Boolean, status: String) {
                lastConnectionStatus = status
                statusText.post { statusText.text = status }
            }
            override fun onProgressUpdate(progress: RideService.Progress) {
                val slots = DisplayPrefs.get(requireContext())
                turnText.post {
                    turnText.text = renderField(slots.phoneTop, progress)
                    distanceText.text = renderField(slots.phoneBottom, progress)
                }
            }
            override fun onLocationUpdate(location: LatLng, bearingDeg: Float?) { /* map lives in OsmAnd */ }
            override fun onRerouteStateChange(message: String?) {
                statusText.post {
                    statusText.text = message ?: lastConnectionStatus
                }
            }
            override fun onRouteReplaced(route: RideViewModel.RouteState.Ready) {
                view.post { viewModel.onRouteReplaced(route) }
            }
        }
    }

    private fun renderField(
        field: Packet.DisplayConfig.Field,
        p: RideService.Progress,
    ): String = when (field) {
        Packet.DisplayConfig.Field.TURN_INSTRUCTION -> p.turnInstruction
        Packet.DisplayConfig.Field.DISTANCE_TO_TURN -> formatDistance(p.distanceToTurnM)
        Packet.DisplayConfig.Field.REMAINING_DISTANCE -> formatDistance(p.remainingDistanceM)
        Packet.DisplayConfig.Field.ETA -> formatDuration(p.etaSec)
        Packet.DisplayConfig.Field.SPEED -> "${p.speedKmh} km/h"
    }

    private fun formatDistance(meters: Int): String =
        if (meters >= 1000) "%.1f km".format(meters / 1000f) else "$meters m"

    private fun formatDuration(seconds: Int): String {
        if (seconds < 60) return "${seconds}s"
        val mins = seconds / 60
        if (mins < 60) return "${mins}m"
        val hours = mins / 60
        val remMin = mins % 60
        return "${hours}h ${remMin}m"
    }

    private fun launchOsmAnd() {
        val ctx = requireContext()
        val pkg = OsmAndAidlClient.resolveOsmAndPackage(ctx)
        if (pkg == null) {
            Log.w("RideControls", "OsmAnd not installed; cannot open map view")
            return
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RideService.uiObserver = null
    }
}
