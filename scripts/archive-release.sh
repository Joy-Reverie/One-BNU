#!/usr/bin/env bash
# 归档一次 release 构建的产物。
#
# R8 的 mapping.txt 必须**每个版本各存一份**，否则用户回报的崩溃栈再也还原不出来
# （v1.9.5–v1.9.32 就是这样丢掉的）。发版流程里紧跟在 assembleRelease 之后跑这个脚本。
#
#   ./scripts/archive-release.sh              # 版本号取自 app/build.gradle.kts
#   ./scripts/archive-release.sh 1.9.34       # 指定版本号
#
# 归档目录默认 ~/Documents/Android/onebnu-mappings，可用 ONEBNU_MAPPING_DIR 覆盖。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${1:-$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$root/app/build.gradle.kts" | head -1)}"
[ -n "$version" ] || { echo "取不到 versionName，请显式传入版本号" >&2; exit 1; }

out="${ONEBNU_MAPPING_DIR:-$HOME/Documents/Android/onebnu-mappings}/v$version"
apk="$root/app/build/outputs/apk/release/app-release.apk"
mapping_dir="$root/app/build/outputs/mapping/release"

for f in "$apk" "$mapping_dir/mapping.txt" "$mapping_dir/seeds.txt"; do
    [ -f "$f" ] || { echo "缺少 $f —— 先跑 ./gradlew :app:assembleRelease" >&2; exit 1; }
done

mkdir -p "$out"
cp "$apk" "$out/One-BNU-$version.apk"
cp "$mapping_dir/mapping.txt" "$mapping_dir/seeds.txt" "$out/"
shasum -a 256 "$out/One-BNU-$version.apk" | awk '{print $1}' > "$out/One-BNU-$version.apk.sha256"

echo "已归档到 $out"
ls -1 "$out"
