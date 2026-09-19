#!/usr/bin/env bash
# 取 NCNN 官方 Android 预编译包（含 Vulkan）并解压到 app/src/main/cpp/ncnn/。
#
# 为什么用 releases API 而不是写死版本号：ncnn 的 tag 是日期（20240820 之类），
# 写死一个不存在的 tag 会让构建以 404 失败；问 API 拿"最新一版"更稳。
# 幂等：已经存在就跳过。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/cpp/ncnn"
ABIS=("arm64-v8a" "x86_64")

ok() {
  for abi in "${ABIS[@]}"; do
    [ -f "$DEST/$abi/lib/libncnn.a" ] || [ -f "$DEST/$abi/lib/libncnn.so" ] || return 1
  done
  return 0
}

if ok; then echo "[fetch_ncnn] 已存在，跳过"; exit 0; fi

echo "[fetch_ncnn] 查询 ncnn 最新 release…"
API="https://api.github.com/repos/Tencent/ncnn/releases/latest"
URL=$(curl -fsSL -m 60 "$API" | grep -o 'https://[^"]*android-vulkan\.zip' | head -1 || true)
if [ -z "$URL" ]; then
  echo "[fetch_ncnn] 拿不到下载地址，回退到固定版本 20240820" >&2
  URL="https://github.com/Tencent/ncnn/releases/download/20240820/ncnn-20240820-android-vulkan.zip"
fi
echo "[fetch_ncnn] 下载 $URL"
TMP="$(mktemp -d)"
curl -fsSL --retry 3 --retry-delay 2 -m 900 "$URL" -o "$TMP/ncnn.zip"
unzip -q "$TMP/ncnn.zip" -d "$TMP/x"
rm -rf "$DEST"; mkdir -p "$DEST"
# 展平：ncnn-YYYYMMDD-android-vulkan/<abi>/... → ncnn/<abi>/...
SRC=$(find "$TMP/x" -maxdepth 1 -mindepth 1 -type d | head -1)
cp -r "$SRC"/* "$DEST"/
rm -rf "$TMP"
ok && echo "[fetch_ncnn] 完成：$(ls "$DEST")" || { echo "[fetch_ncnn] 解压结果不完整" >&2; exit 1; }
