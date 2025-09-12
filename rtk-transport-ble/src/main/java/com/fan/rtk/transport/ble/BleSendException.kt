package com.fan.rtk.transport.ble

sealed class BleSendException(message: String) : Exception(message) {
    object PermissionNotGranted : BleSendException("BLE permission not granted")
    object Disabled : BleSendException("BLE is disabled")
    object NotConnected : BleSendException("BLE is not connected")
    object ServiceNotFound : BleSendException("BLE service not found")
    object CharacteristicNotFound : BleSendException("BLE write characteristic not found")
    class WriteFailed(message: String) : BleSendException(message)
}