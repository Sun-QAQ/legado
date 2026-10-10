package io.legado.app.ui.book.read.eye

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.abs
import kotlin.math.hypot

/** Owns camera and inference on one worker. Images never leave the device or get saved. */
internal class EyeCameraTracker(
    private val context: Context,
    private val displayDegrees: Int,
    private val onSample: (GazeSample?, Long) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val thread = HandlerThread("EyePageTracker").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var landmarker: FaceLandmarker? = null
    private var rotation = 0
    private var lastFrame = 0L

    init {
        worker.post {
            try {
                if (active) open()
            } catch (e: Exception) {
                fail(e)
            } catch (e: LinkageError) {
                fail(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
        } ?: error("未找到前置摄像头")
        val characteristics = manager.getCameraCharacteristics(id)
        rotation = ((characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                + displayDegrees) % 360
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val size = sizes.filter { it.width <= 640 && it.height <= 640 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: error("前置摄像头不支持图像采集")
        landmarker = FaceLandmarker.createFromOptions(context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("eye_page/face_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(2)
                .setMinFaceDetectionConfidence(0.6f)
                .setMinFacePresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .build())
        if (!active) return
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            .apply { setOnImageAvailableListener({ processImage(it) }, worker) }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!active) {
                    device.close()
                    return
                }
                camera = device
                createSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                fail(IllegalStateException("前置摄像头已断开"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                fail(IllegalStateException("无法打开前置摄像头 ($error)"))
            }
        }, worker)
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice) {
        try {
            val surface = reader?.surface ?: return
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!active) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }.build()
                        configured.setRepeatingRequest(request, null, worker)
                    } catch (e: Exception) {
                        fail(e)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    fail(IllegalStateException("无法启动前置摄像头采集"))
                }
            }, worker)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun processImage(source: ImageReader) {
        if (!active) return
        try {
            val image = source.acquireLatestImage() ?: return
            image.use {
                val now = SystemClock.uptimeMillis()
                if (now - lastFrame < 100L) return
                lastFrame = now
                val bitmap = toBitmap(image)
                val rotated = if (rotation == 0) bitmap else Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true)
                if (rotated !== bitmap) bitmap.recycle()
                val input = BitmapImageBuilder(rotated).build()
                try {
                    val result = landmarker?.detectForVideo(input, now)
                    val faces = result?.faceLandmarks().orEmpty()
                    val sample = if (faces.size == 1) gaze(faces[0], Size(rotated.width, rotated.height))
                        else null
                    main.post { if (active) onSample(sample, now) }
                } finally {
                    input.close()
                    rotated.recycle()
                }
            }
        } catch (e: Exception) {
            fail(e)
        } catch (e: LinkageError) {
            fail(e)
        }
    }

    private fun gaze(points: List<NormalizedLandmark>, size: Size): GazeSample? {
        if (points.size < 478) return null
        fun x(i: Int) = points[i].x() * size.width
        fun y(i: Int) = points[i].y() * size.height
        fun eye(outer: Int, inner: Int, upper: Int, lower: Int, iris: Int): Float? {
            val dx = x(inner) - x(outer)
            val dy = y(inner) - y(outer)
            val width = hypot(dx, dy)
            if (width < 8f) return null
            // Perpendicular eye axis removes the effect of head roll.
            val nx = -dy / width * if (dx > 0) 1 else -1
            val ny = abs(dx) / width
            val aperture = ((x(lower) - x(upper)) * nx + (y(lower) - y(upper)) * ny) / width
            if (aperture < 0.12f) return null // Closed eye / blink / unreliable iris.
            return ((x(iris) - (x(upper) + x(lower)) / 2) * nx
                    + (y(iris) - (y(upper) + y(lower)) / 2) * ny) / width
        }
        val right = eye(33, 133, 159, 145, 468) ?: return null
        val left = eye(362, 263, 386, 374, 473) ?: return null
        if (abs(right - left) > 0.08f) return null
        val dx = x(263) - x(33)
        val dy = y(263) - y(33)
        val width = hypot(dx, dy)
        if (width < 30f) return null
        val noseX = x(1) - (x(33) + x(263)) / 2
        val noseY = y(1) - (y(33) + y(263)) / 2
        return GazeSample((left + right) / 2,
            (-noseX * dy + noseY * dx) / (width * width),
            (noseX * dx + noseY * dy) / (width * width), width / size.width)
    }

    private fun toBitmap(image: Image): Bitmap {
        val planes = image.planes
        val pixels = IntArray(image.width * image.height)
        val positions = planes.map { it.buffer.position() }
        fun value(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            return p.buffer.get(positions[plane] + y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val luma = (value(0, x, y) - 16).coerceAtLeast(0)
            val u = value(1, x / 2, y / 2) - 128
            val v = value(2, x / 2, y / 2) - 128
            val r = ((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[y * image.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun fail(error: Throwable) {
        if (!active) return
        main.post { onError(error) }
        stop()
    }

    fun stop() {
        if (!active) return
        // Invalidate queued samples synchronously before closing worker-owned resources.
        active = false
        worker.post {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader?.close() }
            runCatching { landmarker?.close() }
            session = null
            camera = null
            reader = null
            landmarker = null
            thread.quitSafely()
        }
    }
}
