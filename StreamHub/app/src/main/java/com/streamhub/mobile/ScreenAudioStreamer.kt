package com.streamhub.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import org.json.JSONObject

data class ScreenConfig(val bitrate: Int, val fps: Int, val internalAudio: Boolean, val mic: Boolean)

private data class Metrics(val w: Int, val h: Int, val dpi: Int)

class ScreenAudioStreamer(private val ctx: Context) {
    companion object {
        private const val TAG = "ScreenAudioStreamer"
        const val SAMPLE_RATE = 48000
    }

    private val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
    private val dm = ctx.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: H264Encoder? = null
    private var sender: PacketSender? = null
    private var config: ScreenConfig? = null
    private var internalPump: AudioPump? = null
    private var micPump: AudioPump? = null
    private var lastW = 0
    private var lastH = 0
    private var dpi = 320

    @Volatile var onProjectionLost: (() -> Unit)? = null

    val hasProjection: Boolean @Synchronized get() = projection != null
    val isStreaming: Boolean @Synchronized get() = encoder != null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDefaultDisplayChanged()
        }
    }

    @Suppress("DEPRECATION")
    private fun metrics(): Metrics {
        val m = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return Metrics(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    fun currentSize(): Pair<Int, Int> = metrics().let { it.w to it.h }

    /** Must be called AFTER the service is in foreground with the mediaProjection type (Android 14+). */
    @Synchronized
    fun setProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val p = mpm.getMediaProjection(resultCode, data)
        // Android 14+ requires a callback to be registered before createVirtualDisplay.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system/user")
                handler.post {
                    synchronized(this@ScreenAudioStreamer) {
                        stopInternal()
                        try { virtualDisplay?.release() } catch (_: Exception) {}
                        virtualDisplay = null
                        projection = null
                    }
                    onProjectionLost?.invoke()
                }
            }
        }, handler)
        projection = p
    }

    @Synchronized
    fun start(sender: PacketSender, cfg: ScreenConfig) {
        val p = projection ?: throw IllegalStateException("Screen capture permission not granted yet")
        stopInternal()
        this.sender = sender
        this.config = cfg

        val m = metrics()
        dpi = m.dpi
        val (enc, surface) = createEncoder(m.w, m.h, cfg)
        attach(p, enc, surface)
        encoder = enc
        lastW = m.w
        lastH = m.h

        if (cfg.internalAudio) {
            internalPump = try {
                createInternalAudio(p, sender).also { it.start() }
            } catch (e: Exception) {
                Log.d(TAG, "Internal audio unavailable: ${e.message}")
                sender.sendEvent(JSONObject().put("event", "warning").put("msg", "internal audio unavailable: ${e.message}"))
                null
            }
        }
        if (cfg.mic) {
            micPump = try {
                createMicAudio(sender).also { it.start() }
            } catch (e: Exception) {
                Log.d(TAG, "Mic unavailable: ${e.message}")
                sender.sendEvent(JSONObject().put("event", "warning").put("msg", "mic unavailable: ${e.message}"))
                null
            }
        }

        dm.registerDisplayListener(displayListener, handler)
        sendStarted(enc)
    }

    private fun sendStarted(enc: H264Encoder) {
        val cfg = config ?: return
        sender?.sendEvent(
            JSONObject().put("event", "screen_started")
                .put("width", enc.width).put("height", enc.height).put("fps", cfg.fps)
                .put("internal_audio", internalPump != null).put("mic", micPump != null)
                .put("audio_format", JSONObject().put("rate", SAMPLE_RATE).put("bits", 16))
        )
    }

    private fun createEncoder(w: Int, h: Int, cfg: ScreenConfig): Pair<H264Encoder, Surface> {
        val sizes = listOf(
            Pair(w and 1.inv(), h and 1.inv()),
            Pair(w and 15.inv(), h and 15.inv())
        ).distinct()
        var last: Exception? = null
        for ((cw, ch) in sizes) {
            val e = H264Encoder(Proto.SRC_SCREEN, sender!!, cw, ch, cfg.fps, cfg.bitrate, true)
            try {
                return Pair(e, e.start())
            } catch (ex: Exception) {
                last = ex
            }
        }
        throw last ?: IllegalStateException("No usable encoder size")
    }

    private fun attach(p: MediaProjection, enc: H264Encoder, surface: Surface) {
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = p.createVirtualDisplay(
                "StreamHub-Screen", enc.width, enc.height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
            )
        } else {
            vd.resize(enc.width, enc.height, dpi)
            vd.surface = surface
        }
    }

    @Synchronized
    private fun onDefaultDisplayChanged() {
        val cfg = config ?: return
        val p = projection ?: return
        if (encoder == null) return
        val m = metrics()
        if (m.w == lastW && m.h == lastH) return
        try {
            dpi = m.dpi
            val old = encoder
            val (enc, surface) = createEncoder(m.w, m.h, cfg)
            attach(p, enc, surface)
            encoder = enc
            lastW = m.w
            lastH = m.h
            old?.stop()
            sendStarted(enc)
        } catch (e: Exception) {
            Log.d(TAG, "Rotation re-init failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        val was = encoder != null
        stopInternal()
        if (was) sender?.sendEvent(JSONObject().put("event", "screen_stopped"))
    }

    @Synchronized
    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    private fun stopInternal() {
        try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        internalPump?.stop(); internalPump = null
        micPump?.stop(); micPump = null
        // Keep the VirtualDisplay (Android 14 allows only one per projection); just detach the surface.
        try { virtualDisplay?.surface = null } catch (_: Exception) {}
        encoder?.stop(); encoder = null
    }

    private fun releaseProjection() {
        stopInternal()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    @Synchronized
    fun release() {
        releaseProjection()
    }

    private fun hasMicPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun createInternalAudio(p: MediaProjection, sender: PacketSender): AudioPump {
        check(hasMicPermission()) { "RECORD_AUDIO not granted" }
        val apc = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val chunk = SAMPLE_RATE / 50 * 4 // 20 ms stereo s16
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, chunk * 4))
            .setAudioPlaybackCaptureConfig(apc)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord (playback) not initialized" }
        return AudioPump(rec, Proto.TYPE_AUDIO_INTERNAL, chunk, sender)
    }

    @SuppressLint("MissingPermission")
    private fun createMicAudio(sender: PacketSender): AudioPump {
        check(hasMicPermission()) { "RECORD_AUDIO not granted" }
        val chunk = SAMPLE_RATE / 50 * 2 // 20 ms mono s16
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, chunk * 4)
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord (mic) not initialized" }
        return AudioPump(rec, Proto.TYPE_AUDIO_MIC, chunk, sender)
    }
}

/** Reads fixed 20 ms PCM chunks from an AudioRecord and forwards them to the socket. */
private class AudioPump(
    private val record: AudioRecord,
    private val type: Int,
    private val chunkBytes: Int,
    private val sender: PacketSender
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        record.startRecording()
        thread = Thread({
            val buf = ByteArray(chunkBytes)
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        sender.send(type, Proto.SRC_SCREEN, 0, System.nanoTime() / 1000, buf, 0, n)
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.d("AudioPump", "Audio loop ended: ${e.message}")
            }
        }, "audio-pump-$type").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        try { record.stop() } catch (_: Exception) {}
        try { record.release() } catch (_: Exception) {}
    }
}
