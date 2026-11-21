package com.modelforge.bench.report

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.modelforge.bench.core.BenchJson
import com.modelforge.bench.core.Format
import com.modelforge.bench.data.RunStore
import com.modelforge.bench.eval.BenchRun
import com.modelforge.bench.eval.CostResult
import com.modelforge.bench.eval.McResult
import com.modelforge.bench.eval.PerformanceResult
import com.modelforge.bench.eval.QualityResult
import com.modelforge.bench.eval.RunStatus
import com.modelforge.bench.eval.ScorePart
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Four artifacts, one file naming scheme. JSON is the archival record, CSV is
 * for spreadsheets, HTML is for reading and printing, PDF is for attaching to a
 * procurement or sign-off document without a browser.
 */
class ReportWriter(
    private val context: Context,
    private val runStore: RunStore
) {

    data class Files(
        val html: File?,
        val csv: File?,
        val pdf: File?,
        val json: File
    ) {
        val all: List<File> get() = listOfNotNull(html, csv, pdf, json)
    }

    fun writeAll(run: BenchRun): Files {
        val dir = runStore.reportDir(run.id)
        val base = "ModelForge-Bench-" + slug(run.modelName)
        val json = File(dir, "$base.json").apply {
            writeText(BenchJson.encodeToString(run), Charsets.UTF_8)
        }
        val csv = runCatching { File(dir, "$base.csv") }
            .map { file -> writeCsv(run, file); file }
            .getOrNull()
        val html = runCatching { File(dir, "$base.html") }
            .map { file -> writeHtml(run, file); file }
            .getOrNull()
        val pdf = runCatching { File(dir, "$base.pdf") }
            .map { file -> writePdf(run, file); file }
            .getOrNull()
        return Files(html, csv, pdf, json)
    }

    fun shareIntent(files: List<File>): Intent {
        val uris = files.map {
            FileProvider.getUriForFile(context, context.packageName + ".files", it)
        }
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    // -----------------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------------

    private fun writeCsv(run: BenchRun, file: File) {
        val rows = ArrayList<List<String>>()
        fun add(section: String, metric: String, value: Any?, unit: String = "", note: String = "") {
            rows.add(listOf(section, metric, value?.toString() ?: "", unit, note))
        }
        add("Run", "id", run.id)
        add("Run", "model", run.modelName)
        add("Run", "model_sha256", run.modelSha256)
        add("Run", "origin", run.modelOrigin)
        add("Run", "mode", run.mode.name)
        add("Run", "status", run.status.name)
        add("Run", "started", Format.date(run.startedAt))
        add("Run", "duration_ms", run.durationMs)
        add("Run", "seed", run.seed)
        add("Run", "app_version", run.device.appVersion)
        add("Run", "engine", run.engineVersion)
        add("Device", "manufacturer", run.device.manufacturer)
        add("Device", "model", run.device.model)
        add("Device", "soc", run.device.soc)
        add("Device", "android", run.device.android)
        add("Device", "sdk", run.device.sdk)
        add("Device", "abi", run.device.abi)
        add("Device", "cores", run.device.cores)
        add("Device", "ram_mb", run.device.totalRamMb)
        add("Model", "params", run.paramCount)
        add("Model", "weights_kind", run.weightsKind)
        add("Model", "bits", run.bits)
        add("Model", "weights_bytes", run.performance?.weightsBytes)
        add("Model", "d_model", run.config.dModel)
        add("Model", "layers", run.config.layers)
        add("Model", "heads", run.config.heads)
        add("Model", "context", run.config.contextLength)
        add("Model", "vocab", run.config.vocabSize)

        run.quality?.perplexity?.let { p ->
            add("Quality", "perplexity", p.perplexity)
            add("Quality", "perplexity_ci_low", p.ciLow)
            add("Quality", "perplexity_ci_high", p.ciHigh)
            add("Quality", "bits_per_byte", p.bitsPerByte)
            add("Quality", "held_out_tokens", p.tokens)
            add("Quality", "held_out_bytes", p.bytes)
            add("Quality", "windows", p.windows)
            add("Quality", "dataset", p.dataset)
        }
        run.quality?.multipleChoice?.let { mc ->
            add("Quality", "mc_accuracy", mc.accuracy)
            add("Quality", "mc_ci_low", mc.ciLow)
            add("Quality", "mc_ci_high", mc.ciHigh)
            add("Quality", "mc_correct", mc.correct)
            add("Quality", "mc_items", mc.items)
            add("Quality", "mc_chance", mc.chanceAccuracy)
            add("Quality", "mc_ece", mc.ece)
            add("Quality", "mc_mean_confidence", mc.meanConfidence)
            add("Quality", "mc_pack", mc.pack)
            add("Quality", "mc_provenance", mc.provenance)
            mc.categories.forEach { c ->
                add("Category", c.category, c.accuracy, "", "${c.correct}/${c.total}")
            }
        }
        run.quality?.robustness?.let { r ->
            add("Robustness", "base_accuracy", r.baseAccuracy)
            add("Robustness", "reversed_accuracy", r.reversedOrderAccuracy)
            add("Robustness", "reversed_delta", r.reversedDelta)
            add("Robustness", "prefixed_accuracy", r.prefixedAccuracy)
            add("Robustness", "prefixed_delta", r.prefixedDelta)
            add("Robustness", "flip_rate", r.flipRate)
            add("Robustness", "items", r.evaluated)
        }
        run.quality?.contamination?.let { c ->
            add("Contamination", "original_loss", c.originalLoss)
            add("Contamination", "shuffled_loss", c.perturbedLoss)
            add("Contamination", "gap", c.memorizationGap)
            add("Contamination", "method", c.method)
        }
        run.quality?.generation?.let { g ->
            add("Generation", "distinct_1", g.distinct1)
            add("Generation", "distinct_2", g.distinct2)
            add("Generation", "repetition_rate", g.repetitionRate)
            add("Generation", "average_tokens", g.averageTokens)
            add("Generation", "empty_outputs", g.emptyOutputs)
            add("Generation", "invalid_text", g.invalidText)
        }
        run.quality?.tokenizer?.let { t ->
            add("Tokenizer", "vocabulary", t.vocabulary)
            add("Tokenizer", "merges", t.merges)
            add("Tokenizer", "compression", t.compression)
            add("Tokenizer", "tokens_per_word", t.tokensPerWord)
        }
        run.performance?.let { p ->
            add("Performance", "load_ms", p.loadMs)
            add("Performance", "ttft_p50_ms", p.ttftMs.p50)
            add("Performance", "ttft_p95_ms", p.ttftMs.p95)
            add("Performance", "prefill_tokens_per_second", p.prefillTokensPerSecond)
            add("Performance", "decode_p50_tps", p.decode.p50)
            add("Performance", "decode_p95_tps", p.decode.p95)
            add("Performance", "decode_mean_tps", p.decode.mean)
            add("Performance", "rss_after_load_mb", p.rssAfterLoadMb)
            add("Performance", "peak_rss_mb", p.peakRssMb)
            add("Performance", "context_length", p.contextLength)
            p.sustained?.let { s ->
                add("Sustained", "seconds", s.seconds)
                add("Sustained", "early_tps", s.earlyTokensPerSecond)
                add("Sustained", "late_tps", s.lateTokensPerSecond)
                add("Sustained", "drop_percent", s.dropPercent)
                add("Sustained", "battery_start_c", s.startBatteryTempC)
                add("Sustained", "battery_end_c", s.endBatteryTempC)
            }
        }
        run.energy?.let { e ->
            add("Energy", "measured", e.measured)
            add("Energy", "method", e.method)
            add("Energy", "joules", e.joules)
            add("Energy", "tokens", e.tokens)
            add("Energy", "joules_per_token", e.joulesPerToken)
            add("Energy", "milliwatt_hours", e.milliwattHours)
            add("Energy", "average_watts", e.averageWatts)
        }
        run.cost?.let { c ->
            add("Cost", "electricity_per_kwh", c.electricityPerKwh)
            add("Cost", "hardware_price", c.hardwarePrice)
            add("Cost", "lifetime_hours", c.lifetimeHours)
            add("Cost", "energy_cost_per_million", c.energyCostPerMillion)
            add("Cost", "amortised_cost_per_million", c.amortisedCostPerMillion)
            add("Cost", "device_cost_per_million", c.deviceCostPerMillion)
            c.apiRows.forEach { row ->
                add("API", row.name + " input", row.inputPerMillion, "USD/1M")
                add("API", row.name + " output", row.outputPerMillion, "USD/1M")
            }
            add("Cost", "cheapest_api", c.cheapestApiName)
            add("Cost", "cheapest_api_per_million", c.cheapestApiPerMillion)
            add("Cost", "quality_per_dollar", c.qualityPerDollar)
        }
        run.score?.let { s ->
            add("Score", "composite", s.composite)
            add("Score", "grade", s.grade)
            add("Score", "verdict", s.verdict)
            s.parts.forEach { part -> add("ScorePart", part.name, part.score, "/100", part.detail) }
        }
        run.warnings.forEach { add("Warning", "", it) }
        run.error?.let { add("Error", "", it) }

        file.writeText(buildString {
            append(csvRow(listOf("section", "metric", "value", "unit", "note")))
            append('\n')
            for (row in rows) {
                append(csvRow(row))
                append('\n')
            }
        }, Charsets.UTF_8)
    }

    private fun csvRow(values: List<String>): String = values.joinToString(",") { value ->
        val escaped = value.replace("\"", "\"\"")
        if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    // -----------------------------------------------------------------------
    // HTML
    // -----------------------------------------------------------------------

    private fun writeHtml(run: BenchRun, file: File) {
        val score = run.score
        val e: (String) -> String = { ReportWriter.escape(it) }
        val html = buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>ModelForge Bench — ${e(run.modelName)}</title>")
            append("<style>")
            append(
                """
                :root{--bg:#0b0f1a;--card:#141a2b;--card2:#1b2337;--text:#e7ecf7;--muted:#93a0bb;
                --line:#273149;--accent:#7c9cff;--mint:#6be3c1;--amber:#ffc46b;--red:#ff7d7d}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);
                font:15px/1.55 -apple-system,Segoe UI,Roboto,Inter,Arial,sans-serif}
                .wrap{max-width:960px;margin:0 auto;padding:32px 22px 60px}
                header{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-end;justify-content:space-between;
                border-bottom:1px solid var(--line);padding-bottom:22px;margin-bottom:26px}
                .kicker{color:var(--accent);font-size:12px;letter-spacing:.14em;text-transform:uppercase;font-weight:700}
                h1{font-size:30px;margin:6px 0 4px;line-height:1.15}h2{font-size:18px;margin:34px 0 12px}
                h3{font-size:14px;margin:22px 0 8px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em}
                .grade{font-size:48px;font-weight:800;line-height:1}.verdict{color:var(--muted)}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px}
                .tile{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px}
                .tile .v{font-size:20px;font-weight:700;margin-top:2px}.tile .l{font-size:11px;color:var(--muted);
                text-transform:uppercase;letter-spacing:.06em}
                table{width:100%;border-collapse:collapse;background:var(--card);border-radius:12px;overflow:hidden}
                th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);vertical-align:top}
                th{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.06em}
                tr:last-child td{border-bottom:0}td.n{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
                .muted{color:var(--muted)}.small{font-size:12px}
                .bar{height:7px;background:var(--card2);border-radius:99px;overflow:hidden;min-width:90px}
                .bar>span{display:block;height:100%;background:linear-gradient(90deg,var(--accent),var(--mint))}
                .note{background:var(--card2);border-left:3px solid var(--amber);padding:10px 14px;border-radius:6px;
                color:#e9d9b6;margin:8px 0}.warn{border-left-color:var(--red);color:#ffd9d9}
                ul{padding-left:20px}li{margin:4px 0}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
                footer{margin-top:40px;border-top:1px solid var(--line);padding-top:16px;color:var(--muted);font-size:12px}
                @media print{body{background:#fff;color:#111}.card,.tile,table{background:#fff}.muted,.tile .l{color:#555}
                .note{background:#f6f6f6;color:#333}header{border-color:#ddd}th,td{border-color:#ddd}}
                """.trimIndent()
            )
            append("</style></head><body><div class=\"wrap\">")

            // Header
            append("<header><div><div class=\"kicker\">ModelForge Bench · Evaluation report</div>")
            append("<h1>${e(run.modelName)}</h1>")
            append("<div class=\"muted\">${e(Format.params(run.paramCount))} parameters · ")
            append("${e(run.weightsKind)} · ${e(run.device.manufacturer)} ${e(run.device.model)}</div>")
            append("<div class=\"small muted\">${e(Format.date(run.startedAt))} · ${e(run.mode.name.lowercase())} mode · ")
            append("seed ${run.seed} · ${e(Format.duration(run.durationMs))}</div></div>")
            if (score != null) {
                append("<div style=\"text-align:right\"><div class=\"grade\">${e(score.grade)}</div>")
                append("<div class=\"verdict\">${e(score.verdict)} · ${"%.0f".format(Locale.US, score.composite)}/100</div></div>")
            } else {
                append("<div style=\"text-align:right\"><div class=\"verdict\">${e(run.status.name.lowercase())}</div></div>")
            }
            append("</header>")

            if (run.status != RunStatus.COMPLETED) {
                append("<div class=\"note warn\">Run ${e(run.status.name.lowercase())}: ")
                append(e(run.error ?: "no score was produced")).append("</div>")
            }

            // Headline tiles
            val ppl = run.quality?.perplexity
            val mc = run.quality?.multipleChoice
            val perf = run.performance
            append("<div class=\"grid\">")
            tile("Perplexity", ppl?.let { "%.2f".format(Locale.US, it.perplexity) } ?: "—")
            tile("Bits / byte", ppl?.let { "%.3f".format(Locale.US, it.bitsPerByte) } ?: "—")
            tile("MC accuracy", mc?.let { Format.percent(it.accuracy) } ?: "—")
            tile("Decode", perf?.let { "%.1f tok/s".format(Locale.US, it.decode.p50) } ?: "—")
            tile("TTFT p50", perf?.let { Format.ms(it.ttftMs.p50) } ?: "—")
            tile("Energy", run.energy?.let { "%.2f J/tok".format(Locale.US, it.joulesPerToken) } ?: "—")
            tile("Device cost", run.cost?.let { Format.money(it.deviceCostPerMillion) + " / 1M" } ?: "—")
            append("</div>")

            // Score
            if (score != null) {
                append("<h2>Composite score</h2>")
                append("<table><tr><th>Section</th><th>Score</th><th style=\"width:34%\">0–100</th><th>Detail</th></tr>")
                for (part in score.parts) {
                    append("<tr><td>${e(part.name)}</td><td class=\"n\">${"%.0f".format(Locale.US, part.score)}</td>")
                    append("<td><div class=\"bar\"><span style=\"width:${part.score.coerceIn(0.0, 100.0)}%\"></span></div></td>")
                    append("<td class=\"small muted\">${e(part.detail)}</td></tr>")
                }
                append("</table>")
                append("<p class=\"small muted\">${e(score.detail)}</p>")
            }

            // Quality
            run.quality?.let { q ->
                append("<h2>Quality</h2>")
                append("<table>")
                q.perplexity?.let { p ->
                    row("Perplexity", "%.2f (95% CI %.2f–%.2f)".format(Locale.US, p.perplexity, p.ciLow, p.ciHigh))
                    row("Bits per byte", "%.3f".format(Locale.US, p.bitsPerByte))
                    row("Held-out text", "${p.tokens} tokens · ${p.windows} windows · ${Format.bytes(p.bytes)}")
                    row("Dataset", p.dataset)
                }
                q.multipleChoice?.let { m ->
                    row("MC accuracy", "${Format.percent(m.accuracy, 1)} (95% CI ${Format.percent(m.ciLow)}–${Format.percent(m.ciHigh)})")
                    row("Correct", "${m.correct} / ${m.items} (chance ${Format.percent(m.chanceAccuracy)})")
                    row("Calibration", "ECE %.3f · mean confidence %s".format(Locale.US, m.ece, Format.percent(m.meanConfidence)))
                    row("Pack", "${m.pack} — ${m.provenance}")
                    row("Scoring", m.scoring)
                }
                val categories = q.multipleChoice?.categories.orEmpty()
                if (categories.isNotEmpty()) {
                    append("</table><h3>By category</h3><table>")
                    append("<tr><th>Category</th><th>Correct</th><th>Accuracy</th><th>95% CI</th></tr>")
                    for (c in categories) {
                        append("<tr><td>${e(c.category)}</td><td class=\"n\">${c.correct}/${c.total}</td>")
                        append("<td class=\"n\">${Format.percent(c.accuracy)}</td>")
                        append("<td class=\"n\">${Format.percent(c.ciLow)}–${Format.percent(c.ciHigh)}</td></tr>")
                    }
                }
                append("</table>")
                q.robustness?.let { r ->
                    append("<h3>Robustness</h3><table>")
                    row("Reversed choice order", "${Format.percent(r.reversedOrderAccuracy)} (${pp(r.reversedDelta)})")
                    row("Harmless prefix", "${Format.percent(r.prefixedAccuracy)} (${pp(r.prefixedDelta)})")
                    row("Prediction flips", Format.percent(r.flipRate))
                    row("Items", r.evaluated.toString())
                    append("</table><p class=\"small muted\">${e(r.notes)}</p>")
                }
                q.contamination?.let { c ->
                    append("<h3>Memorisation check</h3><table>")
                    row("Held-out loss", "%.4f nats".format(Locale.US, c.originalLoss))
                    row("Shuffled loss", "%.4f nats".format(Locale.US, c.perturbedLoss))
                    row("Gap", "%.4f nats".format(Locale.US, c.memorizationGap))
                    row("Method", c.method)
                    append("</table><p class=\"small muted\">${e(c.interpretation)}</p>")
                }
                q.generation?.let { g ->
                    append("<h3>Generation sanity</h3><table>")
                    row("Distinct-1 / Distrinct-2", "%.2f / %.2f".format(Locale.US, g.distinct1, g.distinct2))
                    row("Repetition", Format.percent(g.repetitionRate))
                    row("Average tokens", "%.1f".format(Locale.US, g.averageTokens))
                    row("Empty / invalid", "${g.emptyOutputs} / ${g.invalidText}")
                    append("</table>")
                    if (g.samples.isNotEmpty()) {
                        append("<h3>Samples (greedy)</h3>")
                        for (sample in g.samples) {
                            append("<div class=\"tile\" style=\"margin:8px 0\">")
                            append("<div class=\"small muted\">Prompt</div><div>${e(sample.prompt)}</div>")
                            append("<div class=\"small muted\" style=\"margin-top:8px\">Output · ${sample.tokens} tokens · ${e(sample.stopReason)}</div>")
                            append("<div class=\"mono\">${e(sample.output.take(600))}</div></div>")
                        }
                    }
                }
                q.tokenizer?.let { t ->
                    append("<h3>Tokenizer</h3><table>")
                    row("Vocabulary", "${t.vocabulary} tokens · ${t.merges} merges")
                    row("Compression", Format.percent(t.compression))
                    row("Tokens per word", "%.2f".format(Locale.US, t.tokensPerWord))
                    append("</table><p class=\"small muted\">${e(t.note)}</p>")
                }
                if (q.warnings.isNotEmpty()) {
                    append("<h3>Quality warnings</h3>")
                    for (warning in q.warnings.distinct()) append("<div class=\"note\">${e(warning)}</div>")
                }
            }

            // Performance
            perf?.let { p ->
                append("<h2>Performance</h2><table>")
                row("Load time", Format.ms(p.loadMs))
                row("Time to first token", "p50 ${Format.ms(p.ttftMs.p50)} · p95 ${Format.ms(p.ttftMs.p95)}")
                row("Prefill", "%.1f tok/s".format(Locale.US, p.prefillTokensPerSecond))
                row("Decode", "p50 %.1f · p95 %.1f · mean %.1f tok/s".format(Locale.US, p.decode.p50, p.decode.p95, p.decode.mean))
                row("Memory after load", "%.0f MB".format(Locale.US, p.rssAfterLoadMb))
                row("Peak memory", "%.0f MB".format(Locale.US, p.peakRssMb))
                row("Context / prompt", "${p.contextLength} / ${p.promptTokens} tokens")
                append("</table>")
                p.sustained?.let { s ->
                    append("<h3>Sustained load</h3><table>")
                    row("Duration", "%.0f s".format(Locale.US, s.seconds))
                    row("Early / late throughput", "%.1f / %.1f tok/s".format(Locale.US, s.earlyTokensPerSecond, s.lateTokensPerSecond))
                    row("Drop", "%.1f%%".format(Locale.US, s.dropPercent))
                    if (s.startBatteryTempC != null) {
                        row("Battery temperature", "%.1f °C → %s".format(Locale.US, s.startBatteryTempC, s.endBatteryTempC?.let { "%.1f °C".format(Locale.US, it) } ?: "—"))
                    }
                    append("</table>")
                }
            }

            // Energy
            run.energy?.let { en ->
                append("<h2>Energy</h2><table>")
                row("Source", en.method)
                row("Energy", "%.1f J · %.2f mWh".format(Locale.US, en.joules, en.milliwattHours))
                row("Per token", "%.3f J".format(Locale.US, en.joulesPerToken))
                row("Average power", "%.2f W".format(Locale.US, en.averageWatts))
                append("</table><p class=\"small muted\">${e(en.note)}</p>")
                if (!en.measured) {
                    append("<div class=\"note\">This is an estimate. Energy figures from this device ")
                    append("should not be used for billing or a regulatory claim.</div>")
                }
            }

            // Cost
            run.cost?.let { c -> appendCostHtml(c) }

            if (run.warnings.isNotEmpty()) {
                append("<h2>Warnings</h2>")
                for (warning in run.warnings.distinct()) append("<div class=\"note\">${e(warning)}</div>")
            }

            // Methodology
            append("<h2>Method and limitations</h2>")
            append("<p class=\"small\">Perplexity is teacher-forced cross-entropy on held-out text, ")
            append("reported as exponential loss and as bits per byte so tokenizers can be compared. ")
            append("The confidence interval is a bootstrap over held-out windows. Multiple-choice ")
            append("accuracy scores each choice by mean log-likelihood per continuation token and ")
            append("reports a Wilson interval. Calibration is expected calibration error over ten ")
            append("confidence bins. Robustness repeats the suite with reversed choices and a harmless ")
            append("prompt prefix. The memorisation check compares held-out loss with a seeded token ")
            append("shuffle; it is a signal, not proof of contamination. Throughput is the median of ")
            append("repeated greedy decodes after a warm-up, with p95 shown so thermal stalls are visible.</p>")
            append("<p class=\"small\">Bench has no network permission. Models and text never leave the ")
            append("device. Every run records the model SHA-256, device, seed and engine version so it ")
            append("can be repeated. The composite score is a weighted rubric (quality 45, calibration 5, ")
            append("robustness 5, generation 5, efficiency 15, memory 10, cost 15), renormalised when a ")
            append("section is unavailable. It is not affiliated with any public benchmark.</p>")

            append("<footer>Generated by ModelForge Bench ${e(run.device.appVersion)} · engine ")
            append(e(run.engineVersion)).append(" · run ").append(e(run.id)).append("<br>")
            append("Apache-2.0 · Model SHA-256 <span class=\"mono\">").append(e(run.modelSha256)).append("</span></footer>")
            append("</div></body></html>")
        }
        file.writeText(html, Charsets.UTF_8)
    }

    private fun StringBuilder.appendCostHtml(c: CostResult) {
        val e: (String) -> String = { ReportWriter.escape(it) }
        append("<h2>Cost</h2><table>")
        append("<tr><th>Scenario</th><th>Per 1M tokens</th><th>Assumption</th></tr>")
        append("<tr><td>On-device energy</td><td class=\"n\">${Format.money(c.energyCostPerMillion)}</td>")
        append("<td>${"%.2f".format(Locale.US, c.electricityPerKwh)} per kWh</td></tr>")
        append("<tr><td>Hardware amortisation</td><td class=\"n\">${Format.money(c.amortisedCostPerMillion)}</td>")
        append("<td>${Format.money(c.hardwarePrice)} over ${c.lifetimeHours.toInt()} hours</td></tr>")
        append("<tr><td><strong>Device total</strong></td><td class=\"n\"><strong>${Format.money(c.deviceCostPerMillion)}</strong></td>")
        append("<td>Energy + hardware</td></tr>")
        for (row in c.apiRows) {
            val blended = row.blendedPerMillion(1.0 - com.modelforge.bench.eval.CostModel.OUTPUT_SHARE)
            append("<tr><td>${e(row.name)}</td><td class=\"n\">${Format.money(blended)}</td>")
            append("<td>25% input ${Format.money(row.inputPerMillion)} · 75% output ${Format.money(row.outputPerMillion)}</td></tr>")
        }
        append("</table>")
        c.cheapestApiName?.let {
            append("<p class=\"small\">Cheapest API tier in the table: <strong>${e(it)}</strong> at ")
            append("${Format.money(c.cheapestApiPerMillion ?: 0.0)} per 1M blended tokens. ")
            append("A local token avoids that charge but carries the device cost above.</p>")
        }
        c.qualityPerDollar?.let {
            append("<div class=\"tile\" style=\"margin-top:10px\"><div class=\"l\">Quality points per device dollar per 1M tokens</div>")
            append("<div class=\"v\">${"%.0f".format(Locale.US, it)}</div></div>")
        }
        append("<p class=\"small muted\">${e(c.note)}</p>")
    }

    private fun StringBuilder.row(label: String, value: String) {
        append("<tr><td class=\"muted\">").append(ReportWriter.escape(label)).append("</td><td>")
            .append(ReportWriter.escape(value)).append("</td></tr>")
    }

    private fun StringBuilder.tile(label: String, value: String) {
        append("<div class=\"tile\"><div class=\"l\">").append(ReportWriter.escape(label))
            .append("</div><div class=\"v\">").append(ReportWriter.escape(value)).append("</div></div>")
    }

    private fun pp(value: Double): String =
        if (!value.isFinite()) "—" else (if (value >= 0) "+" else "") + "%.1f pp".format(Locale.US, value * 100.0)

    // -----------------------------------------------------------------------
    // PDF
    // -----------------------------------------------------------------------

    private fun writePdf(run: BenchRun, file: File) {
        val document = PdfDocument()
        val layout = Layout(document)
        layout.newPage()

        layout.text("MODELFORGE BENCH", 9f, bold = true, color = ACCENT)
        layout.text("Evaluation report", 22f, bold = true, leading = 30f)
        layout.text(run.modelName, 18f, bold = true, leading = 26f)
        layout.text(
            "${Format.params(run.paramCount)} params · ${run.weightsKind} · ${run.mode.name.lowercase()} mode",
            10f, color = MUTED, leading = 16f
        )
        layout.text(
            "${run.device.manufacturer} ${run.device.model} · Android ${run.device.android} · " +
                "${run.device.cores} cores · seed ${run.seed}",
            10f, color = MUTED, leading = 16f
        )
        layout.text(
            "${Format.date(run.startedAt)} · ${Format.duration(run.durationMs)} · ${run.status.name.lowercase()}",
            10f, color = MUTED, leading = 16f
        )
        layout.gap(10f)

        run.score?.let { score ->
            layout.text("${score.grade}  ${score.verdict}", 18f, bold = true, color = MINT, leading = 26f)
            layout.text(
                "Composite ${"%.0f".format(Locale.US, score.composite)}/100 · ${score.detail}",
                9f, color = MUTED, leading = 14f
            )
            layout.gap(6f)
            for (part in score.parts) {
                layout.scoreRow(part)
            }
        }

        run.quality?.let { q ->
            layout.section("Quality")
            q.perplexity?.let { p ->
                layout.row("Perplexity", "%.2f  (95%% CI %.2f–%.2f)".format(Locale.US, p.perplexity, p.ciLow, p.ciHigh))
                layout.row("Bits per byte", "%.3f".format(Locale.US, p.bitsPerByte))
                layout.row("Held-out text", "${p.tokens} tokens · ${p.windows} windows")
                layout.row("Dataset", p.dataset)
            }
            q.multipleChoice?.let { m ->
                layout.row("MC accuracy", "${Format.percent(m.accuracy)}  (${m.correct}/${m.items}, chance ${Format.percent(m.chanceAccuracy)})")
                layout.row("95% interval", "${Format.percent(m.ciLow)} – ${Format.percent(m.ciHigh)}")
                layout.row("Calibration", "ECE %.3f · mean confidence %s".format(Locale.US, m.ece, Format.percent(m.meanConfidence)))
                layout.row("Pack", m.pack)
                layout.row("Provenance", m.provenance)
                if (m.categories.isNotEmpty()) {
                    layout.text("By category", 10f, bold = true, leading = 16f)
                    for (c in m.categories) {
                        layout.row(c.category, "${c.correct}/${c.total}  ${Format.percent(c.accuracy)}")
                    }
                }
            }
            q.robustness?.let { r ->
                layout.text("Robustness", 10f, bold = true, leading = 16f)
                layout.row("Reversed choices", "${Format.percent(r.reversedOrderAccuracy)} (${pp(r.reversedDelta)})")
                layout.row("Harmless prefix", "${Format.percent(r.prefixedAccuracy)} (${pp(r.prefixedDelta)})")
                layout.row("Prediction flips", Format.percent(r.flipRate))
            }
            q.contamination?.let { c ->
                layout.row("Memorisation gap", "%.3f nats (held-out %.3f, shuffled %.3f)".format(Locale.US, c.memorizationGap, c.originalLoss, c.perturbedLoss))
            }
            q.generation?.let { g ->
                layout.row("Generation", "distinct-1 %.2f · repetition %s · empty %d".format(Locale.US, g.distinct1, Format.percent(g.repetitionRate), g.emptyOutputs))
                g.samples.firstOrNull()?.let { s ->
                    layout.row("Sample prompt", s.prompt.take(90))
                    layout.row("Sample output", s.output.take(220).ifBlank { "(empty)" })
                }
            }
            q.tokenizer?.let { t ->
                layout.row("Tokenizer", "${t.vocabulary} tokens · ${t.merges} merges · %.2f tokens/word".format(Locale.US, t.tokensPerWord))
            }
        }

        run.performance?.let { p ->
            layout.section("Performance")
            layout.row("Load time", Format.ms(p.loadMs))
            layout.row("Time to first token", "p50 ${Format.ms(p.ttftMs.p50)} · p95 ${Format.ms(p.ttftMs.p95)}")
            layout.row("Prefill", "%.1f tok/s".format(Locale.US, p.prefillTokensPerSecond))
            layout.row("Decode", "p50 %.1f · p95 %.1f tok/s".format(Locale.US, p.decode.p50, p.decode.p95))
            layout.row("Memory", "%.0f MB after load · %.0f MB peak".format(Locale.US, p.rssAfterLoadMb, p.peakRssMb))
            p.sustained?.let { s ->
                layout.row("Sustained", "%.0fs · %.1f → %.1f tok/s (%.1f%% drop)".format(Locale.US, s.seconds, s.earlyTokensPerSecond, s.lateTokensPerSecond, s.dropPercent))
                if (s.startBatteryTempC != null) {
                    layout.row("Battery temp", "%.1f °C → %s".format(Locale.US, s.startBatteryTempC, s.endBatteryTempC?.let { "%.1f °C".format(Locale.US, it) } ?: "—"))
                }
            }
        }

        run.energy?.let { en ->
            layout.section("Energy")
            layout.row("Source", en.method)
            layout.row("Energy per token", "%.3f J · %.2f mWh total".format(Locale.US, en.joulesPerToken, en.milliwattHours))
            layout.row("Average power", "%.2f W".format(Locale.US, en.averageWatts))
            if (!en.measured) layout.note("Estimated, not measured. Not suitable for billing.")
        }

        run.cost?.let { c ->
            layout.section("Cost")
            layout.row("Device energy", Format.money(c.energyCostPerMillion) + " per 1M tokens")
            layout.row("Hardware", Format.money(c.amortisedCostPerMillion) + " per 1M tokens")
            layout.row("Device total", Format.money(c.deviceCostPerMillion) + " per 1M tokens")
            c.apiRows.forEach { row ->
                layout.row(
                    row.name,
                    Format.money(row.blendedPerMillion(1.0 - com.modelforge.bench.eval.CostModel.OUTPUT_SHARE)) +
                        " per 1M (25% input / 75% output)"
                )
            }
            c.cheapestApiName?.let {
                layout.row("Cheapest API", "$it at ${Format.money(c.cheapestApiPerMillion ?: 0.0)} per 1M")
            }
        }

        if (run.warnings.isNotEmpty()) {
            layout.section("Warnings")
            for (warning in run.warnings.distinct()) layout.text("• $warning", 8.5f, color = AMBER, leading = 12f)
        }

        layout.section("Method and limitations")
        for (line in METHOD_TEXT) layout.text(line, 8.5f, color = MUTED, leading = 12f)
        layout.gap(4f)
        layout.text("Model SHA-256: ${run.modelSha256}", 7.5f, color = MUTED, leading = 11f)
        layout.text("Run ${run.id} · engine ${run.engineVersion} · Bench ${run.device.appVersion}", 7.5f, color = MUTED, leading = 11f)

        layout.finish()
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }

    private class Layout(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = 0f
        private var pageNumber = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun newPage() {
            finish()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
            val newPage = document.startPage(info)
            page = newPage
            canvas = newPage.canvas
            y = MARGIN
        }

        fun finish() {
            val current = page ?: return
            val c = current.canvas
            paint.reset()
            paint.isAntiAlias = true
            paint.color = MUTED
            paint.textSize = 7.5f
            c.drawText("ModelForge Bench · page $pageNumber", MARGIN, PAGE_HEIGHT - 24f, paint)
            document.finishPage(current)
            page = null
            canvas = null
        }

        private fun ensure(height: Float) {
            if (canvas == null || y + height > PAGE_HEIGHT - MARGIN - 10f) newPage()
        }

        fun text(
            value: String,
            size: Float = 10f,
            bold: Boolean = false,
            color: Int = TEXT,
            leading: Float = size * 1.45f
        ) {
            ensure(leading)
            paint.reset()
            paint.isAntiAlias = true
            paint.color = color
            paint.textSize = size
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            for (line in wrap(value, PAGE_WIDTH - MARGIN * 2)) {
                ensure(leading)
                canvas?.drawText(line, MARGIN, y, paint)
                y += leading
            }
        }

        fun gap(height: Float) {
            ensure(height)
            y += height
        }

        fun section(title: String) {
            gap(10f)
            ensure(26f)
            paint.reset()
            paint.isAntiAlias = true
            paint.color = ACCENT
            paint.textSize = 11f
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas?.drawText(title.uppercase(Locale.US), MARGIN, y, paint)
            y += 14f
            paint.color = LINE
            paint.strokeWidth = 0.8f
            canvas?.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint)
            y += 10f
        }

        fun row(label: String, value: String) {
            ensure(13f)
            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = 8.5f
            paint.color = MUTED
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            val labelWidth = 150f
            canvas?.drawText(label.take(34), MARGIN, y, paint)
            paint.color = TEXT
            val available = PAGE_WIDTH - MARGIN * 2 - labelWidth - 8f
            val lines = wrap(value, available)
            var lineY = y
            for ((index, line) in lines.withIndex()) {
                if (index > 0) {
                    ensure(12f)
                    lineY = y
                }
                canvas?.drawText(line, MARGIN + labelWidth, lineY, paint)
                if (index < lines.lastIndex) y += 12f
            }
            y += 14f
        }

        fun note(value: String) {
            text(value, 8.5f, color = AMBER, leading = 12f)
            gap(4f)
        }

        fun scoreRow(part: ScorePart) {
            ensure(14f)
            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = 8.5f
            paint.color = TEXT
            canvas?.drawText(part.name, MARGIN, y, paint)
            paint.color = MUTED
            canvas?.drawText(part.detail.take(70), MARGIN + 130f, y, paint)
            paint.color = ACCENT
            canvas?.drawText("%.0f".format(Locale.US, part.score), PAGE_WIDTH - MARGIN - 24f, y, paint)
            y += 13f
        }

        private fun wrap(value: String, maxWidth: Float): List<String> {
            if (value.isBlank()) return listOf("")
            val words = value.split(' ')
            val lines = ArrayList<String>()
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current.clear()
                    current.append(candidate)
                } else {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            return lines
        }

        companion object {
            const val PAGE_WIDTH = 595f
            const val PAGE_HEIGHT = 842f
            const val MARGIN = 44f
            const val TEXT = 0xFF1B2337.toInt()
            const val MUTED = 0xFF5C6880.toInt()
            const val ACCENT = 0xFF3D5CC4.toInt()
            const val MINT = 0xFF0E7C66.toInt()
            const val AMBER = 0xFF8A5A00.toInt()
            const val LINE = 0xFFD6DCEA.toInt()
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "model" }
        .take(48)

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "html" -> "text/html"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        else -> "application/json"
    }

    companion object {
        private val METHOD_TEXT = listOf(
            "Perplexity is teacher-forced cross-entropy on held-out text, reported as exponential loss and as bits per byte so tokenizers can be compared. The interval is a bootstrap over windows.",
            "Multiple-choice accuracy scores each choice by mean log-likelihood per continuation token, with a Wilson 95% interval. Calibration is expected calibration error over ten bins.",
            "Robustness repeats the suite with reversed choice order and a harmless prompt prefix. The memorisation check compares held-out loss with a seeded token shuffle; it is a signal, not proof.",
            "Throughput is the median of repeated greedy decodes after a warm-up; p95 exposes thermal stalls. Energy is measured from the battery interface when available and otherwise estimated, and is labelled accordingly.",
            "Bench has no network permission. Every run records model SHA-256, device, seed and engine version so it can be reproduced. The composite score is a transparent weighted rubric, not a public benchmark.",
            "Apache-2.0. See the repository for the full method and source."
        )

        val ACCENT = 0xFF7C9CFF.toInt()
        val MUTED = 0xFF93A0BB.toInt()
        val TEXT = 0xFFE7ECF7.toInt()
        val LINE = 0xFF273149.toInt()
        val MINT = 0xFF6BE3C1.toInt()
        val AMBER = 0xFFFFC46B.toInt()

        fun escape(value: String): String = buildString(value.length + 16) {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }
}
