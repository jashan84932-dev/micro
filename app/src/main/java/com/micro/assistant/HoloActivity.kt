package com.micro.assistant

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Hologram mode ("Pepper's ghost"): draws a glowing 3D shape four times around the middle of the screen.
 * Put a clear plastic pyramid (narrow end down) in the centre of a dark room and the shape floats inside it.
 * Tap = next shape, long press = flip the picture (if it looks upside down), wave a hand over the phone = next shape.
 */
class HoloActivity : Activity() {
    private lateinit var view: HoloView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        view = HoloView(this)
        view.setOnClickListener { view.next() }
        view.setOnLongClickListener { view.flip(); true }
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        Gestures.onWave = { runOnUiThread { view.next() } }
    }

    override fun onPause() {
        Gestures.onWave = null
        super.onPause()
    }

    private class HoloView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val born = System.currentTimeMillis()
        private var shape = 0
        private var flipped = false

        fun next() {
            shape = (shape + 1) % 4
        }

        fun flip() {
            flipped = !flipped
        }

        private val cube = arrayOf(
            floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
            floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f)
        )
        private val cubeEdges = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
            intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
            intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)
        )
        private val pyr = arrayOf(
            floatArrayOf(-1f, 1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f), floatArrayOf(0f, -1.3f, 0f)
        )
        private val pyrEdges = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
            intArrayOf(0, 4), intArrayOf(1, 4), intArrayOf(2, 4), intArrayOf(3, 4)
        )
        private val oct = arrayOf(
            floatArrayOf(0f, -1.4f, 0f), floatArrayOf(0f, 1.4f, 0f), floatArrayOf(-1.2f, 0f, 0f), floatArrayOf(1.2f, 0f, 0f), floatArrayOf(0f, 0f, -1.2f), floatArrayOf(0f, 0f, 1.2f)
        )
        private val octEdges = arrayOf(
            intArrayOf(0, 2), intArrayOf(0, 3), intArrayOf(0, 4), intArrayOf(0, 5),
            intArrayOf(1, 2), intArrayOf(1, 3), intArrayOf(1, 4), intArrayOf(1, 5),
            intArrayOf(2, 4), intArrayOf(4, 3), intArrayOf(3, 5), intArrayOf(5, 2)
        )

        private fun project(v: FloatArray, ax: Float, ay: Float, size: Float): FloatArray {
            // rotate around Y then X
            val cy = Math.cos(ay.toDouble()).toFloat()
            val sy = Math.sin(ay.toDouble()).toFloat()
            val x1 = v[0] * cy + v[2] * sy
            val z1 = -v[0] * sy + v[2] * cy
            val cx = Math.cos(ax.toDouble()).toFloat()
            val sx = Math.sin(ax.toDouble()).toFloat()
            val y2 = v[1] * cx - z1 * sx
            val z2 = v[1] * sx + z1 * cx
            val k = 1f / (1f - z2 * 0.18f)
            return floatArrayOf(x1 * k * size, y2 * k * size)
        }

        private fun drawShape(c: Canvas, size: Float, t: Float, d: Float) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            if (shape == 3) {
                // arc reactor rings
                for (k in 0 until 3) {
                    val r = size * (1.2f - k * 0.3f)
                    p.strokeWidth = 3f * d
                    p.color = if (k % 2 == 0) Color.parseColor("#00E5FF") else Color.parseColor("#7FDBFF")
                    val rect = android.graphics.RectF(-r, -r, r, r)
                    val dir = if (k % 2 == 0) 1 else -1
                    for (seg in 0 until 3) c.drawArc(rect, dir * t * (40f + k * 30f) + seg * 120f, 80f, false, p)
                }
                p.style = Paint.Style.FILL
                p.color = Color.WHITE
                c.drawCircle(0f, 0f, size * 0.15f, p)
                return
            }
            val verts = when (shape) { 0 -> cube; 1 -> pyr; else -> oct }
            val edges = when (shape) { 0 -> cubeEdges; 1 -> pyrEdges; else -> octEdges }
            val pts = verts.map { project(it, t * 0.7f, t * 1.1f, size * 0.7f) }
            p.strokeWidth = 3f * d
            p.color = Color.parseColor("#00E5FF")
            for (e in edges) c.drawLine(pts[e[0]][0], pts[e[0]][1], pts[e[1]][0], pts[e[1]][1], p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            for (q in pts) c.drawCircle(q[0], q[1], 3.5f * d, p)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val w = width.toFloat()
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            val cx = w / 2
            val cy = h / 2
            val q = Math.min(w, h) * 0.2f
            val gap = q * 1.35f
            val t = (System.currentTimeMillis() - born) / 1000f
            val flip = if (flipped) 180f else 0f
            // top, right, bottom, left: the feet of every picture point to the middle
            val spots = arrayOf(
                floatArrayOf(cx, cy - gap, 0f),
                floatArrayOf(cx + gap, cy, 90f),
                floatArrayOf(cx, cy + gap, 180f),
                floatArrayOf(cx - gap, cy, -90f)
            )
            for (s in spots) {
                c.save()
                c.translate(s[0], s[1])
                c.rotate(s[2] + flip)
                drawShape(c, q, t, d)
                c.restore()
            }
            postInvalidateOnAnimation()
        }
    }
}
