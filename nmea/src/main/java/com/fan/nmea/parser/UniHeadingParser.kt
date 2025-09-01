package com.fan.nmea.parser

import com.fan.nmea.NmeaUtils
import com.fan.nmea.model.NmeaMessage

data class UniHeadingConfig(
    val headingIndex: Int = 2, // common variants: 2 or 3
    val pitchIndex: Int = 3,   // common variants: 3 or 4
    val baselineIndex: Int = 4,
    val qualityIndex: Int? = null // optional textual mode/quality
)

class UniHeadingParser(
    private val config: UniHeadingConfig = UniHeadingConfig()
) : NmeaParser<NmeaMessage.UniHeading> {
    override val type: String = "UNIHEADINGA"

    override fun canParse(header: String): Boolean =
        header.equals(type, ignoreCase = true)

    override fun parse(sentence: String): NmeaMessage.UniHeading? {
        if (!NmeaUtils.isValidChecksum(sentence)) return null
        val core = sentence.substring(1, sentence.indexOf('*'))
        val f = core.split(',')
        if (f.isEmpty() || !canParse(f[0])) return null
        fun numAt(i: Int): Double? = f.getOrNull(i)?.toDoubleOrNull()
        val heading = numAt(config.headingIndex)
        val pitch = numAt(config.pitchIndex)
        val baseline = numAt(config.baselineIndex)
        val quality = config.qualityIndex?.let { f.getOrNull(it) }
        return NmeaMessage.UniHeading(
            raw = sentence, headingDeg = heading, pitchDeg = pitch, baselineM = baseline, quality = quality
        )
    }
}