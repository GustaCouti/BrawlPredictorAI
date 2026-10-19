package com.brawlpredictor.ai

import android.graphics.RectF
import com.brawlpredictor.ai.core.TrackedEnemy
import java.util.concurrent.atomic.AtomicReference

data class MotionPrediction(
    val x: Float,
    val y: Float,
    val futureX: Float,
    val futureY: Float,
    /** Heuristic score, not a calibrated probability. */
    val confidence: Float,
    val speed: Float,
    val bounds: RectF
)

data class AdvancedCue(
    val trackId: Long,
    val x: Float,
    val y: Float,
    val probability: Float,
    val uncertaintyRadius: Float,
    val model: String
)

data class EnemyMarker(
    val trackId: Long,
    val label: String,
    val x: Float,
    val y: Float,
    val textLeft: Float,
    val textTop: Float,
    val textRight: Float,
    val textBottom: Float,
    val confidence: Float,
    val redRatio: Float,
    val observedAtMs: Long,
    val vx: Float,
    val vy: Float,
    val radiusX: Float,
    val radiusY: Float,
    val observations: Int,
    val zigzagScore: Float,
    val predictedOnly: Boolean
)

object PredictionState {
    private val ref = AtomicReference<List<MotionPrediction>>(emptyList())
    private val enemyRef = AtomicReference<List<EnemyMarker>>(emptyList())
    private val advancedRef = AtomicReference<List<AdvancedCue>>(emptyList())
    @Volatile var screenWidth: Int = 1
    @Volatile var screenHeight: Int = 1
    @Volatile var predictionHorizonSeconds: Float = 0.18f
    @Volatile var lastUpdateMs: Long = 0L
    @Volatile var analyzedFrames: Long = 0L
    @Volatile var ocrFrames: Long = 0L
    @Volatile var ocrFailures: Long = 0L
    @Volatile var ocrCandidates: Long = 0L
    @Volatile var falseCandidateFilters: Long = 0L
    @Volatile var status: String = "Aguardando captura de tela"
    @Volatile var simulationStatus: String = "Simulação sintética ainda não executada"
    @Volatile var ocrStatus: String = "OCR aguardando captura"

    fun get(): List<MotionPrediction> = ref.get()
    fun set(value: List<MotionPrediction>) { ref.set(value); lastUpdateMs = System.currentTimeMillis() }
    fun getEnemyMarkers(): List<EnemyMarker> = enemyRef.get()
    fun getAdvancedCues(): List<AdvancedCue> = advancedRef.get()
    fun setAdvancedCues(value: List<AdvancedCue>) { advancedRef.set(value.take(30)) }
    fun setEnemyMarkers(value: List<EnemyMarker>) { enemyRef.set(value) }

    fun syncTrackedEnemies(items: List<TrackedEnemy>) {
        val now = System.currentTimeMillis()
        enemyRef.set(items.map { item ->
            EnemyMarker(
                trackId = item.trackId,
                label = item.label,
                x = item.x.coerceIn(0f, 1f),
                y = item.y.coerceIn(0f, 1f),
                textLeft = item.textLeft,
                textTop = item.textTop,
                textRight = item.textRight,
                textBottom = item.textBottom,
                confidence = item.confidence,
                redRatio = item.redRatio,
                observedAtMs = item.lastSeenAtMs,
                vx = item.vx,
                vy = item.vy,
                radiusX = item.radiusX,
                radiusY = item.radiusY,
                observations = item.observations,
                zigzagScore = item.zigzagScore,
                predictedOnly = item.predictedOnly
            )
        })
        if (items.isNotEmpty()) lastUpdateMs = now
    }
}
