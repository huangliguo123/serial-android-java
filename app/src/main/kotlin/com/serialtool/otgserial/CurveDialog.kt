package com.serialtool.otgserial

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * 双通道曲线显示弹窗。
 * - 40ms 定时从 dataSource 拉最新数据绘制；
 * - 曲线支持手势缩放/平移，[自适应] 一键显示合适区域；
 * - 内置电机快捷控制（选轴、设置位置+速度、复位），可边看波形边控电机。
 */
class CurveDialog(
    context: Context,
    private val title: String,
    private val dataSource: () -> Pair<IntArray, IntArray>?,
    private val onClear: () -> Unit,
    private val onSetPos: (String, Int, Int) -> Unit,
    private val onReset: (String) -> Unit,
    private val onRecur: (String, Int, Int, Int) -> Unit
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
            text = "$title（双指缩放 / 拖动平移）"
            textSize = 15f
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        root.addView(tvTitle)

        root.addView(curveView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))

        // ---- 控制按钮行 ----
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btnFit = Button(context).apply { text = "自适应" }
        val btnPause = Button(context).apply { text = "暂停" }
        val btnClear = Button(context).apply { text = "清空" }
        val btnClose = Button(context).apply { text = "关闭" }
        bar.addView(btnFit)
        bar.addView(btnPause)
        bar.addView(btnClear)
        bar.addView(btnClose)
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT))

        btnFit.setOnClickListener { curveView.autoFit() }
        btnPause.setOnClickListener {
            paused = !paused
            btnPause.text = if (paused) "继续" else "暂停"
        }
        btnClear.setOnClickListener { onClear() }
        btnClose.setOnClickListener { dismiss() }

        // ---- 电机快捷控制区 ----
        val motorLabel = TextView(context).apply {
            text = "电机控制（边看波形边作动）"
            textSize = 14f
            setPadding(dp(2), dp(10), dp(2), dp(4))
        }
        root.addView(motorLabel)

        // 行1：轴 + 位置 + 速度 + 步距
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val spAxis = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, arrayOf("AP", "AF1", "AF2"))
        }
        val edtPos = EditText(context).apply {
            hint = "位置(um)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 14f
        }
        val edtSpeed = EditText(context).apply {
            hint = "速度"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
        }
        val edtRecurStep = EditText(context).apply {
            hint = "往复步距(um)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
        }
        row1.addView(spAxis, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(edtPos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(edtSpeed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(edtRecurStep, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT))

        // 行2：设置位置 + 复位 + 往复运动
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSetPos = Button(context).apply { text = "设置位置" }
        val btnReset = Button(context).apply { text = "复位" }
        val btnRecur = Button(context).apply { text = "往复运动" }
        row2.addView(btnSetPos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(btnReset, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(btnRecur, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT))

        btnSetPos.setOnClickListener {
            val axis = spAxis.selectedItem?.toString() ?: "AP"
            val pos = edtPos.text.toString().toIntOrNull() ?: 0
            val speed = edtSpeed.text.toString().toIntOrNull() ?: 800
            onSetPos(axis, pos, speed)
        }
        btnReset.setOnClickListener {
            val axis = spAxis.selectedItem?.toString() ?: "AP"
            onReset(axis)
        }
        btnRecur.setOnClickListener {
            val axis = spAxis.selectedItem?.toString() ?: "AP"
            val center = edtPos.text.toString().toIntOrNull() ?: 0
            val step = edtRecurStep.text.toString().toIntOrNull() ?: 0
            val speed = edtSpeed.text.toString().toIntOrNull() ?: 800
            onRecur(axis, center, step, speed)
            btnRecur.text = if (btnRecur.text == "往复运动") "停止" else "往复运动"
        }

        setContentView(root)

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
