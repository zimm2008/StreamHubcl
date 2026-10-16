package com.streamhub.mobile

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamService : Service() {

    companion object {
        private const val TAG = "StreamService"
        const val HOST = "127.0.0.1"
        const val PORT = 27183
        const val CHANNEL_ID = "streamhub_engine"
        const val NOTIF_ID = 27183

        const val ACTION_PROJECTION = "com.streamhub.mobile.PROJECTION"
        const val ACTION_SCREEN_OFF_CHANGED = "com.streamhub.mobile.SCREEN_OFF_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var projectionReady = false
        @Volatile var screenOffMode = false
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var alive = false
    @Volatile private var sender: PacketSender? = null
    private var netThread: Thread? = null

    private lateinit var screen: ScreenAudioStreamer
    private lateinit var camera: CameraStreamer
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        screen = ScreenAudioStreamer(applicationContext)
        camera = CameraStreamer(applicationContext)
        screen.onProjectionLost = {
            projectionReady = false
            sender?.sendEvent(JSONObject().put("event", "projection_lost"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be the first thing we do: promote to foreground within the 5s window.
        ensureForeground(false)

        if (intent?.action == ACTION_PROJECTION) {
            val rc = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (rc == Activity.RESULT_OK && data != null) {
                // Android 14+: the mediaProjection FGS type may only be added after consent was granted.
                if (ensureForeground(true)) {
                    try {
                        screen.setProjection(rc, data)
                        projectionReady = true
                        sender?.sendEvent(JSONObject().put("event", "projection_ready"))
                    } catch (e: Exception) {
                        Log.d(TAG, "setProjection failed: ${e.message}")
                    }
                }
            }
        }

        if (!alive) {
            alive = true
            netThread = Thread({ netLoop() }, "stream-net").also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- foreground

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "StreamHub Engine", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamHub")
            .setContentText("Streaming engine running")
            .setSmallIcon(android.R.drawable.stat_sys_upload) // framework icon: no resource linking needed
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** Returns true if the requested (full) type set was accepted. Falls back to dataSync-only on failure. */
    private fun ensureForeground(withProjection: Boolean): Boolean {
        val notif = buildNotification()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= 30) {
            if (granted(android.Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(android.Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (withProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return try {
            startForeground(NOTIF_ID, notif, types)
            true
        } catch (e: Exception) {
            Log.d(TAG, "startForeground($types) failed: ${e.message}; falling back to dataSync")
            try {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e2: Exception) {
                Log.d(TAG, "dataSync fallback failed: ${e2.message}")
            }
            false
        }
    }

    // ---------------------------------------------------------------- networking

    private fun netLoop() {
        while (alive) {
            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    sendBufferSize = 1 shl 20
                    connect(InetSocketAddress(HOST, PORT), 2000)
                }
                val s = PacketSender(socket) { e ->
                    Log.d(TAG, "Sender failure: ${e.message}")
                }
                sender = s
                Log.d(TAG, "Connected to PC")
                s.sendEvent(hello())

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (alive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    executor.execute { handleCommand(line) }
                }
                Log.d(TAG, "PC closed the connection")
            } catch (e: IOException) {
                Log.d(TAG, "Connection: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "Net loop error: ${e.message}")
            } finally {
                stopAllStreams()
                sender?.close()
                sender = null
                try { socket?.close() } catch (_: IOException) {}
            }
            if (alive) {
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun hello(): JSONObject {
        val (w, h) = screen.currentSize()
        return JSONObject()
            .put("event", "hello")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("screen_width", w).put("screen_height", h)
            .put("projection_ready", projectionReady)
            .put("cameras", camera.describe())
            .put("protocol", 1)
    }

    // ---------------------------------------------------------------- commands

    private fun reply(cmd: String, ok: Boolean, msg: String? = null) {
        val j = JSONObject().put("event", "ack").put("cmd", cmd).put("ok", ok)
        if (msg != null) j.put("msg", msg)
        sender?.sendEvent(j)
    }

    private fun handleCommand(line: String) {
        val s = sender ?: return
        val json = try { JSONObject(line) } catch (e: Exception) {
            reply("?", false, "invalid json"); return
        }
        val cmd = json.optString("cmd")
        try {
            when (cmd) {
                "ping" -> s.sendEvent(JSONObject().put("event", "pong").put("t", json.opt("t")))

                "start_screen" -> {
                    if (json.has("screen_off")) setScreenOff(json.optBoolean("screen_off"))
                    screen.start(
                        s,
                        ScreenConfig(
                            bitrate = json.optInt("bitrate", 12_000_000),
                            fps = json.optInt("fps", 60),
                            internalAudio = json.optBoolean("audio", true),
                            mic = json.optBoolean("mic", false)
                        )
                    )
                    updateLocks()
                    reply(cmd, true)
                }

                "stop_screen" -> {
                    screen.stop()
                    updateLocks()
                    reply(cmd, true)
                }

                "start_camera" -> {
                    camera.start(
                        s,
                        json.optString("lens", "main"),
                        json.optInt("width", 1920),
                        json.optInt("height", 1080),
                        json.optInt("fps", 30),
                        json.optInt("bitrate", 8_000_000)
                    )
                    updateLocks()
                    reply(cmd, true)
                }

                "stop_camera" -> {
                    camera.stop()
                    s.sendEvent(JSONObject().put("event", "camera_stopped"))
                    updateLocks()
                    reply(cmd, true)
                }

                "list_cameras" -> s.sendEvent(JSONObject().put("event", "cameras").put("cameras", camera.describe()))

                "screen_off" -> {
                    setScreenOff(json.optBoolean("enabled", true))
                    reply(cmd, true)
                }

                "request_keyframe" -> {
                    screen.requestKeyFrame()
                    camera.requestKeyFrame()
                    reply(cmd, true)
                }

                "stop_all" -> {
                    stopAllStreams()
                    reply(cmd, true)
                }

                else -> reply(cmd, false, "unknown command")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Command '$cmd' failed: ${e.message}")
            reply(cmd, false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopAllStreams() {
        try { screen.stop() } catch (e: Exception) { Log.d(TAG, "screen.stop: ${e.message}") }
        try { camera.stop() } catch (e: Exception) { Log.d(TAG, "camera.stop: ${e.message}") }
        setScreenOff(false)
        updateLocks()
    }

    // ---------------------------------------------------------------- power

    private fun setScreenOff(enabled: Boolean) {
        screenOffMode = enabled
        sendBroadcast(Intent(ACTION_SCREEN_OFF_CHANGED).setPackage(packageName))
    }

    @Synchronized
    private fun updateLocks() {
        val active = screen.isStreaming || camera.isStreaming
        if (active) {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamHub:Stream").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "StreamHub:Wifi")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    override fun onDestroy() {
        alive = false
        stopAllStreams()
        screen.release()
        projectionReady = false
        sender?.close()
        netThread?.interrupt()
        executor.shutdownNow()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }
}
