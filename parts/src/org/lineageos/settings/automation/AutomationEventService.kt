/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import android.app.Service
import android.app.TaskStackListener
import android.app.ActivityTaskManager
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

class AutomationEventService : Service() {

    private var currentTop: String? = null
    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            super.onTaskStackChanged()
            val top = resolveTopPackage() ?: return
            if (top != currentTop) {
                currentTop?.let { handleAppClose(it) }
                currentTop = top
                handleAppOpen(top)
            }
        }
    }

    private val phoneListener = object : PhoneStateListener() {
        private var wasOffhook = false
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING) {
                wasOffhook = true
            }
            if (state == TelephonyManager.CALL_STATE_IDLE && wasOffhook) {
                wasOffhook = false
                handleCallEnd()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerTaskListener()
        registerCallListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ActivityTaskManager.getService().unregisterTaskStackListener(taskListener) }
        telephony()?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasActiveTriggers()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerTaskListener() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskListener)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to register task listener", e)
        }
    }

    private fun registerCallListener() {
        telephony()?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun telephony(): TelephonyManager? = getSystemService(TelephonyManager::class.java)

    private fun resolveTopPackage(): String? {
        return try {
            val am = getSystemService(ActivityManager::class.java) ?: return null
            val tasks = am.getRunningTasks(1)
            val info = tasks.firstOrNull() ?: return null
            val cn = firstNonNull(info.topActivity, info.baseActivity, info.baseActivity) ?: return null
            cn.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun firstNonNull(vararg comp: ComponentName?): ComponentName? =
        comp.firstOrNull { it != null }

    private fun handleAppOpen(pkg: String) {
        AutomationStore.getAll(this)
            .filter { it.triggerType == Automation.TRIGGER_APP_OPEN && it.triggerApp == pkg }
            .forEach { AutomationExecutor.execute(this, it) }
    }

    private fun handleAppClose(pkg: String) {
        AutomationStore.getAll(this)
            .filter { it.triggerType == Automation.TRIGGER_APP_CLOSE && it.triggerApp == pkg }
            .forEach { AutomationExecutor.execute(this, it) }
    }

    private fun handleCallEnd() {
        AutomationStore.getAll(this)
            .filter { it.triggerType == Automation.TRIGGER_CALL_END }
            .forEach { AutomationExecutor.execute(this, it) }
    }

    private fun hasActiveTriggers(): Boolean {
        return AutomationStore.getAll(this).any { it.triggerType != Automation.TRIGGER_NONE }
    }

    companion object {
        private const val TAG = "AutomationEventService"

        fun startOrStop(context: Context) {
            val intent = Intent(context, AutomationEventService::class.java)
            val has = AutomationStore.getAll(context).any { it.triggerType != Automation.TRIGGER_NONE }
            if (has) context.startService(intent) else context.stopService(intent)
        }
    }
}
