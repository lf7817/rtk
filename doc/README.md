# USB 通讯方案设计

> 文档名：USB 通讯方案设计（方案 B + CH340）

## 概述

参考现有蓝牙实现，新增 **rtk-transport-usb** 模块，实现 `RtkTransport` 接口，使 RtkManager 可通过 USB 与 RTK 设备通讯。不修改 rtk-core，仅新增模块并在示例中可选使用。

- **方案**：B（USB 串口库）
- **设备芯片**：CH340，使用 usb-serial-for-android 的 CH34x 驱动

---

## 现状摘要

- **通讯抽象**：`RtkTransport`（`rtk-transport` 模块）定义统一接口：
  - `connectionState: StateFlow<ConnectionState>`
  - `incoming: SharedFlow<ByteArray>`
  - `send(data: ByteArray): Result<Unit>`
  - `close()`

- **蓝牙实现**：`RtkTransportBle`（`rtk-transport-ble`）实现连接（地址 + UUID）、按 MTU 分片发送、notify 数据推入 `incoming`、`close()` 断开 Gatt。

- **使用方式**：`RtkManager` 仅依赖 `RtkTransport`，通过构造注入 transport；上行 NTRIP 数据走 `rtkTransport.send(it)`，下行从 `rtkTransport.incoming` 收集后喂给 NMEA 解析。

```
┌─────────────────┐     ┌──────────────────┐
│ App / Example   │     │ Transport 实现    │
│   RtkManager    │────▶│ RtkTransportBle  │
│                 │     │ RtkTransportUsb │
└─────────────────┘     └──────────────────┘
         │                        │
         └──────── RtkTransport 接口
```

---

## 实现思路

### 1. 模块与依赖

- 在 `settings.gradle.kts` 中 `include(":rtk-transport-usb")`。
- 新建 `rtk-transport-usb/build.gradle.kts`：Android Library，`minSdk`/`compileSdk` 与 BLE 一致，依赖：
  - `:rtk-transport`
  - 协程、AndroidX Core
  - **com.github.felHR85:UsbSerial**（JitPack，如 6.1.0）
- 在工程 `dependencyResolutionManagement.repositories` 中增加 JitPack：`maven { url = uri("https://jitpack.io") }`。
- 新建 `rtk-transport-usb/src/main/AndroidManifest.xml`：声明 `android.hardware.usb.host`（optional），无额外权限。

### 2. USB 连接方式（已确认）

- **方案 B**：使用 [felHR85/UsbSerial](https://github.com/felHR85/UsbSerial)，按串口方式收发。
- **CH340**：库内置 CH34x 驱动（如 `CH34xSerialDriver`），支持 300～921600 bps，实现时根据 `UsbDevice` 选用 CH34x 驱动即可。

### 3. 核心类设计

**RtkTransportUsb**（实现 `RtkTransport`）

| 项 | 说明 |
|----|------|
| 构造 | `(Context, CoroutineScope?)`，与 BLE 风格一致 |
| 状态 | `connectionState: StateFlow<ConnectionState>`（Idle / Connecting / Connected / Disconnected / Error） |
| 下行 | `incoming: SharedFlow<ByteArray>`，从 USB 读循环把数据 `tryEmit` 进去 |
| 上行 | `send(data: ByteArray): Result<Unit>`，写入 USB 串口；内部可加写锁避免并发写 |
| 关闭 | `close()` 释放连接并置为 Disconnected |

**连接入口（USB 特有）**

- 先实现 `open(usbDevice: UsbDevice)`，由调用方通过 `UsbManager.getDeviceList()` 或 `ACTION_USB_DEVICE_ATTACHED` 选设备后传入。
- 可选：`open(vendorId: Int, productId: Int)` 在已连接设备中按 VID/PID 查找并打开。

**权限**：使用 `UsbManager.requestPermission(device, pendingIntent)` 时，可在类内封装供 UI 调用，或将“请求权限”交给上层，本模块只做“是否已授权”检查。

### 4. 实现要点（UsbSerial + CH340）

- **驱动选择**：使用 UsbSerial 的 **CH34x 驱动**（如 `CH34xSerialDriver`）匹配 CH340 设备；打开时根据 `UsbDevice` 匹配合适的 `UsbSerialDriver`（首版可只支持 CH34x）。
- **打开流程**：在 IO 调度器上调用库的 `open()`，成功后启动协程循环 `read()` 将数据写入 `_incoming`；open 前设为 Connecting，成功为 Connected，失败或断开为 Error/Disconnected。
- **发送**：在 `send()` 中调用库的 `write(data)`，捕获异常并返回 `Result.failure(...)`；定义 **UsbSendException**（类似 BLE 的 `BleSendException`）便于上层区分。
- **断开**：库的 `close()` 或连接断开时更新 `connectionState` 并停止读协程；`RtkTransportUsb.close()` 主动关闭并清理。

### 5. 与 RtkManager 的集成

- **不修改 rtk-core**：RtkManager 已只依赖 `RtkTransport`，无需改代码。
- **Example**（若启用）：UI 中可选择“蓝牙”或“USB”；选 USB 时构造 `RtkTransportUsb`，请求设备权限并 `open(device)` 后，将同一 `RtkTransport` 实例传给 `RtkManager`。

### 6. 可选增强（不阻塞首版）

- 设备枚举：提供 `listAttachedDevices(): List<UsbDevice>` 或类似，供 UI 列表选择。
- 扩展其他芯片：CDC、CP210x 等可后续按需增加驱动选择逻辑。

---

## 文件与任务清单

| 步骤 | 说明 |
|------|------|
| 1 | 新建模块目录 `rtk-transport-usb`，添加 `build.gradle.kts`、`AndroidManifest.xml`，在 `settings.gradle.kts` 中 include；在根仓库 repositories 中添加 JitPack。 |
| 2 | 实现 `RtkTransportUsb`：`connectionState`、`incoming`、`send()`、`close()`；实现 `open(usbDevice)`；USB 读循环写入 `_incoming`。 |
| 3 | 定义 USB 相关异常类（如 `UsbSendException`），与 BLE 的 `BleSendException` 风格一致。 |
| 4 | 在 `build.gradle.kts` 添加 `com.github.felHR85:UsbSerial`（JitPack），使用 CH34x 驱动完成打开/读/写与错误处理。 |
| 5 | （可选）在 example 中增加 USB 入口：设备选择、权限、构造 `RtkTransportUsb` 并传入 `RtkManager`。 |

---

## 参考

- 接口：`rtk-transport/src/main/java/com/fan/rtk/transport/RtkTransport.kt`
- 状态：`rtk-transport/src/main/java/com/fan/rtk/transport/ConnectionState.kt`
- 蓝牙实现参考：`rtk-transport-ble/src/main/java/com/fan/rtk/transport/ble/RtkTransportBle.kt`
- 蓝牙异常参考：`rtk-transport-ble/src/main/java/com/fan/rtk/transport/ble/BleSendException.kt`
- UsbSerial：<https://github.com/felHR85/UsbSerial>
