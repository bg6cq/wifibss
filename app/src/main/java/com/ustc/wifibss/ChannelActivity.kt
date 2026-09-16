package com.ustc.wifibss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ustc.wifibss.data.AppPreferences
import com.ustc.wifibss.database.WifiBssDatabase
import com.ustc.wifibss.databinding.ActivityChannelBinding
import com.ustc.wifibss.model.ChannelAp
import com.ustc.wifibss.repository.BssRepository
import com.ustc.wifibss.util.WifiUtils
import com.ustc.wifibss.util.WifiUtils.removeSurroundingQuotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 信道利用界面：频谱曲线图 + 热点列表
 *
 * 名称列支持在 MAC 与 AP 名字间切换。AP 名字解析策略为本地库优先、远程按需查询：
 * 先一次性匹配本地 BSSMAC 表，未命中的再并发调用查询 API（受 10 分钟缓存保护）。
 */
class ChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var repository: BssRepository

    companion object {
        private const val LOCATION_PERMISSION_CODE = 2001
        private const val SCAN_INTERVAL_MS = 30000L

        // 远程名字查询的并发上限与总数上限
        private const val MAX_REMOTE_LOOKUPS = 20
        private const val REMOTE_CONCURRENCY = 4
    }

    private var adapter: ChannelAdapter? = null
    private var refreshJob: kotlinx.coroutines.Job? = null

    // 两个频段各自缓存的 AP 列表
    private var aps24g: List<ChannelAp> = emptyList()
    private var aps5g: List<ChannelAp> = emptyList()

    // 已查询到的 AP 名字缓存（跨刷新保留，避免每 30s 重新查一遍）
    private val resolvedNames = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val prefs = AppPreferences(this)
        repository = BssRepository(prefs, WifiBssDatabase.getInstance(this))

        // 使用系统 ActionBar 的返回箭头，避免自定义图标引起歧义
        supportActionBar?.apply {
            title = getString(R.string.channel_util_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.rgBand.setOnCheckedChangeListener { _, _ -> renderCurrentBand() }

        binding.rgNameMode.setOnCheckedChangeListener { _, _ ->
            adapter?.nameMode = currentNameMode()
            adapter?.notifyDataSetChanged()
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        startRefreshTimer()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * ActionBar 返回箭头
     */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 每 30 秒重新读取一次扫描结果（系统扫描本身有限频，无需主动触发）
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (true) {
                delay(SCAN_INTERVAL_MS)
                loadData()
            }
        }
    }

    private fun currentNameMode(): String =
        if (binding.rgNameMode.checkedRadioButtonId == R.id.rbNameMac) NAME_MODE_MAC else NAME_MODE_AP

    private fun currentBand(): Int =
        if (binding.rgBand.checkedRadioButtonId == R.id.rbBand5) BAND_5G else BAND_24G

    // ==================== 数据加载 ====================

    private fun loadData() {
        if (!checkPermissions()) {
            showEmpty(getString(R.string.channel_permission_required))
            requestPermissions()
            return
        }

        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { readScanResults() }

            // 一次性读本地 BSSMAC 库，批量匹配名字（零网络开销）
            val localNames = withContext(Dispatchers.IO) {
                try {
                    repository.getBssLocalList().associate { it.bssMac to it.apName }
                } catch (_: Exception) {
                    emptyMap()
                }
            }

            // 本地库名字优先，其次用之前已查到的远程名字
            val knownNames = localNames + resolvedNames
            aps24g = buildBandList(scanned, BAND_24G, knownNames)
            aps5g = buildBandList(scanned, BAND_5G, knownNames)

            renderCurrentBand()

            // 本地未命中的 AP 异步补查名字，查到后再刷新对应行
            resolveRemoteNames(scanned, knownNames)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readScanResults(): List<ScanResult> {
        return try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildBandList(
        scanned: List<ScanResult>,
        band: Int,
        localNames: Map<String, String>
    ): List<ChannelAp> {
        return scanned.mapNotNull { result ->
            val bssid = WifiUtils.formatBssid(result.BSSID) ?: return@mapNotNull null
            if (bssid.length != 12) return@mapNotNull null

            val freq = result.frequency
            if (!inBand(freq, band)) return@mapNotNull null

            val localName = localNames[bssid]?.takeIf { it.isNotBlank() }
            ChannelAp(
                bssid = bssid,
                ssid = result.SSID.removeSurroundingQuotes(),
                rssi = result.level,
                freqMhz = freq,
                channel = WifiUtils.frequencyToChannel(freq),
                bandwidthMhz = WifiUtils.channelWidthToMhz(result.channelWidth),
                standard = getScanStandard(result),
                apName = localName,
                building = null
            )
        }.sortedByDescending { it.rssi }
    }

    /**
     * 频段过滤：2.4G 为 2400-2484，5G 页含 5GHz 与 6GHz
     */
    private fun inBand(freq: Int, band: Int): Boolean {
        return if (band == BAND_24G) {
            freq in 2400..2484
        } else {
            freq in 5150..5895 || freq in 5925..7125
        }
    }

    private fun getScanStandard(result: ScanResult): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return try {
            WifiUtils.wifiStandardToString(result.wifiStandard)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 对本地库与已查缓存均未命中的 AP 并发查询名字，查到后增量刷新列表
     */
    private fun resolveRemoteNames(scanned: List<ScanResult>, knownNames: Map<String, String>) {
        val pending = scanned.mapNotNull { result ->
            val bssid = WifiUtils.formatBssid(result.BSSID) ?: return@mapNotNull null
            if (bssid.length != 12) return@mapNotNull null
            if (knownNames.containsKey(bssid)) return@mapNotNull null
            bssid to result.level
        }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(MAX_REMOTE_LOOKUPS)
            .map { it.first }

        if (pending.isEmpty()) return

        lifecycleScope.launch {
            val semaphore = Semaphore(REMOTE_CONCURRENCY)
            pending.forEach { bssid ->
                launch {
                    val name = semaphore.withPermit { queryApName(bssid) } ?: return@launch
                    applyResolvedName(bssid, name)
                }
            }
        }
    }

    private suspend fun queryApName(bssid: String): String? {
        return try {
            val result = repository.queryBssInfo(bssid)
            result.apInfo.apName.takeIf { it.isNotBlank() && it != "-" }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun applyResolvedName(bssid: String, name: String) {
        withContext(Dispatchers.Main) {
            resolvedNames[bssid] = name

            fun rename(list: List<ChannelAp>): List<ChannelAp> =
                list.map { if (it.bssid == bssid) it.copy(apName = name) else it }

            // 同一 BSSID 只会出现在其中一个频段，但两个都更新更稳妥
            aps24g = rename(aps24g)
            aps5g = rename(aps5g)

            // 名字变化同时影响图表标签与列表，直接整体重绘当前频段，避免维护索引
            renderCurrentBand()
        }
    }

    // ==================== 渲染 ====================

    private fun renderCurrentBand() {
        val band = currentBand()
        val aps = if (band == BAND_24G) aps24g else aps5g

        renderChart(aps, band)
        renderList(aps)
        renderScanHint(aps.size, band)
    }

    private fun renderChart(aps: List<ChannelAp>, band: Int) {
        val is24 = band == BAND_24G
        val minFreq = if (is24) ChannelChartView.FREQ_MIN_24G else ChannelChartView.FREQ_MIN_5G
        val maxFreq = if (is24) ChannelChartView.FREQ_MAX_24G else ChannelChartView.FREQ_MAX_5G
        val channels = if (is24) ChannelChartView.CHANNELS_24G else ChannelChartView.CHANNELS_5G

        val hiddenLabel = getString(R.string.channel_hidden_ssid)
        val currentBssid = currentConnectedBssid()

        val curves = aps.mapIndexed { index, ap ->
            ChannelChartView.ApCurve(
                bssid = ap.bssid,
                label = ap.ssidLabel(hiddenLabel),
                freqMhz = ap.freqMhz,
                channel = ap.channel,
                rssi = ap.rssi,
                widthMhz = ap.bandwidthMhz,
                color = colorForAp(ap, index, currentBssid),
                isCurrent = ap.bssid == currentBssid
            )
        }

        binding.channelChart.setAps(
            aps = curves,
            minFreq = minFreq,
            maxFreq = maxFreq,
            channels = channels,
            emptyHint = getString(R.string.channel_chart_empty_hint),
            segments = if (is24) null else ChannelChartView.SEGMENTS_5G
        )
    }

    /**
     * 当前连接的 AP 用固定醒目色，其余按顺序取调色板
     */
    private fun colorForAp(ap: ChannelAp, index: Int, currentBssid: String?): Int {
        return if (ap.bssid == currentBssid) {
            0xFF00E676.toInt()
        } else {
            ChannelChartView.AP_COLORS.getOrElse(index % ChannelChartView.AP_COLORS.size) { 0xFF9E9E9E.toInt() }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun currentConnectedBssid(): String? {
        if (!checkPermissions()) return null
        return try {
            WifiUtils.formatBssid(wifiManager.connectionInfo?.bssid)
        } catch (_: Exception) {
            null
        }
    }

    private fun renderList(aps: List<ChannelAp>) {
        if (aps.isEmpty()) {
            binding.rvChannel.visibility = View.GONE
            binding.tvChannelEmpty.visibility = View.VISIBLE
            return
        }

        binding.rvChannel.visibility = View.VISIBLE
        binding.tvChannelEmpty.visibility = View.GONE

        val existing = adapter
        if (existing != null && binding.rvChannel.adapter === existing) {
            // 复用适配器，仅替换数据并整体刷新（数据量小，无需 DiffUtil）
            existing.submit(aps)
        } else {
            val newAdapter = ChannelAdapter(aps, currentNameMode())
            binding.rvChannel.layoutManager = LinearLayoutManager(this)
            binding.rvChannel.adapter = newAdapter
            adapter = newAdapter
        }
    }

    private fun renderScanHint(count: Int, band: Int) {
        binding.tvScanHint.text = if (count == 0) {
            getString(R.string.channel_scan_hint_empty)
        } else {
            getString(R.string.channel_scan_hint, count)
        }
    }

    private fun showEmpty(message: String) {
        aps24g = emptyList()
        aps5g = emptyList()
        binding.rvChannel.visibility = View.GONE
        binding.tvChannelEmpty.visibility = View.VISIBLE
        binding.tvChannelEmpty.text = message
        binding.tvScanHint.text = ""
        binding.channelChart.setAps(
            aps = emptyList(),
            minFreq = ChannelChartView.FREQ_MIN_24G,
            maxFreq = ChannelChartView.FREQ_MAX_24G,
            channels = ChannelChartView.CHANNELS_24G,
            emptyHint = getString(R.string.channel_chart_empty_hint)
        )
    }

    // ==================== 列表适配器 ====================

    private class ChannelAdapter(
        aps: List<ChannelAp>,
        var nameMode: String
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        private var aps: List<ChannelAp> = aps

        /**
         * 替换数据源并刷新。列表项数量变化时用 notifyDataSetChanged，
         * 数量不变时逐项刷新，避免整表闪动。
         */
        fun submit(newAps: List<ChannelAp>) {
            val sameSize = newAps.size == aps.size
            aps = newAps
            if (sameSize) notifyItemRangeChanged(0, aps.size) else notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvApName: TextView = view.findViewById(R.id.tvApName)
            val tvApMac: TextView = view.findViewById(R.id.tvApMac)
            val tvApRssi: TextView = view.findViewById(R.id.tvApRssi)
            val tvApChannel: TextView = view.findViewById(R.id.tvApChannel)
            val tvApBandwidth: TextView = view.findViewById(R.id.tvApBandwidth)
            val tvApStandard: TextView = view.findViewById(R.id.tvApStandard)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel_ap, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ap = aps[position]
            val context = holder.itemView.context

            holder.tvApName.text = ap.ssidLabel(context.getString(R.string.channel_hidden_ssid))

            holder.tvApMac.text = ap.deviceLabel(showApName = nameMode == NAME_MODE_AP)

            holder.tvApRssi.text = ap.rssi.toString()

            holder.tvApChannel.text = if (ap.channel > 0) ap.channel.toString() else "-"

            holder.tvApBandwidth.text = if (ap.bandwidthMhz > 0) ap.bandwidthMhz.toString() else "-"

            // 标准列缩写为数字代号：4/5/6/7
            holder.tvApStandard.text = standardShort(ap.standard)
        }

        override fun getItemCount() = aps.size

        private fun standardShort(standard: String): String {
            return when {
                standard.isEmpty() -> "-"
                standard.contains("Wi-Fi 7") -> "7"
                standard.contains("Wi-Fi 6") -> "6"
                standard.contains("Wi-Fi 5") -> "5"
                standard.contains("Wi-Fi 4") -> "4"
                else -> "-"
            }
        }
    }

    // ==================== 权限 ====================

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

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        androidx.core.app.ActivityCompat.requestPermissions(
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
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadData()
            } else {
                Toast.makeText(this, getString(R.string.channel_permission_required), Toast.LENGTH_LONG).show()
                showEmpty(getString(R.string.channel_permission_required))
            }
        }
    }
}

private const val BAND_24G = 0
private const val BAND_5G = 1
private const val NAME_MODE_AP = "ap"
private const val NAME_MODE_MAC = "mac"
