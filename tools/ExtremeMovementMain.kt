import com.brawlpredictor.ai.core.OfflineScenarioLab
import java.util.Locale

/** Synthetic high-speed/random movement stress test. It does not represent real matches. */
fun main() {
    val seeds = listOf(0xA11CE55L, 0xFA57BEEFL, 0x20261019L)
    val reports = seeds.map { OfflineScenarioLab.run(scenarios = 10_000, seed = it, extremeMovement = true) }
    reports.forEachIndexed { i, r ->
        println("CHAOS_${i + 1} seed=${r.seed} scenarios=${r.scenarios} frames=${r.simulatedFrames} " +
            "recall=${"%.5f".format(Locale.US, r.detectionRecall)} " +
            "positionError=${"%.5f".format(Locale.US, r.meanPositionError)} " +
            "switchRate=${"%.5f".format(Locale.US, r.associationSwitchRate)} " +
            "bestHorizon=${r.candidateHorizonSeconds}s " +
            "horizonErrors=${r.horizonErrors.mapValues { "%.5f".format(Locale.US, it.value) }} " +
            "runtime=${r.runtimeMs}ms")
    }
    val total = reports.sumOf { it.scenarios }
    val frames = reports.sumOf { it.simulatedFrames }
    val recall = reports.map { it.detectionRecall }.average()
    val switches = reports.map { it.associationSwitchRate }.average()
    val err = reports.map { it.meanPositionError }.average()
    println("SYNTHETIC_CHAOS_RESULT scenarios=$total frames=$frames avgRecall=${"%.5f".format(Locale.US, recall)} " +
        "avgPositionError=${"%.5f".format(Locale.US, err)} avgIdentitySwitchRate=${"%.5f".format(Locale.US, switches)}")
    println(if (recall >= 0.94 && switches <= 0.02) "TRACKING_THRESHOLDS=PASS" else "TRACKING_THRESHOLDS=NOT_MET; erratic movement exceeds current tracker robustness")
}
