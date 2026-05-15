package dev.glass.phone.ui.ride

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import dev.glass.phone.R
import dev.glass.phone.ride.RideService
import dev.glass.phone.ui.RideViewModel

/**
 * Active-ride view. Subscribes to {@link RideService} updates via a static callback (no IPC needed
 * since service + activity share the same process).
 */
class RideControlsFragment : Fragment(R.layout.fragment_ride_controls) {

    private val viewModel: RideViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val turnText = view.findViewById<TextView>(R.id.turn_text)
        val distanceText = view.findViewById<TextView>(R.id.distance_text)
        val statusText = view.findViewById<TextView>(R.id.connection_status)
        val stopBtn = view.findViewById<MaterialButton>(R.id.stop_button)

        turnText.text = "—"
        distanceText.text = "—"

        stopBtn.setOnClickListener {
            requireActivity().stopService(Intent(requireContext(), RideService::class.java))
            viewModel.onRideStopped()
        }

        RideService.uiObserver = object : RideService.UiObserver {
            override fun onConnectionStateChange(connected: Boolean, status: String) {
                statusText.post { statusText.text = status }
            }
            override fun onTurnUpdate(text: String, distanceM: Int) {
                turnText.post {
                    turnText.text = text
                    distanceText.text = "$distanceM m"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RideService.uiObserver = null
    }
}
