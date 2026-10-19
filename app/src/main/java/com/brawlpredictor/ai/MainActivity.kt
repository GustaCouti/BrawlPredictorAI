package com.brawlpredictor.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (::status.isInitialized) {
                val learning = MotionLearningStore(applicationContext).summary()
                val markers = PredictionState.getEnemyMarkers()
                status.text = "Status: ${PredictionState.status}\n${PredictionState.ocrStatus}\nQuadros analisados: ${PredictionState.analyzedFrames} · leituras OCR: ${PredictionState.ocrFrames} · falhas OCR: ${PredictionState.ocrFailures}\nElementos OCR filtrados: ${PredictionState.falseCandidateFilters} · candidatos aceitos acumulados: ${PredictionState.ocrCandidates}\nMarcadores atuais: ${markers.size} · rastros com zigue-zague: ${markers.count { it.zigzagScore >= 0.25f }} · experiências visuais salvas: ${learning.savedExperiences}/160\nObservações online: ${learning.observations} · mudanças de direção observadas: ${learning.directionChanges}\nHorizonte adaptativo: ${"%.2f".format(java.util.Locale.US, learning.predictionHorizonSeconds)} s · erro médio visual: ${"%.4f".format(java.util.Locale.US, learning.meanNormalizedError)}\nSimulação sintética: ${PredictionState.simulationStatus}"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 19, 27)
        window.navigationBarColor = Color.rgb(16, 19, 27)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(Color.rgb(16, 19, 27))
        }
        val title = TextView(this).apply {
            text = "BRAWL COACH AI"
            textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Percepção visual e previsão de movimento · Brawl Stars"
            textSize = 14f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, 12, 0, 22)
        }
        status = TextView(this).apply {
            text = "Status: pronto para iniciar"
            textSize = 14f; setTextColor(Color.rgb(180, 210, 240)); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 18)
        }
        val start = Button(this).apply {
            text = "INICIAR CAPTURA E PREVISÃO"
            setOnClickListener { startPrediction() }
        }
        val overlay = Button(this).apply {
            text = "ATIVAR / REATIVAR SOBREPOSIÇÃO"
            setOnClickListener { enableOverlay() }
        }
        val stop = Button(this).apply {
            text = "PARAR TUDO"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                PredictionState.status = "Parado"
                status.text = "Status: parado"
            }
        }
        val note = TextView(this).apply {
            text = "COMO FUNCIONA\n• A captura é autorizada explicitamente pelo Android. A análise é local.\n• O OCR procura texto com evidência de cor vermelha, usando forma, posição e repetição temporal; vermelho sozinho não define um inimigo.\n• Os marcadores usam IDs internos, velocidade suavizada e previsão curta quando uma leitura some. O tamanho é apenas uma estimativa visual, nunca a hitbox real confirmada.\n• O laboratório executa 10.000 cenários sintéticos com estilos de movimento, obstáculos, ruído no OCR e oclusões. Isso testa associação e calibra um horizonte inicial; não equivale a partidas, usuários ou vitórias reais.\n• As experiências visuais da sessão e o erro médio ficam salvos localmente para adaptar o horizonte.\n\nLIMITES IMPORTANTES\nO aplicativo não controla o jogo, não toca na tela automaticamente e ainda não identifica com confiabilidade projéteis, ataques recebidos, resultado de acertos ou intenção de esquiva. Portanto, não aprende estratégia de combate real ainda. O marcador estima a região do personagem a partir do nome, não mede a hitbox interna do jogo."
            textSize = 13f; setTextColor(Color.rgb(190, 198, 210)); setPadding(4, 24, 4, 0)
        }
        listOf(title, subtitle, status, start, overlay, stop, note).forEach { root.addView(it, matchWrap()) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        SyntheticTrainingCoordinator.startIfNeeded(applicationContext)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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
