package com.nekonyan.assistant.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekonyan.assistant.core.annot.YoloLabel
import com.nekonyan.assistant.core.collect.TrainingCollector
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.yolo.NcnnDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 标注页：**在手机上修正预标注的框**（只加这一个新界面）。
 *
 * 为什么预标注放在端上而不是服务端：手机本来就带着模型（NcnnDetector），
 * 打开一张图就能出预框，不需要联网、也不需要服务端那套流程。
 * 人只做"改错的"，效率差一个数量级。
 *
 * 三个刻意的设计：
 *   ① **逐张过**：一张图占满屏，标完「保存并下一张」自动前进 —— 标注要看清细节，
 *      一屏列表（缩略图）在手机上看不清小目标，反而要来回放大；
 *   ② **空标签也要保存**：那表示"这张图没有目标"，是**背景负样本**，
 *      对压误检很有价值 —— 所以「保存并下一张」即使一个框都没有也会写文件；
 *   ③ 手势坐标先换算到**图片实际显示矩形**（letterbox 留黑边时不能直接用画布坐标），
 *      换算逻辑在 [YoloLabel.fitRect]，已单测。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val collector = remember { TrainingCollector(ctx) }
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf(collector.pendingFiles()) }
    var idx by remember { mutableStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var boxes by remember { mutableStateOf<List<YoloLabel.Box>>(emptyList()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var cls by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val progress by collector.progress.collectAsStateWithLifecycle()

    // 类别名：内置 COCO 模型翻成中文；用户导入的模型原样保留。
    // 再叠加"用户自己填的类别"（持久化，下次还在）—— 顺序决定 class id，不能乱。
    val modelLabels = remember {
        com.nekonyan.assistant.core.annot.CocoZh.localize(
            NcnnDetector.labels.ifEmpty { listOf("目标0") }
        )
    }
    var extraNames by remember { mutableStateOf(loadExtraClasses(ctx)) }
    val classes = remember(modelLabels, extraNames) { modelLabels + extraNames }
    var newClsText by remember { mutableStateOf("") }
    val current: File? = files.getOrNull(idx)

    // 换张就重新载入：已有标签优先，没有才跑端上预标注
    LaunchedEffect(idx, files.size) {
        val f = files.getOrNull(idx)
        if (f == null) { bmp = null; boxes = emptyList(); return@LaunchedEffect }
        busy = true; msg = null
        withContext(Dispatchers.IO) {
            val b = decodeForAnnotate(f)
            val existing = collector.readLabel(f)
            var bs: List<YoloLabel.Box> = emptyList()
            var note: String? = null
            if (existing != null) {
                bs = YoloLabel.parse(existing)
                note = if (bs.isEmpty()) "这张标的是「无目标」（背景样本）" else "已标注 ${bs.size} 个框"
            } else if (b != null) {
                // 端上预标注
                if (!NcnnDetector.isReady) {
                    note = "模型未加载，先在「YOLO 模型」页切换一个模型，或手动画框"
                } else {
                    val dets = runCatching { NcnnDetector.detect(b) }.getOrDefault(emptyList())
                    bs = dets.map { d ->
                        YoloLabel.fromRect(d.cls, YoloLabel.PxRect(d.x1, d.y1, d.x2, d.y2), b.width, b.height)
                    }
                    note = if (bs.isEmpty()) "预标注没找到目标，可手动画框；没有目标就点「保存并下一张」"
                    else "端上预标注出 ${bs.size} 个框，请修正后保存"
                }
            }
            bmp = b
            boxes = bs
            msg = note
        }
        selected = null
        busy = false
    }

    fun save(advance: Boolean) {
        val f = current ?: return
        val text = YoloLabel.serialize(boxes)
        val ok = collector.saveLabel(f, text)
        msg = if (ok) "已保存（${if (boxes.isEmpty()) "无目标/背景样本" else "${boxes.size} 个框"}）" else "保存失败"
        if (ok && advance) {
            files = collector.pendingFiles()          // 上传成功的会被移走，重新读一遍
            if (idx < files.size - 1) idx++ else msg = "已经是最后一张；可点「上传全部」"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标注（图片 ${if (files.isEmpty()) 0 else idx + 1}/${files.size}）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            // ---- 图片 + 框 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF101014)),
                contentAlignment = Alignment.Center
            ) {
                val b = bmp
                if (b == null) {
                    Text(
                        if (files.isEmpty()) "还没有待标注的样本。先在设置→训练数据采集里采集或选图上传"
                        else "正在载入…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                } else {
                    AnnotateCanvas(
                        bmp = b,
                        boxes = boxes,
                        selected = selected,
                        onBoxes = { boxes = it },
                        onSelect = { selected = it },
                        currentCls = cls
                    )
                }
            }

            // ---- 状态与说明 ----
            msg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (progress.isNotEmpty()) {
                Text(
                    progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            // ---- 类别 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                classes.take(80).forEachIndexed { i, name ->
                    FilterChip(
                        selected = cls == i,
                        onClick = {
                            cls = i
                            selected?.let { s -> boxes = boxes.toMutableList().also { it[s] = YoloLabel.withClass(it[s], i) } }
                        },
                        label = { Text(name.take(10)) }
                    )
                }
            }

            // ---- 自己填类别名（需求：可以自己填写标注内容）----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = newClsText,
                    onValueChange = { newClsText = it },
                    label = { Text("自己填类别名（如：敌人 / 物资）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val n = newClsText.trim()
                        if (n.isNotEmpty() && !classes.contains(n)) {
                            extraNames = extraNames + n
                            saveExtraClasses(ctx, extraNames)
                            cls = classes.size          // 选中刚加的这个
                            msg = "已添加类别「$n」（class id = ${classes.size}）"
                            newClsText = ""
                        }
                    }
                ) { Text("加类别") }
            }

            // ---- 操作 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { if (idx > 0) idx-- }) { Text("上一张") }
                OutlinedButton(
                    onClick = { selected?.let { s -> boxes = boxes.filterIndexed { i, _ -> i != s }; selected = null } },
                    enabled = selected != null
                ) { Text("删除框") }
                OutlinedButton(onClick = { if (idx < files.size - 1) idx++ else msg = "已经是最后一张" }) { Text("跳过") }
                // 文案短一点：截图里「保存并下一张」被挤成两行 ✗
                Button(onClick = { save(advance = true) }, enabled = !busy) { Text("保存") }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val f = current ?: return@OutlinedButton
                        scope.launch {
                            busy = true
                            val fresh = withContext(Dispatchers.IO) {
                                val b = decodeForAnnotate(f)
                                if (b == null || !NcnnDetector.isReady) emptyList()
                                else runCatching { NcnnDetector.detect(b) }.getOrDefault(emptyList())
                                    .map { d -> YoloLabel.fromRect(d.cls, YoloLabel.PxRect(d.x1, d.y1, d.x2, d.y2), b.width, b.height) }
                            }
                            boxes = fresh
                            msg = if (fresh.isEmpty()) "没有找到目标" else "重新预标注：${fresh.size} 个框"
                            busy = false
                        }
                    },
                    enabled = !busy && bmp != null
                ) { Text("重新预标注") }
                OutlinedButton(onClick = { save(advance = false) }, enabled = !busy) { Text("只保存") }
                Button(
                    onClick = {
                        // 类别名必须随上传一起送：YOLO 标签里只有 class id，
                        // 服务端拿不到名字的话，自建类别（敌人/物资…）就无从解释 ✗
                        collector.startUpload(names = classes)
                        msg = "已提交上传（后台进行，可离开本页）"
                    },
                    enabled = !busy
                ) { Text("上传全部") }
            }
            Text(
                "手势：空白处拖 = 画新框；点框 = 选中；拖框内 = 移动；拖右下角 = 缩放；" +
                    "「保存」会自动跳下一张。空标签也是一份有效数据（背景负样本）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** 图片 + 可拖拽的框（手势与绘制都换算到图片实际显示矩形里） */
@Composable
private fun AnnotateCanvas(
    bmp: Bitmap,
    boxes: List<YoloLabel.Box>,
    selected: Int?,
    onBoxes: (List<YoloLabel.Box>) -> Unit,
    onSelect: (Int?) -> Unit,
    currentCls: Int
) {
    var canvasW by remember { mutableStateOf(1f) }
    var canvasH by remember { mutableStateOf(1f) }
    var dragMode by remember { mutableStateOf(0) }        // 0=无 1=移动 2=缩放 3=新建
    var dragIdx by remember { mutableStateOf(-1) }
    var creating by remember { mutableStateOf<YoloLabel.Box?>(null) }
    var startNorm by remember { mutableStateOf(Offset.Zero) }
    var lastNorm by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(bmp, boxes.size) {
                detectDragGestures(
                    onDragStart = { off ->
                        val fit = YoloLabel.fitRect(bmp.width, bmp.height, canvasW, canvasH)
                        val scale = if (bmp.width > 0) (fit.right - fit.left) / bmp.width else 1f
                        if (scale <= 0f) return@detectDragGestures
                        val ix = (off.x - fit.left) / scale
                        val iy = (off.y - fit.top) / scale
                        val nx = ix / bmp.width
                        val ny = iy / bmp.height
                        startNorm = Offset(nx, ny); lastNorm = startNorm
                        val hit = YoloLabel.hitTest(boxes, ix, iy, bmp.width, bmp.height)
                        if (hit != null) {
                            onSelect(hit); dragIdx = hit
                            // 靠近右下角 → 缩放；否则移动
                            val r = YoloLabel.toRect(boxes[hit], bmp.width, bmp.height)
                            val near = abs(ix - r.right) < 60f && abs(iy - r.bottom) < 60f
                            dragMode = if (near) 2 else 1
                        } else {
                            dragMode = 3; dragIdx = -1
                            creating = YoloLabel.Box(currentCls, nx, ny, YoloLabel.MIN_SIDE, YoloLabel.MIN_SIDE)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fit = YoloLabel.fitRect(bmp.width, bmp.height, canvasW, canvasH)
                        val scale = if (bmp.width > 0) (fit.right - fit.left) / bmp.width else 1f
                        if (scale <= 0f) return@detectDragGestures
                        val nx = ((change.position.x - fit.left) / scale) / bmp.width
                        val ny = ((change.position.y - fit.top) / scale) / bmp.height
                        when (dragMode) {
                            1 -> if (dragIdx in boxes.indices) {
                                onBoxes(boxes.toMutableList().also {
                                    it[dragIdx] = YoloLabel.move(it[dragIdx], nx - lastNorm.x, ny - lastNorm.y)
                                })
                            }
                            2 -> if (dragIdx in boxes.indices) {
                                onBoxes(boxes.toMutableList().also {
                                    it[dragIdx] = YoloLabel.resizeFromTopLeft(
                                        it[dragIdx], nx - lastNorm.x, ny - lastNorm.y
                                    )
                                })
                            }
                            3 -> {
                                val c = creating ?: return@detectDragGestures
                                creating = YoloLabel.fromRect(
                                    currentCls,
                                    YoloLabel.PxRect(
                                        c.let { it.cx - it.w / 2f } * bmp.width,
                                        c.let { it.cy - it.h / 2f } * bmp.height,
                                        nx * bmp.width, ny * bmp.height
                                    ),
                                    bmp.width, bmp.height
                                )
                            }
                        }
                        lastNorm = Offset(nx, ny)
                    },
                    onDragEnd = {
                        if (dragMode == 3) {
                            creating?.let { c ->
                                // 太小的当误触丢弃（否则标签里会出现一堆垃圾框）
                                if (c.w > YoloLabel.MIN_SIDE * 2 && c.h > YoloLabel.MIN_SIDE * 2) {
                                    onBoxes(boxes + c); onSelect(boxes.size)
                                }
                            }
                            creating = null
                        }
                        dragMode = 0; dragIdx = -1
                    }
                )
            }
    ) {
        canvasW = size.width; canvasH = size.height
        val fit = YoloLabel.fitRect(bmp.width, bmp.height, size.width, size.height)
        if (fit.right <= fit.left) return@Canvas
        val scale = (fit.right - fit.left) / bmp.width
        drawImage(
            image = bmp.asImageBitmap(),
            dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
            dstSize = IntSize((fit.right - fit.left).toInt(), (fit.bottom - fit.top).toInt())
        )
        val all = if (creating != null) boxes + creating!! else boxes
        all.forEachIndexed { i, box ->
            val r = YoloLabel.toRect(box, bmp.width, bmp.height)
            val topLeft = Offset(fit.left + r.left * scale, fit.top + r.top * scale)
            val sz = Size((r.right - r.left) * scale, (r.bottom - r.top) * scale)
            val color = when {
                i == selected -> Color(0xFFFFD400)
                i >= boxes.size -> Color(0xFF7CFF6B)
                else -> Color(0xFF00E5FF)
            }
            drawRect(color = color, topLeft = topLeft, size = sz, style = Stroke(width = 4f))
            // 右下角一个把手，提示"这里可以缩放"
            if (i == selected) {
                drawRect(
                    color = color,
                    topLeft = Offset(topLeft.x + sz.width - 14f, topLeft.y + sz.height - 14f),
                    size = Size(18f, 18f)
                )
            }
        }
    }
}

private const val PREFS = "nekonyan_annot"

/** 读用户自建的类别名（模型自带的那些不入库，避免模型换了名字还残留） */
private fun loadExtraClasses(ctx: android.content.Context): List<String> =
    ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .getString("extra_classes", "").orEmpty()
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun saveExtraClasses(ctx: android.content.Context, names: List<String>) {
    ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString("extra_classes", names.joinToString("\n")).apply()
}

/** 标注用的解码：长边限制在 1600（原图 1080×2400 直接解会占十几 MB，而模型本来也只吃 640） */
private fun decodeForAnnotate(f: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrElse {
    NekoLog.warn(NekoLog.MODULE_PROJECTION, "annot_decode_failed", it.javaClass.simpleName)
    null
}
