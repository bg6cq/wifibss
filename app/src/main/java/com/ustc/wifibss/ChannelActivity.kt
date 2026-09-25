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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ustc.wifibss.api.MacVendorService
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
 * 名称列支持在 MAC 与 AP 名字间切换，并可将同一设备的多个 SSID 聚合显示为一行：
 * AP名模式按 AP 名合并（同名不同 BSSMAC 合为一行），MAC 模式按 MAC 合并。
 * AP 名字解析策略为本地库优先、远程按需查询：先一次性匹配本地 BSSMAC 表，
 * 未命中的再并发调用查询 API（受 10 分钟缓存保护）；查询失败的 BSSID 在重试
 * 间隔内不再占用每轮查询配额，避免反复失败的 AP 饿死其他 AP 的名字解析。
 */
class ChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelBinding
    private lateinit var wifiManager: WifiManager
    private lateinit var repository: BssRepository

    companion object {
        private const val LOCATION_PERMISSION_CODE = 2001
        private const val SCAN_INTERVAL_MS = 30000L

        // 远程名字查询的并发上限与每轮总数上限
        private const val MAX_REMOTE_LOOKUPS = 20
        private const val REMOTE_CONCURRENCY = 4

        // 查询失败的 BSSID 在此间隔内不再重试（与 API 结果缓存时长一致）
        private const val LOOKUP_RETRY_INTERVAL_MS = 10 * 60 * 1000L
    }

    private var adapter: ChannelAdapter? = null
    private var refreshJob: kotlinx.coroutines.Job? = null

    // 两个频段各自缓存的 AP 列表
    private var aps24g: List<ChannelAp> = emptyList()
    private var aps5g: List<ChannelAp> = emptyList()

    // 已查询到的 AP 名字缓存（跨刷新保留，避免每 30s 重新查一遍）
    private val resolvedNames = mutableMapOf<String, String>()

    // 查询失败的 BSSID → 失败时间：短期内不再重试，把每轮查询配额让给没查过的 AP
    private val failedLookups = mutableMapOf<String, Long>()

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
            // 聚合键随 AP名/MAC 切换而变，需整体重绘；再显式刷新一次以更新设备列文字
            adapter?.nameMode = currentNameMode()
            renderCurrentBand()
            adapter?.notifyDataSetChanged()
        }

        binding.cbAggregate.setOnCheckedChangeListener { _, _ -> renderCurrentBand() }

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

    /**
     * 按「聚合显示」开关加工后的显示列表：AP名模式按 AP 名聚合（同名不同 BSSMAC 合并），
     * MAC 模式按 BSSID 聚合（不同 MAC 不合并）
     */
    private fun displayList(aps: List<ChannelAp>): List<ChannelAp> =
        if (binding.cbAggregate.isChecked)
            ChannelAp.aggregate(aps, getString(R.string.channel_hidden_ssid), currentNameMode() == NAME_MODE_AP)
        else
            aps

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
                building = null,
                centerFreq0 = result.centerFreq0,
                centerFreq1 = result.centerFreq1,
                widthRaw = result.channelWidth,
                capabilities = result.capabilities ?: ""
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
        val now = System.currentTimeMillis()
        val pending = scanned.mapNotNull { result ->
            val bssid = WifiUtils.formatBssid(result.BSSID) ?: return@mapNotNull null
            if (bssid.length != 12) return@mapNotNull null
            if (knownNames.containsKey(bssid)) return@mapNotNull null
            // 近期查询失败的先跳过，否则它们会反复占满每轮配额，导致其他 AP 永远轮不到查询
            val failedAt = failedLookups[bssid]
            if (failedAt != null && now - failedAt < LOOKUP_RETRY_INTERVAL_MS) return@mapNotNull null
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
                    val name = semaphore.withPermit { queryApName(bssid) }
                    if (name != null) {
                        applyResolvedName(bssid, name)
                    } else {
                        // 查不到（不在库里/接口异常）也记一笔，下一轮把配额让给别的 AP
                        failedLookups[bssid] = System.currentTimeMillis()
                    }
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

            // 名字解析后按当前频段/聚合状态整体重绘（各渲染入口只读当前状态，幂等）
            renderCurrentBand()
        }
    }

    // ==================== 渲染 ====================

    private fun renderCurrentBand() {
        val band = currentBand()
        val raw = if (band == BAND_24G) aps24g else aps5g

        // 频谱图固定按 BSSID 聚合：同名不同 MAC 的 AP 可能工作在不同信道，不应合并曲线
        val chartAps = if (binding.cbAggregate.isChecked)
            ChannelAp.aggregate(raw, getString(R.string.channel_hidden_ssid), showApName = false)
        else raw

        renderChart(chartAps, band)
        renderList(displayList(raw))
        renderScanHint()
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
                isCurrent = ap.bssid == currentBssid,
                centerFreqMhz = ap.centerFreq0
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
            val newAdapter = ChannelAdapter(aps, currentNameMode(), ::showApDetail)
            binding.rvChannel.layoutManager = LinearLayoutManager(this)
            binding.rvChannel.adapter = newAdapter
            adapter = newAdapter
        }
    }

    // ==================== AP 详情弹窗 ====================

    /**
     * 点击列表某行：显示该 AP 的详细信息。
     *
     * 弹窗先立即显示扫描结果里已有的字段（信道/频率/带宽/标准/加密等，无需等待网络），
     * 厂商（ip.ustc.edu.cn 按 OUI 查询）与 AP 名字/楼宇（本地库或远程 API，与列表中
     * 名字解析同一套缓存）随后异步补上，查到一项填一项。
     */
    private fun showApDetail(ap: ChannelAp) {
        val view = layoutInflater.inflate(R.layout.dialog_channel_ap, null)
        val tvRssi = view.findViewById<TextView>(R.id.tvDetailRssi)
        val tvLevel = view.findViewById<TextView>(R.id.tvDetailLevel)
        val vSignalFill = view.findViewById<View>(R.id.vSignalFill)
        val tvSsid = view.findViewById<TextView>(R.id.tvDetailSsid)
        val tvFreq = view.findViewById<TextView>(R.id.tvDetailFreq)
        val tvRange = view.findViewById<TextView>(R.id.tvDetailRange)
        val tvBand = view.findViewById<TextView>(R.id.tvDetailBand)
        val tvBandwidthChip = view.findViewById<TextView>(R.id.tvDetailBandwidthChip)
        val tvStandardChip = view.findViewById<TextView>(R.id.tvDetailStandardChip)
        val tvSecurityChip = view.findViewById<TextView>(R.id.tvDetailSecurityChip)
        val tvCapabilities = view.findViewById<TextView>(R.id.tvDetailCapabilities)
        val tvMac = view.findViewById<TextView>(R.id.tvDetailMac)
        val rowBuilding = view.findViewById<View>(R.id.rowDetailBuilding)
        val tvApName = view.findViewById<TextView>(R.id.tvDetailApName)
        val tvBuilding = view.findViewById<TextView>(R.id.tvDetailBuilding)
        val tvVendor = view.findViewById<TextView>(R.id.tvDetailVendor)
        val tvStatus = view.findViewById<TextView>(R.id.tvDetailStatus)

        val unknown = getString(R.string.channel_detail_unknown)

        // 标题：SSID (MAC)，隐藏网络用占位符，与 WiFi Analyzer 的写法一致
        val title = "${ap.ssidLabel(getString(R.string.channel_hidden_ssid))} (${ap.macWithColons()})"

        // ---- 信号强度：大号数值 + 等级文字 + 信号条，颜色随等级变化 ----
        val level = WifiUtils.getSignalLevel(ap.rssi)
        val levelColor = ContextCompat.getColor(this, signalColorRes(level))
        tvRssi.text = ap.rssi.toString()
        tvLevel.text = getString(level.resId)
        tvLevel.setTextColor(levelColor)
        vSignalFill.setBackgroundTintList(android.content.res.ColorStateList.valueOf(levelColor))

        // 信号条宽度按 RSSI 在 -90..-30 之间的位置，并保证最弱时也可见
        val fraction = ((ap.rssi + 90).toFloat() / 60f).coerceIn(0.04f, 1f)
        // 用 post 而非直接取宽：此时视图尚未布局，parent.width 会是 0
        vSignalFill.post {
            val track = (vSignalFill.parent as View).width
            vSignalFill.layoutParams = (vSignalFill.layoutParams as FrameLayout.LayoutParams).apply {
                width = (track * fraction).toInt()
            }
        }

        // ---- 扫描结果里已有的信息，同步填好 ----
        tvSsid.text = ap.ssidLabel(getString(R.string.channel_hidden_ssid))
        tvMac.text = ap.macWithColons()

        val distance = ap.estimatedDistanceMeters()
        tvFreq.text = if (distance != null) {
            getString(R.string.channel_detail_freq_dist, ap.freqMhz, ap.channel, formatDistance(distance))
        } else {
            getString(R.string.channel_detail_freq, ap.freqMhz, ap.channel)
        }

        val ranges = ap.occupiedRangesMhz()
        tvRange.text = when {
            ranges.isEmpty() -> unknown
            ranges.size > 1 -> getString(
                R.string.channel_detail_range_multi,
                ranges.joinToString("  ") { formatRange(it) }
            )
            else -> formatRange(ranges.first())
        }

        val centerChannel = ap.centerChannel()
        val span = ap.channelSpan()
        tvBand.text = if (centerChannel > 0 && span != null && span.first != span.second) {
            getString(
                R.string.channel_detail_band,
                WifiUtils.getBand(ap.freqMhz), span.first, span.second, centerChannel
            )
        } else {
            getString(R.string.channel_detail_band_nocenter, WifiUtils.getBand(ap.freqMhz))
        }

        // 三个短值并排成胶囊；标准列沿用列表里的带圈数字，并补回完整标准名
        tvBandwidthChip.text = getString(R.string.channel_detail_chip_bandwidth, ap.bandwidthMhz)
        tvStandardChip.text = standardChipText(ap.standard)

        val security = ap.securityCode()
        tvSecurityChip.text = if (security.isNotEmpty()) {
            getString(R.string.channel_detail_security, security)
        } else {
            getString(R.string.channel_detail_security_open)
        }

        val tokens = ap.capabilityTokens()
        tvCapabilities.visibility = if (tokens.isEmpty()) View.GONE else View.VISIBLE
        tvCapabilities.text = tokens.joinToString("")

        // ---- 需要查询的两项，先置占位 ----
        tvApName.text = ap.apName ?: unknown
        rowBuilding.visibility = View.GONE
        tvVendor.text = unknown
        tvStatus.text = getString(R.string.channel_detail_loading)
        tvStatus.visibility = View.VISIBLE

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        // 弹窗生命周期内查询，关闭后不再更新已销毁的视图
        val job = lifecycleScope.launch {
            // 厂商查询（独立于 AP 信息查询，任一先返回就先显示）
            launch {
                val vendor = MacVendorService.lookupVendor(ap.bssid)
                if (vendor != null) {
                    // 厂商名较长时自动换行，标签列宽度固定不影响对齐
                    tvVendor.text = vendor
                }
            }

            // AP 名字与楼宇：本地库或远程 API，与列表中名字解析共用同一套缓存
            val result = try {
                repository.queryBssInfo(ap.bssid)
            } catch (_: Exception) {
                null
            }
            val info = result?.apInfo
            if (info != null) {
                val name = info.apName.takeIf { it.isNotBlank() && it != "-" }
                if (name != null) {
                    tvApName.text = name
                }
                val building = info.building.takeIf { it.isNotBlank() && it != "-" }
                if (building != null) {
                    rowBuilding.visibility = View.VISIBLE
                    tvBuilding.text = building
                }
            }
            tvStatus.visibility = View.GONE
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    /**
     * 标准胶囊文字：带圈数字 + 括号里的完整标准名，如「⑥ Wi-Fi 6 (802.11ax)」。
     * 列表列窄只放得下带圈数字，弹窗空间充足就补全，避免要看懂缩写。
     */
    private fun standardChipText(standard: String): String {
        if (standard.isEmpty()) return getString(R.string.channel_detail_unknown)
        val digit = when {
            standard.contains("Wi-Fi 7") -> "⑦"
            standard.contains("Wi-Fi 6") -> "⑥"
            standard.contains("Wi-Fi 5") -> "⑤"
            standard.contains("Wi-Fi 4") -> "④"
            else -> null
        } ?: return standard
        return getString(R.string.channel_detail_chip_standard_pair, digit, standard)
    }

    /**
     * 信号等级对应的颜色资源
     */
    private fun signalColorRes(level: WifiUtils.SignalLevel): Int = when (level) {
        WifiUtils.SignalLevel.EXCELLENT -> R.color.signal_excellent
        WifiUtils.SignalLevel.GOOD -> R.color.signal_good
        WifiUtils.SignalLevel.FAIR -> R.color.signal_fair
        WifiUtils.SignalLevel.WEAK -> R.color.signal_weak
        WifiUtils.SignalLevel.POOR -> R.color.signal_poor
    }


    /**
     * 频率范围显示为「2412 - 2452」；范围两端取信道刻度（20MHz 的上下缘）。
     */
    private fun formatRange(range: IntRange): String = "${range.first} - ${range.last}"

    /**
     * 距离格式化：<10m 保留一位小数，更大时取整
     */
    private fun formatDistance(meters: Double): String =
        if (meters < 10) String.format(java.util.Locale.US, "%.1fm", meters)
        else String.format(java.util.Locale.US, "%.0fm", meters)

    /**
     * 扫描提示行。
     *
     * 计数始终从当前频段的列表长度现算，不接受外部传入：渲染入口有多个
     * （加载、切频段、远程名字解析回调），任何"把调用时的 count 传递下去"的写法
     * 都会让提示文字取决于最后一次回调的时序。这里只读当前状态，保证幂等。
     */
    private fun renderScanHint() {
        val count = displayList(if (currentBand() == BAND_24G) aps24g else aps5g).size
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
        var nameMode: String,
        private val onApClick: (ChannelAp) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        private var aps: List<ChannelAp> = aps

        /**
         * 替换数据源并刷新。
         *
         * 列表按 RSSI 降序排列，信号波动会让同一位置的 AP 换成另一个，
         * 因此不能按位置做增量刷新（会把新数据画到旧行的位置造成错位）。
         * 只有确认内容完全一致时才跳过，否则整体刷新。
         */
        fun submit(newAps: List<ChannelAp>) {
            if (newAps == aps) return
            aps = newAps
            notifyDataSetChanged()
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

            // 标准列缩写为带圈数字：④⑤⑥⑦
            holder.tvApStandard.text = standardShort(ap.standard)

            // 点击整行弹详情，不限于某个单元格
            holder.itemView.setOnClickListener { onApClick(ap) }
        }

        override fun getItemCount() = aps.size

        private fun standardShort(standard: String): String {
            return when {
                standard.isEmpty() -> "-"
                standard.contains("Wi-Fi 7") -> "⑦"
                standard.contains("Wi-Fi 6") -> "⑥"
                standard.contains("Wi-Fi 5") -> "⑤"
                standard.contains("Wi-Fi 4") -> "④"
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
