#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
训练数据收集端点（FastAPI 片段）—— 与 App 的「训练数据采集」对接。

App 侧行为（见 core/collect/TrainingCollector.kt）：
  · **默认关闭**，用户在设置里主动开启后才按间隔采样；
  · 样本先攒在手机本地（文件名即内容哈希，天然去重），断网/服务挂掉不丢；
  · 默认仅 Wi-Fi 上传，每日有张数与流量上限；
  · 上传：POST {base}/collect，multipart 表单，字段 source + image，
    鉴权头 X-API-Token（与检测服务同一枚令牌，地址也复用内置凭据的 base）。

挂到已有的检测服务里（同一个 FastAPI app）即可，不必新起进程：

    from collect_server import router as collect_router
    app.include_router(collect_router)

起好之后自检：
    curl -X POST http://127.0.0.1:8000/collect \\
         -H "X-API-Token: <你的token>" \\
         -F source=screen -F image=@test.jpg
    → {"ok":true,"saved":"...","total":1}
"""
import hashlib
import os
import time

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile

router = APIRouter()

# —— 按你的部署改这三项 ——
TOKEN = os.environ.get("NEKO_API_TOKEN", "change-me")
DATA_DIR = os.environ.get("NEKO_COLLECT_DIR", "./collected")
MAX_BYTES = 8 * 1024 * 1024          # 单张上限，防止被塞大文件
ALLOWED = {"image/jpeg", "image/png"}


def _check(token: str | None) -> None:
    if not token or token != TOKEN:
        raise HTTPException(status_code=401, detail="bad token")


@router.post("/collect")
async def collect(
    source: str = Form("screen"),
    label: str = Form(""),
    image: UploadFile = File(...),
    x_api_token: str | None = Header(default=None, alias="X-API-Token"),
):
    _check(x_api_token)

    if image.content_type not in ALLOWED:
        raise HTTPException(status_code=415, detail=f"unsupported type {image.content_type}")

    raw = await image.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty file")
    if len(raw) > MAX_BYTES:
        raise HTTPException(status_code=413, detail="too large")

    # 用内容哈希命名：同一张图重复上传不会产生第二份（与 App 侧的去重互为双保险）
    digest = hashlib.sha256(raw).hexdigest()[:16]
    day = time.strftime("%Y%m%d")
    out_dir = os.path.join(DATA_DIR, day, source)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{digest}.jpg")
    if not os.path.exists(path):
        with open(path, "wb") as f:
            f.write(raw)

    # 标签：App 端在手机上修正过预标注后，会随图一起把 YOLO txt 送上来。
    # 存成同名 .txt（与图同目录）—— ultralytics 就是按"图旁边同名 txt"找标签的。
    # 空字符串也要写：那表示"这张图没有目标"（背景负样本），是有价值的训练数据。
    label_path = os.path.splitext(path)[0] + ".txt"
    with open(label_path, "w", encoding="utf-8") as f:
        f.write(label)

    total = sum(len(fs) for _, _, fs in os.walk(DATA_DIR))
    labeled = sum(1 for r, _, fs in os.walk(DATA_DIR) for x in fs if x.endswith(".txt"))
    return {"ok": True, "saved": os.path.relpath(path, DATA_DIR),
            "labeled": bool(label.strip()) or label == "", "total": total, "labels": labeled}


@router.get("/collect/stats")
async def stats(x_api_token: str | None = Header(default=None, alias="X-API-Token")):
    _check(x_api_token)
    per_day: dict[str, int] = {}
    for root, _, files in os.walk(DATA_DIR):
        rel = os.path.relpath(root, DATA_DIR)
        if rel == ".":
            continue
        day = rel.split(os.sep)[0]
        per_day[day] = per_day.get(day, 0) + len(files)
    return {"days": dict(sorted(per_day.items())), "total": sum(per_day.values())}
