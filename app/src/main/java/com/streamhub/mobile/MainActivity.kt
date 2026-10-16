package com.streamhub.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var label: TextView

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { applyScreenOffMode() }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermissionsHandled()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, StreamService::class.java)
                    .setAction(StreamService.ACTION_PROJECTION)
                    .putExtra(StreamService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(StreamService.EXTRA_RESULT_DATA, data)
                ContextCompat.startForegroundService(this, i)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        label = TextView(this).apply {
            text = "Waiting for PC Connection..."
            setTextColor(Color.parseColor("#00FF66"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)

        ContextCompat.registerReceiver(
            this, screenOffReceiver,
            IntentFilter(StreamService.ACTION_SCREEN_OFF_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (savedInstanceState == null) {
            val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun onPermissionsHandled() {
        // Start the engine (types are chosen from the permissions that were actually granted).
        ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))

        if (!StreamService.projectionReady) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenOffMode()
    }

    /** Screen-off mode: backlight forced to minimum/off, display kept "on" so capture keeps running. */
    private fun applyScreenOffMode() {
        val lp = window.attributes
        if (StreamService.screenOffMode) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            label.visibility = View.INVISIBLE
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            label.visibility = View.VISIBLE
        }
        window.attributes = lp
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
