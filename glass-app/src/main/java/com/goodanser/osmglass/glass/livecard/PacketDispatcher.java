package com.goodanser.osmglass.glass.livecard;

import android.util.Log;

import com.goodanser.osmglass.protocol.Packet;
import com.goodanser.osmglass.protocol.transport.Transport;

import java.util.HashMap;
import java.util.Map;

/**
 * Receives packets from the {@code Transport} and updates the LiveCard via
 * {@link NavLiveCardService#updateRemoteViews(byte[], String, String)}.
 *
 * Caches {@link Packet.TurnBundle}s by (routeId, turnIndex). When a {@link Packet.Progress}
 * arrives, looks up the corresponding cached bundle and pushes its snippet + instruction +
 * formatted distance to the LiveCard.
 */
public final class PacketDispatcher implements Transport.Listener {
    private static final String TAG = "PacketDispatcher";

    /**
     * Approach threshold scales with speed to give roughly a constant ~18 seconds of lead time:
     * 30 m floor for walking, ~150 m at 30 km/h cycling, ~500 m at 100 km/h driving.
     */
    private static final int MIN_APPROACH_M = 30;
    private static final double APPROACH_M_PER_KMH = 5.0;
    /** Distance at which we speak the "turn now" cue. */
    private static final int IMMINENT_THRESHOLD_M = 30;

    static int approachThresholdM(int speedKmh) {
        return Math.max(MIN_APPROACH_M, (int) Math.round(speedKmh * APPROACH_M_PER_KMH));
    }

    private static int roundToTen(int m) {
        return ((m + 5) / 10) * 10;
    }

    private final NavLiveCardService service;
    private final Map<Long, Packet.TurnBundle> cache = new HashMap<>();
    private long currentRouteId = -1;
    private int activeTurnIndex = -1;
    private int lastApproachSpokenTurn = -1;
    private int lastImminentSpokenTurn = -1;
    /** Whether we've already spoken the "head north on X, then turn left in Y meters" preamble. */
    private boolean initialDirectionSpoken = false;
    /** Departure instruction for the route, from {@link Packet.RouteStart#startLabel}; the preamble
     *  for the initial spoken cue. Empty until a RouteStart arrives or if the phone didn't send one. */
    private String routeStartLabel = "";
    /** Most recently received display configuration from the phone. */
    private Packet.DisplayConfig.Field topSlot = Packet.DisplayConfig.Field.TURN_INSTRUCTION;
    private Packet.DisplayConfig.Field bottomSlot = Packet.DisplayConfig.Field.DISTANCE_TO_TURN;
    /** When true, all TTS announcements (approach, imminent, initial direction, turn alerts) are
     *  suppressed. Set by {@link Packet.DisplayConfig#muteTts} pushed from the phone. */
    private boolean muteTts = false;

    public PacketDispatcher(NavLiveCardService service) {
        this.service = service;
    }

    @Override public void onConnected() {
        Log.i(TAG, "connected");
        service.onTransportConnected();
        service.updateRemoteViews(null, null, "");
    }

    @Override public void onPacket(Packet p) {
        if (p instanceof Packet.RouteStart) {
            Packet.RouteStart rs = (Packet.RouteStart) p;
            Log.i(TAG, "ROUTE_START id=" + rs.routeId + " turns=" + rs.totalTurns);
            currentRouteId = rs.routeId;
            routeStartLabel = rs.startLabel;
            cache.clear();
            lastApproachSpokenTurn = -1;
            lastImminentSpokenTurn = -1;
            initialDirectionSpoken = false;
        } else if (p instanceof Packet.DisplayConfig) {
            Packet.DisplayConfig dc = (Packet.DisplayConfig) p;
            Log.i(TAG, "DISPLAY_CONFIG top=" + dc.topSlot + " bottom=" + dc.bottomSlot
                + " muteTts=" + dc.muteTts);
            topSlot = dc.topSlot;
            bottomSlot = dc.bottomSlot;
            muteTts = dc.muteTts;
        } else if (p instanceof Packet.TurnBundle) {
            Packet.TurnBundle tb = (Packet.TurnBundle) p;
            Log.d(TAG, "TURN_BUNDLE #" + tb.turnIndex + " " + tb.kind + " (" + tb.pngBytes.length + "B)");
            cache.put(key(tb.routeId, tb.turnIndex), tb);
        } else if (p instanceof Packet.Progress) {
            Packet.Progress pr = (Packet.Progress) p;
            Packet.TurnBundle tb = cache.get(key(pr.routeId, pr.turnIndex));
            if (tb == null) {
                Log.d(TAG, "PROGRESS without cached TURN_BUNDLE for #" + pr.turnIndex);
                return;
            }
            // First PROGRESS with a cached bundle: speak the one-time route-start cue, keyed to the
            // turn the rider is actually approaching (the phone ships it first) rather than the
            // already-passed leading turns.
            if (!initialDirectionSpoken) {
                maybeSpeakInitialDirection(pr, tb);
            }
            // If the active turn index advanced, the previous turn has been passed — release the
            // display before deciding whether to wake for the new turn.
            if (activeTurnIndex != -1 && pr.turnIndex != activeTurnIndex) {
                service.onTurnPassed();
                activeTurnIndex = -1;
            }
            int approachThresholdM = approachThresholdM(pr.speedKmh);
            if (pr.distanceToTurnM <= approachThresholdM) {
                service.onApproachingTurn(pr.turnIndex);
                activeTurnIndex = pr.turnIndex;
                if (lastApproachSpokenTurn != pr.turnIndex) {
                    lastApproachSpokenTurn = pr.turnIndex;
                    speak(
                        TtsSpeaker.utteranceFor(TtsSpeaker.Cue.APPROACH, tb.kind, tb.instructionText, roundToTen(pr.distanceToTurnM)),
                        "approach-" + pr.turnIndex);
                }
            }
            if (pr.distanceToTurnM <= IMMINENT_THRESHOLD_M && lastImminentSpokenTurn != pr.turnIndex) {
                lastImminentSpokenTurn = pr.turnIndex;
                speak(
                    TtsSpeaker.utteranceFor(TtsSpeaker.Cue.IMMINENT, tb.kind, tb.instructionText, IMMINENT_THRESHOLD_M),
                    "imminent-" + pr.turnIndex);
            }
            String top = renderField(topSlot, pr, tb);
            String bottom = renderField(bottomSlot, pr, tb);
            Log.i(TAG, "PROGRESS #" + pr.turnIndex + " top=" + top + " bottom=" + bottom
                + " marker=" + (pr.markerPxX == Packet.Progress.MARKER_NONE
                    ? "none"
                    : ("(" + pr.markerPxX + "," + pr.markerPxY + ","
                        + (pr.markerBearingDeg100 / 100.0) + "°)")));
            service.updateRemoteViews(
                tb.pngBytes, top, bottom,
                pr.markerPxX, pr.markerPxY, pr.markerBearingDeg100);
        } else if (p instanceof Packet.TurnAlert) {
            Packet.TurnAlert a = (Packet.TurnAlert) p;
            Log.i(TAG, "TURN_ALERT #" + a.turnIndex);
            // OsmAnd voice prompt fired on the phone. Wake the display now even if we're still
            // outside the distance-based approach threshold so a glance shows the upcoming turn.
            service.onApproachingTurn(a.turnIndex);
            activeTurnIndex = a.turnIndex;
        } else if (p instanceof Packet.RouteEnd) {
            Packet.RouteEnd re = (Packet.RouteEnd) p;
            Log.i(TAG, "ROUTE_END id=" + re.routeId + " " + re.reason);
            service.onTurnPassed();
            activeTurnIndex = -1;
            lastApproachSpokenTurn = -1;
            lastImminentSpokenTurn = -1;
            String message = (re.reason == Packet.RouteEnd.Reason.OFFROUTE) ? "Rerouting…" : "Done";
            service.updateRemoteViews(null, message, "");
            cache.clear();
            currentRouteId = -1;
        }
    }

    @Override public void onDisconnected(Throwable cause) {
        Log.w(TAG, "disconnected: " + (cause == null ? "clean EOF" : cause.getMessage()));
        service.onTurnPassed();
        activeTurnIndex = -1;
        lastApproachSpokenTurn = -1;
        lastImminentSpokenTurn = -1;
        service.onTransportDisconnected();
    }

    /**
     * Speak the one-time route-start cue: the departure instruction (from
     * {@link Packet.RouteStart#startLabel}) plus the maneuver the rider is approaching. Called from
     * the first PROGRESS that resolves a cached bundle, so {@code currentTurn} is the live upcoming
     * turn and {@code pr.distanceToTurnM} its live distance — no dependency on the already-passed
     * leading turns (which the phone now ships last). Falls back to "Start route" when the phone
     * didn't supply a departure label.
     */
    private void maybeSpeakInitialDirection(Packet.Progress pr, Packet.TurnBundle currentTurn) {
        initialDirectionSpoken = true;
        // routeStartLabel is the bare departure street name (or empty when the road is unnamed).
        boolean haveStreet = routeStartLabel != null && !routeStartLabel.isEmpty();
        if (currentTurn.kind == com.goodanser.osmglass.protocol.TurnKind.ARRIVE) {
            speak(haveStreet ? "Head on " + routeStartLabel + ", you have arrived" : "You have arrived",
                "initial-" + pr.routeId);
            return;
        }
        String maneuver = TtsSpeaker.utteranceFor(
            TtsSpeaker.Cue.IMMINENT, currentTurn.kind, currentTurn.instructionText, 0);
        // utteranceFor(IMMINENT, ...) returns "<phrase> now"; we want just the phrase.
        if (maneuver.endsWith(" now")) maneuver = maneuver.substring(0, maneuver.length() - 4);
        int meters = roundToTen(pr.distanceToTurnM);
        String utterance = haveStreet
            ? "Head on " + routeStartLabel + " for " + meters + " meters, then " + maneuver
            : "In " + meters + " meters, " + maneuver;
        speak(utterance, "initial-" + pr.routeId);
    }

    /** Forward an utterance to the service unless the phone has muted Glass-side TTS. */
    private void speak(String utterance, String utteranceId) {
        if (muteTts) {
            Log.d(TAG, "TTS muted — suppressed: " + utteranceId);
            return;
        }
        service.speak(utterance, utteranceId);
    }

    private String renderField(
        Packet.DisplayConfig.Field field, Packet.Progress pr, Packet.TurnBundle tb) {
        switch (field) {
            case TURN_INSTRUCTION:
                return tb.instructionText == null ? "" : tb.instructionText;
            case DISTANCE_TO_TURN:
                return formatDistance(pr.distanceToTurnM);
            case REMAINING_DISTANCE:
                return formatDistance(pr.remainingDistanceM);
            case ETA:
                return formatDuration(pr.etaSec);
            case SPEED:
                return pr.speedKmh + " km/h";
            default:
                return "";
        }
    }

    private static String formatDuration(int seconds) {
        if (seconds < 60) return seconds + "s";
        int mins = seconds / 60;
        if (mins < 60) return mins + "m";
        int hours = mins / 60;
        int remMin = mins % 60;
        return hours + "h " + remMin + "m";
    }

    private static long key(long routeId, int turnIndex) {
        return (routeId << 32) | (turnIndex & 0xffffffffL);
    }

    private static String formatDistance(int meters) {
        if (meters >= 1000) return (meters / 1000) + "." + ((meters % 1000) / 100) + " km";
        return meters + " m";
    }
}
