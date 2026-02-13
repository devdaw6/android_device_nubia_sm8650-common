/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Toast;

import org.lineageos.settings.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TriggerRecorderService extends Service {
    private static final String TAG = "TriggerRecorderService";
    private static final String CHANNEL_ID = "trigger_recorder";
    private static final String ALERT_CHANNEL_ID = "trigger_recorder_alerts";
    private static final int NOTIFICATION_ID = 4132;
    private static final int ALERT_NOTIFICATION_ID = 4133;
    private static final int PCM_SAMPLE_RATE = 16000;
    private static final int PCM_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int PCM_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public static final String ACTION_START = "org.lineageos.settings.trigger.action.START_RECORDING";
    public static final String ACTION_STOP = "org.lineageos.settings.trigger.action.STOP_RECORDING";

    private MediaRecorder mRecorder;
    private PowerManager.WakeLock mWakeLock;
    private boolean mTriedFallback;
    private boolean mTriedPcmFallback;
    private AudioRecord mAudioRecord;
    private Thread mRecordThread;
    private volatile boolean mIsPcmRecording;
    private OutputTarget mMediaTarget;
    private OutputTarget mPcmTarget;
    private FileChannel mPcmChannel;
    private long mRecordingStartMs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private static final class OutputTarget {
        final Uri uri;
        final ParcelFileDescriptor pfd;

        OutputTarget(Uri uri, ParcelFileDescriptor pfd) {
            this.uri = uri;
            this.pfd = pfd;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startRecording();
        } else if (ACTION_STOP.equals(action)) {
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (mRecorder != null) {
            return;
        }

        mTriedFallback = false;
        mTriedPcmFallback = false;
        startRecordingInternal(false);
    }

    private void startRecordingInternal(boolean fallback) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            final String baseName = "TRIGGER_" + ts;
            final String ext = fallback ? ".3gp" : ".m4a";
            final String mime = fallback ? "audio/3gpp" : "audio/mp4";
            mMediaTarget = createMediaStoreOutput(baseName + ext, mime);
            if (mMediaTarget == null) {
                Log.w(TAG, "MediaStore insert failed, falling back to app-private storage");
                File outFile = createPrivateOutputFile(baseName + ext);
                if (outFile == null) {
                    return;
                }
                Log.i(TAG, "Output file: " + outFile.getAbsolutePath());
                startForegroundRecording();
                logRecordAudioAppOps();
                mRecorder = new MediaRecorder();
                mRecorder.setOnErrorListener((mr, what, extra) -> {
                    Log.e(TAG, "MediaRecorder error what=" + what + " extra=" + extra);
                    handleRecorderError();
                });
                mRecorder.setOnInfoListener((mr, what, extra) ->
                        Log.i(TAG, "MediaRecorder info what=" + what + " extra=" + extra));
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                if (fallback) {
                    mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                    mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                    mRecorder.setAudioSamplingRate(8000);
                    mRecorder.setAudioEncodingBitRate(12200);
                    mTriedFallback = true;
                    Log.w(TAG, "Using AMR_NB fallback");
                } else {
                    mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    mRecorder.setAudioEncodingBitRate(128000);
                    mRecorder.setAudioSamplingRate(44100);
                }
                mRecorder.setOutputFile(outFile.getAbsolutePath());
                mRecorder.prepare();
                mRecorder.start();
                scheduleRecorderHealthCheck();
                acquireWakeLock();
                vibrate(true);
                postStartStopAlert(true);
                showToast(getString(R.string.trigger_recorder_toast_started));
                return;
            }
            Log.i(TAG, "Output uri: " + mMediaTarget.uri);

            startForegroundRecording();
            logRecordAudioAppOps();
            mRecorder = new MediaRecorder();
            mRecorder.setOnErrorListener((mr, what, extra) -> {
                Log.e(TAG, "MediaRecorder error what=" + what + " extra=" + extra);
                handleRecorderError();
            });
            mRecorder.setOnInfoListener((mr, what, extra) ->
                    Log.i(TAG, "MediaRecorder info what=" + what + " extra=" + extra));
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (fallback) {
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mRecorder.setAudioSamplingRate(8000);
                mRecorder.setAudioEncodingBitRate(12200);
                mTriedFallback = true;
                Log.w(TAG, "Using AMR_NB fallback");
            } else {
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mRecorder.setAudioEncodingBitRate(128000);
                mRecorder.setAudioSamplingRate(44100);
            }
            mRecorder.setOutputFile(mMediaTarget.pfd.getFileDescriptor());
            mRecorder.prepare();
            mRecorder.start();
            scheduleRecorderHealthCheck();

            acquireWakeLock();
            vibrate(true);
            postStartStopAlert(true);
            showToast(getString(R.string.trigger_recorder_toast_started));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            cleanupRecorder();
            stopForeground(true);
            if (!mTriedFallback) {
                startRecordingInternal(true);
            } else if (!mTriedPcmFallback) {
                startPcmRecording();
            } else {
                stopSelf();
            }
        }
    }

    private void stopRecording() {
        boolean wasRecording = (mRecorder != null) || mIsPcmRecording;
        if (mRecorder == null) {
            stopPcmRecording();
            vibrate(false);
            if (wasRecording) {
                postStartStopAlert(false);
                showToast(getString(R.string.trigger_recorder_toast_stopped));
            }
            stopSelf();
            return;
        }

        try {
            mRecorder.stop();
        } catch (Exception e) {
            Log.w(TAG, "Stop failed", e);
        }
        cleanupRecorder();
        finishMediaStoreOutput(mMediaTarget);
        closeOutputTarget(mMediaTarget);
        mMediaTarget = null;
        stopPcmRecording();
        vibrate(false);
        if (wasRecording) {
            postStartStopAlert(false);
            showToast(getString(R.string.trigger_recorder_toast_stopped));
        }
        stopForeground(true);
        stopSelf();
    }

    private void cleanupRecorder() {
        if (mRecorder != null) {
            try {
                mRecorder.release();
            } catch (Exception ignored) {
            }
            mRecorder = null;
        }
        releaseWakeLock();
    }

    private void handleRecorderError() {
        cleanupRecorder();
        if (!mTriedFallback) {
            startRecordingInternal(true);
        } else if (!mTriedPcmFallback) {
            startPcmRecording();
        } else {
            stopForeground(true);
            stopSelf();
        }
    }

    private void startPcmRecording() {
        if (mTriedPcmFallback) {
            return;
        }
        mTriedPcmFallback = true;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            final String baseName = "TRIGGER_" + ts;
            mPcmTarget = createMediaStoreOutput(baseName + ".wav", "audio/wav");
            if (mPcmTarget == null) {
                Log.w(TAG, "MediaStore insert failed for PCM");
                return;
            }
            Log.i(TAG, "PCM output uri: " + mPcmTarget.uri);

            startForegroundRecording();
            logRecordAudioAppOps();

            int minBuf = AudioRecord.getMinBufferSize(
                    PCM_SAMPLE_RATE, PCM_CHANNEL_CONFIG, PCM_AUDIO_FORMAT);
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid AudioRecord buffer size");
                return;
            }
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    PCM_SAMPLE_RATE,
                    PCM_CHANNEL_CONFIG,
                    PCM_AUDIO_FORMAT,
                    minBuf * 2);
            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed");
                return;
            }

            mIsPcmRecording = true;
            mPcmChannel = new FileOutputStream(mPcmTarget.pfd.getFileDescriptor()).getChannel();
            writeWavHeader(mPcmChannel, PCM_SAMPLE_RATE, (short) 1, (short) 16, 0);
            mAudioRecord.startRecording();
            mRecordThread = new Thread(() -> recordPcmLoop(minBuf), "TriggerPcmRecorder");
            mRecordThread.start();
            acquireWakeLock();
            vibrate(true);
            postStartStopAlert(true);
            showToast(getString(R.string.trigger_recorder_toast_started));
            Log.w(TAG, "Using PCM WAV fallback");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start PCM recording", e);
            stopPcmRecording();
        }
    }

    private void recordPcmLoop(int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        long totalBytes = 0;
        try {
            while (mIsPcmRecording) {
                int read = mAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    mPcmChannel.write(ByteBuffer.wrap(buffer, 0, read));
                    totalBytes += read;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "PCM write failed", e);
        } finally {
            updateWavHeader(mPcmChannel, PCM_SAMPLE_RATE, (short) 1, (short) 16, totalBytes);
            finishMediaStoreOutput(mPcmTarget);
            closeOutputTarget(mPcmTarget);
            mPcmTarget = null;
        }
    }

    private void stopPcmRecording() {
        mIsPcmRecording = false;
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (Exception ignored) {
            }
            try {
                mAudioRecord.release();
            } catch (Exception ignored) {
            }
            mAudioRecord = null;
        }
        if (mRecordThread != null) {
            try {
                mRecordThread.join(500);
            } catch (InterruptedException ignored) {
            }
            mRecordThread = null;
        }
        if (mPcmChannel != null) {
            try {
                mPcmChannel.close();
            } catch (IOException ignored) {
            }
            mPcmChannel = null;
        }
        releaseWakeLock();
        mRecordingStartMs = 0;
    }

    private static void writeWavHeader(FileChannel channel, int sampleRate, short channels,
                                       short bitsPerSample, long dataLength) throws IOException {
        byte[] header = buildWavHeader(sampleRate, channels, bitsPerSample, dataLength);
        channel.position(0);
        channel.write(ByteBuffer.wrap(header));
    }

    private static void updateWavHeader(FileChannel channel, int sampleRate, short channels,
                                        short bitsPerSample, long dataLength) {
        if (channel == null) return;
        try {
            byte[] header = buildWavHeader(sampleRate, channels, bitsPerSample, dataLength);
            channel.position(0);
            channel.write(ByteBuffer.wrap(header));
        } catch (IOException e) {
            Log.e(TAG, "Failed to update WAV header", e);
        }
    }

    private static byte[] buildWavHeader(int sampleRate, short channels,
                                         short bitsPerSample, long dataLength) {
        byte[] header = new byte[44];
        ByteBuffer buf = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'R', 'I', 'F', 'F'});
        buf.putInt((int) (36 + dataLength));
        buf.put(new byte[]{'W', 'A', 'V', 'E'});
        buf.put(new byte[]{'f', 'm', 't', ' '});
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort(channels);
        buf.putInt(sampleRate);
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        buf.putInt(byteRate);
        short blockAlign = (short) (channels * bitsPerSample / 8);
        buf.putShort(blockAlign);
        buf.putShort(bitsPerSample);
        buf.put(new byte[]{'d', 'a', 't', 'a'});
        buf.putInt((int) dataLength);
        return header;
    }

    private OutputTarget createMediaStoreOutput(String displayName, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/TriggerRecordings");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            if (pfd == null) {
                getContentResolver().delete(uri, null, null);
                return null;
            }
            return new OutputTarget(uri, pfd);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open MediaStore output", e);
            getContentResolver().delete(uri, null, null);
            return null;
        }
    }

    private void finishMediaStoreOutput(OutputTarget target) {
        if (target == null) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        getContentResolver().update(target.uri, values, null, null);
    }

    private void closeOutputTarget(OutputTarget target) {
        if (target == null) return;
        try {
            target.pfd.close();
        } catch (IOException ignored) {
        }
    }

    private File createPrivateOutputFile(String name) {
        File baseDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (baseDir == null) {
            Context dps = createDeviceProtectedStorageContext();
            baseDir = dps.getFilesDir();
        }
        File outDir = new File(baseDir, "TriggerRecordings");
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.w(TAG, "Failed to create output dir");
            return null;
        }
        return new File(outDir, name);
    }

    private Notification buildNotification(boolean recording) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.trigger_recorder_title),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_triggers)
                .setContentTitle(getString(R.string.trigger_recorder_title))
                .setContentText(recording
                        ? getString(R.string.trigger_recorder_notif_recording)
                        : getString(R.string.trigger_recorder_summary))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(recording);
        if (recording) {
            builder.setWhen(mRecordingStartMs);
            builder.setUsesChronometer(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void postStartStopAlert(boolean started) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.trigger_recorder_title),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = new Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_triggers)
                .setContentTitle(getString(R.string.trigger_recorder_title))
                .setContentText(started
                        ? getString(R.string.trigger_recorder_toast_started)
                        : getString(R.string.trigger_recorder_toast_stopped))
                .setAutoCancel(true)
                .setTimeoutAfter(4000)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        nm.notify(ALERT_NOTIFICATION_ID, builder.build());
    }

    private void startForegroundWithType() {
        Notification notification = buildNotification(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startForegroundRecording() {
        if (mRecordingStartMs == 0) {
            mRecordingStartMs = System.currentTimeMillis();
        }
        startForegroundWithType();
    }

    private void logRecordAudioAppOps() {
        AppOpsManager appOps = getSystemService(AppOpsManager.class);
        if (appOps == null) return;
        int mode = appOps.noteOpNoThrow(
                AppOpsManager.OPSTR_RECORD_AUDIO,
                Process.myUid(),
                getPackageName(),
                null,
                "TriggerRecorderService");
        Log.i(TAG, "AppOps RECORD_AUDIO mode=" + mode);
    }

    private void scheduleRecorderHealthCheck() {
        mHandler.postDelayed(() -> {
            if (mRecorder == null) return;
            try {
                mRecorder.getMaxAmplitude();
            } catch (Exception e) {
                Log.w(TAG, "Recorder health check failed, fallback to PCM", e);
                cleanupRecorder();
                startPcmRecording();
            }
        }, 1500);
    }

    private void acquireWakeLock() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        if (mWakeLock == null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.setReferenceCounted(false);
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    private void showToast(String msg) {
        mHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void vibrate(boolean started) {
        Vibrator vibrator = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = getSystemService(VibratorManager.class);
            if (vm != null) {
                vibrator = vm.getDefaultVibrator();
            }
        } else {
            vibrator = getSystemService(Vibrator.class);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (started) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 30, 50, 30}, -1));
            }
        } else {
            if (started) {
                vibrator.vibrate(80);
            } else {
                vibrator.vibrate(new long[]{0, 30, 50, 30}, -1);
            }
        }
    }
}
