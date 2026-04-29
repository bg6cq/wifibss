#!/usr/bin/python3
"""AC BSS info web API server."""

import sys
import json
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs


def load_ap_info(path):
    """Load AP info file: AP_NAME AP_GROUP AP_SN AP_MAC AP_IP AC_IP"""
    aps = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 6:
                continue
            ap_name = parts[0]
            aps[ap_name] = {
                'AP_NAME': ap_name,
                'AP_GROUP': parts[1],
                'AP_SN': parts[2],
                'AP_MAC': parts[3].replace('-', '').lower(),
                'AP_IP': parts[4],
                'AC_IP': parts[5],
            }
    return aps


def load_bss_info(path):
    """Load BSS info file: BSSID AP_NAME SSID BAND"""
    entries = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 4:
                continue
            entries.append({
                'BSSID': parts[0].replace('-', '').lower(),
                'AP_NAME': parts[1],
                'SSID': parts[2],
                'BAND': parts[3],
            })
    return entries


def load_mapping(path):
    """Load mapping file: prefix building_name"""
    mappings = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=1)
            if len(parts) < 2:
                continue
            mappings.append((parts[0], parts[1]))
    return mappings


def find_building(name, mappings):
    """Find building by longest prefix match."""
    match = None
    match_len = 0
    for prefix, building in mappings:
        if name.startswith(prefix) and len(prefix) > match_len:
            match = building
            match_len = len(prefix)
    return match


class BSSInfoHandler(BaseHTTPRequestHandler):
    aps = {}
    bss_entries = []
    group_mappings = []
    ap_name_mappings = []
    auth_key = None

    def _build_response(self, bssid):
        """Build response data for the given BSSID, checking all auth states."""
        # check auth first
        hide = False
        if self.auth_key:
            auth_header = self.headers.get('Authorization', '')
            expected = f'Bearer {self.auth_key}'
            hide = (auth_header != expected)

        # find BSS entry
        bss_entry = None
        for b in self.bss_entries:
            if b['BSSID'] == bssid:
                bss_entry = b
                break

        if not bss_entry:
            return []

        ap_name = bss_entry['AP_NAME']
        ap_info = self.aps.get(ap_name, {})

        building = None
        if ap_info:
            building = find_building(ap_name, self.ap_name_mappings)
            if not building:
                building = find_building(ap_info.get('AP_GROUP', ''), self.group_mappings)
            if not building:
                building = ap_info.get('AP_GROUP', '')

        result = {
            'BSS_MAC': bssid,
            'AP_NAME': ap_name,
            'AP_SN': ap_info.get('AP_SN', '') if not hide else '',
            'AP_MAC': ap_info.get('AP_MAC', ''),
            'AC_IP': ap_info.get('AC_IP', '') if not hide else '',
            'BAND': bss_entry['BAND'],
            'SSID': bss_entry['SSID'],
            'AP_Building': building or '',
            'AP_IP': ap_info.get('AP_IP', '') if not hide else '',
        }
        return [result]

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        if parsed.path != '/api/bssinfo' or 'bssid' not in params:
            self._send_json(404, {'status': 'error', 'message': 'Not found'})
            return

        bssid = params['bssid'][0].replace('-', '').lower()
        data = self._build_response(bssid)
        self._send_json(200, {'status': 'ok', 'data': data})

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write('[%s] %s\n' % (self.log_date_time_string(), fmt % args))


def main():
    if len(sys.argv) < 6:
        print(f'Usage: {sys.argv[0]} <port> <ap_info_file> <bss_info_file> '
              '<group_mapping_file> <ap_name_mapping_file> [auth_key]',
              file=sys.stderr)
        sys.exit(1)

    port = int(sys.argv[1])
    ap_file = sys.argv[2]
    bss_file = sys.argv[3]
    group_map_file = sys.argv[4]
    ap_map_file = sys.argv[5]
    auth_key = sys.argv[6] if len(sys.argv) > 6 else None

    BSSInfoHandler.aps = load_ap_info(ap_file)
    BSSInfoHandler.bss_entries = load_bss_info(bss_file)
    BSSInfoHandler.group_mappings = load_mapping(group_map_file)
    BSSInfoHandler.ap_name_mappings = load_mapping(ap_map_file)
    BSSInfoHandler.auth_key = auth_key

    print(f'Loaded {len(BSSInfoHandler.aps)} APs, {len(BSSInfoHandler.bss_entries)} BSS entries', file=sys.stderr)
    print(f'  group mappings: {len(BSSInfoHandler.group_mappings)}', file=sys.stderr)
    print(f'  AP name mappings: {len(BSSInfoHandler.ap_name_mappings)}', file=sys.stderr)
    if auth_key:
        print('Authentication enabled', file=sys.stderr)

    server = HTTPServer(('', port), BSSInfoHandler)
    print(f'Listening on port {port}', file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('Shutting down...', file=sys.stderr)
        server.server_close()


if __name__ == '__main__':
    main()
