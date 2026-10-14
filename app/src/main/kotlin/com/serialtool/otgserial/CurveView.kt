package com.serialtool.otgserial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 双通道实时曲线 View：CH1(红) / CH2(绿)。
 * - 支持双指捏合缩放 X/Y 轴、单指拖动平移；
 * - autoFit() 一键自适应（X 显示全部数据，Y 跟随数据范围）；
 * - 默认跟随最新数据滚动显示最近 defaultSpan 个点。
 */
class CurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var ch1: IntArray = IntArray(0)
    private var ch2: IntArray = IntArray(0)
    private var showCh2 = true

    // 视口（数据索引范围 x，幅值范围 y）
    private var x0 = 0.0
    private var x1 = 500.0
    private var y0 = 0f
    private var y1 = 4096f
    private var followTail = true
    private val defaultSpan = 500.0

    private val paintCh1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCh2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        paintCh1.color = Color.rgb(230, 57, 70)
        paintCh1.strokeWidth = dpf(2f)
        paintCh1.style = Paint.Style.STROKE

        paintCh2.color = Color.rgb(42, 157, 143)
        paintCh2.strokeWidth = dpf(2f)
        paintCh2.style = Paint.Style.STROKE

        gridPaint.color = 0x1F000000.toInt()
        gridPaint.strokeWidth = dpf(1f)

        labelPaint.color = 0xFF666666.toInt()
        labelPaint.textSize = dpf(9f)
        labelPaint.isAntiAlias = true
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // 分轴缩放：水平方向缩放 X，垂直方向缩放 Y，对角则同时缩放
                val pf = d.previousSpan
                val cf = d.currentSpan
                if (pf <= 0f) return true
                val total = cf / pf
                if (total !in 0.5f..2f) return true
                val xf = if (d.previousSpanX > 0f) d.currentSpanX / d.previousSpanX else total
                val yf = if (d.previousSpanY > 0f) d.currentSpanY / d.previousSpanY else total
                zoomAt(d.focusX / width, d.focusY / height, xf.coerceIn(0.5f, 2f), yf.coerceIn(0.5f, 2f))
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // 波形跟随手指方向移动
                panBy(distanceX / width, distanceY / height)
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    /** 主线程调用。c1/c2 为各通道采样值（0~4096）。 */
    fun setData(c1: IntArray, c2: IntArray, show2: Boolean = true) {
        ch1 = c1
        ch2 = c2
        showCh2 = show2
        if (followTail) {
            val n = max(ch1.size, 1)
            x1 = (n - 1).toDouble()
            x0 = x1 - defaultSpan
            if (x0 < 0) x0 = 0.0
        }
        invalidate()
    }

    /** 一键自适应：X 显示全部数据，Y 自适应数据范围（含 10% 边距）。 */
    fun autoFit() {
        val n = ch1.size
        if (n == 0) {
            x0 = 0.0; x1 = 1.0; y0 = 0f; y1 = 4096f
        } else {
            var mn = ch1[0].toFloat()
            var mx = ch1[0].toFloat()
            for (v in ch1) { if (v < mn) mn = v.toFloat(); if (v > mx) mx = v.toFloat() }
            if (showCh2 && ch2.size == n) {
                for (v in ch2) { if (v < mn) mn = v.toFloat(); if (v > mx) mx = v.toFloat() }
            }
            val pad = (mx - mn) * 0.1f
            if (pad <= 0f) { y0 = mn - 10f; y1 = mx + 10f } else { y0 = mn - pad; y1 = mx + pad }
            x0 = 0.0
            x1 = (n - 1).toDouble()
        }
        followTail = true
        invalidate()
    }

    /** 以画布比例焦点 (nx,ny) 为中心，X 缩放 xf 倍、Y 缩放 yf 倍。 */
    private fun zoomAt(nx: Float, ny: Float, xf: Float, yf: Float) {
        if (width <= 0 || height <= 0) return
        val xc = x0 + nx * (x1 - x0)
        val yc = y0 + (1f - ny) * (y1 - y0)
        x0 = xc - (xc - x0) / xf
        x1 = xc + (x1 - xc) / xf
        y0 = yc - (yc - y0) / yf
        y1 = yc + (y1 - yc) / yf
        followTail = false
        invalidate()
    }

    /** 平移：dx/dy 为画布比例位移。 */
    private fun panBy(dx: Float, dy: Float) {
        val sx = dx * (x1 - x0)
        x0 -= sx; x1 -= sx
        val sy = dy * (y1 - y0)
        y0 += sy; y1 += sy
        followTail = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val w = width.toFloat()
        val h = height.toFloat()
        val spanX = (x1 - x0).let { if (it <= 0.0) 1.0 else it }
        val spanY = (y1 - y0).let { if (it <= 0f) 1f else it }

        // 网格 + Y 标签
        for (i in 0..4) {
            val yy = h - h * i / 4f
            canvas.drawLine(0f, yy, w, yy, gridPaint)
            val v = y0 + (y1 - y0) * i / 4f
            canvas.drawText("%.0f".format(v), dpf(4f), yy - dpf(3f), labelPaint)
        }
        canvas.drawText("%.0f".format(y0), dpf(4f), h - dpf(4f), labelPaint)

        drawSeries(canvas, ch1, paintCh1, w, h, spanX, spanY)
        if (showCh2) drawSeries(canvas, ch2, paintCh2, w, h, spanX, spanY)
    }

    private fun drawSeries(canvas: Canvas, data: IntArray, p: Paint, w: Float, h: Float, spanX: Double, spanY: Float) {
        if (data.isEmpty()) return
        val i0 = floor(x0).toInt().coerceIn(0, data.size - 1)
        val i1 = ceil(x1).toInt().coerceIn(0, data.size - 1)
        if (i1 < i0) return
        path.reset()
        var first = true
        for (i in i0..i1) {
            val px = ((i - x0) / spanX * w).toFloat()
            val v = data[i].toFloat().coerceIn(y0, y1)
            val py = h - (v - y0) / spanY * h
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
        }
        canvas.drawPath(path, p)
    }
}
