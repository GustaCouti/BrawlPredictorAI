import com.brawlpredictor.ai.core.*

fun main() {
    val engine = AdvancedPredictionEngine(seed = 101)
    val active = (1L..8L).toSet()
    for (id in active) {
        val offset = id * .015
        val history = (0 until 8).map { i ->
            MotionSample(Vec2((.1 + offset + i * .006).coerceIn(0.0, .95), (.2 + offset + i * .002).coerceIn(0.0, .95)), i * 80L)
        }
        val result = engine.forecast(id, history, 250, minConfidence = 0.0)
        check(result.confidence.isFinite() && result.confidence in 0.0..1.0)
        check(result.candidates.size <= 5)
        check(result.candidates.all { it.position.finite() && it.probability.isFinite() && it.probability >= 0.0 })
        engine.observeActual(id, history.last().position + Vec2(.002, .001))
    }
    engine.retainTracks(setOf(1L, 2L, 3L))
    val malformed = engine.forecast(999L, listOf(
        MotionSample(Vec2(Double.NaN, 0.0), 0L),
        MotionSample(Vec2(.2, .3), 80L),
        MotionSample(Vec2(.21, .31), 160L)
    ), 180, minConfidence = 0.0)
    check(malformed.candidates.all { it.position.finite() })
    println("VARIABLE_TARGET_FORECAST_OK targets=8 pruning=true finite=true")
}
