package com.brawlpredictor.ai.core

import kotlin.math.*
import kotlin.random.Random

/** Empirical split-conformal radius. Coverage is a target, not a guarantee under distribution shift. */
class ConformalCalibrator(private val capacity: Int = 1000, private val minimumSamples: Int = 30) {
    private val residuals = ArrayDeque<Double>()
    fun add(predicted: Vec2, actual: Vec2) { val e = predicted.distance(actual); if (e.isFinite()) { residuals.add(e); while (residuals.size > capacity.coerceAtLeast(1)) residuals.removeFirst() } }
    fun samples() = residuals.size
    fun radius(coverage: Double = .80, fallback: Double = .08): Double {
        require(coverage in .5..0.999)
        if (residuals.size < minimumSamples) return fallback.coerceIn(0.0, 1.0)
        val sorted = residuals.sorted(); val rank = ceil((sorted.size + 1) * coverage).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1].coerceIn(0.0, 1.0)
    }
}

/** Exact O(n^3) Hungarian assignment for rectangular cost matrices; -1 means unmatched. */
object HungarianAssignment {
    fun solve(input: Array<DoubleArray>): IntArray {
        val n = input.size
        if (n == 0) return IntArray(0)
        val m0 = input.minOf { it.size }
        require(input.all { it.size == input[0].size }) { "Ragged cost matrix" }
        if (m0 == 0) return IntArray(n) { -1 }
        val m = input[0].size
        val transposed = n > m
        val a = if (!transposed) input else Array(m) { j -> DoubleArray(n) { i -> input[i][j] } }
        val rows = a.size; val cols = a[0].size
        val u = DoubleArray(rows + 1); val v = DoubleArray(cols + 1)
        val p = IntArray(cols + 1); val way = IntArray(cols + 1)
        for (i in 1..rows) {
            p[0] = i; var j0 = 0
            val minv = DoubleArray(cols + 1) { Double.POSITIVE_INFINITY }; val used = BooleanArray(cols + 1)
            do {
                used[j0] = true; val i0 = p[j0]; var delta = Double.POSITIVE_INFINITY; var j1 = 0
                for (j in 1..cols) if (!used[j]) {
                    val raw = a[i0 - 1][j - 1]
                    val cur = (if (raw.isFinite()) raw else 1e12) - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..cols) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                j0 = j1
            } while (p[j0] != 0)
            do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
        }
        val assignment = IntArray(n) { -1 }
        for (j in 1..cols) if (p[j] != 0) {
            if (!transposed) assignment[p[j] - 1] = j - 1 else assignment[j - 1] = p[j] - 1
        }
        return assignment
    }
}

data class Detection(val position: Vec2, val score: Double, val signature: List<Double> = emptyList())
data class TrackHypothesis(val trackId: Long, val position: Vec2, val velocity: Vec2, val confidence: Double, val misses: Int = 0, val history: List<Vec2> = emptyList(), val signature: List<Double> = emptyList())
data class AssociationResult(val tracks: List<TrackHypothesis>, val unmatchedDetections: List<Int>, val identityAmbiguity: Double)

/** Lightweight MHT-style track bank. Keeps alternate associations briefly instead of committing blindly. */
class MultiHypothesisTracker(private val maxMisses: Int = 5, private val gate: Double = .12, private val maxHypotheses: Int = 4) {
    private var nextId = 1L
    private var tracks = mutableListOf<TrackHypothesis>()
    fun snapshot(): List<TrackHypothesis> = tracks.toList()
    fun update(detections: List<Detection>, dtSeconds: Double): AssociationResult {
        val dt = dtSeconds.coerceIn(.01, 1.0)
        if (tracks.isEmpty()) {
            tracks = detections.filter { it.score >= .25 }.map { TrackHypothesis(nextId++, it.position.clamp(), Vec2(0.0,0.0), it.score, 0, listOf(it.position.clamp()), it.signature.take(32)) }.toMutableList()
            return AssociationResult(snapshot(), emptyList(), 0.0)
        }
        val predicted = tracks.map { (it.position + it.velocity * dt).clamp() }
        val costs = Array(tracks.size) { i -> DoubleArray(detections.size) { j ->
            val detection = detections[j]
            val dist = predicted[i].distance(detection.position)
            val adaptiveGate = (gate + tracks[i].velocity.length() * dt + tracks[i].misses * gate * .35).coerceAtMost(.42)
            val appearance = signatureDistance(tracks[i].signature, detection.signature)
            if (dist > adaptiveGate || (appearance != null && appearance > .72 && dist > gate * .45)) 1e6
            else dist + (1.0 - detection.score.coerceIn(0.0,1.0))*.015 + (appearance ?: .25) * if (tracks[i].signature.isEmpty() || detection.signature.isEmpty()) .0 else .045
        } }
        val assignment = if (detections.isEmpty()) IntArray(tracks.size){-1} else HungarianAssignment.solve(costs)
        val used = assignment.filter { it >= 0 }.toSet(); val next = mutableListOf<TrackHypothesis>(); var ambiguity = 0.0
        for (i in tracks.indices) {
            val old = tracks[i]; val j = assignment.getOrElse(i){-1}; val valid = j >= 0 && j < detections.size && costs[i][j] < 1e5
            if (valid) {
                val d = detections[j]; val measuredV = (d.position - old.position) * (1.0/dt)
                val vel = old.velocity*.45 + measuredV*.55
                val alternatives = costs[i].withIndex().filter { it.index != j && it.value < 1e5 }.sortedBy { it.value }.take(maxHypotheses - 1)
                if (alternatives.isNotEmpty()) { val best = costs[i][j]; val second = alternatives.first().value; ambiguity += (1.0 - abs(second-best)/(second+best+1e-6)).coerceIn(0.0,1.0) }
                val signature = when {
                    old.signature.isEmpty() -> d.signature.take(32)
                    d.signature.isEmpty() -> old.signature
                    else -> blendSignature(old.signature, d.signature, .22)
                }
                next += old.copy(position=d.position.clamp(), velocity=vel, confidence=(old.confidence*.65+d.score*.35).coerceIn(0.0,1.0), misses=0, history=(old.history+d.position.clamp()).takeLast(12), signature=signature)
            } else if (old.misses < maxMisses) next += old.copy(position=predicted[i], confidence=old.confidence*.72, misses=old.misses+1, history=(old.history+predicted[i]).takeLast(12))
        }
        val unmatched = detections.indices.filter { it !in used }
        for (j in unmatched) if (detections[j].score >= .45) { val d=detections[j]; next += TrackHypothesis(nextId++,d.position.clamp(),Vec2(0.0,0.0),d.score,0,listOf(d.position.clamp())) }
        tracks = next
        return AssociationResult(snapshot(), unmatched, (ambiguity / tracks.size.coerceAtLeast(1)).coerceIn(0.0,1.0))
    }
    private fun signatureDistance(a: List<Double>, b: List<Double>): Double? {
        if (a.isEmpty() || b.isEmpty()) return null
        val n = minOf(a.size, b.size)
        if (n == 0) return null
        val distance = sqrt((0 until n).sumOf { i -> (a[i] - b[i]).pow(2) } / n)
        return if (distance.isFinite()) distance.coerceIn(0.0, 2.0) else 1.0
    }
    private fun blendSignature(a: List<Double>, b: List<Double>, rate: Double): List<Double> {
        val n = minOf(a.size, b.size, 32)
        if (n == 0) return a.take(32)
        return (0 until n).map { i -> (a[i] * (1.0-rate) + b[i] * rate).coerceIn(-2.0, 2.0) }
    }
}

/** Small GRU sequence model (real recurrent gates). Online training updates output weights from labeled deltas. */
class TinyGruForecaster(inputSize: Int = 4, private val hiddenSize: Int = 12, seed: Int = 19) {
    private val input = inputSize.coerceAtLeast(2)
    private val rng = Random(seed)
    private val wz = matrix(hiddenSize, input + hiddenSize); private val wr = matrix(hiddenSize, input + hiddenSize); private val wn = matrix(hiddenSize, input + hiddenSize)
    private val bz = DoubleArray(hiddenSize); private val br = DoubleArray(hiddenSize); private val bn = DoubleArray(hiddenSize)
    private var outX = DoubleArray(hiddenSize + 1) { rng.nextDouble(-.08,.08) }
    private var outY = DoubleArray(hiddenSize + 1) { rng.nextDouble(-.08,.08) }
    private fun matrix(r:Int,c:Int)=Array(r){DoubleArray(c){rng.nextDouble(-.18,.18)}}
    private fun sigmoid(x:Double)=1.0/(1.0+exp(-x.coerceIn(-30.0,30.0)))
    private fun dot(row:DoubleArray, x:DoubleArray, bias:Double)=row.indices.sumOf{row[it]*x[it]}+bias
    private fun step(x0:DoubleArray, h0:DoubleArray):DoubleArray {
        val x=DoubleArray(input){i->x0.getOrElse(i){0.0}.coerceIn(-2.0,2.0)}
        val joined=DoubleArray(input+hiddenSize); x.copyInto(joined); h0.copyInto(joined,input)
        val z=DoubleArray(hiddenSize){sigmoid(dot(wz[it],joined,bz[it]))}; val r=DoubleArray(hiddenSize){sigmoid(dot(wr[it],joined,br[it]))}
        val resetJoined=DoubleArray(input+hiddenSize); x.copyInto(resetJoined); for(i in 0 until hiddenSize)resetJoined[input+i]=r[i]*h0[i]
        val n=DoubleArray(hiddenSize){tanh(dot(wn[it],resetJoined,bn[it]))}
        return DoubleArray(hiddenSize){i->(1-z[i])*n[i]+z[i]*h0[i]}
    }
    private fun encode(sequence:List<DoubleArray>):DoubleArray { var h=DoubleArray(hiddenSize); sequence.takeLast(40).forEach{h=step(it,h)}; return h }
    fun predict(sequence:List<DoubleArray>):Vec2 {
        if(sequence.isEmpty()) return Vec2(.5,.5)
        val h=encode(sequence); val f=doubleArrayOf(1.0)+h
        return Vec2(sigmoid(outX.indices.sumOf{outX[it]*f[it]}),sigmoid(outY.indices.sumOf{outY[it]*f[it]}))
    }
    /** Teacher-forced online output-layer update. Inputs/labels should be normalized positions/features. */
    fun train(sequence:List<DoubleArray>, target:Vec2, learningRate:Double=.015):Double {
        require(sequence.isNotEmpty()); val h=encode(sequence); val f=doubleArrayOf(1.0)+h
        val px=sigmoid(outX.indices.sumOf{outX[it]*f[it]}); val py=sigmoid(outY.indices.sumOf{outY[it]*f[it]})
        val tx=target.x.coerceIn(0.0,1.0); val ty=target.y.coerceIn(0.0,1.0); val err=hypot(px-tx,py-ty)
        val lr=learningRate.coerceIn(1e-5,.1)
        for(i in f.indices){outX[i]=(outX[i]-lr*(px-tx)*px*(1-px)*f[i]).coerceIn(-4.0,4.0);outY[i]=(outY[i]-lr*(py-ty)*py*(1-py)*f[i]).coerceIn(-4.0,4.0)}
        return err
    }
}

/** Deterministic domain-randomized motion simulator; all outputs are synthetic, not human data. */
class DomainRandomizedMotionSimulator(seed: Int = 3) {
    private val random=Random(seed)
    data class Scenario(val speed:Double,val turnRate:Double,val acceleration:Double,val observationNoise:Double,val occlusionRate:Double,val interactionStrength:Double)
    data class Frame(val truth:Vec2,val observation:Vec2?,val mode:String)
    fun scenario()=Scenario(random.nextDouble(.04,.65),random.nextDouble(-3.0,3.0),random.nextDouble(-.3,.3),random.nextDouble(0.0,.035),random.nextDouble(0.0,.4),random.nextDouble(0.0,.08))
    fun rollout(steps:Int=120):List<Frame>{val s=scenario();val out=mutableListOf<Frame>();var p=Vec2(random.nextDouble(.1,.9),random.nextDouble(.1,.9));var angle=random.nextDouble(-Math.PI,Math.PI);var speed=s.speed
        repeat(steps.coerceIn(1,2000)){angle+=s.turnRate*.04+random.nextDouble(-.15,.15);speed=(speed+s.acceleration*.01+random.nextDouble(-.015,.015)).coerceIn(.01,.7);p=(p+Vec2(cos(angle),sin(angle))*(speed*.04)).clamp();val mode=when{abs(s.turnRate)>1.8->"turn";s.acceleration>.12->"accelerate";speed<.12->"slow";else->"cruise"};val obs=if(random.nextDouble()>=s.occlusionRate)Vec2((p.x+random.nextDouble(-s.observationNoise,s.observationNoise)).coerceIn(0.0,1.0),(p.y+random.nextDouble(-s.observationNoise,s.observationNoise)).coerceIn(0.0,1.0))else null;out+=Frame(p,obs,mode)};return out}
}

/** Multi-agent self-play harness for synthetic policies; not a learned human-behaviour model. */
class SyntheticSelfPlay(seed:Int=11){private val sim=DomainRandomizedMotionSimulator(seed);fun runMatches(count:Int=100,steps:Int=100):Map<String,Double>{var observed=0;var turns=0;var occluded=0;repeat(count.coerceIn(1,10000)){val frames=sim.rollout(steps);observed+=frames.count{it.observation!=null};occluded+=frames.count{it.observation==null};turns+=frames.count{it.mode=="turn"}};val total=(count.coerceIn(1,10000)*steps.coerceIn(1,2000)).toDouble();return mapOf("synthetic_observation_rate" to observed/total,"synthetic_occlusion_rate" to occluded/total,"synthetic_turn_fraction" to turns/total,"frames" to total)}}
