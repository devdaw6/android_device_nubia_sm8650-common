/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.memory

import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager
import android.app.Service
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log

class RecentsKillService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTaskPackages = HashMap<Int, String>()
    private var homePackageName: String? = null
    private var isListenerRegistered = false

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskRemovalStarted(taskInfo: RunningTaskInfo) {
            val packageName = extractPackageName(taskInfo) ?: return
            if (!shouldKillOnRecentsRemoval(packageName)) return

            synchronized(pendingTaskPackages) {
                pendingTaskPackages[taskInfo.taskId] = packageName
            }
        }

        override fun onTaskRemoved(taskId: Int) {
            val packageName = synchronized(pendingTaskPackages) {
                pendingTaskPackages.remove(taskId)
            } ?: return

            mainHandler.post { killPackageBackground(packageName) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        homePackageName = resolveHomePackage()
        registerTaskStackListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isListenerRegistered) {
            registerTaskStackListener()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (isListenerRegistered) {
            runCatching { ActivityTaskManager.getService().unregisterTaskStackListener(taskStackListener) }
        }
        isListenerRegistered = false
        synchronized(pendingTaskPackages) {
            pendingTaskPackages.clear()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerTaskStackListener() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener)
            isListenerRegistered = true
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to register task stack listener", e)
        }
    }

    private fun extractPackageName(taskInfo: RunningTaskInfo): String? {
        val component = firstNonNullComponent(
            taskInfo.topActivity,
            taskInfo.realActivity,
            taskInfo.baseActivity
        ) ?: return null
        return component.packageName
    }

    private fun firstNonNullComponent(vararg components: ComponentName?): ComponentName? {
        return components.firstOrNull { it != null }
    }

    private fun shouldKillOnRecentsRemoval(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == applicationContext.packageName) return false
        if (pkg == "android") return false
        if (pkg == "com.android.systemui") return false
        if (pkg == homePackageName) return false

        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        return launchIntent != null
    }

    private fun killPackageBackground(packageName: String) {
        val activityManager = getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            Log.w(TAG, "ActivityManager is null, skip package kill for $packageName")
            return
        }
        runCatching {
            activityManager.killBackgroundProcesses(packageName)
            Log.i(TAG, "Killed background process after recents removal: $packageName")
        }.onFailure { e ->
            Log.w(TAG, "Failed to kill background process for $packageName", e)
        }
    }

    private fun resolveHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    companion object {
        private const val TAG = "RecentsKillService"

        fun start(context: Context) {
            context.startService(Intent(context, RecentsKillService::class.java))
        }
    }
}
