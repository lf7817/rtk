package com.fan.nmea

import com.fan.nmea.model.NmeaMessage
import com.fan.nmea.parser.CommandParser
import com.fan.nmea.parser.GGAParser
import com.fan.nmea.parser.NmeaParser
import com.fan.nmea.parser.RMCParser
import com.fan.nmea.parser.UniHeadingParser


class NmeaParserManager(
    parsers: List<NmeaParser<out NmeaMessage>> = listOf(GGAParser(), RMCParser(), UniHeadingParser(), CommandParser())
) {
    private val byType = parsers.associateBy { it.type.uppercase() }

    fun parse(sentence: String): NmeaMessage? {
        if (!sentence.contains(",")) return null
        val header = NmeaUtils.headerOf(sentence)

        // 找到第一个匹配 parser 的 type
        val parser = byType.entries.firstOrNull { (typeKey, _) ->
            // 标准 NMEA 可能有前缀 GP/GN/GL/GA/GB，或者扩展后缀
            // typeKey 为 parser.type，比如 "GGA", "RMC", "UNIHEADINGA", "COMMAND"
            header.endsWith(typeKey)
        }?.value ?: return null

        @Suppress("UNCHECKED_CAST")
        return (parser as NmeaParser<NmeaMessage>).parse(sentence)
    }
}