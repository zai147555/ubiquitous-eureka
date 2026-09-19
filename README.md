# 猫娘助手（NekoNyan）

一个跑在 Android 上的个人助手：**本地视觉（YOLO）+ 云端对话（DeepSeek）+ 模型管理**，
数据默认只留在本机，密钥全部走 Android Keystore。

> 版本 **0.5.0** · 最低 Android 12（API 31）· 默认橘猫色 · Kotlin + Jetpack Compose + Room

## 它现在能做什么

| 能力 | 说明 |
|---|---|
| **对话** | DeepSeek 流式对话（SSE 逐字），紧急停止**真的会切断请求**，已收到的内容照常保存 |
| **人格** | 只保留「名称 + 描述」两个字段；新增/编辑/删除/复制/设为默认/切换当前，切换写只读日志；当前人格用于聊天与任务 |
| **知识库** | 多知识库 → 分类（可独立开关）→ 条目，支持图片；**AI 知识库**固定卡片、用户只读（界面不提供删除入口），AI 可读取（开关默认开）并按「先总结后写入」入库 |
| **YOLO 模型** | **内置 YOLOv11n**（NCNN `.param` + `.bin` + 80 类标签，随 APK 打包）；管理页含当前/已安装/可更新/已禁用/只读日志六视图 + 切换·回滚·禁用·删除·导入·导出 + 配置项；场景对照（普通·长任务·低功耗→11n，战斗→8n，高速→5n，高精度→9） |
| **本地推理** | 自带 `libyolo_ncnn.so`（NCNN，arm64-v8a / x86_64）；模型页「推理自检」会真加载模型跑一帧，把**实测延迟/FPS** 写进性能表（测试机上约 234ms/帧） |
| **识别服务** | 可接自建的 YOLO11n 检测 HTTP 服务（`X-API-Token` 鉴权），地址与令牌存 Keystore，凭据可加密内置 |
| **音乐** | 只播本地文件：导入（六种格式）/元数据与封面解析/去重/播放·暂停·上一首·下一首/删除。**没有**在线搜索、推荐或自动播放 |
| **插件（M15）** | **声明式**插件：一个 `plugin.json` 即可改主题色、加侧边栏项、加提示词模板与配置项。**不含可执行代码**；紧急停止、权限入口、日志只读、安全提示是宿主白名单，插件清单里出现相关键名会被整包拒绝 |
| **无障碍（只读）** | 采集当前界面的文本/控件结构，用于整理与总结；**不调用 performAction**，因此没有点击、滑动、输入注入，也不做后台常驻抓取 |
| **日志** | 只读日志表 + 写入前脱敏（API Key / Token / 手机号 / 身份证等），队列满时优先丢 DEBUG、绝不丢 ERROR |

## 隐私与安全（设计取舍，写在这里备查）

- **密钥不明文**：API Key、服务令牌走 Android Keystore（AES-256-GCM），密钥不出 Keystore；日志与错误信息一律先脱敏；
- **不传原图**：数据采集默认只上传裁剪/缩略后的样本，打码在副本上做，不污染界面上显示的位图；
- **模型热更新 fail-closed**：逐文件 SHA-256 + Ed25519 清单签名，未通过**拒绝加载**并回滚；公钥内置在 App，私钥只在服务端；
- **本地优先**：模型文件、聊天记录、知识库都存在本机，不上传未授权的服务器。

## 明确不做

游戏自动化、输入注入、反作弊绕过、内存修改、账号交易、多人竞技作弊 —— 这些不在本项目范围内，
也不会因为「加个开关」就变成合规功能。

## 怎么拿到可安装的包

推送后 GitHub Actions 会自动跑 `./gradlew test` + `assembleDebug`：

```
仓库 → Actions → 最新一次 run → Artifacts → nekonyan-debug-apk
```

构建时会自动取两样东西（都可离线跳过，见下）：
`tools/fetch_yolo_model.sh` 取内置 YOLOv11n 权重、`tools/fetch_ncnn.sh` 取 NCNN 预编译包。
`-PskipYoloModel=true` / `-PskipNcnn=true` 可跳过（跳过后 APK 不含模型或原生库，应用界面会**如实显示**对应功能不可用）。

> 开发机无法运行 Gradle 的实测原因、以及本机可复现的验证方式，见文末「验证步骤」与「为什么这台机器上跑不了 ./gradlew」两节。

---

## 一、当前状态（重要，请先读）

| 项目 | 状态 |
|---|---|
| 源码与工程配置 | ✅ 已完成，36 个 Kotlin 文件 + 完整 Gradle 配置 |
| 纯 Kotlin 核心算法 | ✅ **已真实编译 + 运行验证**（见下方"已验证"） |
| `./gradlew test` / `assembleDebug` | ✅ **由 GitHub Actions 真实执行**（本机仍跑不了，原因见第五节） |
| 可在真机安装的 APK | ✅ **已产出**：Actions → 对应 run → artifacts → `nekonyan-debug-apk` |

**已验证（不是"看起来对"，是真跑过）**：在开发机（Android + Termux 沙箱）上用
kotlinc 2.4.20 + OpenJDK 17 真实编译并运行了三块核心逻辑，共 **33 项断言全通过**：

| 验证对象 | 断言数 | 说明 |
|---|---|---|
| `core/util/VersionCompare.kt` | 12 | 含 `1.9 < 1.10`（数值比较而非字符串）、预发布版、非法串不抛异常 |
| `core/util/SigningCanonical.kt` | 9 | canonical 六段结构、query 排序、空体哈希；**与 Python 服务端实现交叉比对一致** |
| `core/log/Redactor.kt` | 12 | API Key / Token / 密码 / 验证码 / 手机号 / 身份证脱敏，且不误伤正常日志 |
| `core/util/NekoMode.kt` | 6 | 七种模式齐备、键唯一、非法键回退默认 |
| 任务规则（名称/消息必填、模板变量） | 9 | 空白串也被拒；未提供的 `{{变量}}` 保持原样便于排查 |
| `MusicRepository` 时长与格式判定 | 11 | `--:--`、`1:05`、`12:34`；`.M4A` 大小写不敏感、`.mp4` 正确排除 |

**本轮新增：对话链路 73 项断言，全部真实运行通过。**

| 验证对象 | 断言数 | 说明 |
|---|---|---|
| `core/chat/ChatConfig.kt` | 20 | 地址归一化（含粘贴完整 endpoint）、**http 一律拒绝**、参数越界夹取、日志不含 Key 明文 |
| `core/chat/SseFramer.kt` | 17 | 空行分帧、多行 `data` 合并、心跳注释忽略、CRLF、`[DONE]`、**断流 flush 残留帧** |
| `core/chat/ChatJson.kt` | 17 | 引号/反斜杠/换行/控制字符转义、括号引号配平、数字与语言环境无关 |
| `core/chat/PromptComposer.kt` | 19 | 人格+模式+知识库拼接、知识库超长截断、**裁剪后首条必须是 user**、标题截断 |

**合计 132 项断言，全部真实运行通过**（59 项核心算法 + 73 项对话链路）。

其中 `SigningCanonical` 是端云之间唯一必须逐字节一致的东西，写错的表现是"上线后全部 401"，
所以它被单独抽成纯 Kotlin 文件并做了跨语言比对。

---

## 二、工程结构

```
NekoNyan/
├── gradlew / gradlew.bat / gradle/wrapper/     ← Gradle 8.9 wrapper
├── settings.gradle.kts / build.gradle.kts
├── gradle/libs.versions.toml                   ← 版本目录（依赖集中管理）
├── .github/workflows/android.yml               ← CI：跑 test + assembleDebug
└── app/
    ├── build.gradle.kts / proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/                            ← 主题、图标（矢量猫脸）、字符串
        │   └── java/com/nekonyan/assistant/
        │       ├── NekoApp.kt                  ← Application：日志/数据库/环境检测/温度监控
        │       ├── MainActivity.kt             ← 唯一 Activity + 系统开屏
        │       ├── ui/
        │       │   ├── NekoAppRoot.kt          ← 主题 + 路由
        │       │   ├── theme/                  ← 四套配色、字体缩放、动态效果分级
        │       │   ├── component/              ← 侧边菜单、紧急停止按钮
        │       │   └── screen/                 ← 主界面、知识库、日志、设置、占位页
        │       ├── data/
        │       │   ├── db/                     ← Room：19 实体 + 11 DAO + 首启种子数据
        │       │   └── repo/                    ← 仓库层（主题偏好、知识库）
        │       ├── core/
        │       │   ├── log/                    ← 只读日志 + 脱敏
        │       │   ├── env/                    ← 环境检测 + 温度/电量分级
        │       │   ├── security/               ← Keystore 加密存储 + 设备指纹
        │       │   ├── util/                   ← 纯 Kotlin：版本比较、签名规范化
        │       │   ├── net/                    ← 请求签名（HMAC）
        │       │   ├── yolo/                   ← NCNN 推理封装（JNI）
        │       │   └── data/                   ← 采样脱敏 + 上传 Worker
        │       └── update/                     ← 模型热更新（校验/原子切换/回滚）
        └── test/java/com/nekonyan/assistant/   ← 单元测试（4 个测试类）
```

---

## 三、已实现（M1 + 部分 M2/M18）

### 主界面（严格对齐需求原文）
- **QQ 风格：顶部无返回键、无聊天名**；右上三条杠展开侧边菜单
- 侧边菜单七项：知识库、任务、音乐、插件、配置、设置、全部日志
- 底部输入栏：输入框、发送、导入文件、照片
- **始终存在紧急停止按钮**（严肃红色、方形硬边、不接受插件样式覆盖）
- 聊天气泡左右分栏，长任务运行时顶部常驻状态条

### 主题与无障碍
- 四套主题：**橘猫（默认）**、白猫、黑猫、高对比度
- 字体大小可调（0.8×~1.6×），作用于全部文字
- 动态效果三档（完整/减少/关闭）+ **低功耗、发热、低电量自动降级**
- **识别系统「移除动画」设置**（无障碍要求），开启时完全关闭动画
- 色盲友好：所有开关旁边都有文字状态，不依赖颜色

### M2 · 知识库 / 任务 / 音乐
- **知识库**：多知识库 → 分类 → 条目三层；分类可**独立开关**（关闭后 AI 读不到，有单测覆盖）；
  文本条目增删；图片条目走系统照片选择器（不申请整库权限）；删除知识库级联清理
- **任务**：名称与消息**双向必填**（空白串也拒），按七种模式分独立列表；
  一键执行会把任务消息发送给 AI 并自动切回聊天界面；支持 `{{变量}}` 模板；
  列表显示模式、状态、最后执行时间；启用/停用开关
- **音乐**：**仅本地已下载、无推荐、不会自动播放**（仓库层刻意不提供任何联网检索 API）；
  手动点播单曲，不排队、不自动下一首

### M6a · DeepSeek 文本对话（本轮新增）
- **配置页**（不再是占位页）：API 地址 / Key / 模型 / 超时 / 温度 / 最大输出；
  Key 走 Android Keystore（AES-256-GCM），「测试连接」会**真发一次最小请求（8 token）**
  —— 格式对不等于能用，只有真连通才算配好；
- **流式对话**：SSE 逐字回显（`DeepSeekClient` + `SseFramer`），边收边显示；
- **持久化**：会话与消息落 Room（`conversation_session` / `message`），界面订阅数据库 Flow，
  消息**以库为唯一数据源**；流式增量只在内存累积，整轮结束落库一次（省闪存写）；
- **紧急停止真的能停**：`Call.cancel()` 切断连接 —— 协程取消打不断阻塞中的网络读，
  只改状态会出现"按了停还在刷字"；停止时**已收到的内容照样落库**；
- **错误翻译成人话**：401/402/404/429/超时/DNS/TLS 各有对应提示，且错误体先过 `Redactor` 再进日志/界面。

### 0.3.0 · YOLO 模型管理（按工作区 `yolo.ds`）
- **侧边栏新增「YOLO 模型」**，顺序严格为：知识库、任务、音乐、**YOLO 模型**、插件、配置、设置、全部日志；
- **内置 YOLOv11n**（NCNN 的 `.param` + `.bin` + 80 类 COCO 标签）：由 `tools/fetch_yolo_model.sh`
  在**构建时**拉进 `assets/models/v1/`（挂在 Gradle 的 `preBuild` 上，本地与 CI 一视同仁，
  离线可用 `-PskipYoloModel=true` 跳过），首启释放到 `filesDir/models/builtin-yolo11n/` 并自动登记 ——
  仓库不必背 5MB 二进制，而**APK 里一定有模型**；下载失败会让构建失败，不会悄悄出个空壳包；
- **管理页**：当前使用模型（名称/版本/大小/类别/输入/后端/延迟，含「切换 / 回滚 / 禁用 / 导出」）、
  已安装模型、可更新模型、已禁用模型、**只读模型日志**（切换/导入/导出/更新/性能）、模型配置
  （自动切换、仅 WiFi、自动更新、灰度、A/B、签名校验、导入/导出/删除权限、保留版本数）；
- **导入**（多选 `.param` + `.bin`，校验同名配对 / 家族识别 / 签名）、**导出**（打包成 zip 写到用户选定位置）；
- **场景切换**：普通/长任务/低功耗 → 11n，战斗/复杂 → 8n，高速 → 5n，高精度 → 9；
  场景模型没装会**如实说明并回退**，不静默降级；
- **切换前检查**存储（含 50% 余量）/电量（<10% 拦）/温度（>45℃ 拦）/冷却；
- **版本保留** 2~5 个，当前版本永不被清理；8 张表按 `yolo.ds` 第 124~130 行建在 Room 里（库版本升到 2）；
- **诚实标注**：NCNN 原生推理库（`libyolo_ncnn.so`）尚未接入，因此性能区显示「未运行」而不是编数字。

### 0.2.1 · 交互修正（按真机截图反馈）
- **系统导航键不再压在输入栏上**：MainActivity 隐藏底部导航键（边缘上滑可临时唤出），
  同时底栏按 `WindowInsets.safeDrawing` 的**底边**留白 —— 三键导航、手势导航、键盘弹起
  三种情况下输入栏都不会被盖住（不依赖"藏起来"这一个手段）；
- **键盘弹起时输入栏跟着上移**（清单加 `adjustResize`），紧急停止按钮一并挪到键盘上方，
  任何时候都点得到；
- **从设置/知识库等功能页退出 → 回聊天页并自动展开侧边栏**：侧边栏是唯一导航入口，
  退回一个空聊天页会逼用户再点一次三条杠；系统返回键与页内返回箭头走同一条路径，
  侧边栏展开时返回键先关侧边栏（不会直接退出 App）。

### 数据层（Room）
- 19 个实体，字段与 `猫娘助手.ds` 第 423~465 行清单逐一对齐
- 11 个 DAO；**日志 DAO 刻意不提供 delete/update**（需求：日志只读）
- 首启种子数据：AI 知识库（名「AI」且**初始不可管理**）、默认人格、
  七个模式的**保守默认权限**（不允许 AI 执行、需要确认、不自动写 AI 知识库）

### 功耗与错误处理
- 温度分级（<38℃ 全速 / 38~42℃ 降频 / 42~45℃ 仅核心识别 / >45℃ 暂停）
- 电量分级（<30% 降频 / <20% 只提示 / <10% 停止）
- 温度读取失败时**返回"未知"而不是猜**，降级为按系统热状态判断
- 日志脱敏（写入前）+ 保留期轮转 + **队列满时优先丢 DEBUG，绝不丢 ERROR**

### 安全
- API Key 走 Android Keystore（AES-256-GCM，密钥不出 Keystore）
- 设备指纹加盐哈希，日志不输出明文标识
- 备份规则排除全部本地数据（需求：图表数据本地存储不上传）

### YOLO 端云闭环（来自工作区 docs/01~05 的整改版）
- `ModelUpdater`：SHA-256 + Ed25519 双层校验、原子切换、**回滚保留 previous**、
  冷启动恢复孤儿目录、强制 HTTPS、清单有效期检查
- `UploadWorker`：按服务端 `rejects` **精确丢弃**不合规样本（不再无限重传）
- `DataCollector`：真像素化脱敏（区域来源可注入 ML Kit）、裁剪越界不再崩溃、
  并发不再丢样本
- `NcnnDetector` + `yolo_ncnn_jni.cpp`：letterbox 灰边修正（114/255）、
  Bitmap 锁全路径释放、输出布局自适应、blob 名从模型读取

---

## 四、未实现（按里程碑排期）

| 里程碑 | 内容 | 现状 |
|---|---|---|
| M2 | 知识库 / 任务 / 音乐 页面 | ✅ **已完成**（含图片条目） |
| M3 | 悬浮窗、语音输入、无障碍服务（爬楼） | 未开始 |
| M4 | MediaProjection 抓屏 + YOLO 真机推理 + NCNN/Vulkan 接入 | 代码已备，缺 native 构建 |
| M5 | Shizuku 触控注入、双模型 v8n/v11n 切换 | 未开始 |
| M6 | DeepSeek **文本对话**接入（配置页 / 流式 / 持久化 / 可中断） | ✅ **已完成**（视觉接入与决策循环未开始） |
| M7~M14 | 自动游戏、地图路径、物资/改枪/理包/跟随、大世界、撤离、配装、人格、上下文、爬楼、视频、拍摄、调色、翻译、自动化、备份、追踪、市场、天气、Skill | 未开始 |
| M15 | 插件系统（列表/已安装/已启用/已禁用/可更新/日志） | 表结构已就绪 |
| — | **YOLO 模型管理**（内置 11n / 导入导出 / 场景切换 / 版本保留 / 日志） | ✅ **已完成**（推理引擎见 M4） |
| M16~M17 | 功耗管理、错误处理、流水线、任务队列、状态恢复、反馈闭环、性能仪表盘、新手引导 | 功耗与环境检测已完成大半 |
| M18 | 数据上传、服务端训练、模型热更新、灰度、A/B | 客户端代码已完成 |
| M19 | 测试、文档、发布 | 单元测试已起步 |

**明确不做**（需求中的非目标）：iOS、云同步账号体系、内存修改、注入、
反作弊绕过、多人竞技作弊、账号交易。

---

## 五、验证步骤

### 方式一：CI（推荐，本机跑不了时用这个）
推送到 GitHub 后 `.github/workflows/android.yml` 会自动执行：
```
./gradlew test          # 单元测试
./gradlew assembleDebug # 产出 APK，并作为 artifact 上传
                        # preBuild 会先跑 tools/fetch_yolo_model.sh 把内置模型放进 assets
```
> 模型获取挂在 Gradle（`fetchYoloModel` 任务）而不是 workflow 里：`/.github/workflows/` 的改动
> 需要 token 具备 workflow 权限，缺权限时 GitHub 会拒绝整棵树。挂 Gradle 后本地与 CI 行为一致。

### 方式二：本地开发机
```bash
export JAVA_HOME=/path/to/jdk17
./gradlew test
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
首次启动应看到：系统开屏（猫图标）→ 主界面（橘猫色）→ 右上三条杠展开侧边菜单。

### 方式三：本机可复现的验证（不需 Gradle）
```bash
export JAVA_HOME=<你的 JDK17>
kotlinc app/src/main/java/com/nekonyan/assistant/core/util/VersionCompare.kt \
        app/src/main/java/com/nekonyan/assistant/core/util/SigningCanonical.kt \
        app/src/main/java/com/nekonyan/assistant/core/log/Redactor.kt -d out
```
这三块逻辑是纯 Kotlin，可独立编译与测试。

> **本机实测的坑**：`kotlinc` 的 shebang 指向 `/data/data/com.termux/...`，在本环境直接执行会报
> `bad interpreter`；改用 `bash $PREFIX/bin/kotlinc ...` 调用即可（另需给 `TMPDIR` 指定可写目录）。
> 本轮对话链路的 73 项断言就是这么跑出来的。

---

## 六、为什么这台机器上跑不了 ./gradlew

需求要求「`./gradlew test` 和 `assembleDebug` 通过，**或说明失败原因**」，
以下是实测出来的确切原因（不是没试）：

1. **Gradle 官方发行版无法在 Android 上运行**
   `gradle-8.9-bin.zip`（已下载验证）内的 `libnative-platform.so` 是 **glibc 构建**：
   ```
   NEEDED  libstdc++.so.6 / libm.so.6 / libgcc_s.so.1 / libc.so.6
   dlopen failed: library "libstdc++.so.6" not found
   ```
   Android 使用 bionic + `/system/bin/linker64`，没有 glibc 及其 `libstdc++.so.6`。

2. **Termux 的 gradle 包同样不可用**
   实测 Termux 源里的 `gradle 9.7.1` 包，其 `libnative-platform.so`
   **仍是 glibc 构建**（`readelf -d` 显示依赖 `libstdc++.so.6`），
   官方 `org.gradle.native=false`、`--no-daemon`、`-Dorg.gradle.vfs.watch=false`
   全部无法绕过 native 服务初始化，报同一个错。
   Termux 上 Gradle 能跑通常依赖其自行打过补丁的构建，本环境前缀为
   `/data/data/com.dsharnessmobile.shell/files/usr`（非 `com.termux`），包内脚本与
   原生库路径也都对不上。

3. **本环境已验证可用、且已实际使用的部分**
   | 组件 | 状态 |
   |---|---|
   | OpenJDK 17.0.20（aarch64，NDK 构建） | ✅ 已安装并跑通 `javac` + 运行 |
   | Kotlin 编译器 2.4.20 | ✅ 已安装，33 项核心逻辑断言真实跑过 |
   | Gradle（任何版本） | ❌ 见上述原因 |
   | Android SDK / build-tools | ❌ 未安装（官方 cmdline-tools 只有 x86_64，本机为 aarch64） |

**三条可行路径**（按推荐度）：
1. **用 CI 构建**（已配置好，零改动）；
2. 在 PC 上用 Android Studio / 命令行构建；
3. ~~用 `proot` 跑 glibc 用户态~~ —— **已实测，此路不通**（记录如下，避免以后重复踩坑）：
   - Termux 的 `proot 5.1.107` 本身是 NDK 原生构建，**能装、能跑**（可执行 musl 动态链接器）；
   - 下载 Debian trixie rootfs（35MB，gh-proxy 加速）并解包后，
     **所有 glibc 二进制一律 `execve` 失败**（`/bin/dash`、`/bin/sh` 均报
     "the loader was not found or doesn't work"）；
   - 同一环境里 Alpine(musl) 的 `/lib/ld-musl-aarch64.so.1` 却能被 proot 正常执行 ——
     说明 **proot 的 ptrace 机制可用，失败点是 glibc 加载器**（Android 应用沙箱限制）；
   - 结论：本机无法通过 proot 获得可用的 glibc 用户态，**Gradle 在本机没有可行路径**。

---

## 七、模块与需求对照速查

| 需求原文 | 落点 |
|---|---|
| 默认橘猫色；橘猫/白猫/黑猫/高对比度 | `ui/theme/NekoColors.kt` |
| 顶部无返回键和聊天名；右上三条杠 | `ui/screen/MainScreen.kt` |
| 底部：输入框、发送、导入文件、照片 | `MainScreen.ChatInputBar` |
| 侧边菜单七项 | `ui/component/NekoDrawerContent.kt` |
| 始终有紧急停止按钮；严肃红色不萌化 | `ui/component/EmergencyStopButton.kt` |
| 日志只读、字段、模块、脱敏 | `data/db/Daos.kt`(LogDao)、`core/log/` |
| AI 知识库名「AI」、初始不可管理 | `data/db/NekoDatabase.kt` seed |
| 温度/电量分级与自动降级 | `core/env/ThermalMonitor.kt` |
| 环境检测与一键诊断 | `core/env/EnvironmentChecker.kt` |
| API Key 加密存 Keystore | `core/security/SecurityStore.kt` |
| 模型热更新：校验通过才切换、失败回滚 | `update/ModelUpdater.kt` |
| 数据脱敏上传、可关闭 | `core/data/DataCollector.kt` |
| 全链路 HTTPS + 请求签名 | `core/net/RequestSigner.kt` + `core/util/SigningCanonical.kt` |
