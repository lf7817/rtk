package com.fan.nmea

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * val aggregator = NmeaLineAggregator()
 *
 * // 订阅完整行
 * CoroutineScope(Dispatchers.IO).launch {
 *     aggregator.lines.collect { line ->
 *         println("完整 NMEA 行: $line")
 *     }
 * }
 *
 * // 当收到蓝牙或串口数据
 * aggregator.feed(byteArrayFromBluetooth)
 */

class NmeaLineAggregator(
    replay: Int = 0,
    extraBufferCapacity: Int = 64,
    onBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST
) {
    private val _lines = MutableSharedFlow<String>(replay, extraBufferCapacity, onBufferOverflow)
    val lines = _lines.asSharedFlow()

    private val sb = StringBuilder()

    fun feed(bytes: ByteArray) {

        for (b in bytes) {
            val ch = b.toInt().toChar()
            when (ch) {
                '\r' -> {}
                '\n' -> {
                    val line = sb.toString()
                    sb.clear()
                    if (line.isNotBlank()) {
                        _lines.tryEmit(line)
                    }
                }
                else -> sb.append(ch)
            }
        }
    }
}