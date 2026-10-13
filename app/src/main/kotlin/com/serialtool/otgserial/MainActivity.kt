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
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tab 宿主：串口助手 / 电机控制 两个页签，共享同一串口实例。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var readThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ACTION_USB_PERMISSION = "com.serialtool.USB_PERMISSION"

    // 目标 CDC 设备 VID/PID（0x19F5 / 0x5740）
    private val targetVid = 0x19F5
    private val targetPid = 0x5740

    // 接收分发
    private val receiveListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val statusCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    private var serialFragment: SerialFragment? = null
    private var motorFragment: MotorControlFragment? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                unregisterReceiver(this)
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { openSerial(it) }
                } else {
                    notifyStatus("❌ USB权限被拒绝")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        checkLicense()
        setupTabs()
    }

    /**
     * 启动时联网检查作者维护状态。
     * 读取 https://www.cnblogs.com/cortana1/p/19510092 正文中的 STOP 标记：
     *   STOP:0 → 作者已停止维护，弹出"软件不可用"并退出；
     *   STOP:1 → 正常运行；
     *   网络失败 / 解析不到 → 弹出"网络异常，请联网使用"并退出。
     */
    private fun checkLicense() {
        Thread {
            val stop = try {
                val url = java.net.URL("https://www.cnblogs.com/cortana1/p/19510092")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Regex("STOP\\s*[:：]?\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (stop != null && stop == 1) {
                    // 作者维护中，正常运行
                    return@post
                }
                val title = if (stop == 0) "软件不可用" else "网络异常"
                val msg = if (stop == 0) "作者已停止维护，本软件不可用。" else "请联网使用本软件。"
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("确定") { _, _ -> finish() }
                    .show()
            }
        }.start()
    }

    private fun setupTabs() {
        val group = findViewById<RadioGroup>(R.id.tabGroup)
        if (serialFragment == null) {
            serialFragment = SerialFragment()
            motorFragment = MotorControlFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, serialFragment!!, "serial")
                .add(R.id.fragmentContainer, motorFragment!!, "motor")
                .hide(motorFragment!!)
                .commit()
        }
        group.setOnCheckedChangeListener { _, id ->
            if (id == R.id.tabMotor) showFragment(motorFragment!!) else showFragment(serialFragment!!)
        }
    }

    private fun showFragment(show: Fragment) {
        val hide = if (show === serialFragment) motorFragment else serialFragment
        supportFragmentManager.beginTransaction()
            .show(show)
            .hide(hide!!)
            .commit()
    }

    // ========== 串口 API（供 Fragment 使用） ==========

    fun isSerialOpen(): Boolean = serialPort?.isOpen ?: false

    fun openSerialFromFragment(): Boolean {
        if (serialPort?.isOpen == true) {
            notifyStatus("串口已打开")
            return true
        }
        val driver = findDriver() ?: run {
            notifyStatus("❌ 未检测USB串口设备，请检查OTG/CH340")
            return false
        }
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
            )
            registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            usbManager.requestPermission(device, pi)
            notifyStatus("⚠️ 请在弹窗允许USB权限")
            return false
        }
        return openSerial(device)
    }

    fun closeSerialFromFragment() {
        readThread?.interrupt()
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        notifyStatus("串口已关闭")
    }

    fun sendBytes(data: ByteArray) {
        if (serialPort?.isOpen != true) {
            notifyStatus("⚠️ 串口未打开")
            return
        }
        try {
            serialPort?.write(data, 100)
        } catch (e: Exception) {
            notifyStatus("❌ 发送失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun addReceiveListener(l: (ByteArray) -> Unit) = receiveListeners.add(l)
    fun removeReceiveListener(l: (ByteArray) -> Unit) = receiveListeners.remove(l)

    fun addStatusCallback(cb: (String) -> Unit) = statusCallbacks.add(cb)
    fun removeStatusCallback(cb: (String) -> Unit) = statusCallbacks.remove(cb)

    private fun notifyStatus(msg: String) {
        handler.post { statusCallbacks.forEach { it(msg) } }
    }

    // ========== 串口打开核心 ==========

    private fun findDriver(): UsbSerialDriver? {
        val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (defaultDrivers.isNotEmpty()) return defaultDrivers[0]
        for (dev in usbManager.deviceList.values) {
            if (dev.vendorId == targetVid && dev.productId == targetPid) {
                notifyStatus("⚠️ 默认探测未命中，强制使用 CdcAcmSerialDriver")
                return CdcAcmSerialDriver(dev)
            }
        }
        return null
    }

    private fun openSerial(device: UsbDevice): Boolean {
        val driver = findDriver() ?: return false
        val port = driver.ports[0]
        try {
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                notifyStatus("❌ openDevice返回null，设备可能被占用或已断开")
                return false
            }
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                port.setDTR(true)
                port.setRTS(true)
            } catch (ce: Exception) {
                notifyStatus("⚠️ 控制线设置提示：${ce.javaClass.simpleName}: ${ce.message}")
            }
            serialPort = port
            notifyStatus("✅ 串口打开成功（驱动 ${driver.javaClass.simpleName}）")
            startReadThread()
            return true
        } catch (e: Exception) {
            notifyStatus("❌ 打开失败：${e.javaClass.simpleName}: ${e.message}")
            try { port.close() } catch (_: Exception) {}
            return false
        }
    }

    private fun startReadThread() {
        readThread = Thread {
            val buf = ByteArray(1024)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val len = serialPort?.read(buf, 200) ?: -1
                    if (len > 0) {
                        val data = buf.copyOfRange(0, len)
                        handler.post { receiveListeners.forEach { it(data) } }
                    }
                } catch (e: IOException) {
                    break
                } catch (e: Exception) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                }
            }
        }
        readThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        readThread?.interrupt()
        try { serialPort?.close() } catch (_: Exception) {}
    }
}
