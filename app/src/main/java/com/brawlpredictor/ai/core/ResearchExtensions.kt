package com.brawlpredictor.ai.core

import kotlin.math.*
import kotlin.random.Random

/** Lightweight unscented-style nonlinear motion estimator. Sigma points are propagated through a bounded turn/acceleration model. */
class LightweightUkf(initial: MotionState, private val alpha: Double = 0.35) {
    var state: MotionState = initial; private set
    private var covariance = initial.variance.coerceIn(1e-5, .5)
    fun predict(dtSeconds: Double, turnRate: Double = 0.0, processNoise: Double = .002): MotionState {
        val dt = dtSeconds.coerceIn(0.0, .5); val spread = sqrt(covariance.coerceAtLeast(1e-6)) * alpha.coerceIn(.05, 1.0)
        val points = listOf(state.position, state.position + Vec2(spread, 0.0), state.position - Vec2(spread, 0.0), state.position + Vec2(0.0, spread), state.position - Vec2(0.0, spread))
        val speed = state.velocity.length(); val angle = atan2(state.velocity.y, state.velocity.x) + turnRate.coerceIn(-4.0, 4.0) * dt
        val propagated = points.mapIndexed { i, p -> if (i == 0) (p + Vec2(cos(angle), sin(angle)) * (speed * dt) + state.acceleration * (.5 * dt * dt)).clamp() else (p + Vec2(cos(angle), sin(angle)) * (speed * dt)).clamp() }
        val mean = Vec2(propagated.sumOf { it.x } / propagated.size, propagated.sumOf { it.y } / propagated.size)
        covariance = (propagated.sumOf { it.distance(mean).pow(2) } / propagated.size + processNoise.coerceIn(1e-5, .1)).coerceIn(1e-5, .5)
        state = state.copy(position = mean, velocity = Vec2(cos(angle), sin(angle)) * speed, variance = covariance); return state
    }
    fun update(measured: Vec2, measurementNoise: Double = .004): MotionState {
        val r = measurementNoise.coerceIn(1e-5, .5); val gain = covariance / (covariance + r); val residual = measured.clamp() - state.position
        state = state.copy(position = (state.position + residual * gain).clamp(), velocity = (state.velocity + residual * (gain * 2.0)).let { Vec2(it.x.coerceIn(-1.5,1.5), it.y.coerceIn(-1.5,1.5)) }, variance = ((1-gain)*covariance).coerceIn(1e-5,.5))
        covariance = state.variance; return state
    }
}

/** Bernoulli-mixture/PHD-style multi-target count estimator; estimates count, not persistent identities. */
class PHDCountEstimator(private val survival: Double = .94, private val clutterRate: Double = .12) {
    private var intensity = 0.0
    fun update(detectionScores: List<Double>, missedTrackMass: Double = 0.0): Double {
        val predicted = intensity * survival.coerceIn(.1, 1.0) + missedTrackMass.coerceAtLeast(0.0)
        val evidence = detectionScores.sumOf { s -> val p = s.coerceIn(0.0,1.0); (p / (1.0 + clutterRate.coerceAtLeast(.001))) }
        intensity = (predicted * .35 + evidence * .65).coerceIn(0.0, 64.0)
        return intensity
    }
    fun estimatedCount() = intensity.roundToInt().coerceIn(0,64)
    fun reset() { intensity = 0.0 }
}

/** CPHD-inspired cardinality distribution with bounded state and clutter suppression. */
class CardinalityEstimator(private val maxTargets: Int = 48) {
    private var distribution = DoubleArray(maxTargets.coerceIn(1,64)+1).also { it[0] = 1.0 }
    fun update(detectionScores: List<Double>, missedProbability: Double = .08): Int {
        val expected = detectionScores.sumOf { it.coerceIn(0.0,1.0) } * (1.0 - missedProbability.coerceIn(0.0,.9))
        val n = distribution.lastIndex; val next = DoubleArray(n+1)
        for (k in 0..n) next[k] = exp(-abs(k-expected)/1.8) * (0.15 + distribution[k]*0.85)
        val sum = next.sum().coerceAtLeast(1e-12); for (i in next.indices) next[i] /= sum; distribution = next
        return distribution.indices.maxByOrNull { distribution[it] } ?: 0
    }
    fun probabilities() = distribution.copyOf()
}

/** Tiny causal self-attention forecaster. It is initialized randomly and needs labeled training to become useful. */
class TinyAttentionForecaster(private val featureSize: Int = 4, private val hidden: Int = 12, seed: Int = 31) {
    private val rng = Random(seed); private val wq = weights(); private val wk = weights(); private val wv = weights(); private val wx = DoubleArray(hidden+1) { rng.nextDouble(-.1,.1) }; private val wy = DoubleArray(hidden+1) { rng.nextDouble(-.1,.1) }
    private fun weights() = Array(hidden) { DoubleArray(featureSize) { rng.nextDouble(-.2,.2) } }
    private fun encode(row: DoubleArray, w: Array<DoubleArray>) = DoubleArray(hidden) { i -> tanh((0 until featureSize).sumOf { j -> w[i][j] * row.getOrElse(j){0.0}.coerceIn(-2.0,2.0) }) }
    fun predict(sequence: List<DoubleArray>): Vec2 {
        if (sequence.isEmpty()) return Vec2(.5,.5)
        val seq = sequence.takeLast(32); val qs = seq.map { encode(it,wq) }; val ks = seq.map { encode(it,wk) }; val vs = seq.map { encode(it,wv) }; val q = qs.last()
        val logits = ks.map { k -> q.indices.sumOf { q[it]*k[it] } / sqrt(hidden.toDouble()) }; val max = logits.maxOrNull() ?: 0.0; val ex = logits.map { exp((it-max).coerceIn(-20.0,0.0)) }; val sum=ex.sum().coerceAtLeast(1e-9)
        val pooled = DoubleArray(hidden) { j -> vs.indices.sumOf { i -> ex[i]/sum*vs[i][j] } }; val f=doubleArrayOf(1.0)+pooled
        return Vec2(sigmoid(wx.indices.sumOf{wx[it]*f[it]}), sigmoid(wy.indices.sumOf{wy[it]*f[it]}))
    }
    private fun sigmoid(v:Double)=1.0/(1.0+exp(-v.coerceIn(-25.0,25.0)))
}

/** Monte-Carlo dropout-style uncertainty proxy using independently perturbed small motion hypotheses. */
class ForecastEnsemble(private val members: Int = 7, seed: Int = 53) {
    private val rng=Random(seed)
    data class Result(val mean: Vec2, val candidates: List<Vec2>, val uncertainty: Double)
    fun predict(history: List<MotionSample>, horizonMs: Long, samples: Int = members): Result {
        if(history.isEmpty()) return Result(Vec2(.5,.5), listOf(Vec2(.5,.5)), 1.0)
        val last=history.last().position; val prev=history.getOrNull(history.lastIndex-1)?.position ?: last; val dt=(history.lastOrNull()?.timeMs?.minus(history.getOrNull(history.lastIndex-1)?.timeMs ?: history.last().timeMs) ?: 100L).coerceAtLeast(1L)/1000.0
        val v=(last-prev)*(1.0/dt); val t=(horizonMs.coerceIn(0,5000)/1000.0); val count=samples.coerceIn(2,32)
        val candidates=List(count){ val noise=Vec2(rng.nextDouble(-.025,.025),rng.nextDouble(-.025,.025)); (last+v*t*rng.nextDouble(.65,1.25)+noise).clamp() }
        val mean=Vec2(candidates.sumOf{it.x}/count,candidates.sumOf{it.y}/count); val uncertainty=sqrt(candidates.sumOf{it.distance(mean).pow(2)}/count)
        return Result(mean,candidates,uncertainty)
    }
}

/** Diffusion-inspired iterative denoising sampler for multiple plausible future positions; heuristic, not a trained diffusion network. */
class TrajectoryDenoisingSampler(seed: Int = 71) {
    private val rng=Random(seed)
    fun sample(history: List<MotionSample>, horizonMs: Long, count: Int = 5, denoiseSteps: Int = 6): List<List<Vec2>> {
        if(history.isEmpty()) return emptyList(); val last=history.last().position; val prev=history.getOrNull(history.lastIndex-1)?.position ?: last
        val dt=((history.last().timeMs-(history.getOrNull(history.lastIndex-1)?.timeMs ?: history.last().timeMs)).coerceAtLeast(1L))/1000.0
        val velocity=(last-prev)*(1.0/dt); val horizon=horizonMs.coerceIn(0,5000)/1000.0
        return List(count.coerceIn(1,12)) { branch -> var p=(last+velocity*horizon+Vec2(rng.nextDouble(-.12,.12),rng.nextDouble(-.12,.12))).clamp(); val path=mutableListOf(last)
            repeat(denoiseSteps.coerceIn(1,16)) { step -> val strength=(1.0-step.toDouble()/denoiseSteps.coerceAtLeast(1))*.035; val target=(last+velocity*horizon*(.7+branch*.06)).clamp(); p=(p+(target-p)*.35+Vec2(rng.nextDouble(-strength,strength),rng.nextDouble(-strength,strength))).clamp(); path.add(p) }; path }
    }
}

/** Dynamic per-frame budget: keeps tracking work bounded while allowing more than three simultaneous targets. */
object AdaptiveTrackingBudget {
    data class Budget(val maxTracks:Int,val maxBlobs:Int,val maxForecasts:Int)
    fun forFrame(detectionCount:Int, frameBudgetMs:Double, requested:Int=48):Budget {
        val targetCount=detectionCount.coerceIn(0,64); val pressure=(frameBudgetMs.coerceIn(1.0,80.0)/16.7).coerceIn(.25,3.0)
        val cap=(maxOf(3,targetCount+8)*pressure).roundToInt().coerceIn(8,64).coerceAtMost(requested.coerceIn(3,64))
        return Budget(cap, (cap*1.5).roundToInt().coerceIn(12,96), (targetCount.coerceAtLeast(3)).coerceAtMost(16))
    }
}
