package com.goodanser.osmglass.phone.osmand

import com.goodanser.osmglass.protocol.TurnKind

/**
 * Maps OsmAnd's int turn-type codes (the constants on net.osmand.router.TurnType) to our
 * wire-protocol [TurnKind].
 *
 * The AIDL surface (ADirectionInfo.turnType) carries `TurnType.getValue()` — a plain int in
 * the range 1..14 — without the surrounding TurnType object, so this mapper has access to:
 *   - the int code
 *   - nothing else
 *
 * Fidelity gaps to be aware of when relying on this mapping:
 *
 *  - Roundabout exit number (`TurnType.exitOut`) is **dropped** by OsmAnd's V2 AIDL service
 *    (OsmandAidlApi.java:2081 calls `getValue()` which returns only the int code). We can't
 *    distinguish "1st exit" from "3rd exit" through AIDL — only that it's a roundabout.
 *
 *  - `isLeftSide` is **never set** by the V2 AIDL service (constructor seeds it `false` and the
 *    setter is never called). Treat it as meaningless until OsmAnd is patched.
 *
 *  - Our [TurnKind] enum has no STRAIGHT or ROUNDABOUT ordinals. OsmAnd's `C` (continue) gets
 *    mapped to `null` so callers can skip the bundle; `RNDB`/`RNLB` degrade to the closest
 *    directional turn (KL/KR) as a least-bad approximation. Adding STRAIGHT + ROUNDABOUT to
 *    [TurnKind] would require a proto version bump but would restore fidelity.
 *
 *  - `OFFR` (off-route) is not a turn — caller should treat it as a deviation signal and
 *    re-route, not surface a bundle. Mapped to `null`.
 *
 *  - `TRU` (right U-turn) — our wire protocol only has a single [TurnKind.TU], so left/right
 *    distinction is lost.
 */
object TurnTypeMapping {

    // Mirror the integer constants on net.osmand.router.TurnType so we don't have to depend on
    // the OsmAnd-java module at runtime. Source: OsmAnd/OsmAnd-java/.../router/TurnType.java.
    const val OSMAND_C    = 1   // continue (straight)
    const val OSMAND_TL   = 2
    const val OSMAND_TSLL = 3
    const val OSMAND_TSHL = 4
    const val OSMAND_TR   = 5
    const val OSMAND_TSLR = 6
    const val OSMAND_TSHR = 7
    const val OSMAND_KL   = 8
    const val OSMAND_KR   = 9
    const val OSMAND_TU   = 10
    const val OSMAND_TRU  = 11  // right U-turn
    const val OSMAND_OFFR = 12  // off route
    const val OSMAND_RNDB = 13  // roundabout (right-side traffic)
    const val OSMAND_RNLB = 14  // roundabout (left-side traffic)

    /**
     * Translate an OsmAnd turn-type int to a [TurnKind]. Returns null for codes that should not
     * be surfaced as a turn bundle (continue, off-route, unknown).
     */
    fun fromOsmAndTurnType(value: Int): TurnKind? = when (value) {
        OSMAND_TL   -> TurnKind.TL
        OSMAND_TR   -> TurnKind.TR
        OSMAND_TSLL -> TurnKind.TSLL
        OSMAND_TSLR -> TurnKind.TSLR
        OSMAND_TSHL -> TurnKind.TSHL
        OSMAND_TSHR -> TurnKind.TSHR
        OSMAND_KL   -> TurnKind.KL
        OSMAND_KR   -> TurnKind.KR
        OSMAND_TU, OSMAND_TRU -> TurnKind.TU
        // Roundabouts: degrade to KL/KR — see fidelity-gap note in the class doc.
        OSMAND_RNDB -> TurnKind.KR
        OSMAND_RNLB -> TurnKind.KL
        // C (continue), OFFR (off-route), and unknown codes: caller decides.
        OSMAND_C, OSMAND_OFFR -> null
        else -> null
    }

    /** True iff the AIDL update represents a route deviation rather than a turn. */
    fun isOffRoute(value: Int): Boolean = value == OSMAND_OFFR
}
