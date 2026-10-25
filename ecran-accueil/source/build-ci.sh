#!/bin/bash
# Compile ZenHome-Glass.apk avec un SDK Android recent (aapt2 / d8 / apksigner).
# Utilise par GitHub Actions (le SDK y est deja installe) ; fonctionne aussi sur Linux/WSL avec ANDROID_HOME defini.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BTV="$(ls "$SDK/build-tools" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)"
PLV="$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sort -V | tail -1)"
BT="$SDK/build-tools/$BTV"
JAR="$SDK/platforms/$PLV/android.jar"
echo "SDK : $SDK | build-tools $BTV | plateforme $PLV"

rm -rf out && mkdir -p out/classes

# 1. Java -> classes (Java 8 : compatible avec toutes les Android TV)
javac --release 8 -nowarn -cp "$JAR" -d out/classes $(find src -name '*.java')

# 2. Ressources + manifeste + assets (photos, polices) -> APK sans code
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml -A assets out/res.zip

# 3. classes -> dex, ajoute a l'APK
"$BT/d8" --lib "$JAR" --min-api 21 --output out $(find out/classes -name '*.class')
(cd out && zip -q unsigned.apk classes.dex)

# 4. alignement + signature (cle jetable creee ici)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
[ -f key.jks ] || keytool -genkeypair -keystore key.jks -storepass changeit -keypass changeit -alias k \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ZenHomeGlass" >/dev/null
"$BT/apksigner" sign --ks key.jks --ks-pass pass:changeit --key-pass pass:changeit \
    --out out/ZenHome-Glass.apk out/aligned.apk
"$BT/apksigner" verify out/ZenHome-Glass.apk
echo "OK : out/ZenHome-Glass.apk"
