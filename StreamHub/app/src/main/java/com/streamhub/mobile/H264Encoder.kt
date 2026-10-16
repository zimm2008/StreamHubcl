package com.streamhub.mobile

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

/** Surface-input hardware H.264 encoder that pushes Annex-B packets straight into the PacketSender. */
class H264Encoder(
    private val sourceId: Int,
    private val sender: PacketSender,
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val repeatPreviousFrame: Boolean
) {
    companion object {
        private const val TAG = "H264Encoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start(): Surface {
        var lastError: Exception? = null
        for (mode in intArrayOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )) {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger("prepend-header-to-sync-frames", 1)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                    if (repeatPreviousFrame) {
                        // MediaProjection only emits frames when the screen changes; repeat so the stream never stalls.
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    }
                }
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = c.createInputSurface()
                c.start()
                codec = c
                inputSurface = surface
                running = true
                thread = Thread({ drain(c) }, "encoder-drain-$sourceId").also {
                    it.isDaemon = true
                    it.start()
                }
                Log.d(TAG, "Encoder started ${width}x$height @${fps}fps ${bitrate}bps mode=$mode")
                return surface
            } catch (e: Exception) {
                Log.d(TAG, "Encoder configure failed (mode=$mode): ${e.message}")
                lastError = e
                try { c.release() } catch (_: Exception) {}
            }
        }
        throw lastError ?: IllegalStateException("Unable to start H.264 encoder")
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Log.d(TAG, "requestKeyFrame failed: ${e.message}")
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var seenEpoch = sender.keyframeEpoch
        try {
            while (running) {
                val epoch = sender.keyframeEpoch
                if (epoch != seenEpoch) {
                    seenEpoch = epoch
                    requestKeyFrame()
                }
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        var flags = 0
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags = flags or Proto.FLAG_CONFIG
                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags = flags or Proto.FLAG_KEY
                        sender.send(Proto.TYPE_VIDEO, sourceId, flags, info.presentationTimeUs, data)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (e: Exception) {
            if (running) Log.d(TAG, "Drain loop ended: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }
}
