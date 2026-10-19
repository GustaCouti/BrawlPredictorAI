package com.brawlpredictor.ai.core

import kotlin.math.max

/** Shared, deterministic gates for red-name OCR candidates; all scores remain heuristic. */
object EnemyNameScorer {
    fun score(
        text: String,
        boxLeft: Int,
        boxTop: Int,
        boxRight: Int,
        boxBottom: Int,
        imageWidth: Int,
        imageHeight: Int,
        redPixelRatio: Float,
        /** Fraction of red pixels in the narrow area immediately below the candidate text. */
        nearbyBarRedRatio: Float = 0f
    ): Float? {
        if (imageWidth < 1 || imageHeight < 1) return null
        val label = text.trim().replace(Regex("\\s+"), " ")
        if (label.length !in 2..28 || label.none { it.isLetter() }) return null
        val letterCount = label.count { it.isLetter() }
        if (letterCount < 2 && label.count { it.isLetterOrDigit() } < 3) return null
        if (boxLeft < 0 || boxTop < 0 || boxRight > imageWidth || boxBottom > imageHeight ||
            boxRight <= boxLeft || boxBottom <= boxTop) return null
        val boxWidth = boxRight - boxLeft
        val boxHeight = boxBottom - boxTop
        if (boxWidth < max(8, imageWidth / 120) || boxHeight < max(6, imageHeight / 240)) return null
        if (boxWidth > imageWidth * 0.43f || boxHeight > imageHeight * 0.055f) return null
        val aspect = boxWidth.toFloat() / boxHeight.coerceAtLeast(1)
        if (aspect !in 0.65f..19f) return null
        // Keep a small chroma floor, but do not require a large red ratio: pink names,
        // compression, anti-aliasing and shadows can weaken the measured red pixels.
        if (!redPixelRatio.isFinite() || redPixelRatio < 0.015f || redPixelRatio > 0.92f) return null
        if (!nearbyBarRedRatio.isFinite() || nearbyBarRedRatio !in 0f..1f) return null
        val nearbyBar = nearbyBarRedRatio >= 0.085f && nearbyBarRedRatio <= 0.80f
        // Low-red text can be a pink/anti-aliased player name, but needs other context to survive.
        if (redPixelRatio < 0.035f && !nearbyBar) return null
        val centerY = (boxTop + boxBottom) * 0.5f / imageHeight
        if ((centerY < 0.035f || centerY > 0.965f) && label.length < 4 && !nearbyBar) return null

        val alphanumeric = label.count { it.isLetterOrDigit() }.toFloat() / label.length.coerceAtLeast(1)
        val colorSignal = ((redPixelRatio - 0.025f) / 0.38f).coerceIn(0f, 1f)
        val sizeSignal = if (boxHeight in 7..70) 1f else 0.55f
        val lengthSignal = if (label.length >= 4) 0.08f else 0f
        val shapeSignal = if (aspect in 1.8f..13.5f) 0.08f else 0f
        val contextSignal = if (nearbyBar) 0.10f else 0f
        val raw = (0.31f + 0.31f * colorSignal + 0.16f * alphanumeric + 0.06f * sizeSignal +
            lengthSignal + shapeSignal + contextSignal).coerceIn(0f, 0.94f)
        // If color evidence is weak, require multiple frames before a marker is shown. Conversely,
        // nearly solid-red text often comes from HUD/alerts and is also capped pending confirmation.
        return when {
            redPixelRatio < 0.075f && !nearbyBar -> raw.coerceAtMost(0.70f)
            redPixelRatio > 0.50f && !nearbyBar -> raw.coerceAtMost(0.78f)
            else -> raw
        }
    }

    fun isRedPixel(argb: Int): Boolean {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        // Include the pink/red hue used by some enemy name labels, not only saturated pure red.
        return r > 105 && r - g > 22 && r - b > 14 && r > g * 1.12f && r > b * 1.07f
    }
}
