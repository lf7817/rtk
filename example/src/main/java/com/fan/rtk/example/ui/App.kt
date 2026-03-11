package com.fan.rtk.example.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fan.nmea.model.NmeaMessage
import com.fan.rtk.core.NtripConfig
import com.fan.rtk.core.RtkManager
import com.fan.rtk.transport.ConnectionState
import com.fan.rtk.transport.RtkTransport
import com.fan.rtk.transport.ble.RtkTransportBle
import com.fan.rtk.transport.usb.RtkTransportUsb
import android.bluetooth.le.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "RtkExample"

/** 默认 Nordic UART Service (NUS)，常用于 BLE 串口透传 */
private val DEFAULT_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val DEFAULT_WRITE_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val DEFAULT_NOTIFY_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App(
    modifier: Modifier,
    usbPermissionGranted: StateFlow<android.hardware.usb.UsbDevice?> = MutableStateFlow(null)
) {
    val context = LocalContext.current
    // RtkManager 内会做网络连接，必须使用 IO 调度器避免 NetworkOnMainThreadException
    val ioScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    // 通讯方式：BLE / USB
    var transportKind by remember { mutableStateOf(TransportKind.BLE) }

    // 两个 transport 实例，按需使用
    val bleTransport = remember(context) {
        RtkTransportBle(context.applicationContext, CoroutineScope(Dispatchers.IO + SupervisorJob()))
    }
    val usbTransport = remember(context) {
        RtkTransportUsb(context.applicationContext, CoroutineScope(Dispatchers.IO + SupervisorJob()))
    }

    // 当前已连接的 transport（用于 RtkManager）
    var currentTransport by remember { mutableStateOf<RtkTransport?>(null) }
    val onSetCurrentTransport: (RtkTransport?) -> Unit = { currentTransport = it }
    val rtkManager = remember(currentTransport) {
        currentTransport?.let { RtkManager(ioScope, it) }
    }

    // BLE 权限
    val blePermissions = remember { bleTransport.blePermissions }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { !it }) Log.w(TAG, "Some BLE permissions denied")
    }

    // USB 权限授予后自动打开设备
    LaunchedEffect(usbPermissionGranted, usbTransport, onSetCurrentTransport) {
        usbPermissionGranted.collect { device ->
            if (device != null) {
                usbTransport.open(device)
                onSetCurrentTransport(usbTransport)
                (usbPermissionGranted as? MutableStateFlow)?.value = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            rtkManager?.disconnect()
            currentTransport = null
            ioScope.cancel()
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
        Text(
            "RTK Demo",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 切换通讯方式
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = transportKind == TransportKind.BLE,
                onClick = { transportKind = TransportKind.BLE },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text("蓝牙 BLE") }
            )
            SegmentedButton(
                selected = transportKind == TransportKind.USB,
                onClick = { transportKind = TransportKind.USB },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text("USB 串口") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (transportKind) {
            TransportKind.BLE -> BleSection(
                bleTransport = bleTransport,
                currentTransport = currentTransport,
                onSetCurrentTransport = onSetCurrentTransport,
                onRequestPermissions = { permissionLauncher.launch(blePermissions) }
            )
            TransportKind.USB -> UsbSection(
                usbTransport = usbTransport,
                currentTransport = currentTransport,
                onSetCurrentTransport = onSetCurrentTransport,
                context = context
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 已连接时显示 NTRIP 与状态
        rtkManager?.let { manager ->
            NtripSection(context = context, manager = manager)
            Spacer(modifier = Modifier.height(8.dp))
            ConnectionStateSection(manager = manager)
            LastNmeaSection(manager = manager)
        }
        }
    }
}

private enum class TransportKind { BLE, USB }

@Composable
private fun BleSection(
    bleTransport: RtkTransportBle,
    currentTransport: RtkTransport?,
    onSetCurrentTransport: (RtkTransport?) -> Unit,
    onRequestPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val connectionState by bleTransport.connectionState.collectAsState(initial = ConnectionState.Idle)
    val hasPermission = bleTransport.checkPermissionGranted()
    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("蓝牙 BLE", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (!hasPermission) {
                Button(onClick = onRequestPermissions) { Text("请求蓝牙权限") }
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        isScanning = true
                        scanResults = emptyList()
                        scope.launch {
                            try {
                                bleTransport.startScan(timeoutMillis = 12_000L).collect { result ->
                                    scanResults = scanResults + result
                                }
                            } finally {
                                isScanning = false
                            }
                        }
                    },
                    enabled = !isScanning && connectionState !is ConnectionState.Connecting
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (isScanning) "扫描中…" else "扫描设备")
                }
                if (isScanning) {
                    Button(onClick = { bleTransport.stopScan() }) { Text("停止") }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("状态: ${connectionStateText(connectionState)}", style = MaterialTheme.typography.bodySmall)

            if (currentTransport == bleTransport) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        bleTransport.close()
                        onSetCurrentTransport(null)
                    }
                ) {
                    Text("断开蓝牙")
                }
            }

            if (scanResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("设备列表:", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    scanResults.distinctBy { it.device.address }.forEach { result ->
                        val name = result.device.name?.ifEmpty { result.device.address } ?: result.device.address
                        Button(
                            onClick = {
                                bleTransport.connect(
                                    address = result.device.address,
                                    serviceUuid = DEFAULT_SERVICE_UUID,
                                    writeCharacteristicUuid = DEFAULT_WRITE_UUID,
                                    notifyCharacteristicUuid = DEFAULT_NOTIFY_UUID,
                                    configCharacteristicUuid = null
                                )
                                onSetCurrentTransport(bleTransport)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connectionState != ConnectionState.Connecting
                        ) {
                            Text("$name (${result.device.address})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbSection(
    usbTransport: RtkTransportUsb,
    currentTransport: RtkTransport?,
    onSetCurrentTransport: (RtkTransport?) -> Unit,
    context: Context
) {
    val connectionState by usbTransport.connectionState.collectAsState(initial = ConnectionState.Idle)
    val usbManager = remember(context) { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val deviceList = remember(usbManager) { usbTransport.listAttachedDevices() }

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("USB 串口", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("状态: ${connectionStateText(connectionState)}", style = MaterialTheme.typography.bodySmall)

            if (currentTransport == usbTransport) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        usbTransport.close()
                        onSetCurrentTransport(null)
                    }
                ) {
                    Text("断开 USB")
                }
            }

            if (deviceList.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("未检测到 USB 设备", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text("设备列表:", style = MaterialTheme.typography.labelMedium)
                deviceList.values.forEach { device ->
                    val label = usbDeviceLabel(device)
                    val hasPermission = usbManager.hasPermission(device)
                    Button(
                        onClick = {
                            if (hasPermission) {
                                usbTransport.open(device)
                                onSetCurrentTransport(usbTransport)
                            } else {
                                requestUsbPermission(context, device)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        enabled = connectionState != ConnectionState.Connecting
                    ) {
                        Text("$label ${if (hasPermission) "" else "(需授权)"}")
                    }
                }
            }
        }
    }
}

private fun requestUsbPermission(context: Context, device: UsbDevice) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags or PendingIntent.FLAG_NO_CREATE)
    usbManager.requestPermission(device, pi)
}

const val ACTION_USB_PERMISSION = "com.fan.rtk.example.USB_PERMISSION"

private fun usbDeviceLabel(device: UsbDevice): String {
    return "VID=${Integer.toHexString(device.vendorId)} PID=${Integer.toHexString(device.productId)}"
}

private const val PREFS_NTRIP = "ntrip_config"
private const val KEY_HOST = "host"
private const val KEY_PORT = "port"
private const val KEY_MOUNT = "mount"
private const val KEY_USER = "user"
private const val KEY_PASSWORD = "password"

@Composable
private fun NtripSection(context: Context, manager: RtkManager) {
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(PREFS_NTRIP, Context.MODE_PRIVATE)
    }
    var host by remember(prefs) {
        mutableStateOf(prefs.getString(KEY_HOST, "sdk.pnt.10086.cn") ?: "")
    }
    var portStr by remember(prefs) {
        mutableStateOf(prefs.getInt(KEY_PORT, 8002).toString())
    }
    var mount by remember(prefs) {
        mutableStateOf(prefs.getString(KEY_MOUNT, "RTCM33_GRCE") ?: "")
    }
    var user by remember(prefs) {
        mutableStateOf(prefs.getString(KEY_USER, "cweh009") ?: "")
    }
    var password by remember(prefs) {
        mutableStateOf(prefs.getString(KEY_PASSWORD, "c40fd0n31") ?: "")
    }

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("NTRIP 配置（可选）", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portStr,
                onValueChange = { portStr = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = mount,
                onValueChange = { mount = it },
                label = { Text("Mount Point") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val port = portStr.toIntOrNull() ?: 2101
                    prefs.edit()
                        .putString(KEY_HOST, host)
                        .putInt(KEY_PORT, port)
                        .putString(KEY_MOUNT, mount)
                        .putString(KEY_USER, user)
                        .putString(KEY_PASSWORD, password)
                        .apply()
                    manager.connect(
                        NtripConfig(
                            host = host,
                            port = port,
                            mountPoint = mount,
                            username = user,
                            password = password
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("连接 NTRIP")
            }
            OutlinedButton(
                onClick = { manager.disconnectNtrip() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("断开 NTRIP")
            }
        }
    }
}

@Composable
private fun ConnectionStateSection(manager: RtkManager) {
    val ntripState by manager.connectionState.collectAsState(initial = ConnectionState.Idle)
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("NTRIP 状态: ${connectionStateText(ntripState)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LastNmeaSection(manager: RtkManager) {
    var lastMsg by remember { mutableStateOf<NmeaMessage?>(null) }
    LaunchedEffect(manager) {
        manager.nmeaMessages.collect {
            lastMsg = it
        }
    }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("最新 NMEA", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = lastMsg?.raw ?: "—",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun connectionStateText(s: ConnectionState): String = when (s) {
    is ConnectionState.Idle -> "空闲"
    is ConnectionState.Connecting -> "连接中…"
    is ConnectionState.Connected -> "已连接"
    is ConnectionState.Disconnected -> "已断开"
    is ConnectionState.Error -> "错误: ${s.throwable.message}"
}
