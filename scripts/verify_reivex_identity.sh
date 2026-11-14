#!/usr/bin/env bash
# Check that the standalone ReiVex app identity and entry points are present.
set -euo pipefail
cd "$(dirname "$0")/.."

gradle="android/app/build.gradle.kts"
manifest="android/app/src/main/AndroidManifest.xml"
grep -Fq 'namespace = "com.reivex.browser"' "$gradle"
grep -Fq 'applicationId = "com.reivex.browser"' "$gradle"
grep -Fq 'com.reivex.browser.ReiVexApplication' "$manifest"
grep -Fq 'com.reivex.browser.downloads.DownloadBrowserActivity' "$manifest"
grep -Fq 'android.intent.category.LAUNCHER' "$manifest"

if find android/app/src/main/kotlin -type f -print0 |
  xargs -0 grep -EIl 'com\.reianix\.reianix_local|com\.reiflix\.reiflix_local|ReiAnixAppDependencies|NativePlayerActivity|AniListAccountActivity' >/dev/null; then
  echo "ReiAnix-only app code remains in the ReiVex source tree." >&2
  exit 1
fi

echo "ReiVex app identity and browser launcher verified."
