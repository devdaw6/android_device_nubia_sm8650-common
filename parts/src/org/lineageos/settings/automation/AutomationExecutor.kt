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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private const val PARTS_PACKAGE = "org.lineageos.settings"

    fun executeForBinding(context: Context, binding: Int) {
        val automation = AutomationStore.getForBinding(context, binding) ?: return
        execute(context, automation)
    }

    fun execute(context: Context, automation: Automation) {
        val execContext = resolveExecutionContext(context)
        when (automation.action) {
            ACTION_APP -> launchApp(execContext, automation.appComponent)
            ACTION_CAMERA -> launchCamera(execContext)
            ACTION_RECORDER -> startRecording(execContext)
            ACTION_POWER_CAP_TOGGLE -> togglePowerCap(execContext)
            ACTION_OPEN_URL -> openUrl(execContext, automation.url)
            ACTION_COPY_TEXT -> copyText(execContext, automation.clipboardText)
            ACTION_TOGGLE_DND -> toggleDnd(execContext)
            ACTION_TOGGLE_AUTO_BRIGHTNESS -> toggleAutoBrightness(execContext)
            ACTION_TOGGLE_WIFI -> toggleWifi(execContext)
            ACTION_MEDIA_PLAY_PAUSE -> mediaPlayPause(execContext)
            ACTION_SEND_MESSAGE -> sendMessage(execContext, automation)
            ACTION_BROADCAST -> sendBroadcast(execContext, automation)
            ACTION_FORCE_STOP -> forceStop(execContext, automation)
            else -> Log.w(TAG, "Unknown action ${automation.action}")
        }
    }

    private fun openUrl(
        context: Context,
        url: String?,
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
    }

    fun pinUrlShortcut(context: Context, automation: Automation): Boolean {
        if (automation.action != ACTION_OPEN_URL || !automation.pinShortcut) return false
        val url = automation.url?.takeIf { it.isNotBlank() } ?: return false
        val execContext = resolveExecutionContext(context)
        val shortcutManager = execContext.getSystemService(ShortcutManager::class.java) ?: return false
        if (!shortcutManager.isRequestPinShortcutSupported) return false
        return try {
            val label = automation.shortcutLabel?.takeIf { it.isNotBlank() }
                ?: automation.name.ifBlank { url }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val builder = ShortcutInfo.Builder(execContext, "automation_${automation.id}")
                .setShortLabel(label)
                .setIntent(intent)
            buildIcon(execContext, automation.shortcutIconUri)
                ?.let { builder.setIcon(it) }
            val shortcut = builder.build()
            runCatching { shortcutManager.updateShortcuts(listOf(shortcut)) }
            shortcutManager.requestPinShortcut(shortcut, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pin shortcut", e)
            false
        }
    }

    private fun resolveExecutionContext(context: Context): Context {
        if (context.packageName == PARTS_PACKAGE) {
            return context
        }
        return try {
            context.createPackageContextAsUser(
                PARTS_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
                UserHandle.CURRENT
            )
        } catch (_: Exception) {
            try {
                context.createPackageContext(PARTS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            } catch (_: Exception) {
                context
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

    private fun buildIcon(context: Context, iconUri: String?): Icon? {
        if (iconUri.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(iconUri) }.getOrNull() ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val decoded = BitmapFactory.decodeStream(input, null, options) ?: return null
                val raw = ensureSoftwareBitmap(decoded)
                if (raw !== decoded) {
                    decoded.recycle()
                }
                val sm = context.getSystemService(ShortcutManager::class.java)
                val targetSize = minOf(
                    sm?.iconMaxWidth ?: raw.width,
                    sm?.iconMaxHeight ?: raw.height
                ).coerceAtLeast(1)
                val processed = cropAndScaleToSquare(raw, targetSize)
                if (processed !== raw) {
                    raw.recycle()
                }
                val output = ensureSoftwareBitmap(processed)
                if (output !== processed) {
                    processed.recycle()
                }
                Icon.createWithAdaptiveBitmap(output)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build custom icon from uri", e)
            null
        }
    }

    private fun cropAndScaleToSquare(source: Bitmap, size: Int): Bitmap {
        val background = estimateBackgroundColor(source)
        val trimmed = trimBackgroundBorder(source, background)
        val boosted = cropToContent(trimmed, background)
        if (boosted !== trimmed && trimmed !== source) {
            trimmed.recycle()
        }

        val side = minOf(boosted.width, boosted.height)
        val left = (boosted.width - side) / 2
        val top = (boosted.height - side) / 2
        val square = if (boosted.width == boosted.height) {
            boosted
        } else {
            Bitmap.createBitmap(boosted, left, top, side, side)
        }
        if (square !== boosted && boosted !== source) {
            boosted.recycle()
        }

        if (square.width == size && square.height == size) {
            return square
        }
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        if (scaled !== square && square !== source) {
            square.recycle()
        }
        return scaled
    }

    private fun trimBackgroundBorder(source: Bitmap, background: Int): Bitmap {
        if (source.width < 4 || source.height < 4) return source
        val marginLimitX = (source.width * 0.45f).toInt().coerceAtLeast(1)
        val marginLimitY = (source.height * 0.45f).toInt().coerceAtLeast(1)

        var left = 0
        while (left < marginLimitX && columnLooksLikeBorder(source, left, background)) {
            left++
        }
        var right = source.width - 1
        while ((source.width - 1 - right) < marginLimitX &&
            right > left &&
            columnLooksLikeBorder(source, right, background)
        ) {
            right--
        }
        var top = 0
        while (top < marginLimitY && rowLooksLikeBorder(source, top, background)) {
            top++
        }
        var bottom = source.height - 1
        while ((source.height - 1 - bottom) < marginLimitY &&
            bottom > top &&
            rowLooksLikeBorder(source, bottom, background)
        ) {
            bottom--
        }

        val newWidth = right - left + 1
        val newHeight = bottom - top + 1
        if (newWidth <= 0 || newHeight <= 0 ||
            (newWidth == source.width && newHeight == source.height)
        ) {
            return source
        }
        return Bitmap.createBitmap(source, left, top, newWidth, newHeight)
    }

    private fun cropToContent(source: Bitmap, background: Int): Bitmap {
        val bounds = findContentBounds(source, background) ?: return source
        val contentWidth = bounds.right - bounds.left + 1
        val contentHeight = bounds.bottom - bounds.top + 1
        val fillRatio = maxOf(
            contentWidth.toFloat() / source.width.toFloat(),
            contentHeight.toFloat() / source.height.toFloat()
        )
        if (fillRatio >= 0.90f) return source

        val padding = (minOf(contentWidth, contentHeight) * 0.12f).toInt().coerceAtLeast(4)
        val left = (bounds.left - padding).coerceAtLeast(0)
        val top = (bounds.top - padding).coerceAtLeast(0)
        val right = (bounds.right + padding).coerceAtMost(source.width - 1)
        val bottom = (bounds.bottom + padding).coerceAtMost(source.height - 1)
        val newWidth = right - left + 1
        val newHeight = bottom - top + 1
        if (newWidth <= 0 || newHeight <= 0 ||
            (newWidth == source.width && newHeight == source.height)
        ) {
            return source
        }
        return Bitmap.createBitmap(source, left, top, newWidth, newHeight)
    }

    private fun findContentBounds(bitmap: Bitmap, background: Int): Bounds? {
        val stepX = (bitmap.width / 96).coerceAtLeast(1)
        val stepY = (bitmap.height / 96).coerceAtLeast(1)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (isContentPixel(pixel, background)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                x += stepX
            }
            y += stepY
        }

        if (right < left || bottom < top) return null
        return Bounds(left, top, right, bottom)
    }

    private fun rowLooksLikeBorder(bitmap: Bitmap, row: Int, background: Int): Boolean {
        val step = (bitmap.width / 64).coerceAtLeast(1)
        var samples = 0
        var backgroundCount = 0
        var x = 0
        while (x < bitmap.width) {
            if (isBackgroundPixel(bitmap.getPixel(x, row), background)) {
                backgroundCount++
            }
            samples++
            x += step
        }
        return backgroundCount * 100 >= samples * 96
    }

    private fun columnLooksLikeBorder(bitmap: Bitmap, col: Int, background: Int): Boolean {
        val step = (bitmap.height / 64).coerceAtLeast(1)
        var samples = 0
        var backgroundCount = 0
        var y = 0
        while (y < bitmap.height) {
            if (isBackgroundPixel(bitmap.getPixel(col, y), background)) {
                backgroundCount++
            }
            samples++
            y += step
        }
        return backgroundCount * 100 >= samples * 96
    }

    private fun isBackgroundPixel(pixel: Int, background: Int): Boolean {
        return android.graphics.Color.alpha(pixel) <= 18 || isSimilarColor(pixel, background, 28)
    }

    private fun isContentPixel(pixel: Int, background: Int): Boolean {
        return android.graphics.Color.alpha(pixel) > 32 && !isSimilarColor(pixel, background, 24)
    }

    private fun estimateBackgroundColor(bitmap: Bitmap): Int {
        val tl = bitmap.getPixel(0, 0)
        val tr = bitmap.getPixel(bitmap.width - 1, 0)
        val bl = bitmap.getPixel(0, bitmap.height - 1)
        val br = bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
        val a = (android.graphics.Color.alpha(tl) +
            android.graphics.Color.alpha(tr) +
            android.graphics.Color.alpha(bl) +
            android.graphics.Color.alpha(br)) / 4
        val r = (android.graphics.Color.red(tl) +
            android.graphics.Color.red(tr) +
            android.graphics.Color.red(bl) +
            android.graphics.Color.red(br)) / 4
        val g = (android.graphics.Color.green(tl) +
            android.graphics.Color.green(tr) +
            android.graphics.Color.green(bl) +
            android.graphics.Color.green(br)) / 4
        val b = (android.graphics.Color.blue(tl) +
            android.graphics.Color.blue(tr) +
            android.graphics.Color.blue(bl) +
            android.graphics.Color.blue(br)) / 4
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun isSimilarColor(a: Int, b: Int, tolerance: Int = 24): Boolean {
        val da = kotlin.math.abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b))
        val dr = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
        val dg = kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
        val db = kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
        return da <= tolerance && dr <= tolerance && dg <= tolerance && db <= tolerance
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) {
            return bitmap
        }
        return try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

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
