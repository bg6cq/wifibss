package com.ustc.wifibss.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MAC 厂商查询：https://ip.ustc.edu.cn/mac/<12 位十六进制 MAC>
 *
 * 接口只按前 6 位（OUI）匹配，返回纯文本厂商名；查不到时返回「未知」。
 * 结果在内存里按 OUI 缓存——相邻 AP 常来自同一厂商，重复点开同一行不必再查。
 *
 * 网络异常会退避重试：弹窗只查一次，一次网络抖动就让厂商永远显示"未知"没有意义。
 */
object MacVendorService {

    private const val BASE_URL = "https://ip.ustc.edu.cn/mac/"
    private const val UNKNOWN = "未知"

    // 重试次数与退避基数（1s、2s）
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BASE_MS = 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, String>()

    /**
     * 查询厂商名。接口正常应答但没有该厂商、或重试后仍失败，都返回 null
     * （调用方据此显示"未知"），不抛异常。
     */
    suspend fun lookupVendor(mac: String): String? = withContext(Dispatchers.IO) {
        val normalized = mac.lowercase().filter { it in "0123456789abcdef" }
        if (normalized.length != 12) return@withContext null

        // 接口只看 OUI，缓存也按 OUI 存
        val oui = normalized.take(6)
        cache[oui]?.let { return@withContext it }

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder().url("$BASE_URL$normalized").build()
                val body = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string()?.trim().orEmpty()
                }
                // 接口答得很明确：这个 OUI 不在库里。重试也不会有，直接返回
                if (body.isEmpty() || body == UNKNOWN) return@withContext null
                cache[oui] = body
                return@withContext body
            } catch (cancelled: CancellationException) {
                // 弹窗已关闭：协程取消不能被当成查询失败而继续重试
                throw cancelled
            } catch (_: Exception) {
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_BASE_MS * (attempt + 1))
            }
        }
        null
    }
}
