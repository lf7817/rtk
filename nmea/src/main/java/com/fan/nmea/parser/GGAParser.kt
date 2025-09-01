package com.fan.nmea.parser

import com.fan.nmea.NmeaUtils
import com.fan.nmea.model.NmeaMessage


class GGAParser : NmeaParser<NmeaMessage.GGA> {
    override val type: String = "GGA"

    override fun parse(sentence: String): NmeaMessage.GGA? {
        if (!NmeaUtils.isValidChecksum(sentence)) return null
        val core = sentence.substring(1, sentence.indexOf('*'))
        val f = core.split(',')
        if (f.isEmpty() || !f[0].endsWith(type, true)) return null
        // Fields: 0=GPGGA,1=time,2=lat,3=N/S,4=lon,5=E/W,6=fix,7=sats,8=hdop,9=alt,10=M,11=geoid,12=M,13=age,14=station
        return NmeaMessage.GGA(
            raw = sentence,
            timeUtc = f.getOrNull(1).orEmpty().ifBlank { null },
            latitude = NmeaUtils.toDecimalDegrees(f.getOrNull(2), f.getOrNull(3)),
            longitude = NmeaUtils.toDecimalDegrees(f.getOrNull(4), f.getOrNull(5)),
            fixQuality = f.getOrNull(6)?.toIntOrNull(),
            satellites = f.getOrNull(7)?.toIntOrNull(),
            hdop = f.getOrNull(8)?.toDoubleOrNull(),
            altitudeMsl = f.getOrNull(9)?.toDoubleOrNull(),
            geoidSeparation = f.getOrNull(11)?.toDoubleOrNull()
        )
    }
}