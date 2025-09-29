package com.fan.nmea.parser

import com.fan.nmea.NmeaUtils
import com.fan.nmea.model.NmeaMessage

class GSTParser : NmeaParser<NmeaMessage.GST> {
    override val type: String = "GST"

    override fun parse(sentence: String): NmeaMessage.GST? {
        if (!NmeaUtils.isValidChecksum(sentence)) return null
        val core = sentence.substring(1, sentence.indexOf('*'))
        val f = core.split(',')
        if (f.isEmpty() || !f[0].endsWith(type, true)) return null
        // Fields: 0=GPGST,1=time,2=rms,3=sigmaMajor,4=sigmaMinor,5=orientation,
        // 6=sigmaLat,7=sigmaLon,8=sigmaAlt
        return NmeaMessage.GST(
            raw = sentence,
            timeUtc = f.getOrNull(1).orEmpty().ifBlank { null },
            rms = f.getOrNull(2)?.toDoubleOrNull(),
            sigmaMajor = f.getOrNull(3)?.toDoubleOrNull(),
            sigmaMinor = f.getOrNull(4)?.toDoubleOrNull(),
            orientation = f.getOrNull(5)?.toDoubleOrNull(),
            sigmaLat = f.getOrNull(6)?.toDoubleOrNull(),
            sigmaLon = f.getOrNull(7)?.toDoubleOrNull(),
            sigmaAlt = f.getOrNull(8)?.substringBefore("*")?.toDoubleOrNull()
        )
    }
}