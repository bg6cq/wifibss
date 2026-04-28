# 华为 AC BSSMAC 查询工具

工作原理：登录 AC，分别执行 `display ap all`、`display ap elabel all` 和 `display vap all`，获取的文件分别用 `process_ap.py` 和 `process_bss.py` 处理，生成 AP 信息和 BSS 信息文件。
如果有多台 AC，可以把多台 AC 的输出组合。

这里只有数据处理的过程，具体API 服务请参考 h3c 目录中内容。


## process_ap.py

解析 `display ap all` 和 `display ap elabel all` 命令输出。

```
usage: python3 process_ap.py <ap_all.txt> <elabel_all.txt> [AC_IP]
```

提取字段（空格分隔）：`AP_NAME AP_GROUP AP_SN AP_MAC AP_IP AC_IP`

- 两个文件通过 MAC 地址关联
- `AC_IP` 为可选参数，不传则输出 `-`
- 缺失字段输出 `-`

### 示例

```
python3 process_ap.py display_ap_all.txt display_ap_elabel_all.txt > ap_info.txt
python3 process_ap.py display_ap_all.txt display_ap_elabel_all.txt 10.10.0.1 > ap_info.txt
```

## process_bss.py

解析 `display vap all` 命令输出。

```
usage: python3 process_bss.py <vap_all.txt>
```

提取字段（空格分隔）：`BSSID AP_NAME SSID BAND(Radio ID)`

- BSSID 标准化为 12 位小写十六进制（去除分隔符）

### 示例

```
python3 process_bss.py display_vap_all.txt > bss_info.txt
```

## 说明

- 采用固定列宽解析表格输出，而非空格分割
- 每个 AP/BSS 输出一行
- `AP_MAC` 和 `BSSID` 统一为 12 位小写十六进制格式
