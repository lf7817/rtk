package com.fan.nmea.parser

import com.fan.nmea.model.NmeaMessage

interface NmeaParser<T : NmeaMessage> {
    /** e.g., "GGA", "RMC", "UNIHEADINGA" */
    val type: String
    fun canParse(header: String): Boolean = header.endsWith(type, ignoreCase = true)
    fun parse(sentence: String): T?
}