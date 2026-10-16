package com.streamhub.mobile

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Wire format (all integers big-endian), one frame per packet:
 *   int32  length   = 11 + payloadSize
 *   uint8  type     1=video(H.264 Annex-B) 2=internal audio PCM 3=mic PCM 4=JSON event
 *   uint8  source   0=screen 1=cam main 2=cam ultrawide 3=cam front 255=none
 *   uint8  flags    bit0=keyframe, bit1=codec config (SPS/PPS)
 *   int64  ptsUs    monotonic microseconds (System.nanoTime()/1000 clock)
 *   bytes  payload
 */
object Proto {
    const val TYPE_VIDEO = 1
    const val TYPE_AUDIO_INTERNAL = 2
    const val TYPE_AUDIO_MIC = 3
    const val TYPE_EVENT = 4

    const val SRC_SCREEN = 0
    const val SRC_CAM_MAIN = 1
    const val SRC_CAM_ULTRAWIDE = 2
    const val SRC_CAM_FRONT = 3
    const val SRC_NONE = 255

    const val FLAG_KEY = 1
    const val FLAG_CONFIG = 2

    const val HEADER_SIZE = 15
}

class PacketSender(
    private val socket: Socket,
    private val onFailure: (Throwable) -> Unit
) {
    companion object {
        private const val TAG = "PacketSender"
        private const val MAX_VIDEO_BACKLOG = 60
        private const val MAX_AUDIO_BACKLOG = 300
    }

    private val out: OutputStream = socket.getOutputStream()
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val dropping = BooleanArray(256)

    @Volatile private var running = true

    /** Incremented whenever frames were dropped; encoders watch it and emit a keyframe. */
    @Volatile var keyframeEpoch = 0
        private set

    private val writer = Thread({ writeLoop() }, "stream-writer").apply {
        isDaemon = true
        start()
    }

    fun send(
        type: Int, source: Int, flags: Int, ptsUs: Long,
        data: ByteArray, off: Int = 0, len: Int = data.size
    ) {
        if (!running) return

        if (type == Proto.TYPE_VIDEO) {
            val isKey = (flags and (Proto.FLAG_KEY or Proto.FLAG_CONFIG)) != 0
            synchronized(dropping) {
                if (isKey) {
                    dropping[source and 0xFF] = false
                } else if (dropping[source and 0xFF] || queue.size > MAX_VIDEO_BACKLOG) {
                    if (!dropping[source and 0xFF]) {
                        dropping[source and 0xFF] = true
                        keyframeEpoch++
                    }
                    return
                }
            }
        } else if (type != Proto.TYPE_EVENT && queue.size > MAX_AUDIO_BACKLOG) {
            return
        }

        val buf = ByteBuffer.allocate(Proto.HEADER_SIZE + len)
        buf.putInt(11 + len)
        buf.put(type.toByte())
        buf.put(source.toByte())
        buf.put(flags.toByte())
        buf.putLong(ptsUs)
        buf.put(data, off, len)
        queue.offer(buf.array())
    }

    fun sendEvent(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        send(Proto.TYPE_EVENT, Proto.SRC_NONE, 0, 0L, bytes)
    }

    private fun writeLoop() {
        try {
            while (running) {
                val pkt = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(pkt)
                out.flush()
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: IOException) {
            if (running) {
                Log.d(TAG, "Write failed: ${e.message}")
                onFailure(e)
            }
        }
    }

    fun close() {
        running = false
        writer.interrupt()
        queue.clear()
        try { socket.close() } catch (_: IOException) {}
    }
}
