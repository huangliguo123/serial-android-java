package com.serialtool.otgserial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var readThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ACTION_USB_PERMISSION = "com.serialtool.USB_PERMISSION"

    private lateinit var spBaud: Spinner
    private lateinit var chkRecvHex: CheckBox
    private lateinit var chkSendHex: CheckBox
    private lateinit var etReceive: EditText
    private lateinit var etSend: EditText
    private lateinit var btnOpen: Button
    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var btnClose: Button

    // USB 权限授权结果回调
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                unregisterReceiver(this)
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { openSerial(it) }
                } else {
                    appendLog("❌ USB权限被拒绝")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        spBaud = findViewById(R.id.spBaud)
        chkRecvHex = findViewById(R.id.chkRecvHex)
        chkSendHex = findViewById(R.id.chkSendHex)
        etReceive = findViewById(R.id.etReceive)
        etSend = findViewById(R.id.etSend)
        btnOpen = findViewById(R.id.btnOpen)
        btnSend = findViewById(R.id.btnSend)
        btnClear = findViewById(R.id.btnClear)
        btnClose = findViewById(R.id.btnClose)

        // 波特率下拉
        val baudList = listOf("9600", "19200", "38400", "115200")
        spBaud.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baudList)
        spBaud.setSelection(3) // 默认 115200

        btnOpen.setOnClickListener { scanAndRequestDevice() }
        btnClose.setOnClickListener { closeSerial() }
        btnClear.setOnClickListener { etReceive.text.clear() }
        btnSend.setOnClickListener { sendData() }
    }

    // 扫描 USB 串口设备并申请权限
    private fun scanAndRequestDevice() {
        val availableDrivers: List<UsbSerialDriver> =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            appendLog("❌ 未检测USB串口设备，请检查OTG/CH340")
            return
        }
        val driver = availableDrivers[0]
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            val pi: PendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
            )
            registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            usbManager.requestPermission(device, pi)
            appendLog("⚠️ 请允许USB权限弹窗")
        } else {
            openSerial(device)
        }
    }

    // 打开串口并启动接收线程
    private fun openSerial(device: UsbDevice) {
        val driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            appendLog("❌ 找不到串口驱动")
            return
        }
        val port = driver.ports[0]
        try {
            val connection = usbManager.openDevice(driver.device)
            port.open(connection)
            val baud = spBaud.selectedItem.toString().toInt()
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            appendLog("✅ 串口打开成功，波特率 $baud")

            // 接收线程
            readThread = Thread {
                val buf = ByteArray(256)
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val len = serialPort?.read(buf, 100) ?: 0
                        if (len > 0) {
                            val data = buf.copyOfRange(0, len)
                            handler.post {
                                if (chkRecvHex.isChecked) {
                                    appendLog(bytesToHex(data))
                                } else {
                                    appendLog(String(data, StandardCharsets.UTF_8))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            readThread?.start()
        } catch (e: Exception) {
            appendLog("❌ 打开失败：${e.message}")
        }
    }

    private fun closeSerial() {
        readThread?.interrupt()
        try {
            serialPort?.close()
        } catch (_: Exception) {
        }
        serialPort = null
        appendLog("✅ 串口已关闭")
    }

    private fun sendData() {
        val text = etSend.text.toString().trim()
        if (text.isEmpty()) return
        if (serialPort == null) {
            appendLog("⚠️ 串口未打开")
            return
        }
        val payload: ByteArray
        if (chkSendHex.isChecked) {
            payload = hexToBytes(text) ?: run {
                appendLog("❌ HEX格式错误，示例 01 02 03")
                return
            }
            appendLog(">>> HEX: ${bytesToHex(payload)}")
        } else {
            payload = text.toByteArray(StandardCharsets.UTF_8)
            appendLog(">>> TXT: $text")
        }
        try {
            serialPort?.write(payload, 100)
        } catch (e: Exception) {
            appendLog("❌ 发送失败：${e.message}")
        }
    }

    private fun appendLog(msg: String) {
        etReceive.append("$msg\n")
        val layout = etReceive.layout ?: return
        val scrollY = layout.getLineTop(layout.lineCount - 1)
        etReceive.scrollTo(0, scrollY)
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

    override fun onDestroy() {
        super.onDestroy()
        closeSerial()
    }
}
