package com.brawlpredictor.ai.core

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Normalized OCR candidate. Coordinates are relative to the captured screen (0..1). */
data class EnemyObservation(
    val label: String,
    val x: Float,
    val y: Float,
    val textLeft: Float,
    val textTop: Float,
    val textRight: Float,
    val textBottom: Float,
    /** A heuristic ranking score, not a calibrated probability. */
    val quality: Float,
    val redRatio: Float,
    val observedAtMs: Long
)

data class TrackOutcome(val normalizedPredictionError: Float, val speed: Float, val directionChanged: Boolean)

data class MotionHint(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val confidence: Float,
    val width: Float,
    val height: Float
)

data class TrackedEnemy(
    val trackId: Long,
    val label: String,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val textLeft: Float,
    val textTop: Float,
    val textRight: Float,
    val textBottom: Float,
    val radiusX: Float,
    val radiusY: Float,
    /** A heuristic track-confidence score. It is not a calibrated probability. */
    val confidence: Float,
    val quality: Float,
    val redRatio: Float,
    val observations: Int,
    /** Smoothed recent direction-reversal signal; not a probability that a dodge will occur. */
    val zigzagScore: Float,
    val lastSeenAtMs: Long,
    val ageMs: Long,
    val predictedOnly: Boolean
)

/**
 * Lightweight multi-target tracker for OCR detections. Uses predicted position + gated
 * association, smoothed velocity, short occlusion bridging, and unique internal IDs.
 * It does not claim to recover the game's collision hitbox from pixels.
 */
class EnemyTrackingCore(
    private val expiryMs: Long = 950L,
    private val predictionHoldMs: Long = 620L,
    private val minimumQuality: Float = 0.54f
) {
    private data class Track(
        val id: Long,
        var label: String,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        var radiusX: Float,
        var radiusY: Float,
        var confidence: Float,
        var quality: Float,
        var redRatio: Float,
        var observations: Int,
        var zigzagScore: Float,
        var lastSeenMs: Long,
        var lastStateMs: Long,
        var lastObservedX: Float,
        var lastObservedY: Float,
        var predictedOnly: Boolean = false
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1L
    private val pendingOutcomes = mutableListOf<TrackOutcome>()

    @Synchronized
    fun update(observations: List<EnemyObservation>, nowMs: Long, screenAspect: Float): List<TrackedEnemy> {
        val aspect = screenAspect.takeIf { it.isFinite() && it > 0f } ?: (9f / 16f)
        pendingOutcomes.clear()

        // Move existing tracks forward before matching the newly observed boxes.
        tracks.forEach { track ->
            val dt = ((nowMs - track.lastStateMs).coerceAtLeast(0L) / 1000f).coerceAtMost(1.2f)
            if (dt > 0f) {
                track.x = (track.x + track.vx * dt).coerceIn(0f, 1f)
                track.y = (track.y + track.vy * dt).coerceIn(0f, 1f)
                track.lastStateMs = nowMs
            }
            track.predictedOnly = true
            val age = (nowMs - track.lastSeenMs).coerceAtLeast(0L)
            track.confidence = (track.confidence * exp(-dt / 0.62f)).coerceIn(0f, 1f)
            if (age > 0L) track.quality = (track.quality * exp(-dt / 1.1f)).coerceIn(0f, 1f)
        }

        val assigned = HashSet<Long>()
        observations.asSequence()
            .filter { valid(it) && it.quality >= minimumQuality }
            .sortedByDescending { it.quality }
            .forEach { observation ->
                var best: Track? = null
                var bestCost = Float.MAX_VALUE
                for (track in tracks) {
                    if (track.id in assigned) continue
                    val age = nowMs - track.lastSeenMs
                    if (age > expiryMs) continue
                    val labelSimilarity = similarity(normalizeLabel(track.label), normalizeLabel(observation.label))
                    val dx = (observation.x - track.x) * aspect
                    val dy = observation.y - track.y
                    val distance = hypot(dx, dy)
                    val secondsSinceSeen = (age.coerceAtLeast(40L) / 1000f).coerceAtMost(1.0f)
                    val predictedTravel = hypot(track.vx * aspect, track.vy) * secondsSinceSeen
                    val gate = when {
                        labelSimilarity >= 0.94f -> (0.09f + predictedTravel * 2.4f).coerceAtMost(0.28f)
                        labelSimilarity >= 0.68f -> (0.055f + predictedTravel * 1.95f).coerceAtMost(0.20f)
                        else -> (0.025f + predictedTravel * 1.15f).coerceAtMost(0.075f)
                    }
                    // Name similarity widens the gate only with a motion-dependent bound; it does
                    // not allow an arbitrary map-wide jump when stale/duplicate OCR labels occur.
                    if (distance > gate) continue
                    val cost = distance + (1f - labelSimilarity) * 0.035f - if (labelSimilarity > 0.94f) 0.018f else 0f
                    if (cost < bestCost) { bestCost = cost; best = track }
                }
                if (best == null) {
                    val id = nextId++
                    val rX = estimateRadiusX(observation, aspect)
                    val rY = estimateRadiusY(observation)
                    tracks.add(Track(
                        id, observation.label, observation.x.coerceIn(0f, 1f), observation.y.coerceIn(0f, 1f),
                        0f, 0f, observation.textLeft, observation.textTop, observation.textRight, observation.textBottom,
                        rX, rY, observation.quality * 0.78f, observation.quality,
                        observation.redRatio.coerceIn(0f, 1f), 1, 0f,
                        observation.observedAtMs, nowMs, observation.x, observation.y, false
                    ))
                    assigned.add(id)
                } else {
                    val track = best
                    assigned.add(track.id)
                    val dt = ((nowMs - track.lastSeenMs).coerceAtLeast(40L) / 1000f).coerceAtMost(1.0f)
                    val residualX = observation.x - track.x
                    val residualY = observation.y - track.y
                    val normalizedPredictionError = hypot(residualX * aspect, residualY).coerceIn(0f, 1.5f)
                    val measuredVx = ((observation.x - track.lastObservedX) / dt).coerceIn(-0.9f, 0.9f)
                    val measuredVy = ((observation.y - track.lastObservedY) / dt).coerceIn(-0.9f, 0.9f)
                    val measuredSpeed = hypot(measuredVx * aspect, measuredVy)
                    val previousSpeed = hypot(track.vx * aspect, track.vy)
                    val directionCosine = if (previousSpeed > 0.0001f && measuredSpeed > 0.0001f) {
                        ((track.vx * aspect) * (measuredVx * aspect) + track.vy * measuredVy) /
                            (previousSpeed * measuredSpeed)
                    } else 1f
                    val directionChanged = previousSpeed >= 0.075f && measuredSpeed >= 0.075f && directionCosine < -0.30f
                    // The per-track reversal signal makes the lead shorter for erratic movement instead
                    // of pretending a constant-velocity forecast is equally reliable for every enemy.
                    track.zigzagScore = (track.zigzagScore * 0.78f + if (directionChanged) 0.22f else 0f).coerceIn(0f, 1f)
                    pendingOutcomes.add(TrackOutcome(normalizedPredictionError, measuredSpeed, directionChanged))
                    // Alpha-beta filter: correct predicted position, then gently update velocity.
                    track.x = (track.x + 0.72f * residualX).coerceIn(0f, 1f)
                    track.y = (track.y + 0.72f * residualY).coerceIn(0f, 1f)
                    track.vx = (0.62f * track.vx + 0.38f * measuredVx).coerceIn(-0.9f, 0.9f)
                    track.vy = (0.62f * track.vy + 0.38f * measuredVy).coerceIn(-0.9f, 0.9f)
                    track.left = smooth(track.left, observation.textLeft)
                    track.top = smooth(track.top, observation.textTop)
                    track.right = smooth(track.right, observation.textRight)
                    track.bottom = smooth(track.bottom, observation.textBottom)
                    val rx = estimateRadiusX(observation, aspect)
                    val ry = estimateRadiusY(observation)
                    track.radiusX = smooth(track.radiusX, rx)
                    track.radiusY = smooth(track.radiusY, ry)
                    // Prefer fresh OCR text only when it is not a one-frame OCR corruption.
                    if (similarity(normalizeLabel(track.label), normalizeLabel(observation.label)) < 0.68f && observation.quality >= track.quality + 0.12f) {
                        track.label = observation.label
                    } else if (similarity(normalizeLabel(track.label), normalizeLabel(observation.label)) >= 0.68f) {
                        track.label = observation.label
                    }
                    track.quality = (0.62f * track.quality + 0.38f * observation.quality).coerceIn(0f, 1f)
                    track.redRatio = smooth(track.redRatio, observation.redRatio)
                    track.confidence = (0.68f * track.confidence + 0.32f * observation.quality + 0.06f).coerceIn(0f, 0.98f)
                    track.observations++
                    track.lastSeenMs = nowMs
                    track.lastStateMs = nowMs
                    track.lastObservedX = observation.x
                    track.lastObservedY = observation.y
                    track.predictedOnly = false
                }
            }

        tracks.removeAll { nowMs - it.lastSeenMs > expiryMs || it.confidence < 0.14f }
        return snapshots(nowMs)
    }

    /**
     * Refines recent OCR tracks with nearby moving regions between OCR passes. Motion is only a
     * weak hint: it cannot create a named enemy, raise OCR identity confidence, or extend the OCR TTL.
     */
    @Synchronized
    fun refineWithMotion(hints: List<MotionHint>, nowMs: Long, screenAspect: Float): List<TrackedEnemy> {
        val aspect = screenAspect.takeIf { it.isFinite() && it > 0f } ?: (9f / 16f)
        val usedHints = HashSet<Int>()
        tracks.forEach { track ->
            val dt = ((nowMs - track.lastStateMs).coerceAtLeast(0L) / 1000f).coerceAtMost(0.20f)
            if (dt > 0f) {
                track.x = (track.x + track.vx * dt).coerceIn(0f, 1f)
                track.y = (track.y + track.vy * dt).coerceIn(0f, 1f)
                track.lastStateMs = nowMs
            }
            track.predictedOnly = true
        }
        for (track in tracks.sortedByDescending { it.confidence }) {
            if (nowMs - track.lastSeenMs > predictionHoldMs || track.confidence < 0.30f) continue
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            for ((index, hint) in hints.withIndex()) {
                if (index in usedHints || hint.confidence < 0.48f) continue
                if (!hint.x.isFinite() || !hint.y.isFinite() || !hint.vx.isFinite() || !hint.vy.isFinite()) continue
                // Reject near-full-screen flashes and tiny projectile/effect blobs as body-location hints.
                if (hint.width !in 0.008f..0.16f || hint.height !in 0.010f..0.20f) continue
                val distance = hypot((hint.x - track.x) * aspect, hint.y - track.y)
                val gate = (0.035f + hypot(track.vx * aspect, track.vy) * 0.12f).coerceAtMost(0.075f)
                if (distance < gate && distance < bestDistance) { bestDistance = distance; bestIndex = index }
            }
            if (bestIndex >= 0) {
                val hint = hints[bestIndex]
                usedHints.add(bestIndex)
                // Gentle correction avoids snapping the red marker to an unrelated moving effect.
                track.x = (0.74f * track.x + 0.26f * hint.x).coerceIn(0f, 1f)
                track.y = (0.74f * track.y + 0.26f * hint.y).coerceIn(0f, 1f)
                track.vx = (0.82f * track.vx + 0.18f * hint.vx).coerceIn(-0.9f, 0.9f)
                track.vy = (0.82f * track.vy + 0.18f * hint.vy).coerceIn(-0.9f, 0.9f)
                track.lastStateMs = nowMs
                track.predictedOnly = true
            }
        }
        return snapshots(nowMs)
    }

    @Synchronized fun drainOutcomes(): List<TrackOutcome> = pendingOutcomes.toList().also { pendingOutcomes.clear() }

    @Synchronized fun clear() { tracks.clear(); pendingOutcomes.clear() }

    private fun snapshots(nowMs: Long): List<TrackedEnemy> = tracks.asSequence()
        .filter { track ->
            val age = (nowMs - track.lastSeenMs).coerceAtLeast(0L)
            // A single high-quality OCR observation may be shown briefly during occlusion.
            // Do not base this decision only on decaying quality: that made a strong first
            // detection disappear immediately on the first empty frame. Keep the stricter
            // confidence floor and short prediction TTL to avoid long-lived false positives.
            val sufficientlyConfirmed = track.observations >= 2 ||
                track.quality >= 0.86f ||
                (track.observations == 1 && age <= predictionHoldMs && track.confidence >= 0.40f)
            sufficientlyConfirmed && age <= predictionHoldMs && track.confidence >= 0.28f
        }
        .sortedByDescending { it.confidence }
        .map { track ->
            val age = (nowMs - track.lastSeenMs).coerceAtLeast(0L)
            TrackedEnemy(
                trackId = track.id, label = track.label,
                x = track.x, y = track.y, vx = track.vx, vy = track.vy,
                textLeft = track.left.coerceIn(0f, 1f), textTop = track.top.coerceIn(0f, 1f),
                textRight = track.right.coerceIn(0f, 1f), textBottom = track.bottom.coerceIn(0f, 1f),
                radiusX = track.radiusX, radiusY = track.radiusY,
                confidence = track.confidence, quality = track.quality, redRatio = track.redRatio,
                observations = track.observations, zigzagScore = track.zigzagScore,
                lastSeenAtMs = track.lastSeenMs, ageMs = age, predictedOnly = track.predictedOnly
            )
        }.toList()

    private fun valid(o: EnemyObservation): Boolean =
        o.label.trim().length >= 2 && o.x.isFinite() && o.y.isFinite() &&
            o.textLeft.isFinite() && o.textTop.isFinite() && o.textRight.isFinite() && o.textBottom.isFinite() &&
            o.quality.isFinite() && o.redRatio.isFinite() &&
            o.x in 0f..1f && o.y in 0f..1f && o.textLeft < o.textRight && o.textTop < o.textBottom

    /** Approximate character-body marker from the name's glyph height, not its total width. */
    private fun estimateRadiusX(o: EnemyObservation, aspect: Float): Float {
        val glyphHeight = (o.textBottom - o.textTop).coerceAtLeast(0.004f)
        return (glyphHeight * 1.55f / aspect.coerceAtLeast(0.25f)).coerceIn(0.008f, 0.030f)
    }

    private fun estimateRadiusY(o: EnemyObservation): Float {
        val glyphHeight = (o.textBottom - o.textTop).coerceAtLeast(0.004f)
        return (glyphHeight * 1.9f).coerceIn(0.016f, 0.055f)
    }

    private fun smooth(a: Float, b: Float) = (a * 0.40f + b * 0.60f).coerceIn(0f, 1f)

    private fun normalizeLabel(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(32)

    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1f else 0f
        if (a == b) return 1f
        if (a.length > 30 || b.length > 30) return 0f
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(min(curr[j] + 1, prev[j + 1] + 1), prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return 1f - curr[b.length].toFloat() / max(a.length, b.length).toFloat()
    }
}
