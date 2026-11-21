# ModelForge Bench

Commercial-grade, fully offline evaluation for models trained with
[ModelForge](https://github.com/CocoCopi/ModelForge). Bench runs on the same
phone that trained the model, with the same C++ engine, so a score is a
measurement rather than an estimate.

**Package:** `com.modelforge.bench` · **Version:** 1.0.1 · **minSdk 26, target 34**
**License:** Apache-2.0

## What it measures

| Area | Metrics |
| --- | --- |
| Language modelling | Perplexity, bits per byte, bootstrap 95% interval, windows, tokens |
| Multiple choice | Accuracy, Wilson 95% interval, chance level, per-category accuracy, expected calibration error, mean confidence |
| Robustness | Reversed choice order, neutral prefix, accuracy deltas, answer flip rate |
| Contamination | Held-out vs perturbed loss gap, with an explicit interpretation |
| Generation | Distinct-1/2, trigram repetition, empty outputs, invalid UTF-8, raw samples |
| Tokenizer | Vocabulary, merges, characters per token, tokens per word |
| Performance | Load time, TTFT p50/p95, prefill tok/s, decode p50/p95, sustained-load throughput drop, battery temperature |
| Memory | RSS after load, peak RSS, weights size |
| Energy | Joules per token, mWh, average watts, charging state, measured vs modelled |
| Cost | Electricity and amortised hardware cost per million tokens, hosted API reference tiers, quality per dollar |
| Composite | Weighted rubric with published weights, per-part detail and a letter grade |

Every section is isolated: a model without a tokenizer still receives speed,
memory, energy and cost numbers, and a failed stage is listed as a warning
instead of silently discarding the rest of the run.

## Models

* **Direct from ModelForge 2.4.1+** through a read-only `ContentProvider`
  guarded by `com.modelforge.app.permission.READ_MODELS`.
* **Bundle import** (`.mfbench` / `.zip`) containing weights, tokenizer and
  manifest.
* **Single file import** (`.mfck` checkpoint or `.mfpk` packed model).

Imports are staged and atomically renamed into `filesDir/models/<id>/`. The
checkpoint header, parameter count and tokenizer vocabulary are validated
before a model appears in the list, and the SHA-256 of the weights is stored
with every run. Re-training a model in ModelForge therefore cannot rewrite the
history of a score you already published.

## Benchmarks

A 34-item pack authored for Bench is bundled for sanity checks, with balanced
answer positions across five categories. It is explicitly labelled as a smoke
test, not a leaderboard.

Real comparisons need real data. The JSONL importer understands ARC, MMLU,
HellaSwag and PIQA style records (`question`/`goal`/`ctx`, `choices`/`options`/
`endings`, `sol1`/`sol2`, letter or numeric answer keys, optional `category`).
Third-party test items are **not** bundled: you import the files you are
licensed to use, and the provenance string is copied into every report.

## Reports

Each run writes JSON (archival), CSV (spreadsheets), HTML (reading and
printing) and PDF (sign-off). Reports include the model hash, full device
profile, engine version, run mode, seed, item counts, warnings and every raw
metric used by the composite score.

## ModelForge integration

Bench 1.0.1 exposes completed run summaries through the read-only
`com.modelforge.bench.runs` provider, guarded by the dangerous
`com.modelforge.bench.permission.READ_RUNS` permission. ModelForge 2.4.1+ uses
it to show every version in a series next to its composite score, accuracy,
perplexity and decode speed, and to explain which measured weakness shaped the
next version's training budget.

## Privacy

Bench has **no `INTERNET` permission**. Models, benchmarks, runs and reports
stay in app-private storage until you explicitly share a report.

## Building

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug          # arm64-v8a + x86_64
./gradlew assembleRelease        # arm64-v8a, signed, minified
```

Release builds refuse to produce an unsigned artifact. Provide:

```
MF_KEYSTORE_PATH=/path/to.keystore
MF_KEYSTORE_PASSWORD=...
MF_KEY_PASSWORD=...
MF_KEY_ALIAS=modelforge        # optional, defaults to modelforge
```

Native host tests (Linux/macOS, no Android device needed):

```bash
cmake -S app/src/main/cpp -B build-native -DCMAKE_BUILD_TYPE=Release
cmake --build build-native -j
ctest --test-dir build-native --output-on-failure
```

## CI and releases

`.github/workflows/build.yml` runs the native host tests and unit tests,
builds the signed release APK, uploads it as an artifact, and attaches it to a
GitHub release when a `v*` tag is pushed. Set the `MF_KEYSTORE_BASE64`,
`MF_KEYSTORE_PASSWORD` and `MF_KEY_PASSWORD` repository secrets.

## Honest limitations

* Scores describe **this device, this model file, this seed**. They are not
  claims about other hardware or about a vendor's hosted model.
* Energy is read from Android's battery counters where the device exposes
  them; otherwise it is modelled and labelled as modelled.
* The composite score is a transparent weighted rubric, not an industry
  standard. Disagree with a weight and the raw numbers are still usable.
* Small packs produce wide confidence intervals. The report prints them
  instead of hiding them.
