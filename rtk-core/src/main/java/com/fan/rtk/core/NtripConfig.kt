package com.fan.rtk.core

data class NtripConfig(
    var host: String = "",
    var port: Int = 0,
    var mountPoint: String = "",
    var username: String = "",
    var password: String = "",
    var ntripVersion: String? = "1.0" // NTRIP 版本，默认为 1.0，设为 null 或空字符串则不发送该头
)