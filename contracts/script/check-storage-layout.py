#!/usr/bin/env python3
"""Kiểm bố cục lưu trữ của hợp đồng nâng cấp được so với ảnh chụp trong storage-layout/.

Luật (nâng cấp an toàn):
  1. Bỏ biến `__gap` ra, các biến cũ phải là tiền tố của các biến mới: cùng tên, slot, offset, kiểu.
  2. Tổng số slot chiếm dụng (kể cả `__gap`) không đổi; nếu bản cũ không có `__gap` thì chỉ được tăng.

Dùng: python3 script/check-storage-layout.py            (CI: kiểm)
      python3 script/check-storage-layout.py --update   (ghi lại ảnh chụp sau khi SA duyệt thay đổi)
Danh sách hợp đồng ở script/storage-layout.txt, mỗi dòng `<đường dẫn nguồn>:<tên hợp đồng>`.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIST = ROOT / "script" / "storage-layout.txt"
SNAP = ROOT / "storage-layout"
update = "--update" in sys.argv


def targets():
    for line in LIST.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            yield line


def layout(target):
    out = subprocess.run(
        ["forge", "inspect", target, "storageLayout", "--json"],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout
    data = json.loads(out)
    types = data.get("types") or {}
    entries = []
    for s in data.get("storage", []):
        t = s["type"]
        # Bỏ hậu tố id của AST để so ổn định giữa các lần build.
        label_t = types.get(t, {}).get("label", t)
        entries.append({
            "label": s["label"],
            "slot": int(s["slot"]),
            "offset": int(s["offset"]),
            # Biến kiểu hợp đồng/giao diện lưu như address: đổi tên giao diện không đổi bố cục.
            "type": "address" if label_t.startswith("contract ") else re.sub(r"\)\d+_", ")_", label_t),
            "slots": max(1, int(types.get(t, {}).get("numberOfBytes", "32")) // 32),
        })
    return entries


def footprint(entries):
    return max((e["slot"] + e["slots"] for e in entries), default=0)


def compare(name, old, new):
    errors = []
    old_vars = [e for e in old if e["label"] != "__gap"]
    new_vars = [e for e in new if e["label"] != "__gap"]
    key = lambda e: (e["label"], e["slot"], e["offset"], e["type"])
    for i, o in enumerate(old_vars):
        if i >= len(new_vars) or key(new_vars[i]) != key(o):
            got = key(new_vars[i]) if i < len(new_vars) else "không còn"
            errors.append(f"{name}: biến #{i} đổi từ {key(o)} thành {got}")
    has_gap = any(e["label"] == "__gap" for e in old)
    fo, fn = footprint(old), footprint(new)
    if has_gap and fn != fo:
        errors.append(f"{name}: tổng slot đổi {fo} → {fn}; phải bớt __gap đúng bằng số slot thêm vào")
    if not has_gap and fn < fo:
        errors.append(f"{name}: tổng slot giảm {fo} → {fn}")
    return errors


names = list(targets())
if not names:
    print("Chưa có hợp đồng nâng cấp được trong script/storage-layout.txt; bỏ qua.")
    sys.exit(0)

SNAP.mkdir(exist_ok=True)
errors = []
for target in names:
    name = target.split(":")[-1]
    new = layout(target)
    path = SNAP / f"{name}.json"
    if update or not path.exists():
        if not update:
            errors.append(f"{name}: chưa có ảnh chụp; chạy --update và đưa vào PR để SA duyệt")
            continue
        path.write_text(json.dumps(new, indent=2) + "\n")
        print(f"Đã ghi {path.relative_to(ROOT)}")
        continue
    errors += compare(name, json.loads(path.read_text()), new)

if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"Bố cục lưu trữ khớp cho {len(names)} hợp đồng")
