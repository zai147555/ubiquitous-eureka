#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
训练数据**预标注**：用当前模型先跑一遍，把框写成 YOLO txt，人只需要修正。

为什么这么做：App 采集回来的样本是"有画面、没标签"。纯手工框选既慢又容易漏；
先用现成模型（哪怕是上一版的）出预框，人工只做增删改，效率差一个数量级。

用法：
    # 1) 用你现在的模型给采集目录里的图打预框
    python3 tools/prelabel.py --images ./collected --model runs/nekonyan/weights/best.pt

    # 2) 想顺手看效果（会把画了框的图写到 preview/）
    python3 tools/prelabel.py --images ./collected --model yolo11n.pt --vis

    # 3) 生成训练用的 data.yaml 骨架（类别名取自模型的 names）
    python3 tools/prelabel.py --images ./collected --model best.pt --yaml dataset/data.yaml

要点：
  · **幂等**：已有同名 .txt 的图默认跳过（--overwrite 可强制重打），
    所以可以先粗标一批、再跑一次补新样本；
  · **类别顺序**：写出的 class_id 取自**模型自己的 names**。你之后训练时必须用同样的顺序，
    否则会出现"框位置对、类别名整体错位"（这个坑 App 侧也有校验，见 docs/yolo_训练与接入.md）；
  · 低置信度的框默认丢掉（--conf），太小的框会被 --min-box 过滤（预标注里的小框噪声最多）；
  · 不做任何网络请求：模型与图片都在本地。
"""
import argparse
import os
import sys


def iter_images(root: str):
    exts = (".jpg", ".jpeg", ".png", ".webp")
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in sorted(filenames):
            if name.lower().endswith(exts):
                yield os.path.join(dirpath, name)


def main() -> int:
    ap = argparse.ArgumentParser(description="用现有模型给采集到的图片打预标注（YOLO txt）")
    ap.add_argument("--images", default="./collected", help="图片目录（递归），默认 ./collected")
    ap.add_argument("--model", default="yolo11n.pt", help="模型：.pt 或 NCNN 导出的目录")
    ap.add_argument("--conf", type=float, default=0.25, help="置信度阈值，默认 0.25")
    ap.add_argument("--iou", type=float, default=0.45, help="NMS IoU，默认 0.45")
    ap.add_argument("--imgsz", type=int, default=640, help="推理尺寸，必须与端上一致，默认 640")
    ap.add_argument("--min-box", type=float, default=0.004, help="最小框面积占比（归一化），默认 0.4%%")
    ap.add_argument("--labels-dir", default="", help="标签输出目录（默认与图片同目录）")
    ap.add_argument("--overwrite", action="store_true", help="已有标签也重打")
    ap.add_argument("--vis", action="store_true", help="把画框结果写到 preview/ 便于肉眼核对")
    ap.add_argument("--yaml", default="", help="生成 data.yaml 骨架到这个路径")
    args = ap.parse_args()

    try:
        from ultralytics import YOLO
    except ImportError:
        print("缺少 ultralytics：pip install ultralytics", file=sys.stderr)
        return 2

    if not os.path.isdir(args.images):
        print(f"图片目录不存在：{args.images}", file=sys.stderr)
        return 2

    model = YOLO(args.model)
    names = model.names if isinstance(model.names, dict) else dict(enumerate(model.names))
    print(f"模型：{args.model}（{len(names)} 类）")

    files = list(iter_images(args.images))
    if not files:
        print(f"{args.images} 下没有图片", file=sys.stderr)
        return 2

    done = skipped = empty = 0
    total_boxes = 0
    for img in files:
        base = os.path.splitext(os.path.basename(img))[0]
        out_dir = args.labels_dir or os.path.dirname(img)
        os.makedirs(out_dir, exist_ok=True)
        label_path = os.path.join(out_dir, base + ".txt")
        if os.path.exists(label_path) and not args.overwrite:
            skipped += 1
            continue

        res = model.predict(img, conf=args.conf, iou=args.iou, imgsz=args.imgsz, verbose=False)[0]
        h, w = res.orig_shape[:2]
        lines = []
        for box in res.boxes:
            x1, y1, x2, y2 = [float(v) for v in box.xyxy[0].tolist()]
            cls = int(box.cls[0].item())
            conf = float(box.conf[0].item())
            bw, bh = (x2 - x1) / w, (y2 - y1) / h
            if bw * bh < args.min_box:
                continue          # 预标注的小框噪声最多，默认丢掉
            cx, cy = ((x1 + x2) / 2) / w, ((y1 + y2) / 2) / h
            lines.append(f"{cls} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")

        with open(label_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
        total_boxes += len(lines)
        done += 1
        if not lines:
            empty += 1

        if args.vis:
            vis_dir = os.path.join(os.path.dirname(args.images.rstrip("/")), "preview")
            os.makedirs(vis_dir, exist_ok=True)
            res.save(filename=os.path.join(vis_dir, os.path.basename(img)))

    print(f"完成：新打标 {done} 张（其中无目标 {empty} 张）、跳过 {skipped} 张、共 {total_boxes} 个框")
    print("提示：无目标的图**保留空 txt** —— 那是背景负样本，能明显压误检。")

    if args.yaml:
        os.makedirs(os.path.dirname(args.yaml) or ".", exist_ok=True)
        with open(args.yaml, "w", encoding="utf-8") as f:
            f.write(f"path: {os.path.abspath(args.images)}\ntrain: images/train\nval: images/val\nnames:\n")
            for i, n in sorted(names.items()):
                f.write(f"  {i}: {n}\n")
        print(f"已写出 data.yaml 骨架：{args.yaml}（类别顺序取自模型，训练时不要改）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
