package com.rakuishi.screenrecordingsample

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE: Int = 1234
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startButton.setOnClickListener {
            isRecording = true
            updateButtonStatus()
            startScreenRecording()
        }
        stopButton.setOnClickListener {
            isRecording = false
            updateButtonStatus()
            stopScreenRecording()
        }

        updateButtonStatus()
    }

    override fun onStart() {
        super.onStart()
        registerLocalBroadcast()
    }

    override fun onStop() {
        super.onStop()
        unregisterLocalBroadcast()

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = activityManager.getRunningServices(Integer.MAX_VALUE)
        for (info in services) {
            if (ScreenRecordingService::class.java.canonicalName == info.service.className) {
                // Stop Service
                ScreenRecordingService.stopService(this)
                isRecording = false
                updateButtonStatus()
                break
            }
        }
    }

    private fun updateButtonStatus() {
        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording
    }

    private fun startScreenRecording() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    private fun stopScreenRecording() {
        ScreenRecordingService.stopScreenRecording(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE) {
            ScreenRecordingService.startScreenRecording(this, resultCode, data)
        }
    }

    private fun registerLocalBroadcast() {
        val filter = IntentFilter()
        filter.addAction(ScreenRecordingService.ACTION_MP4_SAVED)
        LocalBroadcastManager.getInstance(this).registerReceiver(localBroadcastReceiver, filter)
    }

    private fun unregisterLocalBroadcast() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localBroadcastReceiver)
    }

    private val localBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ScreenRecordingService.ACTION_MP4_SAVED == action) {
                Log.d(TAG, "onReceive: ${intent.getStringExtra(ScreenRecordingService.KEY_PATH)}")

                val videoFile = File(intent.getStringExtra(ScreenRecordingService.KEY_PATH)!!)
                val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    FileProvider.getUriForFile(
                        baseContext,
                        "${baseContext.packageName}.fileprovider",
                        videoFile
                    )
                else
                    Uri.fromFile(videoFile)
                ShareCompat.IntentBuilder.from(this@MainActivity)
                    .setStream(videoUri)
                    .setType("video/mp4")
                    .setChooserTitle("Share screen recording...")
                    .startChooser()
            }
        }
    }
}
