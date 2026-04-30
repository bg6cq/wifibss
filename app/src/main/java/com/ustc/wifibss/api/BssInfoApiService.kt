package com.ustc.wifibss.api

import com.ustc.wifibss.data.AppPreferences
import com.ustc.wifibss.model.ApInfo
import com.ustc.wifibss.util.WifiUtils
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BssInfoApiService(
    private val prefs: AppPreferences,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val apInfoCache = mutableMapOf<String, CacheEntry>()
    private data class CacheEntry(val result: ApInfo, val cachedAt: Long)
    private val cacheDurationMs = 10 * 60 * 1000L

    suspend fun queryBssInfo(bssid: String): ApInfo = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (prefs.isCacheApInfoEnabled()) {
            val cached = apInfoCache[bssid]
            if (cached != null && System.currentTimeMillis() - cached.cachedAt < cacheDurationMs) {
                return@withContext cached.result
            }
        }

        val url = "${prefs.getQueryUrl()}?bssid=$bssid"
        val requestBuilder = Request.Builder().url(url)
        val queryKey = prefs.getQueryKey()
        if (queryKey.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer $queryKey")

        val result = httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP error: ${response.code}")
            response.body?.string() ?: "无数据"
        }

        val apInfo = parseBssInfoJson(result)
        if (apInfo != null && prefs.isCacheApInfoEnabled()) {
            apInfoCache[bssid] = CacheEntry(apInfo, System.currentTimeMillis())
        }
        apInfo ?: ApInfo.empty()
    }

    suspend fun queryNearbyApName(bssid: String): String? {
        return try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val apInfo = queryBssInfo(bssid)
                if (apInfo.apName != "-") apInfo.apName else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() {
        apInfoCache.clear()
    }

    private fun parseBssInfoJson(jsonString: String): ApInfo? {
        if (jsonString.isBlank()) return null
        return try {
            val json = JSONObject(jsonString)
            if (json.optString("status", "") != "ok") return null
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val item = data.getJSONObject(0)
            ApInfo(
                bssMac = item.optString("BSS_MAC", "-"),
                apName = item.optString("AP_NAME", "-"),
                apSn = item.optString("AP_SN", "-"),
                acIp = item.optString("AC_IP", "-"),
                apIp = item.optString("AP_IP", "-"),
                building = item.optString("AP_Building", "-")
            )
        } catch (_: Exception) {
            null
        }
    }

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val updateUrl: String,
        val updateLog: String
    )

    companion object {
        private const val VERSION_URL = "https://noc.ustc.edu.cn/version.json"

        suspend fun checkVersion(prefs: AppPreferences): VersionInfo? {
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            return try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val json = JSONObject(response.body?.string() ?: return@withContext null)
                        VersionInfo(
                            versionCode = json.optInt("versionCode", 0),
                            versionName = json.optString("versionName", ""),
                            updateUrl = json.optString("updateUrl", ""),
                            updateLog = json.optString("updateLog", "")
                        )
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
