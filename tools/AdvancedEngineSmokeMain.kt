import com.brawlpredictor.ai.core.*

fun main() {
    val ai = AdvancedPredictionEngine(123)
    val history = listOf(
        MotionSample(Vec2(.20,.50), 1000),
        MotionSample(Vec2(.22,.50), 1100),
        MotionSample(Vec2(.24,.51), 1200),
        MotionSample(Vec2(.26,.52), 1300)
    )
    val forecast = ai.forecast(7, history, 160, minConfidence = .01)
    check(!forecast.abstained && forecast.candidates.isNotEmpty()) { "Forecast should return alternatives for stable movement" }
    check(forecast.candidates.size <= 5 && forecast.candidates.all { it.position.finite() && it.probability in 0.0..1.0 })
    check(kotlin.math.abs(forecast.candidates.sumOf { it.probability } - 1.0) < 1e-6)
    check(ai.reachable(Vec2(.25,.5), Vec2(.2,.5), .5, .2))
    check(!ai.reachable(Vec2(.9,.9), Vec2(.1,.1), .1, .2))
    check(ai.intercept(Vec2(0.0,0.0), MotionState(Vec2(.5,.5),Vec2(0.0,0.0)), 1.0) != null)
    val assign = ai.hungarianLikeAssignment(arrayOf(doubleArrayOf(1.0,8.0),doubleArrayOf(7.0,1.0)))
    check(assign.contentEquals(intArrayOf(0,1))) { "Global assignment mismatch: ${assign.contentToString()}" }
    val path = ai.aStar(5,5,GridCell(0,0),GridCell(4,4)) { it != GridCell(1,0) }
    check(path != null && path.cells.first() == GridCell(0,0) && path.cells.last() == GridCell(4,4))
    val hmm = ai.hmmModeProbabilities(7,doubleArrayOf(.8,.1,.05,.05))
    check(kotlin.math.abs(hmm.sum()-1.0) < 1e-8)
    check(ai.fusedEvidence(ObservationEvidence(.9,.8,.4,.4)) > ai.fusedEvidence(ObservationEvidence(.1,.1,.1,.1)))
    check(ai.monteCarloReachability(Vec2(.5,.5),.2,.3,emptyList(),50).size == 50)
    val smoothed = ai.rtsSmooth(listOf(MotionState(Vec2(.1,.1),Vec2(.1,0.0)),MotionState(Vec2(.2,.1),Vec2(.2,0.0)),MotionState(Vec2(.3,.1),Vec2(.3,0.0))))
    check(smoothed.size == 3 && smoothed.all { it.position.finite() })
    val kf = KalmanMotionFilter(MotionState(Vec2(.2,.3),Vec2(.1,.0)))
    check(kf.predict(.1).position.finite())
    check(kf.update(Vec2(.22,.3)).position.finite())
    val imm = ImmMotionFilter(MotionState(Vec2(.2,.3),Vec2(.1,.0)))
    val immState = imm.update(Vec2(.21,.3),.1, .2)
    check(immState.position.finite() && kotlin.math.abs(imm.probabilities().values.sum()-1.0)<1e-6)
    repeat(25) { i -> ai.observeOutcome("cv",Vec2(.2,.2),Vec2(.21 + i*.0001,.2)) }
    println("ADVANCED_ENGINE_SMOKE_OK forecastCandidates=${forecast.candidates.size} confidence=${"%.3f".format(forecast.confidence)} pathLength=${path.cells.size}")
}
