package com.fan.nmea.model

enum class AccuracyMethod { GST, GGA_FALLBACK }

data class AccuracyResult(
    val radiusMeters: Double,      //用于绘制圆的半径（米），若有椭圆信息通常取 major 或 2D RMS
    val majorMeters: Double,       // 椭圆长半轴 (m)
    val minorMeters: Double,       // 椭圆短半轴 (m)
    val orientationDegrees: Double,// 椭圆方向（度，0 = north），如果无则 0
    val method: AccuracyMethod,
    val note: String? = null
)