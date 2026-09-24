#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Edge TTS 转发服务 —— 给「猫娘助手」Android 端提供免费、无需显卡的语音合成。

==================== 运行前置条件 ====================
1) 依赖（都是轻量包，不需要 torch）：
       pip install edge-tts fastapi "uvicorn[standard]"
2) 启动（端口 5052，避开检测服务 8000 / 更新服务 8100 / RVC 5051）：
       python -m uvicorn edge_tts_server:app --host 0.0.0.0 --port 5052
3) 自检：
       curl "http://127.0.0.1:5052/health"          → {"ok":true,...}
       curl "http://127.0.0.1:5052/tts?text=你好" -o a.mp3   → 能播放即通
=====================================================

为什么返回 **MP3** 而不是 WAV：
  edge-tts 的原始输出就是 MP3，而 Android 的 MediaPlayer 原生支持 MP3 ——
  转 WAV 反而要额外装 ffmpeg。**少一个依赖，少一个故障点。**

接口：
  GET /health                        → 服务与依赖状态
  GET /voices                        → 常用中文音色（含说明）
  GET /tts?text=&voice=&rate=&pitch= → audio/mpeg 字节流

说明：Edge TTS 是微软**非官方**接口（社区广泛使用，但无官方承诺）。
若哪天它失效，本服务会返回 502 + 明确原因，App 端会自动回退到系统 TTS。
"""

import io
import time

try:
    import edge_tts
except ImportError:  # 让 /health 能如实报告，而不是起不来
    edge_tts = None

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse, Response

app = FastAPI(title="NekoNyan Edge TTS 转发", version="1.0")

DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

# 常用中文音色（App 里做下拉选择用）
VOICES = [
    {"id": "zh-CN-XiaoxiaoNeural", "name": "晓晓 · 女声（温柔，推荐）"},
    {"id": "zh-CN-XiaoyiNeural", "name": "晓伊 · 女声（活泼）"},
    {"id": "zh-CN-XiaomengNeural", "name": "晓梦 · 女声（可爱）"},
    {"id": "zh-CN-XiaohanNeural", "name": "晓涵 · 女声（知性）"},
    {"id": "zh-CN-YunxiNeural", "name": "云希 · 男声（清亮）"},
    {"id": "zh-CN-YunyangNeural", "name": "云扬 · 男声（播报）"},
    {"id": "zh-CN-liaoning-XiaobeiNeural", "name": "晓北 · 东北话"},
]

STATS = {"total": 0, "failed": 0, "last_ms": 0}


@app.get("/health")
def health():
    return {
        "ok": edge_tts is not None,
        "service": "nekonyan-edge-tts",
        "edge_tts_installed": edge_tts is not None,
        "default_voice": DEFAULT_VOICE,
        "stats": STATS,
        "hint": None if edge_tts is not None else "pip install edge-tts",
    }


@app.get("/voices")
def voices():
    return JSONResponse(content={"default": DEFAULT_VOICE, "voices": VOICES})


@app.get("/tts")
async def tts(
    text: str = Query(..., min_length=1, max_length=2000),
    voice: str = Query(DEFAULT_VOICE),
    # edge-tts 的 rate/volume 是百分比字符串（如 "+10%"），pitch 是频率（如 "+20Hz"）
    rate: str = Query("+0%"),
    volume: str = Query("+0%"),
    pitch: str = Query("+0Hz"),
):
    if edge_tts is None:
        raise HTTPException(status_code=503, detail="服务端缺少依赖：pip install edge-tts")

    t0 = time.time()
    buf = io.BytesIO()
    try:
        comm = edge_tts.Communicate(text, voice, rate=rate, volume=volume, pitch=pitch)
        # 流式收集音频分片（不落临时文件）
        async for chunk in comm.stream():
            if chunk.get("type") == "audio" and chunk.get("data"):
                buf.write(chunk["data"])
    except Exception as e:
        STATS["failed"] += 1
        # 502：上游（微软）不可用；把原因带出去，便于 App 端显示与回退
        raise HTTPException(status_code=502, detail=f"Edge TTS 合成失败：{type(e).__name__}: {e}")

    data = buf.getvalue()
    if not data:
        STATS["failed"] += 1
        raise HTTPException(status_code=502, detail="Edge TTS 返回空音频（音色名可能不对）")

    STATS["total"] += 1
    STATS["last_ms"] = int((time.time() - t0) * 1000)
    return Response(content=data, media_type="audio/mpeg")


if __name__ == "__main__":
    # 直接 python edge_tts_server.py 也能起（等价于上面的 uvicorn 命令）
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=5052)
