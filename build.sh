#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
version_name="${VERSION_NAME:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$root/AndroidManifest.xml")}"
out="$root/build"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if command -v docker >/dev/null 2>&1; then
    mkdir -p /tmp/build_tools
    if [[ ! -f /tmp/build_tools/android.jar ]]; then
      curl -sSL -o /tmp/build_tools/android.jar "https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
    fi
    if [[ ! -f /tmp/build_tools/r8.jar ]]; then
      curl -sSL -o /tmp/build_tools/r8.jar "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar"
    fi

    docker run --rm \
      --user "$(id -u):$(id -g)" \
      -v "$root":/workspace \
      -v /tmp/build_tools:/build_tools \
      -e VERSION_NAME="$version_name" \
      -e KEYSTORE="${KEYSTORE:-}" \
      -e KS_PASS="${KS_PASS:-}" \
      -e KS_ALIAS="${KS_ALIAS:-}" \
      gboard-builder bash -c '
        set -euo pipefail
        out=/workspace/build
        rm -rf "$out"
        mkdir -p "$out/classes" "$out/dex"

        javac -encoding UTF-8 -source 8 -target 8 \
          -cp /build_tools/android.jar:/workspace/libs/libxposed-api-102.0.0.jar \
          -d "$out/classes" \
          /workspace/src/com/midori/gboard/termux/MainHook.java

        java -cp /build_tools/r8.jar com.android.tools.r8.D8 --release --min-api 26 --output "$out/dex" \
          "$out/classes/com/midori/gboard/termux/"*.class

        aapt package -f -m -M /workspace/AndroidManifest.xml -S /workspace/res -I /build_tools/android.jar \
          -F "$out/base.apk"

        python3 - "$out" << '\''PY'\''
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

        zipalign -f 4 "$out/unaligned.apk" "$out/Gboard-Termux-IME-v${VERSION_NAME}-unsigned.apk"

        if [[ -n "${KEYSTORE:-}" && -f "${KEYSTORE}" ]]; then
          apksigner sign --ks "$KEYSTORE" \
            --ks-pass "${KS_PASS:?Set KS_PASS}" --ks-key-alias "${KS_ALIAS:?Set KS_ALIAS}" \
            --out "$out/Gboard-Termux-IME-v${VERSION_NAME}.apk" "$out/Gboard-Termux-IME-v${VERSION_NAME}-unsigned.apk"
        else
          keytool -genkeypair -v -keystore "$out/debug.keystore" -storepass android \
            -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
          apksigner sign --ks "$out/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey \
            --out "$out/Gboard-Termux-IME-v${VERSION_NAME}.apk" "$out/Gboard-Termux-IME-v${VERSION_NAME}-unsigned.apk"
        fi
        echo "Build completed successfully: $out/Gboard-Termux-IME-v${VERSION_NAME}.apk"
      '
    exit 0
  else
    echo "Error: ANDROID_HOME is not set and docker is not available" >&2
    exit 1
  fi
fi

android_home="${ANDROID_HOME}"
build_tools_version="${BUILD_TOOLS_VERSION:-$(find "$android_home/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -1)}"
build_tools="$android_home/build-tools/$build_tools_version"
android_jar="${ANDROID_JAR:-$android_home/platforms/android-34/android.jar}"

rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"

javac -encoding UTF-8 -source 8 -target 8 \
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

"$build_tools/zipalign" -f 4 "$out/unaligned.apk" "$out/Gboard-Termux-IME-v${version_name}-unsigned.apk"

if [[ -n "${KEYSTORE:-}" ]]; then
  "$build_tools/apksigner" sign --ks "$KEYSTORE" \
    --ks-pass "${KS_PASS:?Set KS_PASS}" --ks-key-alias "${KS_ALIAS:?Set KS_ALIAS}" \
    --out "$out/Gboard-Termux-IME-v${version_name}.apk" "$out/Gboard-Termux-IME-v${version_name}-unsigned.apk"
fi
