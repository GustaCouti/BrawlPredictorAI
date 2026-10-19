package com.brawlpredictor.ai.core

import kotlin.math.*
import kotlin.random.Random

/** Advanced, dependency-free tactical forecasting primitives. Coordinates are normalized to [0,1].
 * Probabilities are calibrated only after enough labeled outcomes; before then they are estimates.
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun length() = hypot(x, y)
    fun distance(o: Vec2) = (this - o).length()
    fun clamp() = Vec2(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    fun finite() = x.isFinite() && y.isFinite()
}

data class MotionSample(val position: Vec2, val timeMs: Long)
data class MotionState(val position: Vec2, val velocity: Vec2, val acceleration: Vec2 = Vec2(0.0, 0.0), val variance: Double = 0.0025)
data class ForecastCandidate(val position: Vec2, val probability: Double, val model: String)
data class ForecastResult(val candidates: List<ForecastCandidate>, val confidence: Double, val abstained: Boolean, val reason: String, val uncertaintyRadius: Double)
data class ObservationEvidence(val ocr: Double, val redColor: Double, val motion: Double, val healthBar: Double)
data class EnemyProfile(var aggression: Double = .5, var dodgeRate: Double = .5, var averageSpeed: Double = .15, var lastMode: String = "unknown", var samples: Int = 0)
data class TacticalEvent(val kind: EventKind, val position: Vec2, val timeMs: Long)
enum class EventKind { SHOT, NEARBY_ENEMY, PROJECTILE, DAMAGE, WALL, OBJECTIVE, UNKNOWN }
enum class MotionMode { CONSTANT_VELOCITY, COORDINATED_TURN, SINGER_ACCELERATION, STOP_OR_REVERSE }
data class GridCell(val x: Int, val y: Int)
data class GridPath(val cells: List<GridCell>, val cost: Double)
data class TrackDetection(val position: Vec2, val label: String, val score: Double, val visualSignature: List<Double> = emptyList())

/** Three-expert committee + dynamic error weights + stacking-like meta arbiter + multimodal forecasts. */
class AdvancedPredictionEngine(seed: Int = 71) {
    private val random = Random(seed)
    private val recentErrors = mutableMapOf<String, ArrayDeque<Double>>()
    private val conformalResiduals = ArrayDeque<Double>()
    private val particleClouds = mutableMapOf<Long, MutableList<MotionState>>()
    private val profiles = mutableMapOf<Long, EnemyProfile>()
    private val hmm = mutableMapOf<Long, DoubleArray>() // advance, retreat, dodge, stop
    private val previousPredictions = mutableMapOf<Long, MutableList<MotionSample>>()
    private val pendingModelPredictions = mutableMapOf<Long, List<ForecastCandidate>>()
    private var latencyMs = 70.0
    private var calibrationCount = 0
    private val attentionForecaster = TinyAttentionForecaster()
    private val gruForecaster = TinyGruForecaster(seed = 97)
    private val pendingSequences = mutableMapOf<Long, List<DoubleArray>>()
    private val ensembleForecaster = ForecastEnsemble()
    private val trajectorySampler = TrajectoryDenoisingSampler()
    private val ukfStates = mutableMapOf<Long, LightweightUkf>()

    fun updateLatency(measuredFrameDelayMs: Double) { if (measuredFrameDelayMs.isFinite() && measuredFrameDelayMs in 0.0..500.0) latencyMs = .8 * latencyMs + .2 * measuredFrameDelayMs }

    /** Release per-target state when a visual track expires; prevents session-long map growth. */
    fun forgetTrack(id: Long) {
        profiles.remove(id); hmm.remove(id); previousPredictions.remove(id)
        pendingModelPredictions.remove(id); pendingSequences.remove(id); particleClouds.remove(id); ukfStates.remove(id)
    }

    /** Prune stale target models in one pass after each tracking update. */
    fun retainTracks(activeIds: Set<Long>) {
        (profiles.keys - activeIds).toList().forEach(::forgetTrack)
        (particleClouds.keys - activeIds).toList().forEach(::forgetTrack)
        (ukfStates.keys - activeIds).toList().forEach(::forgetTrack)
    }

    /** Record only after a real observed outcome; this drives dynamic weights and conformal intervals. */
    fun observeOutcome(model: String, predicted: Vec2, actual: Vec2) {
        val e = predicted.distance(actual).coerceIn(0.0, 2.0)
        val q = recentErrors.getOrPut(model) { ArrayDeque() }; q.add(e); while (q.size > 80) q.removeFirst()
        conformalResiduals.add(e); while (conformalResiduals.size > 500) conformalResiduals.removeFirst(); calibrationCount++
    }
    /** Scores the prior frame's alternatives against a newly observed location. */
    fun observeActual(id: Long, actual: Vec2) {
        pendingModelPredictions.remove(id)?.forEach { observeOutcome(it.model, it.position, actual) }
        // Online supervised update uses only an observed next position, never a synthetic label.
        val sequence = pendingSequences.remove(id)
        if (sequence != null && actual.finite()) {
            val predicted = gruForecaster.predict(sequence)
            observeOutcome("tiny-gru", predicted, actual.clamp())
            gruForecaster.train(sequence, actual.clamp(), learningRate = .008)
        }
    }

    fun fusedEvidence(e: ObservationEvidence): Double {
        val values = listOf(e.ocr, e.redColor, e.motion, e.healthBar).map { it.coerceIn(0.001, .999) }
        // Naive Bayes-style log-odds combination with conservative evidence weights.
        val weights = doubleArrayOf(1.0, .85, .45, .35)
        val logOdds = values.indices.sumOf { i -> weights[i] * ln(values[i] / (1.0 - values[i])) }
        return (1.0 / (1.0 + exp(-logOdds))).coerceIn(0.0, 1.0)
    }

    fun updateProfile(id: Long, state: MotionState, mode: String, wasDodging: Boolean = false, aggressiveAction: Boolean = false): EnemyProfile {
        val p = profiles.getOrPut(id) { EnemyProfile() }; val n = p.samples.toDouble()
        p.averageSpeed = (p.averageSpeed * n + state.velocity.length()) / (n + 1.0)
        p.dodgeRate = (p.dodgeRate * n + if (wasDodging) 1.0 else 0.0) / (n + 1.0)
        p.aggression = (p.aggression * n + if (aggressiveAction) 1.0 else 0.0) / (n + 1.0)
        p.lastMode = mode; p.samples++; return p
    }

    fun onEvent(id: Long, event: TacticalEvent) {
        val p = profiles.getOrPut(id) { EnemyProfile() }
        when (event.kind) { EventKind.SHOT, EventKind.PROJECTILE -> p.dodgeRate = (p.dodgeRate * .85 + .15).coerceIn(0.0, 1.0); EventKind.NEARBY_ENEMY, EventKind.DAMAGE -> p.aggression = (p.aggression * .85 + .15).coerceIn(0.0, 1.0); else -> Unit }
    }

    fun forecast(id: Long, history: List<MotionSample>, horizonMs: Long, walls: List<Rect> = emptyList(), objective: Vec2? = null, otherAgents: List<MotionState> = emptyList(), minConfidence: Double = .28): ForecastResult {
        val validHistory = history.filter { it.position.finite() }.distinctBy { it.timeMs }.sortedBy { it.timeMs }
        if (validHistory.size < 2) return ForecastResult(emptyList(), 0.0, true, "histórico insuficiente", 1.0)
        val sorted = validHistory.takeLast(20)
        val dt = ((sorted.last().timeMs - sorted[sorted.lastIndex - 1].timeMs).coerceAtLeast(1L) / 1000.0)
        val v = (sorted.last().position - sorted[sorted.lastIndex - 1].position) * (1.0 / dt)
        val prevV = if (sorted.size >= 3) (sorted[sorted.lastIndex - 1].position - sorted[sorted.lastIndex - 2].position) * (1000.0 / (sorted[sorted.lastIndex - 1].timeMs - sorted[sorted.lastIndex - 2].timeMs).coerceAtLeast(1L)) else v
        val a = (v - prevV) * (1.0 / dt)
        val state = MotionState(sorted.last().position, v, a, adaptiveVariance(sorted))
        val profile = profiles[id] ?: EnemyProfile()
        val seconds = ((horizonMs.coerceIn(30L, 1200L) + latencyMs) / 1000.0)
        val cv = state.position + state.velocity * seconds
        val turn = coordinatedTurn(sorted, seconds)
        val singer = (state.position + state.velocity * seconds + state.acceleration * (.5 * seconds * seconds)).clamp()
        val tactical = tacticalCandidate(state, seconds, profile, objective, otherAgents, walls)
        val particle = particleForecast(id, state, seconds, walls)
        val sequence = lightweightSequenceForecast(sorted, seconds)
        val sequenceFeatures = sorted.mapIndexed { index, sample ->
            doubleArrayOf(sample.position.x, sample.position.y,
                (sample.timeMs - sorted.first().timeMs).coerceAtLeast(0L) / 1000.0,
                sample.position.distance(sorted.getOrNull(index - 1)?.position ?: sample.position))
        }
        val attention = attentionForecaster.predict(sequenceFeatures)
        val gru = gruForecaster.predict(sequenceFeatures)
        pendingSequences[id] = sequenceFeatures
        val ensemble = ensembleForecaster.predict(sorted, (seconds * 1000).toLong())
        val ukf = ukfStates.getOrPut(id) { LightweightUkf(state) }
        // Correct order: assimilate the latest observation, then predict forward.
        ukf.update(sorted.last().position, state.variance.coerceAtLeast(.001))
        val ukfState = ukf.predict(seconds, coordinatedTurnRate(sorted))
        val ukfCandidate = ukfState.position
        val attentionPosition = attention
        val diffusionPath = trajectorySampler.sample(sorted, (seconds * 1000).toLong(), 5, 5)
        val diffusionPosition = diffusionPath.mapNotNull { it.lastOrNull() }.takeIf { it.isNotEmpty() }
            ?.let { points -> Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size) } ?: sequence
        val candidates = listOf(
            ForecastCandidate(constrain(cv, walls), weight("constant-velocity", cv, state.variance), "constant-velocity"),
            ForecastCandidate(constrain(turn, walls), weight("coordinated-turn", turn, state.variance * 1.2), "coordinated-turn"),
            ForecastCandidate(constrain(singer, walls), weight("singer-acceleration", singer, state.variance * 1.4), "singer-acceleration"),
            ForecastCandidate(constrain(tactical, walls), weight("utility-ai", tactical, state.variance * 1.6), "utility-ai"),
            ForecastCandidate(constrain(particle, walls), weight("particle-filter", particle, state.variance * 1.8), "particle-filter"),
            ForecastCandidate(constrain(sequence, walls), weight("lightweight-sequence", sequence, state.variance * 1.7), "lightweight-sequence"),
            ForecastCandidate(constrain(attentionPosition, walls), weight("tiny-attention", attentionPosition, state.variance * 1.8), "tiny-attention"),
            ForecastCandidate(constrain(gru, walls), weight("tiny-gru", gru, state.variance * 1.5), "tiny-gru"),
            ForecastCandidate(constrain(ensemble.mean, walls), weight("forecast-ensemble", ensemble.mean, state.variance + ensemble.uncertainty), "forecast-ensemble"),
            ForecastCandidate(constrain(ukfCandidate, walls), weight("lightweight-ukf", ukfCandidate, ukfState.variance), "lightweight-ukf"),
            ForecastCandidate(constrain(diffusionPosition, walls), weight("trajectory-denoising", diffusionPosition, state.variance * 2.0), "trajectory-denoising")
        )
        val safeCandidates = candidates.filter { it.position.finite() && it.probability.isFinite() && it.probability > 0.0 }
        if (safeCandidates.isEmpty()) return ForecastResult(emptyList(), 0.0, true, "nenhum candidato numérico válido", 1.0)
        val totalWeight = safeCandidates.sumOf { it.probability }.coerceAtLeast(1e-9)
        val normalized = safeCandidates.map { it.copy(position = it.position.clamp(), probability = it.probability / totalWeight) }
        val consensus = committeeConsensus(normalized)
        val probs = normalized.map { it.probability }.sortedDescending()
        val disagreement = normalized.map { it.position.distance(consensus) }.average()
        val residual = conformalRadius(.8)
        val confidence = (probs.take(3).sum() * exp(-disagreement * 3.5) * exp(-state.variance * 3.0)).coerceIn(0.0, 1.0)
        val abstain = confidence < minConfidence || (disagreement > .16 && horizonMs > 300)
        val topModes = normalized.sortedByDescending { it.probability }.take(5)
        val topMass = topModes.sumOf { it.probability }.coerceAtLeast(1e-9)
        val multimodal = topModes.map { it.copy(probability = it.probability / topMass) }
        previousPredictions[id] = sorted.takeLast(8).toMutableList()
        if (!abstain) pendingModelPredictions[id] = multimodal
        return ForecastResult(if (abstain) emptyList() else multimodal, confidence, abstain, if (abstain) "modelos discordam ou confiança baixa" else "previsão multimodal; não é garantia", (residual + disagreement).coerceIn(.01, .5))
    }

    private fun weight(model: String, p: Vec2, variance: Double): Double {
        val errors = recentErrors[model]
        val learned = if (errors.isNullOrEmpty()) 1.0 else 1.0 / (errors.average() + .015)
        return (learned * exp(-variance * 2.0) / (1.0 + p.distance(Vec2(.5, .5)) * .03)).coerceAtLeast(.001)
    }
    private fun committeeConsensus(c: List<ForecastCandidate>): Vec2 {
        val best = c.sortedByDescending { it.probability }.take(3)
        val sum = best.sumOf { it.probability }.coerceAtLeast(.0001)
        return best.fold(Vec2(0.0, 0.0)) { acc, item -> acc + item.position * (item.probability / sum) }.clamp()
    }
    private fun adaptiveVariance(h: List<MotionSample>): Double {
        if (h.size < 3) return .02
        val speeds = h.zipWithNext().map { (a,b) -> a.position.distance(b.position) / ((b.timeMs-a.timeMs).coerceAtLeast(1L)/1000.0) }
        val mean = speeds.average(); val variance = speeds.map { (it-mean).pow(2) }.average()
        return (.001 + variance * .002 + if (mean > .8) .01 else 0.0).coerceIn(.001, .12)
    }
    private fun coordinatedTurn(h: List<MotionSample>, t: Double): Vec2 {
        if (h.size < 3) return (h.last().position + (h.last().position-h[h.lastIndex-1].position) * t * 8.0).clamp()
        val a=h[h.lastIndex-2]; val b=h[h.lastIndex-1]; val c=h.last(); val d1=b.position-a.position; val d2=c.position-b.position
        val angle1=atan2(d1.y,d1.x); val angle2=atan2(d2.y,d2.x); val omega=wrapAngle(angle2-angle1).coerceIn(-5.0,5.0)
        val speed=d2.length() / ((c.timeMs-b.timeMs).coerceAtLeast(1L)/1000.0)
        if (abs(omega)<.05 || speed<.001) return (c.position+d2*(t/((c.timeMs-b.timeMs).coerceAtLeast(1L)/1000.0))).clamp()
        val heading=angle2+omega*t; return (c.position+Vec2(cos(heading)-cos(angle2), sin(heading)-sin(angle2))*(speed/omega)).clamp()
    }
    private fun coordinatedTurnRate(h: List<MotionSample>): Double {
        if (h.size < 3) return 0.0
        val a = h[h.lastIndex-2]; val b = h[h.lastIndex-1]; val c = h.last()
        val d1 = b.position-a.position; val d2 = c.position-b.position
        val dt = ((c.timeMs-b.timeMs).coerceAtLeast(1L))/1000.0
        return (wrapAngle(atan2(d2.y,d2.x)-atan2(d1.y,d1.x))/dt).coerceIn(-4.0,4.0)
    }
    private fun wrapAngle(a: Double) = atan2(sin(a), cos(a))
    private fun tacticalCandidate(s: MotionState, t: Double, p: EnemyProfile, objective: Vec2?, agents: List<MotionState>, walls: List<Rect>): Vec2 {
        var force = s.velocity * .35
        if (objective != null) force += (objective - s.position) * (.08 + p.aggression*.1)
        agents.forEach { other -> val d=s.position-other.position; val dist=d.length().coerceAtLeast(.01); if(dist<.20) force += d*(.015/(dist*dist)) }
        walls.forEach { r -> val nearest=Vec2(s.position.x.coerceIn(r.left,r.right),s.position.y.coerceIn(r.top,r.bottom)); val d=s.position-nearest; val dist=d.length(); if(dist<.08 && dist>.0001) force += d*(.01/(dist*dist)) }
        return (s.position + (s.velocity + force) * t).clamp()
    }
    private fun particleForecast(id: Long, s: MotionState, t: Double, walls: List<Rect>): Vec2 {
        val cloud = particleClouds.getOrPut(id) { MutableList(64) { s } }
        for (i in cloud.indices) { val old=cloud[i]; val noise=(random.nextDouble(-1.0,1.0)*sqrt(old.variance)); val turn=random.nextDouble(-.45,.45); val speed=old.velocity.length(); val heading=atan2(old.velocity.y,old.velocity.x)+turn*t
            val vel=Vec2(cos(heading)*speed+noise*.15,sin(heading)*speed+noise*.15); cloud[i]=MotionState(constrain(old.position+vel*t,walls),vel,old.acceleration, (old.variance*.95+.001).coerceIn(.001,.2)) }
        val mean=cloud.fold(Vec2(0.0,0.0)){a,b->a+b.position}*(1.0/cloud.size); return mean.clamp()
    }
    /** Small recurrent sequence baseline; not a pretrained GRU/Transformer. */
    private fun lightweightSequenceForecast(h: List<MotionSample>, t: Double): Vec2 {
        val recent=h.takeLast(6); var v=Vec2(0.0,0.0); var norm=0.0
        for(i in 1 until recent.size){val dt=((recent[i].timeMs-recent[i-1].timeMs).coerceAtLeast(1L))/1000.0; val w=i.toDouble(); v += (recent[i].position-recent[i-1].position)*(w/dt); norm+=w}
        return (recent.last().position + v*(t/norm.coerceAtLeast(1.0))).clamp()
    }
    private fun constrain(p: Vec2, walls: List<Rect>): Vec2 {
        var q=p.clamp(); for(r in walls) if(q.x in r.left..r.right && q.y in r.top..r.bottom){ val options=listOf(Vec2(r.left-1e-4,q.y),Vec2(r.right+1e-4,q.y),Vec2(q.x,r.top-1e-4),Vec2(q.x,r.bottom+1e-4)); q=options.minBy { it.distance(q) }.clamp() }; return q
    }
    private fun conformalRadius(coverage: Double): Double {
        if (conformalResiduals.size < 20) return .05
        val a=conformalResiduals.sorted(); val index=ceil((a.size+1)*coverage).toInt().coerceIn(1,a.size)-1; return a[index].coerceIn(.005,.5)
    }

    /** Reachability bound; candidates outside this radius are physically implausible for maxSpeed. */
    fun reachable(candidate: Vec2, origin: Vec2, seconds: Double, maxSpeed: Double, tolerance: Double = .015) = candidate.distance(origin) <= maxSpeed.coerceAtLeast(0.0)*seconds.coerceAtLeast(0.0)+tolerance
    fun intercept(origin: Vec2, target: MotionState, projectileSpeed: Double): Vec2? {
        if(projectileSpeed<=0.0) return null; val r=target.position-origin; val v=target.velocity; val a=v.x*v.x+v.y*v.y-projectileSpeed*projectileSpeed; val b=2*(r.x*v.x+r.y*v.y); val c=r.x*r.x+r.y*r.y
        val t=if(abs(a)<1e-8){ if(abs(b)<1e-8) return null else -c/b } else { val d=b*b-4*a*c; if(d<0) return null; val t1=(-b-sqrt(d))/(2*a); val t2=(-b+sqrt(d))/(2*a); listOf(t1,t2).filter{it>0}.minOrNull()?:return null }; return if(t<=0 || t>5) null else (target.position+target.velocity*t).clamp()
    }
    fun threatScore(distance: Double, enemyDamage: Double, enemyAggression: Double, healthFraction: Double, objectiveWeight: Double = .5): Double {
        val proximity=(1.0-distance.coerceAtLeast(0.0)/.6).coerceIn(0.0,1.0); return (.35*proximity+.25*enemyDamage.coerceIn(0.0,1.0)+.2*enemyAggression.coerceIn(0.0,1.0)+.1*(1-healthFraction.coerceIn(0.0,1.0))+.1*objectiveWeight.coerceIn(0.0,1.0)).coerceIn(0.0,1.0)
    }
    fun hmmModeProbabilities(id: Long, evidence: DoubleArray): DoubleArray {
        require(evidence.size==4); val trans=arrayOf(doubleArrayOf(.65,.1,.15,.1),doubleArrayOf(.12,.6,.18,.1),doubleArrayOf(.25,.15,.5,.1),doubleArrayOf(.18,.12,.12,.58)); val old=hmm[id]?:doubleArrayOf(.4,.2,.2,.2); val next=DoubleArray(4){j->(0..3).sumOf{i->old[i]*trans[i][j]}*evidence[j].coerceAtLeast(.001)}; val sum=next.sum().coerceAtLeast(1e-9); for(i in next.indices)next[i]/=sum; hmm[id]=next; return next.copyOf()
    }
    fun imitationUpdate(current: DoubleArray, demonstrated: DoubleArray, learningRate: Double = .05): DoubleArray { require(current.size==demonstrated.size); return current.indices.map { i -> current[i]+learningRate*(demonstrated[i]-current[i]) }.toDoubleArray() }

    /** Global assignment by exact bitmask DP for small sets; greedy fallback for large screens. */
    fun hungarianLikeAssignment(cost: Array<DoubleArray>): IntArray {
        val rows=cost.size; if(rows==0)return IntArray(0); val cols=cost[0].size; if(cols==0)return IntArray(rows){-1}
        if(cols<=18 && rows<=cols){ val states=1 shl cols; val dp=DoubleArray(states){Double.POSITIVE_INFINITY}; val prev=IntArray(states){-1}; val chosen=IntArray(states){-1}; dp[0]=0.0
            for(mask in 0 until states){val r=Integer.bitCount(mask); if(r>=rows||!dp[mask].isFinite())continue; for(c in 0 until cols)if(mask and (1 shl c)==0){val nm=mask or (1 shl c); val v=dp[mask]+cost[r][c]; if(v<dp[nm]){dp[nm]=v;prev[nm]=mask;chosen[nm]=c}}}
            var mask=(0 until states).filter{Integer.bitCount(it)==rows}.minByOrNull{dp[it]}?:0; val out=IntArray(rows){-1}; for(r in rows-1 downTo 0){out[r]=chosen[mask]; mask=prev[mask].coerceAtLeast(0)};return out
        }
        val used=mutableSetOf<Int>(); return IntArray(rows){r->(0 until cols).filter{it !in used}.minByOrNull{cost[r][it]}?.also{used.add(it)}?:-1}
    }
    /** JPDA-style soft association weights for each track against detections, including clutter. */
    fun jpdaWeights(costs: Array<DoubleArray>, temperature: Double = .04): Array<DoubleArray> = Array(costs.size){r-> val vals=costs[r].map{exp(-it/temperature.coerceAtLeast(.001))}; val sum=vals.sum()+.15; DoubleArray(vals.size){c->vals[c]/sum} }
    /** Gating helper used before association; avoids implausible jumps. */
    fun withinGate(a: Vec2,b: Vec2,dtSeconds: Double,speedBound: Double,baseGate: Double=.025)=a.distance(b)<=baseGate+speedBound.coerceAtLeast(0.0)*dtSeconds.coerceAtLeast(0.0)

    fun trackBeforeDetect(previousEvidence: Double, newWeakEvidence: Double, decay: Double = .9): Double = (previousEvidence*decay + newWeakEvidence.coerceIn(0.0,1.0)).coerceIn(0.0, 5.0)
    fun phdExpectedTargets(weights: List<Double>): Double = weights.sumOf { it.coerceIn(0.0,1.0) }
    fun reIdSimilarity(a: List<Double>, b: List<Double>): Double { if(a.isEmpty()||b.isEmpty()||a.size!=b.size)return 0.0; val d=sqrt(a.indices.sumOf{(a[it]-b[it]).pow(2)}); return exp(-d/a.size) }
    fun interactionAdjustment(target: Vec2, others: List<MotionState>, strength: Double=.02): Vec2 { var p=target; others.forEach{val d=target-it.position;val r=d.length();if(r in .001..0.18)p+=d*(strength/(r*r))};return p.clamp() }
    fun monteCarloReachability(start: Vec2, speed: Double, seconds: Double, obstacles: List<Rect>, trials: Int=200): List<Vec2> = List(trials.coerceIn(1,2000)){ val a=random.nextDouble(0.0,2*PI); val d=random.nextDouble(0.0,speed.coerceAtLeast(0.0)*seconds.coerceAtLeast(0.0)); constrain(start+Vec2(cos(a)*d,sin(a)*d),obstacles) }

    /** Grid A*: walkability is false for walls. */
    fun aStar(width: Int, height: Int, start: GridCell, goal: GridCell, walkable: (GridCell)->Boolean): GridPath? {
        if(width<=0||height<=0||start.x !in 0 until width||start.y !in 0 until height||goal.x !in 0 until width||goal.y !in 0 until height||!walkable(start)||!walkable(goal))return null
        val open=mutableSetOf(start); val came=mutableMapOf<GridCell,GridCell>(); val g=mutableMapOf(start to 0.0); val f=mutableMapOf(start to heuristic(start,goal))
        while(open.isNotEmpty()){val cur=open.minBy{f[it]?:Double.POSITIVE_INFINITY};if(cur==goal){val path=mutableListOf(cur);var p=cur;while(p in came){p=came[p]!!;path.add(p)};path.reverse();return GridPath(path,g[goal]?:0.0)};open.remove(cur)
            for(n in listOf(GridCell(cur.x+1,cur.y),GridCell(cur.x-1,cur.y),GridCell(cur.x,cur.y+1),GridCell(cur.x,cur.y-1))){if(n.x !in 0 until width||n.y !in 0 until height||!walkable(n))continue;val ng=(g[cur]?:1e9)+1;if(ng<(g[n]?:1e9)){came[n]=cur;g[n]=ng;f[n]=ng+heuristic(n,goal);open.add(n)}}
        };return null
    }
    private fun heuristic(a:GridCell,b:GridCell)=abs(a.x-b.x).toDouble()+abs(a.y-b.y).toDouble()
    fun flowField(width:Int,height:Int,goal:GridCell,walkable:(GridCell)->Boolean):Map<GridCell,GridCell> { val out=mutableMapOf<GridCell,GridCell>();for(y in 0 until height)for(x in 0 until width){val c=GridCell(x,y);if(c==goal||!walkable(c))continue;val ns=listOf(GridCell(x+1,y),GridCell(x-1,y),GridCell(x,y+1),GridCell(x,y-1)).filter{it.x in 0 until width&&it.y in 0 until height&&walkable(it)};ns.minByOrNull{heuristic(it,goal)}?.let{out[c]=it}};return out }
    fun potentialField(position:Vec2, goal:Vec2, threats:List<Vec2>):Vec2 { var f=(goal-position)*.8;for(t in threats){val d=position-t;val r=d.length();if(r in .001..0.25)f+=d*(.004/(r*r))};return f }
    fun chooseUtilityAction(position:Vec2, actions:Map<String,Double>):String? = actions.filter { (name, score) -> name.isNotBlank() && score.isFinite() && position.finite() }.maxByOrNull{it.value}?.key
    fun dodgeDirection(position:Vec2, threats:List<Vec2>, objective:Vec2?):Vec2 { var force=objective?.let{(it-position)*.25}?:Vec2(0.0,0.0);for(t in threats){val d=position-t;val r=d.length();if(r in .001..0.35)force+=d*(.01/(r*r))};return force.clamp() }
    fun rtsSmooth(samples:List<MotionState>):List<MotionState>{if(samples.size<3)return samples;val out=samples.toMutableList();for(i in samples.lastIndex-1 downTo 0){val n=out[i+1];val c=out[i];out[i]=MotionState(c.position*.65+n.position*.35,c.velocity*.65+n.velocity*.35,c.acceleration*.7+n.acceleration*.3,(c.variance*.7+n.variance*.3).coerceAtLeast(.0001))};return out}
}


/** Lightweight constant-velocity Kalman filter (position/velocity per axis); covariance is adaptive.
 * This is a linear Kalman baseline, not a full nonlinear UKF implementation.
 */
class KalmanMotionFilter(initial: MotionState) {
    var state: MotionState = initial; private set
    private var pPos = initial.variance.coerceAtLeast(1e-4)
    private var pVel = (initial.variance * 2).coerceAtLeast(1e-4)
    fun predict(dtSeconds: Double, processNoise: Double = .002): MotionState {
        val dt = dtSeconds.coerceIn(0.0, 1.0)
        state = MotionState((state.position + state.velocity * dt).clamp(), state.velocity, state.acceleration,
            (pPos + dt*dt*pVel + processNoise).coerceIn(1e-5,.5))
        pPos = (pPos + dt*dt*pVel + processNoise).coerceIn(1e-5,.5); pVel = (pVel + processNoise*.25).coerceIn(1e-5,.5)
        return state
    }
    fun update(measured: Vec2, measurementNoise: Double = .004): MotionState {
        val r = measurementNoise.coerceIn(1e-5,.5); val k = pPos/(pPos+r); val residual=measured-state.position
        val dt=.10; val correctedPosition=(state.position+residual*k).clamp(); val correctedVelocity=state.velocity+residual*(k/dt*.25)
        state=MotionState(correctedPosition,correctedVelocity,state.acceleration,(1-k)*pPos)
        pPos=((1-k)*pPos).coerceIn(1e-5,.5); pVel=(pVel+residual.length()*k*.01).coerceIn(1e-5,.5)
        return state
    }
}

/** IMM-style bank of motion models with adaptive mode probabilities; each model remains lightweight. */
class ImmMotionFilter(initial: MotionState) {
    private val filters = mutableMapOf(MotionMode.CONSTANT_VELOCITY to KalmanMotionFilter(initial),
        MotionMode.COORDINATED_TURN to KalmanMotionFilter(initial), MotionMode.SINGER_ACCELERATION to KalmanMotionFilter(initial),
        MotionMode.STOP_OR_REVERSE to KalmanMotionFilter(initial))
    private val probs = doubleArrayOf(.45,.20,.20,.15)
    fun update(measured: Vec2, dt: Double, observedTurn: Double = 0.0): MotionState {
        val likelihood = doubleArrayOf(
            exp(-filters.getValue(MotionMode.CONSTANT_VELOCITY).state.position.distance(measured)*12),
            exp(-filters.getValue(MotionMode.COORDINATED_TURN).state.position.distance(measured)*10)*(1+abs(observedTurn)),
            exp(-filters.getValue(MotionMode.SINGER_ACCELERATION).state.position.distance(measured)*9),
            exp(-filters.getValue(MotionMode.STOP_OR_REVERSE).state.position.distance(measured)*8)*(1+abs(observedTurn)*.5))
        val trans = arrayOf(doubleArrayOf(.78,.08,.08,.06),doubleArrayOf(.1,.72,.12,.06),doubleArrayOf(.12,.1,.7,.08),doubleArrayOf(.15,.1,.15,.6))
        val prior=DoubleArray(4){j->(0..3).sumOf{i->probs[i]*trans[i][j]}}
        val post=DoubleArray(4){i->prior[i]*likelihood[i].coerceAtLeast(1e-6)};val total=post.sum().coerceAtLeast(1e-9)
        for(i in probs.indices)probs[i]=post[i]/total
        val modes=MotionMode.values(); for(i in modes.indices){val f=filters.getValue(modes[i]);f.predict(dt);f.update(measured, .004+(1-probs[i])*.02)}
        val combinedPos=filters.entries.fold(Vec2(0.0,0.0)){acc,e->acc+e.value.state.position*probs[e.key.ordinal]}.clamp()
        val combinedVel=filters.entries.fold(Vec2(0.0,0.0)){acc,e->acc+e.value.state.velocity*probs[e.key.ordinal]}
        return MotionState(combinedPos,combinedVel,Vec2(0.0,0.0),filters.entries.sumOf{it.value.state.variance*probs[it.key.ordinal]})
    }
    fun probabilities(): Map<MotionMode,Double> = MotionMode.values().associateWith { probs[it.ordinal] }
}

data class Rect(val left:Double,val top:Double,val right:Double,val bottom:Double) { init { require(left<=right && top<=bottom) } }
