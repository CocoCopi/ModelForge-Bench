#include "core/gpt.h"

#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

using namespace mf;

namespace {

double mean_ce(const Tensor& logits, const std::vector<int>& targets) {
    double total = 0.0;
    for (int r = 0; r < logits.rows; ++r) {
        const float* row = logits.row(r);
        float mx = row[0];
        for (int i = 1; i < logits.cols; ++i) mx = std::max(mx, row[i]);
        double sum = 0.0;
        for (int i = 0; i < logits.cols; ++i) sum += std::exp(row[i] - mx);
        const double logz = std::log(sum) + mx;
        total += logz - row[targets[r]];
    }
    return total / std::max(1, logits.rows);
}

int failures = 0;

void check(bool condition, const char* what, double value = 0, double limit = 0) {
    if (!condition) {
        std::printf("FAIL %s (%.6f vs %.6f)\n", what, value, limit);
        ++failures;
    } else {
        std::printf("ok   %s\n", what);
    }
}

}  // namespace

int main() {
    ModelConfig cfg;
    cfg.vocab = 32;
    cfg.context = 16;
    cfg.d_model = 16;
    cfg.layers = 1;
    cfg.heads = 2;
    cfg.d_ff = 32;
    cfg.tied_embeddings = true;

    GPT model;
    if (!model.init(cfg, 1234)) {
        std::printf("FAIL init\n");
        return 1;
    }

    const int B = 1;
    const int T = 8;
    std::mt19937 rng(7);
    std::vector<int> tokens(B * T), targets(B * T);
    for (int i = 0; i < B * T; ++i) {
        tokens[i] = static_cast<int>(rng() % cfg.vocab);
        targets[i] = static_cast<int>(rng() % cfg.vocab);
    }

    const float loss = model.forward(tokens.data(), targets.data(), B, T);
    const double manual = mean_ce(model.cache().logits, targets);
    check(std::abs(loss - manual) < 1e-4, "forward loss matches manual cross entropy",
          loss, manual);

    // Checkpoint round trip must be bit-exact for weights-only files.
    const std::string weights = "/tmp/mfbench-weights.mfck";
    check(model.save_checkpoint(weights, false), "save weights-only checkpoint");
    GPT restored;
    check(restored.load_checkpoint(weights, false), "load weights-only checkpoint");
    const float restoredLoss = restored.forward(tokens.data(), targets.data(), B, T);
    check(std::abs(restoredLoss - loss) < 1e-6, "checkpoint round trip loss is identical",
          restoredLoss, loss);

    // A packed file is lossy, but must stay close and must load.
    const std::string packedPath = "/tmp/mfbench-q4.mfpk";
    check(model.save_packed(packedPath, 4, 32), "save 4-bit packed model");
    PackedModel packed;
    check(packed.load(packedPath), "load 4-bit packed model");
    packed.forward(tokens.data(), B, T);
    const double packedLoss = mean_ce(packed.logits(), targets);
    check(packedLoss < loss * 1.35 + 0.15, "packed loss stays close to fp32",
          packedLoss, loss);
    check(packed.config().vocab == cfg.vocab && packed.config().layers == cfg.layers,
          "packed config is preserved");

    // Greedy generation must be deterministic for a fixed seed.
    const std::vector<int> prompt = {1, 5, 9, 2};
    const std::vector<int> a = model.generate(prompt, 8, 0.0f, 1, 1.0f, 42);
    const std::vector<int> b = model.generate(prompt, 8, 0.0f, 1, 1.0f, 42);
    check(a == b, "greedy generation is deterministic");
    check(a.size() == 8, "generation honours max_new", a.size(), 8);

    // A sequence at the context limit must stay inside the model window.
    std::vector<int> full(cfg.context, 3);
    model.forward(full.data(), nullptr, 1, cfg.context);
    check(model.cache().logits.rows == cfg.context, "context-length forward succeeds",
          model.cache().logits.rows, cfg.context);

    std::remove(weights.c_str());
    std::remove(packedPath.c_str());
    if (failures == 0) {
        std::printf("all native evaluation checks passed\n");
        return 0;
    }
    std::printf("%d native evaluation checks failed\n", failures);
    return 1;
}
