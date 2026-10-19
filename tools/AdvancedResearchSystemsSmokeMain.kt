import com.brawlpredictor.ai.core.*
import kotlin.math.sin
import kotlin.math.abs

fun main() {
    val assignment = HungarianAssignment.solve(arrayOf(
        doubleArrayOf(10.0, 1.0, 8.0),
        doubleArrayOf(9.0, 7.0, 2.0)
    ))
    check(assignment.contentEquals(intArrayOf(1, 2))) { "Hungarian global optimum failed: ${assignment.contentToString()}" }
    val rectangular = HungarianAssignment.solve(arrayOf(doubleArrayOf(1.0, 8.0), doubleArrayOf(2.0, 1.0), doubleArrayOf(3.0, 4.0)))
    check(rectangular.count { it >= 0 } == 2 && rectangular.filter { it >= 0 }.toSet().size == 2)

    val calibrator = ConformalCalibrator(minimumSamples = 5)
    repeat(20) { i -> calibrator.add(Vec2(.2,.2), Vec2(.2 + i*.001,.2)) }
    check(calibrator.samples() == 20 && calibrator.radius(.8) >= calibrator.radius(.5))

    val tracker = MultiHypothesisTracker()
    val first = tracker.update(listOf(Detection(Vec2(.2,.2),.9), Detection(Vec2(.8,.8),.8)), .1)
    check(first.tracks.size == 2)
    val second = tracker.update(listOf(Detection(Vec2(.23,.2),.9), Detection(Vec2(.77,.8),.8)), .1)
    check(second.tracks.size == 2 && second.tracks.all { it.history.size >= 2 })

    // Appearance signatures help preserve identity when two moving regions cross.
    val appearanceTracker = MultiHypothesisTracker(gate = .35)
    appearanceTracker.update(listOf(Detection(Vec2(.30,.5),.95,listOf(0.0,1.0)), Detection(Vec2(.70,.5),.95,listOf(1.0,0.0))), .1)
    appearanceTracker.update(listOf(Detection(Vec2(.43,.5),.95,listOf(0.0,1.0)), Detection(Vec2(.57,.5),.95,listOf(1.0,0.0))), .1)
    val crossing = appearanceTracker.update(listOf(Detection(Vec2(.56,.5),.95,listOf(0.0,1.0)), Detection(Vec2(.44,.5),.95,listOf(1.0,0.0))), .1)
    check(crossing.tracks.size == 2)
    check(crossing.tracks.first { it.trackId == 1L }.position.x > crossing.tracks.first { it.trackId == 2L }.position.x) { "Appearance association lost identity at crossing" }

    val gru = TinyGruForecaster(seed = 9)
    val sequence = (0 until 12).map { i -> doubleArrayOf(.2+i*.01,.4,.1,0.0) }
    val before = gru.predict(sequence).distance(Vec2(.4,.4))
    repeat(300) { gru.train(sequence, Vec2(.4,.4), .05) }
    val after = gru.predict(sequence).distance(Vec2(.4,.4))
    check(after < before) { "GRU output head did not learn target: before=$before after=$after" }

    val history = (0 until 12).map { i -> MotionSample(Vec2(.2 + i*.012, .4 + sin(i*.2)*.01), i*100L) }
    val ukf = LightweightUkf(MotionState(history.last().position, Vec2(.1,.0)))
    check(ukf.predict(.1).position.finite())
    check(ukf.update(history.last().position).variance > 0.0)
    val attention = TinyAttentionForecaster(seed=4).predict(history.map { doubleArrayOf(it.position.x,it.position.y,.1,.2) })
    check(attention.finite() && attention.x in 0.0..1.0 && attention.y in 0.0..1.0)
    val ensemble = ForecastEnsemble(seed=5).predict(history, 300, 8)
    check(ensemble.candidates.size == 8 && ensemble.uncertainty.isFinite())
    val diffusion = TrajectoryDenoisingSampler(6).sample(history, 300, 5, 5)
    check(diffusion.size == 5 && diffusion.all { it.size == 6 })
    val phd = PHDCountEstimator(); check(phd.update(listOf(.9,.8,.2)) > 0.0)
    val cphd = CardinalityEstimator(); check(cphd.update(listOf(.9,.8,.2)) in 0..48)
    check(AdaptiveTrackingBudget.forFrame(8, 16.0).maxTracks >= 8)

    val simulator = DomainRandomizedMotionSimulator(123)
    val frames = simulator.rollout(500)
    check(frames.size == 500 && frames.all { it.truth.finite() && (it.observation == null || it.observation.finite()) })
    val selfPlay = SyntheticSelfPlay(99).runMatches(10, 50)
    check(abs(selfPlay.getValue("synthetic_observation_rate") + selfPlay.getValue("synthetic_occlusion_rate") - 1.0) < 1e-9)
    println("ADVANCED_RESEARCH_SYSTEMS_OK hungarian=${assignment.contentToString()} conformalSamples=${calibrator.samples()} tracks=${second.tracks.size} gruError=$before->$after syntheticFrames=${selfPlay.getValue("frames")}")
}
