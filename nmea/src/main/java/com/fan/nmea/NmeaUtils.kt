package com.fan.nmea

import com.fan.nmea.model.AccuracyMethod
import com.fan.nmea.model.AccuracyResult
import com.fan.nmea.model.NmeaMessage
import kotlin.math.sqrt

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

    /**
     * 计算水平精度（米）。
     * - 优先使用 gst 的 sigma / rms
     * - 若无，则回退到 gga.hdop * uere（根据 fixQuality 取经验值）
     * - confidenceMultiplier: 用于把 1-sigma 扩大为更保守的置信范围（例如 2.0 ~ 95%）
     */
    fun calcHorizontalAccuracy(
        gga: NmeaMessage.GGA?,
        gst: NmeaMessage.GST?,
        confidenceMultiplier: Double = 1.0 // 默认 1-sigma；若要 95% 可传 2.0
    ): AccuracyResult? {
        // 1) GST 优先
        gst?.let { s ->
            // 如果有 rms（总体 RMS），用它
            val rms = s.rms
            if (rms != null && rms > 0.0) {
                val r = rms * confidenceMultiplier
                return AccuracyResult(
                    radiusMeters = r,
                    majorMeters = r,
                    minorMeters = r,
                    orientationDegrees = 0.0,
                    method = AccuracyMethod.GST,
                    note = "used GST.rms"
                )
            }

            // 如果有 sigmaMajor / sigmaMinor -> 可直接画椭圆
            if (s.sigmaMajor != null && s.sigmaMajor > 0.0 && s.sigmaMinor != null && s.sigmaMinor > 0.0) {
                return AccuracyResult(
                    radiusMeters = (s.sigmaMajor * confidenceMultiplier),
                    majorMeters = s.sigmaMajor * confidenceMultiplier,
                    minorMeters = s.sigmaMinor * confidenceMultiplier,
                    orientationDegrees = (s.orientation ?: 0.0),
                    method = AccuracyMethod.GST,
                    note = "used GST.sigmaMajor/sigmaMinor"
                )
            }

            // 如果有 sigmaLat & sigmaLon -> 合成 2D RMS
            if (s.sigmaLat != null && s.sigmaLon != null && s.sigmaLat > 0.0 && s.sigmaLon > 0.0) {
                val twoDRms = sqrt(s.sigmaLat * s.sigmaLat + s.sigmaLon * s.sigmaLon)
                val r = twoDRms * confidenceMultiplier
                return AccuracyResult(
                    radiusMeters = r,
                    majorMeters = r,
                    minorMeters = r,
                    orientationDegrees = (s.orientation ?: 0.0),
                    method = AccuracyMethod.GST,
                    note = "used GST.sigmaLat/sigmaLon -> 2D RMS"
                )
            }
        }

        // 2) 回退到 GGA (hdop * UERE)
        gga?.let { g ->
            val fix = g.fixQuality ?: -1
            val hdop = (g.hdop ?: 1.0).coerceAtLeast(0.0)

            // 经验 UERE (m) — 可根据你设备调整
            val uere = when (fix) {
                4 -> 0.02   // RTK fixed: ~1-2 cm
                5 -> 0.25   // RTK float: 20~50 cm
                2 -> 1.0    // DGPS: ~1 m
                1 -> 5.0    // Single: ~5 m
                else -> 10.0
            }

            val radius = hdop * uere * confidenceMultiplier
            return AccuracyResult(
                radiusMeters = radius,
                majorMeters = radius,
                minorMeters = radius,
                orientationDegrees = 0.0,
                method = AccuracyMethod.GGA_FALLBACK,
                note = "used GGA.hdop * uere (fix=$fix, hdop=$hdop, uere=$uere)"
            )
        }

        // 无法估算
        return null
    }
}