package com.streamhub.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.hypot

data class CamTarget(
    val lens: String,
    val openId: String,
    val physicalId: String?,
    val equivFocal: Float,
    val sourceId: Int
)

class CameraStreamer(private val ctx: Context) {
    companion object {
        private const val TAG = "CameraStreamer"
    }

    private val cm = ctx.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: H264Encoder? = null

    val isStreaming: Boolean @Synchronized get() = encoder != null

    private fun equiv(c: CameraCharacteristics): Float {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 26f
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 26f
        val diag = hypot(s.width.toDouble(), s.height.toDouble()).toFloat()
        return if (diag > 0f) f * 43.27f / diag else 26f
    }

    private class Cand(val openId: String, val physicalId: String?, val equiv: Float)

    /** Finds main / ultrawide / front targets, including hidden physical lenses of logical multi-cameras. */
    fun discover(): Map<String, CamTarget> {
        val result = LinkedHashMap<String, CamTarget>()
        val listed = cm.cameraIdList.toSet()
        val back = LinkedHashMap<String, Cand>()
        var front: CamTarget? = null

        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> {
                    if (front == null) front = CamTarget("front", id, null, equiv(c), Proto.SRC_CAM_FRONT)
                }
                CameraCharacteristics.LENS_FACING_BACK -> {
                    val phys = c.physicalCameraIds
                    if (phys.isEmpty()) {
                        back.putIfAbsent(id, Cand(id, null, equiv(c)))
                    } else {
                        for (p in phys) {
                            val pc = cm.getCameraCharacteristics(p)
                            if (p in listed) back.putIfAbsent(p, Cand(p, null, equiv(pc)))
                            else back.putIfAbsent(p, Cand(id, p, equiv(pc)))
                        }
                    }
                }
            }
        }

        val sorted = back.values.sortedBy { it.equiv }
        if (sorted.isNotEmpty()) {
            val main = sorted.minByOrNull { abs(it.equiv - 26f) }!!
            result["main"] = CamTarget("main", main.openId, main.physicalId, main.equiv, Proto.SRC_CAM_MAIN)
            val ultra = sorted.firstOrNull { it.equiv < main.equiv * 0.8f }
            if (ultra != null) {
                result["ultrawide"] = CamTarget("ultrawide", ultra.openId, ultra.physicalId, ultra.equiv, Proto.SRC_CAM_ULTRAWIDE)
            }
        }
        if (front != null) result["front"] = front
        return result
    }

    fun describe(): JSONArray {
        val arr = JSONArray()
        try {
            for ((lens, t) in discover()) {
                arr.put(JSONObject().put("lens", lens).put("id", t.physicalId ?: t.openId)
                    .put("equiv_mm", t.equivFocal.toDouble()))
            }
        } catch (e: Exception) {
            Log.d(TAG, "describe failed: ${e.message}")
        }
        return arr
    }

    private fun pickSize(chars: CameraCharacteristics, reqW: Int, reqH: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("No stream configuration map")
        val sizes = map.getOutputSizes(MediaCodec::class.java)
            ?: map.getOutputSizes(SurfaceTexture::class.java)
            ?: throw IllegalStateException("No output sizes")
        sizes.firstOrNull { it.width == reqW && it.height == reqH }?.let { return it }
        val aspect = reqW.toFloat() / reqH
        val sameAspect = sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
        val pool = if (sameAspect.isEmpty()) sizes.toList() else sameAspect
        val target = reqW.toLong() * reqH
        return pool.minByOrNull { abs(it.width.toLong() * it.height - target) }!!
    }

    private fun pickFps(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val ok = ranges.filter { it.upper >= fps }
        return if (ok.isNotEmpty()) {
            ok.minWithOrNull(compareBy<Range<Int>>({ abs(it.upper - fps) }, { it.upper - it.lower }))
        } else ranges.maxByOrNull { it.upper }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(sender: PacketSender, lens: String, reqW: Int, reqH: Int, fps: Int, bitrate: Int) {
        stopInternal()
        check(ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission not granted"
        }
        val target = discover()[lens] ?: throw IllegalArgumentException("Camera '$lens' not available")
        val streamChars = cm.getCameraCharacteristics(target.physicalId ?: target.openId)
        val openChars = cm.getCameraCharacteristics(target.openId)
        val size = pickSize(streamChars, reqW, reqH)
        val rotation = streamChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val fpsRange = pickFps(openChars, fps)

        val enc = H264Encoder(target.sourceId, sender, size.width, size.height, fps, bitrate, false)
        val surface = enc.start()
        encoder = enc

        val t = HandlerThread("camera-thread").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        fun fail(msg: String) {
            Log.d(TAG, msg)
            sender.sendEvent(JSONObject().put("event", "camera_error").put("lens", lens).put("msg", msg))
            h.post { stop() }
        }

        try {
            cm.openCamera(target.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    val keep = synchronized(this@CameraStreamer) {
                        if (encoder === enc) { device = dev; true } else false
                    }
                    if (!keep) { dev.close(); return }
                    createSession(dev, target, surface, fpsRange, h) { ok, err ->
                        if (ok) {
                            sender.sendEvent(
                                JSONObject().put("event", "camera_started").put("lens", lens)
                                    .put("source", target.sourceId)
                                    .put("width", size.width).put("height", size.height)
                                    .put("fps", fps).put("rotation", rotation)
                                    .put("mirrored", lens == "front")
                            )
                        } else fail("session failed: $err")
                    }
                }

                override fun onDisconnected(dev: CameraDevice) {
                    dev.close()
                    fail("camera disconnected")
                }

                override fun onError(dev: CameraDevice, error: Int) {
                    dev.close()
                    fail("camera error code $error")
                }
            }, h)
        } catch (e: Exception) {
            stopInternal()
            throw e
        }
    }

    private fun createSession(
        dev: CameraDevice, target: CamTarget, surface: Surface,
        fpsRange: Range<Int>?, h: Handler, done: (Boolean, String?) -> Unit
    ) {
        try {
            val outCfg = OutputConfiguration(surface)
            target.physicalId?.let { outCfg.setPhysicalCameraId(it) }
            val executor = Executor { r -> h.post(r) }
            val sc = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, listOf(outCfg), executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        try {
                            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            }.build()
                            synchronized(this@CameraStreamer) { session = s }
                            s.setRepeatingRequest(req, null, h)
                            done(true, null)
                        } catch (e: Exception) {
                            done(false, e.message)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        done(false, "configure failed")
                    }
                }
            )
            dev.createCaptureSession(sc)
        } catch (e: Exception) {
            done(false, e.message)
        }
    }

    @Synchronized
    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        val had = encoder != null
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        encoder?.stop()
        encoder = null
        thread?.quitSafely()
        thread = null
        handler = null
        if (had) Log.d(TAG, "Camera stopped")
    }
}
