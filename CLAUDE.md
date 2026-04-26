# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库工作时提供指导。

## 构建

```bash
./gradlew assembleRelease    # 构建 Release (签名) APK - 默认构建正式版
./gradlew assembleDebug      # 仅需要 Debug APK 时使用
./gradlew lint               # 运行 Lint 检查
./gradlew test               # 运行单元测试
./gradlew connectedAndroidTest  # 运行仪器测试（需要设备/模拟器）
```

输出位置：`app/build/outputs/apk/release/app-release.apk`

**默认构建正式版**：每次编译都使用 `assembleRelease` 生成签名的 Release APK。

## 架构

单 Activity Android 应用 (`MainActivity.kt`) — 无 Fragment，无 ViewModel 层。网络和 JSON 解析通过协程直接在 Activity 中完成。使用 ViewBinding 访问视图。

- **WiFi BSSID**: 从 `WifiManager.connectionInfo.bssid` 读取，通过移除非十六进制字符并转为小写进行标准化。
- **BSS 信息 API**: `GET https://linux.ustc.edu.cn/api/bssinfo.php?bssid=<12 位十六进制>` — 返回包含 `data` 数组的 JSON；第一个元素包含 `AC_IP`、`AP_IP`、`AP_NAME`、`AP_SN`、`AP_Building`。
- **自动更新**: 启动时获取 `https://noc.ustc.edu.cn/version.json`（字段：`versionCode`、`versionName`、`updateUrl`、`updateLog`），与当前 `versionCode` 比较，如果服务器版本更高则显示更新对话框。更新只是在浏览器中打开 `updateUrl`。

## Maven 镜像

`settings.gradle.kts` 将 Google 和 Maven Central 重定向到阿里云镜像。如果同步时出现网络问题，可能需要更新这些配置。

## 签名

Release 构建使用 `wifi-bss-key.jks` 签名。密钥库凭据在 `keystore.properties` 中（已加入 .gitignore）。

## 版本发布清单

每次更新版本时需要同步修改：

1. **app/build.gradle.kts** - 更新 `versionCode` 和 `versionName`
2. **MainActivity.kt** - 更新 `getVersionInfo()` 返回的版本号
3. **MainActivity.kt** - 更新 `getChangesText()` 添加新版本更新说明

```kotlin
// MainActivity.kt
private fun getVersionInfo(): String {
    return "版本：1.14"  // 与 versionName 一致
}

private fun getChangesText(): String {
    return """
v1.14 布局优化
- 加宽左侧标签栏（70dp → 90dp），避免文字换行

v1.13 历史记录优化
- BSSID 变化时立即记录历史（无需等待查询结果）
- 查询到 AP 信息后自动更新对应历史记录
- 历史记录时间精确到秒

v1.12 信号强度图表
- 新增 RSSI 信号强度曲线图，显示最近 5 分钟变化
- BSSID 切换时用红色大圆点标记
- 优化布局：图表置顶，查询结果可滚动

v1.11 历史记录功能
- 新增查询历史记录，保存 BSSID、AP 名字、楼名和查询时间
- BSSID 变化时自动记录，相同 BSSID 智能合并
- 菜单中可查看和清除历史记录
    """.trimIndent()
}
```
