package com.brawlpredictor.ai

import android.content.Context
import com.brawlpredictor.ai.core.OfflineScenarioLab
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the fixed, reproducible 10k-scenario lab once per model version, off the UI thread. */
object SyntheticTrainingCoordinator {
    private const val SCENARIO_COUNT = 10_000
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BrawlSyntheticTraining").apply { isDaemon = true }
    }

    fun startIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val store = MotionLearningStore(appContext)
        if (store.hasCurrentSimulationTraining()) {
            val saved = store.summary()
            PredictionState.simulationStatus = "${saved.simulatedScenarios} cenários sintéticos salvos · recall ${(saved.simulatedRecall * 100).toInt()}% · não são partidas reais"
            return
        }
        if (!running.compareAndSet(false, true)) return
        PredictionState.simulationStatus = "Executando $SCENARIO_COUNT cenários sintéticos em segundo plano…"
        executor.execute {
            try {
                val report = OfflineScenarioLab.run(scenarios = SCENARIO_COUNT, seed = 0xB4A771L)
                store.saveSimulationTraining(report)
                PredictionState.simulationStatus = String.format(
                    Locale.US,
                    "%d cenários · recall %.1f%% · erro médio %.4f · troca de identidade %.2f%% · horizonte %.2fs · %d ms. Simulação, não partidas reais.",
                    report.scenarios, report.detectionRecall * 100f, report.meanPositionError,
                    report.associationSwitchRate * 100f, report.candidateHorizonSeconds, report.runtimeMs
                )
            } catch (error: Exception) {
                PredictionState.simulationStatus = "Simulação não concluída: ${error.javaClass.simpleName}; rastreamento continua sem prior simulado"
            } finally {
                running.set(false)
            }
        }
    }
}
