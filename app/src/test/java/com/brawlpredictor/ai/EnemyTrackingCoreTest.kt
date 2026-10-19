package com.brawlpredictor.ai

import com.brawlpredictor.ai.core.EnemyNameScorer
import com.brawlpredictor.ai.core.EnemyObservation
import com.brawlpredictor.ai.core.EnemyTrackingCore
import com.brawlpredictor.ai.core.OfflineScenarioLab
import com.brawlpredictor.ai.core.MotionHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnemyTrackingCoreTest {
    private fun observation(label: String, x: Float, y: Float, time: Long, quality: Float = 0.9f) = EnemyObservation(
        label = label,
        x = x,
        y = y,
        textLeft = (x - 0.025f).coerceAtLeast(0f),
        textTop = (y - 0.05f).coerceAtLeast(0f),
        textRight = (x + 0.025f).coerceAtMost(1f),
        textBottom = (y - 0.03f).coerceAtLeast(0.001f),
        quality = quality,
        redRatio = 0.24f,
        observedAtMs = time
    )

    @Test fun trackIdentityPersistsDuringMotionAndNameNoise() {
        val tracker = EnemyTrackingCore()
        val first = tracker.update(listOf(observation("PlayerAlpha", .20f, .40f, 1000L)), 1000L, .56f).single()
        val second = tracker.update(listOf(observation("PlayerAlph0", .23f, .41f, 1160L)), 1160L, .56f)
            .minByOrNull { kotlin.math.abs(it.x - .23f) }
        assertNotNull(second)
        assertEquals(first.trackId, second!!.trackId)
        assertTrue(second.x > first.x)
    }

    @Test fun separateTargetsKeepSeparateInternalIdsEvenWithSimilarNames() {
        val tracker = EnemyTrackingCore()
        val first = tracker.update(listOf(
            observation("EnemyOne", .20f, .30f, 1000L),
            observation("EnemyTwo", .78f, .68f, 1000L)
        ), 1000L, .56f)
        assertEquals(2, first.size)
        val ids = first.map { it.trackId }.toSet()
        val next = tracker.update(listOf(
            observation("EnemyOne", .25f, .31f, 1160L),
            observation("EnemyTwo", .73f, .66f, 1160L)
        ), 1160L, .56f)
        assertEquals(ids, next.map { it.trackId }.toSet())
        assertEquals(next.map { it.trackId }.size, next.map { it.trackId }.toSet().size)
    }

    @Test fun shortOcclusionProducesFadingPredictionThenExpires() {
        val tracker = EnemyTrackingCore()
        val initial = tracker.update(listOf(observation("HiddenTarget", .45f, .40f, 1000L)), 1000L, .56f).single()
        val occluded = tracker.update(emptyList(), 1300L, .56f)
        assertTrue(occluded.isNotEmpty())
        assertTrue(occluded.first().predictedOnly)
        assertTrue(occluded.first().confidence < initial.confidence)
        assertTrue(tracker.update(emptyList(), 2200L, .56f).isEmpty())
    }

    @Test fun weakOrInvalidTextIsIgnored() {
        val tracker = EnemyTrackingCore()
        val weak = observation("x", .4f, .4f, 1000L, .2f)
        val invalid = observation("NaN", Float.NaN, .4f, 1000L)
        assertTrue(tracker.update(listOf(weak, invalid), 1000L, .56f).isEmpty())
    }


    @Test fun redNameScoringUsesColorAndGeometryTogether() {
        val plausible = EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.22f)
        assertNotNull(plausible)
        assertTrue(plausible!! >= 0.54f)
        assertEquals(null, EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.02f))
        assertEquals(null, EnemyNameScorer.score("BUY", 20, 100, 600, 180, 1080, 1920, 0.42f))
        assertTrue(EnemyNameScorer.isRedPixel(0xFFFF2020.toInt()))
        assertTrue("pink enemy name should pass the color gate", EnemyNameScorer.isRedPixel(0xFFF2B6CA.toInt()))
        assertFalseRedPixel(0xFFFFFFFF.toInt())
        val weakColorButPlausibleName = EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.04f)
        assertNotNull("text geometry plus weak color should remain a candidate for temporal confirmation", weakColorButPlausibleName)
        assertTrue(weakColorButPlausibleName!! < 0.86f)
        assertNotNull(EnemyNameScorer.score("EnemyName", 100, 300, 175, 320, 1080, 1920, 0.03f, 0.20f))
        assertEquals(null, EnemyNameScorer.score("3920", 100, 300, 175, 320, 1080, 1920, 0.25f))
        assertEquals(null, EnemyNameScorer.score("EnemyName", -1, 300, 175, 320, 1080, 1920, 0.22f))
    }

    private fun assertFalseRedPixel(argb: Int) { assertTrue(!EnemyNameScorer.isRedPixel(argb)) }


    @Test fun motionHintsRefineExistingIdentityButCannotCreateAnEnemy() {
        val tracker = EnemyTrackingCore()
        val initial = tracker.update(listOf(observation("TrackedEnemy", .40f, .40f, 1000L)), 1000L, .56f).single()
        val refined = tracker.refineWithMotion(
            listOf(MotionHint(.42f, .41f, .10f, .05f, .72f, .04f, .05f)),
            1125L, .56f
        )
        assertEquals(1, refined.size)
        assertEquals(initial.trackId, refined.single().trackId)
        assertTrue(refined.single().predictedOnly)
        assertTrue(refined.single().x > initial.x)
        tracker.clear()
        assertTrue(tracker.refineWithMotion(
            listOf(MotionHint(.50f, .50f, .1f, .1f, .9f, .04f, .05f)), 1500L, .56f
        ).isEmpty())
    }

    @Test fun suddenDirectionReversalRaisesErraticMovementSignal() {
        val tracker = EnemyTrackingCore()
        tracker.update(listOf(observation("Dodger", .20f, .40f, 1000L)), 1000L, .56f)
        tracker.update(listOf(observation("Dodger", .30f, .40f, 1160L)), 1160L, .56f)
        val afterReversal = tracker.update(listOf(observation("Dodger", .20f, .40f, 1320L)), 1320L, .56f)
            .singleOrNull() ?: error("Target should retain its ID through a reversal")
        assertTrue("Reversal signal should rise after a sharp direction change", afterReversal.zigzagScore > 0f)
        assertTrue(afterReversal.zigzagScore <= 1f)
    }

    @Test fun tenThousandSyntheticScenariosMeetBaselineThresholds() {
        val report = OfflineScenarioLab.run(10_000, 0xB4A771L)
        assertEquals(10_000, report.scenarios)
        assertTrue(report.simulatedFrames >= 250_000L)
        assertTrue("recall ${report.detectionRecall}", report.detectionRecall >= 0.94f)
        assertTrue("switch rate ${report.associationSwitchRate}", report.associationSwitchRate <= 0.02f)
        assertTrue("position error ${report.meanPositionError}", report.meanPositionError <= 0.02f)
        assertTrue(report.candidateHorizonSeconds in 0.09f..0.26f)
    }
}
