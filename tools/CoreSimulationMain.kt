import com.brawlpredictor.ai.core.EnemyNameScorer
import com.brawlpredictor.ai.core.EnemyObservation
import com.brawlpredictor.ai.core.EnemyTrackingCore
import com.brawlpredictor.ai.core.OfflineScenarioLab
import com.brawlpredictor.ai.core.MotionHint

private fun candidate(name: String, x: Float, y: Float, t: Long) = EnemyObservation(
    name, x, y, (x - 0.03f).coerceAtLeast(0f), (y - 0.05f).coerceAtLeast(0f),
    (x + 0.03f).coerceAtMost(1f), (y - 0.03f).coerceAtLeast(0.001f), 0.90f, 0.25f, t
)

fun main() {
    val candidateScore = EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.22f)
    check(candidateScore != null && candidateScore >= 0.54f) { "Plausible red name was rejected" }
    check(EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.02f) == null) { "Non-red text was accepted" }
    check(EnemyNameScorer.score("BUY", 20, 100, 600, 180, 1080, 1920, 0.42f) == null) { "Large HUD banner was accepted" }
    check(EnemyNameScorer.isRedPixel(0xFFFF2020.toInt()))
    check(EnemyNameScorer.isRedPixel(0xFFF2B6CA.toInt())) { "Pink enemy-name hue was rejected" }
    check(!EnemyNameScorer.isRedPixel(0xFFFFFFFF.toInt()))
    val weakColor = EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.04f)
    check(weakColor != null && weakColor < 0.86f) { "Weak-color text should need temporal confirmation" }
    check(EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.015f) == null)
    val healthBarContext = EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.03f, 0.20f)
    check(healthBarContext != null) { "Nearby bar evidence should support pink/anti-aliased name text" }
    check(EnemyNameScorer.score("3920", 100, 300, 175, 320, 1080, 1920, 0.25f) == null) { "Health numbers should not be treated as player names" }
    val tracker = EnemyTrackingCore()
    val start = tracker.update(listOf(candidate("Alpha", .20f, .40f, 1000L)), 1000L, .56f).single()
    val moved = tracker.update(listOf(candidate("Alph0", .23f, .41f, 1160L)), 1160L, .56f)
        .minByOrNull { kotlin.math.abs(it.x - .23f) } ?: error("Target disappeared after one noisy OCR reading")
    check(start.trackId == moved.trackId) { "Target identity changed across a one-character OCR error" }
    val occluded = tracker.update(emptyList(), 1320L, .56f)
    check(occluded.any { it.trackId == start.trackId && it.predictedOnly }) { "Brief occlusion did not produce a short prediction" }
    check(tracker.update(emptyList(), 2300L, .56f).none { it.trackId == start.trackId }) { "Expired track remained visible too long" }

    val turnTracker = EnemyTrackingCore()
    turnTracker.update(listOf(candidate("Dodger", .20f, .40f, 1000L)), 1000L, .56f)
    turnTracker.update(listOf(candidate("Dodger", .30f, .40f, 1160L)), 1160L, .56f)
    val reversal = turnTracker.update(listOf(candidate("Dodger", .20f, .40f, 1320L)), 1320L, .56f).single()
    check(reversal.zigzagScore > 0f) { "Abrupt direction reversal was not recorded" }

    val motionTracker = EnemyTrackingCore()
    val named = motionTracker.update(listOf(candidate("TrackedEnemy", .40f, .40f, 2000L)), 2000L, .56f).single()
    val refined = motionTracker.refineWithMotion(
        listOf(MotionHint(.42f, .41f, .1f, .05f, .72f, .04f, .05f)), 2125L, .56f
    )
    check(refined.size == 1 && refined.single().trackId == named.trackId && refined.single().predictedOnly)
    motionTracker.clear()
    check(motionTracker.refineWithMotion(listOf(MotionHint(.5f, .5f, .1f, .1f, .9f, .04f, .05f)), 2200L, .56f).isEmpty())

    val report = OfflineScenarioLab.run(10_000, 0xB4A771L)
    check(report.detectionRecall >= 0.94f) { "Low synthetic detection recall: ${report.detectionRecall}" }
    check(report.associationSwitchRate <= 0.02f) { "High synthetic identity-switch rate: ${report.associationSwitchRate}" }
    check(report.meanPositionError <= 0.02f) { "High synthetic location error: ${report.meanPositionError}" }
    println("SCENARIOS=${report.scenarios}")
    println("FRAMES=${report.simulatedFrames}")
    println("VISIBLE_OBSERVATIONS=${report.visibleObservations}")
    println("ASSOCIATED_OBSERVATIONS=${report.associatedObservations}")
    println("RECALL=${report.detectionRecall}")
    println("MEAN_POSITION_ERROR=${report.meanPositionError}")
    println("IDENTITY_SWITCH_RATE=${report.associationSwitchRate}")
    println("TRAINED_HORIZON_S=${report.candidateHorizonSeconds}")
    println("HORIZON_ERRORS=${report.horizonErrors}")
    println("RUNTIME_MS=${report.runtimeMs}")
}
