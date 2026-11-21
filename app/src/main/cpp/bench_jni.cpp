// JNI surface for ModelForge Bench.
//
// Everything here is inference-only: Bench never trains. A handle wraps either
// a fully-precise checkpoint (GPT) or a packed deployment file (PackedModel).
// The wrapper adds the primitives a benchmark harness needs and the core engine
// does not expose directly: teacher-forced continuation likelihood, per-token
// log-probabilities, a stop flag, and a timed decode loop.

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "core/gpt.h"

namespace {

using mf::GPT;
using mf::ModelConfig;
using mf::PackedModel;
using mf::Tensor;

struct Handle {
    std::mutex mu;
    std::unique_ptr<GPT> gpt;
    std::unique_ptr<PackedModel> packed;
    std::string path;
    std::string error;
    std::atomic<bool> abort{false};
    std::atomic<bool> busy{false};

    bool is_packed() const { return packed != nullptr; }
    const ModelConfig& config() const {
        return packed ? packed->config() : gpt->config();
    }
    int vocab() const { return config().vocab; }
    int context() const { return config().context; }

    // One forward pass; returns the logits for a single sequence.
    const Tensor* forward_tokens(const std::vector<int>& ids) {
        if (ids.empty()) return nullptr;
        const int T = static_cast<int>(ids.size());
        if (T > context()) return nullptr;
        if (packed) {
            packed->forward(ids.data(), 1, T);
            return &packed->logits();
        }
        gpt->forward(ids.data(), nullptr, 1, T);
        return &gpt->cache().logits;
    }
};

Handle* from(jlong h) { return reinterpret_cast<Handle*>(h); }

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::vector<int> to_int_vector(JNIEnv* env, jintArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::vector<int> out(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(arr, 0, n, out.data());
    return out;
}

void set_error(Handle* h, const std::string& message) {
    if (h) h->error = message;
}

// log-softmax over one row. Returns log p(target).
float log_prob_of(const Tensor& logits, int row, int target) {
    if (row < 0 || row >= logits.rows || target < 0 || target >= logits.cols) {
        return -std::numeric_limits<float>::infinity();
    }
    const float* r = logits.row(row);
    const int V = logits.cols;
    float mx = r[0];
    for (int i = 1; i < V; ++i) mx = std::max(mx, r[i]);
    double sum = 0.0;
    for (int i = 0; i < V; ++i) sum += std::exp(static_cast<double>(r[i] - mx));
    const double logz = std::log(sum) + static_cast<double>(mx);
    return static_cast<float>(static_cast<double>(r[target]) - logz);
}

// Deterministic top-k / top-p sampling, matching the core generate() contract.
int sample_from(const float* logits, int V, float temperature, int top_k,
                float top_p, std::mt19937& rng) {
    if (V <= 0) return 0;
    if (!(temperature > 0.0f) || top_k == 1) {
        int best = 0;
        for (int i = 1; i < V; ++i) {
            if (logits[i] > logits[best]) best = i;
        }
        return best;
    }
    std::vector<int> idx(static_cast<size_t>(V));
    for (int i = 0; i < V; ++i) idx[i] = i;
    const float inv = 1.0f / temperature;
    std::vector<float> scaled(static_cast<size_t>(V));
    for (int i = 0; i < V; ++i) scaled[i] = logits[i] * inv;
    const int k = (top_k > 0) ? std::min(top_k, V) : V;
    std::partial_sort(idx.begin(), idx.begin() + k, idx.end(),
                      [&](int a, int b) { return scaled[a] > scaled[b]; });
    idx.resize(static_cast<size_t>(k));

    const float mx = scaled[idx[0]];
    double total = 0.0;
    for (int i : idx) total += std::exp(static_cast<double>(scaled[i] - mx));
    if (!(total > 0.0) || !std::isfinite(total)) {
        return idx[0];
    }
    const float p = (top_p > 0.0f && top_p < 1.0f) ? top_p : 1.0f;
    std::uniform_real_distribution<double> dist(0.0, 1.0);
    const double target = dist(rng) * total;
    double acc = 0.0;
    int fallback = idx[0];
    for (int i : idx) {
        acc += std::exp(static_cast<double>(scaled[i] - mx));
        fallback = i;
        if (acc >= target) return i;
        // Nucleus cut-off: stop once the cumulative mass exceeds p.
        if (acc / total >= p) return i;
    }
    return fallback;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("modelforge-bench-engine/1.0");
}

JNIEXPORT jlong JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeLoadCheckpoint(
    JNIEnv* env, jobject, jstring path, jboolean withOptimizer) {
    auto* h = new Handle();
    h->path = jstring_to_utf8(env, path);
    h->gpt = std::make_unique<GPT>();
    if (!h->gpt->load_checkpoint(h->path, withOptimizer == JNI_TRUE)) {
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jlong JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeLoadPacked(
    JNIEnv* env, jobject, jstring path) {
    auto* h = new Handle();
    h->path = jstring_to_utf8(env, path);
    h->packed = std::make_unique<PackedModel>();
    if (!h->packed->load(h->path)) {
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete from(handle);
}

JNIEXPORT jintArray JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeConfig(JNIEnv* env, jobject, jlong handle) {
    Handle* h = from(handle);
    if (!h) return nullptr;
    std::lock_guard<std::mutex> lock(h->mu);
    const ModelConfig& c = h->config();
    jint values[9] = {c.vocab, c.context, c.d_model, c.layers,
                      c.heads, c.d_ff, c.tied_embeddings ? 1 : 0,
                      h->is_packed() ? h->packed->bits() : 0,
                      h->is_packed() ? h->packed->group() : 0};
    jintArray out = env->NewIntArray(9);
    if (out) env->SetIntArrayRegion(out, 0, 9, values);
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeParamCount(JNIEnv*, jobject, jlong handle) {
    Handle* h = from(handle);
    if (!h) return 0;
    std::lock_guard<std::mutex> lock(h->mu);
    return static_cast<jlong>(h->config().param_count());
}

JNIEXPORT jfloat JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeLoss(
    JNIEnv* env, jobject, jlong handle, jintArray tokens, jintArray targets,
    jint batch, jint seq) {
    Handle* h = from(handle);
    if (!h) return -1.0f;
    std::lock_guard<std::mutex> lock(h->mu);
    const std::vector<int> tok = to_int_vector(env, tokens);
    const std::vector<int> tgt = to_int_vector(env, targets);
    if (tok.empty() || tok.size() != tgt.size()) {
        set_error(h, "tokens and targets must be non-empty and the same size");
        return -1.0f;
    }
    const int n = static_cast<int>(tok.size());
    if (batch <= 0 || seq <= 0 || n != batch * seq || seq > h->context()) {
        set_error(h, "invalid batch/sequence shape");
        return -1.0f;
    }
    for (int i = 0; i < n; ++i) {
        if (tok[i] < 0 || tok[i] >= h->vocab() || tgt[i] < 0 || tgt[i] >= h->vocab()) {
            set_error(h, "token id out of range");
            return -1.0f;
        }
    }
    if (!h->is_packed()) {
        return h->gpt->forward(tok.data(), tgt.data(), batch, seq);
    }
    // PackedModel has no loss helper: forward and compute the mean of -log p.
    h->packed->forward(tok.data(), batch, seq);
    const Tensor& logits = h->packed->logits();
    double total = 0.0;
    for (int i = 0; i < n; ++i) {
        total -= log_prob_of(logits, i, tgt[i]);
    }
    return static_cast<jfloat>(total / static_cast<double>(n));
}

JNIEXPORT jfloat JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeContinueLogProb(
    JNIEnv* env, jobject, jlong handle, jintArray prefix, jintArray continuation) {
    Handle* h = from(handle);
    if (!h) return -1.0f;
    std::lock_guard<std::mutex> lock(h->mu);
    std::vector<int> ids = to_int_vector(env, prefix);
    const std::vector<int> cont = to_int_vector(env, continuation);
    if (cont.empty() || ids.empty()) {
        set_error(h, "prefix and continuation must be non-empty");
        return -1.0f;
    }
    const int start = static_cast<int>(ids.size());
    ids.insert(ids.end(), cont.begin(), cont.end());
    if (ids.size() > static_cast<size_t>(h->context())) {
        set_error(h, "sequence longer than the model context");
        return -1.0f;
    }
    const Tensor* logits = h->forward_tokens(ids);
    if (!logits) {
        set_error(h, "forward failed");
        return -1.0f;
    }
    double total = 0.0;
    for (size_t i = 0; i < cont.size(); ++i) {
        total += log_prob_of(*logits, start + static_cast<int>(i) - 1, cont[i]);
    }
    return static_cast<jfloat>(total);
}

JNIEXPORT jfloatArray JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeTokenLogProbs(
    JNIEnv* env, jobject, jlong handle, jintArray prefix, jintArray continuation) {
    Handle* h = from(handle);
    if (!h) return nullptr;
    std::lock_guard<std::mutex> lock(h->mu);
    std::vector<int> ids = to_int_vector(env, prefix);
    const std::vector<int> cont = to_int_vector(env, continuation);
    if (cont.empty() || ids.empty()) return nullptr;
    const int start = static_cast<int>(ids.size());
    ids.insert(ids.end(), cont.begin(), cont.end());
    if (ids.size() > static_cast<size_t>(h->context())) return nullptr;
    const Tensor* logits = h->forward_tokens(ids);
    if (!logits) return nullptr;
    std::vector<float> out(cont.size());
    for (size_t i = 0; i < cont.size(); ++i) {
        out[i] = log_prob_of(*logits, start + static_cast<int>(i) - 1, cont[i]);
    }
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (arr) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeAbort(JNIEnv*, jobject, jlong handle) {
    Handle* h = from(handle);
    if (!h) return JNI_FALSE;
    h->abort.store(true);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeClearAbort(JNIEnv*, jobject, jlong handle) {
    Handle* h = from(handle);
    if (!h) return JNI_FALSE;
    h->abort.store(false);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeLastError(JNIEnv* env, jobject, jlong handle) {
    Handle* h = from(handle);
    if (!h || h->error.empty()) return nullptr;
    return env->NewStringUTF(h->error.c_str());
}

// Decode `maxNew` tokens and report [prefillNanos, decodeNanos].
// The generated ids are returned separately so the caller can show the text.
JNIEXPORT jintArray JNICALL
Java_com_modelforge_bench_engine_NativeBridge_nativeGenerateTimed(
    JNIEnv* env, jobject, jlong handle, jintArray prompt, jint maxNew,
    jfloat temperature, jint topK, jfloat topP, jlong seed, jlongArray timingOut) {
    Handle* h = from(handle);
    if (!h) return nullptr;
    std::lock_guard<std::mutex> lock(h->mu);
    std::vector<int> ids = to_int_vector(env, prompt);
    if (ids.empty()) {
        set_error(h, "prompt is empty");
        return nullptr;
    }
    const int ctx = h->context();
    if (static_cast<int>(ids.size()) > ctx) ids.resize(static_cast<size_t>(ctx));
    const int room = ctx - static_cast<int>(ids.size());
    const int n = std::max(0, std::min(static_cast<int>(maxNew), room));
    h->abort.store(false);

    std::mt19937 rng(static_cast<uint32_t>(seed));
    auto clock = [] { return std::chrono::steady_clock::now(); };
    auto ns = [](std::chrono::steady_clock::time_point a,
                 std::chrono::steady_clock::time_point b) {
        return static_cast<jlong>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(b - a).count());
    };
    std::vector<int> out;
    out.reserve(static_cast<size_t>(n));

    const auto t0 = clock();
    const Tensor* logits = h->forward_tokens(ids);
    const auto t1 = clock();
    if (!logits) {
        set_error(h, "prefill failed");
        return nullptr;
    }
    jlong prefill = ns(t0, t1);
    jlong decode = 0;

    for (int i = 0; i < n; ++i) {
        if (h->abort.load(std::memory_order_relaxed)) break;
        const Tensor& cur = *logits;
        const float* row = cur.row(cur.rows - 1);
        const auto s0 = clock();
        const int tok = sample_from(row, cur.cols, temperature, topK, topP, rng);
        const auto s1 = clock();
        out.push_back(tok);
        ids.push_back(tok);
        logits = h->forward_tokens(ids);
        const auto s2 = clock();
        if (!logits) break;
        decode += ns(s0, s2);
        (void)s1;
    }

    if (timingOut) {
        jlong timing[2] = {prefill, decode};
        env->SetLongArrayRegion(timingOut, 0, 2, timing);
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (arr && !out.empty()) {
        std::vector<jint> tmp(out.begin(), out.end());
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(tmp.size()), tmp.data());
    }
    return arr;
}

}  // extern "C"
