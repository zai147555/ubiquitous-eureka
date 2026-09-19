@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."
echo ============================================================
echo   YOLO 模型热更新服务 · 一键部署（Windows 挂机宝）
echo   在 cmd 里运行本脚本；需要 Python 3.10+ 已在 PATH 中
echo ============================================================
echo.

REM ---------- 0) 必要文件 ----------
if not exist "code_server\model_dist_server.py" (
  echo [错误] 找不到 code_server\model_dist_server.py —— 请把整个仓库目录复制到挂机宝后再运行
  pause & exit /b 1
)
if not exist "ed25519_private.hex" (
  echo [错误] 缺少 ed25519_private.hex（私钥）
  echo        从设备上的 .verify-chat/ed25519_private.hex 复制过来，放在本脚本同目录的上一级。
  echo        这个文件是机密：不要提交仓库、不要贴到聊天里。
  pause & exit /b 1
)
set /p ED25519_PRIVATE_HEX=<ed25519_private.hex

REM ---------- 1) 参数（两个密钥必须与客户端一致）----------
set /p API_TOKEN=请输入 API_TOKEN（与检测服务一致的令牌，直接回车=沿用上次 setx 的值）:
set /p SIGN_SECRET=请输入 SIGN_SECRET（请求签名密钥，必须与客户端 SigningCanonical 一致）:
set ROLLOUT_PERCENT=5
echo.
echo [1/5] 安装依赖（清华镜像，首次约需几分钟）...
python -m pip install -q fastapi "uvicorn[standard]" python-multipart cryptography -i https://pypi.tuna.tsinghua.edu.cn/simple || goto :fail

REM ---------- 2) 发布第一个版本 ----------
echo [2/5] 准备 releases\v1 ...
if not exist "releases\v1" mkdir "releases\v1"
set SRC=
if exist "模型\yolo11n.param" set SRC=模型
if not defined SRC if exist "docs\yolo11n.param" set SRC=docs
if not defined SRC (
  echo [提示] 没找到 yolo11n.param / yolo11n.bin / labels.txt。
  echo        这三个文件可从 APK 的 assets/models/v1/ 取出，或从模型包复制到 releases\v1\ 后重跑本脚本。
) else (
  copy /y "!SRC!\yolo11n.param" "releases\v1\" >nul
  copy /y "!SRC!\yolo11n.bin"   "releases\v1\" >nul
  if exist "!SRC!\labels.txt" copy /y "!SRC!\labels.txt" "releases\v1\" >nul
  echo        已从 !SRC! 复制 3 个文件到 releases\v1
)

REM ---------- 3) 持久化环境变量 ----------
echo [3/5] 写入环境变量（setx，需重开 cmd 才对新进程生效）...
setx ED25519_PRIVATE_HEX "%ED25519_PRIVATE_HEX%" >nul
if not "%API_TOKEN%"=="" setx API_TOKEN "%API_TOKEN%" >nul
if not "%SIGN_SECRET%"=="" setx SIGN_SECRET "%SIGN_SECRET%" >nul
setx ROLLOUT_PERCENT "%ROLLOUT_PERCENT%" >nul
echo        完成

REM ---------- 4) 启动服务（端口 8100，避开检测服务的 8000）----------
echo [4/5] 启动服务（新窗口，关掉窗口即停）...
start "model-dist-server" cmd /k "cd /d %CD% && set ED25519_PRIVATE_HEX=%ED25519_PRIVATE_HEX% && python -m uvicorn model_dist_server:app --host 0.0.0.0 --port 8100"

REM ---------- 5) 自检 ----------
echo [5/5] 8 秒后自检 http://127.0.0.1:8100/ping ...
timeout /t 8 >nul
curl -s http://127.0.0.1:8100/ping
echo.
echo ------------------------------------------------------------
echo  上面应出现 {"ok":true,...} —— 本机通了。
echo  再用手机浏览器访问  http://^<挂机宝公网IP^>:8100/ping  验证外网可达；
echo  若不通，检查安全组与 Windows 防火墙的 8100 入站规则。
echo  然后把地址告诉我：我接上客户端的「检查更新」，跑端到端验证。
echo ------------------------------------------------------------
pause
exit /b 0

:fail
echo.
echo [失败] 依赖安装出错，请检查 Python 与网络后重跑。
pause
exit /b 1
