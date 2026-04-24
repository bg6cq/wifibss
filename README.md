# WiFi BSS 查询

一个简单的 Android 应用，用于获取当前 WiFi 连接的 BSS MAC 地址并查询 USTC Linux 用户协会的 BSS 信息。

## 功能

- 获取当前 WiFi 连接的 BSSID
- 自动格式化 BSSID（移除 `-`、`:` 等分隔符，保留 12 位十六进制字符）
- 调用 USTC API 查询 BSS 信息
- 在界面上显示查询结果

## 技术栈

- **语言**: Kotlin
- **最低 SDK**: Android 10 (API 29)
- **目标 SDK**: Android 14 (API 34)
- **网络库**: OkHttp 4.12.0
- **异步**: Kotlin Coroutines

## 权限说明

应用需要以下权限：

- `ACCESS_WIFI_STATE`: 获取 WiFi 状态
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`: Android 10+ 获取 BSSID 需要位置权限
- `NEARBY_WIFI_DEVICES`: Android 13+ 需要
- `INTERNET`: 访问 API

## 使用方法

1. 在 Android Studio 中打开项目
2. 编译并运行到设备或模拟器
3. 授予位置权限
4. 点击"查询 BSS 信息"按钮

## 测试

使用测试 BSSID `bcd0eb0c6691` 可以验证 API 功能。

API 端点：`https://linux.ustc.edu.cn/api/bssinfo.php?bssid={bssid}`
