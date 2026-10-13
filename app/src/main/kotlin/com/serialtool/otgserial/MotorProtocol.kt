package com.serialtool.otgserial

import java.nio.charset.StandardCharsets

/**
 * 镜头电机驱动板串口协议。
 *
 * 帧格式（十六进制，5f 结尾）：
 *   简单命令:  55 <cmd> 5f                     如 55 0b 5f（查AP位置）
 *   带数据命令: 55 <cmd> <len> <payload> <crc> 5f
 * CRC = 对 body（cmd 到 payload 末尾）逐字节异或。
 *
 * 返回帧假设与发送同构：55 <cmd> <len> <payload> <crc> 5f，payload 为 ASCII 文本。
 */
object MotorProtocol {

    // ---- 简单命令 ----
    /** 查询位置：id: "0b"(ap) / "0c"(af1) / "11"(af2) */
    fun getPosFrame(id: String): ByteArray = hex("55${id}5f")

    /** 复位（ap/af1/af2 通用） */
    fun resetFrame(): ByteArray = hex("550f5f")

    /** 获取配置：axis "00"(ap) / "01"(af1) / "02"(af2) */
    fun getConfigFrame(axis: String): ByteArray = hex("551b${axis}5f")

    /** 速度曲线开/关：open=true→551a015f, false→551a005f */
    fun curveFrame(open: Boolean): ByteArray = hex(if (open) "551a015f" else "551a005f")

    /** 查询 MCU 版本 */
    fun versionFrame(): ByteArray = hex("55015f")

    // ---- 带数据命令 ----

    /**
     * 设置电机目标位置 + 速度。
     * @param id "13"(ap) / "14"(af1) / "15"(af2)
     * 帧: 55 <id> <步长> <步ascii> <速长> <速ascii> <crc> 5f
     */
    fun setMotorStep(id: String, step: Int, speed: Int): ByteArray {
        val stepHex = step.toString().toByteArray(StandardCharsets.US_ASCII).toHex()
        val speedHex = speed.toString().toByteArray(StandardCharsets.US_ASCII).toHex()
        val stepLen = formatLen(stepHex.length / 2)
        val speedLen = formatLen(speedHex.length / 2)
        val body = id + stepLen + stepHex + speedLen + speedHex
        val crc = xorCrc(body)
        return hex("55" + body + formatLen(crc) + "5f")
    }

    /**
     * 设置电机参数（启动速度/加速度/减速度/停止速度）。
     * @param motorId "00"(ap) / "01"(af1) / "02"(af2)
     * 帧: 55 1c <len> <"电机id#启速#加速#减速#停速"> <crc> 5f
     */
    fun setMotorParam(motorId: String, startSpeed: Int, acc: Int, dec: Int, stopSpeed: Int): ByteArray {
        val payload = "$motorId#$startSpeed#$acc#$dec#$stopSpeed"
            .toByteArray(StandardCharsets.US_ASCII)
        val body = "1c" + formatLen(payload.size) + payload.toHex()
        val crc = xorCrc(body)
        return hex("55" + body + formatLen(crc) + "5f")
    }

    // ---- 返回帧解析 ----

    data class Frame(val cmd: Int, val payload: ByteArray)

    private fun xorCrc(bodyHex: String): Int {
        var crc = 0
        var i = 0
        while (i + 2 <= bodyHex.length) {
            crc = crc xor Integer.parseInt(bodyHex.substring(i, i + 2), 16)
            i += 2
        }
        return crc
    }

    private fun formatLen(v: Int): String = "%02x".format(v and 0xff)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            out[i / 2] = Integer.parseInt(s.substring(i, i + 2), 16).toByte()
            i += 2
        }
        return out
    }

    /**
     * 从累积缓冲中提取完整帧（55 ... 5f）。
     * @param buffer 可变缓冲，解析后已消费的字节会被移除
     * @return 提取到的帧列表
     */
    fun extractFrames(buffer: MutableList<Byte>): List<Frame> {
        val frames = ArrayList<Frame>()
        while (true) {
            // 找帧头 55
            val start = indexOf(buffer, 0x55)
            if (start < 0) { buffer.clear(); break }
            if (start > 0) {
                repeat(start) { buffer.removeAt(0) }
            }
            // 找帧尾 5f
            val end = indexOf(buffer, 0x5f)
            if (end < 0) break // 半帧，等待更多数据
            // 完整帧: buffer[0..end]
            val frame = buffer.subList(0, end + 1).toList().toByteArray()
            if (frame.size >= 3) {
                val cmd = frame[1].toInt() and 0xff
                val payload = if (frame.size >= 5) {
                    val len = frame[2].toInt() and 0xff
                    if (len > 0 && 3 + len <= frame.size - 2) {
                        frame.copyOfRange(3, 3 + len)
                    } else {
                        // len 不可信，取 3 到 倒数第二（跳过 crc）
                        frame.copyOfRange(3, frame.size - 2)
                    }
                } else {
                    ByteArray(0)
                }
                frames.add(Frame(cmd, payload))
            }
            repeat(end + 1) { buffer.removeAt(0) }
        }
        return frames
    }

    private fun indexOf(list: List<Byte>, value: Int): Int {
        for (i in list.indices) if ((list[i].toInt() and 0xff) == value) return i
        return -1
    }

    /** payload 按 ASCII 解码 */
    fun payloadAscii(payload: ByteArray): String =
        String(payload, StandardCharsets.US_ASCII)
}
