package com.nekonyan.assistant.core.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nekonyan.assistant.R
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.yolo.NcnnDetector

/**
 * 屏幕捕获 + 本地推理（`猫娘助手.ds` M4）。
 *
 * 链路：MediaProjection（系统每次会话现场授权）→ VirtualDisplay → ImageReader →
 *      Image 转 Bitmap → [NcnnDetector.detect] → 统计/写只读日志。
 *
 * 三条刻意遵守的系统约束（不遵守就是"能编译但一跑就崩/黑屏"）：
 *   ① 必须**先前台**再取 MediaProjection：先 `startForeground(..., mediaProjection)`，
 *      再 `getMediaProjection(resultCode, data)`；Android 14 起还会校验服务类型；
 *   ② MediaProjection 授权**无法长期保存**：每次开始捕获都要重新弹系统确认框，所以
 *      resultCode/data 由界面通过 Intent 传进来，Service 自己不持有任何"已授权"状态；
 *   ③ ImageReader 的 `rowStride` 通常**大于**宽度×像素字节数，必须按 rowStride 建 Bitmap
 *      再裁掉右侧 padding，否则画面会斜切（这是抓屏最经典的坑）。
 *
 * 本地推理按 [DETECT_INTERVAL_MS] 节流：640 输入在手机上约 234ms/帧（实测），
 * 全速跑会把 CPU 吃满、发热掉电，所以默认约 2 帧/秒。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastDetectAt = 0L
    private var frames = 0
    private var detects = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundSafely()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null) {
                    NekoLog.error(NekoLog.MODULE_PROJECTION, "capture_no_consent", "缺少 MediaProjection 授权数据")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ---------------- 内部 ----------------

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在捕获屏幕并本地识别")
            .setSmallIcon(R.drawable.ic_cat)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        stopCapture()
        thread = HandlerThread("neko-capture").also { it.start() }
        handler = Handler(thread!!.looper)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            NekoLog.error(NekoLog.MODULE_PROJECTION, "projection_denied", "getMediaProjection 返回 null")
            stopSelf()
            return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                NekoLog.warn(NekoLog.MODULE_PROJECTION, "projection_stopped", "系统或用户停止了投屏")
                stopCapture()
                stopSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastDetectAt >= DETECT_INTERVAL_MS) {
                    lastDetectAt = now
                    onFrame(image, w, h)
                }
            } catch (e: Throwable) {
                NekoLog.error(NekoLog.MODULE_PROJECTION, "frame_failed", e.javaClass.simpleName + ": " + e.message)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = proj.createVirtualDisplay(
            "nekonyan-capture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        reader = r
        projection = proj
        running = true
        frames = 0; detects = 0
        NekoLog.info(NekoLog.MODULE_PROJECTION, "capture_started", "${w}x$h @ ${DETECT_INTERVAL_MS}ms")
    }

    private fun onFrame(image: Image, w: Int, h: Int) {
        val bitmap = imageToBitmap(image, w, h) ?: return
        frames++
        val dets = NcnnDetector.detect(bitmap)
        detects += dets.size
        val top = dets.maxByOrNull { it.conf }
        val topText = top?.let {
            val name = NcnnDetector.labels.getOrNull(it.cls) ?: "cls=${it.cls}"
            "$name ${"%.0f".format(it.conf * 100)}%"
        } ?: "无检出"
        lastStats = "帧 $frames · 检出 $detects · 本帧 ${dets.size} 个 · 最高 $topText"
        if (frames % 10 == 0) {
            NekoLog.info(NekoLog.MODULE_PROJECTION, "capture_stats", lastStats)
        }
        bitmap.recycle()
    }

    /** Image(RGBA_8888) → Bitmap：按 rowStride 建图再裁 padding，避免画面斜切 */
    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap? = runCatching {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(
            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        cropped
    }.getOrNull()

    private fun stopCapture() {
        running = false
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null; reader = null; projection = null
        thread?.quitSafely(); thread = null; handler = null
    }

    companion object {
        const val ACTION_START = "com.nekonyan.assistant.capture.START"
        const val ACTION_STOP = "com.nekonyan.assistant.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val DETECT_INTERVAL_MS = 500L      // 约 2 帧/秒，避免吃满 CPU

        @Volatile var running: Boolean = false
            private set

        @Volatile var lastStats: String = "未运行"
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
