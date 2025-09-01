package com.fan.nmea.parser

import com.fan.nmea.NmeaUtils
import com.fan.nmea.model.NmeaMessage

class RMCParser : NmeaParser<NmeaMessage.RMC> {
    override val type: String = "RMC"

    override fun parse(sentence: String): NmeaMessage.RMC? {
        if (!NmeaUtils.isValidChecksum(sentence)) return null
        val core = sentence.substring(1, sentence.indexOf('*'))
        val f = core.split(',')
        if (f.isEmpty() || !f[0].endsWith(type, true)) return null
        // Fields: 0=GPRMC,1=time,2=status,3=lat,4=N/S,5=lon,6=E/W,7=speed(kn),8=course,9=date,10=magvar,11=E/W,12=mode
        return NmeaMessage.RMC(
            raw = sentence,
            timeUtc = f.getOrNull(1).orEmpty().ifBlank { null },
            status = f.getOrNull(2).orEmpty().ifBlank { null },
            latitude = NmeaUtils.toDecimalDegrees(f.getOrNull(3), f.getOrNull(4)),
            longitude = NmeaUtils.toDecimalDegrees(f.getOrNull(5), f.getOrNull(6)),
            speedKnots = f.getOrNull(7)?.toDoubleOrNull(),
            courseDeg = f.getOrNull(8)?.toDoubleOrNull(),
            dateDdMmYy = f.getOrNull(9).orEmpty().ifBlank { null }
        )
    }
}