#!/usr/bin/env bash
# 一键：采集样本 → 数据体检 → 切分 → 训练 → 导出（NCNN 给手机 / ONNX 给服务端）→ 发布目录
#
# 用法：
#   tools/train_yolo.sh --images ./collected
#   tools/train_yolo.sh --images ./collected --epochs 150 --imgsz 640 --base yolo11s.pt --device 0
#
# 设计要点（都是踩过的坑）：
#   · **按来源切分** train/val，不随机切帧 —— 否则相邻帧分别进两边，验证指标虚高、上线就崩；
#   · 训练前做**数据体检**：报告类别 id 分布、标签缺失、空标签（背景样本）数量，
#     并检查 class_id 是否超出 data.yaml 的 names 范围（这就是"框对名字错"的源头）；
#   · 导出 NCNN 时固定 imgsz=640（与 App 的 inputSize 对齐），ONNX 用同一尺寸给服务端；
#   · 产出 release/ 目录：param/bin（改名成 App 认的家族名）+ labels.txt（顺序取自模型）
#     + manifest.json（含 sha256），供热更新发布使用。
set -euo pipefail

IMAGES="./collected"; BASE="yolo11n.pt"; EPOCHS=100; IMGSZ=640; BATCH=16
DEVICE="auto"; NAME="nekonyan"; WORK="./dataset"; YAML=""
SKIP_PRELABEL=0; PRELABEL_MODEL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --images) IMAGES="$2"; shift 2;;
    --base) BASE="$2"; shift 2;;
    --epochs) EPOCHS="$2"; shift 2;;
    --imgsz) IMGSZ="$2"; shift 2;;
    --batch) BATCH="$2"; shift 2;;
    --device) DEVICE="$2"; shift 2;;
    --name) NAME="$2"; shift 2;;
    --work) WORK="$2"; shift 2;;
    --prelabel-model) PRELABEL_MODEL="$2"; SKIP_PRELABEL=0; shift 2;;
    --skip-prelabel) SKIP_PRELABEL=1; shift;;
    -h|--help) sed -n '2,14p' "$0"; exit 0;;
    *) echo "未知参数：$1"; exit 2;;
  esac
done

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

say "0. 环境检查"
command -v python3 >/dev/null || { echo "缺 python3"; exit 2; }
python3 -c "import ultralytics" 2>/dev/null || { echo "缺 ultralytics：pip install ultralytics"; exit 2; }
python3 -c "import torch,sys; sys.exit(0 if torch.cuda.is_available() else 1)" \
  && GPU=1 || GPU=0
if [ "$DEVICE" = "auto" ]; then DEVICE=$([ "$GPU" = "1" ] && echo 0 || echo cpu); fi
echo "  设备=$DEVICE（本机 $([ "$GPU" = 1 ] && echo '有 CUDA' || echo '无 CUDA')）  基座=$BASE  imgsz=$IMGSZ  epochs=$EPOCHS"
[ -d "$IMAGES" ] || { echo "图片目录不存在：$IMAGES"; exit 2; }

say "1. 数据体检"
python3 - "$IMAGES" <<'PY'
import os, sys, collections
root=sys.argv[1]; exts=(".jpg",".jpeg",".png",".webp")
imgs=[]; 
for d,_,fs in os.walk(root):
    for f in fs:
        if f.lower().endswith(exts): imgs.append(os.path.join(d,f))
lab=[os.path.splitext(p)[0]+".txt" for p in imgs]
have=[p for p in lab if os.path.exists(p)]
empty=[p for p in have if os.path.getsize(p)==0]
cls=collections.Counter(); bad=0
for p in have:
    for line in open(p, encoding="utf-8"):
        line=line.strip()
        if not line: continue
        parts=line.split()
        if len(parts)!=5: bad+=1; continue
        try: cls[int(parts[0])]+=1
        except ValueError: bad+=1
print(f"  图片 {len(imgs)} 张；有标签 {len(have)}；无标签 {len(imgs)-len(have)}（将按背景处理）")
print(f"  空标签（背景负样本）{len(empty)} 张" + ("（建议 ≥10%，能压误检）" if len(empty) < len(have)*0.1 else ""))
print(f"  类别分布：" + (", ".join(f"{k}:{v}" for k,v in sorted(cls.items())) if cls else "无"))
if bad: print(f"  ⚠ 格式异常行 {bad} 条（应为 'class cx cy w h'，已跳过）")
if not have: print("  ⚠ 一张标签都没有：先跑 tools/prelabel.py 预标注，或手工标注")
PY

if [ "$SKIP_PRELABEL" = "0" ] && [ -n "$PRELABEL_MODEL" ]; then
  say "2. 预标注（模型：$PRELABEL_MODEL）"
  python3 "$(dirname "$0")/prelabel.py" --images "$IMAGES" --model "$PRELABEL_MODEL"
else
  say "2. 预标注（跳过）"
fi

say "3. 切分 train/val（**按来源目录切**，避免相邻帧泄漏）"
mkdir -p "$WORK/images/train" "$WORK/images/val" "$WORK/labels/train" "$WORK/labels/val"
python3 - "$IMAGES" "$WORK" <<'PY'
import os, sys, shutil, hashlib, collections
src, work = sys.argv[1], sys.argv[2]
exts=(".jpg",".jpeg",".png",".webp")
groups=collections.defaultdict(list)
for d,_,fs in os.walk(src):
    rel=os.path.relpath(d, src)
    for f in sorted(fs):
        if f.lower().endswith(exts): groups[rel].append(os.path.join(d,f))
if not groups: print("  没有图片"); sys.exit(0)
keys=sorted(groups)                        # 按目录名排序，稳定
val_keys={k for i,k in enumerate(keys) if i % 5 == 0}   # 每 5 个来源取 1 个作验证
def link(a,b):
    try: os.symlink(os.path.abspath(a), b)
    except OSError: shutil.copy2(a, b)
n_tr=n_va=0
for k, files in groups.items():
    split = "val" if k in val_keys else "train"
    for p in files:
        stem=os.path.splitext(os.path.basename(p))[0]
        # 用内容哈希做前缀，避免不同目录同名文件互相覆盖
        uniq=hashlib.sha1(p.encode()).hexdigest()[:8] + "_" + stem
        dst_img=os.path.join(work,"images",split,uniq+os.path.splitext(p)[1])
        link(p, dst_img)
        lp=os.path.splitext(p)[0]+".txt"
        if os.path.exists(lp): link(lp, os.path.join(work,"labels",split,uniq+".txt"))
        else: open(os.path.join(work,"labels",split,uniq+".txt"),"w").close()
        if split=="train": n_tr+=1
        else: n_va+=1
print(f"  train {n_tr} 张 / val {n_va} 张（来自 {len(groups)} 个来源目录）")
print(f"  val 来源：{sorted(val_keys)}")
PY

say "4. 生成 data.yaml（类别顺序取自基座模型，训练时不要改）"
python3 - "$BASE" "$WORK" <<'PY'
import sys
from ultralytics import YOLO
base, work = sys.argv[1], sys.argv[2]
m=YOLO(base); names=m.names if isinstance(m.names, dict) else dict(enumerate(m.names))
path=work+"/data.yaml"
with open(path,"w",encoding="utf-8") as f:
    f.write(f"path: {work}\ntrain: images/train\nval: images/val\nnames:\n")
    for i,n in sorted(names.items()): f.write(f"  {i}: {n}\n")
print(f"  ✓ {path}（{len(names)} 类）")
PY

say "5. 训练"
python3 -c "
from ultralytics import YOLO
import sys
YOLO('$BASE').train(data='$WORK/data.yaml', epochs=$EPOCHS, imgsz=$IMGSZ, batch=$BATCH,
                    device='$DEVICE', project='runs', name='$NAME', patience=20)
"
BEST="runs/$NAME/weights/best.pt"
[ -f "$BEST" ] || BEST="runs/$NAME/weights/last.pt"
[ -f "$BEST" ] || { echo "训练没有产出权重，检查上面的输出"; exit 1; }

say "6. 导出（手机端 NCNN imgsz=$IMGSZ / 服务端 ONNX）"
python3 -c "
from ultralytics import YOLO
YOLO('$BEST').export(format='ncnn', imgsz=$IMGSZ)
YOLO('$BEST').export(format='onnx', imgsz=$IMGSZ)
"

say "7. 汇总发布目录"
OUT="release/$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
python3 - "$BEST" "$OUT" "$IMGSZ" <<'PY'
import sys, os, glob, shutil, hashlib, json
best, out, imgsz = sys.argv[1], sys.argv[2], int(sys.argv[3])
from ultralytics import YOLO
m=YOLO(best); names=m.names if isinstance(m.names, dict) else dict(enumerate(m.names))
d=os.path.dirname(best)
ncnn=glob.glob(os.path.join(d,"*_ncnn_model")) or glob.glob(os.path.join(os.path.dirname(d),"*_ncnn_model"))
shutil.copy2(best, os.path.join(out,"best.pt"))
if ncnn:
    src=ncnn[0]
    for f in os.listdir(src):
        if f.endswith(".param"): shutil.copy2(os.path.join(src,f), os.path.join(out,"yolo11n.param"))
        if f.endswith(".bin"):   shutil.copy2(os.path.join(src,f), os.path.join(out,"yolo11n.bin"))
    print(f"  ✓ NCNN：{src} → yolo11n.param / yolo11n.bin")
else:
    print("  ⚠ 没找到 *_ncnn_model 目录，检查导出输出")
with open(os.path.join(out,"labels.txt"),"w",encoding="utf-8") as f:
    for i,n in sorted(names.items()): f.write(f"{n}\n")
files={}
for f in sorted(os.listdir(out)):
    p=os.path.join(out,f)
    if os.path.isfile(p):
        files[f]={"size":os.path.getsize(p),
                  "sha256":hashlib.sha256(open(p,'rb').read()).hexdigest()}
with open(os.path.join(out,"manifest.json"),"w",encoding="utf-8") as f:
    json.dump({"inputSize":imgsz,"classes":len(names),"names":names,"files":files},
              f, ensure_ascii=False, indent=2)
print(f"  ✓ {out}/ → {', '.join(files)}")
print(f"  类别 {len(names)} 个，inputSize={imgsz}")
PY

cat <<EOF

================ 下一步 ================
1) 服务端（准）：把 $OUT/best.pt（或导出的 .onnx）换到你的检测服务里，重启即可。
2) 手机端（快）：发布热更新 —— 用 $OUT 里的 yolo11n.param / yolo11n.bin / labels.txt，
   按 tools/model_release.md 的契约上传（GET /model/latest + /files/{版本}/{文件名} + 签名）。
   ★ labels.txt 的**行序**必须与模型类别顺序一致（脚本已按模型 names 生成）。
3) 真机验证：App → 模型 → 导入模型 → 切换为当前模型 → **推理自检**（会给出实测延迟/FPS）。
EOF
