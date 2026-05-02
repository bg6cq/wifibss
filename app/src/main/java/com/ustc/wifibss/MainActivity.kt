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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ustc.wifibss.api.BssInfoApiService
import com.ustc.wifibss.api.BssQueryResult
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
    private var currentChannelWidth: Int = -1

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

        // 加载自动刷新设置、迁移、检查更新（在同一协程中顺序执行）
        lifecycleScope.launch {
            autoRefreshIntervalMs = prefs.getAutoRefreshInterval()
            restartAutoRefresh()

            // 执行数据迁移
            val sp = getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            DataMigration.migrateIfNeeded(database, sp, prefs.store)

            // 检查更新（在 DataStore 初始化之后）
            if (prefs.isAutoCheckUpdateEnabled()) {
                checkUpdate()
            }
        }

        setupUI()
        setupRssiChart()
    }

    override fun onResume() {
        super.onResume()
        if (!checkPermissions()) {
            showPermissionRationale()
        }
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
        val updateLog = versionInfo.updateLog.ifEmpty { getString(R.string.update_dialog_default_log) }
        val message = getString(R.string.update_dialog_message, currentVersionStr, newVersionStr, updateLog)

        MaterialAlertDialogBuilder(this)
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
                prefs.incrementApSwitch()
                if (prefs.isAutoQueryEnabled() && bssid.length == 12) {
                    autoQueryRetryCount = 0
                    queryBssInfoWithRetry(bssid)
                } else {
                    val localData = repository.getBssLocalByMac(bssid)
                    if (localData != null) {
                        displayLocalBssInfo(localData)
                    }
                }
            }
        }

        // IP 地址
        binding.tvIp.text = WifiUtils.formatIpAddress(wifiInfo.ipAddress)

        // 频宽 + 信号强度 RSSI
        val bandwidth = if (currentChannelWidth >= 0) WifiUtils.channelWidthToString(currentChannelWidth) else "?"
        val rssi = wifiInfo.rssi
        val signalLevel = WifiUtils.getSignalLevel(rssi)
        val bandwidthText = if (bandwidth.isNotEmpty()) "$bandwidth  " else ""
        binding.tvRssi.text = "${bandwidthText}$rssi dBm (${getString(signalLevel.resId)})"

        // 添加 RSSI 数据点到图表
        addRssiDataPoint(rssi)

        // 扫描周围同 SSID 的其它 AP
        scanAndUpdateNearbyAps()

        // 频率/信道
        val freq = wifiInfo.frequency
        val channel = WifiUtils.frequencyToChannel(freq)
        val band = WifiUtils.getBand(freq)
        val bandText = if (band.isNotEmpty()) " ($band)" else ""
        binding.tvFrequency.text = "$freq MHz${getString(R.string.channel_label, channel)}$bandText"

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
                    else -> getString(R.string.wifi_standard_unknown, standard)
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
            apName = getString(R.string.current_ap_label),
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

                // 从扫描结果中获取当前 AP 的频宽
                val currentAp = scanResults.firstOrNull {
                    it.BSSID != null && WifiUtils.formatBssid(it.BSSID) == currentBssid
                }
                currentChannelWidth = currentAp?.channelWidth ?: -1

                val sameSsidAps = scanResults
                    .filter { it.SSID.removeSurroundingQuotes() == currentSsid }
                    .filter { it.BSSID != currentBssid }
                    .sortedByDescending { it.level }
                    .take(2)

                val nearbyIds = listOf("nearby_1", "nearby_2")
                for ((index, ap) in sameSsidAps.withIndex()) {
                    val apBssid = ap.BSSID.replace(Regex("[^0-9a-fA-F]"), "").lowercase()
                    val result = trackedQuery(apBssid)
                    val apName = result?.apInfo?.apName?.takeIf { it != "-" } ?: apBssid
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
        val btnIntroduction = dialogView.findViewById<Button>(R.id.btnIntroduction)

        tvVersion.text = getVersionInfo()
        tvDescription.text = getDescriptionText()
        tvChanges.text = getChangesText()
        tvPrivacy.text = getString(R.string.privacy_description)
        tvAuthor.text = getAuthorText()
        tvGithub.text = "GitHub: https://github.com/bg6cq/wifibss"

        btnIntroduction.setOnClickListener {
            val webView = android.webkit.WebView(this)
            val htmlFile = if (Locale.getDefault().language.startsWith("en")) "introduction_en.html" else "introduction.html"
            webView.loadUrl("file:///android_asset/$htmlFile")
            webView.settings.apply {
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.introduction_button)
                .setView(webView)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun getVersionInfo(): String = getString(R.string.version_info, "1.35")

    private fun getDescriptionText(): String = getString(R.string.about_description)

    private fun getChangesText(): String = getString(R.string.about_changes)

    private fun getAuthorText(): String = getString(R.string.author_text, "james@ustc.edu.cn 2026")

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
                MaterialAlertDialogBuilder(this@MainActivity)
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

            MaterialAlertDialogBuilder(this@MainActivity)
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

        MaterialAlertDialogBuilder(this)
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
                        MaterialAlertDialogBuilder(this@MainActivity)
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

            MaterialAlertDialogBuilder(this@MainActivity)
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

        MaterialAlertDialogBuilder(this)
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
            val cbAutoCheckUpdate = dialogView.findViewById<CheckBox>(R.id.cbAutoCheckUpdate)
            val btnCheckUpdate = dialogView.findViewById<Button>(R.id.btnCheckUpdate)

            etQueryUrl.setText(prefs.getQueryUrl())
            etQueryKey.setText(prefs.getQueryKey())
            cbAutoQuery.isChecked = prefs.isAutoQueryEnabled()
            cbCacheApInfo.isChecked = prefs.isCacheApInfoEnabled()
            cbAutoCheckUpdate.isChecked = prefs.isAutoCheckUpdateEnabled()

            val autoRefresh = prefs.getAutoRefreshInterval()
            when (autoRefresh) {
                1000 -> rgAutoRefresh.check(R.id.rb1s)
                3000 -> rgAutoRefresh.check(R.id.rb3s)
                5000 -> rgAutoRefresh.check(R.id.rb5s)
            }

            // 检查软件更新按钮
            btnCheckUpdate.setOnClickListener {
                checkUpdate()
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.settings_title)
                .setView(dialogView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newInterval = when (rgAutoRefresh.checkedRadioButtonId) {
                        R.id.rb1s -> 1000
                        R.id.rb3s -> 3000
                        R.id.rb5s -> 5000
                        else -> 1000
                    }
                    lifecycleScope.launch {
                        prefs.saveSettings(etQueryUrl.text.toString(), etQueryKey.text.toString())
                        prefs.saveAutoQuery(cbAutoQuery.isChecked)
                        prefs.saveCacheApInfo(cbCacheApInfo.isChecked)
                        if (!cbCacheApInfo.isChecked) {
                            repository.clearApInfoCache()
                        }
                        prefs.saveAutoRefreshInterval(newInterval)
                        prefs.saveAutoCheckUpdate(cbAutoCheckUpdate.isChecked)
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
            R.id.menu_stats -> { showStatsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 统一查询：本地 → 缓存 → API，包含统计
     */
    private suspend fun trackedQuery(bssid: String): BssQueryResult? {
        val wasCached = repository.isInApiCache(bssid)
        return try {
            val result = repository.queryBssInfo(bssid)
            if (result.fromLocal) prefs.incrementLocalHit()
            else if (wasCached) prefs.incrementCacheHit()
            else prefs.incrementQuerySuccess()
            result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 显示查询结果（统一处理本地和远程）
     */
    private suspend fun displayQueryResult(bssid: String, result: BssQueryResult) {
        val apInfo = result.apInfo
        if (result.fromLocal) {
            binding.tvBssMac.text = apInfo.bssMac
            binding.tvAcIp.text = "-"
            binding.tvApIp.text = "-"
            binding.tvApName.text = apInfo.apName
            binding.tvApSn.text = "-"
            binding.tvApBuilding.text = apInfo.building
            binding.tvResult.text = getString(R.string.query_from_local)
            repository.updateHistoryRecord(bssid, apInfo.apName, apInfo.building)
        } else if (apInfo.bssMac != "-") {
            binding.tvBssMac.text = apInfo.bssMac
            binding.tvAcIp.text = apInfo.acIp
            binding.tvApIp.text = apInfo.apIp
            binding.tvApName.text = apInfo.apName
            binding.tvApSn.text = apInfo.apSn
            binding.tvApBuilding.text = apInfo.building
            binding.tvResult.text = result.rawJson
            repository.updateHistoryRecord(bssid, apInfo.apName, apInfo.building)
        } else {
            binding.tvResult.text = result.rawJson
        }
    }

    /**
     * 查询 BSS 信息（带重试，用于自动查询）
     */
    private fun queryBssInfoWithRetry(bssid: String) {
        lifecycleScope.launch {
            binding.tvResult.text = getString(R.string.querying)
            clearApInfoDisplay()

            var result: BssQueryResult? = null
            while (result == null && autoQueryRetryCount < 3) {
                result = trackedQuery(bssid)
                if (result == null) {
                    autoQueryRetryCount++
                    if (autoQueryRetryCount < 3) {
                        withContext(Dispatchers.Main) {
                            binding.tvResult.text = getString(R.string.query_retry_format, autoQueryRetryCount)
                        }
                        delay(1000)
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.tvResult.text = "${getString(R.string.query_error)}: ${getString(R.string.query_max_retries)}"
                            Toast.makeText(this@MainActivity, getString(R.string.query_error), Toast.LENGTH_SHORT).show()
                        }
                        prefs.incrementQueryFailure()
                    }
                }
            }
            if (result != null) {
                displayQueryResult(bssid, result)
            }
        }
    }

    /**
     * 查询 BSS 信息（手动查询）
     */
    private fun queryBssInfo(bssid: String) {
        lifecycleScope.launch {
            binding.tvResult.text = getString(R.string.querying)
            clearApInfoDisplay()
            binding.btnQuery.isEnabled = false

            val result = trackedQuery(bssid)
            if (result != null) {
                displayQueryResult(bssid, result)
            } else {
                withContext(Dispatchers.Main) {
                    binding.tvResult.text = "${getString(R.string.query_error)}: ${getString(R.string.query_max_retries)}"
                }
            }
            withContext(Dispatchers.Main) {
                binding.btnQuery.isEnabled = true
            }
        }
    }

    private fun showStatsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_stats, null)
        val tvApSwitch = dialogView.findViewById<TextView>(R.id.tvStatsApSwitch)
        val tvCacheHit = dialogView.findViewById<TextView>(R.id.tvStatsCacheHit)
        val tvLocalHit = dialogView.findViewById<TextView>(R.id.tvStatsLocalHit)
        val tvQuerySuccess = dialogView.findViewById<TextView>(R.id.tvStatsQuerySuccess)
        val tvQueryFailure = dialogView.findViewById<TextView>(R.id.tvStatsQueryFailure)

        suspend fun updateStats() {
            tvApSwitch.text = "${getString(R.string.stats_ap_switch)}：${prefs.getStatsApSwitch()}"
            tvCacheHit.text = "${getString(R.string.stats_cache_hit)}：${prefs.getStatsCacheHit()}"
            tvLocalHit.text = "${getString(R.string.stats_local_hit)}：${prefs.getStatsLocalHit()}"
            tvQuerySuccess.text = "${getString(R.string.stats_query_success)}：${prefs.getStatsQuerySuccess()}"
            tvQueryFailure.text = "${getString(R.string.stats_query_failure)}：${prefs.getStatsQueryFailure()}"
        }

        dialogView.findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.stats_reset)
                .setMessage(R.string.stats_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        prefs.resetAllStats()
                        updateStats()
                        Toast.makeText(this@MainActivity, R.string.stats_reset_done, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val dialog = MaterialAlertDialogBuilder(this@MainActivity)
            .setTitle(R.string.stats_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        // 每 1 秒自动刷新
        var refreshJob: kotlinx.coroutines.Job? = null
        refreshJob = lifecycleScope.launch {
            while (true) {
                updateStats()
                delay(1000)
            }
        }
        dialog.setOnDismissListener { refreshJob?.cancel() }
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
        binding.tvResult.text = getString(R.string.query_from_local)

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
            missingPerms.add(getString(R.string.permission_location))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!nearbyGranted) {
                missingPerms.add(getString(R.string.permission_nearby))
            }
        }

        if (missingPerms.isEmpty()) {
            requestPermissions()
            return
        }

        MaterialAlertDialogBuilder(this)
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
