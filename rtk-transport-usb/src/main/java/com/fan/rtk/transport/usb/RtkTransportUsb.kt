package com.fan.rtk.transport.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import com.fan.rtk.transport.ConnectionState
import com.fan.rtk.transport.RtkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.BufferOverflow

class RtkTransportUsb(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : RtkTransport {

    private val usbManager: UsbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbConnection: UsbDeviceConnection? = null
    private var serialDevice: UsbSerialDevice? = null

    private val _incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val writeMutex = Mutex()

    /**
     * 打开 USB 串口设备。调用方需已通过 [UsbManager.requestPermission] 获得设备权限。
     * @param usbDevice 通过 [UsbManager.getDeviceList] 或 ACTION_USB_DEVICE_ATTACHED 获得的设备
     * @param baudRate 波特率，默认 115200
     */
    fun open(usbDevice: UsbDevice, baudRate: Int = 115200) {
        coroutineScope.launch(Dispatchers.IO) {
            close()
            _connectionState.value = ConnectionState.Connecting

            val connection = usbManager.openDevice(usbDevice)
            if (connection == null) {
                _connectionState.value = ConnectionState.Error(UsbSendException.PermissionDenied)
                Log.w(TAG, "openDevice returned null, permission may not be granted")
                return@launch
            }

            val serial = UsbSerialDevice.createUsbSerialDevice(usbDevice, connection)
            if (serial == null) {
                connection.close()
                _connectionState.value = ConnectionState.Error(UsbSendException.DeviceNotSupported)
                Log.w(TAG, "Device not supported by UsbSerial (not CH34x/CDC/CP210x/FTDI/PL2303)")
                return@launch
            }

            if (!serial.open()) {
                serial.close()
                connection.close()
                _connectionState.value = ConnectionState.Error(UsbSendException.WriteFailed("open() failed"))
                return@launch
            }

            serial.setBaudRate(baudRate)
            serial.setDataBits(UsbSerialInterface.DATA_BITS_8)
            serial.setParity(UsbSerialInterface.PARITY_NONE)
            serial.setStopBits(UsbSerialInterface.STOP_BITS_1)
            serial.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)

            usbConnection = connection
            serialDevice = serial

            serial.read { data ->
                _incoming.tryEmit(data)
            }

            _connectionState.value = ConnectionState.Connected
            Log.d(TAG, "USB serial opened: baudRate=$baudRate")
        }
    }

    /**
     * 已连接设备中按 VID/PID 查找并打开第一个匹配设备。
     */
    fun open(vendorId: Int, productId: Int, baudRate: Int = 115200): Boolean {
        val device = usbManager.deviceList.values.find {
            it.vendorId == vendorId && it.productId == productId
        } ?: return false
        open(device, baudRate)
        return true
    }

    /**
     * 已连接设备列表，供 UI 选择。
     */
    fun listAttachedDevices(): Map<String, UsbDevice> = usbManager.deviceList

    override suspend fun send(data: ByteArray): Result<Unit> {
        if (_connectionState.value != ConnectionState.Connected) {
            return Result.failure(UsbSendException.NotConnected)
        }
        return writeMutex.withLock {
            runCatching {
                val serial = serialDevice
                if (serial == null || !serial.isOpen) {
                    throw UsbSendException.NotConnected
                }
                serial.write(data)
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { e ->
                    Result.failure(
                        if (e is UsbSendException) e
                        else UsbSendException.WriteFailed(e.message ?: "write failed")
                    )
                }
            )
        }
    }

    override fun close() {
        try {
            serialDevice?.close()
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close exception: ${e.message}", e)
        } finally {
            serialDevice = null
            usbConnection = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    companion object {
        private const val TAG = "RtkTransportUsb"
    }
}
