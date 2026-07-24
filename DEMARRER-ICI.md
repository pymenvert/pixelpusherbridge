# Démarrer ici

Ce dossier est **autonome** : tu peux le déplacer où tu veux, il contient tout le projet,
sa documentation, ses outils de test et les sources d'origine.

## Pour reprendre le développement avec Claude Code

```bash
cd <ce-dossier>
claude
```

Claude Code lit automatiquement **`CLAUDE.md`** au démarrage : il y trouvera l'architecture,
les règles du projet, les endpoints, la façon de compiler et de tester, et les pièges à éviter.

Bonne première question à lui poser :
> « Lis CLAUDE.md et DEVNOTES.md, puis explique-moi l'architecture du projet et ce qui reste dans le backlog. »

## Ce qu'il y a dans ce dossier

| Fichier / dossier | Rôle |
|---|---|
| **CLAUDE.md** | Contexte projet pour Claude Code — à lire en premier |
| **DEVNOTES.md** | Mémoire technique : décisions, pièges rencontrés, validations, backlog |
| **README.md** | Présentation et mode d'emploi (destiné aux utilisateurs / GitHub) |
| **CHANGELOG.md** | Historique des versions (v1.0 → v1.5) |
| **LICENSE** | MIT + note sur le cœur réseau d'origine |
| `src/` | Code source Java (legacy `heroicrobot` + app `pixelpusher`) |
| `web/` | Interface complète (`index.html`) et interface téléphone (`mobile.html`) |
| `packaging/` | Launchers macOS/Windows, Info.plist, icône, script de signature |
| `tools/` | Scripts de test Python (faux pusher, émetteur Art-Net, validateur QR) |
| `reference/` | Sources du projet d'origine + exemple de `pixel.rc` (lecture seule) |
| `dist/` | Binaires générés (jar, app macOS, dossier Windows) |
| `BUILD.bat` | Compilation (Windows, nécessite un JDK) |
| *PixelPusher Bridge - Presentation et Installation.pdf* | Doc utilisateur illustrée |

## Cycle de développement type

```bash
# 1. modifier le code (src/… ou web/…)

# 2. compiler — arrête tout seul les instances en cours
BUILD.bat

# 3. tester sans matériel (3 terminaux, ou en arrière-plan)
python3 tools/fake_pusher.py 60 8 96
java -jar dist/PixelPusherBridge.jar --no-browser
python3 tools/artnet_send.py 10 255 0 0
python3 tools/check_leds.py --watch

# 4. vérifier l'interface
#    http://localhost:7350        interface complète
#    http://localhost:7350/m      interface téléphone
```

## Publier sur GitHub

Le `.gitignore` est déjà configuré (les binaires ne partent pas dans le dépôt).

```bash
git init && git add . && git commit -m "PixelPusher Bridge v1.5.0"
```

Attache les zips de `dist/` à une **Release** GitHub taguée `v1.5.0` plutôt que de les
versionner. Pense à retirer `reference/` du dépôt public si tu préfères ne pas redistribuer
les sources d'origine (ou garde-les, avec le crédit déjà présent dans `LICENSE`).
