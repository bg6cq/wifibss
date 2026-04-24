package com.example.wifibssquery

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
    }

    override fun onResume() {
        super.onResume()
        updateBssid()
    }

    private fun setupUI() {
        binding.btnQuery.setOnClickListener {
            val bssid = getFormattedBssid()
            if (bssid != null && bssid.length == 12) {
                queryBssInfo(bssid)
            } else {
                binding.tvResult.text = getString(R.string.no_wifi_connection)
            }
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
        binding.btnQuery.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = fetchBssInfo(bssid)
                withContext(Dispatchers.Main) {
                    binding.tvResult.text = result.ifEmpty { "无相关信息" }
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
