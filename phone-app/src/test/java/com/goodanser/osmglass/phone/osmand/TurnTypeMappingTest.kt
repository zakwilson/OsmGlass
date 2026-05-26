package com.goodanser.osmglass.phone.osmand

import com.goodanser.osmglass.protocol.TurnKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TurnTypeMappingTest {

    @Test fun `regular turns map 1to1`() {
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TL)).isEqualTo(TurnKind.TL)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TR)).isEqualTo(TurnKind.TR)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TSLL)).isEqualTo(TurnKind.TSLL)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TSLR)).isEqualTo(TurnKind.TSLR)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TSHL)).isEqualTo(TurnKind.TSHL)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TSHR)).isEqualTo(TurnKind.TSHR)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_KL)).isEqualTo(TurnKind.KL)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_KR)).isEqualTo(TurnKind.KR)
    }

    @Test fun `u-turns collapse left and right into TU`() {
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TU)).isEqualTo(TurnKind.TU)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_TRU)).isEqualTo(TurnKind.TU)
    }

    @Test fun `roundabouts degrade to keep-left or keep-right`() {
        // Fidelity gap: AIDL drops the exit number, so we can only emit a directional hint.
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_RNDB)).isEqualTo(TurnKind.KR)
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_RNLB)).isEqualTo(TurnKind.KL)
    }

    @Test fun `continue and off-route map to null so the caller can skip`() {
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_C)).isNull()
        assertThat(TurnTypeMapping.fromOsmAndTurnType(TurnTypeMapping.OSMAND_OFFR)).isNull()
    }

    @Test fun `unknown codes map to null`() {
        assertThat(TurnTypeMapping.fromOsmAndTurnType(0)).isNull()
        assertThat(TurnTypeMapping.fromOsmAndTurnType(99)).isNull()
        assertThat(TurnTypeMapping.fromOsmAndTurnType(-1)).isNull()
    }

    @Test fun `isOffRoute identifies only the OFFR code`() {
        assertThat(TurnTypeMapping.isOffRoute(TurnTypeMapping.OSMAND_OFFR)).isTrue
        assertThat(TurnTypeMapping.isOffRoute(TurnTypeMapping.OSMAND_TL)).isFalse
        assertThat(TurnTypeMapping.isOffRoute(TurnTypeMapping.OSMAND_C)).isFalse
    }
}
