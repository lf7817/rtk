# RTK

An Android-based RTK (Real-Time Kinematic) positioning and data transmission solution, supporting NMEA parsing, RTK core algorithms, and transport layer implementations (generic + BLE).
This project is designed with a modular architecture, allowing flexible composition and integration.

## Features
- NMEA Support: Parse and generate NMEA sentences (GGA, RMC, etc.)
- RTK Core Module: Encapsulates RTK positioning logic, state management, and command interactions
- Multiple Transport Options:
- rtk-transport: Generic transport layer
- rtk-transport-ble: BLE (Bluetooth Low Energy) transport
- Built with Kotlin + Coroutines, extensible and easy to integrate

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

**Option A: Import individual modules**

```kotlin   
dependencies {
    // NMEA parsing module
    implementation("com.github.lf7817.rtk:nmea:v1.0.0")

    // Core module
    implementation("com.github.lf7817.rtk:rtk-core:v1.0.0")

    // Generic transport module
    implementation("com.github.lf7817.rtk:rtk-transport:v1.0.0")

    // BLE transport module
    implementation("com.github.lf7817.rtk:rtk-transport-ble:v1.0.0")
}
```

**Option B: Import the full package**

```kotlin
dependencies {
    implementation("com.github.lf7817:rtk:v1.0.0")
}
```
> Use a released tag (e.g. v1.0.0) or master-SNAPSHOT for the latest build.

## Usage Guide

**1. Initialize Transport and RTK Manager**

```kotlin
val transport = RtkTransportBle(context, scope)
val rtkManager = RtkManager(scope, transport)
```

**2. Connect to BLE Device**

```kotlin
transport.connect(
    address,
    serviceUuid,
    writeCharacteristicUuid,
    notifyCharacteristicUuid,
    expiredMtu
)
```

**3. Connect to NTRIP Server(Auto-Reconnect)**

```kotlin
rtkManager.connect(
    ntripConfig,
    reconnectIntervalMs = 3000L,
    ggaTimeoutMs = 30_000L
)
```

**4. Collect NMEA Messages**

```kotlin
scope.launch {
    rtkManager.nmeaMessages.collect {
        Log.d("RTKManager", "Received NMEA: $it")
    }
}
```

**5. Disconnect**

```kotlin
rtkManager.disconnect()
```

**6.Monitor NTRIP & Transport Connection State**

```kotlin
// NTRIP connection state
rtkManager.connectionState.collect { state ->
    when (state) {
        ConnectionState.CONNECTED -> println("NTRIP connected")
        ConnectionState.CONNECTING -> println("Connecting to NTRIP server...")
        ConnectionState.DISCONNECTED -> println("NTRIP disconnected")
    }
}

// Transport (BLE/Serial) connection state
transport.connectionState.collect { state ->
    when (state) {
        ConnectionState.CONNECTED -> println("Transport connected")
        ConnectionState.CONNECTING -> println("Connecting to transport...")
        ConnectionState.DISCONNECTED -> println("Transport disconnected")
    }
}
```


## Modules

- nmea: NMEA sentence parsing and generation
- rtk-core: Core RTK positioning logic (includes RtkManager)
- rtk-transport: Transport abstraction layer (e.g., serial, TCP)
- rtk-transport-ble: BLE (Bluetooth Low Energy) transport implementation