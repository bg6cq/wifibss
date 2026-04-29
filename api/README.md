# 通用 BSSMAC 查询 API 服务端

基于 BSS 和 AP 数据提供 REST API 查询服务的 HTTP 服务器，数据全部加载到内存中，支持任意 AC 厂商的数据（H3C、华为等）。

```
usage: python3 server.py <port> <ap_info_file> <bss_info_file> <group_mapping_file> <ap_name_mapping_file> [auth_key]
```

## 参数说明

| 参数 | 说明 |
|------|------|
| `port` | TCP 监听端口 |
| `ap_info_file` | AP 信息文件，空格分隔：`AP_NAME AP_GROUP AP_SN AP_MAC AP_IP AC_IP` |
| `bss_info_file` | BSS 信息文件，空格分隔：`BSSID AP_NAME SSID BAND` |
| `group_mapping_file` | AP 组名→楼名映射文件，每行：`组名前缀 楼名`（最长前缀匹配） |
| `ap_name_mapping_file` | AP 名字→楼名映射文件，每行：`AP名前缀 楼名`（最长前缀匹配） |
| `auth_key` | （可选）Bearer Token 认证密钥 |

## 楼名匹配顺序

1. 先按 AP 名字（`ap_name_mapping_file`）最长前缀匹配
2. 匹配不到则按 AP 组名（`group_mapping_file`）最长前缀匹配
3. 仍然匹配不到则使用 AP 组名本身

## API 接口

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

## 认证

启动时指定 `auth_key` 后启用 Bearer Token 认证。
请求需携带 HTTP 头 `Authorization: Bearer <key>`。
认证失败时返回的数据中 `AP_SN`、`AC_IP`、`AP_IP` 为空字符串。

## 数据准备

数据文件可通过各厂商对应的处理脚本生成：

- **H3C AC**: `h3c/` 目录下 `process_ap.py` + `process_bss.py`
- **华为 AC**: `huawei/` 目录下 `process_ap.py` + `process_bss.py`

详细数据采集和处理步骤请参见各子目录中的 README。

## 完整示例

```bash
# 1. 使用 H3C 数据处理脚本生成数据文件
cd h3c
python3 process_ap.py display_ap_all_verbose.txt > ap_info.txt
python3 process_bss.py display_ap_bss_all_verbose.txt > bss_info.txt
cd ..

# 2. 启动 API 服务（端口 8000，开启认证）
python3 server.py 8000 h3c/ap_info.txt h3c/bss_info.txt h3c/group_map.txt h3c/name_map.txt xyz

# 3. 测试查询
curl -H "Authorization: Bearer xyz" "http://127.0.0.1:8000/api/bssinfo?bssid=7cde78d69fc0"
```

## 开发提示

本目录下程序由以下 3 段提示经 AI 生成：

**prompt 1**: `process_ap.py`
```
display_ap_all_verbose.txt 是h3c ac 的命令输出，写一段python程序 process_ap.py ，参数是文件名，提取 AP_NAME AP_GROUP
  AP_SN AP_MAC AP_IP AC_IP, 这些域直接输出到标准输出，用空格分割
```

**prompt 2**: `process_bss.py`
```
display_ap_bss_all_verbose.txt 是h3c ac 的命令输出，写一段python程序 process_bss.py ，参数是文件名，提取 BSSID AP_NAME
  SSID BAND(Radio id) , 这些域直接输出到标准输出，用空格分割
```

**prompt 3**: `server.py`
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
这样的信息。如果启动时带了第6个参数，检查HTTP请求头中的 Authorization: Bearer 后key，如果不匹配，返回的 AP_SN AC_IP AP_IP为空字符串。
```
