#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
android_home="${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK}"
build_tools_version="${BUILD_TOOLS_VERSION:-$(find "$android_home/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -1)}"
build_tools="$android_home/build-tools/$build_tools_version"
android_jar="${ANDROID_JAR:-$android_home/platforms/android-34/android.jar}"
out="$root/build"

rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"

javac -source 8 -target 8 \
  -cp "$android_jar:$root/libs/libxposed-api-102.0.0.jar" \
  -d "$out/classes" \
  "$root/src/com/midori/gboard/termux/MainHook.java"

"$build_tools/d8" --release --min-api 26 --output "$out/dex" \
  "$out/classes/com/midori/gboard/termux/"*.class
"$build_tools/aapt2" compile --dir "$root/res" -o "$out/resources.zip"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$root/AndroidManifest.xml" \
  -o "$out/base.apk" "$out/resources.zip"

python3 - "$out" <<'PY'
import pathlib, sys, zipfile

out = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out / "base.apk") as source, zipfile.ZipFile(out / "unaligned.apk", "w") as target:
    for item in source.infolist():
        target.writestr(item, source.read(item.filename))
    target.write(out / "dex/classes.dex", "classes.dex")
    target.writestr("META-INF/xposed/java_init.list", "com.midori.gboard.termux.MainHook\n")
    target.writestr("META-INF/xposed/scope.list", "com.google.android.inputmethod.latin\n")
    target.writestr("META-INF/xposed/module.prop", "minApiVersion=100\ntargetApiVersion=102\nstaticScope=false\n")
PY

"$build_tools/zipalign" -f 4 "$out/unaligned.apk" "$out/Gboard-Termux-IME-1.3.1-unsigned.apk"

if [[ -n "${KEYSTORE:-}" ]]; then
  "$build_tools/apksigner" sign --ks "$KEYSTORE" \
    --ks-pass "${KS_PASS:?Set KS_PASS}" --ks-key-alias "${KS_ALIAS:?Set KS_ALIAS}" \
    --out "$out/Gboard-Termux-IME-1.3.1.apk" "$out/Gboard-Termux-IME-1.3.1-unsigned.apk"
fi
