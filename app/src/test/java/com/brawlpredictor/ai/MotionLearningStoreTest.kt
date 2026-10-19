package com.brawlpredictor.ai

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class MotionLearningStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("motion_learning_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun startsWithConservativeHorizonAndPersistsObservations() {
        val store = MotionLearningStore(context)
        assertEquals(0.18f, store.horizonSeconds(), 0.0001f)
        repeat(12) { store.recordPredictionError(0.2f) }
        val reopened = MotionLearningStore(context)
        assertEquals(12L, reopened.summary().observations)
        assertTrue("high persistent error should shorten the forecast horizon", reopened.horizonSeconds() < 0.18f)
    }

    @Test fun reliableObservationsCanGraduallyExtendTheForecastHorizon() {
        val store = MotionLearningStore(context)
        val initial = store.horizonSeconds()
        repeat(24) { store.recordPredictionError(0.002f) }
        val reopened = MotionLearningStore(context)
        assertTrue("low prediction residuals should extend the horizon over time", reopened.horizonSeconds() > initial)
        assertTrue(reopened.horizonSeconds() <= 0.30f)
    }

    @Test fun batchRecordPersistsEachValidExperienceAndDirectionChange() {
        val store = MotionLearningStore(context)
        store.recordExperiences(listOf(
            VisualExperience(0.03f, 0.20f, false, "enemy"),
            VisualExperience(0.04f, 0.30f, true, "enemy"),
            VisualExperience(Float.NaN, 0.10f, false, "motion")
        ))
        val summary = MotionLearningStore(context).summary()
        assertEquals(2L, summary.observations)
        assertEquals(1L, summary.directionChanges)
        assertEquals(2, summary.savedExperiences)
    }

    @Test fun ignoresInvalidErrors() {
        val store = MotionLearningStore(context)
        store.recordPredictionError(Float.NaN)
        store.recordPredictionError(-1f)
        assertEquals(0L, store.summary().observations)
        assertTrue(store.summary().meanNormalizedError >= 0f)
    }
}
