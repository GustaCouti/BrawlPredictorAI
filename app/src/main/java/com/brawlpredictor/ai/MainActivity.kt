package com.brawlpredictor.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusPill: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Color.rgb(12, 15, 24)
    private val panel = Color.rgb(23, 28, 42)
    private val muted = Color.rgb(157, 169, 190)
    private val cyan = Color.rgb(70, 214, 225)
    private val violet = Color.rgb(137, 116, 255)

    private val refresh = object : Runnable {
        override fun run() {
            if (::status.isInitialized) {
                val learning = MotionLearningStore(applicationContext).summary()
                val markers = PredictionState.getEnemyMarkers()
                status.text = "QUADROS ANALISADOS   ${PredictionState.analyzedFrames}\n" +
                    "Leituras OCR   ${PredictionState.ocrFrames}   ·   Falhas   ${PredictionState.ocrFailures}\n" +
                    "Candidatos filtrados   ${PredictionState.falseCandidateFilters}\n" +
                    "Candidatos aceitos   ${PredictionState.ocrCandidates}\n\n" +
                    "RASTREIO\nMarcadores ativos   ${markers.size}\n" +
                    "Movimentos em zigue-zague   ${markers.count { it.zigzagScore >= 0.25f }}\n\n" +
                    "APRENDIZADO LOCAL\nExperiências salvas   ${learning.savedExperiences}/160\n" +
                    "Observações   ${learning.observations}   ·   Mudanças de direção   ${learning.directionChanges}\n" +
                    "Horizonte de previsão   ${"%.2f".format(Locale.US, learning.predictionHorizonSeconds)} s\n" +
                    "Erro visual médio   ${"%.4f".format(Locale.US, learning.meanNormalizedError)}\n\n" +
                    "Simulação sintética   ${PredictionState.simulationStatus}"
                statusPill.text = PredictionState.status.uppercase(Locale.getDefault())
            }
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        scroll.addView(root)

        val eyebrow = label("VISION • TRACKING • LEARNING", 11f, cyan, true).apply {
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(9))
        }
        root.addView(eyebrow, matchWrap())
        val title = label("BRAWL COACH", 30f, Color.WHITE, true)
        root.addView(title, matchWrap())
        val aiTitle = label("AI  /  PREDICTOR", 18f, violet, true).apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(aiTitle, matchWrap())
        root.addView(label("Análise visual e previsão curta de movimento para Brawl Stars.", 14f, muted, false).apply {
            setPadding(0, 0, 0, dp(20))
        }, matchWrap())

        val statusCard = card()
        statusCard.addView(label("STATUS DO SISTEMA", 11f, muted, true), matchWrap())
        statusPill = label("PRONTO PARA INICIAR", 13f, cyan, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(Color.rgb(22, 58, 68), 12)
        }
        val pillParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pillParams.topMargin = dp(12)
        statusCard.addView(statusPill, pillParams)
        status = label("Aguardando início da captura…", 13f, Color.rgb(218, 225, 239), false).apply {
            setPadding(0, dp(14), 0, 0)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        statusCard.addView(status, matchWrap())
        root.addView(statusCard, withBottom(matchWrap(), 14))

        root.addView(actionCard("▶   INICIAR CAPTURA E PREVISÃO", "Autorizar captura de tela do Android", cyan) { startPrediction() }, withBottom(matchWrap(), 10))
        root.addView(actionCard("◎   ATIVAR SOBREPOSIÇÃO", "Exibir os marcadores por cima do jogo", violet) { enableOverlay() }, withBottom(matchWrap(), 10))
        root.addView(actionCard("■   PARAR SERVIÇOS", "Encerrar captura e sobreposição", Color.rgb(255, 125, 139)) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            PredictionState.status = "Parado"
            statusPill.text = "PARADO"
        }, withBottom(matchWrap(), 18))

        val info = card()
        info.addView(label("O QUE A IA FAZ", 13f, Color.WHITE, true), matchWrap())
        info.addView(label(
            "• Procura nomes com evidência de cor, geometria e repetição temporal.\n" +
            "• Mantém IDs internos e suaviza a trajetória entre leituras.\n" +
            "• Faz previsões curtas durante oclusões e descarta rastros antigos.\n" +
            "• Guarda estatísticas e experiências visuais localmente no aparelho.",
            13f, muted, false
        ).apply { setPadding(0, dp(10), 0, dp(16)); setLineSpacing(dp(5).toFloat(), 1f) }, matchWrap())
        info.addView(label("LIMITES ATUAIS", 13f, Color.rgb(255, 205, 120), true), matchWrap())
        info.addView(label(
            "A análise é experimental. Não controla o jogo nem toca automaticamente na tela. " +
            "Os marcadores são estimativas visuais, não hitboxes confirmadas. A simulação sintética " +
            "não representa partidas reais nem garante vitórias.", 12f, muted, false
        ).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(dp(4).toFloat(), 1f) }, matchWrap())
        root.addView(info, matchWrap())
        root.addView(label("BRAWL COACH AI  ·  PROCESSAMENTO LOCAL", 10f, Color.rgb(100, 111, 132), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
        }, matchWrap())

        setContentView(scroll)
        SyntheticTrainingCoordinator.startIfNeeded(applicationContext)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(panel, 18)
        elevation = dp(2).toFloat()
    }

    private fun actionCard(title: String, detail: String, accent: Int, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(panel, 16, accent)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(label(title, 14f, accent, true), matchWrap())
        addView(label(detail, 12f, muted, false).apply { setPadding(0, dp(5), 0, 0) }, matchWrap())
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        gravity = Gravity.START
        includeFontPadding = true
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun withBottom(params: LinearLayout.LayoutParams, marginDp: Int) = params.apply { bottomMargin = dp(marginDp) }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun startPrediction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            PredictionState.status = "Autorize Exibir sobre outros apps e toque em iniciar novamente"
            return
        }
        startService(Intent(this, OverlayService::class.java))
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        PredictionState.status = "Aguardando autorização de captura"
    }

    private fun enableOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            PredictionState.status = "Autorize Exibir sobre outros apps"
            return
        }
        startService(Intent(this, OverlayService::class.java))
        PredictionState.status = "Sobreposição ativa; aguardando captura"
    }

    @Deprecated("Uses activity result API for compatibility with older Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            PredictionState.status = "Captura autorizada; calibrando movimento"
        } else {
            stopService(Intent(this, OverlayService::class.java))
            PredictionState.status = "Captura cancelada pelo usuário"
        }
    }

    companion object { private const val REQUEST_CAPTURE = 4107 }
}
