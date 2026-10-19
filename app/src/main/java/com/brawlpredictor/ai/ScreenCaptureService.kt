package com.brawlpredictor.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/** Screen capture service. Every frame is processed locally after explicit Android consent. */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.brawlpredictor.ai.STOP_CAPTURE"
        private const val CHANNEL_ID = "brawl_capture"
        private const val NOTIFICATION_ID = 7412
        private const val MOTION_FRAME_INTERVAL_MS = 125L
        private const val OCR_INTERVAL_MS = 310L
        private const val MAX_OCR_LONG_EDGE = 1600
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    @Volatile private var pendingContentWidth: Int? = null
    @Volatile private var pendingContentHeight: Int? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var tracker: MotionTracker
    private lateinit var enemyTextDetector: EnemyTextDetector
    @Volatile private var lastProcessed = 0L
    @Volatile private var lastOcrMs = 0L
    private var motionLumaBuffer = ByteArray(0)
    private var ocrPixelsBuffer = IntArray(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // Avoid accidentally creating a second projection when the service is already live.
        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = getResultData(intent)
        if (data == null) {
            PredictionState.status = "Permissão de captura não recebida"
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION_ID, notification)
            tracker = MotionTracker(MotionLearningStore(applicationContext))
            enemyTextDetector = EnemyTextDetector(applicationContext)
            beginCapture(resultCode, data)
        } catch (e: Exception) {
            PredictionState.status = "Falha ao iniciar captura: ${e.javaClass.simpleName}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getResultData(intent: Intent?): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    else intent?.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun beginCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection não disponível")
        projection = newProjection
        val metrics = readDisplayMetrics()
        captureWidth = metrics.widthPixels.coerceAtLeast(1)
        captureHeight = metrics.heightPixels.coerceAtLeast(1)
        captureDensity = metrics.densityDpi.coerceAtLeast(1)
        PredictionState.screenWidth = captureWidth
        PredictionState.screenHeight = captureHeight
        thread = HandlerThread("BrawlFrameAnalyzer").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = createReader(captureWidth, captureHeight)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                PredictionState.status = "Captura encerrada pelo Android"
                stopSelf()
            }

            // Android 14+ can share a single app window, whose dimensions may differ from the
            // physical display. Resize the same virtual display instead of creating another one.
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    pendingContentWidth = width
                    pendingContentHeight = height
                    handler?.post { resizeCaptureIfNeeded(width, height) }
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                handler?.post {
                    if (!isVisible) {
                        tracker.reset()
                        enemyTextDetector.clearTracks()
                        PredictionState.status = "Conteúdo capturado fora de vista · rastros limpos"
                    } else {
                        PredictionState.status = "Conteúdo capturado visível · retomando análise"
                    }
                }
            }
        }
        projectionCallback = callback
        newProjection.registerCallback(callback, handler)
        display = newProjection.createVirtualDisplay(
            "BrawlCoachCapture", captureWidth, captureHeight, captureDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        ) ?: throw IllegalStateException("VirtualDisplay não foi criado")
        registerDisplayResizeListener()
        handler?.post { resizeCaptureIfNeeded(pendingContentWidth, pendingContentHeight) }
        PredictionState.status = "Captura ativa · rastreamento local iniciado"
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { imageReader ->
            imageReader.setOnImageAvailableListener({ source -> handleAvailableImage(source) }, handler)
        }
    }

    private fun handleAvailableImage(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
        val now = System.currentTimeMillis()
        if (now - lastProcessed < MOTION_FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastProcessed = now
        try { analyze(image, now) }
        catch (e: Exception) { PredictionState.status = "Falha ao analisar quadro: ${e.javaClass.simpleName}" }
        finally { image.close() }
    }

    private fun registerDisplayResizeListener() {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) handler?.post { resizeCaptureIfNeeded() }
            }
        }
        displayManager = manager
        displayListener = listener
        manager.registerDisplayListener(listener, handler)
    }

    @Suppress("DEPRECATION")
    private fun resizeCaptureIfNeeded(contentWidth: Int? = null, contentHeight: Int? = null) {
        val currentDisplay = display ?: return
        if (projection == null) return
        val metrics = readDisplayMetrics()
        val width = (contentWidth ?: pendingContentWidth ?: metrics.widthPixels).coerceAtLeast(1)
        val height = (contentHeight ?: pendingContentHeight ?: metrics.heightPixels).coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)
        if (width == captureWidth && height == captureHeight && density == captureDensity) return
        val oldReader = reader
        val replacement = createReader(width, height)
        try {
            currentDisplay.resize(width, height, density)
            currentDisplay.setSurface(replacement.surface)
            reader = replacement
            captureWidth = width
            captureHeight = height
            captureDensity = density
            PredictionState.screenWidth = width
            PredictionState.screenHeight = height
            lastProcessed = 0L
            lastOcrMs = 0L
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
            PredictionState.status = "Tela redimensionada · recalibrando rastreamento"
        } catch (error: Exception) {
            // If the new surface is rejected after resize(), restore the old dimensions/surface.
            try { currentDisplay.resize(captureWidth, captureHeight, captureDensity) } catch (_: Exception) {}
            try { oldReader?.let { currentDisplay.setSurface(it.surface) } } catch (_: Exception) {}
            replacement.setOnImageAvailableListener(null, null)
            replacement.close()
            PredictionState.status = "Falha ao redimensionar captura: ${error.javaClass.simpleName}"
        }
    }

    private fun analyze(image: Image, now: Long) {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width < 4 || height < 4 || pixelStride < 4) return

        // Motion analysis uses half resolution and reads the Image buffer directly, avoiding a full
        // frame copy. OCR receives a larger bitmap so small player-name glyphs are not needlessly lost.
        val motionWidth = (width + 1) / 2
        val motionHeight = (height + 1) / 2
        val lumaSize = motionWidth * motionHeight
        if (motionLumaBuffer.size != lumaSize) motionLumaBuffer = ByteArray(lumaSize)
        val luma = motionLumaBuffer
        for (y in 0 until motionHeight) {
            val sourceY = (y * 2).coerceAtMost(height - 1)
            val row = sourceY * rowStride
            val out = y * motionWidth
            for (x in 0 until motionWidth) {
                val sourceX = (x * 2).coerceAtMost(width - 1)
                val index = row + sourceX * pixelStride
                if (index + 2 < buffer.limit()) {
                    val r = buffer.get(index).toInt() and 0xff
                    val g = buffer.get(index + 1).toInt() and 0xff
                    val b = buffer.get(index + 2).toInt() and 0xff
                    luma[out + x] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
                }
            }
        }
        val motionPredictions = tracker.process(luma, motionWidth, motionHeight)
        if (motionPredictions.isNotEmpty()) enemyTextDetector.refineWithMotion(motionPredictions)

        if (now - lastOcrMs < OCR_INTERVAL_MS || enemyTextDetector.isBusy) return
        lastOcrMs = now
        val scale = minOf(1f, MAX_OCR_LONG_EDGE.toFloat() / maxOf(width, height).toFloat())
        val ocrWidth = (width * scale).toInt().coerceAtLeast(1)
        val ocrHeight = (height * scale).toInt().coerceAtLeast(1)
        val pixelCount = ocrWidth * ocrHeight
        if (ocrPixelsBuffer.size != pixelCount) ocrPixelsBuffer = IntArray(pixelCount)
        val pixels = ocrPixelsBuffer
        for (y in 0 until ocrHeight) {
            val sourceY = ((y + 0.5f) * height / ocrHeight).toInt().coerceIn(0, height - 1)
            val row = sourceY * rowStride
            val out = y * ocrWidth
            for (x in 0 until ocrWidth) {
                val sourceX = ((x + 0.5f) * width / ocrWidth).toInt().coerceIn(0, width - 1)
                val index = row + sourceX * pixelStride
                if (index + 2 < buffer.limit()) {
                    val r = buffer.get(index).toInt() and 0xff
                    val g = buffer.get(index + 1).toInt() and 0xff
                    val b = buffer.get(index + 2).toInt() and 0xff
                    pixels[out + x] = Color.rgb(r, g, b)
                }
            }
        }
        // Bitmap.createBitmap copies the colors; the reusable array can safely serve the next frame.
        enemyTextDetector.process(Bitmap.createBitmap(pixels, ocrWidth, ocrHeight, Bitmap.Config.ARGB_8888))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Captura do Brawl Coach AI", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("Brawl Coach AI ativo")
            .setContentText("Analisando tela localmente. Encerre pelo botão PARAR TUDO.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        try { displayManager?.let { manager -> displayListener?.let { manager.unregisterDisplayListener(it) } } } catch (_: Exception) {}
        displayListener = null; displayManager = null
        pendingContentWidth = null; pendingContentHeight = null
        motionLumaBuffer = ByteArray(0); ocrPixelsBuffer = IntArray(0)
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projectionCallback?.let { projection?.unregisterCallback(it) } } catch (_: Exception) {}
        projectionCallback = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (::enemyTextDetector.isInitialized) try { enemyTextDetector.close() } catch (_: Exception) {}
        thread?.quitSafely(); thread = null; handler = null
        PredictionState.set(emptyList())
        PredictionState.setEnemyMarkers(emptyList())
        PredictionState.status = "Captura parada"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }


    override fun onBind(intent: Intent?): IBinder? = null
}
