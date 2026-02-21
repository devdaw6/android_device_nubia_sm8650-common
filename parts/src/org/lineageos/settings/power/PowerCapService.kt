/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.power

import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString

class PowerCapService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentMode: Mode = Mode.STOCK
    private var running = false
    private var screenOffSinceElapsedMs = 0L

    private val poll = Runnable { runStepAndSchedule() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            // Re-evaluate immediately when display state changes.
            handler.removeCallbacks(poll)
            handler.post { runStepAndSchedule() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        registerScreenReceiver()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-apply immediately when user toggles any power-cap setting.
        handler.removeCallbacks(poll)
        handler.post { runStepAndSchedule() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runStepAndSchedule() {
        var nextPollMs = POLL_STATIC_IDLE_MS
        try {
            nextPollMs = stepAndGetNextDelay()
        } catch (e: Exception) {
            Log.e(TAG, "poll error", e)
        } finally {
            if (running) {
                handler.postDelayed(poll, nextPollMs)
            }
        }
    }

    private fun stepAndGetNextDelay(): Long {
        val enabled = getInt(this, "powercap_enable", 1) == 1
        if (!enabled) {
            if (currentMode != Mode.STOCK) {
                PowerCapController.applyStock()
                currentMode = Mode.STOCK
            }
            stopSelf()
            return POLL_STATIC_IDLE_MS
        }

        val allowFull = getInt(this, "powercap_allow_full", 1) == 1
        val respectSystemModes = getInt(this, "powercap_respect_system_modes", 1) == 1
        val whitelist = getString(this, "powercap_whitelist")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val interactive = isInteractive()
        val hasWhitelistMode = allowFull && whitelist.isNotEmpty()

        if (!interactive) {
            if (screenOffSinceElapsedMs == 0L) {
                screenOffSinceElapsedMs = SystemClock.elapsedRealtime()
            }
            val screenOffElapsed = SystemClock.elapsedRealtime() - screenOffSinceElapsedMs
            if (screenOffElapsed < SCREEN_OFF_DEEP_DELAY_MS) {
                applyMode(Mode.CAPPED_SCREEN_OFF_SOFT)
                val remainToDeep = (SCREEN_OFF_DEEP_DELAY_MS - screenOffElapsed).coerceAtLeast(1_000L)
                val regular = if (hasWhitelistMode) POLL_IDLE_MS else POLL_STATIC_IDLE_MS
                return minOf(regular, remainToDeep)
            }
            applyMode(Mode.CAPPED_SCREEN_OFF_DEEP)
            return if (hasWhitelistMode) POLL_IDLE_MS else POLL_STATIC_IDLE_MS
        }
        screenOffSinceElapsedMs = 0L

        if (respectSystemModes && shouldBypassCapsForSystemMode()) {
            applyMode(Mode.STOCK)
            return POLL_SYSTEM_MODE_MS
        }

        if (!hasWhitelistMode) {
            applyMode(Mode.CAPPED_SCREEN_ON)
            return POLL_STATIC_ACTIVE_MS
        }

        val fg = currentForeground() ?: ""
        val shouldFull = allowFull && whitelist.contains(fg)

        val target = if (shouldFull) Mode.STOCK else Mode.CAPPED_SCREEN_ON
        applyMode(target)

        return POLL_ACTIVE_MS
    }

    private fun currentForeground(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 30_000,
            now
        )
        val recent = stats.maxByOrNull { it.lastTimeUsed }
        return recent?.packageName
    }

    private fun isInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun shouldBypassCapsForSystemMode(): Boolean {
        // Keep caps active while Battery Saver is enabled (expected battery-first behavior).
        if (isPowerSaveMode()) return false
        return isKnownPerformanceModeEnabled()
    }

    private fun isPowerSaveMode(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    private fun isKnownPerformanceModeEnabled(): Boolean {
        val keys = arrayOf(
            "high_performance_mode",
            "performance_mode",
            "power_mode",
            "device_performance_mode",
            "gaming_mode",
            "game_mode",
            "game_mode_enabled",
            "game_space_mode",
            "gamespace_mode",
            "gt_mode"
        )
        return keys.any { readSettingBool(it) }
    }

    private fun readSettingBool(key: String): Boolean {
        val cr = contentResolver
        val values = listOf(
            runCatching { Settings.Global.getString(cr, key) }.getOrNull(),
            runCatching { Settings.System.getString(cr, key) }.getOrNull(),
            runCatching { Settings.Secure.getString(cr, key) }.getOrNull()
        )
        return values.any { value ->
            when (value?.trim()?.lowercase()) {
                "1", "true", "on", "enabled", "performance", "high" -> true
                else -> false
            }
        }
    }

    private fun applyMode(target: Mode) {
        if (target == currentMode) return
        when (target) {
            Mode.CAPPED_SCREEN_ON -> PowerCapController.applyCapScreenOn()
            Mode.CAPPED_SCREEN_OFF_SOFT -> PowerCapController.applyCapScreenOffSoft()
            Mode.CAPPED_SCREEN_OFF_DEEP -> PowerCapController.applyCapScreenOff()
            Mode.STOCK -> PowerCapController.applyStock()
        }
        currentMode = target
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    companion object {
        private const val TAG = "PowerCapService"
        private const val POLL_ACTIVE_MS = 8_000L
        private const val POLL_IDLE_MS = 30_000L
        private const val POLL_SYSTEM_MODE_MS = 5_000L
        private const val POLL_STATIC_ACTIVE_MS = 60_000L
        private const val POLL_STATIC_IDLE_MS = 180_000L
        private const val SCREEN_OFF_DEEP_DELAY_MS = 45_000L

        fun startOrStop(context: Context) {
            val enabled = getInt(context, "powercap_enable", 1) == 1
            val intent = Intent(context, PowerCapService::class.java)
            if (enabled) {
                context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }

    private enum class Mode { CAPPED_SCREEN_ON, CAPPED_SCREEN_OFF_SOFT, CAPPED_SCREEN_OFF_DEEP, STOCK }
}
