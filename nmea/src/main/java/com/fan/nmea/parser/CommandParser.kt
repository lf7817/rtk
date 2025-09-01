package com.fan.nmea.parser

import com.fan.nmea.NmeaUtils
import com.fan.nmea.model.NmeaMessage


class CommandParser : NmeaParser<NmeaMessage.Command> {
    override val type: String = "COMMAND"

    override fun parse(sentence: String): NmeaMessage.Command? {
        // 校验校验和
        if (!NmeaUtils.isValidChecksum(sentence)) return null

        // 截取核心内容（去掉开头$和末尾*checksum）
        val core = sentence.substring(1, sentence.indexOf('*'))
        val f = core.split(',')

        // 判断类型是否匹配，例如 $COMMAND,xxx,...
        if (f.isEmpty() || !f[0].endsWith(type, ignoreCase = true)) return null

//        $COMMAND,cmd,response
        return NmeaMessage.Command(
            raw = sentence,
            command =  f.getOrNull(1)?.trim().orEmpty(),
            response = f.getOrNull(2)?.trim().orEmpty()
        )
    }
}