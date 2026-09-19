#!/usr/bin/env bash
# 把内置的 YOLOv11n（NCNN 导出：.param + .bin）与类别表放进 assets，并生成 manifest.json（含 sha256）。
#
# 为什么由构建流程来取，而不是把 5MB 权重直接提交进仓库：
#   · 仓库保持轻量（5MB 二进制每次 clone 都要跟着走）；
#   · 本机到 GitHub 的链路实测只有 10~25 KB/s，而 CI 与环境正常的开发机是秒级；
#   · 无论谁构建，**最终 APK 里都有模型** —— 这才是"内置"的实际含义。
#
# 幂等：文件已存在且校验通过就直接跳过，重复构建不会反复下载。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/app/src/main/assets/models/v1}"
BASE="yolo11n"          # 目标文件名（Kotlin 侧按这个基名加载）
LABELS_SRC="$ROOT/tools/coco80.labels.txt"

# 公开源：主源 + 兜底源（两处都是公开仓库的原始文件；第二个源的基名不同，已在下面兼容）
SOURCES=(
  "https://raw.githubusercontent.com/PIPIKAI/android-ncnn-yolo11/main/app/src/main/assets"
  "https://raw.githubusercontent.com/Superbigbag/edge-Intrusion-detector-imx6ull/main/models/YOLOv11n_FP16"
)

mkdir -p "$DEST"

valid() {
  [ -s "$DEST/$BASE.param" ] && [ -s "$DEST/$BASE.bin" ] \
    && grep -q "Input" "$DEST/$BASE.param" \
    && [ "$(stat -c%s "$DEST/$BASE.bin" 2>/dev/null || echo 0)" -gt 1000000 ]
}

if valid; then
  echo "[fetch_yolo_model] 已存在且校验通过，跳过下载"
else
  echo "[fetch_yolo_model] 下载 $BASE 到 $DEST"
  found=0
  for src in "${SOURCES[@]}"; do
    for name in "yolo11n" "yolov11n"; do
      echo "  尝试 $src/$name.{param,bin}"
      if curl -fsSL --retry 3 --retry-delay 2 -m 300 "$src/$name.param" -o "$DEST/$BASE.param" \
         && curl -fsSL --retry 3 --retry-delay 2 -m 300 "$src/$name.bin" -o "$DEST/$BASE.bin"; then
        found=1; break 2
      fi
    done
  done
  if [ "$found" -ne 1 ] || ! valid; then
    echo "[fetch_yolo_model] 错误：模型下载失败或校验不通过" >&2
    echo "  → APK 将不含内置模型；请检查网络，或手动把 yolo11n.param/.bin 放到 $DEST" >&2
    exit 1
  fi
fi

cp "$LABELS_SRC" "$DEST/labels.txt"
CLASSES=$(grep -c . "$DEST/labels.txt" || true)
PARAM_BYTES=$(stat -c%s "$DEST/$BASE.param")
BIN_BYTES=$(stat -c%s "$DEST/$BASE.bin")
# sha256：Linux 用 sha256sum，macOS 用 shasum，都没有就退回 python3
if command -v sha256sum >/dev/null 2>&1; then
  SHA=$(sha256sum "$DEST/$BASE.bin" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  SHA=$(shasum -a 256 "$DEST/$BASE.bin" | awk '{print $1}')
else
  SHA=$(python3 -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$DEST/$BASE.bin")
fi

cat > "$DEST/manifest.json" <<JSON
{
  "name": "YOLOv11n（内置）",
  "family": "$BASE",
  "version": "11n-1.0",
  "inputSize": 640,
  "classes": $CLASSES,
  "labels": "labels.txt",
  "param": "$BASE.param",
  "bin": "$BASE.bin",
  "source": "builtin",
  "sha256": { "$BASE.bin": "$SHA" }
}
JSON

echo "[fetch_yolo_model] 完成：param ${PARAM_BYTES}B / bin ${BIN_BYTES}B / ${CLASSES} 类 / sha256 ${SHA:0:12}…"
