# PixelPusher Bridge — contexte projet

> Ce fichier est lu automatiquement par Claude Code au démarrage. Il contient tout
> ce qu'il faut savoir pour reprendre le développement sans repartir de zéro.

## Le projet en une phrase

Application desktop (macOS + Windows) qui reçoit du **Art-Net / sACN** depuis un logiciel
lumière (MadMapper, grandMA, Resolume, console…) et le transmet à des contrôleurs
**PixelPusher** qui pilotent des rubans LED. Pilotage par **interface web embarquée**.

Auteur : Pierre Yves Mansour — Collectif WSK. Licence MIT (voir `LICENSE`).
Version actuelle : **1.5.0** (voir `CHANGELOG.md` pour l'historique complet).
L'utilisateur parle **français** — l'interface, les logs et les commentaires sont en français.

## Règle d'or : ne pas toucher au cœur legacy

```
src/com/heroicrobot/…     ← code d'origine (robot-head/PixelPusher-artnet), NE PAS REFACTORER
src/com/pixelpusher/…     ← tout le code de l'app, c'est ici qu'on développe
```

Le cœur réseau vient d'un projet open source éprouvé. Il est repris **quasiment à
l'identique** parce que c'est lui qui garantit la fluidité (aucune saccade sur les LED),
priorité n°1 de l'utilisateur. Les seules modifications faites sont des *hooks* minimaux,
tous marqués par un commentaire `// … PixelPusherBridge` :

- `ArtNetReceiver` : compteurs de paquets, `muteDmx`, `lastFrame` (moniteur DMX), `tap` (enregistreur), retry de bind, `listening`/`bindError`
- `SacnReceiver` : idem + `enabled`
- `CardThread` : compteur `totalPacketsSent`
- `DeviceRegistry` : 2 `System.err` passés en `System.out` (c'étaient des messages informatifs comptés comme erreurs)

Tout le reste passe par la façade **`LegacyCore.java`** (dans le package artnet) : c'est le
seul point de contact autorisé entre le nouveau code et le legacy. Ajouter une fonctionnalité
= nouvelle classe dans `com.pixelpusher.bridge` + endpoint dans `WebServer` + UI dans `web/`.

## Architecture

```
src/com/pixelpusher/bridge/
  Main.java           Démarrage, ordre d'init, arrêt/redémarrage propre, verrou d'instance unique,
                      ouverture en fenêtre d'app (Edge/Chrome --app=)
  AppConfig.java      Config persistante (~/.pixelpusherbridge/config.properties) + VERSION
  Presets.java        Presets nommés (~/.pixelpusherbridge/presets/*.properties)
  LogBus.java         Capture System.out/err → historique mémoire + fichier + SSE ; compteur d'erreurs
  StatusService.java  Snapshot JSON pour le dashboard (/api/status)
  Diagnostic.java     Vérifications + conseils + rapport texte
  Recorder.java       Enregistreur/lecteur de séquences (format PPBREC01, écriture asynchrone)
  TestPatterns.java   10 scénarios de test dont tests de lignes
  Watchdog.java       Blackout auto si plus de signal DMX
  Qr.java             Encodeur QR maison (byte mode, ECC L, v1-5) — zéro dépendance
  Tray.java           Icône barre système (AWT SystemTray) + menu clic droit
  WebServer.java      Serveur HTTP embarqué (com.sun.net.httpserver), tous les endpoints
  Json.java           Échappement JSON

web/index.html        Interface complète (un seul fichier, vanilla JS, aucun framework)
web/mobile.html       Interface téléphone simplifiée (/m)
packaging/            Launchers macOS/Windows, Info.plist, icône, script de signature
reference/            Sources du projet d'origine + pixel.rc d'exemple (lecture seule, référence)
tools/                Scripts de test (faux PixelPusher, émetteur Art-Net, validateur QR)
```

**Aucune dépendance externe.** Uniquement la bibliothèque standard Java (cible **Java 11**).
C'est volontaire : le jar doit rester autonome et léger. Ne pas introduire Maven/Gradle/npm
sans raison majeure.

## Endpoints HTTP

| Endpoint | Méthode | Rôle |
|---|---|---|
| `/` `/index.html` | GET | Interface complète |
| `/m` `/mobile` | GET | Interface téléphone |
| `/api/status` | GET | Snapshot complet (dashboard, poll 1 s) |
| `/api/config` | GET/POST | Configuration (POST = form-urlencoded, application à chaud) |
| `/api/test` | POST | Scénarios de test (enabled, pattern, color, brightness, speed, linePusher, lineStrip) |
| `/api/action` | POST | `remap` / `clearLogs` / `blackout` / `stop` / `restart` |
| `/api/logs` | GET | Flux SSE des logs |
| `/api/logs/download` | GET | Journal texte |
| `/api/presets` | GET/POST | `save` / `load` / `delete` |
| `/api/recorder` | GET/POST | `record` / `stopRecord` / `play` / `stopPlay` / `delete` |
| `/api/dmx?u=N` | GET | Valeurs brutes du dernier paquet DMX (moniteur) |
| `/api/diagnostic` | GET | Vérifications JSON |
| `/api/diagnostic/download` | GET | Rapport texte complet |
| `/qr.svg[?text=…]` | GET | QR code SVG (par défaut : URL mobile LAN) |

Ports utilisés : **6454** Art-Net · **5568** sACN · **7331** discovery PixelPusher ·
**7350** interface (glisse sur 7351+ si occupé) · **80** URL courte (best effort).

## Compiler

```
BUILD.bat          (Windows, nécessite un JDK)
```
Le script arrête d'abord les instances en cours (sinon le jar est corrompu silencieusement),
compile en Java 11, embarque `web/*.html`, génère `dist/PixelPusherBridge.jar` et met à jour
le dossier Windows + l'app macOS.

Zip macOS complet : `packaging/make_mac_app.sh` — **à lancer depuis macOS ou Linux**, jamais
depuis Windows (le zip perdrait le bit exécutable du launcher, l'app ne démarrerait plus).

## Tester sans matériel

Le dossier `tools/` contient tout le nécessaire (Python 3, aucune dépendance) :

```bash
python3 tools/fake_pusher.py 60        # simule un PixelPusher (2 lignes × 8 px) 60 s
python3 tools/artnet_send.py 10 255 0 0  # envoie du rouge en Art-Net pendant 10 s
python3 tools/check_leds.py            # affiche ce que le faux pusher a reçu
python3 tools/validate_qr.py <jar>     # valide l'encodeur QR (décodeur indépendant)
```

Scénario type : lancer le faux pusher, lancer le bridge (`java -jar dist/PixelPusherBridge.jar --no-browser`),
envoyer de l'Art-Net, puis vérifier les couleurs reçues avec `check_leds.py`.

## Pièges connus (déjà rencontrés, ne pas refaire l'erreur)

Voir **`DEVNOTES.md`** pour le détail complet. Les principaux :

1. **Ne jamais recompiler pendant que l'app tourne** — un jar réécrit sous une JVM active est corrompu (`ZipFile invalid LOC header`). BUILD.bat le gère désormais.
2. **macOS : `/usr/bin/java` existe toujours**, même sans Java installé (leurre Apple). Tout lanceur doit valider avec `java -version`.
3. **Le zip macOS doit être créé sous Unix** (bit exécutable).
4. **Reed-Solomon (Qr.java)** : le polynôme générateur doit être inversé après construction. Validé contre le vecteur de référence v1-M "HELLO WORLD".
5. **OpenCV QRCodeDetector est un juge peu fiable** pour valider des QR — utiliser le décodeur indépendant de `tools/validate_qr.py`.
6. **Univers Art-Net 0 côté source = univers 1 côté bridge** (source de confusion récurrente pour les utilisateurs).
7. `pixel.rc` du pusher : `artnet_universe` et `artnet_channel` à 0 = aucun mapping, rien ne s'allume.

## Priorités de l'utilisateur (dans l'ordre)

1. **Fluidité absolue** du flux LED — aucune fonctionnalité ne doit ralentir le chemin Art-Net → pushers (tout ce qui est lourd va dans un thread séparé).
2. **Robustesse** — l'app doit survivre à tout : port occupé, pusher qui disparaît, source qui plante, disque lent.
3. **Simplicité pour un néophyte** — explications partout, valeurs conseillées, diagnostic qui donne la solution, installation en 2 clics.
4. Esthétique soignée et moderne de l'interface.

## Conventions

- Interface, logs et messages utilisateur **en français**.
- Code Java : commentaires en français, sans accents dans les fichiers `.java` (compilation `-encoding UTF-8` mais on reste prudent), style du projet (2 espaces, accolades K&R).
- Chaque nouvelle version : incrémenter `AppConfig.VERSION`, `packaging/macos/Info.plist` (2 occurrences) et ajouter une entrée dans `CHANGELOG.md`.
- Toute modification de l'UI doit rester **responsive** (desktop + téléphone).
