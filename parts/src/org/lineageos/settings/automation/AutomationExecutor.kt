/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.wifi.WifiManager
import android.media.session.MediaSessionManager
import android.os.PowerManager
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.lineageos.settings.power.PowerCapService
import org.lineageos.settings.trigger.TriggerRecorderService
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.putInt
import org.lineageos.settings.R

object AutomationExecutor {
    const val ACTION_APP = 0
    const val ACTION_CAMERA = 1
    const val ACTION_RECORDER = 2
    const val ACTION_POWER_CAP_TOGGLE = 3
    const val ACTION_OPEN_URL = 4
    const val ACTION_COPY_TEXT = 5
    const val ACTION_TOGGLE_DND = 6
    const val ACTION_TOGGLE_AUTO_BRIGHTNESS = 7
    const val ACTION_TOGGLE_WIFI = 8
    const val ACTION_MEDIA_PLAY_PAUSE = 9
    const val ACTION_SEND_MESSAGE = 10
    const val ACTION_BROADCAST = 11
    const val ACTION_FORCE_STOP = 12

    private const val TAG = "AutomationExecutor"

    fun executeForBinding(context: Context, binding: Int) {
        val automation = AutomationStore.getForBinding(context, binding) ?: return
        execute(context, automation)
    }

    fun execute(context: Context, automation: Automation) {
        when (automation.action) {
            ACTION_APP -> launchApp(context, automation.appComponent)
            ACTION_CAMERA -> launchCamera(context)
            ACTION_RECORDER -> startRecording(context)
            ACTION_POWER_CAP_TOGGLE -> togglePowerCap(context)
            ACTION_OPEN_URL -> openUrl(
                context,
                automation.url,
                automation.pinShortcut,
                automation.name,
                automation.shortcutLabel,
                automation.shortcutIcon,
                automation.shortcutIconUri
            )
            ACTION_COPY_TEXT -> copyText(context, automation.clipboardText)
            ACTION_TOGGLE_DND -> toggleDnd(context)
            ACTION_TOGGLE_AUTO_BRIGHTNESS -> toggleAutoBrightness(context)
            ACTION_TOGGLE_WIFI -> toggleWifi(context)
            ACTION_MEDIA_PLAY_PAUSE -> mediaPlayPause(context)
            ACTION_SEND_MESSAGE -> sendMessage(context, automation)
            ACTION_BROADCAST -> sendBroadcast(context, automation)
            ACTION_FORCE_STOP -> forceStop(context, automation)
            else -> Log.w(TAG, "Unknown action ${automation.action}")
        }
    }

    private fun openUrl(
        context: Context,
        url: String?,
        pinShortcut: Boolean,
        name: String,
        shortcutLabel: String? = null,
        shortcutIcon: String? = null,
        shortcutIconUri: String? = null,
    ) {
        if (url.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open url", e)
        }
        if (pinShortcut) {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            if (sm.isRequestPinShortcutSupported) {
                val label = shortcutLabel?.takeIf { it.isNotBlank() } ?: name.ifBlank { url }
                val builder = ShortcutInfo.Builder(context, "automation_" + name)
                    .setShortLabel(label)
                    .setIntent(intent)
                buildIcon(context, shortcutIcon, shortcutIconUri)?.let { builder.setIcon(it) }
                val shortcut = builder.build()
                try {
                    sm.updateShortcuts(listOf(shortcut))
                } catch (_: Exception) { }
                sm.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun copyText(context: Context, text: String?) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("automation", text ?: "")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.automation_clip_copied, Toast.LENGTH_SHORT).show()
    }

    private fun toggleDnd(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val current = nm.currentInterruptionFilter
        val next = if (current == NotificationManager.INTERRUPTION_FILTER_NONE)
            NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_NONE
        nm.setInterruptionFilter(next)
    }

    private fun toggleAutoBrightness(context: Context) {
        val cr = context.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val next = if (current == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, next)
    }

    private fun toggleWifi(context: Context) {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        wm.isWifiEnabled = !wm.isWifiEnabled
    }

    private fun mediaPlayPause(context: Context) {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        val controllers = msm.getActiveSessions(null)
        val controller = controllers.firstOrNull() ?: return
        val state = controller.playbackState?.state
        if (state == android.media.session.PlaybackState.STATE_PLAYING ||
            state == android.media.session.PlaybackState.STATE_BUFFERING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    private fun sendMessage(context: Context, automation: Automation) {
        val text = automation.messageText ?: return
        val target = automation.messageTarget
        val intent = if (automation.messageIsSms && !target.isNullOrBlank()) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target"))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                target?.let { setPackage(it) }
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("sms_body", text)
        try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send message intent", e)
        }
    }

    private fun sendBroadcast(context: Context, automation: Automation) {
        val action = automation.broadcastAction ?: return
        val intent = Intent(action).apply {
            automation.broadcastExtraKey?.let { key ->
                putExtra(key, automation.broadcastExtraValue)
            }
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send broadcast", e)
        }
    }

    private fun forceStop(context: Context, automation: Automation) {
        val pkg = automation.appComponent?.let {
            ComponentName.unflattenFromString(it)?.packageName
        } ?: automation.triggerApp ?: return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        try {
            am.forceStopPackage(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "forceStop failed for $pkg", e)
        }
    }

    private fun buildIcon(context: Context, iconKey: String?, iconUri: String?): Icon? {
        iconUri?.let {
            return try { Icon.createWithContentUri(Uri.parse(it)) } catch (_: Exception) { null }
        }
        return when (iconKey) {
            "globe" -> Icon.createWithResource(context, R.drawable.ic_shortcut_globe)
            "star" -> Icon.createWithResource(context, R.drawable.ic_shortcut_star)
            "bolt" -> Icon.createWithResource(context, R.drawable.ic_shortcut_bolt)
            else -> null
        }
    }

    private fun launchApp(context: Context, componentString: String?) {
        if (componentString.isNullOrEmpty()) return
        val component = ComponentName.unflattenFromString(componentString) ?: return
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch app", e)
        }
    }

    private fun launchCamera(context: Context) {
        wakeScreen(context)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val locked = keyguardManager?.isKeyguardLocked == true
        val intent = Intent(
            if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
            else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch camera", e)
        }
    }

    private fun startRecording(context: Context) {
        val intent = Intent(context, TriggerRecorderService::class.java).apply {
            action = TriggerRecorderService.ACTION_START
        }
        try {
            context.startForegroundServiceAsUser(intent, UserHandle.CURRENT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start recorder", e)
        }
    }

    private fun togglePowerCap(context: Context) {
        val enabled = getInt(context, "powercap_enable", 1) == 1
        putInt(context, "powercap_enable", if (enabled) 0 else 1)
        PowerCapService.startOrStop(context)
    }

    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG
            )
            wl.acquire(1000)
        }
    }
}
