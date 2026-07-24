#!/bin/bash
# Assemble "PixelPusher Bridge.app" + zip a partir du jar compile.
# Usage : ./make_mac_app.sh   (depuis le dossier packaging/)
# Fonctionne sur macOS et Linux (necessite zip).
#
# Ce script est la SEULE source de verite du livrable macOS : tout ce qui doit
# arriver chez le client doit etre copie ici, sans quoi il disparaitra a la
# prochaine release (le script de signature avait deja ete perdu ainsi).
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
JAR="$ROOT/dist/PixelPusherBridge.jar"
APP="$ROOT/dist/PixelPusher Bridge.app"
SIGN="Signer l'app (optionnel).command"

if [ ! -f "$JAR" ]; then
  echo "ERREUR : $JAR introuvable."
  echo "Compile d'abord le jar avec BUILD.bat sur une machine Windows equipee d'un"
  echo "JDK, puis relance ce script depuis macOS ou Linux."
  exit 1
fi

if ! command -v zip >/dev/null 2>&1; then
  echo "ERREUR : la commande zip est introuvable."
  echo "Lance ce script depuis macOS ou une machine Linux disposant de zip : une"
  echo "archive fabriquee sous Windows perd le bit executable du lanceur et l'app"
  echo "ne demarre plus."
  exit 1
fi

# On controle la presence de TOUS les elements avant d'assembler : un fichier
# manquant produirait un zip incomplet, livre tel quel sans que personne ne le
# remarque.
for f in "$HERE/macos/Info.plist" \
         "$HERE/macos/PixelPusherBridge" \
         "$HERE/macos/AppIcon.icns" \
         "$HERE/macos/$SIGN" \
         "$ROOT/LICENSE"; do
  if [ ! -f "$f" ]; then
    echo "ERREUR : element manquant, l'archive ne serait pas complete : $f"
    exit 1
  fi
done

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$HERE/macos/Info.plist"        "$APP/Contents/"
cp "$HERE/macos/PixelPusherBridge" "$APP/Contents/MacOS/"
cp "$HERE/macos/AppIcon.icns"      "$APP/Contents/Resources/"
cp "$JAR"                          "$APP/Contents/Resources/"
# La licence MIT impose que sa notice accompagne toute copie du logiciel.
cp "$ROOT/LICENSE"                 "$APP/Contents/Resources/"

# Droits explicites plutot que l'umask de la machine de build : une app dont les
# dossiers sont en 0700 est illisible depuis tout autre compte du Mac (poste de
# regie partage entre regisseur titulaire et remplacant) et refuse de demarrer.
chmod -R go+rX "$APP"
chmod 755 "$APP/Contents/MacOS/PixelPusherBridge"

cd "$ROOT/dist"
rm -f "PixelPusher Bridge (macOS).zip"

# Le script de signature et la licence voyagent a cote de l'app, a la racine de
# l'archive : c'est ce que contenaient les livraisons precedentes. Le trap evite
# de laisser ces copies temporaires dans dist/ si le zip echoue.
trap 'rm -f "$ROOT/dist/$SIGN" "$ROOT/dist/LICENSE"' EXIT
rm -f "$SIGN" LICENSE
cp "$HERE/macos/$SIGN" "$SIGN"
cp "$ROOT/LICENSE" LICENSE
chmod 755 "$SIGN"
chmod 644 LICENSE

zip -qry "PixelPusher Bridge (macOS).zip" "PixelPusher Bridge.app" "$SIGN" LICENSE
rm -f "$SIGN" LICENSE

echo "OK : dist/PixelPusher Bridge (macOS).zip"
echo "Contenu attendu : PixelPusher Bridge.app (lanceur, Info.plist, icone, jar,"
echo "LICENSE), $SIGN et LICENSE a la racine."
