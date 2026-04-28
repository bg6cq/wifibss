package com.ustc.wifibss

import android.Manifest
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ustc.wifibss.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val PREFS_NAME = "app_settings"
        private const val KEY_QUERY_URL = "query_url"
        private const val KEY_QUERY_KEY = "query_key"
        private const val KEY_AUTO_QUERY = "auto_query"
        private const val KEY_AUTO_REFRESH = "auto_refresh"
        private const val KEY_HISTORY = "query_history"
        private const val KEY_BSS_LOCAL = "bss_local_data"
        private const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
        private const val MAX_HISTORY_COUNT = 50
    }

    // 历史记录数据类
    data class QueryHistory(
        val timestamp: Long,
        val bssid: String,
        val apName: String,
        val building: String
    )

    // 本地 BSS MAC 数据类
    data class BssLocalEntry(
        val bssMac: String,
        val apName: String,
        val building: String
    )

    private var lastBssid: String? = null
    private var autoQueryRetryCount = 0
    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var autoRefreshIntervalMs: Int = 0 // 0 = 不刷新
    private var bssidChangedForChart: Boolean = false // 标记当前刷新是否 BSSID 变化

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            exportBssLocalToFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 保持屏幕唤醒
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 加载自动刷新设置
        autoRefreshIntervalMs = getAutoRefreshInterval()

        setupUI()
        setupRssiChart()
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        updateWifiInfo()
        restartAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
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

    /**
     * 更新所有 WiFi 信息显示
     */
    private fun updateWifiInfo() {
        if (!checkPermissions()) {
            showPermissionRationale()
            clearWifiInfo()
            return
        }

        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo == null || wifiInfo.bssid == null) {
            clearWifiInfo()
            lastBssid = null
            return
        }

        // SSID
        val ssid = wifiInfo.ssid.removeSurroundingQuotes()
        binding.tvSsid.text = ssid.ifEmpty { getString(R.string.no_wifi_connection) }

        // BSSID
        val bssid = getFormattedBssid()
        binding.tvBssidValue.text = bssid ?: getString(R.string.no_wifi_connection)

        // 检测 BSSID 变化
        bssidChangedForChart = (bssid != null && bssid != lastBssid)

        // 检测 BSSID 变化
        if (bssid != null && bssid != lastBssid) {
            lastBssid = bssid
            // 记录历史（即使没有查询到数据）
            addHistoryRecord(bssid, "", "")

            // 无论自动查询是否开启，都先检查本地数据库
            val localData = getBssLocalList().find { it.bssMac == bssid }
            if (localData != null) {
                displayLocalBssInfo(localData)
            } else if (getAutoQuery() && bssid.length == 12) {
                // 本地无数据且开启了自动查询，才调用远程 API
                autoQueryRetryCount = 0
                queryBssInfoWithRetry(bssid)
            }
        }

        // IP 地址
        val ip = formatIpAddress(wifiInfo.ipAddress)
        binding.tvIp.text = ip

        // 信号强度 RSSI
        val rssi = wifiInfo.rssi
        binding.tvRssi.text = "$rssi dBm (${getSignalLevel(rssi)})"

        // 添加 RSSI 数据点到图表
        addRssiDataPoint(rssi)

        // 频率/信道
        val freq = wifiInfo.frequency
        val channel = frequencyToChannel(freq)
        val band = when {
            freq <= 0 -> ""
            freq < 2500 -> "2.4GHz"
            freq < 5925 -> "5GHz"
            else -> "6GHz"
        }
        val bandText = if (band.isNotEmpty()) " ($band)" else ""
        binding.tvFrequency.text = "$freq MHz (信道 $channel)$bandText"

        // 链路速度和 WiFi 标准
        val wifiStandard = getWifiStandard(wifiInfo)
        val standardText = if (wifiStandard.isNotEmpty()) " ($wifiStandard)" else ""
        binding.tvLinkSpeed.text = "${wifiInfo.linkSpeed} Mbps$standardText"
    }

    /**
     * 获取 WiFi 技术标准 (802.11 a/b/g/n/ac/ax/be)
     * Android 11+ 使用官方 API，旧版本不显示
     */
    private fun getWifiStandard(wifiInfo: android.net.wifi.WifiInfo): String {
        // Android 11+ (API 30) 使用官方 API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val standard = wifiInfo.wifiStandard
                when (standard) {
                    ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
                    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
                    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
                    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
                    ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
                    else -> "WiFi 代码 $standard"
                }
            } catch (e: Exception) {
                ""
            }
        }
        return ""
    }

    /**
     * 清空 WiFi 信息显示
     */
    private fun clearWifiInfo() {
        binding.tvSsid.text = getString(R.string.no_wifi_connection)
        binding.tvBssidValue.text = getString(R.string.no_wifi_connection)
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
     * 设置 RSSI 图表
     */
    private fun setupRssiChart() {
        // 初始隐藏空数据提示
        binding.tvRssiEmpty.visibility = android.view.View.GONE
    }

    /**
     * 添加 RSSI 数据点到图表
     */
    private fun addRssiDataPoint(rssi: Int) {
        binding.rssiChart.addDataPoint(rssi, bssidChangedForChart)
        binding.tvRssiEmpty.visibility = android.view.View.GONE
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
        val tvGithub = dialogView.findViewById<TextView>(R.id.tvGithub)

        tvVersion.text = getVersionInfo()
        tvDescription.text = getDescriptionText()
        tvChanges.text = getChangesText()
        tvAuthor.text = getAuthorText()
        tvGithub.text = "GitHub: https://github.com/bg6cq/wifibss"

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 获取版本信息
     */
    private fun getVersionInfo(): String {
        return "版本：1.28"
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
v1.28 显示文字优化
- 优化关于页面描述文本，表达更清晰流畅
- 设置项文字调整："BSSID 变化时自动查询"
- 作者信息格式简化

v1.27 构建系统升级
- AGP 7.4.2 → 8.5.2
- Kotlin 1.8.22 → 2.0.0
- Gradle 8.0 → 8.7
- Java 11 → 17

v1.26 BSSMAC 编辑优化
- 修改 MAC 地址时保留原记录并添加新记录

v1.24 历史记录修复
- 修复本地 BSSMAC 数据未更新历史记录的问题

v1.23 功能增强
- BSSMAC 支持按 MAC/所在楼/AP 名字排序
- BSSMAC 信息支持导出到文件
- 权限不足时显示详细说明，解释为什么需要各项权限

v1.21 BSSMAC 编辑修复
- 修复 BSSMAC 信息编辑后列表不刷新的问题

v1.19 权限和体验优化
- 添加屏幕唤醒保持，APP 运行时屏幕不会休眠
- 自动查询关闭时也会检查本地数据库

v1.18 WiFi 技术标准显示
- 链路速度后显示 WiFi 技术标准（WiFi 4/WiFi 5/WiFi 6/WiFi 6E/WiFi 7）

v1.17 RSSI 图表优化
- 添加每分钟一条的竖向虚线网格，方便查看时间

v1.16 历史记录增强
- 点击历史记录可保存到本地 BSSMAC 数据库

v1.15 本地 BSS MAC 数据库
- 新增本地 BSSMAC 信息编辑功能（设置 → BSSMAC 信息）
- 支持批量添加：每行格式"BSSMAC AP 名字 所在楼"
- 支持多种 BSSMAC 格式自动识别（xx:xx:xx:xx:xx:xx、xxxx-xxxx-xxxx 等）
- 左滑删除记录，点击记录可编辑
- 查询时优先使用本地数据，无数据时调用远程 API

v1.13 历史记录优化
- BSSID 变化时立即记录历史（无需等待查询结果）
- 查询到 AP 信息后自动更新对应历史记录
- 历史记录时间精确到秒

v1.12 信号强度图表
- 新增 RSSI 信号强度曲线图，显示最近 5 分钟变化
- BSSID 切换时用红色大圆点标记

v1.11 历史记录功能
- 新增查询历史记录，保存 BSSID、AP 名字、楼名和查询时间
- BSSID 变化时自动记录，相同 BSSID 智能合并

v1.10 图标修复
- 修复应用图标显示问题（标准 WiFi 信号图案：3 条弧线 + 中心圆点）

v1.9 应用图标更新
- 更换为 WiFi 信号主题图标（青绿色背景 + 白色信号弧线）

v1.8 包名更新
- 包名从 com.example.wifibssquery 更名为 com.ustc.wifibss

v1.7 修正频段显示
- 修正 5GHz/6GHz 频段判断逻辑（5925MHz 以上为 6GHz）
- 无效频率时不显示频段标识

v1.6 自动刷新 WiFi 信息
- 设置中增加自动刷新时间选项（不刷新/1s/5s/10s）
- 按设定间隔自动刷新 WiFi 信息（RSSI、频率/信道等）
- 刷新后 BSSID 变化时触发自动查询（如果已开启）
- 自动查询失败时 1 秒后重试，最多 3 次

v1.2 新增 BSS MAC 显示
- 在查询结果最上方显示返回的 BSS MAC 地址

v1.1 菜单功能和 WiFi 信息增强
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
        return "作者：james@ustc.edu.cn 2026"
    }

    /**
     * 显示历史记录对话框
     */
    private fun showHistoryDialog() {
        val history = getHistoryList().toMutableList()

        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val rvHistory = dialogView.findViewById<RecyclerView>(R.id.rvHistory)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvHistoryEmpty)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearHistory)

        // 设置适配器
        fun updateAdapter() {
            if (history.isEmpty()) {
                rvHistory.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                btnClear.isEnabled = false
            } else {
                rvHistory.visibility = android.view.View.VISIBLE
                tvEmpty.visibility = android.view.View.GONE
                rvHistory.layoutManager = LinearLayoutManager(this)
                rvHistory.adapter = HistoryAdapter(history) { entry ->
                    // 单击编辑：显示编辑对话框
                    showEditHistoryDialog(entry)
                }
            }
        }
        updateAdapter()

        // 清除按钮点击事件
        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.history_clear)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    clearHistory()
                    Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    // 刷新列表
                    val newHistory = getHistoryList()
                    history.clear()
                    history.addAll(newHistory)
                    updateAdapter()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 显示编辑历史记录对话框
     */
    private fun showEditHistoryDialog(history: QueryHistory) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_history, null)
        val etBssid = dialogView.findViewById<EditText>(R.id.etBssid)
        val etApName = dialogView.findViewById<EditText>(R.id.etApName)
        val etBuilding = dialogView.findViewById<EditText>(R.id.etBuilding)

        etBssid.setText(history.bssid)
        etApName.setText(history.apName)
        etBuilding.setText(history.building)

        AlertDialog.Builder(this)
            .setTitle("编辑记录")
            .setView(dialogView)
            .setPositiveButton(R.string.bssmac_save) { _, _ ->
                val newBssid = etBssid.text.toString()
                val newApName = etApName.text.toString()
                val newBuilding = etBuilding.text.toString()

                if (newBssid.isNotBlank() && newApName.isNotBlank()) {
                    // 保存到本地 BSS MAC 数据库
                    val success = addBssLocal(newBssid, newApName, newBuilding)
                    if (success) {
                        Toast.makeText(this, "已保存到本地 BSS MAC 数据库", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "保存失败：BSS MAC 格式错误", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.bssmac_cancel, null)
            .show()
    }


    /**
     * 显示本地 BSSMAC 对话框
     */
    private fun showBssLocalDialog() {
        val bssList = getBssLocalList().toMutableList()

        val dialogView = layoutInflater.inflate(R.layout.dialog_bss_local, null)
        val rvBssLocal = dialogView.findViewById<RecyclerView>(R.id.rvBssLocal)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvBssLocalEmpty)
        val etBulkAdd = dialogView.findViewById<EditText>(R.id.etBulkAdd)
        val btnBulkAdd = dialogView.findViewById<Button>(R.id.btnBulkAdd)
        val rgBssSort = dialogView.findViewById<RadioGroup>(R.id.rgBssSort)

        var currentSort = "mac"

        // 排序
        fun sortList(by: String) {
            currentSort = by
            bssList.sortWith(Comparator { a, b ->
                when (by) {
                    "building" -> {
                        val cmp = a.building.compareTo(b.building, ignoreCase = true)
                        if (cmp != 0) cmp else a.apName.compareTo(b.apName, ignoreCase = true)
                    }
                    "apname" -> {
                        val cmp = a.apName.compareTo(b.apName, ignoreCase = true)
                        if (cmp != 0) cmp else a.building.compareTo(b.building, ignoreCase = true)
                    }
                    else -> a.bssMac.compareTo(b.bssMac)
                }
            })
        }

        // 重新加载并排序
        fun reloadList() {
            val newList = getBssLocalList().toMutableList()
            bssList.clear()
            bssList.addAll(newList)
            sortList(currentSort)
        }

        // 设置适配器
        fun updateAdapter() {
            if (bssList.isEmpty()) {
                rvBssLocal.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
            } else {
                rvBssLocal.visibility = android.view.View.VISIBLE
                tvEmpty.visibility = android.view.View.GONE
                rvBssLocal.layoutManager = LinearLayoutManager(this)
                rvBssLocal.adapter = BssLocalAdapter(bssList) { entry ->
                    showEditBssDialog(entry) {
                        reloadList()
                        updateAdapter()
                    }
                }
            }
        }
        sortList("mac")
        updateAdapter()

        // 排序切换
        rgBssSort.setOnCheckedChangeListener { _, checkedId ->
            val sortBy = when (checkedId) {
                R.id.rbSortBuilding -> "building"
                R.id.rbSortApName -> "apname"
                else -> "mac"
            }
            sortList(sortBy)
            rvBssLocal.adapter?.notifyDataSetChanged()
        }

        // 导出按钮
        val btnExport = dialogView.findViewById<Button>(R.id.btnExport)
        btnExport.setOnClickListener {
            val list = getBssLocalList()
            if (list.isEmpty()) {
                Toast.makeText(this, R.string.bssmac_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportFileLauncher.launch("bssmac_export.txt")
        }

        // 批量添加
        btnBulkAdd.setOnClickListener {
            val text = etBulkAdd.text.toString()
            if (text.isNotBlank()) {
                var added = 0
                text.lines().forEach { line ->
                    val parts = line.trim().split("\\s+".toRegex(), 3)
                    if (parts.size >= 2) {
                        val building = if (parts.size >= 3) parts[2] else ""
                        if (addBssLocal(parts[0], parts[1], building)) {
                            added++
                        }
                    }
                }
                if (added > 0) {
                    Toast.makeText(this, "已添加 $added 条记录", Toast.LENGTH_SHORT).show()
                    reloadList()
                    updateAdapter()
                    etBulkAdd.text.clear()
                } else {
                    Toast.makeText(this, "格式错误，请检查输入", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 添加滑动删除支持（带确认）
        val touchCallback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.adapterPosition
                if (position >= 0 && position < bssList.size) {
                    val entry = bssList[position]
                    // 显示确认对话框
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.bssmac_delete)
                        .setMessage("确定要删除 ${entry.bssMac} 吗？")
                        .setPositiveButton(R.string.bssmac_delete) { _, _ ->
                            deleteBssLocal(entry.bssMac)
                            bssList.removeAt(position)
                            rvBssLocal.adapter?.notifyItemRemoved(position)
                            Toast.makeText(this@MainActivity, "已删除 ${entry.bssMac}", Toast.LENGTH_SHORT).show()
                            if (bssList.isEmpty()) {
                                updateAdapter()
                            }
                        }
                        .setNegativeButton(R.string.bssmac_cancel) { _, _ ->
                            // 取消删除，恢复 item
                            rvBssLocal.adapter?.notifyItemChanged(position)
                        }
                        .setOnCancelListener {
                            // 对话框取消，恢复 item
                            rvBssLocal.adapter?.notifyItemChanged(position)
                        }
                        .show()
                }
            }
        }
        ItemTouchHelper(touchCallback).attachToRecyclerView(rvBssLocal)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 显示编辑 BSSMAC 对话框
     */
    private fun showEditBssDialog(entry: BssLocalEntry, onSaved: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_bss, null)
        val etBssMac = dialogView.findViewById<EditText>(R.id.etBssMac)
        val etApName = dialogView.findViewById<EditText>(R.id.etApName)
        val etBuilding = dialogView.findViewById<EditText>(R.id.etBuilding)

        etBssMac.setText(entry.bssMac)
        etApName.setText(entry.apName)
        etBuilding.setText(entry.building)

        AlertDialog.Builder(this)
            .setTitle(R.string.bssmac_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.bssmac_save) { _, _ ->
                val newMac = etBssMac.text.toString()
                val newApName = etApName.text.toString()
                val newBuilding = etBuilding.text.toString()

                if (newMac.isNotBlank() && newApName.isNotBlank()) {
                    if (addBssLocal(newMac, newApName, newBuilding)) {
                        val msg = if (normalizeBssMac(newMac) != normalizeBssMac(entry.bssMac)) {
                            "已添加新记录"
                        } else {
                            getString(R.string.bssmac_save)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        onSaved()
                    } else {
                        Toast.makeText(this, "BSS MAC 格式错误", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.bssmac_cancel, null)
            .show()
    }

    /**
     * 获取历史记录列表
     */
    private fun getHistoryList(): List<QueryHistory> {
        val json = prefs.getString(KEY_HISTORY, "") ?: return emptyList()
        if (json.isEmpty()) return emptyList()

        return try {
            val array = JSONArray(json)
            val list = mutableListOf<QueryHistory>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    QueryHistory(
                        timestamp = obj.getLong("timestamp"),
                        bssid = obj.getString("bssid"),
                        apName = obj.getString("apName"),
                        building = obj.getString("building")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存历史记录列表
     */
    private fun saveHistoryList(list: List<QueryHistory>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("timestamp", item.timestamp)
            obj.put("bssid", item.bssid)
            obj.put("apName", item.apName)
            obj.put("building", item.building)
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    /**
     * 清除历史记录
     */
    private fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * 添加历史记录
     */
    private fun addHistoryRecord(bssid: String, apName: String, building: String) {
        val list = getHistoryList().toMutableList()

        // 检查是否与上一条记录 BSSID 相同
        val lastRecord = list.lastOrNull()
        if (lastRecord != null && lastRecord.bssid == bssid) {
            // 如果上一条没有 AP 名字，而这次有，则替换
            if (lastRecord.apName.isEmpty() && apName.isNotEmpty()) {
                list.removeAt(list.size - 1)
                list.add(
                    QueryHistory(
                        timestamp = System.currentTimeMillis(),
                        bssid = bssid,
                        apName = apName,
                        building = building
                    )
                )
                saveHistoryList(list)
                return
            }
            // 如果上一条已经有 AP 名字，这次也有，则不添加（避免重复）
            if (lastRecord.apName.isNotEmpty() && apName.isNotEmpty()) {
                return
            }
            // 如果两条都没有 AP 名字，不添加
            if (lastRecord.apName.isEmpty() && apName.isEmpty()) {
                return
            }
        }

        // 添加新记录
        list.add(
            QueryHistory(
                timestamp = System.currentTimeMillis(),
                bssid = bssid,
                apName = apName,
                building = building
            )
        )

        // 限制最多 50 条
        if (list.size > MAX_HISTORY_COUNT) {
            list.removeAt(0)
        }

        saveHistoryList(list)
    }

    /**
     * 更新历史记录（用 AP 信息更新最近一条同 BSSID 的记录）
     */
    private fun updateHistoryRecord(bssid: String, apName: String, building: String) {
        val list = getHistoryList().toMutableList()

        // 从后往前找第一条同 BSSID 且没有 AP 信息的记录
        for (i in list.size - 1 downTo 0) {
            if (list[i].bssid == bssid && list[i].apName.isEmpty()) {
                list[i] = QueryHistory(
                    timestamp = list[i].timestamp,
                    bssid = bssid,
                    apName = apName,
                    building = building
                )
                saveHistoryList(list)
                return
            }
        }

        // 如果没有找到，添加新记录
        addHistoryRecord(bssid, apName, building)
    }

    /**
     * 标准化 BSS MAC 格式（支持多种输入格式）
     */
    private fun normalizeBssMac(input: String): String? {
        // 移除所有非十六进制字符
        val hexOnly = input.replace(Regex("[^0-9a-fA-F]"), "")
        // 验证是否为 12 位十六进制
        return if (hexOnly.length == 12) {
            hexOnly.lowercase()
        } else {
            null
        }
    }

    /**
     * 获取本地 BSS MAC 列表
     */
    private fun getBssLocalList(): List<BssLocalEntry> {
        val json = prefs.getString(KEY_BSS_LOCAL, "") ?: return emptyList()
        if (json.isEmpty()) return emptyList()

        return try {
            val array = JSONArray(json)
            val list = mutableListOf<BssLocalEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BssLocalEntry(
                        bssMac = obj.getString("bssMac"),
                        apName = obj.getString("apName"),
                        building = obj.getString("building")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存本地 BSS MAC 列表
     */
    private fun saveBssLocalList(list: List<BssLocalEntry>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("bssMac", item.bssMac)
            obj.put("apName", item.apName)
            obj.put("building", item.building)
            array.put(obj)
        }
        prefs.edit().putString(KEY_BSS_LOCAL, array.toString()).apply()
    }

    /**
     * 添加本地 BSS MAC 记录
     */
    private fun addBssLocal(bssMac: String, apName: String, building: String): Boolean {
        val normalizedMac = normalizeBssMac(bssMac) ?: return false
        val list = getBssLocalList().toMutableList()

        // 检查是否已存在
        val existingIndex = list.indexOfFirst { it.bssMac == normalizedMac }
        if (existingIndex >= 0) {
            // 更新已有记录
            list[existingIndex] = BssLocalEntry(normalizedMac, apName, building)
        } else {
            // 添加新记录
            list.add(BssLocalEntry(normalizedMac, apName, building))
        }

        saveBssLocalList(list)
        return true
    }

    /**
     * 删除本地 BSS MAC 记录
     */
    private fun deleteBssLocal(bssMac: String) {
        val normalizedMac = normalizeBssMac(bssMac) ?: return
        val list = getBssLocalList().toMutableList()
        list.removeAll { it.bssMac == normalizedMac }
        saveBssLocalList(list)
    }

    /**
     * 导出 BSS MAC 列表到文件
     */
    private fun exportBssLocalToFile(uri: android.net.Uri) {
        try {
            val list = getBssLocalList()
            val content = list.joinToString("\n") { entry ->
                val parts = mutableListOf(entry.bssMac, entry.apName)
                if (entry.building.isNotEmpty()) parts.add(entry.building)
                parts.joinToString(" ")
            }
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            Toast.makeText(this, "已导出 ${list.size} 条记录", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 历史记录适配器
     */
    private class HistoryAdapter(
        private val historyList: List<QueryHistory>,
        private val onItemClick: (QueryHistory) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tvHistoryTime)
            val tvBssid: TextView = view.findViewById(R.id.tvHistoryBssid)
            val tvApName: TextView = view.findViewById(R.id.tvHistoryApName)
            val tvBuilding: TextView = view.findViewById(R.id.tvHistoryBuilding)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = historyList[position]
            holder.tvTime.text = dateFormat.format(Date(item.timestamp))
            holder.tvBssid.text = item.bssid
            holder.tvApName.text = item.apName.ifEmpty { "-" }
            holder.tvBuilding.text = item.building.ifEmpty { "" }

            // 点击保存本地
            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount() = historyList.size
    }

    /**
     * 本地 BSSMAC 适配器（支持滑动删除/编辑）
     */
    private class BssLocalAdapter(
        private val bssList: MutableList<BssLocalEntry>,
        private val onEditClick: (BssLocalEntry) -> Unit
    ) : RecyclerView.Adapter<BssLocalAdapter.ViewHolder>() {

        class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val tvBssMac: TextView = view.findViewById(R.id.tvBssMac)
            val tvApName: TextView = view.findViewById(R.id.tvApName)
            val tvBuilding: TextView = view.findViewById(R.id.tvBuilding)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bss_local, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = bssList[position]
            holder.tvBssMac.text = item.bssMac
            holder.tvApName.text = item.apName.ifEmpty { "-" }
            holder.tvBuilding.text = item.building.ifEmpty { "-" }

            // 点击编辑
            holder.itemView.setOnClickListener {
                onEditClick(item)
            }
        }

        override fun getItemCount() = bssList.size
    }

    /**
     * 显示设置对话框
     */
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etQueryUrl = dialogView.findViewById<EditText>(R.id.etQueryUrl)
        val etQueryKey = dialogView.findViewById<EditText>(R.id.etQueryKey)
        val rgAutoRefresh = dialogView.findViewById<RadioGroup>(R.id.rgAutoRefresh)
        val cbAutoQuery = dialogView.findViewById<CheckBox>(R.id.cbAutoQuery)

        // 加载当前设置
        etQueryUrl.setText(getQueryUrl())
        etQueryKey.setText(getQueryKey())
        cbAutoQuery.isChecked = getAutoQuery()

        // 设置自动刷新选中状态
        val autoRefresh = getAutoRefreshInterval()
        when (autoRefresh) {
            0 -> rgAutoRefresh.check(R.id.rbNever)
            1000 -> rgAutoRefresh.check(R.id.rb1s)
            5000 -> rgAutoRefresh.check(R.id.rb5s)
            10000 -> rgAutoRefresh.check(R.id.rb10s)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                // 保存设置
                saveSettings(etQueryUrl.text.toString(), etQueryKey.text.toString())
                saveAutoQuery(cbAutoQuery.isChecked)
                // 保存自动刷新设置
                val selectedId = rgAutoRefresh.checkedRadioButtonId
                val newInterval = when (selectedId) {
                    R.id.rb1s -> 1000
                    R.id.rb5s -> 5000
                    R.id.rb10s -> 10000
                    else -> 0
                }
                saveAutoRefreshInterval(newInterval)
                // 如果间隔变化，重启自动刷新
                if (newInterval != autoRefresh) {
                    autoRefreshIntervalMs = newInterval
                    restartAutoRefresh()
                }
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
     * 获取自动查询设置
     */
    private fun getAutoQuery(): Boolean {
        return prefs.getBoolean(KEY_AUTO_QUERY, false)
    }

    /**
     * 保存自动查询设置
     */
    private fun saveAutoQuery(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_QUERY, enabled)
            .apply()
    }

    /**
     * 获取自动刷新间隔（毫秒）
     */
    private fun getAutoRefreshInterval(): Int {
        return prefs.getInt(KEY_AUTO_REFRESH, 0)
    }

    /**
     * 保存自动刷新间隔
     */
    private fun saveAutoRefreshInterval(intervalMs: Int) {
        prefs.edit()
            .putInt(KEY_AUTO_REFRESH, intervalMs)
            .apply()
    }

    /**
     * 重启自动刷新
     */
    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        if (autoRefreshIntervalMs > 0) {
            autoRefreshJob = lifecycleScope.launch {
                while (true) {
                    delay(autoRefreshIntervalMs.toLong())
                    updateWifiInfo()
                }
            }
        }
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
            R.id.menu_history -> {
                showHistoryDialog()
                true
            }
            R.id.menu_bssmac -> {
                showBssLocalDialog()
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
     * 查询 BSS 信息（带重试，用于自动查询）
     */
    private fun queryBssInfoWithRetry(bssid: String) {
        // 先检查本地数据
        val localData = getBssLocalList().find { it.bssMac == bssid }
        if (localData != null) {
            displayLocalBssInfo(localData)
            return
        }

        binding.tvResult.text = getString(R.string.querying)
        // 清空之前的 AP 信息显示
        binding.tvBssMac.text = "-"
        binding.tvAcIp.text = "-"
        binding.tvApIp.text = "-"
        binding.tvApName.text = "-"
        binding.tvApSn.text = "-"
        binding.tvApBuilding.text = "-"

        lifecycleScope.launch {
            var success = false
            while (!success && autoQueryRetryCount < 3) {
                try {
                    val result = fetchBssInfo(bssid)
                    withContext(Dispatchers.Main) {
                        binding.tvResult.text = result.ifEmpty { "无相关信息" }
                        parseAndDisplayApInfo(result)
                    }
                    success = true
                } catch (e: Exception) {
                    autoQueryRetryCount++
                    if (autoQueryRetryCount < 3) {
                        withContext(Dispatchers.Main) {
                            binding.tvResult.text = "查询失败，${1}s 后重试 (${autoQueryRetryCount}/3)..."
                        }
                        delay(1000)
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.tvResult.text = "${getString(R.string.query_error)}: 已达最大重试次数"
                            Toast.makeText(this@MainActivity, getString(R.string.query_error), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * 查询 BSS 信息（手动查询，无重试）
     */
    private fun queryBssInfo(bssid: String) {
        // 先检查本地数据
        val localData = getBssLocalList().find { it.bssMac == bssid }
        if (localData != null) {
            displayLocalBssInfo(localData)
            return
        }

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
     * 显示本地 BSS 信息
     */
    private fun displayLocalBssInfo(localData: BssLocalEntry) {
        binding.tvBssMac.text = localData.bssMac
        binding.tvAcIp.text = "-"
        binding.tvApIp.text = "-"
        binding.tvApName.text = localData.apName
        binding.tvApSn.text = "-"
        binding.tvApBuilding.text = localData.building
        binding.tvResult.text = "本地数据"

        // 更新历史记录
        val currentBssid = getFormattedBssid()
        if (currentBssid != null && currentBssid.length == 12) {
            updateHistoryRecord(
                bssid = currentBssid,
                apName = localData.apName,
                building = localData.building
            )
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
                val bssMac = item.optString("BSS_MAC", "-")
                val apName = item.optString("AP_NAME", "-")
                val apBuilding = item.optString("AP_Building", "-")

                binding.tvBssMac.text = bssMac
                binding.tvAcIp.text = item.optString("AC_IP", "-")
                binding.tvApIp.text = item.optString("AP_IP", "-")
                binding.tvApName.text = apName
                binding.tvApSn.text = item.optString("AP_SN", "-")
                binding.tvApBuilding.text = apBuilding

                // 更新历史记录（用 AP 信息填充最近一条同 BSSID 的记录）
                val currentBssid = getFormattedBssid()
                if (currentBssid != null && currentBssid.length == 12 && (apName != "-" || apBuilding != "-")) {
                    updateHistoryRecord(
                        bssid = currentBssid,
                        apName = apName,
                        building = apBuilding
                    )
                }
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
     * Android 10-12: 需要位置权限
     * Android 13+: 需要位置权限 + 近场设备权限
     */
    private fun checkPermissions(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            locationGranted && nearbyGranted
        } else {
            locationGranted
        }
    }

    /**
     * 显示权限说明，然后请求权限
     */
    private fun showPermissionRationale() {
        val missingPerms = mutableListOf<String>()

        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            missingPerms.add("• 位置权限：Android 10+ 要求位置权限才能获取 WiFi BSSID 信息")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!nearbyGranted) {
                missingPerms.add("• 附近设备权限：Android 13+ 需要此权限以扫描附近 WiFi 设备")
            }
        }

        if (missingPerms.isEmpty()) {
            requestPermissions()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("本应用需要以下权限以读取 WiFi 信息：\n\n${missingPerms.joinToString("\n")}\n\n点击「确定」后请在系统弹窗中允许。")
            .setPositiveButton("确定") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    updateWifiInfo()
                } else {
                    Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
