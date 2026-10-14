package com.serialtool.otgserial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 双通道实时曲线 View：CH1(红) / CH2(绿)，Y 轴 0~4096，网格背景。
 * 数据由外部 setData 喂入（主线程调用），内部只负责绘制。
 */
class CurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var ch1: IntArray = IntArray(0)
    private var ch2: IntArray = IntArray(0)
    private var showCh2 = true
    private val yMin = 0
    private val yMax = 4096

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

        labelPaint.color = 0xFF888888.toInt()
        labelPaint.textSize = dpf(9f)
        labelPaint.isAntiAlias = true
    }

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    /** 主线程调用。c1/c2 为各通道采样值（0~4096）。 */
    fun setData(c1: IntArray, c2: IntArray, show2: Boolean = true) {
        ch1 = c1
        ch2 = c2
        showCh2 = show2
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val w = width.toFloat()
        val h = height.toFloat()

        // 横向网格
        for (i in 0..4) {
            val y = h - h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        // Y 标签
        canvas.drawText("$yMax", dpf(4f), dpf(11f), labelPaint)
        canvas.drawText("$yMin", dpf(4f), h - dpf(4f), labelPaint)

        drawSeries(canvas, ch1, paintCh1, w, h)
        if (showCh2) drawSeries(canvas, ch2, paintCh2, w, h)
    }

    private fun drawSeries(canvas: Canvas, data: IntArray, p: Paint, w: Float, h: Float) {
        if (data.isEmpty()) return
        val n = data.size
        val xStep = w / (n - 1).coerceAtLeast(1).toFloat()
        val span = (yMax - yMin).toFloat()
        path.reset()
        var first = true
        for (i in 0 until n) {
            val x = i * xStep
            val v = data[i].toFloat().coerceIn(yMin.toFloat(), yMax.toFloat())
            val y = h - (v - yMin) / span * h
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, p)
    }
}
