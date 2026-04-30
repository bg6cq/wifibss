package com.ustc.wifibss.model

/**
 * AP 信息数据类
 * @param bssMac BSS MAC 地址
 * @param apName AP 名称
 * @param apSn AP 序列号
 * @param acIp AC IP 地址
 * @param apIp AP IP 地址
 * @param building 所在楼栋
 */
data class ApInfo(
    val bssMac: String,
    val apName: String,
    val apSn: String,
    val acIp: String,
    val apIp: String,
    val building: String
) {
    companion object {
        fun empty() = ApInfo("-", "-", "-", "-", "-", "-")
    }
}
