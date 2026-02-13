/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

public final class TriggerKeyHandlerImpl implements DeviceKeyHandler {
    private static final String TAG = "TriggerKeyHandlerImpl";

    private static final String SETTINGS_PREFIX = "nubia_parts_";
    private static final String KEY_TRIGGER_ENABLE = "trigger_enable";
    private static final String KEY_TRIGGER_ACTION_GREEN = "trigger_action_green";
    private static final String KEY_TRIGGER_APP = "trigger_app";
    private static final String KEY_TRIGGER_RECORDER = "trigger_recorder_mode";
    private static final String KEY_TRIGGER_FLASHLIGHT = "trigger_flashlight_mode";

    private static final int ACTION_CAMERA = 0;
    private static final int ACTION_VIBRATION = 1;
    private static final int ACTION_APP = 2;

    private static final int SCANCODE_RED = 0x18e;
    private static final int SCANCODE_GREEN = 0x18f;

    private final Context mContext;
    private String mFlashlightCameraId;

    public TriggerKeyHandlerImpl(Context context) {
        mContext = context;
        Log.i(TAG, "Loaded");
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (!isTriggerKey(event)) {
            return event;
        }

        if (!isEnabled()) {
            return event;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return null;
        }

        if (isRecorderMode()) {
            if (isGreenKey(event)) {
                startRecording();
                return null;
            }
            if (isRedKey(event)) {
                stopRecording();
                return null;
            }
        }

        if (isFlashlightMode()) {
            if (isGreenKey(event)) {
                setFlashlight(true);
                return null;
            }
            if (isRedKey(event)) {
                setFlashlight(false);
                return null;
            }
        }

        if (isGreenKey(event)) {
            handleGreen();
            return null;
        }

        if (isRedKey(event)) {
            handleRed();
            return null;
        }

        return event;
    }

    private boolean isEnabled() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                SETTINGS_PREFIX + KEY_TRIGGER_ENABLE,
                0) == 1;
    }

    private int getAction() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                SETTINGS_PREFIX + KEY_TRIGGER_ACTION_GREEN,
                ACTION_CAMERA);
    }

    private String getAppComponent() {
        return Settings.Global.getString(
                mContext.getContentResolver(),
                SETTINGS_PREFIX + KEY_TRIGGER_APP);
    }

    private boolean isRecorderMode() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                SETTINGS_PREFIX + KEY_TRIGGER_RECORDER,
                0) == 1;
    }

    private boolean isFlashlightMode() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                SETTINGS_PREFIX + KEY_TRIGGER_FLASHLIGHT,
                0) == 1;
    }

    private boolean isGreenKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int scanCode = event.getScanCode();
        return scanCode == SCANCODE_GREEN
                || keyCode == KeyEvent.KEYCODE_PROG_GREEN
                || keyCode == KeyEvent.KEYCODE_F14;
    }

    private boolean isRedKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int scanCode = event.getScanCode();
        return scanCode == SCANCODE_RED
                || keyCode == KeyEvent.KEYCODE_PROG_RED
                || keyCode == KeyEvent.KEYCODE_F13;
    }

    private boolean isTriggerKey(KeyEvent event) {
        return isGreenKey(event) || isRedKey(event);
    }

    private void handleGreen() {
        switch (getAction()) {
            case ACTION_CAMERA:
                launchCamera();
                break;
            case ACTION_VIBRATION:
                setVibration(true);
                break;
            case ACTION_APP:
                launchApp();
                break;
            default:
                break;
        }
    }

    private void handleRed() {
        if (getAction() == ACTION_VIBRATION) {
            setVibration(false);
            return;
        }
        goHome();
    }

    private void setVibration(boolean enabled) {
        AudioManager audioManager = mContext.getSystemService(AudioManager.class);
        if (audioManager == null) {
            return;
        }
        audioManager.setRingerMode(enabled
                ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_NORMAL);
    }

    private void startRecording() {
        Intent intent = new Intent();
        intent.setClassName(
                "org.lineageos.settings",
                "org.lineageos.settings.trigger.TriggerRecorderService");
        intent.setAction(TriggerRecorderService.ACTION_START);
        try {
            mContext.startForegroundServiceAsUser(intent, UserHandle.CURRENT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start recorder service", e);
        }
    }

    private void stopRecording() {
        Intent intent = new Intent();
        intent.setClassName(
                "org.lineageos.settings",
                "org.lineageos.settings.trigger.TriggerRecorderService");
        intent.setAction(TriggerRecorderService.ACTION_STOP);
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop recorder service", e);
        }
    }

    private void setFlashlight(boolean enabled) {
        CameraManager cm = mContext.getSystemService(CameraManager.class);
        if (cm == null) return;
        try {
            String cameraId = getFlashlightCameraId(cm);
            if (cameraId != null) {
                cm.setTorchMode(cameraId, enabled);
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Failed to set torch mode", e);
        }
    }

    private String getFlashlightCameraId(CameraManager cm) throws CameraAccessException {
        if (mFlashlightCameraId != null) {
            return mFlashlightCameraId;
        }
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lens = chars.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(hasFlash)
                    && lens != null
                    && lens == CameraCharacteristics.LENS_FACING_BACK) {
                mFlashlightCameraId = id;
                return id;
            }
        }
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(hasFlash)) {
                mFlashlightCameraId = id;
                return id;
            }
        }
        return null;
    }

    private void launchCamera() {
        wakeScreen();
        final KeyguardManager keyguardManager = mContext.getSystemService(KeyguardManager.class);
        final boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();

        Intent intent = new Intent(locked
                ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void launchApp() {
        final String componentString = getAppComponent();
        if (componentString == null || componentString.isEmpty()) {
            return;
        }
        ComponentName component = ComponentName.unflattenFromString(componentString);
        if (component == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(intent);
    }

    private void goHome() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void startActivity(Intent intent) {
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start activity", e);
        }
    }

    private void wakeScreen() {
        PowerManager powerManager = mContext.getSystemService(PowerManager.class);
        if (powerManager == null) {
            return;
        }
        if (!powerManager.isInteractive()) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    TAG);
            wakeLock.acquire(1000);
        }
    }
}
