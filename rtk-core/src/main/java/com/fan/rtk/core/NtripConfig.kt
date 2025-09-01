package com.fan.rtk.core

data class NtripConfig(
    var host: String = "",
    var port: Int = 0,
    var mountPoint: String = "",
    var username: String = "",
    var password: String = ""
)