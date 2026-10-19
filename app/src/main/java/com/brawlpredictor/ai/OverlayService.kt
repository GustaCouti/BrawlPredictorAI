package com.brawlpredictor.ai

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Read-only visual guidance overlay; it never sends taps or controls the game. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var overlay: PredictionView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val redraw = object : Runnable {
        override fun run() {
            overlay?.invalidate()
            handler.postDelayed(this, 65L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = PredictionView(); overlay = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, params)
        handler.post(redraw)
    }

    override fun onDestroy() {
        handler.removeCallbacks(redraw)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class PredictionView : View(this@OverlayService) {
        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity
        private val enemyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FF343D.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
        }
        private val enemyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1CFF303A; style = Paint.Style.FILL
        }
        private val predictedOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44FF565D.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.9f * density
        }
        private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB8FFFFFF.toInt(); textSize = 11f * scaledDensity; style = Paint.Style.FILL
            setShadowLayer(2f * density, 1f * density, 1f * density, Color.BLACK)
        }
        private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA3F9CFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.0f * density
        }
        private val blueFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x223F9CFF; style = Paint.Style.FILL
        }
        private val uncertaintyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x18FFB020; style = Paint.Style.FILL
        }
        private val probabilityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFD166.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.1f * density
        }
        private val probabilityText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); textSize = 9f * scaledDensity; style = Paint.Style.FILL
            setShadowLayer(2f * density, 1f * density, 1f * density, Color.BLACK)
        }
        private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x7710131B.toInt(); style = Paint.Style.FILL
        }
        private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDEAF5FF.toInt(); textSize = 11f * scaledDensity; style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val all = PredictionState.getEnemyMarkers()
            val enemies = all.filter {
                val age = now - it.observedAtMs
                age in 0L..620L && it.confidence >= 0.28f
            }
            val fresh = enemies.count { !it.predictedOnly }
            val predicted = enemies.size - fresh
            val panelRight = minOf(width - 8f * density, 310f * density)
            canvas.drawRoundRect(8f * density, 12f * density, panelRight, 42f * density, 7f * density, 7f * density, hudBg)
            canvas.drawText("BRAWL COACH · $fresh alvo(s) · $predicted estimado(s)", 14f * density, 31f * density, hudText)

            for (enemy in enemies) {
                val x = enemy.x * width
                val y = enemy.y * height
                val rx = (enemy.radiusX * width).coerceIn(8f * density, 30f * density)
                val ry = (enemy.radiusY * height).coerceIn(12f * density, 36f * density)
                val age = (now - enemy.observedAtMs).coerceAtLeast(0L)
                // Fade short occlusion predictions rather than leave a stale red marker indefinitely.
                val opacity = (1f - age / 700f).coerceIn(0.15f, 1f)
                enemyFill.alpha = (28 * opacity).toInt()
                enemyOutline.alpha = ((if (enemy.predictedOnly) 74 else 136) * opacity).toInt()
                canvas.drawOval(x - rx, y - ry, x + rx, y + ry, enemyFill)
                canvas.drawOval(x - rx, y - ry, x + rx, y + ry,
                    if (enemy.predictedOnly) predictedOutline.apply { alpha = (68 * opacity).toInt() } else enemyOutline)
                namePaint.alpha = ((if (enemy.predictedOnly) 120 else 184) * opacity).toInt()
                val label = enemy.label.take(20)
                val labelWidth = namePaint.measureText(label)
                val labelX = (x - labelWidth / 2f).coerceIn(4f, (width - labelWidth - 4f).coerceAtLeast(4f))
                canvas.drawText(label, labelX, (y - ry - 5f).coerceIn(18f, height - 8f), namePaint)
                if (!enemy.predictedOnly && enemy.confidence > 0.55f && (kotlin.math.abs(enemy.vx) + kotlin.math.abs(enemy.vy)) > 0.08f) {
                    val baseHorizon = PredictionState.predictionHorizonSeconds.coerceIn(0.06f, 0.26f)
                    val personalFactor = (1f - enemy.zigzagScore * 0.62f).coerceIn(0.38f, 1f)
                    val horizon = baseHorizon * personalFactor
                    val futureX = (x + enemy.vx * horizon * width).coerceIn(0f, width.toFloat())
                    val futureY = (y + enemy.vy * horizon * height).coerceIn(0f, height.toFloat())
                    canvas.drawLine(x, y, futureX, futureY, blue)
                    canvas.drawCircle(futureX, futureY, 3f * density, blueFill)
                }
            }

            // Amber multimodal cues are alternatives, not certainties. Radius visualizes model disagreement.
            PredictionState.getAdvancedCues().forEach { cue ->
                val x = cue.x * width
                val y = cue.y * height
                val radius = (cue.uncertaintyRadius * minOf(width, height)).coerceIn(5f * density, 48f * density)
                uncertaintyPaint.alpha = (24 + cue.probability * 48).toInt().coerceIn(20, 72)
                probabilityPaint.alpha = (55 + cue.probability * 150).toInt().coerceIn(55, 205)
                canvas.drawCircle(x, y, radius, uncertaintyPaint)
                canvas.drawCircle(x, y, (3f + cue.probability * 5f) * density, probabilityPaint)
                val percent = "${(cue.probability * 100f).toInt()}%"
                canvas.drawText(percent, (x + 5f * density).coerceIn(2f, width - 35f * density), (y - 4f * density).coerceIn(16f, height - 4f), probabilityText)
            }

            // Blue cues are generic moving regions, not proof of an enemy or of an incoming attack.
            PredictionState.get().take(12).forEach { p ->
                if (p.confidence < 0.35f) return@forEach
                val x = p.x * width; val y = p.y * height
                val fx = p.futureX * width; val fy = p.futureY * height
                canvas.drawLine(x, y, fx, fy, blue)
                canvas.drawCircle(fx, fy, 6f * density, blueFill)
                canvas.drawCircle(fx, fy, 6f * density, blue)
            }
        }
    }
}
