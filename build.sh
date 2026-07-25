#!/bin/bash
# ============================================================================
#  PixelPusher Bridge - Compilation sous macOS et Linux
#
#  Equivalent de BUILD.bat : memes sources (hors *Test.java), meme cible
#  Java 11, meme jar, meme controle de la version du bytecode produit.
#  Usage : ./build.sh   (depuis la racine du projet, ou de n'importe ou)
#
#  Pas d'accents dans ce fichier : l'encodage d'un terminal ne se devine pas,
#  et un message illisible au moment ou la compilation echoue est le pire
#  moment pour decouvrir un probleme d'encodage.
# ============================================================================
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

JAR="dist/PixelPusherBridge.jar"
MAIN_CLASS="build/classes/com/pixelpusher/bridge/Main.class"
DOSSIER_WINDOWS="dist/PixelPusher Bridge (Windows)"
APP_MACOS="dist/PixelPusher Bridge.app"

echo "============================================"
echo " PixelPusher Bridge - Compilation"
echo "============================================"

# ---------------------------------------------------------------------------
# 1. Outils necessaires
#
# On exige javac ET jar : un JRE seul possede parfois "java" sans "javac",
# et l'erreur brute ("javac: command not found") n'aide personne.
# ---------------------------------------------------------------------------
manquant=""
command -v javac >/dev/null 2>&1 || manquant="javac"
command -v jar   >/dev/null 2>&1 || manquant="${manquant:+$manquant et }jar"

if [ -n "$manquant" ]; then
  echo
  echo "ERREUR : il manque $manquant - il faut un JDK, pas seulement un JRE."
  echo
  echo "  macOS  : brew install --cask temurin"
  echo "           (ou telecharge le JDK sur https://adoptium.net)"
  echo "  Debian / Ubuntu : sudo apt install default-jdk"
  echo "  Fedora          : sudo dnf install java-latest-openjdk-devel"
  echo
  echo "Verifie ensuite avec : javac -version"
  exit 1
fi

echo "JDK detecte : $(javac -version 2>&1)"

# ---------------------------------------------------------------------------
# 2. Instances en cours
#
# Sous Windows, une JVM verrouille son jar et BUILD.bat doit arreter les
# instances avant de compiler. Ici on ne tue rien - couper un bridge en pleine
# representation serait pire que tout. On ecrit le jar dans un fichier
# temporaire puis on le deplace : le remplacement cree un nouvel inode, la JVM
# en marche continue de lire l'ancien fichier sans se corrompre. Elle ne verra
# la nouvelle version qu'apres un redemarrage, d'ou l'avertissement.
# ---------------------------------------------------------------------------
# pgrep n'existe pas partout (Git Bash sous Windows, images minimales) : son
# absence ne doit surtout pas faire echouer la compilation.
if command -v pgrep >/dev/null 2>&1 && pgrep -f "PixelPusherBridge" >/dev/null 2>&1; then
  echo
  echo "ATTENTION : un PixelPusher Bridge tourne actuellement."
  echo "            La compilation se fera quand meme sans le perturber, mais il"
  echo "            faudra le redemarrer pour qu'il utilise le nouveau jar."
  echo
fi

# ---------------------------------------------------------------------------
# 3. Liste des sources (les tests ne partent jamais dans le jar)
# ---------------------------------------------------------------------------
rm -rf build
mkdir -p build/classes
SOURCES="build/sources_list.txt"
find src -name '*.java' ! -name '*Test.java' > "$SOURCES"

if [ ! -s "$SOURCES" ]; then
  echo "ERREUR : aucun fichier source trouve dans src/ - mauvais dossier ?"
  exit 1
fi
echo "Sources : $(wc -l < "$SOURCES" | tr -d ' ') fichiers (hors *Test.java)"

# ---------------------------------------------------------------------------
# 4. Compilation
#
# Le repli sans --release ne doit servir QUE si le JDK refuse l'option
# elle-meme. Sur une vraie erreur de compilation on s'arrete net : sinon on
# fabriquerait un jar dont le bytecode cible la version du JDK local, qui
# refuserait de demarrer sur un poste equipe d'un Java 11
# (UnsupportedClassVersionError, invisible derriere le lanceur).
# ---------------------------------------------------------------------------
echo "Compilation (cible Java 11)..."
if ! javac --release 11 -encoding UTF-8 -d build/classes "@$SOURCES" 2>build/javac_err.txt; then
  if grep -qE -- "--release|release version|invalid target release" build/javac_err.txt; then
    echo "Option --release 11 refusee par ce JDK, nouvel essai en mode standard..."
    echo "La version reelle du bytecode sera verifiee juste apres."
    if ! javac -encoding UTF-8 -d build/classes "@$SOURCES" 2>build/javac_err.txt; then
      echo
      echo "ERREUR DE COMPILATION :"
      cat build/javac_err.txt
      echo
      echo "(details egalement dans build/javac_err.txt)"
      exit 1
    fi
  else
    echo
    echo "ERREUR DE COMPILATION :"
    cat build/javac_err.txt
    echo
    echo "(details egalement dans build/javac_err.txt)"
    exit 1
  fi
fi
# Avertissements eventuels : ils ne font pas echouer le build, mais on les
# montre. Un « [ ... ] && cat » suffirait a faire sortir le script sous
# « set -e » des que le fichier est vide, c'est-a-dire dans le cas normal.
if [ -s build/javac_err.txt ]; then
  cat build/javac_err.txt
fi

# ---------------------------------------------------------------------------
# 5. Garde-fou : version majeure du bytecode produit
#
# Octets 6-7 de l'en-tete d'une classe compilee. 55 = Java 11. Sans ce
# controle, un repli ou un futur JDK produirait en silence un jar illisible sur
# la machine de spectacle.
# ---------------------------------------------------------------------------
echo "Verification de la version du bytecode (Java 11 = 55)..."
if [ ! -f "$MAIN_CLASS" ]; then
  echo "ERREUR : $MAIN_CLASS introuvable apres compilation."
  exit 1
fi
set -- $(od -An -tu1 -j6 -N2 "$MAIN_CLASS")
MAJEURE=$(( $1 * 256 + $2 ))
if [ "$MAJEURE" -ne 55 ]; then
  echo
  echo "ERREUR : bytecode en version $MAJEURE au lieu de 55."
  echo "Le jar ne demarrerait pas sur un poste equipe d'un Java plus ancien."
  exit 1
fi

# ---------------------------------------------------------------------------
# 6. Interface web et licence embarquees dans le jar
# ---------------------------------------------------------------------------
echo "Integration de l'interface web..."
mkdir -p build/classes/web
if ! cp web/*.html build/classes/web/ 2>/dev/null; then
  echo
  echo "ERREUR : copie de web/*.html impossible - le jar serait sans interface."
  exit 1
fi

# La licence MIT impose que la notice accompagne toute copie du logiciel :
# on l'embarque dans le jar, puis a cote des binaires livres.
mkdir -p build/classes/META-INF
if [ -f LICENSE ]; then
  cp LICENSE build/classes/META-INF/LICENSE
else
  echo "ATTENTION : LICENSE introuvable, le jar partira sans notice de licence."
fi

# ---------------------------------------------------------------------------
# 7. Fabrication du jar (ecriture indirecte, voir etape 2)
# ---------------------------------------------------------------------------
echo "Creation du JAR..."
mkdir -p dist
jar cfe build/PixelPusherBridge.jar com.pixelpusher.bridge.Main -C build/classes .
mv -f build/PixelPusherBridge.jar "$JAR"

# ---------------------------------------------------------------------------
# 8. Mise a jour des livrables deja assembles
# ---------------------------------------------------------------------------
if [ -d "$DOSSIER_WINDOWS" ]; then
  cp -f "$JAR" "$DOSSIER_WINDOWS/"
  if [ -f LICENSE ]; then
    cp -f LICENSE "$DOSSIER_WINDOWS/"
  fi
  echo "Dossier Windows mis a jour (jar + LICENSE)."
fi

if [ -d "$APP_MACOS/Contents/Resources" ]; then
  cp -f "$JAR" "$APP_MACOS/Contents/Resources/"
  if [ -f LICENSE ]; then
    cp -f LICENSE "$APP_MACOS/Contents/Resources/"
  fi
  echo "App macOS mise a jour (jar + LICENSE)."
fi

# ---------------------------------------------------------------------------
# 9. Coherence des versions
#
# La convention du projet exige la meme valeur dans AppConfig.VERSION, les deux
# entrees de Info.plist et CHANGELOG.md. Simple avertissement : ce n'est pas
# une raison de rater un build.
# ---------------------------------------------------------------------------
VERSION="$(sed -n 's/.*VERSION[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)".*/\1/p' \
  src/com/pixelpusher/bridge/AppConfig.java | head -n 1 || true)"
if [ -n "${VERSION:-}" ]; then
  coherent=1
  if [ -f packaging/macos/Info.plist ]; then
    autres="$(grep -c "<string>$VERSION</string>" packaging/macos/Info.plist || true)"
    total="$(grep -cE "<string>[0-9]+\.[0-9]+\.[0-9]+</string>" packaging/macos/Info.plist || true)"
    [ "$autres" = "$total" ] || coherent=0
  fi
  grep -q "\[$VERSION\]" CHANGELOG.md 2>/dev/null || coherent=0
  if [ "$coherent" -eq 1 ]; then
    echo "Version $VERSION : AppConfig, Info.plist et CHANGELOG concordent."
  else
    echo "ATTENTION : la version $VERSION de AppConfig.java ne figure pas a"
    echo "            l'identique dans packaging/macos/Info.plist et/ou CHANGELOG.md."
  fi
else
  echo "ATTENTION : version illisible dans AppConfig.java, controle ignore."
fi

rm -f "$SOURCES"

echo
echo "============================================"
echo " OK ! Fichiers generes dans le dossier dist/"
echo "   - PixelPusherBridge.jar ($(wc -c < "$JAR" | tr -d ' ') octets)"
echo
echo " Lancer :   java -jar \"$JAR\""
echo " Tester :   java -jar \"$JAR\" --no-browser"
echo
echo " Livrables distribuables : python3 tools/make_livrables.py"
echo " Il assemble les deux archives (macOS et Windows) avec les droits Unix"
echo " corrects, puis relit l'archive macOS pour verifier que le lanceur y est"
echo " bien executable. Fonctionne depuis n'importe quel systeme."
echo " Pense a joindre LISEZ-MOI.txt a cote des binaires distribues."
echo "============================================"
