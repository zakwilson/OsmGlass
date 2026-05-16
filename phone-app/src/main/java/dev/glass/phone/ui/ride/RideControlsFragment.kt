package dev.glass.phone.ui.ride

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import dev.glass.phone.R
import dev.glass.phone.render.MapDataSource
import dev.glass.phone.ride.RideService
import dev.glass.phone.routing.LatLng
import dev.glass.phone.ui.OrientationPrefs
import dev.glass.phone.ui.RideViewModel
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

/**
 * Active-ride view. Subscribes to {@link RideService} updates via a static callback (no IPC needed
 * since service + activity share the same process).
 */
class RideControlsFragment : Fragment(R.layout.fragment_ride_controls) {

    private val viewModel: RideViewModel by activityViewModels()
    private var mapView: MapView? = null
    private var rendererLayer: TileRendererLayer? = null
    private var positionMarker: Circle? = null
    private var hasRecenteredOnFirstFix = false
    private var routePolyline: Polyline? = null
    private var lastConnectionStatus: String = ""
    private var orientationToggle: MaterialButton? = null
    private var lastBearingDeg: Float? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        AndroidGraphicFactory.createInstance(requireActivity().application)

        val turnText = view.findViewById<TextView>(R.id.turn_text)
        val distanceText = view.findViewById<TextView>(R.id.distance_text)
        val statusText = view.findViewById<TextView>(R.id.connection_status)
        val mapStatus = view.findViewById<TextView>(R.id.map_status)
        val mapContainer = view.findViewById<FrameLayout>(R.id.map_container)
        val stopBtn = view.findViewById<MaterialButton>(R.id.stop_button)
        val orientBtn = view.findViewById<MaterialButton>(R.id.orientation_toggle)
        orientationToggle = orientBtn

        turnText.text = "—"
        distanceText.text = "—"

        stopBtn.setOnClickListener {
            // Keep the service + Bluetooth transport alive so the next route can push to Glass
            // immediately instead of waiting on a BT reconnect.
            RideService.stopRide()
            viewModel.onRideStopped()
        }

        orientBtn.setOnClickListener {
            val next = if (OrientationPrefs.get(requireContext()) == OrientationPrefs.Mode.TRAVEL_UP) {
                OrientationPrefs.Mode.NORTH_UP
            } else {
                OrientationPrefs.Mode.TRAVEL_UP
            }
            OrientationPrefs.set(requireContext(), next)
            applyMapRotation()
            RideService.requestSnippetRefresh()
        }

        mountMap(mapContainer, mapStatus)
        drawCurrentRoute()
        applyMapRotation()

        RideService.uiObserver = object : RideService.UiObserver {
            override fun onConnectionStateChange(connected: Boolean, status: String) {
                lastConnectionStatus = status
                statusText.post { statusText.text = status }
            }
            override fun onTurnUpdate(text: String, distanceM: Int) {
                turnText.post {
                    turnText.text = text
                    distanceText.text = "$distanceM m"
                }
            }
            override fun onLocationUpdate(location: LatLng, bearingDeg: Float?) {
                view.post {
                    updatePositionMarker(location)
                    if (bearingDeg != null) {
                        lastBearingDeg = bearingDeg
                        applyMapRotation()
                    }
                }
            }
            override fun onRerouteStateChange(message: String?) {
                statusText.post {
                    statusText.text = message ?: lastConnectionStatus
                }
            }
            override fun onRouteReplaced(route: RideViewModel.RouteState.Ready) {
                view.post {
                    viewModel.onRouteReplaced(route)
                    redrawRoute(route.track)
                }
            }
        }
    }

    private fun mountMap(container: FrameLayout, status: TextView) {
        val mapFile = MapDataSource(requireContext()).resolve()
        if (mapFile == null) {
            status.text = "No .map present at ${requireContext().filesDir}/maps/."
            return
        }
        status.visibility = View.GONE

        val mv = MapView(requireContext())
        mv.isClickable = true
        mv.mapScaleBar.isVisible = false
        mv.setBuiltInZoomControls(false)
        container.addView(
            mv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        mapView = mv

        val tileCache = AndroidUtil.createTileCache(
            requireContext(), "ride-controls", mv.model.displayModel.tileSize, 1f,
            mv.model.frameBufferModel.overdrawFactor,
        )
        val store: MapDataStore = MapFile(mapFile)
        val layer = TileRendererLayer(
            tileCache, store, mv.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE,
        )
        layer.setXmlRenderTheme(MapsforgeThemes.OSMARENDER)
        mv.layerManager.layers.add(layer)
        rendererLayer = layer
    }

    private fun drawCurrentRoute() {
        val state = viewModel.route.value
        val ready = when (state) {
            is RideViewModel.RouteState.Active -> state.ready
            is RideViewModel.RouteState.Ready -> state
            else -> return
        }
        if (ready.track.isEmpty()) return
        redrawRoute(ready.track)
        val mv = mapView ?: return
        val origin = ready.origin
        mv.setCenter(LatLong(origin.lat, origin.lon))
        mv.setZoomLevel(15)
    }

    private fun redrawRoute(track: List<LatLng>) {
        val mv = mapView ?: return
        routePolyline?.let { mv.layerManager.layers.remove(it) }
        val paint = AndroidGraphicFactory.INSTANCE.createPaint()
        paint.setColor(Color.RED)
        paint.setStyle(Style.STROKE)
        paint.setStrokeWidth(8f)
        val poly = Polyline(paint, AndroidGraphicFactory.INSTANCE)
        poly.latLongs.addAll(track.map { LatLong(it.lat, it.lon) })
        mv.layerManager.layers.add(poly)
        routePolyline = poly
    }

    private fun updatePositionMarker(location: LatLng) {
        val mv = mapView ?: return
        val point = LatLong(location.lat, location.lon)
        val marker = positionMarker ?: createPositionMarker().also {
            positionMarker = it
            mv.layerManager.layers.add(it)
        }
        marker.setLatLong(point)
        marker.requestRedraw()
        if (!hasRecenteredOnFirstFix) {
            hasRecenteredOnFirstFix = true
            mv.setCenter(point)
            mv.setZoomLevel(16)
        } else {
            mv.setCenter(point)
        }
    }

    private fun createPositionMarker(): Circle {
        val factory = AndroidGraphicFactory.INSTANCE
        val fill = factory.createPaint().apply {
            setColor(Color.parseColor("#3B82F6"))
            setStyle(Style.FILL)
        }
        val stroke = factory.createPaint().apply {
            setColor(Color.WHITE)
            setStyle(Style.STROKE)
            setStrokeWidth(4f)
        }
        return Circle(null, 8f, fill, stroke)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RideService.uiObserver = null
        mapView?.destroyAll()
        mapView = null
        rendererLayer = null
        positionMarker = null
        routePolyline = null
        orientationToggle = null
        hasRecenteredOnFirstFix = false
    }

    /**
     * Rotates the MapView so direction-of-travel points up. Uses View.setRotation on the entire
     * MapView — mapsforge renders tiles north-up internally; visually rotating the view is the
     * simplest approach that doesn't depend on private API surface across mapsforge versions.
     */
    private fun applyMapRotation() {
        val mv = mapView ?: return
        val mode = OrientationPrefs.get(requireContext())
        val rotation = if (mode == OrientationPrefs.Mode.TRAVEL_UP) {
            -(lastBearingDeg ?: 0f)
        } else 0f
        mv.rotation = rotation
        // Counter-rotate the toggle icon so the compass arrow always points to actual north.
        orientationToggle?.rotation = -rotation
    }
}
