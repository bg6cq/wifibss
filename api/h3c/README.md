# H3C AC BSSMAC 查询API服务端

工作原理：登录AC，分别执行display wlan ap all verbose和display wlan bss all verbose，获取的文件分别用process_ap.py 和 process_bss.py处理，生成AP信息文件和BSS信息文件。
如果有多台AC，可以把多台AC的输出组合。

然后运行 server.py 对外提供查询API


## 例子：

目录中display_ap_all_verbose.txt 和 display_ap_bss_all_verbose.txt 分别是AC 采集的数据。

执行以下命令处理数据:
```
python3 process_ap.py display_ap_all_verbose.txt > ap_info.txt 
python3 process_bss.py display_ap_bss_all_verbose.txt > bss_info.txt
```
执行以下命令启动API服务端在8000端口提供服务，认证密码为 xyz:
```
python3 8000 ap_info.txt bss_info.txt group_map.txt name_map.txt xyz
```

执行以下命令可以测试API:
```
$ curl -H "Authorization: Bearer xyz" "http://127.0.0.1:8000/api/bssinfo?bssid=7cde78d69fc0"

{"status": "ok", "data": [{"BSS_MAC": "7cde78d69fc0", "AP_NAME": "AAA-1F-AP01", "AP_SN": "219801A3PFP227000303", "AP_MAC": "7cde78d69fc0", "AC_IP": "100.00.100.250", "BAND": "1", "SSID": "USTC-WW", "AP_Building": "化学实验楼", "AP_IP": "100.00.11.161"}]}%
```


## server.py

基于 BSS 和 AP 数据提供 REST API 查询服务的 HTTP 服务器，数据全部加载到内存中。

```
usage: python3 server.py <port> <ap_info_file> <bss_info_file> <group_mapping_file> <ap_name_mapping_file> [auth_key]
```

### 参数说明

| 参数 | 说明 |
|------|------|
| `port` | TCP 监听端口 |
| `ap_info_file` | AP 信息文件，空格分隔：`AP_NAME AP_GROUP AP_SN AP_MAC AP_IP AC_IP` |
| `bss_info_file` | BSS 信息文件，空格分隔：`BSSID AP_NAME SSID BAND` |
| `group_mapping_file` | AP 组名→楼名映射文件，每行：`组名前缀 楼名`（最长前缀匹配） |
| `ap_name_mapping_file` | AP 名字→楼名映射文件，每行：`AP名前缀 楼名`（最长前缀匹配） |
| `auth_key` | （可选）Bearer Token 认证密钥 |

### 楼名匹配顺序

1. 先按 AP 名字（`ap_name_mapping_file`）最长前缀匹配
2. 匹配不到则按 AP 组名（`group_mapping_file`）最长前缀匹配
3. 仍然匹配不到则使用 AP 组名本身

### API 接口

`GET /api/bssinfo?bssid=XXXXXXXXXXXXXX`

BSSID 中的 `-` 号自动删除，大小写不敏感。

响应示例：

```json
{
  "status": "ok",
  "data": [{
    "BSS_MAC": "19c97ad55e00",
    "AP_NAME": "wlzx-13895-207",
    "AP_SN": "219801A6M28257E00XXX",
    "AP_MAC": "19c97ad55e00",
    "AC_IP": "10.0.0.1",
    "BAND": "2",
    "SSID": "eduroam",
    "AP_Building": "东区 - 网络信息中心",
    "AP_IP": "10.0.0.2"
  }]
}
```

### 认证

启动时指定第 6 个参数 `auth_key` 后启用 Bearer Token 认证。
请求需携带 HTTP 头 `Authorization: Bearer <key>`。
认证失败时返回的数据中 `AP_SN`、`AC_IP`、`AP_IP` 为空字符串。


# H3C AC 命令采集信息输出解析工具

解析 H3C AC `display` 命令的详细输出，提取关键字段，提供 Web API 查询服务。

## process_ap.py

解析 `display wlan ap all verbose` 命令输出。

```
usage: process_ap.py <filename>
```

提取字段（空格分隔）：`AP_NAME AP_GROUP AP_SN AP_MAC AP_IP AC_IP`

## process_bss.py

解析 `display wlan bss all verbose` 命令输出。

```
usage: process_bss.py <filename>
```

提取字段（空格分隔）：`BSSID AP_NAME SSID BAND(Radio ID)`

## 说明

- 缺失字段输出 `-`
- 每个 AP/BSS 输出一行


## 备注

开发本目录下程序的3段提示：
```
display_ap_all_verbose.txt 是h3c ac 的命令输出，写一段python程序 process_ap.py ，参数是文件名，提取 AP_NAME AP_GROUP
  AP_SN AP_MAC AP_IP AC_IP, 这些域直接输出到标准输出，用空格分割
```

```
display_ap_bss_all_verbose.txt 是h3c ac 的命令输出，写一段python程序 process_bss.py ，参数是文件名，提取 BSSID AP_NAME
  SSID BAND(Radio id) , 这些域直接输出到标准输出，用空格分割
```
```
写1个web api server，启动时有6个参数，其中第1个参数是TCP 端口号，第2个参数是AP信息文件名，第3个参数是BSS信息文件名，第4
  个参数是AP组名到楼名的映射文件名，第5个参数是AP名字都楼名的映射文件名，第6个参数是可选的 认证key。AP信息文件包含空格分开的  AP_NAME
  AP_GROUP  AP_SN AP_MAC AP_IP AC_IP，BSS信息文件包含空格分开的 BSSID AP_NAME  SSID BAND，组名和楼名映射分别是一行一行空格
  分割的文字，把前缀匹配的AP组名或AP名字映射成楼的名字（如果查不到，楼名就是AP组名），启动时读入所有文件，把AP_MAC和BSSID
  中的-号删除保留为12位字符，所有数据存在内存中。收到 GET /api/bssinfo?bssid=XXXXXXXXXXXXXX
  时，根据收到的BSSID先查询BSS信息，然后根据查到的AP_NAME查询AP信息，最后返回 
{
  "status": "ok",
  "data": [{
    "BSS_MAC": "19c97ad55e00",
    "AP_NAME": "wlzx-13895-207",
    "AP_SN": "219801A6M28257E00XXX",
    "AP_MAC": "19c97ad55e00",
    "AC_IP": "x.x.x.x",
    "BAND": "2",
    "SSID": "ssid",
    "AP_Building": "东区 - 网络信息中心",
    "AP_IP": "x.x.x.x"
  }]
}
这样的信息。如果启动时带了第6个参数，检查HTTP请求头中的 Authorization: Bearer 后key，如果不匹配，返回的 AP_SN AC_IP AP_IP为空字符串。
```

