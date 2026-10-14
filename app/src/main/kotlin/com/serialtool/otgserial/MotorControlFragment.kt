package com.serialtool.otgserial

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电机控制页：型号配置 / 获取下发配置 / 三轴位置设置复位 / 往复运动 / 步距 / JSON 配置导入。
 */
class MotorControlFragment : Fragment() {

    private val motorConfig = MotorConfig()
    private val frameBuffer = Collections.synchronizedList(ArrayList<Byte>())
    private val positions = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val recurFlag = LinkedHashMap<String, Int>()      // 1 正向, 2 反向
    private val recurDelayCount = LinkedHashMap<String, Int>()
    private val recurRunning = AtomicBoolean(false)
    private var recurThread: Thread? = null

    private var visible = false
    private val receiveListener: (ByteArray) -> Unit = { onReceive(it) }
    private val statusCb: (String) -> Unit = { log(it) }

    // 控件
    private lateinit var spModel: Spinner
    private lateinit var spConfigAxis: Spinner
    private lateinit var edtRecurDelay: EditText
    private lateinit var edtAccCur: EditText
    private lateinit var edtDecCur: EditText
    private lateinit var edtStartCur: EditText
    private lateinit var edtStopCur: EditText
    private lateinit var edtSetAcc: EditText
    private lateinit var edtSetDec: EditText
    private lateinit var edtSetStart: EditText
    private lateinit var edtSetStop: EditText
    private lateinit var logView: EditText

    private val axes = LinkedHashMap<String, AxisUi>()

    // 曲线数据缓冲（1a=速度曲线双通道, 1c=传感器曲线单通道）
    private val curveBuf1 = ArrayList<Int>()
    private val curveBuf2 = ArrayList<Int>()
    private val curveMaxPoints = 4000
    private var curveDialog: CurveDialog? = null

    class AxisUi(
        val title: TextView,
        val tvPos: EditText,
        val edtSetPos: EditText,
        val edtSpeed: EditText,
        val edtStepUm: EditText,
        val btnGetPos: Button,
        val btnSetPos: Button,
        val btnReset: Button,
        val btnRecur: Button,
        val btnStepPlus: Button,
        val btnStepMinus: Button
    )

    // 各轴参数: key -> (显示名, 设置步id, 查询位置id, 配置轴id)
    data class AxisParam(val display: String, val setStep: String, val getPos: String, val config: String)

    private val axisParams = mapOf(
        "ap"  to AxisParam("AP", "13", "0b", "00"),
        "af1" to AxisParam("AF1", "14", "0c", "01"),
        "af2" to AxisParam("AF2", "15", "11", "02")
    )

    private fun mainActivity(): MainActivity = activity as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_motor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindControls(view)
        loadConfig()
        bindActions()
        refreshAxisEnabled()
    }

    private fun bindControls(view: View) {
        spModel = view.findViewById(R.id.spModel)
        spConfigAxis = view.findViewById(R.id.spConfigAxis)
        edtRecurDelay = view.findViewById(R.id.edtRecurDelay)
        edtAccCur = view.findViewById(R.id.edtAccCur)
        edtDecCur = view.findViewById(R.id.edtDecCur)
        edtStartCur = view.findViewById(R.id.edtStartCur)
        edtStopCur = view.findViewById(R.id.edtStopCur)
        edtSetAcc = view.findViewById(R.id.edtSetAcc)
        edtSetDec = view.findViewById(R.id.edtSetDec)
        edtSetStart = view.findViewById(R.id.edtSetStart)
        edtSetStop = view.findViewById(R.id.edtSetStop)
        logView = view.findViewById(R.id.edtLog)

        // 绑定三个轴
        bindAxis(view, "ap", R.id.ap_card)
        bindAxis(view, "af1", R.id.af1_card)
        bindAxis(view, "af2", R.id.af2_card)
    }

    private fun bindAxis(root: View, key: String, cardId: Int) {
        val card = root.findViewById<ViewGroup>(cardId)
        val p = axisParams[key]!!
        val ui = AxisUi(
            card.findViewById(R.id.tvAxisTitle),
            card.findViewById(R.id.tvAxisPos),
            card.findViewById(R.id.edtSetPos),
            card.findViewById(R.id.edtSpeed),
            card.findViewById(R.id.edtStepUm),
            card.findViewById(R.id.btnGetPos),
            card.findViewById(R.id.btnSetPos),
            card.findViewById(R.id.btnReset),
            card.findViewById(R.id.btnRecur),
            card.findViewById(R.id.btnStepPlus),
            card.findViewById(R.id.btnStepMinus)
        )
        ui.title.text = p.display
        ui.edtSpeed.setText("800")
        ui.edtStepUm.setText("1000")
        axes[key] = ui
    }

    private fun loadConfig() {
        // 内置配置
        var ok = try {
            val text = requireContext().assets.open("motor_config.json").bufferedReader().readText()
            motorConfig.load(text)
        } catch (e: Exception) {
            false
        }
        if (!ok) motorConfig.load("{\"mode\":\"058\",\"MotorConfig\":{}}")
        refreshModelSpinner()
    }

    private fun refreshModelSpinner() {
        val names = motorConfig.modelNames
        spModel.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        if (names.isNotEmpty()) {
            val idx = names.indexOf(motorConfig.mode).let { if (it < 0) 0 else it }
            spModel.setSelection(idx)
        }
    }

    private fun bindActions() {
        spModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                motorConfig.setMode(motorConfig.modelNames[pos])
                refreshAxisEnabled()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spConfigAxis.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, listOf("AP", "AF1", "AF2")
        )

        view?.findViewById<Button>(R.id.btnGetConfig)?.setOnClickListener { sendGetConfig() }
        view?.findViewById<Button>(R.id.btnSetConfig)?.setOnClickListener { sendSetConfig() }
        view?.findViewById<Button>(R.id.btnImportJson)?.setOnClickListener { openImport() }
        view?.findViewById<Button>(R.id.btnSpeedCurve)?.setOnClickListener { toggleCurve("speed") }
        view?.findViewById<Button>(R.id.btnSensorCurve)?.setOnClickListener { toggleCurve("sensor") }

        axes.forEach { (key, ui) ->
            val p = axisParams[key]!!
            ui.btnGetPos.setOnClickListener { sendGetPos(key) }
            ui.btnSetPos.setOnClickListener { sendSetPos(key) }
            ui.btnReset.setOnClickListener { mainActivity().sendBytes(MotorProtocol.resetFrame()); log("${p.display} 复位") }
            ui.btnStepPlus.setOnClickListener { stepPos(key, 1) }
            ui.btnStepMinus.setOnClickListener { stepPos(key, -1) }
            ui.btnRecur.setOnClickListener { toggleRecur(key, ui) }
        }
    }

    private fun refreshAxisEnabled() {
        axes.forEach { (key, ui) ->
            val en = motorConfig.axis(key).enabled
            val act = listOf(ui.btnGetPos, ui.btnSetPos, ui.btnReset, ui.btnRecur, ui.btnStepPlus, ui.btnStepMinus)
            act.forEach { it.isEnabled = true } // 获取位置始终可用
        }
    }

    private fun cfgAxisKey(): String {
        val name = spConfigAxis.selectedItem?.toString() ?: "AP"
        return axisParams.entries.firstOrNull { it.value.display == name }?.key ?: "ap"
    }

    private fun sendGetConfig() {
        val key = cfgAxisKey()
        val axis = axisParams[key]!!.config
        mainActivity().sendBytes(MotorProtocol.getConfigFrame(axis))
        log("获取 ${axisParams[key]!!.display} 配置")
    }

    private fun sendSetConfig() {
        val key = cfgAxisKey()
        val motorId = axisParams[key]!!.config
        val start = edtSetStart.text.toString().toIntOrNull() ?: 400
        val acc = edtSetAcc.text.toString().toIntOrNull() ?: 400
        val dec = edtSetDec.text.toString().toIntOrNull() ?: 400
        val stop = edtSetStop.text.toString().toIntOrNull() ?: 400
        mainActivity().sendBytes(MotorProtocol.setMotorParam(motorId, start, acc, dec, stop))
        log("下发 ${axisParams[key]!!.display} 配置 启速$start 加速$acc 减速$dec 停速$stop")
        // 下发后自动回读配置确认（与PC工具一致）
        mainActivity().sendBytes(MotorProtocol.getConfigFrame(motorId))
        log("已请求回读配置")
    }

    private fun sendGetPos(key: String) {
        val posId = axisParams[key]!!.getPos
        mainActivity().sendBytes(MotorProtocol.getPosFrame(posId))
    }

    private fun sendSetPos(key: String) {
        val ui = axes[key] ?: return
        val p = axisParams[key]!!
        val raw = ui.edtSetPos.text.toString().toIntOrNull() ?: return
        val limited = motorConfig.limit(raw, key)
        val speed = ui.edtSpeed.text.toString().toIntOrNull() ?: 800
        ui.edtSetPos.setText(limited.toString())
        mainActivity().sendBytes(MotorProtocol.setMotorStep(p.setStep, limited, speed))
        log("${p.display} 设置位置 $limited 速度 $speed")
    }

    private fun stepPos(key: String, dir: Int) {
        val ui = axes[key] ?: return
        val p = axisParams[key]!!
        val um = ui.edtStepUm.text.toString().toDoubleOrNull() ?: 0.0
        val step = motorConfig.umToStep(um, key)
        val cur = ui.edtSetPos.text.toString().toIntOrNull() ?: 0
        val newPos = motorConfig.limit(cur + dir * step, key)
        ui.edtSetPos.setText(newPos.toString())
        val speed = ui.edtSpeed.text.toString().toIntOrNull() ?: 800
        mainActivity().sendBytes(MotorProtocol.setMotorStep(p.setStep, newPos, speed))
        log("${p.display} ${if (dir > 0) "+" else "-"}${step} 步 → $newPos")
    }

    private fun toggleRecur(key: String, ui: AxisUi) {
        val running = recurRunning.get()
        if (running) {
            recurRunning.set(false)
            recurThread?.interrupt()
            recurThread = null
            ui.btnRecur.text = "往复运动"
            log("${axisParams[key]!!.display} 往复已停止")
        } else {
            recurFlag[key] = 1
            recurDelayCount[key] = 0
            recurRunning.set(true)
            val speed = ui.edtSpeed.text.toString().toIntOrNull() ?: 800
            ui.btnRecur.text = "停止"
            log("${axisParams[key]!!.display} 往复启动 速度$speed")
            startRecurThread(key, ui, speed)
        }
    }

    private fun startRecurThread(key: String, ui: AxisUi, speed: Int) {
        recurThread = Thread {
            val p = axisParams[key]!!
            while (recurRunning.get()) {
                // 查询当前位置
                mainActivity().sendBytes(MotorProtocol.getPosFrame(p.getPos))
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                doRecurStep(key, ui, speed, p.setStep)
            }
        }
        recurThread?.start()
    }

    private fun doRecurStep(key: String, ui: AxisUi, speed: Int, stepId: String) {
        val target = ui.edtSetPos.text.toString().toIntOrNull() ?: return
        val cur = positions[key] ?: target
        val step = motorConfig.umToStep(ui.edtStepUm.text.toString().toDoubleOrNull() ?: 0.0, key)
        val threshold = motorConfig.axis(key).threshold
        val delayMs = edtRecurDelay.text.toString().toIntOrNull() ?: 500
        val flag = recurFlag[key] ?: 1
        val absDiff = Math.abs(target - cur)

        if (absDiff <= threshold) {
            recurDelayCount[key] = (recurDelayCount[key] ?: 0) + 1
            if ((recurDelayCount[key] ?: 0) * 100 >= delayMs) {
                recurDelayCount[key] = 0
                if (flag == 1) {
                    recurFlag[key] = 2
                    val np = motorConfig.limit(target - step, key)
                    updateSetPosOnUi(ui, np)
                    mainActivity().sendBytes(MotorProtocol.setMotorStep(stepId, np, speed))
                } else {
                    recurFlag[key] = 1
                    val np = motorConfig.limit(target + step, key)
                    updateSetPosOnUi(ui, np)
                    mainActivity().sendBytes(MotorProtocol.setMotorStep(stepId, np, speed))
                }
            }
        } else if (absDiff >= step) {
            mainActivity().sendBytes(MotorProtocol.setMotorStep(stepId, target, speed))
        }
    }

    private fun updateSetPosOnUi(ui: AxisUi, value: Int) {
        view?.post { ui.edtSetPos.setText(value.toString()) }
    }

    // ========== 接收解析 ==========

    private fun onReceive(data: ByteArray) {
        synchronized(frameBuffer) { frameBuffer.addAll(data.toList()) }
        val frames = MotorProtocol.extractFrames(frameBuffer)
        if (frames.isEmpty()) return
        view?.post {
            for (f in frames) handleFrame(f)
        }
    }

    private fun handleFrame(f: MotorProtocol.Frame) {
        when (f.cmd) {
            0x0b -> positions["ap"] = asciiInt(f.payload).also { axes["ap"]?.tvPos?.setText(it.toString()) }
            0x0c -> positions["af1"] = asciiInt(f.payload).also { axes["af1"]?.tvPos?.setText(it.toString()) }
            0x11 -> positions["af2"] = asciiInt(f.payload).also { axes["af2"]?.tvPos?.setText(it.toString()) }
            0x1b -> parseConfig(f)
            0x16 -> log("AP 配置CRC: ${MotorProtocol.payloadAscii(f.payload)}")
            0x17 -> log("AF1 配置CRC: ${MotorProtocol.payloadAscii(f.payload)}")
            0x18 -> log("AF2 配置CRC: ${MotorProtocol.payloadAscii(f.payload)}")
            0x01 -> log("MCU版本: ${MotorProtocol.payloadAscii(f.payload)}")
            0x1a -> appendCurve(f.payload, dual = true)   // 速度曲线：每2值一对 ch1/ch2
            0x1c -> appendCurve(f.payload, dual = false)  // 传感器曲线：单通道，ch2 复制 ch1
        }
    }

    private fun parseConfig(f: MotorProtocol.Frame) {
        val parts = MotorProtocol.payloadAscii(f.payload).split("#")
        if (parts.size >= 4) {
            // 顺序: 启动速度, 加速度, 减速度, 停止速度
            edtStartCur.setText(parts[0])
            edtAccCur.setText(parts[1])
            edtDecCur.setText(parts[2])
            edtStopCur.setText(parts[3])
            log("已加载配置: 启速${parts[0]} 加速${parts[1]} 减速${parts[2]} 停速${parts[3]}")
        }
    }

    private fun asciiInt(payload: ByteArray): Int =
        MotorProtocol.payloadAscii(payload).trim().toIntOrNull() ?: 0

    // ========== 曲线数据 ==========

    /** 解析曲线 payload：每 2 字节一个采样（16 位大端，0~4096）。 */
    private fun appendCurve(payload: ByteArray, dual: Boolean) {
        val vals = ArrayList<Int>()
        var i = 0
        while (i + 1 < payload.size) {
            val v = ((payload[i].toInt() and 0xff) shl 8) or (payload[i + 1].toInt() and 0xff)
            vals.add(v)
            i += 2
        }
        if (dual) {
            // 每 2 值一对 ch1/ch2
            var k = 0
            while (k + 1 < vals.size) {
                curveBuf1.add(vals[k])
                curveBuf2.add(vals[k + 1])
                k += 2
            }
        } else {
            // 单通道：ch1 取值，ch2 复制上一个（保持长度一致）
            for (v in vals) {
                curveBuf1.add(v)
                curveBuf2.add(curveBuf2.lastOrNull() ?: v)
            }
        }
        // 限制长度，保留最新
        while (curveBuf1.size > curveMaxPoints) curveBuf1.removeAt(0)
        while (curveBuf2.size > curveMaxPoints) curveBuf2.removeAt(0)
    }

    private fun curveData(): Pair<IntArray, IntArray>? {
        if (curveBuf1.isEmpty()) return null
        return Pair(curveBuf1.toIntArray(), curveBuf2.toIntArray())
    }

    /** 速度曲线/传感器曲线按钮：启动采集并弹窗显示；再点关闭。 */
    private fun toggleCurve(type: String) {
        val cur = curveDialog
        if (cur != null && cur.isShowing) {
            mainActivity().sendBytes(MotorProtocol.curveFrame(false))
            cur.dismiss()
            curveDialog = null
            curveBuf1.clear()
            curveBuf2.clear()
            log("${if (type == "speed") "速度" else "传感器"}曲线已停止")
            return
        }
        curveBuf1.clear()
        curveBuf2.clear()
        mainActivity().sendBytes(MotorProtocol.curveFrame(true))
        val title = if (type == "speed") "速度曲线" else "传感器曲线"
        curveDialog = CurveDialog(requireContext(), title, { curveData() }, {
            curveBuf1.clear(); curveBuf2.clear()
        })
        curveDialog?.show()
        log("$title 已启动")
    }

    private fun log(msg: String) {
        view?.post { logView.append("$msg\n") }
    }

    // ========== JSON 导入 ==========

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: return@registerForActivityResult
                if (motorConfig.load(text)) {
                    refreshModelSpinner()
                    log("✅ 配置导入成功，型号: ${motorConfig.modelNames.joinToString()}")
                } else {
                    log("❌ 配置文件格式错误")
                }
            } catch (e: Exception) {
                log("❌ 导入失败: ${e.message}")
            }
        }
    }

    private fun openImport() {
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    override fun onResume() {
        super.onResume()
        visible = true
        mainActivity().addReceiveListener(receiveListener)
        mainActivity().addStatusCallback(statusCb)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        recurRunning.set(false)
        recurThread?.interrupt()
        recurThread = null
        curveDialog?.dismiss()
        curveDialog = null
        curveBuf1.clear()
        curveBuf2.clear()
        mainActivity().removeReceiveListener(receiveListener)
        mainActivity().removeStatusCallback(statusCb)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recurRunning.set(false)
        recurThread?.interrupt()
        recurThread = null
        curveDialog?.dismiss()
        curveDialog = null
    }
}
