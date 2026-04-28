#!/usr/bin/python3
"""Parse H3C AC 'display wlan bss all verbose' output and extract BSS fields."""

import sys

FIELD_MAP = {
    "AP name": "AP_NAME",
    "BSSID": "BSSID",
    "Radio ID": "BAND",
    "SSID": "SSID",
}


def parse_file(path: str) -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    current: dict[str, str] = {}

    with open(path) as f:
        for line in f:
            if line.startswith(" AP name"):
                if current:
                    entries.append(current)
                current = {}

            if ":" not in line:
                continue

            key, _, val = line.partition(":")
            key = key.strip()
            val = val.strip()

            if key in FIELD_MAP:
                current[FIELD_MAP[key]] = val

    if current:
        entries.append(current)

    return entries


def main():
    if len(sys.argv) != 2:
        print("Usage: process_bss.py <filename>", file=sys.stderr)
        sys.exit(1)

    entries = parse_file(sys.argv[1])
    headers = ["BSSID", "AP_NAME", "SSID", "BAND"]

    for e in entries:
        print(" ".join(e.get(h, "-") for h in headers))


if __name__ == "__main__":
    main()
