# YOLO 训练与接入（NekoNyan 端侧 NCNN）

本文只讲**能直接跑起来**的流程，以及本项目特有的硬约束。
适用范围：训练一个自定义检测模型 → 导出 NCNN → 导入 App 端侧推理（CPU，无需联网）。

---

## 0. 先明确三条硬约束（写错了模型永远跑不对）

| 约束 | 说明 |
|---|---|
| **输入尺寸** | App 默认 `inputSize = 640`。导出时必须 `imgsz=640`，否则框全乱或检不到。要换 416/320 就得连模型记录里的 `inputSize` 一起改。 |
| **类别顺序** | `labels.txt` 的一行一个类名，顺序**必须**与训练时 `data.yaml` 的 `names` 完全一致（按 id 升序）。顺序错的表现是"框位置对、名字整体错位"，最难发现。 |
| **任务类型** | 只支持 **detect**（目标检测）。`seg`/`pose`/`obb` 导出的模型输出结构不同，App 的检测器解析不了。 |

---

## 1. 环境

```bash
python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install ultralytics                                # 会带上 torch
yolo checks                                            # 自检
```

GPU 可选：有 NVIDIA 卡会快很多；CPU 也能训小模型（yolo11n + 几百张图，几十分钟量级）。

---

## 2. 数据准备

### 2.1 目录结构

```
dataset/
├── images/
│   ├── train/   *.jpg / *.png
│   └── val/     *.jpg / *.png
└── labels/
    ├── train/   *.txt      # 与图片同名
    └── val/     *.txt
```

### 2.2 标注格式（YOLO txt）

每个 `.txt` 每行一个目标，**坐标已归一化到 0~1**：

```
class_id  cx  cy  w  h
0 0.512 0.431 0.120 0.208
1 0.301 0.702 0.083 0.140
```

- `class_id` 从 0 开始，与 `labels.txt` 行号一一对应；
- 没有目标的图片：**放一个空 txt**（这是"背景负样本"，能显著压误检，别丢）。

### 2.3 data.yaml

```yaml
path: /abs/path/to/dataset
train: images/train
val: images/val
names:
  0: cat
  1: mouse
```

### 2.4 数量与质量

- 每类 **≥150 张**（同一类出现在不同背景/尺度/光照下才算不同样本）；
- **背景样本（空标注）占 10%~20%**，明显减少误检；
- 标注工具：**X-AnyLabeling**（可用 YOLO/SAM 辅助预标注，效率最高）、LabelImg、CVAT、Roboflow；
- ★ **划分 train/val 要按来源切分**：如果数据是连续截图/视频帧，随机切帧会导致"几乎相同的两帧分别进训练集和验证集"→ 验证指标虚高、上线崩。按**场景/视频/时间段**切。

---

## 2.5 一键跑完（推荐）

采集回来的样本可以直接一条命令跑完"体检 → 切分 → 训练 → 双导出 → 发布目录"：

```bash
# 先用现有模型预标注，再训练（推荐；已标注过就加 --skip-prelabel）
tools/train_yolo.sh --images ./collected --prelabel-model runs/nekonyan/weights/best.pt

# 服务端有 GPU：换大模型、更大输入（手机端仍可用小模型，两端不必相同）
tools/train_yolo.sh --images ./collected --base yolo11s.pt --imgsz 960 --device 0 --epochs 150
```

脚本会做这几件容易漏的事：
  · **数据体检**：图片/标签数量、空标签（背景负样本）占比、类别 id 分布、格式异常行；
  · **按来源目录切分** train/val（每 5 个来源取 1 个作验证）—— 不随机切帧，
    否则相邻帧分别进两边，验证指标虚高、上线才发现不行；
  · `data.yaml` 的类别顺序**取自基座模型**，导出后按同一顺序生成 `labels.txt`
    （顺序错位的表现是"框位置对、类别名整体错位"，最难查）；
  · 产出 `release/<时间>/`：`yolo11n.param` / `.bin`（改名成 App 认的家族名）、
    `labels.txt`、`manifest.json`（含 sha256 与类别数）、`best.pt`，
    并打印"服务端换权重 + 手机端发布热更新"的下一步。

## 3. 训练

```bash
yolo detect train \
  model=yolo11n.pt \
  data=dataset/data.yaml \
  epochs=100 imgsz=640 batch=16 patience=20 \
  project=runs name=nekonyan
```

- **从 `yolo11n.pt` 微调**（COCO 预训练权重）：小数据集从零训几乎不可能收敛；
- `patience=20`：验证指标 20 轮不升就早停；
- 看 `runs/nekonyan/results.csv` 与终端表格：**关注 `metrics/mAP50-95`、`precision`、`recall`**，别只看 loss；
- train loss 降、val mAP 掉 = 过拟合 → 加数据 / 减 epochs / 加增强（`degrees`, `scale`, `mosaic`）；
- 某类 recall 长期很低 → 该类样本太少或太小（小目标可提高输入尺寸，但端上会变慢）。

---

## 4. 导出 NCNN（App 吃的格式）

```bash
yolo export model=runs/nekonyan/weights/best.pt format=ncnn imgsz=640
```

产出 `runs/nekonyan/weights/best_ncnn_model/`：

```
best_ncnn_model/
├── model.ncnn.param     # 网络结构
├── model.ncnn.bin       # 权重
└── metadata.yaml        # 含 names（类别名，顺序同训练）
```

> 文件名不重要：App 会**按内容识别**家族（yolo11n / yolov8n / yolov5n）并自动找同目录下的 `.param` 与 `.bin` 配对，不写死文件名。

---

## 5. 导入 App 前的自检（PC 上先验一遍，别到手机上猜）

```bash
yolo detect predict model=runs/nekonyan/weights/best_ncnn_model imgsz=640 source=test.jpg
```

- 框对、类别名对 → 再进 App；
- 框错位 → 十有八九是 `imgsz` 不是 640；
- 全检不到 → 检查导出时的 task 是否 detect、`labels.txt` 顺序是否对。

## 6. 导入 App

1. 准备两个文件：`.param` 与 `.bin`（名字可任意，但建议同名）；
2. 需要类名时再加 `labels.txt`（一行一个，顺序同 `names`）与可选 `manifest.json`；
3. App：**模型 → 导入模型**（可多选，会校验同名配对与家族识别）；
4. **切换**为当前模型；
5. 点**推理自检**：会给出实测延迟与 FPS。
   参考值：内置 yolo11n 在真机上实测 **≈234 ms/帧、4.27 FPS**（CPU）。你的模型如果大很多（如 11m/11l），端上会明显更慢 —— 端侧选型优先 `n` 级别。

## 7. 已知坑（都是实际踩过的）

- **类别顺序错位**：App 侧有 labels 数量校验 + 自动回滚，但仍会白跑一轮；
- **导出忘了 `imgsz=640`**：与 App 默认不符；
- **验证集泄漏**（连续帧随机切分）：指标虚高，上线才发现不行；
- **int8 量化**：`format=ncnn` 不支持直接 int8 导出，需要另走 ncnn 的 `ncnnoptimize` / `ncnn2table` 流程；
- **大模型**：端上是 CPU 推理，`n` 以外基本不实用。

---

## 附：想跳过训练？

仓库内置的就是 COCO 80 类的 `yolo11n`（`assets/models/v1/`），能识别日常物体；
如果你的目标是特定领域的（某个游戏 UI、特定物件），**必须自己训** —— COCO 里没有这些类。
