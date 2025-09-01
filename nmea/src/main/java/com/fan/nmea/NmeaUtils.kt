package com.fan.nmea

object NmeaUtils {
    /** Returns true if XOR checksum matches for "$...*hh" style. */
    fun isValidChecksum(sentence: String): Boolean {
        if (!sentence.contains("*")) return false
        val star = sentence.lastIndexOf('*')
        if (star < 0 || star + 3 > sentence.length) return false
        val provided = sentence.substring(star + 1)
        val data = when {
            sentence.startsWith("$") -> sentence.substring(1, star)
            sentence.startsWith("#") -> sentence.substring(1, star) // accept '#' vendor lines
            else -> sentence.substring(0, star)
        }
        var calc = 0
        for (ch in data) calc = calc xor ch.code
        return "%02X".format(calc) == provided.uppercase()
    }

    /** ddmm.mmmm (lat) or dddmm.mmmm (lon) -> decimal degrees */
    fun toDecimalDegrees(coord: String?, hemi: String?): Double? {
        if (coord.isNullOrBlank() || hemi.isNullOrBlank()) return null
        return try {
            val degLen = if (hemi.equals("N", true) || hemi.equals("S", true)) 2 else 3
            val d = coord.substring(0, degLen).toDouble()
            val m = coord.substring(degLen).toDouble()
            var dec = d + m / 60.0
            if (hemi.equals("S", true) || hemi.equals("W", true)) dec = -dec
            dec
        } catch (_: Throwable) { null }
    }

    /** Extract header like GPGGA / GPRMC / UNIHEADINGA from "$GPGGA,..." or "#UNIHEADINGA,..." */
    fun headerOf(sentence: String): String {
        val start = if (sentence.startsWith("$") || sentence.startsWith("#")) 1 else 0
        val end = sentence.indexOf(',').let { if (it == -1) sentence.length else it }
        return sentence.substring(start, end)
    }
}