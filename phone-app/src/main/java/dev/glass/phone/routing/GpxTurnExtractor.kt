package dev.glass.phone.routing

import dev.glass.protocol.TurnKind
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.io.StringReader

/**
 * Parses BRouter osmand-mode (`turnInstructionMode=3`) GPX into a list of {@link Turn}s plus the
 * full track polyline.
 *
 * Expected GPX shape:
 *   <rte>
 *     <rtept lat="..." lon="...">
 *       <desc>...</desc>
 *       <extensions>
 *         <turn>TL</turn>           (absent for start/destination)
 *         <turn-angle>-87</turn-angle>
 *         <offset>23</offset>       (index into the trkpt list)
 *       </extensions>
 *     </rtept>
 *     ...
 *   </rte>
 *   <trk><trkseg>
 *     <trkpt lat="..." lon="..."/>
 *     ...
 *   </trkseg></trk>
 *
 * The first rtept (desc=start) becomes a {@link TurnKind#START} turn at distance 0.
 * Each interior rtept maps via its <turn> element. The final rtept (desc=destination)
 * becomes a {@link TurnKind#ARRIVE} at the polyline end.
 */
class GpxTurnExtractor {

    data class Result(
        val track: List<LatLng>,
        val turns: List<Turn>,
    )

    fun parse(gpx: String): Result = parse(StringReader(gpx))

    fun parse(reader: Reader): Result {
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        val rtepts = mutableListOf<RtePt>()
        val trkpts = mutableListOf<LatLng>()
        var inRte = false
        var inTrk = false
        var currentRte: RtePt? = null
        var currentText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "rte" -> inRte = true
                    "trk" -> inTrk = true
                    "rtept" -> if (inRte) {
                        currentRte = RtePt(
                            lat = parser.getAttributeValue(null, "lat").toDouble(),
                            lon = parser.getAttributeValue(null, "lon").toDouble(),
                        )
                    }
                    "trkpt" -> if (inTrk) {
                        trkpts += LatLng(
                            parser.getAttributeValue(null, "lat").toDouble(),
                            parser.getAttributeValue(null, "lon").toDouble(),
                        )
                    }
                    "desc", "turn", "offset" -> currentText = StringBuilder()
                }
                XmlPullParser.TEXT -> if (!parser.isWhitespace) currentText.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "rte" -> inRte = false
                    "trk" -> inTrk = false
                    "rtept" -> {
                        currentRte?.let { rtepts += it }
                        currentRte = null
                    }
                    "desc" -> currentRte?.let { it.desc = currentText.toString().trim() }
                    "turn" -> currentRte?.let { it.turn = currentText.toString().trim() }
                    "offset" -> currentRte?.let {
                        it.offset = currentText.toString().trim().toIntOrNull() ?: -1
                    }
                }
            }
            event = parser.next()
        }

        val cum = cumulativeMeters(trkpts)
        val turns = mutableListOf<Turn>()
        rtepts.forEachIndexed { idx, rp ->
            val kind = mapKind(rp)
            val distM = if (rp.offset in 0 until cum.size) cum[rp.offset].toInt()
            else if (idx == 0) 0
            else cum.lastOrNull()?.toInt() ?: 0
            val instruction = when {
                rp.desc.isNullOrBlank() -> kind.glyph()
                else -> rp.desc!!
            }
            turns += Turn(
                seq = idx,
                lat = rp.lat,
                lon = rp.lon,
                kind = kind,
                distanceFromStartM = distM,
                instruction = instruction,
            )
        }
        return Result(track = trkpts, turns = turns)
    }

    private fun mapKind(rp: RtePt): TurnKind {
        val descLower = rp.desc?.trim()?.lowercase() ?: ""
        if (descLower == "start") return TurnKind.START
        if (descLower == "destination") return TurnKind.ARRIVE
        return when (rp.turn?.trim()?.uppercase()) {
            "TL" -> TurnKind.TL
            "TR" -> TurnKind.TR
            "TSLL" -> TurnKind.TSLL
            "TSLR" -> TurnKind.TSLR
            "TSHL" -> TurnKind.TSHL
            "TSHR" -> TurnKind.TSHR
            "KL" -> TurnKind.KL
            "KR" -> TurnKind.KR
            "TU" -> TurnKind.TU
            else -> TurnKind.START // unknown / no turn — treat as straight; UI will dim
        }
    }

    private class RtePt(
        val lat: Double,
        val lon: Double,
        var desc: String? = null,
        var turn: String? = null,
        var offset: Int = -1,
    )
}
