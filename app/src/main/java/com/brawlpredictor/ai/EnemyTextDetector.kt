package com.brawlpredictor.ai

import android.graphics.Bitmap
import android.graphics.Rect
import com.brawlpredictor.ai.core.EnemyNameScorer
import com.brawlpredictor.ai.core.EnemyObservation
import com.brawlpredictor.ai.core.EnemyTrackingCore
import com.brawlpredictor.ai.core.MotionHint
import com.brawlpredictor.ai.core.AdvancedPredictionEngine
import com.brawlpredictor.ai.core.MotionSample
import com.brawlpredictor.ai.core.Vec2
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.hypot

/**
 * Offline OCR + color/geometry evidence + temporal multi-object tracking.
 * OCR quality and tracking confidence are heuristic scores, not calibrated probabilities.
 */
class EnemyTextDetector(context: android.content.Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "BrawlEnemyTracking") }
    private val tracker = EnemyTrackingCore()
    private val learning = MotionLearningStore(context.applicationContext)
    private val advanced = AdvancedPredictionEngine()
    private val histories = mutableMapOf<Long, MutableList<MotionSample>>()

    val isBusy: Boolean get() = busy.get()

    /** Takes ownership of bitmap and recycles it after OCR completes. */
    fun process(bitmap: Bitmap) {
        if (closed.get() || !busy.compareAndSet(false, true)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        PredictionState.ocrStatus = "OCR processando quadro"
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> submitResult(bitmap) {
                    val now = System.currentTimeMillis()
                    val candidates = mutableListOf<EnemyObservation>()
                    var rawElements = 0
                    // Score complete OCR lines instead of individual words. Player names can
                    // contain spaces; word-level OCR fragments would create duplicate tracks.
                    for (block in result.textBlocks) for (line in block.lines) {
                        rawElements++
                        val box = line.boundingBox ?: continue
                        val label = line.text.trim().replace(Regex("\\s+"), " ")
                        val redRatio = redPixelRatio(bitmap, box)
                        val nearbyBarRatio = nearbyHealthBarRedRatio(bitmap, box)
                        // Text hue, name-like geometry, and a nearby enemy-style health bar are
                        // combined; weak red alone is not enough to create a visible marker.
                        val quality = EnemyNameScorer.score(
                            label, box.left, box.top, box.right, box.bottom,
                            bitmap.width, bitmap.height, redRatio, nearbyBarRatio
                        ) ?: continue
                        val anchorX = (box.left + box.right) / (2f * bitmap.width)
                        val padY = max(box.height() * 2.0f, bitmap.height * 0.0075f)
                        val anchorY = ((box.bottom + padY) / bitmap.height.toFloat()).coerceIn(0f, 0.985f)
                        candidates.add(EnemyObservation(
                            label = label,
                            x = anchorX.coerceIn(0f, 1f), y = anchorY,
                            textLeft = box.left.toFloat() / bitmap.width,
                            textTop = box.top.toFloat() / bitmap.height,
                            textRight = box.right.toFloat() / bitmap.width,
                            textBottom = box.bottom.toFloat() / bitmap.height,
                            quality = quality,
                            redRatio = redRatio,
                            observedAtMs = now
                        ))
                    }
                    val deduplicated = deduplicate(candidates)
                    PredictionState.ocrFrames++
                    PredictionState.ocrCandidates += deduplicated.size
                    PredictionState.falseCandidateFilters += (rawElements - deduplicated.size).coerceAtLeast(0)
                    val tracked = tracker.update(deduplicated, now, bitmap.width.toFloat() / bitmap.height.toFloat())
                    learning.recordExperiences(tracker.drainOutcomes().map { outcome ->
                        VisualExperience(outcome.normalizedPredictionError, outcome.speed, outcome.directionChanged, "enemy")
                    })
                    PredictionState.syncTrackedEnemies(tracked)
                    updateAdvancedForecasts(tracked, now)
                    PredictionState.predictionHorizonSeconds = learning.horizonSeconds()
                    val freshCount = tracked.count { !it.predictedOnly }
                    PredictionState.ocrStatus = "${deduplicated.size} candidato(s) OCR · $freshCount alvo(s) atualizado(s), ${tracked.size - freshCount} previsto(s)"
                } }
                .addOnFailureListener { submitResult(bitmap) {
                    PredictionState.ocrFailures++
                    val now = System.currentTimeMillis()
                    val tracked = tracker.update(emptyList(), now, bitmap.width.toFloat() / bitmap.height.toFloat())
                    PredictionState.syncTrackedEnemies(tracked)
                    updateAdvancedForecasts(tracked, now)
                    PredictionState.predictionHorizonSeconds = learning.horizonSeconds()
                    PredictionState.ocrStatus = "Leitura OCR falhou · rastros recentes mantidos"
                } }
                .addOnCompleteListener {
                    // processResult normally owns the bitmap. The failure/success callbacks enqueue work;
                    // freeing it here would race the queued pixel sampling, so ownership stays queued.
                }
        } catch (_: Exception) {
            PredictionState.ocrFailures++
            PredictionState.ocrStatus = "Falha ao iniciar OCR"
            finishWithoutPixels(bitmap)
        }
    }

    /**
     * Uses frame-difference movement only to gently refine already OCR-confirmed targets between
     * text reads. It cannot create a named target and does not reset the OCR expiry timer.
     */
    fun refineWithMotion(predictions: List<MotionPrediction>) {
        if (closed.get() || PredictionState.getEnemyMarkers().isEmpty()) return
        val horizon = PredictionState.predictionHorizonSeconds.coerceIn(0.06f, 0.26f)
        val hints = predictions.asSequence()
            .filter { it.confidence >= 0.48f && it.bounds.width() > 0f && it.bounds.height() > 0f }
            .map { prediction ->
                MotionHint(
                    x = prediction.x,
                    y = prediction.y,
                    vx = ((prediction.futureX - prediction.x) / horizon).coerceIn(-0.9f, 0.9f),
                    vy = ((prediction.futureY - prediction.y) / horizon).coerceIn(-0.9f, 0.9f),
                    confidence = prediction.confidence,
                    width = prediction.bounds.width(),
                    height = prediction.bounds.height()
                )
            }.toList()
        val now = System.currentTimeMillis()
        val aspect = PredictionState.screenWidth.toFloat() / PredictionState.screenHeight.coerceAtLeast(1).toFloat()
        PredictionState.syncTrackedEnemies(tracker.refineWithMotion(hints, now, aspect))
    }

    private fun updateAdvancedForecasts(tracked: List<com.brawlpredictor.ai.core.TrackedEnemy>, now: Long) {
        val active = tracked.filter { !it.predictedOnly && it.confidence >= 0.35f }
        val cues = mutableListOf<AdvancedCue>()
        for (enemy in active) {
            val history = histories.getOrPut(enemy.trackId) { mutableListOf() }
            advanced.observeActual(enemy.trackId, Vec2(enemy.x.toDouble(), enemy.y.toDouble()))
            if (history.isEmpty() || now - history.last().timeMs >= 35L) {
                history.add(MotionSample(Vec2(enemy.x.toDouble(), enemy.y.toDouble()), now))
                while (history.size > 20) history.removeAt(0)
            }
            if (history.size < 3) continue
            val seconds = PredictionState.predictionHorizonSeconds.coerceIn(0.06f, 0.26f)
            val result = advanced.forecast(enemy.trackId, history.toList(), (seconds * 1000f).toLong(), minConfidence = 0.24)
            if (result.abstained) continue
            result.candidates.take(5).forEach { candidate ->
                cues.add(AdvancedCue(enemy.trackId, candidate.position.x.toFloat(), candidate.position.y.toFloat(),
                    candidate.probability.toFloat().coerceIn(0f, 1f), result.uncertaintyRadius.toFloat(), candidate.model))
            }
        }
        val liveIds = tracked.map { it.trackId }.toSet()
        histories.keys.retainAll(liveIds)
        PredictionState.setAdvancedCues(cues)
    }

    private fun submitResult(bitmap: Bitmap, work: () -> Unit) {
        try {
            executor.execute {
                try { if (!closed.get()) work() }
                catch (_: Exception) { PredictionState.ocrFailures++ }
                finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!bitmap.isRecycled) bitmap.recycle()
            busy.set(false)
        }
    }

    private fun finishWithoutPixels(bitmap: Bitmap) {
        try {
            executor.execute {
                try {
                    val now = System.currentTimeMillis()
                    PredictionState.syncTrackedEnemies(tracker.update(emptyList(), now, bitmap.width.toFloat() / bitmap.height.toFloat()))
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (_: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            busy.set(false)
        }
    }

    private fun deduplicate(input: List<EnemyObservation>): List<EnemyObservation> {
        val sorted = input.sortedByDescending { it.quality }
        val result = mutableListOf<EnemyObservation>()
        for (candidate in sorted) {
            val duplicate = result.any { existing ->
                val dx = (candidate.x - existing.x) * 0.56f
                val dy = candidate.y - existing.y
                val near = hypot(dx, dy) < 0.018f
                val a = existing.label.lowercase().filter { it.isLetterOrDigit() }
                val b = candidate.label.lowercase().filter { it.isLetterOrDigit() }
                near && (a == b || editSimilarity(a, b) > 0.72f)
            }
            if (!duplicate) result.add(candidate)
        }
        return result
    }

    private fun editSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            for (j in previous.indices) previous[j] = current[j]
        }
        return 1f - current[b.length].toFloat() / max(a.length, b.length).toFloat()
    }

    private fun nearbyHealthBarRedRatio(bitmap: Bitmap, nameBox: Rect): Float {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0f
        val boxHeight = nameBox.height().coerceAtLeast(1)
        // The bar can sit below a small health-number line, so sample a slim strip under the name.
        val sampleWidth = max(nameBox.width() * 3 / 4, boxHeight * 3).coerceAtMost(bitmap.width)
        val centerX = (nameBox.left + nameBox.right) / 2
        val left = (centerX - sampleWidth / 2).coerceIn(0, bitmap.width - 1)
        val right = (left + sampleWidth).coerceIn(left + 1, bitmap.width)
        val top = (nameBox.bottom + (boxHeight * 0.45f).toInt()).coerceIn(0, bitmap.height - 1)
        val bottom = (nameBox.bottom + (boxHeight * 3.4f).toInt()).coerceIn(top + 1, bitmap.height)
        return redPixelRatio(bitmap, Rect(left, top, right, bottom))
    }

    private fun redPixelRatio(bitmap: Bitmap, rect: Rect): Float {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0f
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val sampleStride = max(1, max(right - left, bottom - top) / 22)
        var sampled = 0
        var redPixels = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (EnemyNameScorer.isRedPixel(bitmap.getPixel(x, y))) redPixels++
                sampled++
                x += sampleStride
            }
            y += sampleStride
        }
        return if (sampled == 0) 0f else redPixels.toFloat() / sampled
    }

    fun clearTracks() {
        tracker.clear()
        histories.clear()
        PredictionState.setAdvancedCues(emptyList())
        PredictionState.setEnemyMarkers(emptyList())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        histories.clear()
        PredictionState.setAdvancedCues(emptyList())
        try { recognizer.close() } catch (_: Exception) {}
        executor.shutdown()
    }
}
