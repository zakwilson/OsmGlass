package dev.glass.phone.osmand.spike

import dev.glass.phone.osmand.TurnTypeMapping
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import kotlin.math.max
import kotlin.math.min

/**
 * Records nav-update cadence (spike glass-nav-hva) and voice-router messages (spike glass-nav-51h)
 * to flat files in [outputDir]. Designed for one capture session: instantiate, feed events via
 * [onNavUpdate] / [onVoice], call [finish] when the simulated ride is stopped.
 */
class SpikeRecorder(private val outputDir: File) {

    private val startedAtMs = System.currentTimeMillis()
    private val navCsv: PrintWriter
    private val voiceJsonl: PrintWriter

    @Volatile var navEvents = 0; private set
    @Volatile var voiceEvents = 0; private set

    private var lastNavWallMs = -1L
    private var lastDistanceTo = Int.MAX_VALUE
    private var minGapMs = Long.MAX_VALUE
    private var maxGapMs = 0L
    private var monotonicityViolations = 0
    private var turnTypeChanges = 0
    private var lastTurnType = Int.MIN_VALUE

    init {
        outputDir.mkdirs()
        navCsv = PrintWriter(FileWriter(File(outputDir, "hva-cadence.csv"))).apply {
            println("t_ms_since_start,wall_ms,gap_ms,distance_to_m,turn_type_int,turn_kind_mapped,is_left_side_RAW,is_off_route")
        }
        voiceJsonl = PrintWriter(FileWriter(File(outputDir, "51h-voice.jsonl")))
    }

    @Synchronized
    fun onNavUpdate(info: ADirectionInfo) {
        val now = System.currentTimeMillis()
        val sinceStart = now - startedAtMs
        val gap = if (lastNavWallMs < 0) 0 else now - lastNavWallMs
        if (lastNavWallMs >= 0) {
            minGapMs = min(minGapMs, gap)
            maxGapMs = max(maxGapMs, gap)
        }
        val dist = info.distanceTo
        // Monotonicity: distanceTo should generally decrease (until a new turn becomes the target,
        // where it resets upward). Count strict increases when turnType doesn't change as anomalies.
        val turnType = info.turnType
        if (turnType == lastTurnType && dist > lastDistanceTo + 5) {
            monotonicityViolations++
        }
        if (turnType != lastTurnType) {
            turnTypeChanges++
            lastTurnType = turnType
        }
        val mapped = TurnTypeMapping.fromOsmAndTurnType(turnType)?.name ?: "<null>"
        val offRoute = TurnTypeMapping.isOffRoute(turnType)
        navCsv.println("$sinceStart,$now,$gap,$dist,$turnType,$mapped,${info.isLeftSide},$offRoute")
        navCsv.flush()
        lastNavWallMs = now
        lastDistanceTo = dist
        navEvents++
    }

    @Synchronized
    fun onVoice(params: OnVoiceNavigationParams) {
        val now = System.currentTimeMillis()
        val sinceStart = now - startedAtMs
        val obj = JSONObject().apply {
            put("t_ms_since_start", sinceStart)
            put("wall_ms", now)
            put("commands", JSONArray(params.commands ?: emptyList<String>()))
            put("played", JSONArray(params.played ?: emptyList<String>()))
            put("composed", (params.commands ?: emptyList()).joinToString(" "))
        }
        voiceJsonl.println(obj.toString())
        voiceJsonl.flush()
        voiceEvents++
    }

    /**
     * Closes recorders and writes hva-summary.txt + 51h-summary.txt.
     */
    @Synchronized
    fun finish() {
        navCsv.close()
        voiceJsonl.close()
        val durationS = (System.currentTimeMillis() - startedAtMs) / 1000.0
        val hz = if (durationS > 0) navEvents / durationS else 0.0
        File(outputDir, "hva-summary.txt").writeText(
            buildString {
                appendLine("# Spike glass-nav-hva — updateNavigationInfo cadence/content")
                appendLine()
                appendLine("session_duration_s = ${"%.1f".format(durationS)}")
                appendLine("events_total       = $navEvents")
                appendLine("avg_hz             = ${"%.2f".format(hz)}")
                appendLine("min_gap_ms         = ${if (minGapMs == Long.MAX_VALUE) "n/a" else minGapMs}")
                appendLine("max_gap_ms         = $maxGapMs")
                appendLine("turn_type_changes  = $turnTypeChanges")
                appendLine("monotonicity_anomalies = $monotonicityViolations")
                appendLine()
                appendLine("Decision criteria:")
                appendLine("- avg_hz >= ~1.0 means cadence is at least as good as the current phone-side 1Hz Progress emitter.")
                appendLine("- max_gap_ms should stay under ~2000ms; large gaps mean we'd starve the Glass display.")
                appendLine("- monotonicity_anomalies > a few suggests we'd need our own smoothing.")
                appendLine("- A NULL/empty 'turn_kind_mapped' column means OsmAnd reported continue/off-route — review hva-cadence.csv for frequency.")
            }
        )
        File(outputDir, "51h-summary.txt").writeText(
            buildString {
                appendLine("# Spike glass-nav-51h — voice router messages")
                appendLine()
                appendLine("session_duration_s = ${"%.1f".format(durationS)}")
                appendLine("voice_events_total = $voiceEvents")
                appendLine()
                appendLine("Decision criteria:")
                appendLine("- Inspect 51h-voice.jsonl: do the 'composed' sentences read naturally enough to pipe straight to TTS on Glass?")
                appendLine("- Compare with our current Glass-side composer 'maybeSpeakInitialDirection' which emits:")
                appendLine("    '<start.instructionText> for <first.distanceFromStartM> meters, then <maneuver phrase>'")
                appendLine("- If yes → repurpose the protocol to carry pre-composed strings (option A in the spike issue).")
                appendLine("- If OsmAnd's phrasing is awkward → keep our composer; just use ADirectionInfo data (option B).")
            }
        )
    }
}
