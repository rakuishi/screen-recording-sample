package com.rakuishi.screenrecordingsample

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.daasuu.mp4compose.FillMode
import com.daasuu.mp4compose.composer.Mp4Composer
import java.io.File

class ScreenRecordingService : Service() {

    companion object {

        const val ACTION_MP4_SAVED = "action_mp4_saved"
        const val KEY_PATH = "key_path"

        private const val TAG = "ScreenRecordingService"
        private const val NOTIFICATION_ID = 1234
        private const val NOTIFICATION_CHANNEL_ID = "NotificationChannelId"
        private const val USE_MP4_COMPOSER = true

        private const val KEY_START_SCREEN_RECORDING = "key_start_screen_recording"
        private const val KEY_STOP_SCREEN_RECORDING = "key_stop_screen_recording"
        private const val KEY_RESULT_CODE = "key_result_code"
        private const val KEY_SCREEN_CAPTURE_INTENT = "key_screen_capture_intent"

        fun startScreenRecording(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, ScreenRecordingService::class.java)
            intent.putExtra(KEY_START_SCREEN_RECORDING, true)
            intent.putExtra(KEY_RESULT_CODE, resultCode)
            intent.putExtra(KEY_SCREEN_CAPTURE_INTENT, data)
            startForegroundService(context, intent)
        }

        fun stopScreenRecording(context: Context) {
            val intent = Intent(context, ScreenRecordingService::class.java)
            intent.putExtra(KEY_STOP_SCREEN_RECORDING, true)
            startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            Toast.makeText(context, "Screen Recording Stopped", Toast.LENGTH_SHORT).show()
            context.stopService(Intent(context, ScreenRecordingService::class.java))
        }

        private fun startForegroundService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var originalMp4File: File? = null
    private var mp4ComposeredMp4File: File? = null

    private var mediaRecorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val handler = Handler()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        setupNotificationChannel()

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Notification.Builder(this)
        } else {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        }

        val notification = builder
            .setContentTitle("Screen Recording")
            .build()
        startForeground(NOTIFICATION_ID, notification)

        when {
            intent.getBooleanExtra(KEY_START_SCREEN_RECORDING, false) -> startRecording(intent)
            intent.getBooleanExtra(KEY_STOP_SCREEN_RECORDING, false) -> stopRecording()
            else -> throw IllegalStateException("")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaRecorder = null
        virtualDisplay = null
        projection = null
        super.onDestroy()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Screen Recording",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun startRecording(intent: Intent) {
        val metrics = resources.displayMetrics

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection =
            projectionManager.getMediaProjection(
                intent.getIntExtra(KEY_RESULT_CODE, Activity.RESULT_OK),
                intent.getParcelableExtra(KEY_SCREEN_CAPTURE_INTENT)
            )
        originalMp4File = createMp4File()

        mediaRecorder = MediaRecorder().also {
            it.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            it.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            it.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            it.setVideoEncodingBitRate(5 * 1024 * 1024)
            it.setVideoFrameRate(15)
            it.setVideoSize(metrics.widthPixels, metrics.heightPixels)
            it.setAudioSamplingRate(44100)
            it.setOutputFile(originalMp4File!!.path)
            it.prepare()
        }

        virtualDisplay = projection!!.createVirtualDisplay(
            "recode",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null,
            null
        )

        mediaRecorder!!.start()
    }

    private fun stopRecording() {
        mediaRecorder?.stop()
        mediaRecorder?.release()
        virtualDisplay?.release()
        projection?.stop()

        if (originalMp4File != null && originalMp4File!!.exists()) {
            Toast.makeText(this, "Success: Screen Recording", Toast.LENGTH_SHORT).show()
            if (USE_MP4_COMPOSER) {
                mp4ComposeredMp4File = createMp4File()

                val metrics = resources.displayMetrics
                Mp4Composer(originalMp4File!!.path, mp4ComposeredMp4File!!.path)
                    .trim(200, -1) // remove dialog
                    .size(metrics.widthPixels, metrics.widthPixels) // just crop center
                    .fillMode(FillMode.PRESERVE_ASPECT_CROP)
                    .listener(mp4Listener)
                    .start()
            } else {
                notifySuccess(originalMp4File!!)
            }
        } else {
            notifyFailed()
        }
    }

    private val mp4Listener = object : Mp4Composer.Listener {
        override fun onProgress(progress: Double) {
            Log.d(TAG, "onProgress = $progress")
        }

        override fun onCompleted() {
            Log.d(TAG, "onCompleted()")
            notifySuccess(mp4ComposeredMp4File!!)
        }

        override fun onCanceled() {
            Log.d(TAG, "onCanceled")
            notifyFailed()
        }

        override fun onFailed(exception: Exception) {
            Log.e(TAG, "onFailed()", exception)
            notifyFailed()
        }
    }

    private fun notifySuccess(file: File) {
        runOnUiThread {
            Toast.makeText(this, "Success: Screen Recording", Toast.LENGTH_SHORT)
                .show()

            // notify the path to MainActivity
            val intent = Intent(ACTION_MP4_SAVED)
            intent.putExtra(KEY_PATH, file.path)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            stopSelf()
        }
    }

    private fun notifyFailed() {
        runOnUiThread {
            Toast.makeText(this, "Failed: Screen Recording", Toast.LENGTH_SHORT)
                .show()
            stopSelf()
        }
    }

    private fun runOnUiThread(r: (() -> Unit)?) {
        handler.post(r)
    }

    private fun createMp4File(): File {
        // /storage/emulated/0/Android/data/com.rakuishi.screenrecordingsample/cache/*.mp4
        val dir = File("${externalCacheDir?.path}/screen_recording/")
        if (!dir.exists()) {
            dir.mkdir()
        }
        return File("${externalCacheDir?.path}/screen_recording/${System.currentTimeMillis()}.mp4")
    }
}