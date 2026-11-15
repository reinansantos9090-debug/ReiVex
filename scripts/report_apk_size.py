#!/usr/bin/env python3
"""Small read-only APK ZIP report; runs inside the normal build workflow."""
import os
import sys
import zipfile
from pathlib import Path

apk_paths = [Path(p) for p in sys.argv[1:] if Path(p).is_file()]
if not apk_paths:
    raise SystemExit("No APK to report")

summary = ["## APK size and native-library audit", ""]
for apk in apk_paths:
    entries = {}
    top_native = []
    total = apk.stat().st_size
    with zipfile.ZipFile(apk) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = item.filename
            if name.startswith("lib/"):
                group = "Native libraries (.so)"
                if name.endswith(".so"):
                    top_native.append((item.file_size, item.compress_size, name))
            elif name.endswith(".dex"):
                group = "DEX code"
            elif name.startswith("assets/"):
                group = "Assets"
            elif name.startswith("res/") or name == "resources.arsc":
                group = "Resources"
            else:
                group = "Other APK entries"
            uncompressed, compressed = entries.get(group, (0, 0))
            entries[group] = (uncompressed + item.file_size, compressed + item.compress_size)
    summary += [f"### {apk.name}", "",
                f"**Actual APK size:** {total / (1024 * 1024):.2f} MiB ({total:,} bytes)", "",
                "| Content | ZIP entry bytes | Uncompressed bytes |",
                "| --- | ---: | ---: |"]
    for group, (uncompressed, compressed) in sorted(entries.items(), key=lambda kv: -kv[1][1]):
        summary.append(f"| {group} | {compressed / (1024*1024):.2f} MiB | {uncompressed / (1024*1024):.2f} MiB |")
    summary += ["", "**Largest native libraries:**", ""]
    for plain, zipped, name in sorted(top_native, reverse=True)[:12]:
        summary.append(f"- `{name}`: {plain / (1024*1024):.2f} MiB (ZIP {zipped / (1024*1024):.2f} MiB)")
    summary.append("")
report = "\n".join(summary)
print(report)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
        out.write(report + "\n")
