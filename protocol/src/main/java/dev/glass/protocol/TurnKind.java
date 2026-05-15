package dev.glass.protocol;

/**
 * Turn maneuver kinds matching BRouter's osmand-style turn instructions.
 * Wire-encoded as a single byte equal to the enum ordinal — order MUST NOT change without bumping protoVersion.
 */
public enum TurnKind {
    START,    // route start
    TL,       // turn left
    TR,       // turn right
    TSLL,     // turn slight left
    TSLR,     // turn slight right
    TSHL,     // turn sharp left
    TSHR,     // turn sharp right
    KL,       // keep left
    KR,       // keep right
    TU,       // u-turn
    ARRIVE;   // route end / arrived

    public static TurnKind fromOrdinal(int ord) throws ProtocolException {
        TurnKind[] vs = values();
        if (ord < 0 || ord >= vs.length) {
            throw new ProtocolException("unknown TurnKind ordinal " + ord);
        }
        return vs[ord];
    }
}
