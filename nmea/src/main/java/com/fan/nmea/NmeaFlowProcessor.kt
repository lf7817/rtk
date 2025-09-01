package com.fan.nmea

import com.fan.nmea.model.NmeaMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull


// ---------------------------
// Flow decoders
// ---------------------------

/** Convert a Flow of full-text NMEA/ASCII lines (each ending without CRLF) into parsed messages. */
fun decodeLines(
    lineFlow: Flow<String>,
    manager: NmeaParserManager = NmeaParserManager()
): Flow<NmeaMessage> = lineFlow
    .mapNotNull { line -> manager.parse(line) }

/** Accumulate raw bytes into CR/LF-delimited lines and parse to messages. */
fun decodeBytes(
    byteFlow: Flow<ByteArray>,
    manager: NmeaParserManager = NmeaParserManager()
): Flow<NmeaMessage> = channelFlow {
    val buf = StringBuilder()
    val emitLine: (String) -> Unit = { line ->
        if (line.isNotBlank()) {
            manager.parse(line)?.let { trySend(it) }
        }
    }
    collectBytes(byteFlow) { b ->
        val ch = b.toInt().toChar()
        when (ch) {
            '\r' -> {} // ignore
            '\n' -> {
                val line = buf.toString()
                buf.clear()
                emitLine(line)
            }
            else -> buf.append(ch)
        }
    }
}

private suspend inline fun collectBytes(
    flow: Flow<ByteArray>,
    crossinline onByte: suspend (Byte) -> Unit
) {
    flow.collect { arr ->
        for (b in arr) onByte(b)
    }
}
