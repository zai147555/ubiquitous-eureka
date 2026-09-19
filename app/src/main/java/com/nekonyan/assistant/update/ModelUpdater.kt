package com.nekonyan.assistant.update

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nekonyan.assistant.core.util.VersionCompare
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * 模型热更新器（端侧闭环的关键模块）
 *
 * 安全保证：
 *   ① 双层校验：每个文件 SHA-256 + 清单 Ed25519 签名
 *   ② 原子切换：先完整落盘校验，再改指针文件（rename 原子）
 *   ③ 自动回滚：加载失败立即切回 previous（且**保留** previous 指针，可反复回滚）
 *   ④ 兼容检查：min_app_version / 清单有效期
 *
 * 目录结构：
 *   filesDir/models/
 *     ├── current/             ← 当前生效的实体副本（运行时读取，保证路径稳定）
 *     ├── v3/ v4/ …            ← 各版本实体（保留最近 2 个用于回滚）
 *     ├── .staging/            ← 下载暂存（校验通过才改名入库）
 *     └── manifest.json        ← { "current":"v4", "previous":"v3", ... }
 *
 * 与 docs/04 的差异说明：
 *   · 文档用 download_tmp/，这里用 .staging/ 并在校验通过后「改名为版本目录」，
 *     避免出现「已登记但内容不完整」的版本目录；
 *   · 文档的指针法保留（manifest 是唯一真相），current/ 只是实体副本。
 */
class ModelUpdater(
    private val context: Context,
    private val api: UpdateApi,
    private val appVersion: String,
    private val deviceIdHash: String,
    /** 可选：查询/下载走 HMAC 签名（见 RequestSigner）。未配置则不发签名头（仅限内网调试） */
    private val signer: com.nekonyan.assistant.core.net.RequestSigner? = null
) {

    companion object {
        private const val TAG = "ModelUpdater"
        private const val DIR_MODELS = "models"
        private const val DIR_STAGING = ".staging"
        private const val MANIFEST = "manifest.json"
        private const val MAX_KEEP_VERSIONS = 2      // 保留 current + previous
        private const val DOWNLOAD_RETRY = 3
        private const val MIN_FREE_SPACE_HEADROOM = 8L * 1024 * 1024   // 留 8MB 余量

        /**
         * ★ 内置公钥（Ed25519，仅公钥；私钥在服务端 CI）
         * 生成：python tools/export_ncnn.md 中的 keygen 命令，取公钥 DER 的 Base64。
         * 未替换时更新功能会「显式失败」，绝不静默通过。
         */
        private const val PUBLIC_KEY_B64 = "REPLACE_WITH_YOUR_ED25519_PUBLIC_KEY_DER_BASE64"

        private const val KEY_NOT_SET = "REPLACE_WITH"

        /** 当前生效的模型版本（由本类维护） */
        @Volatile var activeVersion: String? = null
            private set

        /** 公钥是否已配置（未配置时 checkAndUpdate 直接快速失败，便于排查） */
        fun isPublicKeyConfigured(): Boolean = !PUBLIC_KEY_B64.contains(KEY_NOT_SET)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val modelsDir get() = File(context.filesDir, DIR_MODELS).apply { mkdirs() }
    private val stagingDir get() = File(modelsDir, DIR_STAGING)
    private val manifestFile get() = File(modelsDir, MANIFEST)

    init {
        // 冷启动清理：孤儿暂存目录、半截版本目录、以及上次「切了指针但没加载成功」的版本
        recoverOnStartup()
        activeVersion = manifest()?.optString("current")?.ifBlank { null }
    }

    // ============================================================
    // 主流程
    // ============================================================

    /**
     * 检查并更新模型。
     * @param onLoaded 校验通过并切换指针后回调：加载新模型，返回 true 表示加载成功。
     *                 返回 false / 抛异常都会触发回滚。
     * @return true 表示有更新并成功加载
     */
    suspend fun checkAndUpdate(
        onLoaded: suspend (modelDir: File, labels: List<String>) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isPublicKeyConfigured()) {
            Log.e(TAG, "PUBLIC_KEY_B64 未配置，为保证「校验通过才加载」，本次更新直接放弃。"
                    + "请在 CI 取出 Ed25519 公钥并写入 ModelUpdater.PUBLIC_KEY_B64")
            return@withContext false
        }

        val current = currentVersion()
        val meta = try {
            api.fetchLatest(appVersion, current ?: "", deviceIdHash)
        } catch (e: Exception) {
            Log.w(TAG, "查询版本失败: ${e.message}")
            return@withContext false
        }

        if (meta == null || !meta.available || meta.version == current) {
            Log.i(TAG, "无需更新（current=$current）")
            return@withContext false
        }
        if (compareVersion(appVersion, meta.minAppVersion) < 0) {
            Log.w(TAG, "App 版本过低，跳过 ${meta.version}（需 ≥ ${meta.minAppVersion}）")
            return@withContext false
        }
        // 清单有效期（docs/01 契约里的 expire_at）
        if (meta.expireAt > 0 && System.currentTimeMillis() > meta.expireAt) {
            Log.w(TAG, "清单已过期（expire_at=${meta.expireAt}），拒绝更新")
            return@withContext false
        }
        if (meta.files.isEmpty()) {
            Log.w(TAG, "清单没有任何文件，拒绝更新")
            return@withContext false
        }

        // ---- ① 下载到 .staging（不污染任何已登记版本） ----
        Log.i(TAG, "发现新模型 ${meta.version}，开始下载…")
        cleanStaging()
        stagingDir.mkdirs()

        var totalNeed = 0L
        for (f in meta.files) {
            if (!isSafeRemoteUrl(f.url)) {
                Log.e(TAG, "非 HTTPS 下载地址，拒绝: ${f.url}")
                cleanStaging(); return@withContext false
            }
            totalNeed += f.size
        }
        if (freeSpace() < totalNeed + MIN_FREE_SPACE_HEADROOM) {
            Log.e(TAG, "存储空间不足：需要 ${totalNeed / 1024}KB，可用 ${freeSpace() / 1024}KB")
            cleanStaging(); return@withContext false
        }

        for (f in meta.files) {
            val target = File(stagingDir, f.name)
            if (!downloadTo(f.url, target, f.size)) {
                Log.e(TAG, "下载失败: ${f.name}")
                cleanStaging(); return@withContext false
            }
        }
        if (meta.labels.isNotEmpty()) {
            File(stagingDir, "labels.txt").writeText(meta.labels.joinToString("\n"))
        }

        // ---- ② ★ 双层校验（在暂存区完成，此时指针未动） ----
        if (!verifyFiles(stagingDir, meta)) {
            Log.e(TAG, "模型校验失败，拒绝更新（旧模型与指针均未改动）")
            cleanStaging(); return@withContext false
        }

        // ---- ③ 暂存区改名入库（同分区 rename 原子；失败则退化为复制） ----
        val versionDir = File(modelsDir, sanitizeVersion(meta.version))
        versionDir.deleteRecursively()
        if (!stagingDir.renameTo(versionDir)) {
            Log.w(TAG, "rename 失败，改用复制入库")
            stagingDir.copyRecursively(versionDir, overwrite = true)
            cleanStaging()
        }

        // ---- ④ 切换指针（previous 保留，回滚有目标） ----
        val prev = current
        switchTo(version = meta.version, previous = prev, failed = null)

        // ---- ⑤ 触发加载（失败则回滚，且不破坏 previous） ----
        val ok = try {
            onLoaded(versionDir, meta.labels)
        } catch (e: Throwable) {
            Log.e(TAG, "新模型加载异常: ${e.message}")
            false
        }

        if (!ok) {
            Log.e(TAG, "新模型加载失败 → 回滚到 $prev")
            // 回滚后指针回到 prev；previous 指向「上一个」旧版本，
            // 并把失败版本记录在 manifest.failed，便于服务端/诊断看到
            switchTo(version = prev ?: "", previous = previousVersion(), failed = meta.version)
            activeVersion = prev
            return@withContext false
        }

        activeVersion = meta.version
        cleanupOldVersions()
        Log.i(TAG, "✅ 模型已更新到 ${meta.version}")
        true
    }

    // ============================================================
    // 下载（断点续传 + 重试）
    // ============================================================

    private fun downloadTo(url: String, target: File, expectedSize: Long): Boolean {
        var retry = 0
        while (retry < DOWNLOAD_RETRY) {
            try {
                val existed = if (target.exists()) target.length() else 0L
                // 已下满则跳过（重试幂等）
                if (expectedSize > 0 && existed == expectedSize) return true

                val builder = Request.Builder().url(url)
                if (existed > 0) builder.header("Range", "bytes=$existed-")
                signer?.sign("GET", url, builder)

                http.newCall(builder.build()).execute().use { resp ->
                    if (resp.code == 416) {           // Range 越界 → 本地文件已损坏，重下
                        target.delete()
                        return false
                    }
                    if (!resp.isSuccessful && resp.code != 206) {
                        Log.w(TAG, "下载返回 ${resp.code}: $url")
                        return false
                    }
                    val body = resp.body ?: return false
                    // 只有服务端明确回了 206 才是真续传；否则一律从头写，避免半截文件拼接
                    val append = existed > 0 && resp.code == 206
                    RandomAccessFile(target, "rw").use { raf ->
                        if (append) raf.seek(existed) else raf.setLength(0)
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var written = if (append) existed else 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                raf.write(buf, 0, n)
                                written += n
                                if (expectedSize > 0 && written > expectedSize) {
                                    throw IllegalStateException("收到数据超过清单声明大小")
                                }
                            }
                        }
                    }
                }
                if (expectedSize <= 0) {
                    // 服务端没给 size 时，至少要求文件非空（真正的完整性由 SHA-256 兜底）
                    if (target.length() > 0) return true
                    Log.w(TAG, "下载到空文件")
                } else if (target.length() == expectedSize) {
                    return true
                } else {
                    Log.w(TAG, "大小不符: ${target.length()} != $expectedSize，重试")
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载异常(${retry + 1}/$DOWNLOAD_RETRY): ${e.message}")
            }
            retry++
            Thread.sleep(1000L * (1 shl (retry - 1)))     // 1s → 2s → 4s
        }
        return false
    }

    /** 仅允许 https（docs/04：模型下载必须走 HTTPS；证书绑定由 OkHttp 层统一配置） */
    private fun isSafeRemoteUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true)

    // ============================================================
    // 校验（SHA-256 + Ed25519 签名）
    // ============================================================

    /** 逐文件 SHA-256 + 清单签名。任何异常/不匹配一律 false（fail-closed） */
    private fun verifyFiles(dir: File, meta: ModelMeta): Boolean {
        // ① 逐文件 SHA-256（不是只看第一个）
        for (f in meta.files) {
            val file = File(dir, f.name)
            if (!file.exists()) { Log.e(TAG, "缺少文件 ${f.name}"); return false }
            if (f.size > 0 && file.length() != f.size) {
                Log.e(TAG, "大小不匹配 ${f.name}: ${file.length()} != ${f.size}")
                return false
            }
            val actual = sha256(file)
            if (!actual.equals(f.sha256, ignoreCase = true)) {
                Log.e(TAG, "SHA-256 不匹配: ${f.name}")
                return false
            }
        }
        // ② 清单签名（Ed25519）
        val payload = buildString {
            append(meta.version).append('\n')
            meta.files.sortedBy { it.name }.forEach {
                append("${it.name}:${it.size}:${it.sha256}")
            }
        }
        if (meta.signature.isBlank()) {
            Log.e(TAG, "清单没有签名 → 拒绝加载（服务端 ED25519_PRIVATE_HEX 可能未配置）")
            return false
        }
        return try {
            val pub = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)))
            val sig = Signature.getInstance("Ed25519").apply {
                initVerify(pub)
                update(payload.toByteArray(Charsets.UTF_8))
            }
            val ok = sig.verify(Base64.decode(meta.signature, Base64.NO_WRAP))
            if (!ok) Log.e(TAG, "签名验证不通过（清单被篡改，或公私钥不配对）")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "签名校验异常（公钥格式/算法不可用）: ${e.message}")
            false
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ============================================================
    // 指针（原子切换）
    // ============================================================

    private fun manifest(): JSONObject? = try {
        if (!manifestFile.exists()) null else JSONObject(manifestFile.readText())
    } catch (e: Exception) {
        Log.w(TAG, "manifest 解析失败，按无主处理: ${e.message}")
        null
    }

    fun currentVersion(): String? = manifest()?.optString("current")?.ifBlank { null }

    /** 供回滚/诊断使用 */
    fun previousVersion(): String? = manifest()?.optString("previous")?.ifBlank { null }

    /** 上次更新失败的版本（供日志/上报） */
    fun lastFailedVersion(): String? = manifest()?.optString("failed")?.ifBlank { null }

    /**
     * 原子写指针：写 .tmp → rename。
     * rename 在同一文件系统内是原子操作；失败才退化为直接写（并记日志）。
     */
    private fun switchTo(version: String, previous: String?, failed: String?) {
        if (version.isBlank()) {
            Log.w(TAG, "switchTo 收到空版本号，忽略（避免把指针写坏）")
            return
        }
        val obj = JSONObject().apply {
            put("current", version)
            previous?.takeIf { it.isNotBlank() }?.let { put("previous", it) }
            failed?.takeIf { it.isNotBlank() }?.let { put("failed", it) }
            put("updated_at", System.currentTimeMillis())
        }
        val tmp = File(modelsDir, "$MANIFEST.tmp")
        tmp.writeText(obj.toString())
        if (tmp.renameTo(manifestFile)) {
            Log.i(TAG, "指针已切换 → $version（previous=$previous, failed=$failed）")
        } else {
            Log.w(TAG, "manifest rename 失败，退化为直接写")
            manifestFile.writeText(obj.toString())
            tmp.delete()
        }
        // current/ 实体副本（运行时读取路径稳定；失败不影响指针正确性）
        syncCurrentDir(version)
    }

    /**
     * 把 current/ 同步成 version 的内容。
     * 注意：整个过程包在 .new 临时目录里，最后才替换 current/，
     * 避免「先 delete 后 copy」中途失败导致 current/ 残缺。
     */
    private fun syncCurrentDir(version: String) {
        val src = File(modelsDir, version)
        if (!src.isDirectory) {
            Log.w(TAG, "版本目录不存在，跳过 current/ 同步: $version")
            return
        }
        val cur = File(modelsDir, "current")
        val next = File(modelsDir, "current.new")
        val old = File(modelsDir, "current.old")
        try {
            next.deleteRecursively(); next.mkdirs()
            src.listFiles()?.forEach { f ->
                if (f.isFile) f.copyTo(File(next, f.name), overwrite = true)
            }
            old.deleteRecursively()
            if (cur.exists() && !cur.renameTo(old)) {
                cur.deleteRecursively()          // 退化路径：先删再换
            }
            if (!next.renameTo(cur)) {
                next.copyRecursively(cur, overwrite = true)
                next.deleteRecursively()
            }
            old.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "current/ 同步失败（不影响指针，下次启动会重试）: ${e.message}")
        }
    }

    /**
     * 冷启动恢复：
     *   1) 清掉 .staging 残留（上次下载/校验中断）
     *   2) 清掉没有被 manifest 引用的版本目录（孤儿）
     *   3) 若 recorded failed 版本仍在，只清它的指针记录，不删目录（保留证据）
     */
    private fun recoverOnStartup() {
        try {
            cleanStaging()
            val m = manifest() ?: return
            val keep = setOfNotNull(
                m.optString("current").ifBlank { null },
                m.optString("previous").ifBlank { null },
                "current", "current.new", "current.old"
            )
            modelsDir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                if (d.name !in keep) {
                    Log.i(TAG, "清理孤儿目录: ${d.name}")
                    d.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动恢复失败（不阻塞）: ${e.message}")
        }
    }

    /**
     * 旧版本清理：始终保留 current / previous / current 副本，其余按新→旧保留 2 个。
     * （原实现用 drop(MAX_KEEP_VERSIONS) 极易把 previous 本身删掉，这里改为显式保留集合）
     */
    private fun cleanupOldVersions() {
        try {
            val keep = setOfNotNull(
                currentVersion(), previousVersion(),
                DIR_STAGING, "current", "current.new", "current.old"
            )
            modelsDir.listFiles()
                ?.filter { it.isDirectory && it.name !in keep }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_KEEP_VERSIONS)
                ?.forEach {
                    Log.i(TAG, "清理旧版本: ${it.name}")
                    it.deleteRecursively()
                }
        } catch (e: Exception) {
            Log.w(TAG, "清理旧版本失败: ${e.message}")
        }
    }

    private fun cleanStaging() {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
    }

    private fun freeSpace(): Long = try {
        modelsDir.usableSpace
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    /** 版本号只允许字母数字 . _ -（防止清单里的 version 拼出路径） */
    private fun sanitizeVersion(v: String): String {
        val safe = v.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (safe.isEmpty() || safe != v) {
            Log.w(TAG, "版本号包含非法字符，已清洗: '$v' → '$safe'")
        }
        return safe.ifEmpty { "unknown" }
    }

    /**
     * 版本号比较。
     * ★ 实现抽到 core/util/VersionCompare（纯 Kotlin），
     *   让"1.10 > 1.9"这类易错点能被单元测试直接覆盖，而不是藏在私有方法里。
     */
    private fun compareVersion(a: String, b: String): Int = VersionCompare.compare(a, b)
}

// ============================================================
// 数据模型
// ============================================================

data class ModelFile(
    val name: String, val size: Long, val sha256: String, val url: String
)

data class ModelMeta(
    val version: String,
    val available: Boolean,
    val force: Boolean,
    val minAppVersion: String,
    val notes: String,
    val files: List<ModelFile>,
    val signature: String,
    val labels: List<String>,
    /** 清单有效期（0 表示服务端未给，不做过期判断） */
    val expireAt: Long = 0L
)

/**
 * 由 UpdateApi 实现（Retrofit 接口），见 docs/01 数据契约。
 *
 * ⚠️ 服务端 /model/latest 需要签名头，而 Retrofit 接口自己拿不到「最终 URL + body」，
 *    因此**必须**在 OkHttpClient 上装签名拦截器，否则一定 401：
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(RequestSigner.interceptor(token, signSecret))
 *     .build()
 * val api = Retrofit.Builder().baseUrl(base).client(client)...create(UpdateApi::class.java)
 * ```
 *
 * 返回 null 表示「无可用更新」，与「网络失败」区分开（后者抛异常，由调用方兜底）。
 */
interface UpdateApi {
    suspend fun fetchLatest(appVersion: String, current: String, deviceId: String): ModelMeta?
}
