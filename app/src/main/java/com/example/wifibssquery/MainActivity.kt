package com.example.wifibssquery

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val WIFI_PERMISSION_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

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
        updateBssid()
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

        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    /**
     * 显示关于对话框，获取并显示 Git commit 信息
     */
    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val tvCommits = dialogView.findViewById<TextView>(R.id.tvCommits)
        tvCommits.text = getString(R.string.loading_commits)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.show()

        // 获取 commit 信息
        lifecycleScope.launch {
            val commits = fetchGitCommits()
            tvCommits.text = commits
        }
    }

    /**
     * 从 GitHub 获取 commit 信息
     */
    private suspend fun fetchGitCommits(): String = withContext(Dispatchers.IO) {
        try {
            // 使用 GitHub API 获取 commit 列表
            val url = "https://api.github.com/repos/username/repo/commits"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                parseCommits(body)
            } else {
                // 如果无法获取，返回本地信息
                getLocalCommitInfo()
            }
        } catch (e: Exception) {
            getLocalCommitInfo()
        }
    }

    /**
     * 解析 GitHub API 返回的 commit 数据
     */
    private fun parseCommits(jsonString: String?): String {
        if (jsonString.isNullOrEmpty()) return "无法获取 commit 信息"

        try {
            val jsonArray = org.json.JSONArray(jsonString)
            val sb = StringBuilder()
            for (i in 0 until Math.min(jsonArray.length(), 10)) {
                val commit = jsonArray.getJSONObject(i)
                val sha = commit.getString("sha").substring(0, 7)
                val commitObj = commit.getJSONObject("commit")
                val message = commitObj.getJSONObject("message").getString("message")
                val author = commitObj.getJSONObject("author").getString("name")
                val date = commitObj.getString("date").substring(0, 10)
                sb.append("[$sha] $message\n")
                sb.append("作者：$author  日期：$date\n\n")
            }
            return sb.toString()
        } catch (e: Exception) {
            return getLocalCommitInfo()
        }
    }

    /**
     * 返回本地硬编码的 commit 信息
     */
    private fun getLocalCommitInfo(): String {
        return """
        [b817304] 添加 AP 信息显示和签名配置
        作者：Zhang Huanjie  日期：2026-04-25

        [2d41675] 添加 gradle wrapper zip 文件到.gitignore
        作者：Zhang Huanjie  日期：2026-04-25

        [322b577] 修复 Gradle 配置以兼容 Java 11
        作者：Zhang Huanjie  日期：2026-04-25

        [e8ce5ac] 添加 Gradle Wrapper 文件
        作者：Zhang Huanjie  日期：2026-04-25

        [3f2eeeb] 初始化 Android WiFi BSS 查询应用
        作者：Zhang Huanjie  日期：2026-04-25
        """.trimIndent()
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
        val url = "https://linux.ustc.edu.cn/api/bssinfo.php?bssid=$bssid"

        val request = Request.Builder()
            .url(url)
            .build()

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
