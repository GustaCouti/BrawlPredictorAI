package com.brawlpredictor.ai

import android.content.Context
import com.brawlpredictor.ai.core.ScenarioReport

/**
 * Persists online visual-motion experiences, not fabricated match results. Raw records are a
 * compact rolling history; aggregate error statistics adapt the forecast horizon conservatively.
 */
class MotionLearningStore(context: Context) {
    private val prefs = context.getSharedPreferences("motion_learning_v2", Context.MODE_PRIVATE)

    @Synchronized
    fun recordPredictionError(normalizedError: Float) = recordExperiences(
        listOf(VisualExperience(normalizedError, 0f, false, "motion"))
    )

    @Synchronized
    fun recordPredictionErrors(errors: List<Float>) = recordExperiences(
        errors.map { VisualExperience(it, 0f, false, "motion") }
    )

    @Synchronized
    fun recordExperience(normalizedError: Float, normalizedSpeed: Float, directionChanged: Boolean, source: String = "enemy") =
        recordExperiences(listOf(VisualExperience(normalizedError, normalizedSpeed, directionChanged, source)))

    /** One disk preference update per frame/batch rather than one full XML rewrite per target. */
    @Synchronized
    fun recordExperiences(experiences: List<VisualExperience>) {
        val valid = experiences.filter {
            it.normalizedError.isFinite() && it.normalizedError >= 0f &&
                it.normalizedSpeed.isFinite() && it.normalizedSpeed >= 0f
        }
        if (valid.isEmpty()) return
        var count = prefs.getLong(KEY_COUNT, 0L)
        var mean = prefs.getFloat(KEY_MEAN_ERROR, valid.first().normalizedError.coerceIn(0f, 1.5f))
        var meanSpeed = prefs.getFloat(KEY_MEAN_SPEED, 0f)
        var turns = prefs.getLong(KEY_TURNS, 0L)
        val previous = prefs.getString(KEY_JOURNAL, "") ?: ""
        val journal = if (previous.isBlank()) mutableListOf() else previous.split(';').takeLast(MAX_JOURNAL_ITEMS).toMutableList()
        val now = System.currentTimeMillis()
        for ((index, item) in valid.withIndex()) {
            val error = item.normalizedError.coerceIn(0f, 1.5f)
            val speed = item.normalizedSpeed.coerceIn(0f, 2f)
            val alpha = if (count < 20L) 0.12f else 0.06f
            mean = if (count == 0L) error else mean * (1f - alpha) + error * alpha
            meanSpeed = if (count == 0L) speed else meanSpeed * 0.92f + speed * 0.08f
            if (item.directionChanged) turns++
            count++
            val source = if (item.source == "motion") "motion" else "enemy"
            journal.add("${now + index},${"%.4f".format(java.util.Locale.US, error)},${"%.4f".format(java.util.Locale.US, speed)},${if (item.directionChanged) 1 else 0},$source")
        }
        val trimmedJournal = journal.takeLast(MAX_JOURNAL_ITEMS).joinToString(";")
        val trainedPrior = prefs.getFloat(KEY_TRAINED_HORIZON, DEFAULT_HORIZON).coerceIn(MIN_HORIZON, MAX_HORIZON)
        val currentHorizon = prefs.getFloat(KEY_CURRENT_HORIZON, trainedPrior).coerceIn(MIN_HORIZON, MAX_HORIZON)
        // Ignore the first few noisy measurements. Thereafter, adapt the stored horizon gradually
        // from observed one-step tracking residuals rather than rewriting it on every frame read.
        val nextHorizon = if (count >= MIN_ADAPTIVE_SAMPLES) {
            val targetHorizon = horizonTarget(mean, trainedPrior)
            (currentHorizon * (1f - HORIZON_ADAPT_ALPHA) + targetHorizon * HORIZON_ADAPT_ALPHA)
                .coerceIn(MIN_HORIZON, MAX_HORIZON)
        } else currentHorizon
        prefs.edit()
            .putLong(KEY_COUNT, count)
            .putFloat(KEY_MEAN_ERROR, mean)
            .putFloat(KEY_MEAN_SPEED, meanSpeed)
            .putLong(KEY_TURNS, turns)
            .putString(KEY_JOURNAL, trimmedJournal)
            .putFloat(KEY_CURRENT_HORIZON, nextHorizon)
            .apply()
    }

    /** Returns the persisted, conservatively adapted horizon. Reading it never writes preferences. */
    @Synchronized fun horizonSeconds(): Float {
        val prior = prefs.getFloat(KEY_TRAINED_HORIZON, DEFAULT_HORIZON).coerceIn(MIN_HORIZON, MAX_HORIZON)
        return prefs.getFloat(KEY_CURRENT_HORIZON, prior).coerceIn(MIN_HORIZON, MAX_HORIZON)
    }

    private fun horizonTarget(meanError: Float, simulationPrior: Float): Float = when {
        !meanError.isFinite() -> simulationPrior
        meanError >= 0.14f -> 0.06f
        meanError >= 0.11f -> 0.075f
        meanError >= 0.085f -> 0.09f
        meanError >= 0.060f -> 0.11f
        meanError >= 0.040f -> simulationPrior.coerceAtMost(0.14f)
        meanError >= 0.025f -> simulationPrior.coerceAtMost(0.18f)
        meanError >= 0.015f -> maxOf(simulationPrior, 0.18f)
        meanError >= 0.008f -> maxOf(simulationPrior, 0.22f)
        else -> maxOf(simulationPrior, 0.26f)
    }.coerceIn(MIN_HORIZON, MAX_HORIZON)

    @Synchronized fun summary(): LearningSummary {
        val journal = prefs.getString(KEY_JOURNAL, "") ?: ""
        val stored = if (journal.isBlank()) 0 else journal.split(';').count { it.isNotBlank() }
        return LearningSummary(
            observations = prefs.getLong(KEY_COUNT, 0L),
            meanNormalizedError = prefs.getFloat(KEY_MEAN_ERROR, 0f),
            predictionHorizonSeconds = horizonSeconds(),
            directionChanges = prefs.getLong(KEY_TURNS, 0L),
            meanNormalizedSpeed = prefs.getFloat(KEY_MEAN_SPEED, 0f),
            savedExperiences = stored,
            simulatedScenarios = prefs.getLong(KEY_SIM_SCENARIOS, 0L),
            simulatedRecall = prefs.getFloat(KEY_SIM_RECALL, 0f),
            simulatedMeanPositionError = prefs.getFloat(KEY_SIM_POSITION_ERROR, 0f),
            simulatedSwitchRate = prefs.getFloat(KEY_SIM_SWITCH_RATE, 0f),
            simulationRuntimeMs = prefs.getLong(KEY_SIM_RUNTIME, 0L)
        )
    }

    @Synchronized fun hasCurrentSimulationTraining(): Boolean = prefs.getInt(KEY_SIM_VERSION, 0) >= SIMULATION_VERSION

    @Synchronized fun saveSimulationTraining(report: ScenarioReport) {
        if (report.scenarios <= 0 || !report.candidateHorizonSeconds.isFinite()) return
        prefs.edit()
            .putInt(KEY_SIM_VERSION, SIMULATION_VERSION)
            .putLong(KEY_SIM_SCENARIOS, report.scenarios.toLong())
            .putLong(KEY_SIM_FRAMES, report.simulatedFrames)
            .putFloat(KEY_SIM_RECALL, report.detectionRecall.coerceIn(0f, 1f))
            .putFloat(KEY_SIM_POSITION_ERROR, report.meanPositionError.coerceIn(0f, 1.5f))
            .putFloat(KEY_SIM_SWITCH_RATE, report.associationSwitchRate.coerceIn(0f, 1f))
            .putFloat(KEY_TRAINED_HORIZON, report.candidateHorizonSeconds.coerceIn(MIN_HORIZON, MAX_HORIZON))
            .putFloat(KEY_CURRENT_HORIZON, report.candidateHorizonSeconds.coerceIn(MIN_HORIZON, MAX_HORIZON))
            .putLong(KEY_SIM_RUNTIME, report.runtimeMs.coerceAtLeast(0L))
            .apply()
    }

    /** Useful to inspect learning in a debug screen or after a test session. */
    @Synchronized fun recentExperiences(): List<String> {
        val raw = prefs.getString(KEY_JOURNAL, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(';').takeLast(MAX_JOURNAL_ITEMS)
    }

    @Synchronized fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_COUNT = "observations"
        private const val KEY_MEAN_ERROR = "mean_normalized_error"
        private const val KEY_MEAN_SPEED = "mean_normalized_speed"
        private const val KEY_TURNS = "direction_changes"
        private const val KEY_JOURNAL = "recent_experiences"
        private const val MAX_JOURNAL_ITEMS = 160
        private const val DEFAULT_HORIZON = 0.18f
        private const val MIN_HORIZON = 0.06f
        private const val MAX_HORIZON = 0.30f
        private const val MIN_ADAPTIVE_SAMPLES = 8L
        private const val HORIZON_ADAPT_ALPHA = 0.12f
        private const val KEY_CURRENT_HORIZON = "current_prediction_horizon"
        private const val SIMULATION_VERSION = 2
        private const val KEY_SIM_VERSION = "simulation_model_version"
        private const val KEY_SIM_SCENARIOS = "simulated_scenarios"
        private const val KEY_SIM_FRAMES = "simulated_frames"
        private const val KEY_SIM_RECALL = "simulation_recall"
        private const val KEY_SIM_POSITION_ERROR = "simulation_position_error"
        private const val KEY_SIM_SWITCH_RATE = "simulation_switch_rate"
        private const val KEY_SIM_RUNTIME = "simulation_runtime_ms"
        private const val KEY_TRAINED_HORIZON = "simulation_trained_horizon"
    }
}

data class VisualExperience(
    val normalizedError: Float,
    val normalizedSpeed: Float,
    val directionChanged: Boolean,
    val source: String = "enemy"
)

data class LearningSummary(
    val observations: Long,
    val meanNormalizedError: Float,
    val predictionHorizonSeconds: Float,
    val directionChanges: Long = 0L,
    val meanNormalizedSpeed: Float = 0f,
    val savedExperiences: Int = 0,
    val simulatedScenarios: Long = 0L,
    val simulatedRecall: Float = 0f,
    val simulatedMeanPositionError: Float = 0f,
    val simulatedSwitchRate: Float = 0f,
    val simulationRuntimeMs: Long = 0L
)
