# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release (signed) APK
./gradlew lint               # Run lint checks
./gradlew test               # Run unit tests
./gradlew connectedAndroidTest  # Run instrumentation tests (requires device/emulator)
```

Appears at `app/build/outputs/apk/release/app-release.apk`.

## Architecture

Single-Activity Android app (`MainActivity.kt`) — no fragments, no ViewModel layer. Networking and JSON parsing are done directly in the Activity via coroutines. Uses ViewBinding for view access.

- **WiFi BSSID**: Read from `WifiManager.connectionInfo.bssid`, normalized by stripping non-hex chars and lowercasing.
- **BSS info API**: `GET https://linux.ustc.edu.cn/api/bssinfo.php?bssid=<12-char-hex>` — returns JSON with a `data` array; the first element contains `AC_IP`, `AP_IP`, `AP_NAME`, `AP_SN`, `AP_Building`.
- **Auto-update**: On launch, fetches `https://noc.ustc.edu.cn/version.json` (fields: `versionCode`, `versionName`, `updateUrl`, `updateLog`), compares with current `versionCode`, and shows an update dialog if server version is higher. Update just opens the `updateUrl` in a browser.

## Maven mirroring

`settings.gradle.kts` redirects Google and Maven Central to Aliyun mirrors. If network issues occur during sync, these may need updating.

## Signing

Release builds are signed with `wifi-bss-key.jks`. Keystore credentials are in `keystore.properties` (gitignored).
