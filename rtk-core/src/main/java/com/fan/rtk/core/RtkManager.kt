package com.fan.rtk.core

import com.fan.nmea.NmeaLineAggregator
import com.fan.nmea.NmeaParserManager
import com.fan.nmea.model.NmeaMessage
import com.fan.rtk.transport.RtkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

class RtkManager(
    private val scope: CoroutineScope,
    private val rtkTransport: RtkTransport,
) {
    // ---------- NTRIP ----------
    private val ntripClient = NtripClient(scope)
    private var connectJob: Job? = null

    val connectionState = ntripClient.connectionState

    // ---------- NMEA ----------
    private val lineAggregator = NmeaLineAggregator()
    private val parserManager = NmeaParserManager()

    private var latestGgaLine: String? = null

//    private var latestGgaLine: String? =
//        "\$GNGGA,010822.00,3157.01094528,N,11853.42141470,E,1,21,1.0,21.2697,M,2.6087,M,,*77"

    val nmeaMessages: SharedFlow<NmeaMessage> = lineAggregator.lines
        .mapNotNull { parserManager.parse(it) }
        .onEach { msg ->
            if (msg is NmeaMessage.GGA) latestGgaLine = msg.raw
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5000), replay = 0)


    init {
        scope.launch(Dispatchers.IO) {
            rtkTransport.incoming.collect {
                lineAggregator.feed(it)
            }
        }
    }

    /**
     * 启动 RTKManager
     */
    fun connect(ntripConfig: NtripConfig, reconnectIntervalMs: Long = 3000L, ggaTimeoutMs: Long = 30_000L) {
        connectJob?.cancel()
        ntripClient.disconnect()
        ntripClient.configure(ntripConfig)

        connectJob = scope.launch {
            ntripClient.startStreaming(
                getLatestGga = { latestGgaLine },
                reconnectIntervalMs = reconnectIntervalMs,
                ggaTimeoutMs = ggaTimeoutMs
            ).collect {
                rtkTransport.send(it)
            }
        }
    }

    fun disconnectNtrip() {
        connectJob?.cancel()
        ntripClient.disconnect()
    }

    suspend fun disconnect() {
        disconnectNtrip()
        rtkTransport.close()
    }
}