package com.example.wifibssquery

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.wifibssquery.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val WIFI_PERMISSION_CODE = 1002
        private const val PREFS_NAME = "app_settings"
        private const val KEY_QUERY_URL = "query_url"
        private const val KEY_QUERY_KEY = "query_key"
        private const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        checkUpdate()
    }

    /**
     * 检查更新
     */
    private fun checkUpdate() {
        lifecycleScope.launch {
            try {
                val versionInfo = fetchVersionInfo()
                withContext(Dispatchers.Main) {
                    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        packageManager.getPackageInfo(packageName, 0)
                    }
                    val currentVersionCode = packageInfo.longVersionCode.toInt()
                    if (versionInfo != null && versionInfo.versionCode > currentVersionCode) {
                        showUpdateDialog(versionInfo, packageInfo)
                    }
                }
            } catch (e: Exception) {
                // 检查更新失败，静默处理
            }
        }
    }

    /**
     * 获取服务器版本信息
     */
    private suspend fun fetchVersionInfo(): VersionInfo? = withContext(Dispatchers.IO) {
        val url = "https://noc.ustc.edu.cn/version.json"
        val request = Request.Builder()
            .url(url)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: return@withContext null)
                    VersionInfo(
                        versionCode = json.optInt("versionCode", 0),
                        versionName = json.optString("versionName", ""),
                        updateUrl = json.optString("updateUrl", ""),
                        updateLog = json.optString("updateLog", "")
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 显示更新对话框
     */
    private fun showUpdateDialog(versionInfo: VersionInfo, packageInfo: android.content.pm.PackageInfo) {
        val currentVersionStr = "v${packageInfo.versionName} (${packageInfo.longVersionCode})"
        val newVersionStr = "v${versionInfo.versionName} (${versionInfo.versionCode})"

        val message = """
            当前版本：$currentVersionStr
            最新版本：$newVersionStr

            ${versionInfo.updateLog.ifEmpty { "发现新版本，是否立即下载更新？" }}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(R.string.new_version_found)
            .setMessage(message)
            .setPositiveButton(R.string.download_now) { _, _ ->
                downloadApk(versionInfo.updateUrl)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 下载 APK
     */
    private fun downloadApk(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(url)
        startActivity(intent)
    }

    /**
     * 版本信息数据类
     */
    private data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val updateUrl: String,
        val updateLog: String
    )

    override fun onResume() {
        super.onResume()
        updateWifiInfo()
    }

    /**
     * 更新所有 WiFi 信息显示
     */
    private fun updateWifiInfo() {
        if (!checkPermissions()) {
            requestPermissions()
            clearWifiInfo()
            return
        }

        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo == null || wifiInfo.bssid == null) {
            clearWifiInfo()
            return
        }

        // SSID
        val ssid = wifiInfo.ssid.removeSurroundingQuotes()
        binding.tvSsid.text = ssid.ifEmpty { getString(R.string.no_wifi_connection) }

        // BSSID
        val bssid = getFormattedBssid()
        binding.tvBssidValue.text = bssid ?: getString(R.string.no_wifi_connection)
        binding.tvWifiBssid.text = bssid ?: getString(R.string.no_wifi_connection)

        // IP 地址
        val ip = formatIpAddress(wifiInfo.ipAddress)
        binding.tvIp.text = ip

        // 信号强度 RSSI
        val rssi = wifiInfo.rssi
        binding.tvRssi.text = "$rssi dBm (${getSignalLevel(rssi)})"

        // 频率/信道
        val freq = wifiInfo.frequency
        val channel = frequencyToChannel(freq)
        val band = when {
            freq < 2500 -> "2.4GHz"
            freq < 5000 -> "5GHz"
            else -> "6GHz"
        }
        binding.tvFrequency.text = "$freq MHz (信道 $channel, $band)"

        // 链路速度
        binding.tvLinkSpeed.text = "${wifiInfo.linkSpeed} Mbps"
    }

    /**
     * 清空 WiFi 信息显示
     */
    private fun clearWifiInfo() {
        binding.tvSsid.text = getString(R.string.no_wifi_connection)
        binding.tvWifiBssid.text = getString(R.string.no_wifi_connection)
        binding.tvIp.text = "-"
        binding.tvRssi.text = "-"
        binding.tvFrequency.text = "-"
        binding.tvLinkSpeed.text = "-"
    }

    /**
     * 格式化 IP 地址 (int 转 IPv4)
     */
    private fun formatIpAddress(ip: Int): String {
        if (ip == 0) return "-"
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /**
     * 频率转信道
     */
    private fun frequencyToChannel(freq: Int): Int {
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
    private fun getSignalLevel(rssi: Int): String {
        return when {
            rssi >= -50 -> getString(R.string.signal_excellent)
            rssi >= -60 -> getString(R.string.signal_good)
            rssi >= -70 -> getString(R.string.signal_fair)
            rssi >= -80 -> getString(R.string.signal_weak)
            else -> getString(R.string.signal_poor)
        }
    }

    /**
     * 移除 SSID 周围的引号
     */
    private fun String.removeSurroundingQuotes(): String {
        return if (startsWith("\"") && endsWith("\"")) {
            substring(1, length - 1)
        } else {
            this
        }
    }

    private fun setupUI() {
        binding.btnQuery.setOnClickListener {
            val bssid = getFormattedBssid()
            if (bssid != null && bssid.length == 12) {
                // 更新界面显示的 BSSID
                binding.tvBssidValue.text = bssid
                queryBssInfo(bssid)
            } else {
                binding.tvResult.text = getString(R.string.no_wifi_connection)
            }
        }
    }

    /**
     * 显示关于对话框
     */
    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvVersion)
        val tvDescription = dialogView.findViewById<TextView>(R.id.tvDescription)
        val tvChanges = dialogView.findViewById<TextView>(R.id.tvChanges)
        val tvAuthor = dialogView.findViewById<TextView>(R.id.tvAuthor)

        tvVersion.text = getVersionInfo()
        tvDescription.text = getDescriptionText()
        tvChanges.text = getChangesText()
        tvAuthor.text = getAuthorText()

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 获取版本信息
     */
    private fun getVersionInfo(): String {
        return "版本：1.5"
    }

    /**
     * 获取功能说明
     */
    private fun getDescriptionText(): String {
        return getString(R.string.about_description)
    }

    /**
     * 获取重大更新内容
     */
    private fun getChangesText(): String {
        return """
v1.5 更新说明
- 优化关于对话框内容和布局

v1.4 布局优化
- BSSID 居中显示
- BSS 信息使用卡片展示，与 WiFi 信息样式一致

v1.3 关于对话框优化
- 支持滚动显示
- 分段展示：功能说明、重大更新、开发信息
- 增加各版本重大变化说明

v1.2 新增 BSS MAC 显示
- 在查询结果最上方显示返回的 BSS MAC 地址

v1.1 菜单功能和 WiFi 信息增强
- 添加右上角菜单（设置、关于）
- 设置可配置查询 URL 和 KEY（Authorization: Bearer）
- WiFi 信息卡片显示：SSID、BSSID、IP 地址、信号强度、频率/信道、链路速度
- 信号强度分级显示（优秀/良好/一般/较差/弱）
- 自动感知 WiFi 连接变化并刷新显示

v1.0 初始版本
- 获取当前 WiFi BSSID
- 查询 BSS 信息并显示
        """.trimIndent()
    }

    /**
     * 获取作者信息
     */
    private fun getAuthorText(): String {
        return "作者：james@ustc.edu.cn \n2026"
    }

    /**
     * 显示设置对话框
     */
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etQueryUrl = dialogView.findViewById<EditText>(R.id.etQueryUrl)
        val etQueryKey = dialogView.findViewById<EditText>(R.id.etQueryKey)

        // 加载当前设置
        etQueryUrl.setText(getQueryUrl())
        etQueryKey.setText(getQueryKey())

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                // 保存设置
                saveSettings(etQueryUrl.text.toString(), etQueryKey.text.toString())
                Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 获取查询 URL
     */
    private fun getQueryUrl(): String {
        return prefs.getString(KEY_QUERY_URL, DEFAULT_QUERY_URL) ?: DEFAULT_QUERY_URL
    }

    /**
     * 获取查询 KEY
     */
    private fun getQueryKey(): String {
        return prefs.getString(KEY_QUERY_KEY, "") ?: ""
    }

    /**
     * 保存设置
     */
    private fun saveSettings(url: String, key: String) {
        prefs.edit()
            .putString(KEY_QUERY_URL, url.ifEmpty { DEFAULT_QUERY_URL })
            .putString(KEY_QUERY_KEY, key)
            .apply()
    }

    /**
     * 创建菜单
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    /**
     * 处理菜单点击
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                showSettingsDialog()
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 获取并格式化 BSSID
     * 移除所有非十六进制字符（如 : - 等），只保留 12 位十六进制字符
     */
    private fun getFormattedBssid(): String? {
        val bssid = wifiManager.connectionInfo.bssid ?: return null

        // 移除所有非十六进制字符，只保留 0-9, a-f, A-F
        return bssid.replace(Regex("[^0-9a-fA-F]"), "").lowercase()
    }

    /**
     * 更新界面显示的 BSSID
     */
    private fun updateBssid() {
        if (!checkPermissions()) {
            requestPermissions()
            binding.tvBssidValue.text = "等待权限..."
            return
        }

        val formattedBssid = getFormattedBssid()
        binding.tvBssidValue.text = formattedBssid ?: getString(R.string.no_wifi_connection)
    }

    /**
     * 查询 BSS 信息
     */
    private fun queryBssInfo(bssid: String) {
        binding.tvResult.text = getString(R.string.querying)
        // 清空之前的 AP 信息显示
        binding.tvBssMac.text = "-"
        binding.tvAcIp.text = "-"
        binding.tvApIp.text = "-"
        binding.tvApName.text = "-"
        binding.tvApSn.text = "-"
        binding.tvApBuilding.text = "-"
        binding.btnQuery.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = fetchBssInfo(bssid)
                withContext(Dispatchers.Main) {
                    binding.tvResult.text = result.ifEmpty { "无相关信息" }
                    parseAndDisplayApInfo(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvResult.text = "${getString(R.string.query_error)}: ${e.message}"
                    Toast.makeText(this@MainActivity, getString(R.string.query_error), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnQuery.isEnabled = true
                }
            }
        }
    }

    /**
     * 解析 API 返回的 JSON 数据并显示 AP 信息
     */
    private fun parseAndDisplayApInfo(jsonString: String) {
        if (jsonString.isEmpty()) return

        try {
            val json = JSONObject(jsonString)

            // 检查是否返回成功
            val status = json.optString("status", "")
            if (status != "ok") {
                val message = json.optString("message", "查询失败")
                binding.tvResult.text = message
                return
            }

            // 数据可能在 data 数组中
            val data = json.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val item = data.getJSONObject(0)
                binding.tvBssMac.text = item.optString("BSS_MAC", "-")
                binding.tvAcIp.text = item.optString("AC_IP", "-")
                binding.tvApIp.text = item.optString("AP_IP", "-")
                binding.tvApName.text = item.optString("AP_NAME", "-")
                binding.tvApSn.text = item.optString("AP_SN", "-")
                binding.tvApBuilding.text = item.optString("AP_Building", "-")
            }
        } catch (e: Exception) {
            // JSON 解析失败，可能是返回的数据格式不对
        }
    }

    /**
     * 调用 API 获取 BSS 信息
     */
    private suspend fun fetchBssInfo(bssid: String): String = withContext(Dispatchers.IO) {
        val baseUrl = getQueryUrl()
        val queryKey = getQueryKey()
        val url = "$baseUrl?bssid=$bssid"

        val requestBuilder = Request.Builder().url(url)

        // 如果设置了查询 KEY，添加 Authorization 头
        if (queryKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $queryKey")
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error: ${response.code}")
            }
            response.body?.string() ?: "无数据"
        }
    }

    /**
     * 检查所需权限
     */
    private fun checkPermissions(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val wifiPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            locationGranted && wifiPermission
        } else {
            locationGranted
        }
    }

    /**
     * 请求权限
     */
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            LOCATION_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    updateBssid()
                } else {
                    Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
