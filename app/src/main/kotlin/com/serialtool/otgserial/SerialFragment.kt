package com.serialtool.otgserial

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import androidx.fragment.app.Fragment
import java.nio.charset.StandardCharsets

/**
 * 串口助手页：波特率 / HEX 收发 / 收发日志。
 */
class SerialFragment : Fragment() {

    private lateinit var spBaud: Spinner
    private lateinit var chkRecvHex: CheckBox
    private lateinit var chkSendHex: CheckBox
    private lateinit var etReceive: EditText
    private lateinit var etSend: EditText

    private var visible = false
    private val receiveListener: (ByteArray) -> Unit = { data -> onReceive(data) }
    private val statusCb: (String) -> Unit = { msg -> appendLog(msg) }

    private fun mainActivity(): MainActivity = activity as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_serial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spBaud = view.findViewById(R.id.spBaud)
        chkRecvHex = view.findViewById(R.id.chkRecvHex)
        chkSendHex = view.findViewById(R.id.chkSendHex)
        etReceive = view.findViewById(R.id.etReceive)
        etSend = view.findViewById(R.id.etSend)

        spBaud.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            listOf("9600", "19200", "38400", "115200")
        )
        spBaud.setSelection(3)

        view.findViewById<Button>(R.id.btnOpen).setOnClickListener { mainActivity().openSerialFromFragment() }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { mainActivity().closeSerialFromFragment() }
        view.findViewById<Button>(R.id.btnClear).setOnClickListener { etReceive.text.clear() }
        view.findViewById<Button>(R.id.btnSend).setOnClickListener { sendData() }
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
        mainActivity().removeReceiveListener(receiveListener)
        mainActivity().removeStatusCallback(statusCb)
    }

    /** Tab 切换时同步前台状态（show/hide 不触发 onResume/onPause）。 */
    fun setShown(shown: Boolean) {
        visible = shown
    }

    private fun onReceive(data: ByteArray) {
        if (!visible) return
        val disp = if (chkRecvHex.isChecked) bytesToHex(data)
                   else String(data, StandardCharsets.UTF_8)
        etReceive.append(disp)
        etReceive.post { etReceive.setSelection(etReceive.text.length) }
    }

    private fun sendData() {
        val text = etSend.text.toString().trim()
        if (text.isEmpty()) return
        val payload: ByteArray = if (chkSendHex.isChecked) {
            hexToBytes(text) ?: run {
                appendLog("❌ HEX格式错误，示例 01 02 03")
                return
            }
        } else {
            text.toByteArray(StandardCharsets.UTF_8)
        }
        mainActivity().sendBytes(payload)
    }

    private fun appendLog(msg: String) {
        if (!visible) return
        etReceive.append("$msg\n")
        etReceive.post { etReceive.setSelection(etReceive.text.length) }
    }

    private fun bytesToHex(data: ByteArray): String =
        data.joinToString(" ") { String.format("%02X", it) }

    private fun hexToBytes(s: String): ByteArray? = try {
        s.split(" ")
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
    } catch (_: Exception) {
        null
    }
}
