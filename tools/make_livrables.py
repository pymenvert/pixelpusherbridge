#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Assemble les deux livrables distribuables — depuis n'importe quel système :

    dist/PixelPusher Bridge (macOS).zip     l'app complète, droits Unix corrects
    dist/PixelPusher Bridge (Windows).zip   le jar, les lanceurs, LICENSE

POURQUOI CE SCRIPT EXISTE
Le livrable macOS devait jusqu'ici être assemblé sur un Mac ou sous Linux : une
archive fabriquée sous Windows perd le bit exécutable du lanceur, et l'app ne
démarre plus (piège n°7 de DEVNOTES). En pratique, la machine de développement
tournant sous Windows, le zip livré finissait par dater d'une version antérieure
au jar — exactement ce qu'on veut éviter.

Or ce n'est pas le format zip qui est en cause, c'est l'outil : le format stocke
les permissions Unix dans le champ « external attributes », que les outils
Windows laissent simplement vide. En écrivant l'archive nous-mêmes, on pose ces
bits explicitement et l'archive est identique à celle qu'aurait produite `zip`
sur un Mac.

Vérifie son travail : le script relit l'archive qu'il vient d'écrire et refuse
de la valider si le lanceur n'y est pas exécutable.

    python3 tools/make_livrables.py
"""
import io
import os
import re
import shutil
import stat
import sys
import zipfile

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGING = os.path.join(RACINE, "packaging", "macos")
DIST = os.path.join(RACINE, "dist")
JAR = os.path.join(DIST, "PixelPusherBridge.jar")
APP = os.path.join(DIST, "PixelPusher Bridge.app")
ZIP = os.path.join(DIST, "PixelPusher Bridge (macOS).zip")
SIGNATURE = "Signer l'app (optionnel).command"

# Ce qui doit être exécutable dans l'archive. Tout le reste est en 0644.
EXECUTABLES = {
    "PixelPusher Bridge.app/Contents/MacOS/PixelPusherBridge",
    SIGNATURE,
}

MODE_EXEC = 0o755
MODE_FICHIER = 0o644
MODE_DOSSIER = 0o755
UNIX = 3  # ZipInfo.create_system : indispensable pour que macOS lise les droits


def version_du_code():
    chemin = os.path.join(RACINE, "src", "com", "pixelpusher", "bridge", "AppConfig.java")
    with io.open(chemin, encoding="utf-8") as f:
        m = re.search(r'VERSION\s*=\s*"([^"]+)"', f.read())
    return m.group(1) if m else "?"


def controler_version(version):
    """Le Info.plist doit annoncer la même version que le code : sinon macOS
    affiche un numéro faux dans « À propos » et les mises à jour semblent ne pas
    avoir pris."""
    chemin = os.path.join(PACKAGING, "Info.plist")
    with io.open(chemin, encoding="utf-8") as f:
        contenu = f.read()
    versions = set(re.findall(r"<string>(\d+\.\d+\.\d+)</string>", contenu))
    if versions != {version}:
        print("  ATTENTION : Info.plist annonce %s, le code annonce %s."
              % (", ".join(sorted(versions)) or "rien", version))
        return False
    return True


def assembler_app():
    for f in (os.path.join(PACKAGING, "Info.plist"),
              os.path.join(PACKAGING, "PixelPusherBridge"),
              os.path.join(PACKAGING, "AppIcon.icns"),
              os.path.join(PACKAGING, SIGNATURE),
              os.path.join(RACINE, "LICENSE"),
              JAR):
        if not os.path.isfile(f):
            raise SystemExit("Élément manquant, l'archive serait incomplète : " + f)

    if os.path.isdir(APP):
        shutil.rmtree(APP)
    contenus = os.path.join(APP, "Contents")
    os.makedirs(os.path.join(contenus, "MacOS"))
    os.makedirs(os.path.join(contenus, "Resources"))
    shutil.copy2(os.path.join(PACKAGING, "Info.plist"), contenus)
    shutil.copy2(os.path.join(PACKAGING, "PixelPusherBridge"), os.path.join(contenus, "MacOS"))
    shutil.copy2(os.path.join(PACKAGING, "AppIcon.icns"), os.path.join(contenus, "Resources"))
    shutil.copy2(JAR, os.path.join(contenus, "Resources"))
    # La licence MIT impose que sa notice accompagne toute copie du logiciel.
    shutil.copy2(os.path.join(RACINE, "LICENSE"), os.path.join(contenus, "Resources"))


def entree(chemin_dans_zip, mode, dossier=False):
    info = zipfile.ZipInfo(chemin_dans_zip + ("/" if dossier else ""))
    info.create_system = UNIX
    info.external_attr = (mode | (stat.S_IFDIR if dossier else stat.S_IFREG)) << 16
    if dossier:
        info.external_attr |= 0x10  # attribut « répertoire » côté MS-DOS
    info.date_time = (2026, 1, 1, 0, 0, 0)  # archive reproductible
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def ecrire_zip():
    if os.path.exists(ZIP):
        os.remove(ZIP)
    dossiers = []
    fichiers = []
    for racine, noms_dossiers, noms_fichiers in os.walk(APP):
        rel_racine = os.path.relpath(racine, DIST).replace(os.sep, "/")
        dossiers.append(rel_racine)
        for n in sorted(noms_fichiers):
            fichiers.append((os.path.join(racine, n),
                             (rel_racine + "/" + n)))
        noms_dossiers.sort()

    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for d in sorted(dossiers):
            z.writestr(entree(d, MODE_DOSSIER, dossier=True), b"")
        for source, cible in fichiers:
            mode = MODE_EXEC if cible in EXECUTABLES else MODE_FICHIER
            with open(source, "rb") as f:
                z.writestr(entree(cible, mode), f.read())
        # Le script de signature et la licence voyagent à la racine de l'archive,
        # à côté de l'app : c'est ce que contenaient les livraisons précédentes.
        for source, cible, mode in (
            (os.path.join(PACKAGING, SIGNATURE), SIGNATURE, MODE_EXEC),
            (os.path.join(RACINE, "LICENSE"), "LICENSE", MODE_FICHIER),
        ):
            with open(source, "rb") as f:
                z.writestr(entree(cible, mode), f.read())


def verifier():
    """Relit l'archive produite. C'est ce contrôle qui remplace « fais-le sur un
    Mac » : on ne fait pas confiance à l'écriture, on vérifie le résultat."""
    attendus = {
        "PixelPusher Bridge.app/Contents/Info.plist": MODE_FICHIER,
        "PixelPusher Bridge.app/Contents/MacOS/PixelPusherBridge": MODE_EXEC,
        "PixelPusher Bridge.app/Contents/Resources/AppIcon.icns": MODE_FICHIER,
        "PixelPusher Bridge.app/Contents/Resources/PixelPusherBridge.jar": MODE_FICHIER,
        "PixelPusher Bridge.app/Contents/Resources/LICENSE": MODE_FICHIER,
        SIGNATURE: MODE_EXEC,
        "LICENSE": MODE_FICHIER,
    }
    problemes = []
    with zipfile.ZipFile(ZIP) as z:
        presents = {i.filename: i for i in z.infolist()}
        for nom, mode_attendu in attendus.items():
            info = presents.get(nom)
            if info is None:
                problemes.append("absent de l'archive : " + nom)
                continue
            if info.create_system != UNIX:
                problemes.append("droits non Unix : " + nom)
                continue
            mode = (info.external_attr >> 16) & 0o777
            if mode != mode_attendu:
                problemes.append("%s : %04o au lieu de %04o" % (nom, mode, mode_attendu))
        mauvais = z.testzip()
        if mauvais:
            problemes.append("archive corrompue : " + mauvais)
    return problemes


ZIP_WINDOWS = os.path.join(DIST, "PixelPusher Bridge (Windows).zip")
DOSSIER_WINDOWS = os.path.join(DIST, "PixelPusher Bridge (Windows)")


def ecrire_zip_windows():
    """Le livrable Windows n'a pas de contrainte de permissions, mais il doit
    exister sous forme d'archive : un dossier ne s'attache pas à une release."""
    if not os.path.isdir(DOSSIER_WINDOWS):
        return "dossier Windows absent — lance BUILD.bat d'abord"
    if os.path.exists(ZIP_WINDOWS):
        os.remove(ZIP_WINDOWS)
    attendus = {"PixelPusherBridge.jar", "PixelPusher Bridge.bat",
                "Arreter PixelPusher Bridge.bat", "LICENSE"}
    presents = set(os.listdir(DOSSIER_WINDOWS))
    manquants = attendus - presents
    if manquants:
        return "éléments manquants : " + ", ".join(sorted(manquants))
    with zipfile.ZipFile(ZIP_WINDOWS, "w", zipfile.ZIP_DEFLATED) as z:
        base = os.path.basename(DOSSIER_WINDOWS)
        z.writestr(entree(base, MODE_DOSSIER, dossier=True), b"")
        for nom in sorted(presents):
            with open(os.path.join(DOSSIER_WINDOWS, nom), "rb") as f:
                z.writestr(entree(base + "/" + nom, MODE_FICHIER), f.read())
    return None


def main():
    version = version_du_code()
    print("=" * 58)
    print(" Livrables PixelPusher Bridge — version %s" % version)
    print("=" * 58)
    if not os.path.isfile(JAR):
        print("Jar introuvable. Lance BUILD.bat (ou ./build.sh) d'abord.")
        return 1
    controler_version(version)

    print("\n[macOS] Assemblage de l'app…")
    assembler_app()
    print("[macOS] Écriture de l'archive avec les droits Unix…")
    ecrire_zip()
    print("[macOS] Relecture de l'archive produite…")
    problemes = verifier()
    if problemes:
        print("\nÉCHEC — l'archive ne serait pas utilisable sur un Mac :")
        for p in problemes:
            print("   - " + p)
        return 1

    print("\n[Windows] Écriture de l'archive…")
    souci = ecrire_zip_windows()
    if souci:
        print("\nÉCHEC — livrable Windows : " + souci)
        return 1

    print("\n" + "=" * 58)
    for chemin in (ZIP, ZIP_WINDOWS):
        with zipfile.ZipFile(chemin) as z:
            n = len(z.infolist())
        print(" OK  dist/%-38s %3d entrées, %7d octets"
              % (os.path.basename(chemin), n, os.path.getsize(chemin)))
    print(" Lanceur macOS exécutable (0755) vérifié dans l'archive.")
    print("=" * 58)
    return 0


if __name__ == "__main__":
    sys.exit(main())
