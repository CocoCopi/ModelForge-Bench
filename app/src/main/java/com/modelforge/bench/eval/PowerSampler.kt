package com.modelforge.bench.eval

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * Samples the battery interface while inference runs.
 *
 * Android does not guarantee any of these counters. [Sample.measured] is true
 * only when a real counter moved; otherwise the caller reports an estimate and
 * says so. Charging is treated as unmeasurable because current is then flowing
 * into the battery and the net figure would understate consumption.
 */
class PowerSampler(private val context: Context) {

    data class Sample(
        val measured: Boolean,
        val joules: Double,
        val milliwattHours: Double,
        val averageWatts: Double,
        val batteryStartC: Double?,
        val batteryEndC: Double?,
        val wasCharging: Boolean,
        val method: String
    )

    private val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    @Volatile private var running = false
    private var worker: Thread? = null

    private var startNanos = 0L
    private var lastNanos = 0L
    private var startCounterNwh = Long.MIN_VALUE
    private var lastCounterNwh = Long.MIN_VALUE
    private var counterJoules = 0.0
    private var currentJoules = 0.0
    private var counterWorks = false
    private var currentWorks = false

    private var voltageV = 4.0
    private var charging = false
    private var chargingAtStart = false
    private var startTemp: Double? = null
    private var lastTemp: Double? = null

    fun start() {
        startNanos = System.nanoTime()
        lastNanos = startNanos
        counterJoules = 0.0
        currentJoules = 0.0
        counterWorks = false
        currentWorks = false
        charging = false
        startTemp = null
        lastTemp = null

        readBatteryState()
        chargingAtStart = charging
        val counter = energyCounter()
        if (counter != null) {
            startCounterNwh = counter
            lastCounterNwh = counter
            counterWorks = true
        }

        running = true
        val thread = Thread({ loop() }, "bench-power-sampler")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
        worker = thread
    }

    fun stop(): Sample {
        running = false
        worker?.let { runCatching { it.join(1500) } }
        worker = null
        sampleOnce(System.nanoTime())
        readBatteryState()

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val startCharging = chargingAtStart
        val measured = !startCharging && ((counterWorks && counterJoules > 0.0) || (currentWorks && currentJoules > 0.0))
        val joules = when {
            !measured -> JoulesEstimator.estimate(elapsed)
            counterWorks && counterJoules > 0.0 -> counterJoules
            else -> currentJoules
        }
        val watts = if (elapsed > 0.0) joules / elapsed else 0.0
        val method = when {
            startCharging -> "estimated (device was charging)"
            !measured -> "estimated (no usable battery counter)"
            counterJoules > 0.0 -> "measured (battery energy counter)"
            else -> "measured (battery current × voltage)"
        }
        return Sample(
            measured = measured,
            joules = joules,
            milliwattHours = joules / 3.6,
            averageWatts = watts,
            batteryStartC = startTemp,
            batteryEndC = lastTemp,
            wasCharging = startCharging,
            method = method
        )
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
            sampleOnce(System.nanoTime())
        }
    }

    private fun sampleOnce(now: Long) {
        readBatteryState()
        val dt = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        if (dt <= 0.0 || dt > 5.0) return

        val counter = energyCounter()
        if (counter != null) {
            if (lastCounterNwh != Long.MIN_VALUE) {
                val deltaNwh = abs(counter - lastCounterNwh)
                // A plausible 5 W phone uses ~1.4 mWh/s ≈ 5 million nWh/s.
                if (deltaNwh in 1..50_000_000L) {
                    counterJoules += deltaNwh * 3.6e-6
                    counterWorks = true
                }
            }
            lastCounterNwh = counter
        }

        val currentUa = currentNow()
        if (currentUa != null) {
            val amps = abs(currentUa) / 1_000_000.0
            currentJoules += amps * voltageV * dt
            currentWorks = true
        }
    }

    private fun readBatteryState() {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) {
            val celsius = temp / 10.0
            if (startTemp == null) startTemp = celsius
            lastTemp = celsius
        }
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        if (voltage > 1000) voltageV = voltage / 1000.0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun energyCounter(): Long? {
        val manager = battery ?: return null
        val value = runCatching {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }.getOrNull() ?: return null
        return if (value > Long.MIN_VALUE / 2) value else null
    }

    private fun currentNow(): Long? {
        val manager = battery ?: return null
        val value = runCatching {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() ?: return null
        return if (value > Long.MIN_VALUE / 2 && value != 0L) value else null
    }

    object JoulesEstimator {
        fun estimate(seconds: Double): Double =
            if (seconds <= 0.0) 0.0 else CostModel.ESTIMATED_WATTS * seconds
    }
}
