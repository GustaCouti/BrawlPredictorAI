package com.brawlpredictor.ai.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reproducible synthetic stress lab. It is deliberately labelled simulation: no Brawl Stars
 * screenshots, matches, player choices or live match outcomes are used as training data.
 */
data class ScenarioReport(
    val seed: Long,
    val scenarios: Int,
    val simulatedFrames: Long,
    val visibleObservations: Long,
    val associatedObservations: Long,
    val associationSwitches: Long,
    val associationOpportunities: Long,
    val meanPositionError: Float,
    val detectionRecall: Float,
    val associationSwitchRate: Float,
    val candidateHorizonSeconds: Float,
    val horizonErrors: Map<Float, Float>,
    val runtimeMs: Long
)

object OfflineScenarioLab {
    private val horizons = listOf(0.09f, 0.12f, 0.15f, 0.18f, 0.22f, 0.26f)

    /** Runs randomly generated arenas, target movement styles, walls, occlusion and OCR noise. */
    fun run(scenarios: Int = 10_000, seed: Long = 0xB4A771L, extremeMovement: Boolean = false): ScenarioReport {
        require(scenarios > 0)
        val started = System.nanoTime()
        val random = Random(seed)
        var frames = 0L
        var visibleObs = 0L
        var matchedObs = 0L
        var switches = 0L
        var switchOpportunities = 0L
        var localizationErrorTotal = 0.0
        val localizationSamples = longArrayOf(0L)
        val horizonErrorTotals = DoubleArray(horizons.size)
        val horizonCounts = LongArray(horizons.size)
        val frameCount = 28
        val dt = 0.16f

        repeat(scenarios) { scenarioIndex ->
            val aspect = (0.46 + random.nextDouble() * 1.30).toFloat()
            val obstacles = List(random.nextInt(6)) {
                val left = (0.08 + random.nextDouble() * 0.76).toFloat()
                val top = (0.08 + random.nextDouble() * 0.76).toFloat()
                val w = (0.035 + random.nextDouble() * 0.12).toFloat()
                val h = (0.035 + random.nextDouble() * 0.12).toFloat()
                Rect(left.coerceAtMost(0.89f), top.coerceAtMost(0.89f), (left + w).coerceAtMost(0.97f), (top + h).coerceAtMost(0.97f))
            }
            val agentCount = 1 + random.nextInt(4)
            val agents = List(agentCount) { idx ->
                Agent(
                    id = idx,
                    label = "enemy${scenarioIndex % 997}_${idx}",
                    x = (0.08 + random.nextDouble() * 0.84).toFloat(),
                    y = (0.08 + random.nextDouble() * 0.84).toFloat(),
                    angle = random.nextDouble() * PI * 2,
                    speed = (if (extremeMovement) 0.28 + random.nextDouble() * 0.72 else 0.06 + random.nextDouble() * 0.28).toFloat(),
                    style = if (extremeMovement) random.nextInt(6) else random.nextInt(5),
                    turnTimer = (if (extremeMovement) 0.035 + random.nextDouble() * 0.20 else 0.18 + random.nextDouble() * 0.75).toFloat()
                )
            }
            val tracker = EnemyTrackingCore()
            val histories = agents.map { agent -> MutableList(frameCount) { Point(agent.x, agent.y) } }
            val noisyObservations: List<MutableList<Point?>> = agents.map { MutableList<Point?>(frameCount) { null } }
            val lastLinkedId = LongArray(agentCount) { -1L }
            val lastLinkedFrame = IntArray(agentCount) { -100 }

            repeat(frameCount) { frame ->
                frames++
                agents.forEachIndexed { agentIdx, agent ->
                    agent.advance(dt, random, obstacles)
                    histories[agentIdx][frame] = Point(agent.x, agent.y)
                }
                val now = ((scenarioIndex.toLong() * frameCount + frame) * 160L) + 1_000L
                val candidates = mutableListOf<EnemyObservation>()
                agents.forEachIndexed { agentIdx, agent ->
                    // Random occlusion includes bushes/effects/brief OCR misses, not real game visibility.
                    if (random.nextDouble() < 0.085) return@forEachIndexed
                    val noise = (random.nextDouble() * 0.012 - 0.006).toFloat()
                    val noiseY = (random.nextDouble() * 0.012 - 0.006).toFloat()
                    val measuredX = (agent.x + noise).coerceIn(0.01f, 0.99f)
                    val measuredY = (agent.y + noiseY).coerceIn(0.01f, 0.99f)
                    noisyObservations[agentIdx][frame] = Point(measuredX, measuredY)
                    val label = if (random.nextDouble() < 0.025) corruptLabel(agent.label, random) else agent.label
                    val textWidth = (0.035 + random.nextDouble() * 0.07).toFloat()
                    val textHeight = (0.008 + random.nextDouble() * 0.009).toFloat()
                    val quality = (0.67 + random.nextDouble() * 0.29).toFloat()
                    candidates.add(EnemyObservation(
                        label = label,
                        x = measuredX,
                        y = measuredY,
                        textLeft = (measuredX - textWidth / 2).coerceIn(0f, 1f),
                        textTop = (measuredY - textHeight * 3f).coerceAtLeast(0f),
                        textRight = (measuredX + textWidth / 2).coerceIn(0f, 1f),
                        textBottom = (measuredY - textHeight * 2f).coerceAtLeast(0.001f),
                        quality = quality,
                        redRatio = (0.10 + random.nextDouble() * 0.42).toFloat(),
                        observedAtMs = now
                    ))
                    visibleObs++
                }
                val outputs = tracker.update(candidates, now, aspect)
                agents.forEachIndexed { agentIdx, agent ->
                    if (noisyObservations[agentIdx][frame] == null) return@forEachIndexed
                    val fresh = outputs.filter { !it.predictedOnly && now - it.lastSeenAtMs < 45L && labelSimilarity(it.label, agent.label) >= 0.52f }
                    val nearest = fresh.minByOrNull { hypot((it.x - agent.x) * aspect, it.y - agent.y) }
                    if (nearest != null && hypot((nearest.x - agent.x) * aspect, nearest.y - agent.y) < 0.075f) {
                        matchedObs++
                        val err = hypot((nearest.x - agent.x) * aspect, nearest.y - agent.y).toDouble()
                        localizationErrorTotal += err
                        localizationSamples[0]++
                        if (lastLinkedId[agentIdx] != -1L && frame - lastLinkedFrame[agentIdx] <= 5) {
                            switchOpportunities++
                            if (nearest.trackId != lastLinkedId[agentIdx]) switches++
                        }
                        lastLinkedId[agentIdx] = nearest.trackId
                        lastLinkedFrame[agentIdx] = frame
                    }
                }
            }

            // Tune one conservative synthetic prior by comparing short constant-velocity forecasts
            // against the generated future path. Motion changes/obstacles naturally penalize long leads.
            for (agentIdx in agents.indices) {
                val history = histories[agentIdx]
                val measured = noisyObservations[agentIdx]
                for (i in 1 until frameCount - 2) {
                    val current = measured[i] ?: continue
                    val previous = measured[i - 1] ?: continue
                    val vx = ((current.x - previous.x) / dt).coerceIn(-1.2f, 1.2f)
                    val vy = ((current.y - previous.y) / dt).coerceIn(-1.2f, 1.2f)
                    for (hIndex in horizons.indices) {
                        val h = horizons[hIndex]
                        val step = h / dt
                        val base = i + step.toInt()
                        val frac = step - step.toInt()
                        if (base + 1 >= frameCount) continue
                        val p0 = history[base]
                        val p1 = history[base + 1]
                        val futureX = p0.x + (p1.x - p0.x) * frac
                        val futureY = p0.y + (p1.y - p0.y) * frac
                        val predictX = (current.x + vx * h).coerceIn(0f, 1f)
                        val predictY = (current.y + vy * h).coerceIn(0f, 1f)
                        val error = hypot((predictX - futureX) * aspect, predictY - futureY)
                        horizonErrorTotals[hIndex] += error.toDouble()
                        horizonCounts[hIndex]++
                    }
                }
            }
        }
        val horizonErrors = horizons.mapIndexed { i, h -> h to if (horizonCounts[i] == 0L) 1f else (horizonErrorTotals[i] / horizonCounts[i]).toFloat() }.toMap()
        val bestHorizon = horizonErrors.minByOrNull { it.value }?.key ?: 0.12f
        val means = if (localizationSamples[0] == 0L) 1f else (localizationErrorTotal / localizationSamples[0]).toFloat()
        return ScenarioReport(
            seed = seed,
            scenarios = scenarios,
            simulatedFrames = frames,
            visibleObservations = visibleObs,
            associatedObservations = matchedObs,
            associationSwitches = switches,
            associationOpportunities = switchOpportunities,
            meanPositionError = means,
            detectionRecall = if (visibleObs == 0L) 0f else matchedObs.toFloat() / visibleObs,
            associationSwitchRate = if (switchOpportunities == 0L) 0f else switches.toFloat() / switchOpportunities,
            candidateHorizonSeconds = bestHorizon,
            horizonErrors = horizonErrors,
            runtimeMs = (System.nanoTime() - started) / 1_000_000L
        )
    }

    private fun labelSimilarity(first: String, second: String): Float {
        val a = first.lowercase().filter { it.isLetterOrDigit() }
        val b = second.lowercase().filter { it.isLetterOrDigit() }
        if (a.isEmpty() || b.isEmpty()) return 0f
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1), previous[j] + if (a[i] == b[j]) 0 else 1)
            for (j in previous.indices) previous[j] = current[j]
        }
        return 1f - current[b.length].toFloat() / max(a.length, b.length).toFloat()
    }

    private fun corruptLabel(original: String, random: Random): String {
        if (original.length < 3) return original
        val index = 1 + random.nextInt(original.length - 1)
        val replacement = if (random.nextBoolean()) '0' else 'o'
        return original.substring(0, index) + replacement + original.substring(index + 1)
    }

    private data class Point(val x: Float, val y: Float)
    private data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    private class Agent(
        val id: Int,
        val label: String,
        var x: Float,
        var y: Float,
        var angle: Double,
        val speed: Float,
        val style: Int,
        var turnTimer: Float
    ) {
        fun advance(dt: Float, random: Random, obstacles: List<Rect>) {
            turnTimer -= dt
            if (turnTimer <= 0f) {
                angle = when (style) {
                    0 -> angle + (if (random.nextBoolean()) 0.75 else -0.75)
                    1 -> angle + (if (random.nextBoolean()) PI / 2 else -PI / 2)
                    2 -> angle + PI + (random.nextDouble() - 0.5) * 0.55
                    3 -> angle + (random.nextDouble() - 0.5) * 2.3
                    4 -> angle + (random.nextDouble() - 0.5) * PI
                    else -> angle + (random.nextDouble() - 0.5) * PI * 2.0
                }
                turnTimer = when (style) {
                    5 -> (0.025 + random.nextDouble() * 0.075).toFloat()
                    0 -> (0.22 + random.nextDouble() * 0.5).toFloat()
                    1 -> (0.35 + random.nextDouble() * 0.6).toFloat()
                    2 -> (0.4 + random.nextDouble() * 0.7).toFloat()
                    3 -> (0.12 + random.nextDouble() * 0.3).toFloat()
                    else -> (0.25 + random.nextDouble() * 0.7).toFloat()
                }
            }
            var nextX = x + cos(angle).toFloat() * speed * dt
            var nextY = y + sin(angle).toFloat() * speed * dt
            if (nextX < 0.035f || nextX > 0.965f) { angle = PI - angle; nextX = x + cos(angle).toFloat() * speed * dt }
            if (nextY < 0.035f || nextY > 0.965f) { angle = -angle; nextY = y + sin(angle).toFloat() * speed * dt }
            if (obstacles.any { it.contains(nextX, nextY) }) {
                angle += if (random.nextBoolean()) PI / 2 else -PI / 2
                nextX = x + cos(angle).toFloat() * speed * dt
                nextY = y + sin(angle).toFloat() * speed * dt
            }
            x = nextX.coerceIn(0.035f, 0.965f)
            y = nextY.coerceIn(0.035f, 0.965f)
        }
    }
}
