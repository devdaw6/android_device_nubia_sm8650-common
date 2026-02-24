/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

object AutomationScheduler {
    private const val ACTION_ALARM = "org.lineageos.settings.automation.ALARM"
    private const val TAG = "AutomationScheduler"

    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        AutomationStore.getAll(context).forEach { automation ->
            cancel(context, automation.id)
            if (automation.scheduleEnabled) {
                scheduleOne(context, am, automation)
            }
        }
    }

    private fun scheduleOne(context: Context, am: AlarmManager, automation: Automation) {
        val minute = automation.scheduleMinuteOfDay ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pendingIntent(context, automation.id)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            Log.i(TAG, "Scheduled ${automation.id} at ${cal.time}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule alarm", e)
        }
    }

    fun handleAlarm(context: Context, id: String?) {
        if (id == null) return
        val automation = AutomationStore.getAll(context).firstOrNull { it.id == id } ?: return
        AutomationExecutor.execute(context, automation)
        if (automation.scheduleRepeatDaily) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            scheduleOne(context, am, automation)
        }
    }

    private fun cancel(context: Context, id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, id))
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, AutomationSchedulerReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra("id", id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
