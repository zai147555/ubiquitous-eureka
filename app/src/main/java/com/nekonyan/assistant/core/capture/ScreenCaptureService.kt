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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import com.nekonyan.assistant.R
import com.nekonyan.assistant.core.collect.CollectPolicy
import com.nekonyan.assistant.core.collect.TrainingCollector
import com.nekonyan.assistant.core.log.NekoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private val CHANNEL_ID = "nekonyan_capture"
    private val NOTIF_ID = 1001

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    /**
     * 按需取帧的等待者（agent 的 source=screen 用）。
     *
     * ImageReader 的监听器**每帧都触发**（500ms 节流只挡自动检测），所以这里等一帧的
     * 延迟约等于一个 vsync，不需要为取帧再开一条抓屏链路。
     */
    @Volatile private var grabWaiter: CompletableDeferred<Bitmap?>? = null

    /**
     * 训练数据采集（**默认关闭**，开关与计数在界面/通知里都可见）。
     * 采样按 [CollectPolicy] 的间隔与每日上限来，绝不逐帧上传。
     */
    private val collector by lazy { TrainingCollector(this) }

    /** 只用于后台上传，避免占用抓屏线程 */
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastUploadAtMs = 0L
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastDetectAt = 0L
    private var frames = 0
    private var detects = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 登记实例：取帧接口靠它找到正在跑的捕获服务（stopSelf 后由 onDestroy 清空）
        instance = this
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
        // 先唤醒等在取帧上的调用方，再收摊：否则它要白等到超时
        grabWaiter?.complete(null)
        grabWaiter = null
        instance = null
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
                // 有人在等帧就优先交付（不受自动检测节流影响）
                deliverIfWaiting(image, w, h)
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

    /** 有人在等帧就把这一帧交付出去（一帧只交付一次）；转换失败也要 complete(null)，避免调用方空等 */
    private fun deliverIfWaiting(image: Image, w: Int, h: Int) {
        val waiter = grabWaiter ?: return
        grabWaiter = null
        // 用 complete(null) 表达失败：completeExceptionally 会让 await() 抛异常，
        // 而这条接口的约定是"取不到就返回 null"，不该把异常丢给工具层
        waiter.complete(imageToBitmap(image, w, h))
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
        // 训练数据采样（默认关闭；间隔/上限/去重都在 CollectPolicy 里判定）
        maybeCollect(bitmap)
        bitmap.recycle()
    }

    /**
     * 按策略决定这一刻要不要留一张样本。
     *
     * 用 9x8 灰度算 64 位 dHash 做**近似去重**：抓屏相邻帧几乎一样，
     * 不去重的话队列里全是同一画面（既占用户空间，也把训练集带偏）。
     */
    private fun maybeCollect(bitmap: Bitmap) {
        val c = collector
        val rules = c.rules()
        if (!rules.enabled) return
        runCatching {
            val hash = CollectPolicy.dHash(lumaOf(bitmap), LUMA_W, LUMA_H)
            val now = System.currentTimeMillis()
            val decision = CollectPolicy.shouldCapture(rules, c.usage(), now, hash)
            if (decision !is CollectPolicy.Decision.Capture) return@runCatching
            val bos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            if (c.enqueue(bos.toByteArray(), hash, "screen", now)) {
                val (n, bytes) = c.pending()
                lastStats = lastStats + " · 待传 $n 张/${bytes / 1024}KB"
            }
            // 每 2 分钟尝试补传一次（断网/隧道挂掉时不丢样本，恢复后自动传）
            if (now - lastUploadAtMs > 120_000L) {
                lastUploadAtMs = now
                uploadScope.launch { c.uploadPending() }
            }
        }.onFailure {
            NekoLog.warn(NekoLog.MODULE_PROJECTION, "collect_sample_failed", it.javaClass.simpleName)
        }
    }

    /** 缩到 9x8 灰度算 dHash（9 宽 → 每行 8 次比较，8 行正好 64 位） */
    private fun lumaOf(src: Bitmap, w: Int = LUMA_W, h: Int = LUMA_H): IntArray {
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return IntArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
    }

    /** Image(RGBA_8888) → Bitmap：按 rowStride 建图再裁 padding，避免画面斜切 */
    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap? = runCatching {
        val plane = image.planes[0]
        // 同一帧可能被读两次（先交付取帧者、再跑自动检测），复位读取位置才安全
        runCatching { plane.buffer.rewind() }
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
        runCatching { uploadScope.cancel() }
        virtualDisplay = null; reader = null; projection = null
        thread?.quitSafely(); thread = null; handler = null
    }

    companion object {
        private const val LUMA_W = 9
        private const val LUMA_H = 8

        @Volatile private var instance: ScreenCaptureService? = null

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

        /**
         * 取一帧当前屏幕（**调用方负责 recycle**）。
         *
         * 未开启捕获 / 超时 / 帧转换失败 / 服务中途停止，一律返回 null 而**不抛异常** ——
         * 工具层要的是"能不能给模型一句实话"，不是异常栈。
         */
        suspend fun acquireFrame(timeoutMs: Long = 2000L): Bitmap? {
            val svc = instance ?: return null
            if (!running) return null
            val waiter = CompletableDeferred<Bitmap?>()
            svc.grabWaiter = waiter
            return try {
                withTimeoutOrNull(timeoutMs) { waiter.await() }
            } finally {
                // 只在还是自己那次等待时清理，避免踩掉紧随其后的另一次取帧
                if (svc.grabWaiter === waiter) svc.grabWaiter = null
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
