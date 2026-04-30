package com.ustc.wifibss.model

/**
 * 查询历史记录数据类
 * @param timestamp 查询时间戳
 * @param bssid BSSID
 * @param apName AP 名称
 * @param building 所在楼栋
 */
data class QueryHistory(
    val timestamp: Long,
    val bssid: String,
    val apName: String,
    val building: String
)
