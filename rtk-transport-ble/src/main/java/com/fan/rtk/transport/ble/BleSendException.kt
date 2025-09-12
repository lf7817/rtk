package com.fan.rtk.transport.ble

sealed class BleSendException(message: String) : Exception(message) {
    object PermissionNotGranted : BleSendException("BLE permission not granted") {
        private fun readResolve(): Any = PermissionNotGranted
    }

    object Disabled : BleSendException("BLE is disabled") {
        private fun readResolve(): Any = Disabled
    }

    object NotConnected : BleSendException("BLE is not connected") {
        private fun readResolve(): Any = NotConnected
    }

    object ServiceNotFound : BleSendException("BLE service not found") {
        private fun readResolve(): Any = ServiceNotFound
    }

    object CharacteristicNotFound : BleSendException("BLE write characteristic not found") {
        private fun readResolve(): Any = CharacteristicNotFound
    }

    class WriteFailed(message: String) : BleSendException(message)
}