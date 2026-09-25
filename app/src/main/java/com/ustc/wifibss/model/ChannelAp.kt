package com.ustc.wifibss.model

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

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
    val building: String?,
    // 以下为扫描结果里的原始字段，仅详情弹窗使用
    val centerFreq0: Int = 0, // 第一段中心频率，0 表示厂商未上报
    val centerFreq1: Int = 0, // 80+80 的第二段中心频率
    val widthRaw: Int = 0,    // ScanResult.channelWidth 常量，用于区分 80+80
    val capabilities: String = "" // ScanResult.capabilities 原文，如 "[WPA2-PSK-CCMP][ESS]"
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

    /**
     * 带分隔符的 MAC，便于阅读（48:a9:8a:63:43:07）
     */
    fun macWithColons(): String =
        bssid.chunked(2).joinToString(":")

    /**
     * 是否加密。开放网络的能力串里通常只有 [ESS]，没有任何加密套件。
     */
    fun isSecured(): Boolean {
        val caps = capabilities.uppercase()
        return caps.contains("WEP") || caps.contains("WPA") || caps.contains("RSN")
    }

    /**
     * 加密方式代码：WPA3 / WPA2 / WPA / WEP，开放网络返回空串。
     * 只返回代码，由界面层映射到字符串资源。
     */
    fun securityCode(): String {
        val caps = capabilities.uppercase()
        return when {
            caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
            caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            else -> ""
        }
    }

    /**
     * 能力串拆成逐项文本，如 "[WPA2-PSK-CCMP][ESS]" → ["[WPA2-PSK-CCMP]", "[ESS]"]。
     * 与 WiFi Analyzer 详情弹窗的展示方式一致。
     */
    fun capabilityTokens(): List<String> {
        if (capabilities.isBlank()) return emptyList()
        return Regex("\\[[^\\]]*\\]").findAll(capabilities).map { it.value }.toList()
    }

    /**
     * 绑定块的中心频率（MHz）。优先用 ScanResult.centerFreq0——它是块位置的唯一权威
     * 来源：主信道可以在绑定块内的任意位置（ch36/80MHz 峰在块最左端，ch157/80MHz
     * 则偏右），光凭主信道推不出块在哪。厂商未上报时退化为以主信道为中心。
     */
    fun blockCenterMhz(): Int = centerFreq0.takeIf { it > 0 } ?: freqMhz

    /**
     * 实际占用的频率范围（MHz），80+80 有两个不相邻的段，因此返回列表。
     * 与频谱图 drawCurve 用同一套块定位规则，两处不会打架。
     */
    fun occupiedRangesMhz(): List<IntRange> {
        if (bandwidthMhz <= 0 || freqMhz <= 0) return emptyList()
        val half = bandwidthMhz / 2

        // 80+80：两段各 80MHz，第二段只能来自 centerFreq1
        if (widthRaw == WIDTH_80MHZ_PLUS && centerFreq1 > 0) {
            return listOf(
                (blockCenterMhz() - half)..(blockCenterMhz() + half),
                (centerFreq1 - half)..(centerFreq1 + half)
            )
        }

        val center = blockCenterMhz()
        return listOf((center - half)..(center + half))
    }

    /**
     * 绑定后的中心信道号（80MHz 的 ch36-48 对应 42）。纯推导，不依赖 centerFreq0。
     * 未绑定（20MHz）时返回 -1。
     */
    fun centerChannel(): Int {
        val range = occupiedRangesMhz().firstOrNull() ?: return -1
        if (range.last - range.first <= 20) return -1
        return com.ustc.wifibss.util.WifiUtils.frequencyToChannel((range.first + range.last) / 2)
    }

    /**
     * 绑定覆盖的信道跨度（首信道、末信道），如 ch36/80MHz → (36, 48)。
     * 20MHz 时两者相同；无法推算时返回 null。
     *
     * 取占用范围两端各内缩 10MHz 得到首个/末个 20MHz 信道的主频，
     * 这样 2.4G（信道间距 5MHz、20MHz 带宽互相重叠）与 5G 都能算对。
     */
    fun channelSpan(): Pair<Int, Int>? {
        val range = occupiedRangesMhz().firstOrNull() ?: return null
        val first = com.ustc.wifibss.util.WifiUtils.frequencyToChannel(range.first + 10)
        val last = com.ustc.wifibss.util.WifiUtils.frequencyToChannel(range.last - 10)
        if (first <= 0 || last <= 0) return null
        return first to last
    }

    /**
     * 估算距离（米）：自由空间路径损耗模型，与 WiFi Analyzer 的取值一致。
     * 只是粗略估计——实际受墙体遮挡与 AP 发射功率影响，仅供相对远近参考。
     */
    fun estimatedDistanceMeters(): Double? {
        if (freqMhz <= 0 || rssi >= 0) return null
        return 10.0.pow((27.55 - 20.0 * log10(freqMhz.toDouble()) + abs(rssi)) / 20.0)
    }

    companion object {
        // ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ 的值
        private const val WIDTH_80MHZ_PLUS = 3

        /**
         * 按设备列显示内容（deviceLabel）聚合：同一设备的多个 SSID 合并为一行。
         * showApName=true 时按 AP 名聚合（同一 AP 的不同 BSSMAC 也合并），
         * 未解析到名字的按各自 BSSID 单独成组；showApName=false 时按 BSSID 聚合。
         * 名称列为各 SSID 按出现顺序以 "/" 连接（隐藏 SSID 用占位符代替），
         * 信号取最强者，其余列取信号最强记录。
         */
        fun aggregate(aps: List<ChannelAp>, hiddenSsidLabel: String, showApName: Boolean): List<ChannelAp> {
            if (aps.size < 2) return aps
            return aps.groupBy { it.deviceLabel(showApName) }.map { (_, group) ->
                if (group.size == 1) return@map group.first()
                val best = group.maxByOrNull { it.rssi } ?: group.first()
                best.copy(
                    ssid = group.map { it.ssid.ifEmpty { hiddenSsidLabel } }.distinct().joinToString("/"),
                    rssi = group.maxOf { it.rssi }
                )
            }.sortedByDescending { it.rssi }
        }
    }
}
