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
import android.os.UserHandle
import android.util.Log
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString

class RecentsKillService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTaskPackages = HashMap<Int, String>()
    private var chinaModeEnabled = false
    private var whitelist: Set<String> = emptySet()
    private var homePackageName: String? = null
    private var isListenerRegistered = false

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskRemovalStarted(taskInfo: RunningTaskInfo) {
            val packageName = extractPackageName(taskInfo) ?: return
            if (!shouldKillOnRecentsRemoval(packageName)) return
            Log.i(TAG, "Task removal started for $packageName (taskId=${taskInfo.taskId})")
            // Try to stop immediately to catch background services that linger
            mainHandler.post { killPackageBackground(packageName) }

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
        loadState()
        registerTaskStackListener()
        Log.i(TAG, "RecentsKillService created, chinaMode=$chinaModeEnabled")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadState()
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
        if (!chinaModeEnabled) return false
        if (pkg.isBlank()) return false
        if (pkg == applicationContext.packageName) return false
        if (pkg == "android") return false
        if (pkg == "com.android.systemui") return false
        if (pkg == homePackageName) return false
        if (whitelist.contains(pkg)) return false

        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        val res = launchIntent != null
        if (!res) {
            Log.d(TAG, "Skip $pkg: no launch intent")
        }
        return res
    }

    private fun killPackageBackground(packageName: String) {
        val activityManager = getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            Log.w(TAG, "ActivityManager is null, skip package kill for $packageName")
            return
        }
        runCatching {
            // Tier 1: IActivityManager binder — equivalent to Settings → Force Stop
            try {
                val getSvc = ActivityManager::class.java.getDeclaredMethod("getService")
                getSvc.isAccessible = true
                val amBinder = getSvc.invoke(null)!!
                val forceStop = amBinder.javaClass.getDeclaredMethod(
                    "forceStopPackage", String::class.java, Int::class.javaPrimitiveType
                )
                forceStop.isAccessible = true
                forceStop.invoke(amBinder, packageName, UserHandle.myUserId())
                Log.i(TAG, "Force-stopped via binder: $packageName")
                return
            } catch (e: Exception) {
                Log.w(TAG, "forceStop binder failed, fallback", e)
            }
            // Tier 2: ActivityManager.forceStopPackage (current user)
            try {
                val m = activityManager.javaClass.getDeclaredMethod(
                    "forceStopPackage", String::class.java
                )
                m.isAccessible = true
                m.invoke(activityManager, packageName)
                Log.i(TAG, "Force-stopped via ActivityManager: $packageName")
                return
            } catch (e: Exception) {
                Log.w(TAG, "forceStop via ActivityManager failed, fallback killUid", e)
            }
            // Tier 3: killUid — kills processes but not alarms/services
            val uid = packageManager.getApplicationInfo(packageName, 0).uid
            activityManager.killUid(uid, "china_killer")
            Log.i(TAG, "killUid fallback for $packageName uid=$uid")
        }.onFailure { e ->
            Log.w(TAG, "Failed to kill background process for $packageName", e)
        }
    }

    private fun resolveHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private fun loadState() {
        chinaModeEnabled = getInt(this, "china_killer_enable", 0) == 1
        whitelist = getString(this, "china_killer_whitelist")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        Log.i(TAG, "China mode=$chinaModeEnabled whitelist=${whitelist.size}")
    }

    companion object {
        private const val TAG = "RecentsKillService"

        fun start(context: Context) {
            context.startService(Intent(context, RecentsKillService::class.java))
        }

        fun applyChinaMode(context: Context, enabled: Boolean) {
            val i = Intent(context, RecentsKillService::class.java)
            context.startService(i)
            // state is persisted in Settings.Global; service reloads on startCommand
        }

        fun applyWhitelist(context: Context) {
            context.startService(Intent(context, RecentsKillService::class.java))
        }
    }
}
