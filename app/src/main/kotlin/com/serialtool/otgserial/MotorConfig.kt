package com.serialtool.otgserial

import org.json.JSONObject

/**
 * 单个轴（ap/af1/af2）的行程配置
 */
data class AxisConfig(
    val enabled: Boolean,   // 该轴电机是否启用
    val start: Int,         // 行程起点（电机步）
    val end: Int,           // 行程终点（电机步）
    val stepLength: Double, // 单步长度（um）
    val threshold: Int      // 来回跑阈值（电机步）
)

/**
 * 电机型号 JSON 配置：解析、型号切换、单步换算、行程限制。
 * 内置加载自 assets/motor_config.json，也支持从文件导入。
 */
class MotorConfig {

    private val configs = LinkedHashMap<String, JSONObject>()
    var mode: String = ""
        private set

    val modelNames: List<String> get() = configs.keys.toList()

    /** 从 JSON 文本加载配置，成功返回 true */
    fun load(jsonText: String): Boolean {
        return try {
            val root = JSONObject(jsonText)
            mode = root.optString("mode", "")
            configs.clear()
            val motor = root.optJSONObject("MotorConfig") ?: return false
            val names = motor.keys()
            while (names.hasNext()) {
                val name = names.next()
                configs[name] = motor.getJSONObject(name)
            }
            if (mode !in configs) mode = configs.keys.firstOrNull() ?: ""
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setMode(name: String) {
        if (name in configs) mode = name
    }

    /** 获取某轴配置，无配置返回安全默认值 */
    fun axis(id: String): AxisConfig {
        val c = configs[mode] ?: return AxisConfig(false, 0, 0, 1.0, 1)
        return AxisConfig(
            c.optBoolean("${id}电机", false),
            c.optInt("${id}电机起点", 0),
            c.optInt("${id}电机终点", 0),
            c.optDouble("${id}电机单步长度", 1.0),
            c.optInt("${id}来回跑阈值", 1)
        )
    }

    /** um 换算为电机步数 */
    fun umToStep(um: Double, id: String): Int {
        val l = axis(id).stepLength
        return if (l <= 0.0) um.toInt() else (um / l).toInt()
    }

    /** 行程限制：把位置夹在起点与终点之间 */
    fun limit(pos: Int, id: String): Int {
        val a = axis(id)
        return when {
            pos < a.start -> a.start
            pos > a.end -> a.end
            else -> pos
        }
    }
}
