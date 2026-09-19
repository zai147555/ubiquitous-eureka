package com.nekonyan.assistant.data.repo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import com.nekonyan.assistant.core.log.NekoLog
import com.nekonyan.assistant.core.yolo.ModelFiles
import com.nekonyan.assistant.core.yolo.NcnnDetector
import com.nekonyan.assistant.core.yolo.ModelPolicy
import com.nekonyan.assistant.core.yolo.SignatureCheck
import com.nekonyan.assistant.core.yolo.SwitchCheck
import com.nekonyan.assistant.core.yolo.SwitchContext
import com.nekonyan.assistant.core.yolo.SwitchGuard
import com.nekonyan.assistant.core.yolo.VersionInfo
import com.nekonyan.assistant.core.yolo.VersionRetention
import com.nekonyan.assistant.core.yolo.YoloScene
import com.nekonyan.assistant.data.db.YoloModelConfigEntity
import com.nekonyan.assistant.data.db.YoloModelDao
import com.nekonyan.assistant.data.db.YoloModelEntity
import com.nekonyan.assistant.data.db.YoloModelExportRecordEntity
import com.nekonyan.assistant.data.db.YoloModelImportRecordEntity
import com.nekonyan.assistant.data.db.YoloModelPerformanceEntity
import com.nekonyan.assistant.data.db.YoloModelSwitchRecordEntity
import com.nekonyan.assistant.data.db.YoloModelUpdateRecordEntity
import com.nekonyan.assistant.data.db.YoloModelVersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 一次操作的结果（界面直接显示 message） */
data class YoloOpResult(val ok: Boolean, val message: String)

/**
 * YOLO 模型仓库：**文件系统 + Room + 策略**的编排层。
 *
 * 三点刻意的设计：
 *   ① **内置模型"安装"成真实文件**：assets 里的模型在首启时释放到 filesDir/models/，
 *      之后所有操作（切换/导出/删除）都只面对真实目录 —— 不用为"内置/已安装"写两套代码；
 *   ② 切换前的检查、版本保留、签名比对全部委托给 core/yolo 的**纯逻辑**（可本机断言），
 *      这里只负责取真实设备数据（存储/电量/温度）与落库；
 *   ③ 推理引擎（NCNN native）尚未接入，因此性能数据不做假：没有真实数据时写入 0，
 *      界面据 [YoloModelEntity.latencyMs] == 0 显示"未运行"，而不是编一个好看的延迟。
 */
class YoloModelRepository(
    private val context: Context,
    private val dao: YoloModelDao
) {

    private val modelsRoot: File get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun observeModels(): Flow<List<YoloModelEntity>> = dao.observeModels()
    fun observeConfig(): Flow<YoloModelConfigEntity?> = dao.observeConfig()
    fun observeSwitches(limit: Int = 50): Flow<List<YoloModelSwitchRecordEntity>> = dao.observeSwitches(limit)
    fun observeImports(limit: Int = 50): Flow<List<YoloModelImportRecordEntity>> = dao.observeImports(limit)
    fun observeExports(limit: Int = 50): Flow<List<YoloModelExportRecordEntity>> = dao.observeExports(limit)
    fun observeUpdates(limit: Int = 50): Flow<List<YoloModelUpdateRecordEntity>> = dao.observeUpdates(limit)
    fun observePerformance(limit: Int = 100): Flow<List<YoloModelPerformanceEntity>> =
        dao.observeAllPerformance(limit)

    // ---------------- 首启：把内置模型释放成真实文件 ----------------

    /**
     * 幂等：内置模型目录已存在就只补登记（DB 被清过也能恢复），不存在才从 assets 释放。
     * @return 当前应当生效的模型 id
     */
    suspend fun ensureBuiltinInstalled(): String = withContext(Dispatchers.IO) {
        val config = ensureConfig()
        val id = BUILTIN_ID
        val dir = File(modelsRoot, id)
        val param = File(dir, "$BUILTIN_BASE.param")
        val bin = File(dir, "$BUILTIN_BASE.bin")
        val labels = File(dir, ModelFiles.LABELS_NAME)

        if (!param.exists() || !bin.exists()) {
            dir.mkdirs()
            val assets = runCatching { context.assets.list(ASSETS_DIR)?.toList().orEmpty() }
                .getOrDefault(emptyList())
            if (assets.isEmpty()) {
                NekoLog.warn(NekoLog.MODULE_YOLO, "builtin_missing",
                    "APK 内没有 $ASSETS_DIR（构建时未打包模型），模型管理页将显示为空")
                return@withContext config.currentModelId
            }
            // 只释放与内置 base 同名的文件，外加 labels/manifest
            assets.filter {
                ModelFiles.baseName(it).equals(BUILTIN_BASE, true) ||
                    it == ModelFiles.LABELS_NAME || it == ModelFiles.MANIFEST_NAME
            }.forEach { name ->
                runCatching {
                    context.assets.open("$ASSETS_DIR/$name").use { ins ->
                        File(dir, name).outputStream().use { outs -> ins.copyTo(outs) }
                    }
                }.onFailure {
                    NekoLog.error(NekoLog.MODULE_YOLO, "builtin_extract_failed", "$name: ${it.message}")
                }
            }
        }

        if (!param.exists() || !bin.exists()) {
            NekoLog.warn(NekoLog.MODULE_YOLO, "builtin_incomplete", "释放后仍缺 .param/.bin")
            return@withContext config.currentModelId
        }

        // 已有登记就不重复写库（避免每次启动都刷新 updatedAt）
        if (dao.byId(id) == null) {
            val paramText = runCatching { param.readText() }.getOrDefault("")
            val classCount = if (labels.exists()) {
                runCatching { ModelFiles.countLabels(labels.readText()) }.getOrDefault(0)
            } else 0
            val manifest = readManifest(File(dir, ModelFiles.MANIFEST_NAME))
            val model = YoloModelEntity(
                id = id,
                name = manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: "YOLOv11n（内置）",
                version = manifest?.optString("version")?.takeIf { it.isNotBlank() } ?: BUILTIN_VERSION,
                dirPath = dir.absolutePath,
                paramPath = param.absolutePath,
                binPath = bin.absolutePath,
                sizeBytes = param.length() + bin.length(),
                classCount = classCount,
                inputSize = ModelFiles.detectInputSize(paramText) ?: 640,
                backend = YoloModelEntity.BACKEND_VULKAN,
                source = YoloModelEntity.SOURCE_BUILTIN,
                signature = sha256(bin),
                status = YoloModelEntity.STATUS_ENABLED
            )
            dao.upsert(model)
            dao.upsertVersion(
                YoloModelVersionEntity(
                    id = UUID.randomUUID().toString(),
                    modelId = id,
                    version = model.version,
                    path = dir.absolutePath,
                    sizeBytes = model.sizeBytes
                )
            )
            NekoLog.info(NekoLog.MODULE_YOLO, "builtin_registered",
                "${model.name} ${SwitchGuard.humanSize(model.sizeBytes)} 类别=${model.classCount} 输入=${model.inputSize}")
        }

        if (config.currentModelId.isBlank()) {
            val updated = config.copy(currentModelId = id, defaultModelId = id)
            dao.upsertConfig(updated)
            return@withContext id
        }
        config.currentModelId
    }

    suspend fun ensureConfig(): YoloModelConfigEntity = withContext(Dispatchers.IO) {
        dao.config() ?: YoloModelConfigEntity().also { dao.upsertConfig(it) }
    }

    suspend fun updateConfig(transform: (YoloModelConfigEntity) -> YoloModelConfigEntity): YoloModelConfigEntity =
        withContext(Dispatchers.IO) {
            val updated = transform(ensureConfig())
            dao.upsertConfig(updated)
            updated
        }

    // ---------------- 切换 / 回滚 / 启停 ----------------

    /**
     * 切换模型（yolo.ds 第 46~57 行）。
     * 检查不通过时**不切**，并把原因原样返回给界面 —— 静默失败比报错更难查。
     */
    suspend fun switchTo(modelId: String, reason: String, scene: YoloScene? = null): YoloOpResult =
        withContext(Dispatchers.IO) {
            val target = dao.byId(modelId)
                ?: return@withContext YoloOpResult(false, "模型不存在：$modelId")
            if (target.status == YoloModelEntity.STATUS_ERROR) {
                return@withContext YoloOpResult(false, "${target.name} 状态异常，先修复再切换")
            }
            val config = ensureConfig()
            val check = SwitchGuard.check(
                SwitchContext(
                    freeStorageBytes = freeStorageBytes(),
                    requiredBytes = target.sizeBytes,
                    batteryPercent = batteryPercent(),
                    temperatureC = batteryTemperatureC().toDouble(),
                    cooldownRemainingMs = 0
                )
            )
            val from = config.currentModelId
            if (!check.allowed) {
                dao.insertSwitch(
                    YoloModelSwitchRecordEntity(
                        fromModel = from, toModel = modelId, reason = reason,
                        success = false, message = check.reason.orEmpty()
                    )
                )
                NekoLog.warn(NekoLog.MODULE_YOLO, "switch_blocked", "${target.name}: ${check.reason}")
                return@withContext YoloOpResult(false, check.reason ?: "切换条件不满足")
            }

            applyCurrent(target, config)
            dao.insertSwitch(
                YoloModelSwitchRecordEntity(fromModel = from, toModel = modelId, reason = reason, success = true)
            )
            NekoLog.info(NekoLog.MODULE_YOLO, "switch_ok",
                "${from.ifBlank { "无" }} → ${target.name}${scene?.let { "（场景 ${it.label}）" } ?: ""}")
            YoloOpResult(true, "已切换到 ${target.name}")
        }

    /** 回滚到上一个启用过的模型（yolo.ds 第 57/72 行） */
    suspend fun rollback(reason: String = "manual_rollback"): YoloOpResult = withContext(Dispatchers.IO) {
        val config = ensureConfig()
        val all = dao.all()
        val previous = all
            .filter { it.id != config.currentModelId && it.status != YoloModelEntity.STATUS_ERROR }
            .maxByOrNull { it.updatedAt }
            ?: return@withContext YoloOpResult(false, "没有可回滚的模型")
        switchTo(previous.id, reason)
    }

    suspend fun setEnabled(modelId: String, enabled: Boolean): YoloOpResult = withContext(Dispatchers.IO) {
        val model = dao.byId(modelId) ?: return@withContext YoloOpResult(false, "模型不存在")
        val config = ensureConfig()
        if (!enabled && modelId == config.currentModelId) {
            return@withContext YoloOpResult(false, "当前正在使用该模型，先切到别的模型再禁用")
        }
        dao.setStatus(
            modelId,
            if (enabled) YoloModelEntity.STATUS_ENABLED else YoloModelEntity.STATUS_DISABLED,
            System.currentTimeMillis()
        )
        NekoLog.info(NekoLog.MODULE_YOLO, if (enabled) "model_enabled" else "model_disabled", model.name)
        YoloOpResult(true, if (enabled) "已启用 ${model.name}" else "已禁用 ${model.name}")
    }

    /** 删除（yolo.ds 第 123 行 allowDelete 权限；当前模型与内置模型不删目录） */
    suspend fun delete(modelId: String): YoloOpResult = withContext(Dispatchers.IO) {
        val config = ensureConfig()
        if (!config.allowDelete) return@withContext YoloOpResult(false, "配置里已关闭「模型删除权限」")
        val model = dao.byId(modelId) ?: return@withContext YoloOpResult(false, "模型不存在")
        if (modelId == config.currentModelId) {
            return@withContext YoloOpResult(false, "当前正在使用该模型，先切换再删除")
        }
        if (model.source != YoloModelEntity.SOURCE_BUILTIN) {
            runCatching { File(model.dirPath).deleteRecursively() }
                .onFailure { NekoLog.warn(NekoLog.MODULE_YOLO, "delete_files_failed", it.message.orEmpty()) }
        }
        dao.delete(modelId)
        // 版本记录随外键级联删除
        NekoLog.info(NekoLog.MODULE_YOLO, "model_deleted", model.name)
        YoloOpResult(true, "已删除 ${model.name}${if (model.source == YoloModelEntity.SOURCE_BUILTIN) "（内置模型只删登记，文件保留）" else ""}")
    }

    /** yolo.ds 第 67/75 行：清理旧版本，返回删除数量 */
    suspend fun pruneVersions(): YoloOpResult = withContext(Dispatchers.IO) {
        val config = ensureConfig()
        val versions = dao.allVersions()
        val plan = VersionRetention.prunePlan(
            versions.map {
                VersionInfo(
                    id = it.id, version = it.version, createdAt = it.createdAt,
                    isCurrent = it.modelId == config.currentModelId
                )
            },
            config.keepVersions
        )
        if (plan.isEmpty()) return@withContext YoloOpResult(true, "没有需要清理的旧版本")
        dao.deleteVersions(plan)
        NekoLog.info(NekoLog.MODULE_YOLO, "versions_pruned", "清理 ${plan.size} 个旧版本")
        YoloOpResult(true, "已清理 ${plan.size} 个旧版本")
    }

    // ---------------- 导入 / 导出 ----------------

    /**
     * 导入模型（yolo.ds 第 31~37 行）：支持一次选多个文件（.param + .bin [+ labels.txt]）。
     * 校验：成对、家族可识别、签名（若配置开启且有 manifest）。
     */
    suspend fun importModel(uris: List<Uri>, sourceType: String = YoloModelEntity.SOURCE_LOCAL): YoloOpResult =
        withContext(Dispatchers.IO) {
            val config = ensureConfig()
            if (!config.allowImport) return@withContext YoloOpResult(false, "配置里已关闭「模型导入权限」")
            if (uris.isEmpty()) return@withContext YoloOpResult(false, "没有选择文件")

            val staging = File(modelsRoot, "staging-${System.currentTimeMillis()}").apply { mkdirs() }
            val copied = mutableListOf<File>()
            // ★ 用 for + 错误变量，而不是 forEach + return@withContext：
            //   在嵌套 lambda 里做非局部返回，Kotlin 会报 "'return' is prohibited here"
            var copyError: String? = null
            for (uri in uris) {
                val name = displayName(uri)
                if (name == null) {
                    copyError = "无法读取文件名：$uri"
                    break
                }
                try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) {
                        copyError = "打开输入流失败：$name"
                        break
                    }
                    input.use { ins ->
                        File(staging, name).outputStream().use { outs -> ins.copyTo(outs) }
                    }
                } catch (e: Exception) {
                    copyError = "读取 $name 失败：${e.message}"
                    break
                }
                copied += File(staging, name)
            }
            val failedCopy = copyError
            if (failedCopy != null) {
                return@withContext importFailed(staging, uris, sourceType, failedCopy)
            }

            val param = copied.firstOrNull { it.extension.equals(ModelFiles.PARAM_EXT, true) }
            val bin = copied.firstOrNull { it.extension.equals(ModelFiles.BIN_EXT, true) }
            if (param == null || bin == null) {
                return@withContext importFailed(staging, uris, sourceType, "需要同时选择 .param 与 .bin 两个文件")
            }
            if (!ModelFiles.pairsWith(param.name, bin.name)) {
                return@withContext importFailed(
                    staging, uris, sourceType,
                    "${param.name} 与 ${bin.name} 不同名：NCNN 要求 .param 与 .bin 同基名"
                )
            }
            val family = ModelFiles.familyOf(param.name)
            if (family == null) {
                return@withContext importFailed(
                    staging, uris, sourceType,
                    "认不出模型家族（文件名需以 yolo11/yolov8/yolov5/yolov9 开头）"
                )
            }

            val binSha = sha256(bin)
            val manifest = copied.firstOrNull { it.name == ModelFiles.MANIFEST_NAME }?.let { readManifest(it) }
            val expected = manifest?.optJSONObject("sha256")?.optString(bin.name)
            if (config.signatureCheck && !SignatureCheck.matches(expected, binSha)) {
                return@withContext importFailed(
                    staging, uris, sourceType,
                    "签名校验不通过：manifest 声明的 ${expected?.take(12)}… 与实际 ${binSha.take(12)}… 不一致"
                )
            }

            val id = "local-$family-${System.currentTimeMillis()}"
            val dir = File(modelsRoot, id)
            staging.renameTo(dir)
            val paramText = runCatching { File(dir, param.name).readText() }.getOrDefault("")
            val labelsFile = File(dir, ModelFiles.LABELS_NAME)
            val model = YoloModelEntity(
                id = id,
                name = manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: "${family}（导入）",
                version = manifest?.optString("version")?.takeIf { it.isNotBlank() } ?: "imported",
                dirPath = dir.absolutePath,
                paramPath = File(dir, param.name).absolutePath,
                binPath = File(dir, bin.name).absolutePath,
                sizeBytes = param.length() + bin.length(),
                classCount = if (labelsFile.exists()) {
                    runCatching { ModelFiles.countLabels(labelsFile.readText()) }.getOrDefault(0)
                } else 0,
                inputSize = ModelFiles.detectInputSize(paramText) ?: 640,
                source = sourceType,
                signature = binSha,
                status = YoloModelEntity.STATUS_ENABLED
            )
            dao.upsert(model)
            dao.upsertVersion(
                YoloModelVersionEntity(
                    id = UUID.randomUUID().toString(), modelId = id,
                    version = model.version, path = dir.absolutePath, sizeBytes = model.sizeBytes
                )
            )
            dao.insertImport(
                YoloModelImportRecordEntity(
                    sourceType = sourceType, sourceUri = uris.joinToString(","),
                    modelId = id, status = "ok"
                )
            )
            NekoLog.info(NekoLog.MODULE_YOLO, "model_imported",
                "${model.name} ${SwitchGuard.humanSize(model.sizeBytes)} 类别=${model.classCount}")
            pruneVersions()
            YoloOpResult(true, "已导入 ${model.name}")
        }

    /** 导入失败时的统一收尾：清掉暂存目录 + 落一条失败记录 + 返回给界面 */
    private suspend fun importFailed(
        staging: File,
        uris: List<Uri>,
        sourceType: String,
        message: String
    ): YoloOpResult {
        staging.deleteRecursively()
        dao.insertImport(
            YoloModelImportRecordEntity(
                sourceType = sourceType,
                sourceUri = uris.joinToString(","),
                status = "failed",
                errorMessage = message
            )
        )
        return YoloOpResult(false, message)
    }

    /** 导出（yolo.ds 第 39~44 行）：打包成 zip 写到用户选的位置 */
    suspend fun exportModel(modelId: String, target: Uri): YoloOpResult = withContext(Dispatchers.IO) {
        val config = ensureConfig()
        if (!config.allowExport) return@withContext YoloOpResult(false, "配置里已关闭「模型导出权限」")
        val model = dao.byId(modelId) ?: return@withContext YoloOpResult(false, "模型不存在")
        val dir = File(model.dirPath)
        if (!dir.isDirectory) return@withContext YoloOpResult(false, "模型目录不存在：${model.dirPath}")
        val exportError = runCatching {
            context.contentResolver.openOutputStream(target)?.use { outs ->
                ZipOutputStream(outs).use { zip ->
                    dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                        zip.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: error("打开输出流失败")
        }.exceptionOrNull()
        if (exportError != null) {
            return@withContext YoloOpResult(false, "导出失败：${exportError.message}")
        }
        dao.insertExport(YoloModelExportRecordEntity(modelId = modelId, exportUri = target.toString()))
        NekoLog.info(NekoLog.MODULE_YOLO, "model_exported", model.name)
        YoloOpResult(true, "已导出 ${model.name}")
    }

    /** 记录一次性能采样（推理引擎接入后由推理侧调用） */
    suspend fun recordPerformance(modelId: String, scene: YoloScene, fps: Float, latencyMs: Int) =
        withContext(Dispatchers.IO) {
            dao.insertPerformance(
                YoloModelPerformanceEntity(
                    modelId = modelId, scene = scene.key, fps = fps, latencyMs = latencyMs,
                    powerUsage = 0f, temperature = batteryTemperatureC(), accuracy = 0f
                )
            )
        }

    /**
     * 检查更新（M18）：真调用热更新服务 —— 取签名清单 → 逐文件 SHA-256 + Ed25519 验签 →
     * 原子切换 → 回调里加载模型；任一步失败都会保留旧模型（逻辑在 [ModelUpdater] 内，fail-closed）。
     *
     * 更新源与凭据来自随包内置的密文（[BuiltinSecretStore]），没有配置时如实报错而不是假装"已是最新"。
     */
    suspend fun checkUpdate(appVersion: String, deviceIdHash: String): YoloOpResult = withContext(Dispatchers.IO) {
        val currentVersion = dao.byId(ensureConfig().currentModelId)?.version.orEmpty()
        val creds = com.nekonyan.assistant.core.security.BuiltinSecretStore.loadModelService(context)
        if (creds == null) {
            dao.insertUpdate(
                YoloModelUpdateRecordEntity(
                    fromVersion = currentVersion, toVersion = "", status = "failed",
                    message = "内置凭据里没有热更新地址/密钥"
                )
            )
            return@withContext YoloOpResult(false, "未配置更新源：内置凭据缺少 model_base_url / sign_secret")
        }
        val (modelBase, token, secret) = creds
        dao.insertUpdate(
            YoloModelUpdateRecordEntity(
                fromVersion = currentVersion, toVersion = "", status = "checking",
                message = "正在检查 $modelBase"
            )
        )
        val updater = com.nekonyan.assistant.update.ModelUpdater(
            context = context,
            api = com.nekonyan.assistant.update.HttpUpdateApi(modelBase, token, secret),
            appVersion = appVersion,
            deviceIdHash = deviceIdHash
        )
        val updated = runCatching {
            updater.checkAndUpdate { dir, labels ->
                val loaded = NcnnDetector.init(context = context, modelDir = dir, useGpu = false, inputSize = 640)
                // ★ 硬约束：清单里的 labels 不在签名范围内（实测：改 labels 验签仍通过）。
                //   加载成功后比对类别数，不一致就当作失败 → 触发 ModelUpdater 回滚，
                //   避免"框位置对、类别名整体错位"这种最难发现的故障（docs/02 专门警告过）。
                if (loaded && labels.isNotEmpty() && NcnnDetector.labels.size != labels.size) {
                    NekoLog.error(NekoLog.MODULE_UPDATE, "update_labels_mismatch",
                        "清单 ${labels.size} 类，实际加载 ${NcnnDetector.labels.size} 类 → 回滚")
                    false
                } else {
                    loaded
                }
            }
        }.getOrElse { e ->
            NekoLog.error(NekoLog.MODULE_UPDATE, "update_failed", e.javaClass.simpleName + ": " + e.message)
            dao.insertUpdate(
                YoloModelUpdateRecordEntity(
                    fromVersion = currentVersion, toVersion = "", status = "failed",
                    message = e.message ?: e.javaClass.simpleName
                )
            )
            return@withContext YoloOpResult(false, "检查更新失败：${e.message ?: e.javaClass.simpleName}")
        }
        if (!updated) {
            dao.insertUpdate(
                YoloModelUpdateRecordEntity(
                    fromVersion = currentVersion, toVersion = currentVersion, status = "done",
                    message = "无可用更新（或校验未通过，已保留旧模型）"
                )
            )
            return@withContext YoloOpResult(true, "已是最新（$currentVersion）；若刚发布过，请确认契约与负向用例")
        }
        val newVersion = dao.byId(ensureConfig().currentModelId)?.version ?: currentVersion
        dao.insertUpdate(
            YoloModelUpdateRecordEntity(
                fromVersion = currentVersion, toVersion = newVersion, status = "done",
                downloadedAt = System.currentTimeMillis(), switchedAt = System.currentTimeMillis()
            )
        )
        NekoLog.info(NekoLog.MODULE_UPDATE, "update_ok", "$currentVersion → $newVersion")
        YoloOpResult(true, "已更新并加载：$currentVersion → $newVersion")
    }

    // ---------------- 内部工具 ----------------

    private suspend fun applyCurrent(target: YoloModelEntity, config: YoloModelConfigEntity) {
        dao.all().forEach { m ->
            val status = when {
                m.id == target.id -> YoloModelEntity.STATUS_CURRENT
                m.status == YoloModelEntity.STATUS_ERROR -> YoloModelEntity.STATUS_ERROR
                m.status == YoloModelEntity.STATUS_DISABLED -> YoloModelEntity.STATUS_DISABLED
                else -> YoloModelEntity.STATUS_ROLLBACKABLE
            }
            if (status != m.status) dao.setStatus(m.id, status, System.currentTimeMillis())
        }
        dao.upsertConfig(config.copy(currentModelId = target.id))
    }

    private fun freeStorageBytes(): Long =
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

    private fun batteryPercent(): Int {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return 100   // 读不到就当作充足，不阻断用户操作
        return level * 100 / scale
    }

    /** 电池温度（℃）；读不到返回 0 而不是编一个数字 */
    private fun batteryTemperatureC(): Float {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths > 0) tenths / 10f else 0f
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment
        }.getOrNull()

    private fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun readManifest(file: File): JSONObject? =
        runCatching { JSONObject(file.readText()) }.getOrNull()

    companion object {
        const val MODELS_DIR = "models"
        const val ASSETS_DIR = "models/v1"
        const val BUILTIN_ID = "builtin-yolo11n"
        const val BUILTIN_BASE = "yolo11n"
        const val BUILTIN_VERSION = "11n-1.0"

        fun humanSize(bytes: Long): String = SwitchGuard.humanSize(bytes)

        fun builtinFamily(): String = ModelPolicy.BUILTIN
    }
}
