package com.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that captures screen frames using MediaProjection API.
 * Works on ALL Android devices — no root required.
 *
 * Frames are JPEG-encoded and pushed to [ScreenFrameRelay] for
 * the VncTunnel to stream over WebSocket.
 */
class MediaProjectionCaptureService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var captureJob: Job? = null

    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 240

    override fun onCreate() {
        super.onCreate()
        readDisplayMetrics()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == 0 || data == null) {
            Log.w(TAG, "No projection result code/data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun readDisplayMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        // Cap resolution to 720p for bandwidth
        val scale = minOf(1.0f, MAX_WIDTH.toFloat() / metrics.widthPixels)
        screenWidth = (metrics.widthPixels * scale).toInt()
        screenHeight = (metrics.heightPixels * scale).toInt()
        screenDensity = metrics.densityDpi
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val thread = HandlerThread("ScreenCaptureThread").also {
            it.start()
            handlerThread = it
        }
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2,
        )
        imageReader = reader

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DrishtiCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )

        captureJob = serviceScope.launch {
            while (isActive) {
                captureFrame(reader)
                delay(FRAME_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Screen capture started at ${screenWidth}x${screenHeight}")
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun captureFrame(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(buffer)

            // Crop to exact width if there's padding
            val cropped = if (rowPadding > 0) {
                Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                    .also { bmp.recycle() }
            } else {
                bmp
            }

            val jpeg = ByteArrayOutputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            cropped.recycle()

            ScreenFrameRelay.push(jpeg)
        } catch (e: Exception) {
            Log.w(TAG, "Frame capture error: ${e.message}")
        } finally {
            image?.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drishti — Screen Sharing")
            .setContentText("Screen is being shared remotely")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "MediaProjectionCapture"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 2
        private const val FRAME_INTERVAL_MS = 67L  // ~15 fps
        private const val JPEG_QUALITY = 75
        private const val MAX_WIDTH = 720
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
    }
}

/**
 * In-process relay: the capture service pushes JPEG frames here;
 * VncTunnel pulls the latest frame to send over WebSocket.
 */
object ScreenFrameRelay {
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val listeners = mutableListOf<(ByteArray) -> Unit>()
    private val lock = Any()

    fun push(jpeg: ByteArray) {
        latestFrame.set(jpeg)
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it(jpeg) }
    }

    fun getLatest(): ByteArray? = latestFrame.get()

    fun addListener(listener: (ByteArray) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: (ByteArray) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }
}
