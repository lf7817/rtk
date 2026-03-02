package com.fan.rtk.transport.usb

sealed class UsbSendException(message: String) : Exception(message) {
    object PermissionDenied : UsbSendException("USB device permission not granted") {
        private fun readResolve(): Any = PermissionDenied
    }

    object NotConnected : UsbSendException("USB serial is not connected") {
        private fun readResolve(): Any = NotConnected
    }

    object DeviceNotSupported : UsbSendException("USB device is not supported (e.g. not CH34x)") {
        private fun readResolve(): Any = DeviceNotSupported
    }

    class WriteFailed(message: String) : UsbSendException(message)
}
