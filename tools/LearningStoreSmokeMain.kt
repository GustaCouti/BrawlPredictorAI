import com.brawlpredictor.ai.MotionLearningStore
import com.brawlpredictor.ai.VisualExperience
import com.brawlpredictor.ai.core.OfflineScenarioLab

fun main() {
    val poorContext = FakeContext()
    val poor = MotionLearningStore(poorContext)
    check(poor.horizonSeconds() == 0.18f)
    repeat(12) { poor.recordPredictionError(0.2f) }
    val poorReloaded = MotionLearningStore(poorContext)
    check(poorReloaded.summary().observations == 12L)
    check(poorReloaded.horizonSeconds() < 0.18f) { "poor tracking errors did not shorten horizon: ${poorReloaded.horizonSeconds()}" }

    val goodContext = FakeContext()
    val good = MotionLearningStore(goodContext)
    repeat(24) { good.recordPredictionError(0.002f) }
    val goodReloaded = MotionLearningStore(goodContext)
    check(goodReloaded.summary().observations == 24L)
    check(goodReloaded.horizonSeconds() > 0.18f) { "good tracking errors did not increase horizon: ${goodReloaded.horizonSeconds()}" }
    check(goodReloaded.horizonSeconds() <= 0.30f)

    val batchContext = FakeContext()
    val batch = MotionLearningStore(batchContext)
    batch.recordExperiences(listOf(
        VisualExperience(0.03f, 0.20f, false, "enemy"),
        VisualExperience(0.04f, 0.30f, true, "enemy"),
        VisualExperience(Float.NaN, 0.10f, false, "motion")
    ))
    val batchReloaded = MotionLearningStore(batchContext).summary()
    check(batchReloaded.observations == 2L)
    check(batchReloaded.directionChanges == 1L)
    check(batchReloaded.savedExperiences == 2)

    val report = OfflineScenarioLab.run(10_000, 0xB4A771L)
    batch.saveSimulationTraining(report)
    val afterSimulation = MotionLearningStore(batchContext)
    check(afterSimulation.hasCurrentSimulationTraining())
    check(afterSimulation.summary().simulatedScenarios == 10_000L)
    check(afterSimulation.horizonSeconds() == report.candidateHorizonSeconds)
    println("PASS learning-store logic: persist/reopen, invalid samples, batch journal, direction changes, adaptive horizon, synthetic training metadata")
}
