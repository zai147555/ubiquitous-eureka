# 模型热更新 · 发布与部署（M18）

> 目标（docs/04）：**安全地把新模型换上去，失败能秒回滚，绝不加载未经验证的文件。**
> 现状：客户端 `ModelUpdater` 已就绪（逐文件 SHA-256 + Ed25519 清单签名 + 原子切换 + 回滚保留），
> 公钥已写入 `ModelUpdater.PUBLIC_KEY_B64`；**缺的是把服务端跑起来并发布第一个版本**。

## 一、密钥（已生成，勿再生成）

| 项 | 位置 | 用途 |
|---|---|---|
| 私钥（32 字节 hex） | 本机 `.verify-chat/ed25519_private.hex`（**在仓库之外，未入库**） | 服务端环境变量 `ED25519_PRIVATE_HEX` |
| 公钥（SPKI DER Base64） | 已写入 `ModelUpdater.PUBLIC_KEY_B64` | 客户端验签 |

> ⚠️ 私钥**绝不能**进仓库、进 APK、进聊天记录。若怀疑泄漏，用 `tools/export_ncnn.md` 第二节的命令重新生成一对，
> 然后同时替换服务端环境变量与客户端常量（两边必须成对，否则更新会被判为签名失败）。

## 二、服务端部署（Windows 挂机宝）

> **一键方式**：把整个仓库目录复制到挂机宝，再把设备上的
> `.verify-chat/ed25519_private.hex` 复制到仓库根目录，然后双击
> `tools/deploy_model_server.bat`。它会：装依赖 → 从 `模型/`（或 `docs/`）复制
> `yolo11n.param/.bin/labels.txt` 到 `releases/v1/` → `setx` 写环境变量 →
> 在 8100 端口起服务 → 自检 `/ping`。下面是不用脚本时的等价手工步骤。

### 手工步骤

```cmd
:: 1) 依赖
python -m pip install fastapi "uvicorn[standard]" python-multipart cryptography -i https://pypi.tuna.tsinghua.edu.cn/simple

:: 2) 环境变量（缺一项就会退化为不安全/不可用）
set ED25519_PRIVATE_HEX=<把 .verify-chat/ed25519_private.hex 的内容粘这里>
set API_TOKEN=<与检测服务一致的令牌>
set SIGN_SECRET=<请求签名密钥，必须与客户端 SigningCanonical 用的一致>
set ROLLOUT_PERCENT=5          :: 灰度：先 5%，稳定后调大
set RELEASES_DIR=.\releases

:: 3) 起服务（端口自定，别和检测服务的 8000 撞）
python -m uvicorn model_dist_server:app --host 0.0.0.0 --port 8100
```

## 三、发布一个模型版本

```
releases/
└── v1/                      ← 版本目录名即版本号
    ├── yolo11n.param
    ├── yolo11n.bin
    └── labels.txt
```

服务端会扫描 `releases/<version>/` **自动生成并签名** `meta.json`
（签名串与服务端 `_sign_manifest`、客户端 `verifyFiles` 三方一致：
`版本 + "\n"` + 按文件名排序的 `name:size:sha256` 直接拼接，**无分隔符**）。

## 四、客户端还需要的一段（尚未接线）

`ModelUpdater` 需要一个 `UpdateApi` 实现（OkHttp + 签名拦截器）：

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(RequestSigner.interceptor(token, signSecret))   // 否则一定 401
    .build()
// UpdateApi.fetchLatest 打 GET <更新源>/model/latest?app_version=&current=&device_id=
val updater = ModelUpdater(context, api = HttpUpdateApi(client, baseUrl))
updater.checkAndUpdate { dir, labels -> NcnnDetector.init(context, dir, inputSize = 640) }
```

要接的两处：
1. 新增 `update/HttpUpdateApi.kt`（OkHttp 实现 + `ModelMeta` 解析）；
2. 「YOLO 模型」页的「检查更新」改为调用 `ModelUpdater.checkAndUpdate`（当前是如实提示"未配置更新源"）。

## 五、验收清单（照 docs/04 第 148~160 行）

- [ ] `GET /ping` 通、`GET /model/latest` 返回带签名的 `meta.json`
- [ ] 篡改任一模型文件 → 客户端**拒绝加载**（SHA-256 不匹配）
- [ ] 篡改 `meta.json` 签名 → 客户端**拒绝加载**（Ed25519 验签失败）
- [ ] 正常发布 → 更新成功、`current` 指针切换、旧版本留作 `previous`
- [ ] 回调里 `NcnnDetector.init` 返回 false → **自动回滚**到旧版本
- [ ] 断网/超时 → 不改变当前模型（fail-closed）
