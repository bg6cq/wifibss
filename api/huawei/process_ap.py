#!/usr/bin/python3
"""Parse Huawei AC 'display ap all' and 'display ap elabel all' output.

Usage:
    python3 process_ap.py display_ap_all.txt display_ap_elabel_all.txt [AC_IP]
"""

import sys

# Column positions derived from the header line of each file.
# Each tuple is (start, end) — end is the start of the next column.
AP_ALL_COLS = {
    "MAC":    (6, 20),
    "Name":   (21, 48),
    "Group":  (49, 61),
    "IP":     (62, 75),
}

ELABEL_COLS = {
    "MAC":    (5, 19),
    "SN":     (60, 80),
}


def parse_ap_all(path: str) -> dict[str, dict[str, str]]:
    """Parse display_ap_all.txt, return dict keyed by normalized MAC."""
    aps: dict[str, dict[str, str]] = {}
    with open(path) as f:
        for line in f:
            if not line or not line[0].isdigit():
                continue
            mac = line[6:20].strip()
            if not mac:
                continue
            mac = mac.replace("-", "").lower()
            aps[mac] = {
                "AP_NAME":  line[21:48].strip(),
                "AP_GROUP": line[49:61].strip(),
                "AP_IP":    line[62:75].strip(),
                "AP_MAC":   mac,
            }
    return aps


def parse_elabel(path: str) -> dict[str, str]:
    """Parse display_ap_elabel_all.txt, return dict of MAC -> SN."""
    sn_map: dict[str, str] = {}
    with open(path) as f:
        for line in f:
            if not line or not line[0].isdigit():
                continue
            mac = line[5:19].strip()
            if not mac:
                continue
            mac = mac.replace("-", "").lower()
            sn_map[mac] = line[60:80].strip()
    return sn_map


def main() -> None:
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <ap_all.txt> <elabel_all.txt> [AC_IP]", file=sys.stderr)
        sys.exit(1)

    ap_all = parse_ap_all(sys.argv[1])
    elabel = parse_elabel(sys.argv[2])
    ac_ip = sys.argv[3] if len(sys.argv) > 3 else "-"

    for mac, ap in ap_all.items():
        sn = elabel.get(mac, "-")
        values = [ap["AP_NAME"], ap["AP_GROUP"], sn, mac, ap["AP_IP"], ac_ip]
        print(" ".join(values))


if __name__ == "__main__":
    main()
