package com.ustc.wifibss.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * MAC 厂商查询：https://ip.ustc.edu.cn/mac/<12 位十六进制 MAC>
 *
 * 接口只按前 6 位（OUI）匹配，返回纯文本厂商名；查不到时返回「未知」。
 * 结果在内存里按 OUI 缓存——相邻 AP 常来自同一厂商，重复点开同一行不必再查。
 */
object MacVendorService {

    private const val BASE_URL = "https://ip.ustc.edu.cn/mac/"
    private const val UNKNOWN = "未知"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, String>()

    /**
     * 查询厂商名。查不到返回 null（调用方据此显示"未知"），不抛异常。
     */
    suspend fun lookupVendor(mac: String): String? = withContext(Dispatchers.IO) {
        val normalized = mac.lowercase().filter { it in "0123456789abcdef" }
        if (normalized.length != 12) return@withContext null

        // 接口只看 OUI，缓存也按 OUI 存
        val oui = normalized.take(6)
        cache[oui]?.let { return@withContext it }

        try {
            val request = Request.Builder().url("$BASE_URL$normalized").build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()?.trim() ?: return@withContext null
            }
            val vendor = body.takeIf { it.isNotEmpty() && it != UNKNOWN } ?: return@withContext null
            cache[oui] = vendor
            vendor
        } catch (_: Exception) {
            null
        }
    }
}
