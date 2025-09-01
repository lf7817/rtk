package com.fan.rtk.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RtkTransport {
    val connectionState: StateFlow<ConnectionState>
    val incoming: SharedFlow<ByteArray>
    suspend fun send(data: ByteArray)
    suspend fun close()
}