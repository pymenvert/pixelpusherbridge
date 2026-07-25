# PixelPusher Bridge — contexte projet

> Ce fichier est lu automatiquement par Claude Code au démarrage. Il contient tout
> ce qu'il faut savoir pour reprendre le développement sans repartir de zéro.

## Le projet en une phrase

Application desktop (macOS + Windows) qui reçoit du **Art-Net / sACN** depuis un logiciel
lumière (MadMapper, grandMA, Resolume, console…) et le transmet à des contrôleurs
**PixelPusher** qui pilotent des rubans LED. Pilotage par **interface web embarquée**.

Auteur : Pierre Yves Mansour — Collectif WSK. Licence MIT (voir `LICENSE`).
Version actuelle : **1.6.0** (voir `CHANGELOG.md` pour l'historique complet).
L'utilisateur parle **français** — l'interface, les logs et les commentaires sont en français.
Usage réel : **spectacle vivant**. Une panne se voit devant public : la robustesse prime
toujours sur l'élégance d'une refonte.

## Règle d'or : ne pas toucher au cœur legacy

```
src/com/heroicrobot/…     ← code d'origine (robot-head/PixelPusher-artnet), NE PAS REFACTORER
src/com/pixelpusher/…     ← tout le code de l'app, c'est ici qu'on développe
```

Le cœur réseau vient d'un projet open source éprouvé. Il est repris **quasiment à
l'identique** parce que c'est lui qui garantit la fluidité (aucune saccade sur les LED),
priorité n°1 de l'utilisateur. Les seules modifications faites sont des *hooks* minimaux,
tous marqués par un commentaire `// … PixelPusherBridge` :

- `ArtNetReceiver` : compteurs de paquets, `muteDmx`, `lastFrame` (moniteur DMX), `tap` (enregistreur), retry de bind, `listening`/`bindError`, **`setLength` du tampon + catch élargi**
- `SacnReceiver` : idem + `enabled`, **validation longueur / vecteur / start code**
- `CardThread` : compteur `totalPacketsSent`
- `DeviceRegistry` : 2 `System.err` passés en `System.out`, **verrou en `try/finally`, division en virgule flottante du limiteur de puissance, `setLength` du tampon de découverte**
- `Strip` : **index de la table anti-log masqué en `& 0xff`** (l'octet est signé)
- `PixelPusher` : **borne de `getStrip()` corrigée en `>=`**
- `PixelPusherObserver` : **mapping reconstruit puis publié atomiquement** (`volatile`)

Chacun de ces correctifs vient d'un défaut confirmé par l'audit et documenté dans
`AUDIT.md`. Ne pas les défaire en resynchronisant avec `reference/`.

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
  LegacyMessages.java Traduit et reclasse les messages du legacy (WARN/INFO au lieu d'ERROR),
                      et compte les signaux exploités par le Diagnostic
  StatusService.java  Snapshot JSON pour le dashboard (/api/status)
  Diagnostic.java     Vérifications + conseils + rapport texte
  Blackout.java       Blackout d'urgence **verrouillé** (mute DMX + extinction, reprise explicite)
  Recorder.java       Enregistreur/lecteur de séquences (format PPBREC01, écriture asynchrone)
  TestPatterns.java   10 scénarios de test dont tests de lignes
  Watchdog.java       Blackout auto si plus de signal DMX
  Qr.java             Encodeur QR maison (byte mode, ECC L, v1-5) — zéro dépendance
  Tray.java           Icône barre système (AWT SystemTray) + menu clic droit
  WebServer.java      Serveur HTTP embarqué, tous les endpoints
  MiniHttpServer.java Serveur HTTP **de secours** en sockets bloquantes (voir DEVNOTES §9)
  Names.java          Règle de nommage unique des fichiers : `sanitize`, `safeFile`,
                      noms interdits par Windows (CON, LPT1…). Presets et Recorder délèguent ici.
  Net.java            Énumération des adresses IPv4 site-local (QR code, URL téléphone).
                      Seule implémentation : ne pas réécrire une boucle `NetworkInterface` ailleurs.
  Json.java           Échappement JSON + `Json.Writer`, mini-écrivain qui pose les virgules et
                      échappe **toute** chaîne (clé comprise). Tout nouveau JSON passe par lui.

tests/                Banc de tests (hors du jar) — `RUN-TESTS.bat`
web/index.html        Interface complète (un seul fichier, vanilla JS, aucun framework)
web/mobile.html       Interface téléphone simplifiée (/m)
packaging/            Launchers macOS/Windows, Info.plist, icône, script de signature
reference/            Sources du projet d'origine + pixel.rc d'exemple (lecture seule, référence)
tools/                Scripts de test (faux PixelPusher, émetteur Art-Net, validateur QR,
                      vérificateur des interfaces web, test de bout en bout `smoke_test.py`)
BUILD.bat             Compilation sous Windows
build.sh              Compilation sous macOS / Linux (même cible, même contrôle du bytecode)
RUN-TESTS.bat         Banc de tests
VERIFIER-TOUT.bat     Compilation + tests + bout en bout : la commande à faire passer
                      avant un spectacle ou une publication
LISEZ-MOI.txt         Pense-bête à livrer à côté des binaires (version, licence, aide)
DEMARRER-ICI.md       Point d'entrée du dossier pour une reprise de développement
AUDIT.md              Rapport d'audit complet (90 défauts confirmés) + plan d'action
audit-findings.json   Les mêmes, en données exploitables
```

## Toujours lancer les tests avant de publier

```
RUN-TESTS.bat
```
Compile `src/` + `tests/`, exécute 313 vérifications (conversions de puissance,
reclassement des messages legacy, échappement JSON, filtrage des noms de fichiers,
serveur HTTP de secours de bout en bout), puis valide les interfaces web et
l'encodeur QR. Zéro dépendance. Les fichiers `*Test.java` et le dossier `tests/`
ne partent jamais dans le jar.

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
| `/api/action` | POST | `remap` / `clearLogs` / `blackout` / `resume` / `stop` / `restart` |
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
./build.sh         (macOS / Linux, nécessite un JDK)
```
Les deux scripts font la même chose : compilation en Java 11 (`--release 11 -encoding UTF-8`,
sources hors `*Test.java`), intégration de `web/*.html` et de `LICENSE` dans `META-INF`,
`jar cfe`, puis **relecture de la version majeure du bytecode produit** (55 = Java 11) —
sans ce contrôle, un jar illisible sur la machine de spectacle partirait en silence.
`BUILD.bat` arrête en plus les instances en cours (sinon le jar est corrompu silencieusement)
et met à jour le dossier Windows + l'app macOS.

Zip macOS complet : `python3 tools/make_livrables.py` — **depuis n'importe quel système**,
Windows compris. Il pose les permissions Unix explicitement dans l'archive puis la relit
pour vérifier que le lanceur y est bien exécutable (voir DEVNOTES §7 : la contrainte
« assembler sous Unix » était fausse, c'était l'outil `zip` qui manquait, pas le format).
`packaging/make_mac_app.sh` reste disponible sous Mac et Linux — même contenu produit.

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
8. **La plage de ports web est un invariant partagé** : `WebServer.bind()` et
   `Main.detectRunningInstance()` doivent balayer exactement `port → port + AppConfig.PORT_SCAN_RANGE`.
   Élargir une seule des deux boucles casse le verrou d'instance unique **en silence**.
9. **`MiniHttpServer` ferme la connexion dès que le handler rend la main** (contrairement au
   serveur du JDK, où l'échange survit au handler). Une réponse en flux continu doit donc
   tenir la ligne sur le serveur de secours, et surtout **pas** sur celui du JDK.

## Priorités de l'utilisateur (dans l'ordre)

1. **Fluidité absolue** du flux LED — aucune fonctionnalité ne doit ralentir le chemin Art-Net → pushers (tout ce qui est lourd va dans un thread séparé).
2. **Robustesse** — l'app doit survivre à tout : port occupé, pusher qui disparaît, source qui plante, disque lent.
3. **Simplicité pour un néophyte** — explications partout, valeurs conseillées, diagnostic qui donne la solution, installation en 2 clics.
4. Esthétique soignée et moderne de l'interface.

## Conventions

### Règle d'écriture (accents) — tranchée, ne plus hésiter

Un seul critère : **qui lit le texte ?**

| Ce qu'on écrit | Accents | Exemple |
|---|---|---|
| Commentaires et Javadoc dans les `.java` | **NON** | `// borne du tampon : evite une trame tronquee` |
| Noms de classes, méthodes, variables, constantes, clés de config, clés JSON | **NON** | `purgeUniversSilencieux()`, `artnetThreadAlive` |
| Chaînes destinées à l'utilisateur (logs, messages d'erreur, réponses JSON affichées, libellés du menu système) | **OUI** | `LogBus.warn("Commande refusée : origine inconnue.")` |
| Fichiers `web/*.html` (texte visible, `aria-label`, `title`) | **OUI** | « Écoute Art-Net (port 6454) » |
| Documentation (`*.md`, `LISEZ-MOI.txt`), scripts `.bat` / `.sh` côté messages | **OUI** dans les `.md` · **NON** dans les `.bat`/`.sh` (encodage console peu fiable) | |

Justification : la compilation se fait en `-encoding UTF-8`, un accent dans un commentaire
serait donc légal — mais les `.java` de ce projet transitent par des outils et des consoles
Windows dont l'encodage varie, et un commentaire mal décodé passe inaperçu jusqu'au jour où
il casse une ligne de code. À l'inverse, un message utilisateur sans accent se voit
immédiatement et fait négligé devant un client. Les fichiers `.java` doivent être en
**UTF-8 sans BOM** (un BOM casse la déclaration `package`).

### Autres conventions

- Interface, logs et messages utilisateur **en français**.
- Style Java du projet : 2 espaces, accolades K&R, cible **Java 11**, zéro dépendance externe.
- Chaque nouvelle version : incrémenter `AppConfig.VERSION`, `packaging/macos/Info.plist`
  (2 occurrences), `LISEZ-MOI.txt` et ajouter une entrée dans `CHANGELOG.md`.
  `BUILD.bat` avertit si `AppConfig`, `Info.plist` et `CHANGELOG.md` divergent.
- Toute modification de l'UI doit rester **responsive** (desktop + téléphone).
- Avant de publier : `VERIFIER-TOUT.bat` doit finir en vert (compilation + 100 tests +
  test de bout en bout réseau → LED).
