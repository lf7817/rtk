package com.fan.rtk.core

import com.fan.rtk.transport.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.contains

class NtripClient(private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
    private var config: NtripConfig? = null
    private val userAgent = "sorob/1.0"

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: InputStream? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    fun configure(config: NtripConfig) {
        this.config = config
    }

    fun disconnect() {
        try {
            reader?.close()
        } catch (_: Exception) {}
        reader = null

        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * 连接 NTRIP 并返回 RTCM 数据流
     * @param getLatestGga 每次上传 GGA 时调用
     * @param reconnectIntervalMs 自动重连间隔
     */
    fun startStreaming(
        getLatestGga: () -> String?,
        reconnectIntervalMs: Long = 3000L,
        ggaTimeoutMs: Long = 10_000L // GGA 超时时间
    ): Flow<ByteArray> = callbackFlow {
        if (config == null) {
            return@callbackFlow
        }

        while (isActive && config != null) {
            _connectionState.value = ConnectionState.Connecting

            try {
                socket = Socket(config!!.host, config!!.port)
                writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.US_ASCII))
                reader = socket!!.getInputStream()

                // 发送请求
                val authHeader = if (!config!!.username.isNullOrBlank() && config!!.password != null) {
                    "Authorization: Basic " + Base64.getEncoder()
                        .encodeToString("${config!!.username}:${config!!.password}".toByteArray(Charsets.US_ASCII))
                } else ""
                val requestHeaders = buildString {
                    append("GET /${config!!.mountPoint} HTTP/1.1\r\n")
                    append("User-Agent: $userAgent\r\n")
                    append("Ntrip-Version: Ntrip/2.0\r\n")
                    if (authHeader.isNotEmpty()) append("$authHeader\r\n")
                    append("\r\n")
                }
                writer!!.write(requestHeaders)
                writer!!.flush()

                // 检查 NTRIP 响应
                val headerReader = BufferedReader(InputStreamReader(reader!!, Charsets.US_ASCII))
                val firstLine = headerReader.readLine()
                if (firstLine == null || !firstLine.contains("200", ignoreCase = true)) {
                    throw IllegalStateException("NTRIP server rejected: $firstLine")
                }

                _connectionState.value = ConnectionState.Connected

                // 上传 GGA 协程，带超时检测
                val ggaJob = coroutineScope.launch {
                    var firstGgaSent = false
                    var ggaWaitTime = 0L
                    while (isActive) {
                        val gga = getLatestGga()?.takeIf { it.isNotBlank() }
                        if (gga != null) {
                            try {
                                writer?.write("$gga\r\n")
                                writer?.flush()
                                if (!firstGgaSent) firstGgaSent = true
                            } catch (_: Exception) {
                                break
                            }
                        } else if (!firstGgaSent) {
                            ggaWaitTime += 1000L
                            if (ggaWaitTime >= ggaTimeoutMs) {
                                try { socket?.close() } catch (_: Exception) {}
                                break
                            }
                        }
                        delay(1000L)
                    }
                }

                // 读取 RTCM 数据流
                val buffer = ByteArray(10240)
                while (isActive) {
                    val read = try {
                        reader!!.read(buffer)
                    } catch (e: Exception) {
                        println("NTRIP server 网络异常")
                        break // 网络异常
                    }
                    if (read == -1) {
                        println("NTRIP server closed connection (EOF)")
                        break
                    }
                    if (read > 0) {
                        // TODO 频繁断开、连接蓝牙设备时这里会发送失败，暂未解决；临时解决方法蓝牙连接或断开时重连
                        val result = trySend(buffer.copyOf(read))
                        if (result.isClosed) {
                            println("Send failed: isClosed")
                        }
                        if (!result.isSuccess) {
                            println("Send failed: ${result.exceptionOrNull()}")
                        }
                    }
                }

                ggaJob.cancelAndJoin()
                _connectionState.value = ConnectionState.Disconnected

            } catch (e: Exception) {
                if (e is CancellationException) {
                    // 协程被取消，正常关闭 Flow，不视为错误
                    _connectionState.value = ConnectionState.Disconnected
                    disconnect() // awaitClose 不会触发，不知道为啥，手动清理下
                    close()
                } else {
                    _connectionState.value = ConnectionState.Error(e)
                }

            }

            delay(reconnectIntervalMs)
        }

        awaitClose {
            disconnect()
        }
    }
}