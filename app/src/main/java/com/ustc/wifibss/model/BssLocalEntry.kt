package com.ustc.wifibss.model

/**
 * 本地 BSS MAC 数据类
 * @param bssMac BSS MAC 地址（12 位十六进制）
 * @param apName AP 名称
 * @param building 所在楼栋
 */
data class BssLocalEntry(
    val bssMac: String,
    val apName: String,
    val building: String
)
