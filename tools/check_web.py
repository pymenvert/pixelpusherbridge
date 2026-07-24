#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Vérifie les interfaces web sans navigateur.

Les deux pages sont du HTML+JS écrit à la main, sans build ni linter : une
faute de frappe dans le JavaScript ne se voit qu'à l'exécution, c'est-à-dire
potentiellement en spectacle. Ce script attrape le plus gros avant publication :

  - syntaxe JavaScript (via node --check, si node est installé) ;
  - identifiants référencés par $("...") mais absents du HTML ;
  - balises <script>/<style> déséquilibrées.

Usage :
    python3 tools/check_web.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGES = ["web/index.html", "web/mobile.html"]

# Identifiants créés dynamiquement en JavaScript : ils n'existent pas dans le
# HTML statique, c'est normal.
TOLERES = set()


def verifier(chemin):
    erreurs = []
    with open(os.path.join(RACINE, chemin), encoding="utf-8") as f:
        source = f.read()

    if source.count("<script>") != source.count("</script>"):
        erreurs.append("balises <script> déséquilibrées")
    if source.count("<style>") != source.count("</style>"):
        erreurs.append("balises <style> déséquilibrées")

    scripts = re.findall(r"<script>(.*?)</script>", source, re.S)
    js = "\n".join(scripts)

    if shutil.which("node"):
        tmp = os.path.join(tempfile.gettempdir(), "ppb_check_web.js")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(js)
        res = subprocess.run(["node", "--check", tmp],
                             capture_output=True, text=True)
        if res.returncode != 0:
            erreurs.append("syntaxe JavaScript : " + res.stderr.strip().split("\n")[0])
        os.unlink(tmp)

    # Identifiants HTML déclarés
    declares = set(re.findall(r'\bid\s*=\s*"([^"]+)"', source))
    declares |= set(re.findall(r"\bid\s*=\s*'([^']+)'", source))

    # Identifiants utilisés via $("...") ou getElementById("...")
    utilises = set(re.findall(r'\$\(\s*"([A-Za-z0-9_-]+)"\s*\)', js))
    utilises |= set(re.findall(r'getElementById\(\s*"([A-Za-z0-9_-]+)"\s*\)', js))

    manquants = sorted(utilises - declares - TOLERES)
    if manquants:
        erreurs.append("identifiants utilisés mais absents du HTML : "
                       + ", ".join(manquants))
    return erreurs


def main():
    total = 0
    for page in PAGES:
        erreurs = verifier(page)
        if erreurs:
            total += len(erreurs)
            print("  ECHEC %-18s" % page)
            for e in erreurs:
                print("        - " + e)
        else:
            print("  ok    %-18s" % page)
    if total:
        print("\n%d probleme(s) dans les interfaces web." % total)
        return 1
    print("\nInterfaces web valides.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
