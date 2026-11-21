package com.modelforge.bench.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.modelforge.bench.BenchApp
import com.modelforge.bench.MainActivity
import com.modelforge.bench.R
import com.modelforge.bench.core.DeviceProfile
import com.modelforge.bench.data.ModelRepository
import com.modelforge.bench.data.RunStore
import com.modelforge.bench.data.SettingsStore
import com.modelforge.bench.eval.BenchRun
import com.modelforge.bench.data.BenchmarkStore
import com.modelforge.bench.eval.EvalRunner
import com.modelforge.bench.eval.RunMode
import com.modelforge.bench.eval.RunSettings
import com.modelforge.bench.eval.RunStatus
import com.modelforge.bench.engine.BenchEngine
import com.modelforge.bench.report.ReportWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one evaluation in the foreground so Android does not freeze the process
 * while the screen is off. The native engine is CPU-bound and can legitimately
 * spend minutes in a single stage, which is exactly the case a background
 * service exists for.
 */
class EvalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                EvalBus.progress("Cancelling", EvalBus.state.value.fraction, "Saving what finished so far…")
                job?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID).orEmpty()
                val mode = runCatching {
                    RunMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: RunMode.STANDARD.name)
                }.getOrDefault(RunMode.STANDARD)
                val packId = intent.getStringExtra(EXTRA_PACK_ID)?.takeIf { it.isNotBlank() }
                if (modelId.isBlank()) {
                    EvalBus.failed("No model was selected.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNow("Preparing", 0.0)
                if (job?.isActive == true) {
                    // A new request supersedes the old one rather than silently
                    // doing nothing; the user's last tap must win.
                    job?.cancel()
                }
                job = scope.launch { execute(modelId, mode, packId) }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun execute(modelId: String, mode: RunMode, packId: String?) {
        val repository = ModelRepository(this)
        val runStore = RunStore(this)
        val settingsStore = SettingsStore(this)
        val benchmarkStore = BenchmarkStore(this)
        val reportWriter = ReportWriter(this, runStore)
        val appSettings = settingsStore.load()
        val model = repository.find(modelId)
        if (model == null) {
            EvalBus.failed("That model is no longer installed.")
            finishService()
            return
        }
        EvalBus.start(model.id, model.name)
        acquireWakeLock()
        val settings = RunSettings.forMode(mode, appSettings.seed)
        val pack = packId?.let { id ->
            runCatching { benchmarkStore.load(id) }.getOrNull()
        }
        val runner = EvalRunner(this, repository)
        try {
            val run = runner.run(
                model = model,
                settings = settings,
                mode = mode,
                costSettings = appSettings.cost,
                benchmarkPack = pack,
                onProgress = { progress ->
                    EvalBus.progress(progress.stage, progress.fraction, progress.detail)
                    notifyProgress(progress.stage, progress.fraction, model.name)
                }
            )
            withContext(Dispatchers.IO) { runStore.save(run) }
            withContext(Dispatchers.IO) { runCatching { reportWriter.writeAll(run) } }
            notifyResult(run)
            EvalBus.finished(run.id)
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                val now = System.currentTimeMillis()
                val cancelled = BenchRun(
                    id = "run-$now-cancelled",
                    modelId = model.id,
                    modelName = model.name,
                    modelSha256 = model.sha256,
                    modelOrigin = model.origin.name,
                    config = model.config,
                    paramCount = model.paramCount,
                    weightsKind = model.displayKind,
                    bits = model.bits,
                    device = DeviceProfile.collect(this@EvalService),
                    mode = mode,
                    seed = settings.seed,
                    settings = settings,
                    startedAt = EvalBus.state.value.startedAt.takeIf { it > 0L } ?: now,
                    finishedAt = now,
                    durationMs = (now - (EvalBus.state.value.startedAt.takeIf { it > 0L } ?: now))
                        .coerceAtLeast(0L),
                    status = RunStatus.CANCELLED,
                    engineVersion = BenchEngine.engineVersion,
                    error = "Cancelled by the user.",
                    warnings = listOf("This run was cancelled; no scores were produced.")
                )
                withContext(Dispatchers.IO) { runCatching { runStore.save(cancelled) } }
                EvalBus.finished(cancelled.id)
            }
            throw c
        } catch (t: Throwable) {
            EvalBus.failed(t.message ?: t::class.java.simpleName)
        } finally {
            releaseWakeLock()
            finishService()
        }
    }

    private fun startForegroundNow(stage: String, fraction: Double) {
        val notification = buildNotification(stage, fraction, null, false)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notifyProgress(stage: String, fraction: Double, modelName: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 900L && fraction < 1.0) return
        lastNotifyAt = now
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(stage, fraction, modelName, false))
        }
    }

    private fun notifyResult(run: BenchRun) {
        val text = if (run.status == RunStatus.COMPLETED) {
            "${run.modelName} · ${run.headline}"
        } else {
            "${run.modelName} · ${run.status.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
        val notification = NotificationCompat.Builder(this, BenchApp.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_bench)
            .setContentTitle("Benchmark finished")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(RESULT_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        stage: String,
        fraction: Double,
        modelName: String?,
        finished: Boolean
    ): Notification {
        val title = if (modelName.isNullOrBlank()) "ModelForge Bench" else modelName
        val text = if (finished) "Finished" else "$stage · ${(fraction * 100).toInt()}%"
        val builder = NotificationCompat.Builder(this, BenchApp.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_bench)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
        if (!finished) {
            val cancel = PendingIntent.getService(
                this,
                REQUEST_CANCEL,
                Intent(this, EvalService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Cancel", cancel)
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ModelForgeBench:eval"
        ).apply {
            setReferenceCounted(false)
            acquire(6L * 60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.modelforge.bench.action.START_EVAL"
        private const val ACTION_CANCEL = "com.modelforge.bench.action.CANCEL_EVAL"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PACK_ID = "pack_id"
        private const val NOTIFICATION_ID = 4101
        private const val RESULT_NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN = 1
        private const val REQUEST_CANCEL = 2

        fun start(context: Context, modelId: String, mode: RunMode, packId: String? = null) {
            val intent = Intent(context, EvalService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_PACK_ID, packId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, EvalService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
