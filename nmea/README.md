# NMEA

A lightweight Kotlin NMEA parsing library for GNSS/RTK applications.
Supports parsing standard and vendor-specific NMEA messages from byte streams or text lines.

## Features
- Parses standard NMEA sentences: GGA, RMC
- Supports custom/vendor messages: UNIHEADING, COMMAND
- verts latitude/longitude to decimal degrees
- Validates NMEA checksum automatically
- Designed with Kotlin Coroutines & Flow

## Installation

Available via [JitPack](https://jitpack.io/#lf7817/rtk).

### 1.Add JitPack repository

In your project’s settings.gradle.kts:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### 2.Add dependencies
In app/build.gradle.kts:

```kotlin
dependencies {
    implementation("com.github.lf7817.rtk:nmea:v1.0.0")
}
```

## Usage Guide

**1. Parse NMEA lines**

If you already have complete NMEA lines (without CRLF):

```kotlin
val lineFlow: Flow<String> = getLineFlowFromSerialOrBle

val messages = decodeLines(lineFlow)

messages.collect { msg ->
    when (msg) {
        is NmeaMessage.GGA -> println("Lat: ${msg.latitude}, Lon: ${msg.longitude}, Alt: ${msg.altitudeMsl}")
        is NmeaMessage.RMC -> println("Speed: ${msg.speedKnots} knots")
        is NmeaMessage.UniHeading -> println("Heading: ${msg.headingDeg}°")
        is NmeaMessage.Command -> println("Response: ${msg.response}")
    }
}
```

**2. Parse from byte stream (e.g., BLE, Serial)**

```kotlin
val byteFlow: Flow<ByteArray> = getBytesFromBle()

val messages = decodeBytes(byteFlow)

messages.collect { msg ->
    println("Received NMEA: ${msg.raw}")
}
```

**3. Aggregate byte stream into lines (manual)**

```kotlin
val aggregator = NmeaLineAggregator()

CoroutineScope(Dispatchers.IO).launch {
    aggregator.lines.collect { line ->
        println("NMEA line: $line")
    }
}

// Feed data from BLE/serial
aggregator.feed(byteArrayFromDevice)
```

## Supported Message Types

```kotlin
sealed class NmeaMessage {
    data class GGA(
        val timeUtc: String?, val latitude: Double?, val longitude: Double?,
        val fixQuality: Int?, val satellites: Int?, val hdop: Double?,
        val altitudeMsl: Double?, val geoidSeparation: Double?
    ) : NmeaMessage()

    data class RMC(
        val timeUtc: String?, val status: String?, val latitude: Double?,
        val longitude: Double?, val speedKnots: Double?, val courseDeg: Double?,
        val dateDdMmYy: String?
    ) : NmeaMessage()

    data class UniHeading(
        val headingDeg: Double?, val pitchDeg: Double?, val baselineM: Double?,
        val quality: String?
    ) : NmeaMessage()

    data class Command(
        val command: String, val response: String
    ) : NmeaMessage()
}
```

## Extending Parsers

You can register custom parsers via NmeaParserManager:

```kotlin
class MyCustomParser : NmeaParser<NmeaMessage> {
    override val type: String = "MYTYPE"
    override fun parse(sentence: String): NmeaMessage? {
        // Your custom parsing logic here
        return null
    }
}

val manager = NmeaParserManager(parsers = listOf(MyCustomParser(), GGAParser(), RMCParser()))
```