package com.nekonyan.assistant.ui.screen

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekonyan.assistant.NekoApp
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.yolo.ModelPolicy
import com.nekonyan.assistant.core.yolo.NcnnDetector
import com.nekonyan.assistant.core.yolo.SwitchGuard
import com.nekonyan.assistant.core.yolo.YoloScene
import com.nekonyan.assistant.data.db.NekoDatabase
import com.nekonyan.assistant.data.db.YoloModelConfigEntity
import com.nekonyan.assistant.data.db.YoloModelEntity
import com.nekonyan.assistant.data.db.YoloModelExportRecordEntity
import com.nekonyan.assistant.data.db.YoloModelImportRecordEntity
import com.nekonyan.assistant.data.db.YoloModelPerformanceEntity
import com.nekonyan.assistant.data.db.YoloModelSwitchRecordEntity
import com.nekonyan.assistant.data.db.YoloModelUpdateRecordEntity
import com.nekonyan.assistant.core.net.YoloServiceClient
import com.nekonyan.assistant.core.net.ServiceOutcome
import com.nekonyan.assistant.data.repo.YoloModelRepository
import com.nekonyan.assistant.data.repo.YoloServiceStore
import com.nekonyan.assistant.data.repo.YoloOpResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 模型管理页状态：全部来自 Room 的 Flow，界面不自己维护第二份真相 */
data class YoloUiState(
    val models: List<YoloModelEntity> = emptyList(),
    val config: YoloModelConfigEntity = YoloModelConfigEntity(),
    val switches: List<YoloModelSwitchRecordEntity> = emptyList(),
    val imports: List<YoloModelImportRecordEntity> = emptyList(),
    val exports: List<YoloModelExportRecordEntity> = emptyList(),
    val updates: List<YoloModelUpdateRecordEntity> = emptyList(),
    val performance: List<YoloModelPerformanceEntity> = emptyList(),
    val scene: YoloScene = YoloScene.Default,
    val busy: Boolean = false,
    val message: String? = null,
    val messageOk: Boolean = false,
    // ---- 局域网识别服务（模型/接入指南.md）----
    val serviceBase: String = "",
    val serviceToken: String = "",
    val serviceEnabled: Boolean = false,
    val serviceBusy: Boolean = false,
    val serviceMessage: String? = null,
    val serviceOk: Boolean = false
) {
    val current: YoloModelEntity? get() = models.firstOrNull { it.id == config.currentModelId }

    val enabledModels: List<YoloModelEntity>
        get() = models.filter { it.status != YoloModelEntity.STATUS_DISABLED }

    val disabledModels: List<YoloModelEntity>
        get() = models.filter { it.status == YoloModelEntity.STATUS_DISABLED }

    /** 按 yolo.ds 的场景表，当前场景**应该**用哪个；已装与否都能算出来 */
    val scenePreferred: String get() = ModelPolicy.preferredModel(scene)

    val sceneResolved: String
        get() = ModelPolicy.resolve(scene, models.map { it.id }, config.currentModelId)

    /** 推理引擎是否已接入：有真实延迟数据才算接过 */
    val engineReady: Boolean get() = performance.any { it.latencyMs > 0 }

    /** 只读日志（yolo.ds 第 88~92 行：导入/导出/切换/更新/回滚/删除 + 失败） */
    val logLines: List<String>
        get() = buildList {
            switches.forEach {
                add("${fmt(it.timestamp)} 切换 ${it.fromModel.ifBlank { "无" }} → ${it.toModel}" +
                    "（${it.reason}）${if (it.success) "成功" else "失败：${it.message}"}")
            }
            imports.forEach {
                add("${fmt(it.createdAt)} 导入 ${it.sourceType} ${it.status}" +
                    if (it.errorMessage.isNotBlank()) "：${it.errorMessage}" else "")
            }
            exports.forEach { add("${fmt(it.createdAt)} 导出 ${it.modelId} → ${it.exportUri.takeLast(40)}") }
            updates.forEach {
                add("${fmt(it.createdAt)} 更新 ${it.fromVersion} → ${it.toVersion.ifBlank { "?" }} ${it.status}" +
                    if (it.message.isNotBlank()) "：${it.message}" else "")
            }
            performance.take(20).forEach {
                add("${fmt(it.timestamp)} 性能 ${it.modelId} 场景=${it.scene} 延迟=${it.latencyMs}ms fps=${it.fps}")
            }
        }.sortedDescending()

    private fun fmt(ts: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(ts))
}

/**
 * 模型管理 ViewModel。
 *
 * 所有"能不能做"的判断都不在这里 —— 切换检查、版本保留、签名比对都在
 * core/yolo（纯逻辑、本机有断言），这里只负责调用与把结果告诉界面。
 */
class YoloModelViewModel(
    private val repo: YoloModelRepository,
    private val appContext: android.content.Context
) : ViewModel() {

    private val serviceStore = YoloServiceStore(appContext)
    private val service = YoloServiceClient()

    private val _state = MutableStateFlow(YoloUiState())
    val state: StateFlow<YoloUiState> = _state.asStateFlow()

    init {
        // 内置（加密）凭据：只在 Keystore 为空时注入，用户手填优先
        runCatching { serviceStore.ensureBuiltinCredentials() }
        _state.update {
            it.copy(
                serviceBase = serviceStore.baseUrl(),
                serviceToken = serviceStore.token(),
                serviceEnabled = serviceStore.enabled()
            )
        }
        viewModelScope.launch {
            // 首启把 assets 里的内置模型释放成真实文件并登记
            runCatching { repo.ensureBuiltinInstalled() }
                .onFailure { NekoLog.error(NekoLog.MODULE_YOLO, "builtin_init_failed", it.message ?: "") }
        }
        viewModelScope.launch { repo.observeModels().collect { list -> _state.update { it.copy(models = list) } } }
        viewModelScope.launch {
            repo.observeConfig().collect { c -> if (c != null) _state.update { it.copy(config = c) } }
        }
        viewModelScope.launch { repo.observeSwitches().collect { l -> _state.update { it.copy(switches = l) } } }
        viewModelScope.launch { repo.observeImports().collect { l -> _state.update { it.copy(imports = l) } } }
        viewModelScope.launch { repo.observeExports().collect { l -> _state.update { it.copy(exports = l) } } }
        viewModelScope.launch { repo.observeUpdates().collect { l -> _state.update { it.copy(updates = l) } } }
        viewModelScope.launch { repo.observePerformance().collect { l -> _state.update { it.copy(performance = l) } } }
    }

    fun selectScene(scene: YoloScene) = _state.update { it.copy(scene = scene) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 按当前场景切换（yolo.ds 第 46~57 行） */
    fun applySceneSwitch() = act("切换模型") {
        val target = _state.value.sceneResolved
        val installed = _state.value.models.any { it.id == target }
        if (!installed) {
            // 场景想要的模型没装：如实说明，并给出去哪拿
            return@act YoloOpResult(
                false,
                "${ModelPolicy.preferredModel(_state.value.scene)} 未安装（场景「${_state.value.scene.label}」需要它）；" +
                    "当前内置的是 ${ModelPolicy.BUILTIN}，可在下方导入模型后重试"
            )
        }
        repo.switchTo(target, "manual:${_state.value.scene.key}", _state.value.scene)
    }

    fun switchTo(model: YoloModelEntity) = act("切换模型") {
        repo.switchTo(model.id, "manual", _state.value.scene)
    }

    fun rollback() = act("回滚") { repo.rollback() }

    fun setEnabled(model: YoloModelEntity, enabled: Boolean) =
        act(if (enabled) "启用" else "禁用") { repo.setEnabled(model.id, enabled) }

    fun delete(model: YoloModelEntity) = act("删除") { repo.delete(model.id) }

    fun pruneVersions() = act("清理旧版本") { repo.pruneVersions() }

    fun checkUpdate() = act("检查更新") {
        repo.checkUpdate(
            appVersion = com.nekonyan.assistant.BuildConfig.VERSION_NAME,
            deviceIdHash = deviceIdHash()
        )
    }

    /** 灰度已按用户要求删除：device_id 仅作调用合法性占位，用**每台设备随机生成**的 id 哈希，不含任何个人信息 */
    private fun deviceIdHash(): String {
        val prefs = appContext.getSharedPreferences("nekonyan_update", android.content.Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 推理自检（yolo.ds 第 78~86 行：实时 FPS / 推理延迟）。
     *
     * 真加载当前模型 → 跑一次预热推理 → 把**实测**延迟与 FPS 写进性能表。
     * 原生库缺失或模型不匹配时如实报错，不编数字（页面上"未运行"就是这样来的）。
     */
    fun selfTest() = act("推理自检") {
        val model = _state.value.current
            ?: return@act YoloOpResult(false, "没有当前模型，先导入或切换一个")
        val dir = File(model.dirPath)
        if (!dir.isDirectory) return@act YoloOpResult(false, "模型目录不存在：${model.dirPath}")

        val loaded = runCatching {
            NcnnDetector.init(
                context = appContext,
                modelDir = dir,
                numThreads = 0,
                useGpu = false,               // 先 CPU：Vulkan 在部分设备上会初始化失败，留给后续降级策略
                inputSize = model.inputSize
            )
        }.getOrElse { false }

        if (!loaded) {
            return@act YoloOpResult(
                false,
                "模型加载失败：原生库 libyolo_ncnn.so 缺失或模型与 JNI 不匹配（本次未写入任何性能数据）"
            )
        }

        val t0 = SystemClock.elapsedRealtime()
        runCatching { NcnnDetector.warmUp(320) }
        val ms = (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
        repo.recordPerformance(model.id, _state.value.scene, fps = 1000f / ms, latencyMs = ms)
        YoloOpResult(
            true,
            "自检完成：单帧约 ${ms}ms（${"%.1f".format(1000f / ms)} FPS）· 类别表 ${NcnnDetector.labels.size} 项" +
                if (NcnnDetector.labels.isEmpty()) "（⚠ labels 为空，检测结果的类别名会缺失）" else ""
        )
    }

    fun importModels(uris: List<Uri>) = act("导入模型") {
        repo.importModel(uris, YoloModelEntity.SOURCE_LOCAL)
    }

    fun exportModel(model: YoloModelEntity, target: Uri) = act("导出模型") {
        repo.exportModel(model.id, target)
    }

    // ---------------- 局域网识别服务（接入指南第 365~384 行的接入清单） ----------------

    fun saveService(baseUrl: String, token: String) {
        serviceStore.save(baseUrl, token)
        _state.update {
            it.copy(
                serviceBase = serviceStore.baseUrl(),
                serviceToken = serviceStore.token(),
                serviceMessage = "已保存服务地址与 Token（Token 存于 Keystore，不进日志）",
                serviceOk = true
            )
        }
    }

    fun setServiceEnabled(enabled: Boolean) {
        serviceStore.setEnabled(enabled)
        _state.update { it.copy(serviceEnabled = enabled) }
    }

    /** 第 1 步：GET / （免认证）——判断"服务活着吗"，并确认模型名与阈值 */
    fun probeService() = serviceAct("探活") {
        when (val r = service.info(_state.value.serviceBase)) {
            is ServiceOutcome.Ok -> "✅ 服务在跑：${r.value.service} · 模型 ${r.value.model} · 输入 ${r.value.imgsz} · 阈值 ${r.value.confThreshold}" to true
            is ServiceOutcome.Err -> "❌ ${r.message}" to false
        }
    }

    /** 第 3 步：GET /health（需认证）——确认鉴权与并发状态 */
    fun healthService() = serviceAct("健康检查") {
        when (val r = service.health(_state.value.serviceBase, _state.value.serviceToken)) {
            is ServiceOutcome.Ok -> "✅ ${r.value}" to true
            is ServiceOutcome.Err -> "❌ ${r.message}" to false
        }
    }

    /**
     * 第 4 步：POST /detect —— 用一张**合成测试图**验证链路（不需要相册权限）。
     * 实测到的 total_ms 同时写进性能表，这样"服务端推理"的延迟也是真实数据。
     */
    fun detectTestImage() = serviceAct("检测测试图") {
        val jpeg = syntheticTestJpeg()
            ?: return@serviceAct "❌ 生成测试图失败" to false
        when (val r = service.detect(_state.value.serviceBase, _state.value.serviceToken, jpeg)) {
            is ServiceOutcome.Ok -> {
                val d = r.value
                val names = NcnnDetector.labels
                val top = d.detections.maxByOrNull { it.conf }
                val topText = top?.let {
                    val name = names.getOrNull(it.cls) ?: "cls=${it.cls}"
                    "最高置信：$name ${"%.0f".format(it.conf * 100)}%"
                } ?: "无检出（阈值 0.3 下正常）"
                _state.value.current?.let { m ->
                    repo.recordPerformance(m.id, _state.value.scene, fps = if (d.totalMs > 0) (1000.0 / d.totalMs).toFloat() else 0f, latencyMs = d.totalMs.toInt())
                }
                "✅ 链路通：检出 ${d.count} 个目标 · 服务端耗时 ${"%.0f".format(d.totalMs)}ms（推理 ${"%.0f".format(d.costMs)}ms）· $topText" to true
            }
            is ServiceOutcome.Err -> "❌ ${r.message}" to false
        }
    }

    /** 合成一张 640×640 的测试图（白底 + 深色矩形），只为验证链路，不涉隐私 */
    private fun syntheticTestJpeg(): ByteArray? = runCatching {
        val bmp = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(40, 40, 60) }
        canvas.drawRect(180f, 120f, 460f, 560f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()

    private fun serviceAct(label: String, block: suspend () -> Pair<String, Boolean>) = viewModelScope.launch {
        _state.update { it.copy(serviceBusy = true, serviceMessage = "$label 中…", serviceOk = false) }
        val (msg, ok) = runCatching { block() }.getOrElse {
            "❌ $label 失败：${it.message ?: it.javaClass.simpleName}" to false
        }
        _state.update { it.copy(serviceBusy = false, serviceMessage = msg, serviceOk = ok) }
    }

    fun updateConfig(transform: (YoloModelConfigEntity) -> YoloModelConfigEntity) = viewModelScope.launch {
        repo.updateConfig(transform)
    }

    /** 统一的操作包装：置忙、跑、报结果（失败也让用户看到原因） */
    private fun act(label: String, block: suspend () -> YoloOpResult) =
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = runCatching { block() }.getOrElse {
                YoloOpResult(false, "$label 失败：${it.message ?: it.javaClass.simpleName}")
            }
            _state.update {
                it.copy(busy = false, message = "${if (result.ok) "✅" else "❌"} ${result.message}", messageOk = result.ok)
            }
        }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = NekoDatabase.get(NekoApp.get())
                YoloModelViewModel(
                    repo = YoloModelRepository(NekoApp.context(), db.yoloModelDao()),
                    appContext = NekoApp.context()
                )
            }
        }
    }
}
