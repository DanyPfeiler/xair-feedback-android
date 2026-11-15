#!/bin/bash
# Compila el APK sin Gradle ni Android Studio, con las herramientas de Debian/Ubuntu:
#   apt install aapt zipalign apksigner dalvik-exchange android-sdk-platform-23
# y el compilador de Kotlin 1.9 (KOTLINC=/ruta/a/kotlinc). Uso: scripts/build_sin_gradle.sh [version]
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
KOTLIN_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")"
ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
VERSION="${1:-1}"
PKG=com.danypfeiler.xairfeedback
OUT=build_local
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/stdlib" "$OUT/dex"

# aapt (sin Gradle) necesita el paquete dentro del manifiesto
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
    app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"
grep -q "package=\"$PKG\"" "$OUT/AndroidManifest.xml"
AAPT_FLAGS=(-M "$OUT/AndroidManifest.xml" -S app/src/main/res -I "$ANDROID_JAR"
            --min-sdk-version 26 --target-sdk-version 33 --version-code "$VERSION" --version-name "1.0.$VERSION")

echo "== recursos"; aapt package -f -m -J "$OUT/gen" "${AAPT_FLAGS[@]}"
echo "== kotlin";   "$KOTLINC" app/src/main/java "$OUT/gen" -cp "$ANDROID_JAR" -no-jdk -jvm-target 1.8 \
                        -Xlambdas=class -Xsam-conversions=class -d "$OUT/classes" 2>&1 | grep -v "^Picked up" || true
javac -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java') 2>&1 | grep -v "^Picked up\|warning\|^1 warn\|^4 warn" || true
test -f "$OUT/classes/com/danypfeiler/xairfeedback/MainActivity.class"
test -f "$OUT/classes/com/danypfeiler/xairfeedback/R.class"

echo "== dex"
(cd "$OUT/stdlib" && unzip -q "$KOTLIN_HOME/lib/kotlin-stdlib.jar" -x 'META-INF/*')
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes" "$OUT/stdlib" 2>&1 | grep -v "^Picked up" || true
test -s "$OUT/dex/classes.dex"

echo "== apk"
aapt package -f "${AAPT_FLAGS[@]}" -F "$OUT/unsigned.apk"
(cd "$OUT/dex" && aapt add ../unsigned.apk classes.dex > /dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks keystore/xair-debug.jks --ks-pass pass:xairfeedback --key-pass pass:xairfeedback \
    --ks-key-alias xair --min-sdk-version 26 --out "$OUT/XAirFeedback.apk" "$OUT/aligned.apk"
apksigner verify --min-sdk-version 26 "$OUT/XAirFeedback.apk" && echo "APK listo: $OUT/XAirFeedback.apk"
