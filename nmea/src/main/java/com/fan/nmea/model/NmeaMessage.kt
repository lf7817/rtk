package com.fan.nmea.model

sealed class NmeaMessage {
    abstract val raw: String

    data class GGA(
        override val raw: String,
        val timeUtc: String?,
        val latitude: Double?,
        val longitude: Double?,
        val fixQuality: Int?,
        val satellites: Int?,
        val hdop: Double?,
        val altitudeMsl: Double?,
        val geoidSeparation: Double?
    ) : NmeaMessage()

    data class RMC(
        override val raw: String,
        val timeUtc: String?,
        val status: String?,  // A = valid, V = void
        val latitude: Double?,
        val longitude: Double?,
        val speedKnots: Double?,
        val courseDeg: Double?,
        val dateDdMmYy: String?
    ) : NmeaMessage()

    data class UniHeading(
        override val raw: String,
        val headingDeg: Double?,
        val pitchDeg: Double?,
        val baselineM: Double?,
        val quality: String? // free text / mode / status depending on firmware
    ) : NmeaMessage()

    // 新增 UM982 响应消息
    data class Command(
        override val raw: String,
        val command: String,
        val response: String
    ) : NmeaMessage()
}