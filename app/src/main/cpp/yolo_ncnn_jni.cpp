// ============================================================
//  yolo_ncnn_jni.cpp —— 端侧 YOLO(NCNN) 推理 JNI 实现
//
//  与 NcnnDetector.kt 的 native 方法一一对应：
//    external fun nativeInit(paramPath, binPath, threads, useGpu, inputSize): Long
//    external fun nativeDetect(handle, bitmap, conf, iou, maxDetections): FloatArray?
//    external fun nativeRelease(handle)
//
//  输出：扁平 FloatArray [x1,y1,x2,y2,cls,conf, ...]（坐标已还原到原图）
//
//  ── 相对上一版的修正 ───────────────────────────────────────
//   ① letterbox 灰边：归一化后填充值必须是 114/255，而不是 114（114 是纯白）
//   ② AndroidBitmap 锁在所有分支都能释放（原版两条 early return 会漏）
//   ③ 输出布局自适应（通道优先 / 锚点优先），不再硬假设一种排布
//   ④ 输入/输出 blob 名优先用模型自带名字，找不到才回退，换模型不再静默零检出
//   ⑤ NMS 前按分数截断候选，避免病态输出把 O(n²) 放大成卡死
//   ⑥ GetStringUTFChars / Mat 分配 / extract 返回值全部检查
// ============================================================
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <algorithm>
#include <cmath>
#include <cstring>

#include "net.h"

#define LOG_TAG "yolo_ncnn"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// 候选框上限：超过则先按分数截断，防止 NMS 的 O(n²) 拖死线程
static const int kMaxCandidates = 3000;

struct Detector {
    ncnn::Net net;
    bool use_gpu = false;
    int input_size = 640;
    std::string input_name = "images";    // 回退名（YOLOv8 ncnn 导出）
    std::string output_name = "output0";  // 回退名
};

static inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline float iou(const float* a, const float* b) {
    const float x1 = std::max(a[0], b[0]);
    const float y1 = std::max(a[1], b[1]);
    const float x2 = std::min(a[2], b[2]);
    const float y2 = std::min(a[3], b[3]);
    const float w = std::max(0.f, x2 - x1);
    const float h = std::max(0.f, y2 - y1);
    const float inter = w * h;
    const float area_a = std::max(0.f, a[2] - a[0]) * std::max(0.f, a[3] - a[1]);
    const float area_b = std::max(0.f, b[2] - b[0]) * std::max(0.f, b[3] - b[1]);
    const float uni = area_a + area_b - inter;
    return uni > 1e-6f ? inter / uni : 0.f;
}

// ------------------------------------------------------------
// init：加载 .param / .bin
// ------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_nekonyan_assistant_core_yolo_NcnnDetector_nativeInit(
        JNIEnv* env, jobject, jstring paramPath, jstring binPath,
        jint threads, jboolean useGpu, jint inputSize) {

    if (paramPath == nullptr || binPath == nullptr) {
        LOGE("nativeInit: 路径为空");
        return 0;
    }
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    if (param == nullptr) { LOGE("nativeInit: GetStringUTFChars(param) 失败"); return 0; }
    const char* bin = env->GetStringUTFChars(binPath, nullptr);
    if (bin == nullptr) {
        env->ReleaseStringUTFChars(paramPath, param);
        LOGE("nativeInit: GetStringUTFChars(bin) 失败");
        return 0;
    }

    auto* d = new (std::nothrow) Detector();
    if (d == nullptr) {
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        LOGE("nativeInit: 内存不足");
        return 0;
    }
    d->net.opt.num_threads = threads > 0 ? threads : 4;
    d->net.opt.use_vulkan_compute = useGpu;
    d->net.opt.use_fp16_packed = true;
    d->net.opt.use_fp16_storage = true;
    d->net.opt.use_fp16_arithmetic = false;
    d->net.opt.use_packing_layout = true;
    d->use_gpu = useGpu;
    d->input_size = (inputSize > 0) ? (int) inputSize : 640;

    int rp = d->net.load_param(param);
    int rb = d->net.load_model(bin);

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);

    if (rp != 0 || rb != 0) {
        LOGE("load model failed: param=%d bin=%d", rp, rb);
        delete d;
        return 0;
    }

    // ★ 用模型自带的 blob 名，避免导出工具换名后「能加载但永远零检出」
    //   接口自 ncnn 起即提供 input_names()/output_names()（2020 年后所有版本）；
    //   个别自定义裁剪版若没有该接口，编译期会报错 → 把下面整段注释掉即可回退默认名。
    {
        const std::vector<const char*>& ins = d->net.input_names();
        const std::vector<const char*>& outs = d->net.output_names();
        if (!ins.empty() && ins[0] != nullptr) {
            d->input_name = ins[0];
        }
        if (!outs.empty()) {
            if (outs[0] != nullptr) d->output_name = outs[0];
            for (const char* n : outs) {
                if (n != nullptr && strstr(n, "output") != nullptr) {
                    d->output_name = n;
                    break;
                }
            }
        }
    }
    LOGI("model loaded (threads=%d, gpu=%d, size=%d, in='%s', out='%s')",
         d->net.opt.num_threads, (int) useGpu, d->input_size,
         d->input_name.c_str(), d->output_name.c_str());
    return reinterpret_cast<jlong>(d);
}

// ------------------------------------------------------------
// detect：Bitmap → ncnn Mat → 推理 → NMS → FloatArray
// ------------------------------------------------------------
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_nekonyan_assistant_core_yolo_NcnnDetector_nativeDetect(
        JNIEnv* env, jobject, jlong handle, jobject bitmap,
        jfloat conf, jfloat iou_thres, jint maxDetections) {

    auto* d = reinterpret_cast<Detector*>(handle);
    if (d == nullptr || bitmap == nullptr) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("get bitmap info failed");
        return nullptr;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        // Kotlin 侧已统一转 ARGB_8888，这里只是兜底防护
        LOGE("bitmap must be RGBA_8888 (got %d)", (int) info.format);
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        LOGE("lockPixels failed");
        return nullptr;
    }

    const int img_w = (int) info.width;
    const int img_h = (int) info.height;
    const int size  = d->input_size;
    if (img_w <= 0 || img_h <= 0) {
        AndroidBitmap_unlockPixels(env, bitmap);   // ★ 每条退出路径都解锁
        return nullptr;
    }

    // ---- letterbox（保持比例 + 灰边 114）----
    const float scale = std::min(size / (float) img_w, size / (float) img_h);
    const int nw = (int) std::round(img_w * scale);
    const int nh = (int) std::round(img_h * scale);
    const int dx = (size - nw) / 2;
    const int dy = (size - nh) / 2;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
            (const unsigned char*) pixels, ncnn::Mat::PIXEL_RGBA2RGB,
            img_w, img_h, nw, nh);
    AndroidBitmap_unlockPixels(env, bitmap);       // ★ 像素用完立刻解锁（下面不再碰 bitmap）

    if (in.empty()) {
        LOGE("from_pixels_resize failed");
        return nullptr;
    }

    // 归一化（YOLOv8: 0~1）
    const float norm_vals[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in.substract_mean_normalize(nullptr, norm_vals);

    // ★ 填充到 size×size：必须先归一化再填，且填的是 114/255
    //   （原实现填 114，归一化空间里等于纯白，灰边变白边 → 与训练侧不一致）
    ncnn::Mat padded(size, size, 3);
    if (padded.empty()) {
        LOGE("padded Mat 分配失败（%dx%d）", size, size);
        return nullptr;
    }
    padded.fill(114.f / 255.f);
    {
        const int copy_w = std::min(nw, size);
        const int copy_h = std::min(nh, size);
        for (int c = 0; c < 3; ++c) {
            const float* src = in.channel(c);
            float* dst = padded.channel(c);
            if (src == nullptr || dst == nullptr) continue;
            for (int y = 0; y < copy_h; ++y) {
                const int ty = y + dy;
                if (ty < 0 || ty >= size) continue;
                memcpy(dst + (size_t) ty * size + dx, src + (size_t) y * nw,
                       (size_t) copy_w * sizeof(float));
            }
        }
    }

    // ---- 推理 ----
    ncnn::Extractor ex = d->net.create_extractor();
    // 说明：不要把线程数设在 Extractor 上 —— ncnn 已移除 Extractor::set_num_threads，
    // 线程数统一由 Net::opt.num_threads 决定（init 里已设，见 d->net.opt.num_threads）。
    // 之前这里留了一行旧 API 调用，CI 上直接编译失败：no member named 'set_num_threads' in 'ncnn::Extractor'。
    if (ex.input(d->input_name.c_str(), padded) != 0) {
        LOGE("input blob '%s' 不存在（模型与代码不匹配）", d->input_name.c_str());
        return nullptr;
    }
    ncnn::Mat out;
    int ret = ex.extract(d->output_name.c_str(), out);
    if (ret != 0 || out.empty()) {
        // ★ 原实现不检查返回值：名字不对时会静默返回空结果，极难排查
        LOGE("extract '%s' 失败(ret=%d) 或输出为空", d->output_name.c_str(), ret);
        return nullptr;
    }

    // ---- 解析输出布局（自适应，不再硬假设一种排布）----
    // YOLOv8 ncnn 常见输出：
    //   A) (4+nc) 通道 × 8400 宽   → channel(c) 取第 c 个特征
    //   B) 8400 通道 × (4+nc) 宽   → row(i) 取第 i 个 anchor 的特征向量
    int num_anchors = 0;
    int feat_dim = 0;
    bool channels_first = true;                 // A 布局
    if (out.h > 0 && out.h <= 256) {
        feat_dim = out.h;  num_anchors = out.w; channels_first = true;
    } else if (out.w > 0 && out.w <= 256) {
        feat_dim = out.w;  num_anchors = out.h; channels_first = false;
    } else {
        LOGE("无法识别的输出形状 w=%d h=%d c=%d", out.w, out.h, out.c);
        return nullptr;
    }
    const int num_class = feat_dim - 4;
    if (num_class <= 0 || num_anchors <= 0) {
        LOGE("输出特征维异常: feat_dim=%d anchors=%d", feat_dim, num_anchors);
        return nullptr;
    }

    auto feat_at = [&](int anchor, int c) -> float {
        return channels_first ? out.channel(c)[anchor] : out.row(anchor)[c];
    };

    std::vector<float> boxes;                   // x1,y1,x2,y2,cls,score
    boxes.reserve(256 * 6);
    for (int i = 0; i < num_anchors; ++i) {
        const float cx = feat_at(i, 0);
        const float cy = feat_at(i, 1);
        const float bw = feat_at(i, 2);
        const float bh = feat_at(i, 3);

        float best = 0.f;
        int best_c = -1;
        for (int c = 0; c < num_class; ++c) {
            const float sc = feat_at(i, 4 + c);
            if (sc > best) { best = sc; best_c = c; }
        }
        if (best_c < 0 || best < conf) continue;

        float x1 = (cx - bw * 0.5f - dx) / scale;
        float y1 = (cy - bh * 0.5f - dy) / scale;
        float x2 = (cx + bw * 0.5f - dx) / scale;
        float y2 = (cy + bh * 0.5f - dy) / scale;

        x1 = clampf(x1, 0.f, (float) img_w);
        y1 = clampf(y1, 0.f, (float) img_h);
        x2 = clampf(x2, 0.f, (float) img_w);
        y2 = clampf(y2, 0.f, (float) img_h);
        if (x2 <= x1 || y2 <= y1) continue;     // 退化框直接丢

        boxes.push_back(x1); boxes.push_back(y1);
        boxes.push_back(x2); boxes.push_back(y2);
        boxes.push_back((float) best_c);
        boxes.push_back(best);
    }

    // ---- NMS ----
    int n = (int) boxes.size() / 6;
    std::vector<int> idx(n);
    for (int i = 0; i < n; ++i) idx[i] = i;
    std::sort(idx.begin(), idx.end(), [&](int a, int b) {
        return boxes[a * 6 + 5] > boxes[b * 6 + 5];
    });

    // ★ 截断：只对分数最高的 kMaxCandidates 个做 O(n²) 比较
    if (n > kMaxCandidates) {
        LOGW("候选框 %d 个，截断到 %d 个再做 NMS", n, kMaxCandidates);
        n = kMaxCandidates;
    }

    const int max_out = (maxDetections > 0) ? (int) maxDetections : 100;
    std::vector<char> removed(n, 0);
    std::vector<float> final_boxes;
    final_boxes.reserve(64 * 6);
    for (int i = 0; i < n; ++i) {
        const int mi = idx[i];
        if (removed[mi]) continue;
        const float* a = &boxes[(size_t) mi * 6];
        final_boxes.insert(final_boxes.end(), a, a + 6);
        if ((int) (final_boxes.size() / 6) >= max_out) break;   // 上限，防结果爆炸
        for (int j = i + 1; j < n; ++j) {
            const int mj = idx[j];
            if (removed[mj]) continue;
            const float* b = &boxes[(size_t) mj * 6];
            if ((int) a[4] != (int) b[4]) continue;
            if (iou(a, b) > iou_thres) removed[mj] = 1;
        }
    }

    const int out_n = (int) final_boxes.size();
    jfloatArray result = env->NewFloatArray(out_n);
    if (result == nullptr) return nullptr;      // OOM：交给 Kotlin 侧按空结果处理
    if (out_n > 0) {
        env->SetFloatArrayRegion(result, 0, out_n, final_boxes.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nekonyan_assistant_core_yolo_NcnnDetector_nativeRelease(JNIEnv*, jobject, jlong handle) {
    auto* d = reinterpret_cast<Detector*>(handle);
    if (d == nullptr) return;
    // 调用方（NcnnDetector 的 synchronized 锁）保证此刻没有在跑的 detect
    d->net.clear();
    delete d;
}
