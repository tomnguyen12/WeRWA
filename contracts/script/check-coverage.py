#!/usr/bin/env python3
"""Cổng độ phủ dòng cho phần WeRWA (contracts/src/). Đọc lcov.info do `forge coverage --report lcov` sinh.

Dùng: python3 script/check-coverage.py lcov.info [ngưỡng phần trăm, mặc định 95]
"""
import sys
from pathlib import Path

lcov = Path(sys.argv[1] if len(sys.argv) > 1 else "lcov.info")
threshold = float(sys.argv[2]) if len(sys.argv) > 2 else 95.0

files = {}
current = None
for raw in lcov.read_text().splitlines():
    if raw.startswith("SF:"):
        path = raw[3:].replace("\\", "/")
        current = path if (path.startswith("src/") or "/src/" in path) and "/lib/" not in path else None
        if current:
            files.setdefault(current, [0, 0])
    elif current and raw.startswith("LF:"):
        files[current][0] += int(raw[3:])
    elif current and raw.startswith("LH:"):
        files[current][1] += int(raw[3:])
    elif raw == "end_of_record":
        current = None

if not files:
    print("Chưa có hợp đồng WeRWA trong src/; bỏ qua cổng độ phủ.")
    sys.exit(0)

total = sum(f[0] for f in files.values())
hit = sum(f[1] for f in files.values())
for path, (lf, lh) in sorted(files.items()):
    pct = 100.0 * lh / lf if lf else 100.0
    print(f"{pct:6.2f}%  {lh:4d}/{lf:<4d}  {path}")
pct = 100.0 * hit / total if total else 100.0
print(f"Tổng phần WeRWA: {pct:.2f}% ({hit}/{total}), ngưỡng {threshold:.0f}%")
sys.exit(0 if pct >= threshold else 1)
