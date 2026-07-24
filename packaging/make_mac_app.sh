#!/bin/bash
# Assemble "PixelPusher Bridge.app" + zip a partir du jar compile.
# Usage : ./make_mac_app.sh   (depuis le dossier packaging/)
# Fonctionne sur macOS et Linux (necessite zip).
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
JAR="$ROOT/dist/PixelPusherBridge.jar"
APP="$ROOT/dist/PixelPusher Bridge.app"

if [ ! -f "$JAR" ]; then
  echo "ERREUR : $JAR introuvable. Lance d'abord BUILD.bat (ou build.sh)."
  exit 1
fi

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$HERE/macos/Info.plist"        "$APP/Contents/"
cp "$HERE/macos/PixelPusherBridge" "$APP/Contents/MacOS/"
cp "$HERE/macos/AppIcon.icns"      "$APP/Contents/Resources/"
cp "$JAR"                          "$APP/Contents/Resources/"
chmod +x "$APP/Contents/MacOS/PixelPusherBridge"

cd "$ROOT/dist"
rm -f "PixelPusher Bridge (macOS).zip"
zip -qry "PixelPusher Bridge (macOS).zip" "PixelPusher Bridge.app"
echo "OK : dist/PixelPusher Bridge (macOS).zip"
