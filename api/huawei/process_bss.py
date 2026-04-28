#!/usr/bin/python3
"""Parse Huawei AC 'display vap all' output.

Usage:
    python3 process_bss.py display_vap_all.txt
"""

import sys

# Column positions derived from the header:
# 'AP ID AP name              RfID WID  BSSID          Status  Auth type     STA   SSID'
# pos:   0      6            27    32   37             52      60           74    80
BSSID_COL = (37, 52)
AP_NAME_COL = (6, 27)
BAND_COL = (27, 32)
SSID_COL = 80


def parse_vap(path: str) -> list[tuple[str, str, str, str]]:
    """Parse display_vap_all.txt, return list of (BSSID, AP_NAME, SSID, BAND)."""
    results: list[tuple[str, str, str, str]] = []
    with open(path) as f:
        for line in f:
            if not line or not line[0].isdigit():
                continue
            bssid = line[37:52].strip()
            if not bssid:
                continue
            bssid = bssid.replace("-", "").lower()
            ap_name = line[6:27].strip()
            band = line[27:32].strip()
            ssid = line[80:].strip()
            results.append((bssid, ap_name, ssid, band))
    return results


def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <vap_all.txt>", file=sys.stderr)
        sys.exit(1)

    for bssid, ap_name, ssid, band in parse_vap(sys.argv[1]):
        print(f"{bssid} {ap_name} {ssid} {band}")


if __name__ == "__main__":
    main()
