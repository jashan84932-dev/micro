package com.micro.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors

/**
 * Air control: the front camera watches your hand and Micro moves a cursor on screen.
 *  - point with one finger = move the cursor
 *  - pinch (thumb + finger) = tap, pinch and move then release = swipe / drag, pinch and hold = long press
 *  - open palm moving up, down, left or right = swipe the screen
 *  - fist held for a moment = Back, two fingers (peace) held = Home
 * It needs: camera permission, "draw over other apps" permission, Micro's accessibility service and the hand model file.
 */
object AirControl {
    private const val MODEL = "hand_landmarker.task"

    @Volatile var running = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var landmarker: HandLandmarker? = null
    private var sensorOrientation = 270
    private val actions = Executors.newSingleThreadExecutor()
    @Volatile private var actionBusy = false

    private var wm: WindowManager? = null
    private var cursor: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    private var screenW = 1080
    private var screenH = 1920
    private var lastFrame = 0L
    private var lastHandAt = 0L

    // tracking state
    private var sx = -1f
    private var sy = -1f
    private var pinching = false
    private var pinchStartX = 0f
    private var pinchStartY = 0f
    private var pinchStartAt = 0L
    private var longPressed = false
    private var pose = ""
    private var poseSince = 0L
    private var lastPoseAction = 0L
    private val palm = ArrayList<DoubleArray>() // t, x, y
    private var lastSwipeAt = 0L

    private class CursorView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var down = false
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.FILL
            p.color = if (down) Color.parseColor("#9900E676") else Color.parseColor("#6600E5FF")
            c.drawCircle(r, r, r - 4f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = if (down) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
            c.drawCircle(r, r, r - 4f, p)
        }
    }

    // ------------------------------------------------------------------ start and stop

    fun start(ctx: Context, hands: Hands): String {
        if (running) return "हाथ के इशारे पहले से चल रहे हैं।"
        val app = ctx.applicationContext
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "कैमरे की अनुमति चाहिए। Micro में पहला बटन दबाकर दे दो।"
        }
        if (!Settings.canDrawOverlays(app)) {
            return "सेटिंग्स में Micro को 'दूसरे ऐप्स के ऊपर दिखाने' की अनुमति दे दो, ताकि स्क्रीन पर कर्सर दिख सके।"
        }
        if (hands.service == null) return "पहले Accessibility में Micro को चालू करो, उसी से मैं टैप और स्वाइप कर पाऊँगा।"
        val hasModel = try { app.assets.open(MODEL).use { true } } catch (e: Exception) { false }
        if (!hasModel) return "हाथ पहचानने वाली मॉडल फ़ाइल ऐप में नहीं है। ऐप को GitHub से दोबारा बनाओ, बिल्ड के समय वह अपने आप डाउनलोड होती है।"
        running = true
        resetState()
        Thread {
            try {
                val opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()
                landmarker = HandLandmarker.createFromOptions(app, opts)
                main.post { showCursor(app) }
                openCamera(app, hands)
            } catch (e: Exception) {
                running = false
                Assistant.get(app).announce("हाथ के इशारे शुरू नहीं हो पाए।")
                cleanup()
            }
        }.start()
        return "हाथ के इशारे चालू। एक उँगली से कर्सर हिलाओ, उँगली और अँगूठे को जोड़कर टैप करो, खुली हथेली से स्वाइप करो। बंद करने के लिए बोलो, हाथ के इशारे बंद।"
    }

    fun stop(): String {
        if (!running) return "हाथ के इशारे चल ही नहीं रहे थे।"
        running = false
        cleanup()
        return "हाथ के इशारे बंद।"
    }

    private fun resetState() {
        sx = -1f
        sy = -1f
        pinching = false
        longPressed = false
        pose = ""
        palm.clear()
        lastHandAt = System.currentTimeMillis()
        lastFrame = 0L
    }

    private fun cleanup() {
        try { session?.close() } catch (e: Exception) { /* ignore */ }
        try { camera?.close() } catch (e: Exception) { /* ignore */ }
        try { reader?.close() } catch (e: Exception) { /* ignore */ }
        try { landmarker?.close() } catch (e: Exception) { /* ignore */ }
        session = null
        camera = null
        reader = null
        landmarker = null
        try { thread?.quitSafely() } catch (e: Exception) { /* ignore */ }
        thread = null
        handler = null
        main.post {
            try { cursor?.let { wm?.removeView(it) } } catch (e: Exception) { /* ignore */ }
            cursor = null
        }
    }

    // ------------------------------------------------------------------ cursor

    private fun showCursor(ctx: Context) {
        try {
            val m = ctx.resources.displayMetrics
            screenW = m.widthPixels
            screenH = m.heightPixels
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val v = CursorView(ctx)
            val lp = WindowManager.LayoutParams(
                84, 84,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = screenW / 2
            lp.y = screenH / 2
            w.addView(v, lp)
            cursor = v
            cursorParams = lp
        } catch (e: Exception) {
            // without a cursor the gestures still work
        }
    }

    private fun moveCursor(x: Float, y: Float, down: Boolean) {
        main.post {
            val v = cursor ?: return@post
            val lp = cursorParams ?: return@post
            lp.x = (x - 42).toInt()
            lp.y = (y - 42).toInt()
            v.down = down
            try {
                wm?.updateViewLayout(v, lp)
                v.invalidate()
            } catch (e: Exception) {
                // window gone
            }
        }
    }

    // ------------------------------------------------------------------ camera

    private fun openCamera(app: Context, hands: Hands) {
        val t = HandlerThread("air-camera")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        if (id == null) {
            running = false
            Assistant.get(app).announce("आगे वाला कैमरा नहीं मिला।")
            cleanup()
            return
        }
        val chars = cm.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes?.filter { it.width in 320..800 }?.minByOrNull { it.width * it.height }
            ?: sizes?.minByOrNull { it.width * it.height }
        if (size == null) {
            running = false
            cleanup()
            return
        }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd -> onFrame(app, hands, rd) }, h)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    try {
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(r.surface)
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Exception) {
                                    running = false
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                running = false
                                cleanup()
                            }
                        }, h)
                    } catch (e: Exception) {
                        running = false
                        cleanup()
                    }
                }

                override fun onDisconnected(cam: CameraDevice) {
                    running = false
                    cleanup()
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    running = false
                    Assistant.get(app).announce("कैमरा नहीं खुल पाया। शायद कोई दूसरा ऐप इस्तेमाल कर रहा है।")
                    cleanup()
                }
            }, h)
        } catch (e: SecurityException) {
            running = false
            cleanup()
        }
    }

    // ------------------------------------------------------------------ one camera frame

    private fun toBitmap(img: Image): Bitmap {
        val w = img.width
        val h = img.height
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val yy = yB.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF
                val u = (uB.get((y shr 1) * uP.rowStride + (x shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vB.get((y shr 1) * vP.rowStride + (x shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val raw = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postRotate(sensorOrientation.toFloat())
        m.postScale(-1f, 1f) // mirror, like a selfie
        return Bitmap.createBitmap(raw, 0, 0, w, h, m, true)
    }

    private fun onFrame(app: Context, hands: Hands, rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val now = System.currentTimeMillis()
        if (!running || now - lastFrame < 90) {
            img.close()
            return
        }
        lastFrame = now
        val bmp = try { toBitmap(img) } catch (e: Exception) { null } finally { img.close() }
        if (bmp == null) return
        val lm = landmarker ?: return
        val result = try { lm.detect(BitmapImageBuilder(bmp).build()) } catch (e: Exception) { return }
        val pts = result.landmarks().firstOrNull()
        if (pts == null || pts.size < 21) {
            handLost(app, now)
            return
        }
        lastHandAt = now
        val lx = FloatArray(21) { pts[it].x() }
        val ly = FloatArray(21) { pts[it].y() }
        process(hands, lx, ly, now)
    }

    private fun handLost(app: Context, now: Long) {
        if (pinching) pinching = false
        pose = ""
        palm.clear()
        moveCursor(if (sx < 0) screenW / 2f else sx, if (sy < 0) screenH / 2f else sy, false)
        if (now - lastHandAt > 180_000) {
            running = false
            cleanup()
            Assistant.get(app).announce("तीन मिनट से हाथ नहीं दिखा, इसलिए हाथ के इशारे बंद कर दिए।")
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = Math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private fun process(hands: Hands, x: FloatArray, y: FloatArray, now: Long) {
        val size = dist(x[0], y[0], x[9], y[9]).coerceAtLeast(0.01f)
        fun ext(tip: Int, pip: Int) = y[tip] < y[pip] - 0.02f
        val idx = ext(8, 6)
        val mid = ext(12, 10)
        val ring = ext(16, 14)
        val pinky = ext(20, 18)
        val pinch = dist(x[4], y[4], x[8], y[8]) < 0.32f * size

        // where the finger points, mapped from the middle of the camera view to the whole screen
        val fx = if (pinch) (x[4] + x[8]) / 2 else x[8]
        val fy = if (pinch) (y[4] + y[8]) / 2 else y[8]
        val tx = (((fx - 0.15f) / 0.7f).coerceIn(0f, 1f)) * screenW
        val ty = (((fy - 0.10f) / 0.6f).coerceIn(0f, 1f)) * screenH
        if (sx < 0) {
            sx = tx
            sy = ty
        } else {
            sx += (tx - sx) * 0.5f
            sy += (ty - sy) * 0.5f
        }

        val palmOpen = idx && mid && ring && pinky
        val fist = !idx && !mid && !ring && !pinky && !pinch
        val peace = idx && mid && !ring && !pinky

        // pinch = tap, swipe or long press
        if (pinch) {
            if (!pinching) {
                pinching = true
                pinchStartX = sx
                pinchStartY = sy
                pinchStartAt = now
                longPressed = false
            } else if (!longPressed && now - pinchStartAt > 900 && dist(sx, sy, pinchStartX, pinchStartY) < 40f) {
                longPressed = true
                val px = pinchStartX.toInt()
                val py = pinchStartY.toInt()
                act { hands.swipe(px, py, px + 1, py, 900) }
            }
            moveCursor(sx, sy, true)
            return
        } else if (pinching) {
            pinching = false
            val moved = dist(sx, sy, pinchStartX, pinchStartY)
            val sxI = pinchStartX.toInt()
            val syI = pinchStartY.toInt()
            val exI = sx.toInt()
            val eyI = sy.toInt()
            if (!longPressed) {
                if (moved < 90f) act { hands.tap(exI, eyI) } else act { hands.swipe(sxI, syI, exI, eyI, 300) }
            }
        }

        moveCursor(sx, sy, false)

        // open palm moving fast = swipe the screen
        if (palmOpen) {
            palm.add(doubleArrayOf(now.toDouble(), x[9].toDouble(), y[9].toDouble()))
            while (palm.isNotEmpty() && now - palm[0][0] > 450) palm.removeAt(0)
            if (palm.size >= 3 && now - lastSwipeAt > 900) {
                val dx = (palm.last()[1] - palm[0][1]).toFloat()
                val dy = (palm.last()[2] - palm[0][2]).toFloat()
                val w = screenW
                val h = screenH
                if (Math.abs(dy) > 0.16f && Math.abs(dy) > Math.abs(dx)) {
                    lastSwipeAt = now
                    palm.clear()
                    // hand moves up = content moves up
                    if (dy < 0) act { hands.swipe(w / 2, (h * 0.7f).toInt(), w / 2, (h * 0.3f).toInt(), 250) }
                    else act { hands.swipe(w / 2, (h * 0.3f).toInt(), w / 2, (h * 0.7f).toInt(), 250) }
                } else if (Math.abs(dx) > 0.2f && Math.abs(dx) > Math.abs(dy)) {
                    lastSwipeAt = now
                    palm.clear()
                    if (dx > 0) act { hands.swipe((w * 0.25f).toInt(), h / 2, (w * 0.75f).toInt(), h / 2, 250) }
                    else act { hands.swipe((w * 0.75f).toInt(), h / 2, (w * 0.25f).toInt(), h / 2, 250) }
                }
            }
        } else {
            palm.clear()
        }

        // fist held = Back, peace sign held = Home
        val now2 = when {
            fist -> "fist"
            peace -> "peace"
            else -> ""
        }
        if (now2 != pose) {
            pose = now2
            poseSince = now
        } else if (pose.isNotEmpty() && now - poseSince > 1200 && now - lastPoseAction > 2000) {
            lastPoseAction = now
            val which = pose
            act { hands.press(if (which == "fist") "back" else "home") }
        }
    }

    private fun act(block: () -> Unit) {
        if (actionBusy) return
        actionBusy = true
        actions.execute {
            try {
                block()
            } catch (e: Exception) {
                // ignore a failed gesture
            } finally {
                actionBusy = false
            }
        }
    }
}
