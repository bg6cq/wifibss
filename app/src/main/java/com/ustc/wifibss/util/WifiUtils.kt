package com.ustc.wifibss.util

import java.util.regex.Pattern

/**
 * WiFi 相关工具方法
 */
object WifiUtils {

    private val HEX_PATTERN = Pattern.compile("[^0-9a-fA-F]")

    /**
     * 格式化 BSSID：移除非十六进制字符，转为小写
     */
    fun formatBssid(bssid: String?): String? {
        bssid ?: return null
        return HEX_PATTERN.matcher(bssid).replaceAll("").lowercase()
    }

    /**
     * 标准化 BSS MAC 格式（支持多种输入格式）
     * 返回 12 位十六进制字符串，或 null（如果格式无效）
     */
    fun normalizeBssMac(input: String): String? {
        val hexOnly = HEX_PATTERN.matcher(input).replaceAll("")
        return if (hexOnly.length == 12) hexOnly.lowercase() else null
    }

    /**
     * 移除 SSID 周围的引号
     */
    fun String.removeSurroundingQuotes(): String {
        return if (length >= 2 && startsWith("\"") && endsWith("\"")) {
            substring(1, length - 1)
        } else {
            this
        }
    }

    /**
     * 格式化 IP 地址 (int 转 IPv4)
     */
    fun formatIpAddress(ip: Int): String {
        if (ip == 0) return "-"
        return "${ip and 0xFF}.${(ip ushr 8) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 24) and 0xFF}"
    }

    /**
     * 频率转信道
     */
    fun frequencyToChannel(freq: Int): Int {
        return when {
            freq <= 0 -> -1
            freq <= 2484 -> when (freq) {
                2484 -> 14
                else -> (freq - 2407) / 5
            }
            freq in 5000..5900 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5 + 1
            else -> -1
        }
    }

    /**
     * 获取信号强度等级
     */
    fun getSignalLevel(rssi: Int): SignalLevel {
        return when {
            rssi >= -50 -> SignalLevel.EXCELLENT
            rssi >= -60 -> SignalLevel.GOOD
            rssi >= -70 -> SignalLevel.FAIR
            rssi >= -80 -> SignalLevel.WEAK
            else -> SignalLevel.POOR
        }
    }

    /**
     * 根据频率判断频段
     */
    fun getBand(freq: Int): String {
        return when {
            freq <= 0 -> ""
            freq < 2500 -> "2.4GHz"
            freq < 5925 -> "5GHz"
            else -> "6GHz"
        }
    }

    /**
     * 将信道宽度常量转为可读字符串
     */
    fun channelWidthToString(width: Int): String {
        return when (width) {
            android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
            android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
            android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
            android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
            android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
            android.net.wifi.ScanResult.CHANNEL_WIDTH_320MHZ -> "320 MHz"
            else -> ""
        }
    }

    enum class SignalLevel(val label: String) {
        EXCELLENT("优秀"),
        GOOD("良好"),
        FAIR("一般"),
        WEAK("较差"),
        POOR("弱")
    }
}
