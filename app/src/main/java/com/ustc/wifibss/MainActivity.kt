package com.ustc.wifibss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ustc.wifibss.api.BssInfoApiService
import com.ustc.wifibss.database.DataMigration
import com.ustc.wifibss.database.WifiBssDatabase
import com.ustc.wifibss.data.AppPreferences
import com.ustc.wifibss.datastore.SettingsDataStore
import com.ustc.wifibss.databinding.ActivityMainBinding
import com.ustc.wifibss.model.ApInfo
import com.ustc.wifibss.model.BssLocalEntry
import com.ustc.wifibss.model.QueryHistory
import com.ustc.wifibss.repository.BssRepository
import com.ustc.wifibss.util.WifiUtils
import com.ustc.wifibss.util.WifiUtils.SignalLevel
import com.ustc.wifibss.util.WifiUtils.removeSurroundingQuotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var repository: BssRepository
    private lateinit var prefs: AppPreferences
    private lateinit var database: WifiBssDatabase

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val SCAN_INTERVAL_MS = 30000L // WiFi 扫描间隔 30 秒
    }

    private var lastBssid: String? = null
    private var autoQueryRetryCount = 0
    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var autoRefreshIntervalMs: Int = 0
    private var bssidChangedForChart: Boolean = false
    private var lastScanTimeMs: Long = 0

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportBssLocalToFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        database = WifiBssDatabase.getInstance(this)
        prefs = AppPreferences(this)
        repository = BssRepository(prefs, database)

        // 保持屏幕唤醒
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 加载自动刷新设置
        lifecycleScope.launch {
            autoRefreshIntervalMs = prefs.getAutoRefreshInterval()

            // 执行数据迁移
            DataMigration.migrateIfNeeded(database, getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE))
        }

        setupUI()
        setupRssiChart()
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        updateWifiInfo()
        scanAndUpdateNearbyAps()
        restartAutoRefresh()
        startApScanTimer()
    }

    override fun onPause() {
        super.onPause()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        apScanJob?.cancel()
        apScanJob = null
    }

    private var apScanJob: kotlinx.coroutines.Job? = null

    /**
     * 启动 AP 扫描定时器（每 30 秒扫描一次）
     */
    private fun startApScanTimer() {
        apScanJob?.cancel()
        apScanJob = lifecycleScope.launch {
            while (true) {
                delay(SCAN_INTERVAL_MS)
                scanAndUpdateNearbyAps()
            }
        }
    }

    /**
     * 检查更新
     */
    private fun checkUpdate() {
        lifecycleScope.launch {
            try {
                val versionInfo = repository.checkVersion()
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

    private fun showUpdateDialog(versionInfo: BssInfoApiService.VersionInfo, packageInfo: android.content.pm.PackageInfo) {
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

    private fun downloadApk(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(url)
        startActivity(intent)
    }

    /**
     * 更新所有 WiFi 信息显示
     */
    @Suppress("DEPRECATION")
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
        val bssid = WifiUtils.formatBssid(wifiInfo.bssid)
        binding.tvBssidValue.text = bssid ?: getString(R.string.no_wifi_connection)

        // 检测 BSSID 变化
        val bssidChanged = (bssid != null && bssid != lastBssid)
        bssidChangedForChart = bssidChanged

        if (bssidChanged) {
            lastBssid = bssid
            addHistoryRecord(bssid, "", "")

            lifecycleScope.launch {
                val localData = repository.getBssLocalList().find { it.bssMac == bssid }
                if (localData != null) {
                    displayLocalBssInfo(localData)
                } else if (prefs.isAutoQueryEnabled() && bssid.length == 12) {
                    autoQueryRetryCount = 0
                    queryBssInfoWithRetry(bssid)
                }
            }
        }

        // IP 地址
        binding.tvIp.text = WifiUtils.formatIpAddress(wifiInfo.ipAddress)

        // 信号强度 RSSI
        val rssi = wifiInfo.rssi
        val signalLevel = WifiUtils.getSignalLevel(rssi)
        binding.tvRssi.text = "$rssi dBm (${signalLevel.label})"

        // 添加 RSSI 数据点到图表
        addRssiDataPoint(rssi)

        // 扫描周围同 SSID 的其它 AP
        scanAndUpdateNearbyAps()

        // 频率/信道
        val freq = wifiInfo.frequency
        val channel = WifiUtils.frequencyToChannel(freq)
        val band = WifiUtils.getBand(freq)
        val bandText = if (band.isNotEmpty()) " ($band)" else ""
        binding.tvFrequency.text = "$freq MHz (信道 $channel)$bandText"

        // 链路速度和 WiFi 标准
        val wifiStandard = getWifiStandard(wifiInfo)
        val standardText = if (wifiStandard.isNotEmpty()) " ($wifiStandard)" else ""
        binding.tvLinkSpeed.text = "${wifiInfo.linkSpeed} Mbps$standardText"
    }

    /**
     * 获取 WiFi 技术标准 (802.11 a/b/g/n/ac/ax/be)
     */
    private fun getWifiStandard(wifiInfo: android.net.wifi.WifiInfo): String {
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

    private fun clearWifiInfo() {
        binding.tvSsid.text = getString(R.string.no_wifi_connection)
        binding.tvBssidValue.text = getString(R.string.no_wifi_connection)
        binding.tvIp.text = "-"
        binding.tvRssi.text = "-"
        binding.tvFrequency.text = "-"
        binding.tvLinkSpeed.text = "-"
    }

    private fun setupUI() {
        binding.btnQuery.setOnClickListener {
            val bssid = WifiUtils.formatBssid(wifiManager.connectionInfo.bssid)
            if (bssid != null && bssid.length == 12) {
                binding.tvBssidValue.text = bssid
                queryBssInfo(bssid)
            } else {
                binding.tvResult.text = getString(R.string.no_wifi_connection)
            }
        }
    }

    private fun setupRssiChart() {
        binding.tvRssiEmpty.visibility = android.view.View.GONE
    }

    private fun addRssiDataPoint(rssi: Int) {
        binding.rssiChart.addOrUpdateApSeries(
            apId = "current",
            apName = "当前 AP",
            bssid = "current",
            isCurrentAp = true,
            rssi = rssi,
            bssidChanged = bssidChangedForChart
        )
        binding.tvRssiEmpty.visibility = android.view.View.GONE
    }

    /**
     * 扫描周围 WiFi AP，更新同 SSID 的其它 AP 到图表
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanAndUpdateNearbyAps() {
        val now = System.currentTimeMillis()
        if (now - lastScanTimeMs < SCAN_INTERVAL_MS) return

        if (!checkPermissions()) return

        val wifiInfo = wifiManager.connectionInfo ?: return
        val currentBssid = WifiUtils.formatBssid(wifiInfo.bssid) ?: return
        val currentSsid = wifiInfo.ssid.removeSurroundingQuotes()
        if (currentSsid.isEmpty()) return

        lastScanTimeMs = now

        lifecycleScope.launch {
            try {
                if (!checkPermissions()) return@launch
                val scanResults = wifiManager.scanResults

                val sameSsidAps = scanResults
                    .filter { it.SSID.removeSurroundingQuotes() == currentSsid }
                    .filter { it.BSSID != currentBssid }
                    .sortedByDescending { it.level }
                    .take(2)

                val nearbyIds = listOf("nearby_1", "nearby_2")
                for ((index, ap) in sameSsidAps.withIndex()) {
                    val apBssid = ap.BSSID.replace(Regex("[^0-9a-fA-F]"), "").lowercase()
                    val apName = repository.queryNearbyApName(apBssid) ?: apBssid
                    binding.rssiChart.addOrUpdateApSeries(
                        apId = nearbyIds[index],
                        apName = apName,
                        bssid = apBssid,
                        isCurrentAp = false,
                        rssi = ap.level,
                        bssidChanged = false
                    )
                }
            } catch (e: Exception) {
                // 扫描失败，静默处理
            }
        }
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvVersion)
        val tvDescription = dialogView.findViewById<TextView>(R.id.tvDescription)
        val tvChanges = dialogView.findViewById<TextView>(R.id.tvChanges)
        val tvPrivacy = dialogView.findViewById<TextView>(R.id.tvPrivacy)
        val tvAuthor = dialogView.findViewById<TextView>(R.id.tvAuthor)
        val tvGithub = dialogView.findViewById<TextView>(R.id.tvGithub)

        tvVersion.text = getVersionInfo()
        tvDescription.text = getDescriptionText()
        tvChanges.text = getChangesText()
        tvPrivacy.text = getString(R.string.privacy_description)
        tvAuthor.text = getAuthorText()
        tvGithub.text = "GitHub: https://github.com/bg6cq/wifibss"

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun getVersionInfo(): String = "版本：1.30"

    private fun getDescriptionText(): String = getString(R.string.about_description)

    private fun getChangesText(): String {
        return """
v1.30 显示可能漫游切换的 AP 信号
- 自动查询附近 AP 名称并在图表下方显示
- 设置中增加缓存 AP 信息选项，减少查询次数
- 图表布局和显示优化

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

    private fun getAuthorText(): String = "作者：james@ustc.edu.cn 2026"

    /**
     * 显示历史记录对话框
     */
    private fun showHistoryDialog() {
        lifecycleScope.launch {
            val history = repository.getHistoryList().toMutableList()

            val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
            val rvHistory = dialogView.findViewById<RecyclerView>(R.id.rvHistory)
            val tvEmpty = dialogView.findViewById<TextView>(R.id.tvHistoryEmpty)
            val btnClear = dialogView.findViewById<Button>(R.id.btnClearHistory)

            fun updateAdapter() {
                if (history.isEmpty()) {
                    rvHistory.visibility = android.view.View.GONE
                    tvEmpty.visibility = android.view.View.VISIBLE
                    btnClear.isEnabled = false
                } else {
                    rvHistory.visibility = android.view.View.VISIBLE
                    tvEmpty.visibility = android.view.View.GONE
                    rvHistory.layoutManager = LinearLayoutManager(this@MainActivity)
                    rvHistory.adapter = HistoryAdapter(history) { entry ->
                        showEditHistoryDialog(entry)
                    }
                }
            }
            updateAdapter()

            btnClear.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.history_clear)
                    .setMessage(R.string.history_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            repository.clearHistory()
                            Toast.makeText(this@MainActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                            history.clear()
                            history.addAll(repository.getHistoryList())
                            updateAdapter()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            AlertDialog.Builder(this@MainActivity)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showEditHistoryDialog(history: QueryHistory) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_history, null)
        val etBssid = dialogView.findViewById<EditText>(R.id.etBssid)
        val etApName = dialogView.findViewById<EditText>(R.id.etApName)
        val etBuilding = dialogView.findViewById<EditText>(R.id.etBuilding)

        etBssid.setText(history.bssid)
        etApName.setText(history.apName)
        etBuilding.setText(history.building)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_record_title)
            .setView(dialogView)
            .setPositiveButton(R.string.bssmac_save) { _, _ ->
                val newBssid = etBssid.text.toString()
                val newApName = etApName.text.toString()
                val newBuilding = etBuilding.text.toString()

                if (newBssid.isNotBlank() && newApName.isNotBlank()) {
                    lifecycleScope.launch {
                        val success = repository.addBssLocal(newBssid, newApName, newBuilding)
                        if (success) {
                            Toast.makeText(this@MainActivity, R.string.saved_to_local_db, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, R.string.save_failed_invalid_mac, Toast.LENGTH_SHORT).show()
                        }
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
        lifecycleScope.launch {
            val bssList = repository.getBssLocalList().toMutableList()

            val dialogView = layoutInflater.inflate(R.layout.dialog_bss_local, null)
            val rvBssLocal = dialogView.findViewById<RecyclerView>(R.id.rvBssLocal)
            val tvEmpty = dialogView.findViewById<TextView>(R.id.tvBssLocalEmpty)
            val etBulkAdd = dialogView.findViewById<EditText>(R.id.etBulkAdd)
            val btnBulkAdd = dialogView.findViewById<Button>(R.id.btnBulkAdd)
            val rgBssSort = dialogView.findViewById<RadioGroup>(R.id.rgBssSort)

            var currentSort = "mac"

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

            suspend fun reloadList() {
                val newList = repository.getBssLocalList().toMutableList()
                bssList.clear()
                bssList.addAll(newList)
                sortList(currentSort)
            }

            fun updateAdapter() {
                if (bssList.isEmpty()) {
                    rvBssLocal.visibility = android.view.View.GONE
                    tvEmpty.visibility = android.view.View.VISIBLE
                } else {
                    rvBssLocal.visibility = android.view.View.VISIBLE
                    tvEmpty.visibility = android.view.View.GONE
                    rvBssLocal.layoutManager = LinearLayoutManager(this@MainActivity)
                    rvBssLocal.adapter = BssLocalAdapter(bssList) { entry ->
                        showEditBssDialog(entry) {
                            lifecycleScope.launch { reloadList() }
                            updateAdapter()
                        }
                    }
                }
            }
            sortList("mac")
            updateAdapter()

            rgBssSort.setOnCheckedChangeListener { _, checkedId ->
                val sortBy = when (checkedId) {
                    R.id.rbSortBuilding -> "building"
                    R.id.rbSortApName -> "apname"
                    else -> "mac"
                }
                sortList(sortBy)
                rvBssLocal.adapter?.notifyDataSetChanged()
            }

            val btnExport = dialogView.findViewById<Button>(R.id.btnExport)
            btnExport.setOnClickListener {
                lifecycleScope.launch {
                    val list = repository.getBssLocalList()
                    if (list.isEmpty()) {
                        Toast.makeText(this@MainActivity, R.string.bssmac_empty, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    exportFileLauncher.launch("bssmac_export.txt")
                }
            }

            btnBulkAdd.setOnClickListener {
                val text = etBulkAdd.text.toString()
                if (text.isNotBlank()) {
                    lifecycleScope.launch {
                        var added = 0
                        text.lines().forEach { line ->
                            val parts = line.trim().split("\\s+".toRegex(), 3)
                            if (parts.size >= 2) {
                                val building = if (parts.size >= 3) parts[2] else ""
                                if (repository.addBssLocal(parts[0], parts[1], building)) {
                                    added++
                                }
                            }
                        }
                        if (added > 0) {
                            Toast.makeText(this@MainActivity, getString(R.string.bssmac_added_count, added), Toast.LENGTH_SHORT).show()
                            reloadList()
                            updateAdapter()
                            etBulkAdd.text.clear()
                        } else {
                            Toast.makeText(this@MainActivity, R.string.bssmac_format_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val touchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    val position = vh.adapterPosition
                    if (position >= 0 && position < bssList.size) {
                        val entry = bssList[position]
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.bssmac_delete)
                            .setMessage(getString(R.string.bssmac_delete_confirm, entry.bssMac))
                            .setPositiveButton(R.string.bssmac_delete) { _, _ ->
                                lifecycleScope.launch {
                                    repository.deleteBssLocal(entry.bssMac)
                                    bssList.removeAt(position)
                                    rvBssLocal.adapter?.notifyItemRemoved(position)
                                    Toast.makeText(this@MainActivity, getString(R.string.bssmac_deleted, entry.bssMac), Toast.LENGTH_SHORT).show()
                                    if (bssList.isEmpty()) updateAdapter()
                                }
                            }
                            .setNegativeButton(R.string.bssmac_cancel) { _, _ ->
                                rvBssLocal.adapter?.notifyItemChanged(position)
                            }
                            .setOnCancelListener {
                                rvBssLocal.adapter?.notifyItemChanged(position)
                            }
                            .show()
                    }
                }
            }
            ItemTouchHelper(touchCallback).attachToRecyclerView(rvBssLocal)

            AlertDialog.Builder(this@MainActivity)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

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
                    lifecycleScope.launch {
                        if (repository.addBssLocal(newMac, newApName, newBuilding)) {
                            val msg = if (WifiUtils.normalizeBssMac(newMac) != WifiUtils.normalizeBssMac(entry.bssMac)) {
                                getString(R.string.bssmac_added_new)
                            } else {
                                getString(R.string.bssmac_save)
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            onSaved()
                        } else {
                            Toast.makeText(this@MainActivity, R.string.bssmac_format_invalid, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.bssmac_cancel, null)
            .show()
    }

    private fun exportBssLocalToFile(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val content = repository.exportBssLocalToString()
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                val count = repository.getBssLocalList().size
                Toast.makeText(this@MainActivity, getString(R.string.bssmac_export_success, count), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.bssmac_export_failed, e.message), Toast.LENGTH_LONG).show()
            }
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

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = historyList.size
    }

    /**
     * 本地 BSSMAC 适配器
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
            holder.itemView.setOnClickListener { onEditClick(item) }
        }

        override fun getItemCount() = bssList.size
    }

    /**
     * 显示设置对话框
     */
    private fun showSettingsDialog() {
        lifecycleScope.launch {
            val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
            val etQueryUrl = dialogView.findViewById<EditText>(R.id.etQueryUrl)
            val etQueryKey = dialogView.findViewById<EditText>(R.id.etQueryKey)
            val rgAutoRefresh = dialogView.findViewById<RadioGroup>(R.id.rgAutoRefresh)
            val cbAutoQuery = dialogView.findViewById<CheckBox>(R.id.cbAutoQuery)
            val cbCacheApInfo = dialogView.findViewById<CheckBox>(R.id.cbCacheApInfo)

            etQueryUrl.setText(prefs.getQueryUrl())
            etQueryKey.setText(prefs.getQueryKey())
            cbAutoQuery.isChecked = prefs.isAutoQueryEnabled()
            cbCacheApInfo.isChecked = prefs.isCacheApInfoEnabled()

            val autoRefresh = prefs.getAutoRefreshInterval()
            when (autoRefresh) {
                0 -> rgAutoRefresh.check(R.id.rbNever)
                1000 -> rgAutoRefresh.check(R.id.rb1s)
                5000 -> rgAutoRefresh.check(R.id.rb5s)
                10000 -> rgAutoRefresh.check(R.id.rb10s)
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.settings_title)
                .setView(dialogView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newInterval = when (rgAutoRefresh.checkedRadioButtonId) {
                        R.id.rb1s -> 1000
                        R.id.rb5s -> 5000
                        R.id.rb10s -> 10000
                        else -> 0
                    }
                    lifecycleScope.launch {
                        prefs.saveSettings(etQueryUrl.text.toString(), etQueryKey.text.toString())
                        prefs.saveAutoQuery(cbAutoQuery.isChecked)
                        prefs.saveCacheApInfo(cbCacheApInfo.isChecked)
                        if (!cbCacheApInfo.isChecked) {
                            repository.clearApInfoCache()
                        }
                        prefs.saveAutoRefreshInterval(newInterval)
                        if (newInterval != autoRefresh) {
                            autoRefreshIntervalMs = newInterval
                            restartAutoRefresh()
                        }
                        Toast.makeText(this@MainActivity, R.string.save, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
            R.id.menu_settings -> { showSettingsDialog(); true }
            R.id.menu_about -> { showAboutDialog(); true }
            R.id.menu_history -> { showHistoryDialog(); true }
            R.id.menu_bssmac -> { showBssLocalDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 查询 BSS 信息（带重试，用于自动查询）
     */
    private fun queryBssInfoWithRetry(bssid: String) {
        lifecycleScope.launch {
            val localData = repository.getBssLocalList().find { it.bssMac == bssid }
            if (localData != null) {
                displayLocalBssInfo(localData)
                return@launch
            }

            binding.tvResult.text = getString(R.string.querying)
            clearApInfoDisplay()

            var success = false
            while (!success && autoQueryRetryCount < 3) {
                try {
                    val apInfo = repository.queryBssInfo(bssid)
                    withContext(Dispatchers.Main) {
                        if (apInfo.bssMac != "-") {
                            binding.tvBssMac.text = apInfo.bssMac
                            binding.tvAcIp.text = apInfo.acIp
                            binding.tvApIp.text = apInfo.apIp
                            binding.tvApName.text = apInfo.apName
                            binding.tvApSn.text = apInfo.apSn
                            binding.tvApBuilding.text = apInfo.building
                            binding.tvResult.text = "查询成功"
                            repository.updateHistoryRecord(bssid, apInfo.apName, apInfo.building)
                        } else {
                            binding.tvResult.text = "无相关信息"
                        }
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
        lifecycleScope.launch {
            val localData = repository.getBssLocalList().find { it.bssMac == bssid }
            if (localData != null) {
                displayLocalBssInfo(localData)
                return@launch
            }

            binding.tvResult.text = getString(R.string.querying)
            clearApInfoDisplay()
            binding.btnQuery.isEnabled = false

            try {
                val apInfo = repository.queryBssInfo(bssid)
                withContext(Dispatchers.Main) {
                    if (apInfo.bssMac != "-") {
                        binding.tvBssMac.text = apInfo.bssMac
                        binding.tvAcIp.text = apInfo.acIp
                        binding.tvApIp.text = apInfo.apIp
                        binding.tvApName.text = apInfo.apName
                        binding.tvApSn.text = apInfo.apSn
                        binding.tvApBuilding.text = apInfo.building
                        binding.tvResult.text = "查询成功"
                        repository.updateHistoryRecord(bssid, apInfo.apName, apInfo.building)
                    } else {
                        binding.tvResult.text = "无相关信息"
                    }
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

    private fun clearApInfoDisplay() {
        binding.tvBssMac.text = "-"
        binding.tvAcIp.text = "-"
        binding.tvApIp.text = "-"
        binding.tvApName.text = "-"
        binding.tvApSn.text = "-"
        binding.tvApBuilding.text = "-"
    }

    private fun displayLocalBssInfo(localData: BssLocalEntry) {
        binding.tvBssMac.text = localData.bssMac
        binding.tvAcIp.text = "-"
        binding.tvApIp.text = "-"
        binding.tvApName.text = localData.apName
        binding.tvApSn.text = "-"
        binding.tvApBuilding.text = localData.building
        binding.tvResult.text = "本地数据"

        lifecycleScope.launch {
            val currentBssid = WifiUtils.formatBssid(wifiManager.connectionInfo.bssid)
            if (currentBssid != null && currentBssid.length == 12) {
                repository.updateHistoryRecord(currentBssid, localData.apName, localData.building)
            }
        }
    }

    private fun addHistoryRecord(bssid: String, apName: String, building: String) {
        lifecycleScope.launch {
            repository.addHistoryRecord(bssid, apName, building)
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
            .setTitle(R.string.need_permission)
            .setMessage(getString(R.string.permission_message, missingPerms.joinToString("\n")))
            .setPositiveButton(android.R.string.ok) { _, _ ->
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
