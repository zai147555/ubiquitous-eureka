#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
说话链路：DeepSeek 文本 → TTS 合成 WAV → RVC 变声 → 保存 WAV

==================== 运行前置条件（缺一不可）====================
1) RVC API 服务已启动，且**已加载音色模型**（/convert 在没加载模型时返回 400）：
       python -m rvc_python api -p 5050 -l
   自检：浏览器打开 http://localhost:5050/models 应能看到模型列表；
        若列表为空，先放 .pth 到模型目录，或 POST /set_models_dir 指过去。
2) 依赖（只用到 requests，TTS 二选一）：
       pip install requests
       pip install pyttsx3          # 推荐：直接产出 WAV，无额外依赖
       # 或：pip install edge-tts     # 音质更好，但产出 MP3，需要 ffmpeg 转 WAV
3) DeepSeek Key（走 OpenAI 兼容接口）：
       Windows:        set DEEPSEEK_API_KEY=sk-xxxx
       Linux / macOS:  export DEEPSEEK_API_KEY=sk-xxxx
================================================================

接口契约（读自 rvc_python/api.py 源码，不是猜的）：
  · GET  /models            → {"models":[...]}
  · POST /models/{name}     → 加载音色
  · POST /params            → {"params":{...}}，音调键名是 **f0up_key**（+12 = 男转女）
  · POST /convert           → {"audio_data":"<base64 WAV>"} → 返回 audio/wav 字节
  · POST /convert_file      → multipart 字段名 file（本脚本在 /convert 不可用时回退到它）

用法：
  python rvc_speak.py "用猫娘的语气说一句晚安"
  python rvc_speak.py "你好" --pitch 12 --model myvoice.pth --out hello.wav
  python rvc_speak.py --say "直接朗读这句，不调 DeepSeek" --rvc http://127.0.0.1:5050
"""

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

try:
    import requests
except ImportError:
    sys.exit("缺少依赖：pip install requests")


# ----------------------------------------------------------------------------
# 1) DeepSeek：拿到要说的文本
# ----------------------------------------------------------------------------
def deepseek_reply(prompt: str, model: str = "deepseek-chat", timeout: int = 60) -> str:
    key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if not key:
        sys.exit("缺少 DEEPSEEK_API_KEY 环境变量（不想调模型就用 --say \"要读的文本\"）")
    # OpenAI 兼容：POST /chat/completions
    url = "https://api.deepseek.com/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是猫娘助手。回答要短、口语化，控制在两三句话内，便于朗读。"},
            {"role": "user", "content": prompt},
        ],
        "stream": False,
        "temperature": 0.7,
        "max_tokens": 512,
    }
    try:
        r = requests.post(
            url,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json=payload,
            timeout=timeout,
        )
    except requests.exceptions.Timeout:
        sys.exit("DeepSeek 请求超时：网络慢或超时设小了（--timeout 调大）")
    except requests.exceptions.RequestException as e:
        sys.exit(f"DeepSeek 请求失败：{type(e).__name__}: {e}")

    if r.status_code != 200:
        # 常见：401 Key 无效 / 402 余额不足 / 429 太频繁
        hint = {401: "API Key 无效", 402: "余额不足", 429: "请求过于频繁"}.get(r.status_code, "请求被拒绝")
        sys.exit(f"DeepSeek 返回 {r.status_code}（{hint}）：{r.text[:200]}")
    try:
        return r.json()["choices"][0]["message"]["content"].strip()
    except Exception:
        sys.exit(f"DeepSeek 响应结构异常：{r.text[:200]}")


# ----------------------------------------------------------------------------
# 2) TTS：文本 → 基础 WAV（优先 pyttsx3，直接产出 WAV）
# ----------------------------------------------------------------------------
def tts_to_wav(text: str, out_path: str) -> str:
    """返回所用引擎名；失败则抛异常（由 main 统一报错）"""
    # ---- 首选 pyttsx3：save_to_file 直接写 WAV，无需 ffmpeg ----
    try:
        import pyttsx3  # noqa
        engine = pyttsx3.init()
        # 语速略放慢，RVC 转换后人声更自然
        engine.setProperty("rate", 170)
        engine.save_to_file(text, out_path)
        engine.runAndWait()
        engine.stop()
        if os.path.exists(out_path) and os.path.getsize(out_path) > 1024:
            return "pyttsx3"
        raise RuntimeError("pyttsx3 未产出有效 WAV")
    except ImportError:
        pass  # 没装，试 edge-tts
    except Exception as e:
        print(f"[提示] pyttsx3 不可用（{e}），尝试 edge-tts …", file=sys.stderr)

    # ---- 备选 edge-tts：产出 MP3，需要 ffmpeg 转 WAV ----
    try:
        import asyncio
        import edge_tts  # noqa
    except ImportError:
        raise RuntimeError(
            "没有可用的 TTS 引擎：pip install pyttsx3（推荐，直接出 WAV）"
            " 或 pip install edge-tts + 安装 ffmpeg"
        )

    mp3_path = out_path + ".mp3"

    async def _synth():
        await edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural").save(mp3_path)

    asyncio.run(_synth())
    if not os.path.exists(mp3_path) or os.path.getsize(mp3_path) == 0:
        raise RuntimeError("edge-tts 未产出音频")
    if not shutil.which("ffmpeg"):
        raise RuntimeError(
            "edge-tts 产出的是 MP3，而 RVC 需要 WAV：请安装 ffmpeg，或改用 pyttsx3"
        )
    # ffmpeg 转成 16-bit 单声道 WAV（RVC 更喜欢这个格式）
    subprocess.run(
        ["ffmpeg", "-y", "-i", mp3_path, "-ac", "1", "-ar", "40000", "-sample_fmt", "s16", out_path],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    os.remove(mp3_path)
    return "edge-tts + ffmpeg"


# ----------------------------------------------------------------------------
# 3) RVC：把基础音频送去变声
# ----------------------------------------------------------------------------
def rvc_health(base_url: str) -> bool:
    try:
        r = requests.get(f"{base_url}/models", timeout=10)
        return r.status_code == 200
    except requests.exceptions.RequestException:
        return False


def rvc_set_pitch(base_url: str, pitch: int) -> None:
    """音调偏移走 POST /params，键名是 f0up_key（+12 = 男转女）"""
    try:
        r = requests.post(f"{base_url}/params", json={"params": {"f0up_key": pitch}}, timeout=15)
        if r.status_code == 200:
            print(f"[RVC] 音调已设为 f0up_key={pitch}")
        else:
            print(f"[警告] 设置音调失败（{r.status_code}）：{r.text[:120]}", file=sys.stderr)
    except requests.exceptions.RequestException as e:
        print(f"[警告] 设置音调时网络异常：{e}", file=sys.stderr)


def rvc_load_model(base_url: str, model: str) -> None:
    try:
        r = requests.post(f"{base_url}/models/{model}", timeout=120)
        if r.status_code == 200:
            print(f"[RVC] 已加载音色：{model}")
        else:
            sys.exit(f"加载音色失败（{r.status_code}）：{r.text[:200]}")
    except requests.exceptions.RequestException as e:
        sys.exit(f"加载音色时连不上服务：{e}")


def rvc_convert(base_url: str, wav_path: str, pitch: int, timeout: int = 600) -> bytes:
    """返回变声后的 WAV 字节；失败抛异常"""
    with open(wav_path, "rb") as f:
        raw = f.read()
    if len(raw) < 1024:
        raise RuntimeError(f"基础音频太小（{len(raw)} 字节），TTS 可能没成功")

    # ---- 首选 /convert：JSON + base64（契约见源码 ConvertAudioRequest）----
    try:
        r = requests.post(
            f"{base_url}/convert",
            json={"audio_data": base64.b64encode(raw).decode("ascii")},
            timeout=timeout,
        )
        if r.status_code == 200:
            return r.content
        if r.status_code == 400 and "model" in r.text.lower():
            sys.exit(f"RVC 服务没加载模型：{r.text[:160]}（先 POST /models/{{名}} 或加 --model）")
        if r.status_code in (404, 405, 422):  # 老版本/不同部署，回退到文件上传端点
            print(f"[提示] /convert 返回 {r.status_code}，回退到 /convert_file …", file=sys.stderr)
        else:
            raise RuntimeError(f"/convert 返回 {r.status_code}：{r.text[:200]}")
    except requests.exceptions.ConnectionError:
        sys.exit(
            f"连不上 RVC 服务（{base_url}）。请先启动：\n"
            f"    python -m rvc_python api -p 5050 -l\n"
            f"并确认已加载音色模型（浏览器开 {base_url}/models 看一眼）"
        )
    except requests.exceptions.Timeout:
        sys.exit("RVC 转换超时：CPU 上通常 1~3 秒/句，若音频很长请调大 --timeout")

    # ---- 回退：/convert_file（multipart，字段名 file）----
    with open(wav_path, "rb") as f:
        r = requests.post(
            f"{base_url}/convert_file", files={"file": ("tts.wav", f, "audio/wav")}, timeout=timeout
        )
    if r.status_code != 200:
        raise RuntimeError(f"/convert_file 返回 {r.status_code}：{r.text[:200]}")
    return r.content


def assert_wav(data: bytes, what: str) -> None:
    """需求：API 返回的不是有效 WAV 必须检测出来并报错"""
    if len(data) < 44:
        raise RuntimeError(f"{what}不是有效 WAV：只有 {len(data)} 字节")
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        head = data[:16].hex()
        raise RuntimeError(f"{what}不是有效 WAV（缺少 RIFF/WAVE 头，前 16 字节 {head}）")


# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="DeepSeek → TTS → RVC 变声 → WAV")
    ap.add_argument("prompt", nargs="?", help="给 DeepSeek 的提问（省略则需用 --say）")
    ap.add_argument("--say", help="跳过 DeepSeek，直接朗读这段文本")
    ap.add_argument("--pitch", type=int, default=12, help="音调偏移（f0up_key），默认 12 = 男转女")
    ap.add_argument("--model", help="要先加载的 RVC 音色文件名（如 myvoice.pth）")
    ap.add_argument("--rvc", default="http://localhost:5050", help="RVC 服务地址")
    ap.add_argument("--out", default="rvc_out.wav", help="输出 WAV 路径")
    ap.add_argument("--timeout", type=int, default=600, help="RVC 转换超时（秒）")
    args = ap.parse_args()

    base = args.rvc.rstrip("/")

    # ① 文本
    if args.say:
        text = args.say
        print(f"[1/4] 直接使用文本：{text[:40]}…")
    else:
        if not args.prompt:
            ap.error("要么给一个提问位置参数，要么用 --say \"文本\"")
        print("[1/4] 调用 DeepSeek …")
        text = deepseek_reply(args.prompt)
        print(f"      回复：{text[:60]}…")

    # ② TTS
    print("[2/4] 合成基础音频 …")
    tmp_wav = os.path.join(tempfile.gettempdir(), f"rvc_tts_{int(time.time())}.wav")
    engine = tts_to_wav(text, tmp_wav)
    size = os.path.getsize(tmp_wav)
    print(f"      {engine} 产出 {size / 1024:.0f} KB")

    # ③ 服务可用性（先探活，给出明确提示而不是让 requests 抛栈）
    print("[3/4] 检查 RVC 服务 …")
    if not rvc_health(base):
        sys.exit(
            f"RVC 服务不可用：{base}/models 无响应。\n"
            f"  请先启动：python -m rvc_python api -p 5050 -l\n"
            f"  并确认已加载音色（浏览器打开 {base}/models）"
        )
    if args.model:
        rvc_load_model(base, args.model)
    rvc_set_pitch(base, args.pitch)
    print("      转换中（CPU 上可能几秒到几十秒）…")
    converted = rvc_convert(base, tmp_wav, args.pitch, timeout=args.timeout)
    assert_wav(converted, "RVC 返回内容")
    print(f"      变声完成：{len(converted) / 1024:.0f} KB")

    # ④ 落盘
    with open(args.out, "wb") as f:
        f.write(converted)
    os.remove(tmp_wav)
    print(f"[4/4] 已保存：{os.path.abspath(args.out)}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("\n已中断")
    except Exception as e:
        sys.exit(f"\n[失败] {type(e).__name__}: {e}")
