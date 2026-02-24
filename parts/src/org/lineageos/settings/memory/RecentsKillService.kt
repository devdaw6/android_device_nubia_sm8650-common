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
import android.os.UserHandle
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString

class RecentsKillService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTaskPackages = HashMap<Int, String>()
    private val knownTaskPackages = HashMap<Int, String>()
    private val recentlyRemovedTaskPackages = LinkedHashMap<Int, String>()
    private var chinaModeEnabled = false
    private var whitelist: Set<String> = emptySet()
    private var homePackageName: String? = null
    private var isListenerRegistered = false

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            refreshTaskSnapshot(detectRemovals = true)
        }

        override fun onTaskRemovalStarted(taskInfo: RunningTaskInfo) {
            val packageName = extractPackageName(taskInfo) ?: return
            if (!shouldKillOnRecentsRemoval(packageName)) return
            // Try to stop immediately to catch background services that linger
            mainHandler.post { killPackageBackground(packageName) }

            synchronized(pendingTaskPackages) {
                pendingTaskPackages[taskInfo.taskId] = packageName
            }
            synchronized(knownTaskPackages) {
                knownTaskPackages[taskInfo.taskId] = packageName
            }
        }

        override fun onTaskRemoved(taskId: Int) {
            val packageName = synchronized(pendingTaskPackages) {
                pendingTaskPackages.remove(taskId)
            } ?: synchronized(knownTaskPackages) {
                knownTaskPackages.remove(taskId)
            } ?: synchronized(recentlyRemovedTaskPackages) {
                recentlyRemovedTaskPackages.remove(taskId)
            }

            if (packageName == null) {
                return
            }
            if (!shouldKillOnRecentsRemoval(packageName)) {
                return
            }

            mainHandler.post { killPackageBackground(packageName) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        homePackageName = resolveHomePackage()
        loadState()
        refreshTaskSnapshot(detectRemovals = false)
        registerTaskStackListener()
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
        synchronized(knownTaskPackages) {
            knownTaskPackages.clear()
        }
        synchronized(recentlyRemovedTaskPackages) {
            recentlyRemovedTaskPackages.clear()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerTaskStackListener() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener)
            isListenerRegistered = true
        } catch (_: Exception) {
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

        return packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun killPackageBackground(packageName: String) {
        val activityManager = getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            return
        }

        // Tier 1: strongest force-stop path (also while user is stopping)
        try {
            activityManager.forceStopPackageAsUserEvenWhenStopping(
                packageName, UserHandle.myUserId()
            )
            return
        } catch (_: Exception) {
        }
        // Tier 2: ActivityManager.forceStopPackage (current user)
        try {
            activityManager.forceStopPackage(packageName)
            return
        } catch (_: Exception) {
        }
        // Tier 3: direct binder API for force-stop
        try {
            ActivityManager.getService().forceStopPackage(packageName, UserHandle.myUserId())
            return
        } catch (_: Exception) {
        }
        // Tier 4: killUid fallback (kills processes only)
        try {
            val uid = packageManager.getApplicationInfo(packageName, 0).uid
            activityManager.killUid(uid, "china_killer")
        } catch (_: Exception) {
        }
    }

    private fun captureTaskSnapshot(): Map<Int, String> {
        val tasks = runCatching {
            ActivityTaskManager.getInstance().getTasks(
                TASK_SNAPSHOT_LIMIT,
                false,
                true
            )
        }.getOrElse {
            val am = getSystemService(ActivityManager::class.java) ?: return emptyMap()
            runCatching { am.getRunningTasks(TASK_SNAPSHOT_LIMIT) }.getOrDefault(emptyList())
        }
        val snapshot = HashMap<Int, String>(tasks.size)
        tasks.forEach { info ->
            val pkg = extractPackageName(info) ?: return@forEach
            snapshot[info.taskId] = pkg
        }
        return snapshot
    }

    private fun refreshTaskSnapshot(detectRemovals: Boolean) {
        val snapshot = captureTaskSnapshot()
        val removedTasks = synchronized(knownTaskPackages) {
            val old = knownTaskPackages.toMap()
            knownTaskPackages.clear()
            knownTaskPackages.putAll(snapshot)
            if (!detectRemovals) {
                emptyList()
            } else {
                old.entries
                    .filter { !snapshot.containsKey(it.key) }
                    .map { it.key to it.value }
            }
        }
        if (!detectRemovals || removedTasks.isEmpty()) {
            return
        }
        synchronized(recentlyRemovedTaskPackages) {
            snapshot.keys.forEach { taskId ->
                recentlyRemovedTaskPackages.remove(taskId)
            }
            removedTasks.forEach { (taskId, packageName) ->
                recentlyRemovedTaskPackages[taskId] = packageName
            }
            while (recentlyRemovedTaskPackages.size > MAX_RECENTLY_REMOVED_TRACKED) {
                val oldestKey = recentlyRemovedTaskPackages.entries.iterator().next().key
                recentlyRemovedTaskPackages.remove(oldestKey)
            }
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
    }

    companion object {
        private const val TASK_SNAPSHOT_LIMIT = 128
        private const val MAX_RECENTLY_REMOVED_TRACKED = 64

        fun start(context: Context) {
            context.startService(Intent(context, RecentsKillService::class.java))
        }

        fun applyChinaMode(context: Context, enabled: Boolean) {
            val intent = Intent(context, RecentsKillService::class.java)
            if (enabled) {
                context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }

        fun applyWhitelist(context: Context) {
            if (getInt(context, "china_killer_enable", 0) == 1) {
                context.startService(Intent(context, RecentsKillService::class.java))
            }
        }
    }
}
