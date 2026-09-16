package com.ustc.wifibss.model

/**
 * 信道利用界面中一个扫描到的 AP
 */
data class ChannelAp(
    val bssid: String,        // 12 位小写十六进制
    val ssid: String,         // 原始 SSID，空串表示隐藏
    val rssi: Int,
    val freqMhz: Int,
    val channel: Int,         // frequencyToChannel 结果，-1 表示未知
    val bandwidthMhz: Int,    // 20/40/80/160/320，未知时为 20
    val standard: String,     // "Wi-Fi 6 (802.11ax)" 等，空串表示未知
    val apName: String?,      // 已解析到的 AP 名字，null 表示未解析
    val building: String?
) {
    /**
     * 名称列显示文本：SSID（隐藏网络显示占位符）。不随 MAC/AP 切换变化。
     */
    fun ssidLabel(hiddenSsidLabel: String): String =
        if (ssid.isNotEmpty()) ssid else hiddenSsidLabel

    /**
     * 设备列显示文本：AP 模式显示查到的 AP 名字，未查到则回落 MAC；showApName=false 时始终显示 MAC。
     */
    fun deviceLabel(showApName: Boolean): String =
        if (showApName) apName?.takeIf { it.isNotBlank() && it != "-" } ?: bssid else bssid
}
