package com.nekonyan.assistant.core.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/**
 * 端侧 YOLO 推理（NCNN via JNI）
 *
 * 使用：
 *   NcnnDetector.init(context, modelDir = File(filesDir, "models/current"))
 *   val dets = NcnnDetector.detect(bitmap, conf = 0.3f, iou = 0.45f)
 *
 * 说明：
 *   · 模型目录优先用热更新目录；不存在则回退 assets/models/v1/
 *   · init() 可重复调用（会先 release），用于热更新后重载
 *   · ★ labels 与模型**强绑定**：每次 init 都会重置类别表，
 *     回退基线模型时不会残留上一个模型的类名（否则类别名整体错位）
 */
object NcnnDetector {

    private const val TAG = "NcnnDetector"
    private const val ASSETS_FALLBACK = "models/v1"

    /** 内置模型的基名（与 tools/fetch_yolo_model.sh 落地的文件名、assets 目录一致） */
    private const val MODEL_BASE = ModelPolicy.BUILTIN
    private const val DEFAULT_INPUT_SIZE = 640
    private const val DEFAULT_MAX_DETECTIONS = 100

    private var handle: Long = 0L
    private val lock = Any()

    /** 当前类别表（与当前模型配套；未加载到 labels.txt 时为空表） */
    @Volatile var labels: List<String> = emptyList()
        private set

    /** 当前加载的是模型目录还是 assets 兜底（诊断用） */
    @Volatile var usingFallbackAssets: Boolean = false
        private set

    /** 当前实际生效的输入尺寸（必须与模型导出时一致） */
    @Volatile var inputSize: Int = DEFAULT_INPUT_SIZE
        private set

    data class Detection(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val cls: Int, val conf: Float
    )

    /**
     * 初始化 / 重载模型
     * @param modelDir 模型目录（含 .param 与 .bin；目录不存在或文件缺失则回退 assets）
     * @param inputSize 模型输入边长，**必须与导出时一致**（640 / 416 / 320）
     * @return 是否成功
     */
    fun init(
        context: Context,
        modelDir: File?,
        numThreads: Int = 0,
        useGpu: Boolean = false,
        inputSize: Int = DEFAULT_INPUT_SIZE
    ): Boolean = synchronized(lock) {
        release()
        return try {
            val threads = if (numThreads > 0) numThreads
                          else (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)

            val paramPath: String
            val binPath: String
            val dirLabels: List<String>

            // 从目录里**认**模型而不是写死文件名：导入的模型可能叫 yolo11n / yolov8n / yolov5n，
            // 写死会导致"导入成功、却永远回退 assets"这种极难排查的现象。
            val param = modelDir?.let { dir ->
                dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("param", true) }
            }
            val bin = param?.let { p ->
                File(p.parentFile, p.nameWithoutExtension + ".bin").takeIf { it.isFile }
            }
            if (param != null && bin != null && param.exists() && bin.exists()) {
                paramPath = param.absolutePath
                binPath = bin.absolutePath
                dirLabels = readLabels(File(modelDir, "labels.txt"))
                usingFallbackAssets = false
            } else {
                if (modelDir != null) {
                    android.util.Log.w(TAG, "模型目录不完整，回退 assets：${modelDir.absolutePath}")
                }
                val (p, b, l) = extractAssets(context)
                paramPath = p; binPath = b; dirLabels = l
                usingFallbackAssets = true
            }

            // ★ labels 每次 init 都重置（含 assets 回退路径），杜绝类名错位
            labels = dirLabels
            this.inputSize = inputSize

            handle = nativeInit(paramPath, binPath, threads, useGpu, inputSize)
            if (handle == 0L && useGpu) {
                // Vulkan 初始化失败 → 降级 CPU 重试
                android.util.Log.w(TAG, "GPU 初始化失败，降级 CPU 重试")
                handle = nativeInit(paramPath, binPath, threads, false, inputSize)
            }
            if (handle == 0L) {
                android.util.Log.e(TAG, "模型加载失败（param/bin 可能不匹配或已损坏）")
            }
            handle != 0L
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "init failed: ${e.message}")
            false
        }
    }

    /** 读取类别表：去掉空行与行尾空白，避免行号错位 */
    private fun readLabels(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "labels 读取失败: ${e.message}")
            emptyList()
        }
    }

    /** assets 兜底：复制到 filesDir 后加载（NCNN 需要真实文件路径） */
    private fun extractAssets(context: Context): Triple<String, String, List<String>> {
        val out = File(context.filesDir, "assets_models").apply { mkdirs() }
        var labelsOut: List<String> = emptyList()
        listOf("$MODEL_BASE.param", "$MODEL_BASE.bin", "labels.txt").forEach { name ->
            try {
                val dst = File(out, name)
                // 已存在但为空视作无效，重新释放
                if (!dst.exists() || dst.length() == 0L) {
                    context.assets.open("$ASSETS_FALLBACK/$name").use { ins ->
                        dst.outputStream().use { os -> ins.copyTo(os) }
                    }
                }
                if (name == "labels.txt") labelsOut = readLabels(dst)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "assets 释放失败 $name: ${e.message}")
            }
        }
        return Triple(
            File(out, "$MODEL_BASE.param").absolutePath,
            File(out, "$MODEL_BASE.bin").absolutePath,
            labelsOut
        )
    }

    /**
     * 推理
     * @return 检测结果列表（坐标已还原到原图）
     */
    fun detect(
        bitmap: Bitmap,
        conf: Float = 0.3f,
        iou: Float = 0.45f,
        maxDetections: Int = DEFAULT_MAX_DETECTIONS
    ): List<Detection> = synchronized(lock) {
        if (handle == 0L) return emptyList()
        if (bitmap.isRecycled) return emptyList()
        // JNI 侧只接受 RGBA_8888
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                  else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList()

        val flat = nativeDetect(handle, src, conf, iou, maxDetections) ?: return emptyList()
        // 扁平数组：[x1,y1,x2,y2,cls,conf, ...]
        buildList {
            var i = 0
            while (i + 5 < flat.size) {
                val x1 = flat[i]; val y1 = flat[i + 1]
                val x2 = flat[i + 2]; val y2 = flat[i + 3]
                // 退化框（宽或高为 0）不返回，避免下游画框/裁剪出异常
                if (x2 > x1 && y2 > y1) {
                    add(Detection(x1, y1, x2, y2, flat[i + 4].toInt(), flat[i + 5]))
                }
                i += 6
            }
        }
    }

    fun release() = synchronized(lock) {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    /**
     * 首帧预热：避免第一次推理把 UI 卡住（docs/02 自检清单要求）。
     * 建议在子线程、模型 init 成功后调用一次。
     */
    fun warmUp(size: Int = 64) {
        if (handle == 0L) return
        try {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            detect(bmp, 0.99f, 0.45f, maxDetections = 1)
            bmp.recycle()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "预热失败（忽略）: ${e.message}")
        }
    }

    // ============================================================
    // 便捷：绘制结果（调试用）
    // ============================================================

    fun drawOn(src: Bitmap, dets: List<Detection>, labelMap: Map<Int, String> = emptyMap()): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val box = Paint().apply {
            color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE
            strokeWidth = 4f; isAntiAlias = true
        }
        val text = Paint().apply {
            color = Color.WHITE; textSize = 28f; isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        dets.forEach { d ->
            canvas.drawRect(d.x1, d.y1, d.x2, d.y2, box)
            val name = labelMap[d.cls] ?: labels.getOrNull(d.cls) ?: d.cls.toString()
            canvas.drawText("$name ${(d.conf * 100).toInt()}%", d.x1, (d.y1 - 6f).coerceAtLeast(24f), text)
        }
        return bmp
    }

    // ============================================================
    // JNI
    // ============================================================

    private external fun nativeInit(
        paramPath: String, binPath: String, threads: Int, useGpu: Boolean, inputSize: Int
    ): Long

    private external fun nativeDetect(
        handle: Long, bitmap: Bitmap, conf: Float, iou: Float, maxDetections: Int
    ): FloatArray?

    private external fun nativeRelease(handle: Long)

    init {
        try {
            System.loadLibrary("yolo_ncnn")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "load library failed: ${e.message}")
        }
    }
}
