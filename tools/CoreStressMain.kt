import com.brawlpredictor.ai.core.OfflineScenarioLab

/** Repeatable multi-seed stress check; synthetic scenarios are not real matches or real players. */
fun main() {
    val seeds = listOf(0xB4A771L, 0x1234ABCDL, 0x65ED2099L, 0xDEADBEEFL, 0x20261018L)
    val reports = seeds.map { seed -> OfflineScenarioLab.run(scenarios = 10_000, seed = seed) }
    reports.forEachIndexed { index, report ->
        println("SEED_${index + 1}=${report.seed} scenarios=${report.scenarios} frames=${report.simulatedFrames} " +
            "recall=${"%.5f".format(java.util.Locale.US, report.detectionRecall)} " +
            "positionError=${"%.5f".format(java.util.Locale.US, report.meanPositionError)} " +
            "switchRate=${"%.5f".format(java.util.Locale.US, report.associationSwitchRate)} " +
            "bestHorizon=${report.candidateHorizonSeconds}s runtime=${report.runtimeMs}ms")
        check(report.detectionRecall >= 0.94f) { "Recall threshold failed for seed ${report.seed}" }
        check(report.meanPositionError <= 0.02f) { "Position error threshold failed for seed ${report.seed}" }
        check(report.associationSwitchRate <= 0.02f) { "Identity switch threshold failed for seed ${report.seed}" }
    }
    println("PASS: ${reports.size} seeds, ${reports.sumOf { it.scenarios }} synthetic scenarios, ${reports.sumOf { it.simulatedFrames }} frames")
}
