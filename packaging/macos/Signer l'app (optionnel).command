#!/bin/bash
# Signature de PixelPusher Bridge.app (a lancer sur le Mac, app a cote de ce script
# ou dans /Applications).
#
# 1. Si un certificat "Pierre Yves Mansour - Collectif WSK" existe dans le
#    trousseau (Developer ID ou certificat auto-signe de type "signature de code"
#    cree via Trousseau d'acces > Assistant de certification), il est utilise.
# 2. Sinon : signature "ad hoc" (suffisante pour Apple Silicon) + suppression
#    de la quarantaine, ce qui regle les blocages "app endommagee".
#
# NB : pour une vraie signature reconnue par Gatekeeper sans clic droit->Ouvrir,
# il faut un compte Apple Developer (99 EUR/an) et un certificat "Developer ID
# Application", puis une notarisation. Ce script fait le maximum sans cela.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/PixelPusher Bridge.app"
if [ ! -d "$APP" ]; then
  APP="/Applications/PixelPusher Bridge.app"
fi
if [ ! -d "$APP" ]; then
  echo "PixelPusher Bridge.app introuvable (ni a cote de ce script, ni dans /Applications)."
  read -r -p "Entree pour fermer..." _
  exit 1
fi

IDENTITY="Pierre Yves Mansour - Collectif WSK"
echo "App : $APP"

if security find-identity -v -p codesigning 2>/dev/null | grep -q "$IDENTITY"; then
  echo "Certificat '$IDENTITY' trouve dans le trousseau : signature avec identite..."
  codesign --force --deep --options runtime -s "$IDENTITY" "$APP" \
    && echo "Signe par : $IDENTITY"
else
  echo "Pas de certificat '$IDENTITY' dans le trousseau : signature ad hoc..."
  codesign --force --deep -s - "$APP" && echo "Signature ad hoc appliquee."
  echo
  echo "Pour signer au nom de '$IDENTITY' sans compte Apple Developer :"
  echo "  Trousseau d'acces > menu Trousseau d'acces > Assistant de certification"
  echo "  > Creer un certificat... > Nom : $IDENTITY > Type : Signature de code"
  echo "  puis relance ce script."
fi

echo "Suppression de la quarantaine..."
xattr -cr "$APP" 2>/dev/null || true
echo
codesign -dv "$APP" 2>&1 | head -4
echo
echo "Termine. Tu peux lancer l'app normalement."
read -r -p "Entree pour fermer..." _
