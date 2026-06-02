package com.goodanser.osmglass.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
// Tests in this class are written to allow checked-exception throw signatures.

class CodecTest {

    @Test void roundTripHello() throws Exception {
        Packet.Hello in = new Packet.Hello(0x0001);
        Packet out = Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
    }

    @Test void roundTripRouteStart() throws Exception {
        Packet.RouteStart in = new Packet.RouteStart(0xCAFEBABEL, 42, "Brandenburger Tor");
        assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
    }

    @Test void roundTripRouteStartWithUtf8() throws Exception {
        Packet.RouteStart in = new Packet.RouteStart(1L, 7, "Café Müller — straße");
        assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
    }

    @Test void roundTripRouteStartWithStartLabel() throws Exception {
        Packet.RouteStart in = new Packet.RouteStart(
            0xCAFEBABEL, 42, "Brandenburger Tor", "Head north on Bramfelder Straße");
        Packet.RouteStart out = (Packet.RouteStart) Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
        assertThat(out.startLabel).isEqualTo("Head north on Bramfelder Straße");
    }

    @Test void routeStartDecodesLegacyPayloadWithoutStartLabel() throws Exception {
        // Synthesize a payload with only the original fields (no trailing startLabel pstr).
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(payload);
        out.writeInt(42);                 // routeId
        out.writeShort(5);                // totalTurns
        byte[] dest = "Berlin".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(dest.length);      // destinationLabel pstr
        out.write(dest);
        out.flush();
        Packet.RouteStart decoded =
            (Packet.RouteStart) Codec.decodePayload(PacketType.ROUTE_START, payload.toByteArray());
        assertThat(decoded.routeId).isEqualTo(42L);
        assertThat(decoded.destinationLabel).isEqualTo("Berlin");
        assertThat(decoded.startLabel).isEqualTo("");
    }

    @ParameterizedTest
    @MethodSource("turnBundleSizes")
    void roundTripTurnBundle(int pngSize) throws Exception {
        byte[] png = randomBytes(pngSize, 0xC0FFEEL ^ pngSize);
        Packet.TurnBundle in = new Packet.TurnBundle(
            0xDEADBEEFL, 5, TurnKind.TSLR, 1234, "Slight right onto Prenzlauer Allee", png);
        Packet.TurnBundle out = (Packet.TurnBundle) Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
        assertThat(out.pngBytes).isEqualTo(png); // explicit array compare
    }

    static Stream<Integer> turnBundleSizes() {
        return Stream.of(0, 1, 100, 30_000, 60_000, 120_000, 200_000);
    }

    @Test void roundTripProgress() throws Exception {
        // Legacy 7-arg constructor leaves marker fields as MARKER_NONE.
        Packet.Progress in = new Packet.Progress(7L, 3, 142, (short) -2750, 28, 4321, 900);
        Packet.Progress out = (Packet.Progress) Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
        assertThat(out.markerPxX).isEqualTo(Packet.Progress.MARKER_NONE);
        assertThat(out.markerPxY).isEqualTo(Packet.Progress.MARKER_NONE);
        assertThat(out.markerBearingDeg100).isEqualTo(Packet.Progress.MARKER_NONE);
    }

    @Test void roundTripProgressWithMarker() throws Exception {
        Packet.Progress in = new Packet.Progress(7L, 3, 142, (short) -2750, 28, 4321, 900,
            320, 180, 9000);
        Packet.Progress out = (Packet.Progress) Codec.decode(Codec.encode(in));
        assertThat(out).isEqualTo(in);
        assertThat(out.markerPxX).isEqualTo(320);
        assertThat(out.markerPxY).isEqualTo(180);
        assertThat(out.markerBearingDeg100).isEqualTo(9000);
    }

    @Test void progressDecodesLegacyPayloadWithoutMarker() throws Exception {
        // Synthesize a payload that contains only the original 16 bytes (no marker trailer).
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(payload);
        out.writeInt(42);              // routeId
        out.writeShort(5);             // turnIndex
        out.writeShort(123);           // distanceToTurnM
        out.writeShort(0);             // bearingDelta100
        out.writeShort(20);            // speedKmh
        out.writeShort(800);           // remainingDistanceM
        out.writeShort(180);           // etaSec
        out.flush();
        Packet.Progress decoded = (Packet.Progress) Codec.decodePayload(PacketType.PROGRESS, payload.toByteArray());
        assertThat(decoded.routeId).isEqualTo(42L);
        assertThat(decoded.turnIndex).isEqualTo(5);
        assertThat(decoded.markerPxX).isEqualTo(Packet.Progress.MARKER_NONE);
        assertThat(decoded.markerPxY).isEqualTo(Packet.Progress.MARKER_NONE);
        assertThat(decoded.markerBearingDeg100).isEqualTo(Packet.Progress.MARKER_NONE);
    }

    @Test void roundTripDisplayConfig() throws Exception {
        Packet.DisplayConfig.Field[] fields = Packet.DisplayConfig.Field.values();
        // Exhaustively crossing all four corners is fields^4 — keep it bounded by pairing each
        // corner with a different field and sweeping the start index across the enum.
        int[] wakeSecs = { 0, 15, 60, 0xffff };
        for (int i = 0; i < fields.length; i++) {
            for (boolean mute : new boolean[] { false, true }) {
                Packet.DisplayConfig in = new Packet.DisplayConfig(
                    fields[i],
                    fields[(i + 1) % fields.length],
                    fields[(i + 2) % fields.length],
                    fields[(i + 3) % fields.length],
                    mute,
                    wakeSecs[i % wakeSecs.length]);
                assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
            }
        }
    }

    @Test void displayConfigDecodesLegacyPayloadWithoutScreenWake() throws Exception {
        // Synthesize a payload with the four corner ordinals + muteTts byte but no trailing
        // screenWakeSec — an older phone. It should decode to the default timeout.
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(payload);
        out.writeByte(Packet.DisplayConfig.Field.TURN_INSTRUCTION.ordinal());
        out.writeByte(Packet.DisplayConfig.Field.NONE.ordinal());
        out.writeByte(Packet.DisplayConfig.Field.DISTANCE_TO_TURN.ordinal());
        out.writeByte(Packet.DisplayConfig.Field.NONE.ordinal());
        out.writeByte(1); // muteTts
        out.flush();
        Packet.DisplayConfig decoded =
            (Packet.DisplayConfig) Codec.decodePayload(PacketType.DISPLAY_CONFIG, payload.toByteArray());
        assertThat(decoded.muteTts).isTrue();
        assertThat(decoded.screenWakeSec).isEqualTo(Packet.DisplayConfig.DEFAULT_SCREEN_WAKE_SEC);
    }

    @Test void roundTripTurnAlert() throws Exception {
        Packet.TurnAlert in = new Packet.TurnAlert(0xABCDEF01L, 7);
        assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
    }

    @Test void roundTripRouteEnd() throws Exception {
        for (Packet.RouteEnd.Reason r : Packet.RouteEnd.Reason.values()) {
            Packet.RouteEnd in = new Packet.RouteEnd(99L, r);
            assertThat(Codec.decode(Codec.encode(in))).isEqualTo(in);
        }
    }

    @Test void roundTripPingPong() throws Exception {
        Packet.Ping ping = new Packet.Ping(0xFFFFFFFFL);
        Packet.Pong pong = new Packet.Pong(123_456L);
        assertThat(Codec.decode(Codec.encode(ping))).isEqualTo(ping);
        assertThat(Codec.decode(Codec.encode(pong))).isEqualTo(pong);
    }

    @Test void rejectsBadMagic() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        frame[0] = 0x00;
        assertThatThrownBy(() -> Codec.decode(frame))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("bad magic");
    }

    @Test void rejectsUnsupportedVersion() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        frame[1] = 0x02;
        assertThatThrownBy(() -> Codec.decode(frame))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("protoVersion");
    }

    @Test void rejectsUnknownType() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        frame[2] = 0x55;
        assertThatThrownBy(() -> Codec.decode(frame))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("unknown packet type");
    }

    @Test void rejectsTruncatedFrame() {
        byte[] frame = Codec.encode(new Packet.Ping(1L));
        byte[] truncated = new byte[frame.length - 2];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertThatThrownBy(() -> Codec.decode(truncated))
            .isInstanceOf(ProtocolException.class);
    }

    @Test void packetTypeFromCodeRoundTrip() throws ProtocolException {
        for (PacketType t : PacketType.values()) {
            assertThat(PacketType.fromCode(t.code)).isEqualTo(t);
        }
    }

    @Test void turnKindFromOrdinalRoundTrip() throws ProtocolException {
        for (TurnKind k : TurnKind.values()) {
            assertThat(TurnKind.fromOrdinal(k.ordinal())).isEqualTo(k);
        }
        assertThatThrownBy(() -> TurnKind.fromOrdinal(99)).isInstanceOf(ProtocolException.class);
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
