package com.serialtool.otgserial

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 双通道曲线显示弹窗。定时（40ms）从 dataSource 拉最新数据绘制；
 * 提供 暂停/清空/关闭 按钮。
 */
class CurveDialog(
    context: Context,
    private val title: String,
    private val dataSource: () -> Pair<IntArray, IntArray>?,
    private val onClear: () -> Unit
) : Dialog(context) {

    private val curveView = CurveView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var paused = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!paused) {
                dataSource()?.let { curveView.setData(it.first, it.second, true) }
            }
            handler.postDelayed(this, 40)
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    init {
        setTitle(title)
        setCanceledOnTouchOutside(false)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val tvTitle = TextView(context).apply {
            text = title
            textSize = 16f
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        root.addView(tvTitle)

        root.addView(curveView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340)))

        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPause = Button(context).apply { text = "暂停" }
        val btnClear = Button(context).apply { text = "清空" }
        val btnClose = Button(context).apply { text = "关闭" }
        bar.addView(btnPause)
        bar.addView(btnClear)
        bar.addView(btnClose)
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        btnPause.setOnClickListener {
            paused = !paused
            btnPause.text = if (paused) "继续" else "暂停"
        }
        btnClear.setOnClickListener { onClear() }
        btnClose.setOnClickListener { dismiss() }

        window?.setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.CENTER)

        handler.postDelayed(ticker, 40)
    }

    override fun dismiss() {
        handler.removeCallbacks(ticker)
        super.dismiss()
    }
}
