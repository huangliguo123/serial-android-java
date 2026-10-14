# 唯卓仕镜头电机控制上位机（serial-android-java）

基于 **Kotlin + usb-serial-for-android** 的安卓 OTG 串口调试工具，用于通过 USB 串口控制镜头电机驱动板。

主要包含两大功能页（Tab）：
- **串口助手**：USB OTG 串口收发，支持文本 / HEX 收发、波特率选择、收发日志。
- **电机控制**：AP / AF1 / AF2 三轴镜头电机控制（设置位置 / 速度 / 往复运动 / 步距微调 / 复位），支持按镜头型号的行程限制，可导入 JSON 配置；并内置速度曲线 / 传感器曲线实时显示。

---

## 目录

- [功能特性](#功能特性)
- [支持的设备](#支持的设备)
- [界面说明](#界面说明)
- [电机控制与配置](#电机控制与配置)
- [串口协议](#串口协议)
- [速度曲线 / 传感器曲线](#速度曲线--传感器曲线)
- [应用授权检查](#应用授权检查)
- [构建打包](#构建打包)
- [安装使用](#安装使用)

---

## 功能特性

- 双 Tab 界面：串口助手 / 电机控制，共享同一串口连接。
- 三轴电机控制：设置目标位置（自动限幅到型号行程）、速度、往复运动、步距 +/–、复位、获取 / 下发加减速启停参数。
- 型号配置：内置 `motor_config.json`，可用系统文件选择器导入自定义 JSON（不同镜头型号行程不同）。
- 速度曲线、传感器曲线：实时采集并绘制双通道波形。
- 启动联网授权检查：按作者维护状态（`STOP` 标记）决定软件是否可用。

---

## 支持的设备

- **USB CDC 虚拟串口**（本项目默认目标设备：`VID 0x19F5 / PID 0x5740`），使用 `CdcAcmSerialDriver` 打开。
- **CH340 / 常见 USB 转串口**：通过默认探测自动识别。

> 说明：部分 CDC 设备枚举接口类型不标准，默认探测可能命中不了。代码中对 `VID 0x19F5 / PID 0x5740` 做了强制走 `CdcAcmSerialDriver(device)`（单参构造，处理 IAD / 单接口设备），并在打开时 `setDTR(true)` / `setRTS(true)`（部分 CDC VCP 不拉高控制线不工作）。

如需支持其他 CDC 设备，可在 `MainActivity.kt` 的 `findDriver()` 中把对应 VID/PID 加入强制驱动分支。

---

## 界面说明

### 串口助手（SerialFragment）

- 波特率选择（默认 115200）
- HEX 显示 / HEX 发送开关
- 接收区（文本 / HEX）、发送输入框
- 打开串口 / 发送 / 清空 / 关闭

### 电机控制（MotorControlFragment）

#### 电机配置区
- 型号下拉框（来自配置文件）、配置轴选择（AP / AF1 / AF2）
- 往复延时（ms）
- 当前参数（只读）：加速度 / 减速度 / 启动速度 / 停止速度
- 设置参数（下发）：设加速度 / 设减速度 / 设启速 / 设停速
- 按钮：速度曲线、传感器曲线、获取配置、下发配置、导入 JSON

> 说明：点击「下发配置」后会发送参数，并**自动回读**一次配置用于确认（与 PC 工具行为一致）。下发参数输入框默认值为 `400`。

#### 三轴卡片（AP / AF1 / AF2）
- 当前位置（获取后更新）
- 获取位置 / 设置位置 / 复位
- 速度输入
- 往复运动（到目标位置后按「往复延时」停顿并反向移动一个步距）
- 步距（um）+ / –

---

## 电机控制与配置

### 配置文件结构（motor_config.json）

```json
{
  "mode": "058",
  "MotorConfig": {
    "058": {
      "ap电机": false,
      "ap电机起点": 0,
      "ap电机终点": 400,
      "af1电机": true,
      "af1电机起点": -240,
      "af1电机终点": 1936,
      "af2电机": false,
      "af2电机起点": 0,
      "af2电机终点": 8600,
      "ap电机单步长度": 2.5,
      "af1电机单步长度": 2.5,
      "af2电机单步长度": 2.5,
      "af1来回跑阈值": 1,
      "af2来回跑阈值": 1,
      "ap来回跑阈值": 1
    }
  }
}
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `xxx电机` | 该轴是否启用 |
| `xxx电机起点 / 终点` | 行程范围（位置限幅，单位 um，支持负数） |
| `xxx电机单步长度` | 单步长度（um/步），用于「um → 步数」换算与步距微调 |
| `xxx来回跑阈值` | 往复运动到达判定阈值（位置差 ≤ 阈值视为到达） |

> 增加新型号时，在 `MotorConfig.MotorConfig` 下新增一个键（如 `"059": { ... }`），并把 `mode` 改为对应型号名即可。可通过「导入 JSON」按钮用系统文件选择器导入完整配置文件。

### 换算规则
- `步数 = 目标位置(um) / 单步长度(um)`
- 设置位置时会先换算并**夹取到该型号的行程范围**（起点/终点），超出则限幅。

---

## 串口协议

帧格式与 PC 工具 `ly_motor_test_tool.py` 保持一致。

### 发送帧（命令）

```
简单命令:   55 <cmd> 5f
带数据命令: 55 <cmd> <len> <payload> <crc> 5f
CRC = 对 body（cmd 到 payload 末尾）逐字节异或
```

常用命令：

| 功能 | 命令 |
| --- | --- |
| 查询位置 AP / AF1 / AF2 | `55 0b 5f` / `55 0c 5f` / `55 11 5f` |
| 复位 | `55 0f 5f` |
| 获取配置 AP / AF1 / AF2 | `55 1b 00 5f` / `55 1b 01 5f` / `55 1b 02 5f` |
| 查询版本 | `55 01 5f` |
| 速度曲线 开 / 关 | `55 1a 01 5f` / `55 1a 00 5f` |
| 设置位置 + 速度 AP/AF1/AF2 | `55 <13/14/15> <步长> <步ascii> <速长> <速ascii> <crc> 5f` |
| 设置参数（启速/加速/减速/停速） | `55 1c <len> <"电机id#启速#加速#减速#停速"> <crc> 5f`（电机id: 00/01/02） |

> `步ascii` / `速ascii` 为数字的 ASCII 编码；`len` 为 ASCII 字节数。

### 接收帧（返回，`aa` 头，无 `5f` 尾）

```
aa <id> <len> <payload> <crc>
CRC = 对 [id, len, payload...] 逐字节异或
```

| id | 含义 |
| --- | --- |
| `0b / 0c / 11` | AP / AF1 / AF2 位置（payload 为 ASCII 数字） |
| `1b` | 配置（`启速#加速#减速#停速`） |
| `16 / 17 / 18` | AP / AF1 / AF2 配置 CRC |
| `01` | MCU 版本 |
| `1a` | 速度曲线数据（每 2 字节一个采样，16 位大端，双通道成对） |
| `1c` | 传感器曲线数据（每 2 字节一个采样，16 位大端，单通道） |

> 重要：**发送帧用 `55` 头、接收帧用 `aa` 头**，两套格式。接收解析在 `MotorProtocol.extractFrames()` 中实现（按 `aa` 头 + `len` 定边界 + CRC 校验）。

---

## 速度曲线 / 传感器曲线

点击电机控制页的「速度曲线」或「传感器曲线」按钮：
- 发送 `55 1a 01 5f` 开始采集，并弹出曲线窗口（CH1 红 / CH2 绿，Y 轴 0~4096）。
- 收到 `1a`（速度曲线，双通道）/ `1c`（传感器曲线，单通道）帧，每 2 字节解析一个采样并实时绘制。
- 窗口提供 **暂停 / 清空 / 关闭**。
- 再次点击按钮或关闭窗口会发送 `55 1a 00 5f` 停止采集。

---

## 应用授权检查

每次启动 App 会联网读取作者维护状态：

```
https://www.cnblogs.com/cortana1/p/19510092
```

解析正文中的 `STOP` 标记：

| 情况 | 行为 |
| --- | --- |
| `STOP:1` | 正常运行 |
| `STOP:0` | 弹出「作者已停止维护，本软件不可用」并退出 |
| 网络失败 / 解析不到 | 弹出「网络异常，请联网使用本软件」并退出 |

实现位于 `MainActivity.checkLicense()`。

---

## 构建打包

工程为 Kotlin + Gradle（AGP 8.2.0 / Kotlin 1.9.0 / compileSdk 34 / minSdk 24 / targetSdk 34），建议通过 **GitHub Actions** 云端打包（本地无需 Android SDK）。

### 通过 GitHub Actions 打包

1. 将本工程 push 到 GitHub 仓库（仓库内需含 `.github/workflows/build.yml`）。
2. 打开仓库 `Actions` 页，选择 **Build Android APK** 工作流，点 **Run workflow**。
3. 等待构建完成（约几分钟），在 Artifacts 中下载 `app-apk`，解压得到 `app-debug.apk`。

> 工作流关键点：不打包 gradlew 二进制，使用 `gradle/actions/setup-gradle@v4` + `gradle-version: 8.4` 后直接执行 `gradle assembleDebug`。

---

## 安装使用

1. 安装 APK，将 OTG 转接头 / USB 串口线接入手机。
2. 打开 App，插入设备时会弹 USB 权限请求，允许后即可。
3. 在「串口助手」页点击「打开串口」。
4. 切到「电机控制」页，选择型号 → 操作各轴 / 下发配置 / 查看曲线。

---

## 主要代码文件

```
app/src/main/kotlin/com/serialtool/otgserial/
├── MainActivity.kt            # Tab 宿主 + 共享串口 + 联网授权检查
├── SerialFragment.kt          # 串口助手页
├── MotorControlFragment.kt    # 电机控制页（三轴 + 配置 + 曲线开关）
├── MotorProtocol.kt           # 串口协议（帧构造 + aa 头返回帧解析）
├── MotorConfig.kt             # 型号配置解析 / um↔步数换算 / 行程限制
├── CurveView.kt               # 双通道曲线绘制 View
└── CurveDialog.kt             # 曲线显示弹窗

app/src/main/res/layout/
├── activity_main.xml          # Tab 宿主布局
├── fragment_serial.xml        # 串口助手布局
├── fragment_motor.xml         # 电机控制布局
└── axis_control.xml           # 单轴卡片布局（三轴复用）

app/src/main/assets/motor_config.json   # 内置型号配置
app/src/main/res/xml/device_filter.xml  # USB 设备过滤（含 0x19F5/0x5740）
```
