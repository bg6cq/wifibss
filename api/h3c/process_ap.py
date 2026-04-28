#!/usr/bin/python3
"""Parse H3C AC 'display wlan ap all verbose' output and extract AP fields."""

import sys

FIELD_MAP = {
    "AP name": "AP_NAME",
    "AP group name": "AP_GROUP",
    "Serial ID": "AP_SN",
    "MAC address": "AP_MAC",
    "IP address": "AP_IP",
    "Current AC IP": "AC_IP",
}


def parse_file(path: str) -> list[dict[str, str]]:
    aps: list[dict[str, str]] = []
    current: dict[str, str] = {}

    with open(path) as f:
        for line in f:
            # Each AP block starts with "AP name"
            if line.startswith("AP name"):
                if current:
                    aps.append(current)
                current = {}

            if ":" not in line:
                continue

            key, _, val = line.partition(":")
            key = key.strip()
            val = val.strip()

            if key in FIELD_MAP:
                current[FIELD_MAP[key]] = val

    if current:
        aps.append(current)

    return aps


def main():
    if len(sys.argv) != 2:
        print("Usage: process_ap.py <filename>", file=sys.stderr)
        sys.exit(1)

    aps = parse_file(sys.argv[1])
    headers = ["AP_NAME", "AP_GROUP", "AP_SN", "AP_MAC", "AP_IP", "AC_IP"]

    for ap in aps:
        print(" ".join(ap.get(h, "-") for h in headers))


if __name__ == "__main__":
    main()
