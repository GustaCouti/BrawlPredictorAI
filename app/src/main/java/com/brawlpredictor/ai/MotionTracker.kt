package com.brawlpredictor.ai

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight frame-difference tracker. It detects moving regions, never classifies them as enemies.
 * Reuses frame/grid buffers and batches persistence so garbage collection and disk writes do not run
 * for every target on every frame.
 */
class MotionTracker(private val learning: MotionLearningStore) {
    private var previous = ByteArray(0)
    private var prevW = 0
    private var prevH = 0
    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    private var gridW = 0
    private var gridH = 0
    private var active = BooleanArray(0)
    private var seen = BooleanArray(0)
    private var energy = IntArray(0)
    private var queueX = IntArray(0)
    private var queueY = IntArray(0)
    private val pendingErrors = ArrayList<Float>(96)
    private var lastLearningFlushMs = 0L

    private data class Blob(
        val x: Float, val y: Float,
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
        val energy: Int, val pixels: Int
    )
    private data class Track(
        val id: Int, var x: Float, var y: Float, var vx: Float, var vy: Float,
        var hits: Int, var missed: Int, var energy: Int, var box: RectF,
        var lastMs: Long
    )

    fun process(luma: ByteArray, width: Int, height: Int): List<MotionPrediction> {
        val nowMs = System.currentTimeMillis()
        if (width < 2 || height < 2 || luma.size < width * height) return emptyList()
        if (prevW != width || prevH != height || previous.size != luma.size) {
            previous = luma.copyOf()
            prevW = width
            prevH = height
            tracks.clear()
            allocateGrid(width, height)
            PredictionState.status = "Calibrando movimento…"
            return emptyList()
        }

        // The grid is coarse by design; reading all pixels would waste CPU on mostly static UI.
        val step = 4
        val gw = gridW
        val gh = gridH
        val cellCount = gw * gh
        active.fill(false)
        energy.fill(0)
        seen.fill(false)
        var activeCount = 0
        for (gy in 1 until gh - 1) for (gx in 1 until gw - 1) {
            var diff = 0
            var samples = 0
            for (oy in 0..2) for (ox in 0..2) {
                val px = gx * step + ox * (step / 2)
                val py = gy * step + oy * (step / 2)
                val i = py * width + px
                if (i < luma.size) {
                    val current = luma[i].toInt() and 0xff
                    val old = previous[i].toInt() and 0xff
                    diff += abs(current - old)
                    samples++
                }
            }
            val avg = if (samples > 0) diff / samples else 0
            val idx = gy * gw + gx
            energy[idx] = avg
            if (avg >= DIFFERENCE_THRESHOLD) {
                active[idx] = true
                activeCount++
            }
        }

        // A screen transition / flashing frame can make nearly everything appear to move.
        // Do not turn that single frame into dozens of fake targets.
        if (activeCount > (cellCount * SCENE_CHANGE_ACTIVE_RATIO).toInt()) {
            copyCurrentFrame(luma)
            tracks.clear()
            flushLearningIfDue(nowMs, force = true)
            PredictionState.analyzedFrames++
            PredictionState.set(emptyList())
            PredictionState.status = "Mudança de cena detectada · recalibrando movimento"
            return emptyList()
        }

        val blobs = mutableListOf<Blob>()
        for (sy in 1 until gh - 1) for (sx in 1 until gw - 1) {
            val start = sy * gw + sx
            if (!active[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queueX[tail] = sx; queueY[tail] = sy; tail++
            seen[start] = true
            var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
            var totalX = 0f; var totalY = 0f; var count = 0; var totalEnergy = 0
            while (head < tail) {
                val x = queueX[head]; val y = queueY[head]; head++
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                totalX += x; totalY += y; count++; totalEnergy += energy[y * gw + x]
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx <= 0 || ny <= 0 || nx >= gw - 1 || ny >= gh - 1) continue
                    val ni = ny * gw + nx
                    if (active[ni] && !seen[ni] && tail < queueX.size) {
                        seen[ni] = true; queueX[tail] = nx; queueY[tail] = ny; tail++
                    }
                }
            }
            // Reject isolated noise, oversized flashes, and thin scan-line artifacts.
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val area = boxW * boxH
            val fillRatio = count.toFloat() / area.coerceAtLeast(1)
            if (count >= MIN_BLOB_PIXELS && count <= cellCount / 7 &&
                boxW <= gw * 0.34f && boxH <= gh * 0.34f && fillRatio >= 0.035f) {
                blobs.add(Blob(
                    x = totalX / count * step,
                    y = totalY / count * step,
                    minX = minX * step,
                    minY = minY * step,
                    maxX = (maxX + 1) * step,
                    maxY = (maxY + 1) * step,
                    energy = totalEnergy,
                    pixels = count
                ))
            }
        }
        copyCurrentFrame(luma)

        // Bound work on noisy frames (particles, bushes and attack effects can create many blobs).
        val boundedBlobs = blobs.sortedByDescending { it.energy }.take(MAX_BLOBS)
        tracks.forEach { it.missed++ }
        val used = HashSet<Int>(boundedBlobs.size)
        for (blob in boundedBlobs) {
            var best: Track? = null
            var bestDistance = Float.MAX_VALUE
            for (track in tracks) {
                if (track.id in used) continue
                val dt = ((nowMs - track.lastMs).coerceAtLeast(1L) / 1000f).coerceIn(0.04f, 0.8f)
                val px = track.x + track.vx * dt
                val py = track.y + track.vy * dt
                val distance = hypot(blob.x - px, blob.y - py)
                val gate = (max(width, height) * 0.045f + hypot(track.vx, track.vy) * dt * 1.1f)
                    .coerceIn(max(width, height) * 0.035f, max(width, height) * 0.105f)
                if (distance < gate && distance < bestDistance) {
                    bestDistance = distance
                    best = track
                }
            }
            val box = RectF(blob.minX.toFloat(), blob.minY.toFloat(), blob.maxX.toFloat(), blob.maxY.toFloat())
            if (best == null) {
                if (tracks.size < MAX_TRACKS) {
                    val created = Track(nextId++, blob.x, blob.y, 0f, 0f, 1, 0, blob.energy, box, nowMs)
                    tracks.add(created)
                    used.add(created.id)
                }
            } else {
                val track = best
                used.add(track.id)
                val dt = ((nowMs - track.lastMs).coerceAtLeast(1L) / 1000f).coerceIn(0.04f, 0.8f)
                val predictedX = track.x + track.vx * dt
                val predictedY = track.y + track.vy * dt
                val residual = hypot(blob.x - predictedX, blob.y - predictedY) / max(width, height).toFloat()
                if (residual.isFinite()) pendingErrors.add(residual)
                val rawVx = ((blob.x - track.x) / dt).coerceIn(-width.toFloat() * 1.2f, width.toFloat() * 1.2f)
                val rawVy = ((blob.y - track.y) / dt).coerceIn(-height.toFloat() * 1.2f, height.toFloat() * 1.2f)
                track.vx = (track.vx * 0.55f + rawVx * 0.45f).coerceIn(-width.toFloat(), width.toFloat())
                track.vy = (track.vy * 0.55f + rawVy * 0.45f).coerceIn(-height.toFloat(), height.toFloat())
                track.x = blob.x; track.y = blob.y; track.hits++; track.missed = 0
                track.energy = blob.energy; track.box = box; track.lastMs = nowMs
            }
        }
        tracks.removeAll { it.missed > MAX_MISSED_FRAMES || nowMs - it.lastMs > TRACK_TTL_MS }
        flushLearningIfDue(nowMs, force = pendingErrors.size >= MAX_PENDING_ERRORS)

        val horizon = learning.horizonSeconds()
        PredictionState.predictionHorizonSeconds = horizon
        val predictions = tracks.asSequence()
            .filter { it.hits >= 2 && it.missed == 0 && hypot(it.vx, it.vy) > MIN_SPEED_PIXELS_PER_SECOND }
            .sortedByDescending { it.energy }
            .take(MAX_PREDICTIONS)
            .map { track ->
                val futureX = (track.x + track.vx * horizon).coerceIn(0f, width.toFloat())
                val futureY = (track.y + track.vy * horizon).coerceIn(0f, height.toFloat())
                val confidence = (0.35f + min(0.55f, track.hits * 0.06f)).coerceIn(0.1f, 0.9f)
                MotionPrediction(
                    track.x / width, track.y / height, futureX / width, futureY / height,
                    confidence, hypot(track.vx, track.vy),
                    RectF(track.box.left / width, track.box.top / height, track.box.right / width, track.box.bottom / height)
                )
            }.toList()
        PredictionState.analyzedFrames++
        PredictionState.status = if (predictions.isEmpty()) "Captura ativa · aguardando movimento rastreável" else "Captura ativa · ${predictions.size} região(ões) em movimento"
        PredictionState.set(predictions)
        return predictions
    }

    fun reset() {
        flushLearningIfDue(System.currentTimeMillis(), force = true)
        previous = ByteArray(0)
        prevW = 0
        prevH = 0
        tracks.clear()
        pendingErrors.clear()
        gridW = 0
        gridH = 0
        active = BooleanArray(0)
        seen = BooleanArray(0)
        energy = IntArray(0)
        queueX = IntArray(0)
        queueY = IntArray(0)
        lastLearningFlushMs = 0L
        PredictionState.set(emptyList())
    }

    private fun allocateGrid(width: Int, height: Int) {
        gridW = width / GRID_STEP
        gridH = height / GRID_STEP
        val size = gridW * gridH
        active = BooleanArray(size)
        seen = BooleanArray(size)
        energy = IntArray(size)
        queueX = IntArray(size)
        queueY = IntArray(size)
    }

    private fun copyCurrentFrame(luma: ByteArray) {
        if (previous.size != luma.size) previous = ByteArray(luma.size)
        System.arraycopy(luma, 0, previous, 0, luma.size)
    }

    private fun flushLearningIfDue(nowMs: Long, force: Boolean = false) {
        if (pendingErrors.isEmpty()) return
        if (!force && nowMs - lastLearningFlushMs < LEARNING_FLUSH_INTERVAL_MS) return
        learning.recordPredictionErrors(pendingErrors.toList())
        pendingErrors.clear()
        lastLearningFlushMs = nowMs
    }

    companion object {
        private const val GRID_STEP = 4
        private const val DIFFERENCE_THRESHOLD = 24
        private const val MIN_BLOB_PIXELS = 3
        private const val MAX_BLOBS = 48
        private const val MAX_TRACKS = 48
        private const val MAX_PREDICTIONS = 12
        private const val MAX_MISSED_FRAMES = 4
        private const val TRACK_TTL_MS = 700L
        private const val LEARNING_FLUSH_INTERVAL_MS = 800L
        private const val MAX_PENDING_ERRORS = 96
        private const val MIN_SPEED_PIXELS_PER_SECOND = 1.5f
        private const val SCENE_CHANGE_ACTIVE_RATIO = 0.48f
    }
}
