package com.fan.rtk.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadSystemException
import android.util.Log
import androidx.core.content.ContextCompat
import com.fan.rtk.transport.ConnectionState
import com.fan.rtk.transport.RtkTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class RtkTransportBle(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : RtkTransport {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var mtuSize: Int = 20 // 默认

    private val _incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isBleEnabled = MutableStateFlow(false)
    val isBleEnabled: StateFlow<Boolean> = _isBleEnabled.asStateFlow()

    private var writeCharacteristicUuid: UUID? = null
    private var notifyCharacteristicUuid: UUID? = null
    private var serviceUuid: UUID? = null

    // 保存当前扫描回调
    private var currentScanCallback: ScanCallback? = null
    private val stopSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // -------- 权限逻辑交给外部 UI 层 --------
    val blePermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    fun checkPermissionGranted(): Boolean {
        return blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        _isBleEnabled.value = true
                    }

                    BluetoothAdapter.STATE_OFF -> {
                        _isBleEnabled.value = false
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
                Log.d("RtkBle", "Bluetooth state changed: ${state == BluetoothAdapter.STATE_ON}")
            }
        }
    }

    init {
        // 初始化状态
        if (bluetoothAdapter?.isEnabled == false) {
            _isBleEnabled.value = false
            _connectionState.value = ConnectionState.Disconnected
        } else {
            _isBleEnabled.value = true
        }
        // 注册广播
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    fun isBleEnabled() = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan(
        filters: List<ScanFilter> = emptyList(),
        timeoutMillis: Long = 10_000L
    ): Flow<ScanResult> = callbackFlow {
        if (currentScanCallback != null) {
            close(IllegalStateException("Scan already in progress"))
            return@callbackFlow
        }

        if (!isBleEnabled()) {
            close(IllegalStateException("Bluetooth is disabled"))
            return@callbackFlow
        }

        if (!checkPermissionGranted()) {
            close(SecurityException("BLE permission not granted"))
            return@callbackFlow
        }

        val scanner =
            (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter.bluetoothLeScanner

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result).isSuccess
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { trySend(it).isSuccess }
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        currentScanCallback = scanCallback

        val stopJob = launch {
            stopSignal.collect {
                close()
            }
        }

        val timeoutJob = launch {
            delay(timeoutMillis)
            close()
        }

        scanner.startScan(
            filters,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            scanCallback
        )

        awaitClose {
            stopJob.cancel()
            timeoutJob.cancel()
            stopScanInternal(scanner, scanCallback)
            Log.d("RtkBle", "Scan stopped!!!!")
        }
    }

    fun stopScan() {
        stopSignal.tryEmit(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal(scanner: BluetoothLeScanner, callback: ScanCallback) = checkReady {
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            // 避免 "could not find callback wrapper" 崩溃
            Log.w("RtkBle", "Stop scan exception: ${e.message}")
        } finally {
            currentScanCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ) = checkReady {
        val service = gatt.getService(serviceUuid)

        if (service == null) {
            Log.w("RtkBle", "Service $serviceUuid not found")
            return@checkReady
        }

        val notifyChar = service.getCharacteristic(characteristicUuid)
        if (notifyChar == null) {
            Log.w("RtkBle", "Characteristic $characteristicUuid not found")
            return@checkReady
        }

        gatt.setCharacteristicNotification(notifyChar, true)
    }

    private fun checkReady(callback: () -> Unit) {
        if (isBleEnabled() && checkPermissionGranted()) {
            try {
                callback()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e)
            }
        } else {
            _connectionState.value = ConnectionState.Error(Exception("BLE permission denied or disabled"))
        }
    }


    @OptIn(InternalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun connect(
        address: String,
        serviceUuid: UUID,
        writeCharacteristicUuid: UUID,
        notifyCharacteristicUuid: UUID,
        expiredMtu: Int = 247
    ): Unit = checkReady {
        close()

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            _connectionState.value = ConnectionState.Error(Exception("Bluetooth device not found"))
            return@checkReady
        }

        this@RtkTransportBle.serviceUuid = serviceUuid
        this@RtkTransportBle.writeCharacteristicUuid = writeCharacteristicUuid
        this@RtkTransportBle.notifyCharacteristicUuid = notifyCharacteristicUuid

        _connectionState.value = ConnectionState.Connecting

        bluetoothGatt = device.connectGatt(context, true, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = checkReady {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _connectionState.value = ConnectionState.Connected
                        coroutineScope.launch {
                            delay(300)
                            checkReady {
                                Log.d("RtkBle", "Discovering services: ${gatt.discoverServices()}")
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) = checkReady {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeCallback?.invoke(true)
                } else {
                    writeCallback?.invoke(false)
                    Log.w("RtkBle", "onCharacteristicWrite failed with status=$status")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = checkReady {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtuSize = mtu - 3
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = checkReady {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("RtkBle", "Services discovered. Size: ${gatt.services.size}")
                    Log.d("RtkBle", "Requesting MTU $expiredMtu")
                    gatt.requestMtu(expiredMtu)

                    coroutineScope.launch {
                        delay(300)
                        checkReady {
                            enableNotifications(gatt, serviceUuid, notifyCharacteristicUuid)
                        }
                    }
                } else {
                    _connectionState.value =
                        ConnectionState.Error(Exception("Service or characteristics not found"))
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) = checkReady {
                if (characteristic.uuid == notifyCharacteristicUuid) {
                    _incoming.tryEmit(characteristic.value)
                }
            }
        })
    }

    private val writeMutex = Mutex()

    private var writeCallback: ((Boolean) -> Unit)? = null

    @SuppressLint("MissingPermission")
    override suspend fun send(data: ByteArray) {
        if (!checkPermissionGranted() || !isBleEnabled() || connectionState.value != ConnectionState.Connected) return

        try {
            writeMutex.withLock {
                val gatt = bluetoothGatt ?: return
                val service = gatt.getService(serviceUuid) ?: return
                val writeChar = service.getCharacteristic(writeCharacteristicUuid) ?: return

                // MTU 分片发送
                var offset = 0
                while (offset < data.size && checkPermissionGranted() && isBleEnabled()) {
                    val end = (offset + mtuSize).coerceAtMost(data.size)
                    val chunk = data.copyOfRange(offset, end)


                    // 等待写入完成信号
                    val writeResult = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    writeCallback = { success -> writeResult.complete(success) }

                    // Android 13+ 新 API 写法（memory-safe）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            gatt.writeCharacteristic(
                                writeChar,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            )
                        } catch (e: DeadSystemException) {
                            Log.e("RtkBle", "BLE system dead, need to reconnect", e)
                            // 清理本地对象
                            this@RtkTransportBle.close()
                        }
                    } else {
                        // 低版本使用老方法
                        writeChar.value = chunk
                        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(writeChar)
                        0 // 假设发起成功
                    }

                    // 等待回调信号，而不是固定 delay
                    if (!writeResult.await()) {
                        Log.e("RtkBle", "Write failed at offset=$offset, chunk=${chunk.size}")
                        break
                    }

                    offset = end
                    delay(20) // 避免写入过快，保证蓝牙稳定
                }
            }
        } catch (e: Exception) {
            Log.e("RtkBle", "Exception in send: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() = checkReady {
        try {
            bluetoothGatt?.apply {
                disconnect() // 先断开
                close()      // 再关闭
            }
            bluetoothGatt = null
            _connectionState.value = ConnectionState.Disconnected
        } catch (e: Exception) {
            Log.e("RtkBle", "Exception in close: ${e.message}", e)
        }
    }
}

