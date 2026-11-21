package com.modelforge.bench.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Everything a commercial report must pin down so a number can be reproduced
 * on the same kind of device. Collected without any privileged permission.
 */
@Serializable
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val soc: String,
    val android: String,
    val sdk: Int,
    val abi: String,
    val cores: Int,
    val totalRamMb: Long,
    val appVersion: String
) {
    fun summary(): String = "$manufacturer $model · Android $android · $cores cores · ${totalRamMb / 1024} GB RAM"

    companion object {
        fun collect(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOfNotNull(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                    .joinToString(" ")
                    .ifBlank { Build.HARDWARE }
            } else {
                Build.HARDWARE
            }
            return DeviceProfile(
                manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                model = Build.MODEL,
                device = Build.DEVICE,
                soc = soc,
                android = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                cores = Runtime.getRuntime().availableProcessors(),
                totalRamMb = if (mi.totalMem > 0) mi.totalMem / (1024 * 1024) else 0L,
                appVersion = appVersion(context)
            )
        }

        private fun appVersion(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("unknown")
    }
}

/** Peak resident memory of this process, if the kernel exposes it. */
fun currentRssBytes(): Long = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.times(1024L)
    } ?: 0L
}.getOrDefault(0L)
