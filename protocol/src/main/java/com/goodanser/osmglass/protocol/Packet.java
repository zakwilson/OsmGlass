package com.goodanser.osmglass.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Base class for all wire packets. Subclasses are immutable value types — equals/hashCode are
 * defined for round-trip test convenience.
 */
public abstract class Packet {

    public abstract PacketType type();

    public static final class Hello extends Packet {
        public final int protoVersionAccepted; // uint16

        public Hello(int protoVersionAccepted) {
            this.protoVersionAccepted = protoVersionAccepted;
        }

        @Override public PacketType type() { return PacketType.HELLO; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Hello)) return false;
            return protoVersionAccepted == ((Hello) o).protoVersionAccepted;
        }
        @Override public int hashCode() { return Objects.hash(protoVersionAccepted); }
        @Override public String toString() { return "Hello(v=" + protoVersionAccepted + ")"; }
    }

    public static final class RouteStart extends Packet {
        public final long routeId;          // uint32, stored in low 32 bits of long
        public final int totalTurns;        // uint16
        public final String destinationLabel;

        public RouteStart(long routeId, int totalTurns, String destinationLabel) {
            this.routeId = routeId;
            this.totalTurns = totalTurns;
            this.destinationLabel = Objects.requireNonNull(destinationLabel);
        }

        @Override public PacketType type() { return PacketType.ROUTE_START; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof RouteStart)) return false;
            RouteStart r = (RouteStart) o;
            return routeId == r.routeId && totalTurns == r.totalTurns && destinationLabel.equals(r.destinationLabel);
        }
        @Override public int hashCode() { return Objects.hash(routeId, totalTurns, destinationLabel); }
        @Override public String toString() {
            return "RouteStart(id=" + routeId + ", turns=" + totalTurns + ", to=" + destinationLabel + ")";
        }
    }

    public static final class TurnBundle extends Packet {
        public final long routeId;             // uint32
        public final int turnIndex;            // uint16
        public final TurnKind kind;            // uint8 (ordinal)
        public final int distanceFromStartM;   // uint16
        public final String instructionText;
        public final byte[] pngBytes;

        public TurnBundle(long routeId, int turnIndex, TurnKind kind, int distanceFromStartM,
                          String instructionText, byte[] pngBytes) {
            this.routeId = routeId;
            this.turnIndex = turnIndex;
            this.kind = Objects.requireNonNull(kind);
            this.distanceFromStartM = distanceFromStartM;
            this.instructionText = Objects.requireNonNull(instructionText);
            this.pngBytes = Objects.requireNonNull(pngBytes);
        }

        @Override public PacketType type() { return PacketType.TURN_BUNDLE; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof TurnBundle)) return false;
            TurnBundle t = (TurnBundle) o;
            return routeId == t.routeId
                && turnIndex == t.turnIndex
                && kind == t.kind
                && distanceFromStartM == t.distanceFromStartM
                && instructionText.equals(t.instructionText)
                && Arrays.equals(pngBytes, t.pngBytes);
        }
        @Override public int hashCode() {
            int h = Objects.hash(routeId, turnIndex, kind, distanceFromStartM, instructionText);
            return 31 * h + Arrays.hashCode(pngBytes);
        }
        @Override public String toString() {
            return "TurnBundle(id=" + routeId + ", #" + turnIndex + ", " + kind
                + ", " + distanceFromStartM + "m, png=" + pngBytes.length + "B)";
        }
    }

    public static final class Progress extends Packet {
        /** Sentinel for "unknown / no marker" in the marker fields below. */
        public static final int MARKER_NONE = -1;

        public final long routeId;            // uint32
        public final int turnIndex;           // uint16
        public final int distanceToTurnM;     // uint16
        public final short bearingDelta100;   // int16, centidegrees
        public final int speedKmh;            // uint16
        public final int remainingDistanceM;  // uint16, meters from current position to arrival
        public final int etaSec;              // uint16, seconds remaining to arrival
        /** X pixel coord (0..snippetWidth-1) of the position arrow in the current TurnBundle's
         *  snippet bitmap, or {@link #MARKER_NONE} if no marker should be drawn. Set by the phone
         *  using the same projection used to render the snippet. */
        public final int markerPxX;           // uint16, MARKER_NONE encoded as 0xFFFF
        /** Y pixel coord (0..snippetHeight-1) of the position arrow, or {@link #MARKER_NONE}. */
        public final int markerPxY;           // uint16
        /** Marker heading in centidegrees clockwise from north (0..35999), or {@link #MARKER_NONE}
         *  if no bearing is known (Glass draws a circle instead of an oriented arrow). */
        public final int markerBearingDeg100; // uint16

        public Progress(long routeId, int turnIndex, int distanceToTurnM, short bearingDelta100,
                        int speedKmh, int remainingDistanceM, int etaSec) {
            this(routeId, turnIndex, distanceToTurnM, bearingDelta100, speedKmh, remainingDistanceM,
                etaSec, MARKER_NONE, MARKER_NONE, MARKER_NONE);
        }

        public Progress(long routeId, int turnIndex, int distanceToTurnM, short bearingDelta100,
                        int speedKmh, int remainingDistanceM, int etaSec,
                        int markerPxX, int markerPxY, int markerBearingDeg100) {
            this.routeId = routeId;
            this.turnIndex = turnIndex;
            this.distanceToTurnM = distanceToTurnM;
            this.bearingDelta100 = bearingDelta100;
            this.speedKmh = speedKmh;
            this.remainingDistanceM = remainingDistanceM;
            this.etaSec = etaSec;
            this.markerPxX = markerPxX;
            this.markerPxY = markerPxY;
            this.markerBearingDeg100 = markerBearingDeg100;
        }

        @Override public PacketType type() { return PacketType.PROGRESS; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Progress)) return false;
            Progress p = (Progress) o;
            return routeId == p.routeId && turnIndex == p.turnIndex
                && distanceToTurnM == p.distanceToTurnM && bearingDelta100 == p.bearingDelta100
                && speedKmh == p.speedKmh
                && remainingDistanceM == p.remainingDistanceM && etaSec == p.etaSec
                && markerPxX == p.markerPxX && markerPxY == p.markerPxY
                && markerBearingDeg100 == p.markerBearingDeg100;
        }
        @Override public int hashCode() {
            return Objects.hash(routeId, turnIndex, distanceToTurnM, bearingDelta100, speedKmh,
                remainingDistanceM, etaSec, markerPxX, markerPxY, markerBearingDeg100);
        }
        @Override public String toString() {
            return "Progress(id=" + routeId + ", #" + turnIndex + ", " + distanceToTurnM
                + "m, bearing=" + (bearingDelta100 / 100.0) + "°, " + speedKmh + "km/h, rem="
                + remainingDistanceM + "m, eta=" + etaSec + "s, marker="
                + (markerPxX == MARKER_NONE ? "none" : "(" + markerPxX + "," + markerPxY + "," + (markerBearingDeg100 / 100.0) + "°)") + ")";
        }
    }

    /**
     * Phone → Glass: which data field each Glass display slot should render. Sent on connect and
     * whenever the user changes their selection. Glass stores the most recent config and applies it
     * to subsequent updates.
     */
    public static final class DisplayConfig extends Packet {
        public enum Field {
            TURN_INSTRUCTION,
            DISTANCE_TO_TURN,
            REMAINING_DISTANCE,
            ETA,
            SPEED;
            public static Field fromCode(int b) throws ProtocolException {
                Field[] vs = values();
                if (b < 0 || b >= vs.length) throw new ProtocolException("unknown DisplayConfig field " + b);
                return vs[b];
            }
        }

        public final Field topSlot;
        public final Field bottomSlot;
        /** If true, the Glass dispatcher suppresses all TTS announcements. Lets the user rely on
         *  OsmAnd's phone-side voice (e.g. via bone-conduction headphones) without doubling up. */
        public final boolean muteTts;

        public DisplayConfig(Field topSlot, Field bottomSlot) {
            this(topSlot, bottomSlot, false);
        }

        public DisplayConfig(Field topSlot, Field bottomSlot, boolean muteTts) {
            this.topSlot = Objects.requireNonNull(topSlot);
            this.bottomSlot = Objects.requireNonNull(bottomSlot);
            this.muteTts = muteTts;
        }

        @Override public PacketType type() { return PacketType.DISPLAY_CONFIG; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof DisplayConfig)) return false;
            DisplayConfig d = (DisplayConfig) o;
            return topSlot == d.topSlot && bottomSlot == d.bottomSlot && muteTts == d.muteTts;
        }
        @Override public int hashCode() { return Objects.hash(topSlot, bottomSlot, muteTts); }
        @Override public String toString() {
            return "DisplayConfig(top=" + topSlot + ", bottom=" + bottomSlot + ", muteTts=" + muteTts + ")";
        }
    }

    /**
     * Phone → Glass: OsmAnd has issued a voice prompt about an upcoming turn, so the Glass should
     * wake its display and surface the LiveCard even if the rider hasn't crossed the distance
     * threshold that PacketDispatcher normally uses to wake on PROGRESS packets.
     */
    public static final class TurnAlert extends Packet {
        public final long routeId;   // uint32
        public final int turnIndex;  // uint16

        public TurnAlert(long routeId, int turnIndex) {
            this.routeId = routeId;
            this.turnIndex = turnIndex;
        }

        @Override public PacketType type() { return PacketType.TURN_ALERT; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof TurnAlert)) return false;
            TurnAlert a = (TurnAlert) o;
            return routeId == a.routeId && turnIndex == a.turnIndex;
        }
        @Override public int hashCode() { return Objects.hash(routeId, turnIndex); }
        @Override public String toString() { return "TurnAlert(id=" + routeId + ", #" + turnIndex + ")"; }
    }

    public static final class RouteEnd extends Packet {
        public enum Reason { ARRIVED, CANCELLED, OFFROUTE;
            public static Reason fromCode(int b) throws ProtocolException {
                Reason[] vs = values();
                if (b < 0 || b >= vs.length) throw new ProtocolException("unknown RouteEnd reason " + b);
                return vs[b];
            }
        }
        public final long routeId;   // uint32
        public final Reason reason;

        public RouteEnd(long routeId, Reason reason) {
            this.routeId = routeId;
            this.reason = Objects.requireNonNull(reason);
        }

        @Override public PacketType type() { return PacketType.ROUTE_END; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof RouteEnd)) return false;
            RouteEnd r = (RouteEnd) o;
            return routeId == r.routeId && reason == r.reason;
        }
        @Override public int hashCode() { return Objects.hash(routeId, reason); }
        @Override public String toString() { return "RouteEnd(id=" + routeId + ", " + reason + ")"; }
    }

    public static final class Ping extends Packet {
        public final long timestampMs; // uint32

        public Ping(long timestampMs) { this.timestampMs = timestampMs; }
        @Override public PacketType type() { return PacketType.PING; }
        @Override public boolean equals(Object o) {
            return o instanceof Ping && timestampMs == ((Ping) o).timestampMs;
        }
        @Override public int hashCode() { return Long.hashCode(timestampMs); }
        @Override public String toString() { return "Ping(t=" + timestampMs + ")"; }
    }

    public static final class Pong extends Packet {
        public final long echoTimestampMs; // uint32

        public Pong(long echoTimestampMs) { this.echoTimestampMs = echoTimestampMs; }
        @Override public PacketType type() { return PacketType.PONG; }
        @Override public boolean equals(Object o) {
            return o instanceof Pong && echoTimestampMs == ((Pong) o).echoTimestampMs;
        }
        @Override public int hashCode() { return Long.hashCode(echoTimestampMs); }
        @Override public String toString() { return "Pong(t=" + echoTimestampMs + ")"; }
    }
}
