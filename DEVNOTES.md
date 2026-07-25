# Notes de développement

Mémoire technique du projet : décisions, pièges rencontrés et vérifications faites.
À lire avant de modifier quoi que ce soit de sensible.

## Décisions d'architecture

**Pourquoi garder le code legacy quasi intact ?** La priorité n°1 est la fluidité du flux
LED. Le code `com.heroicrobot` vient d'un projet éprouvé en production depuis des années
(Heroic Robotics). Le réécrire ferait courir un risque de régression sur le timing pour un
gain nul. Toutes les fonctionnalités ajoutées vivent **à côté**, dans des threads séparés,
et communiquent avec le legacy via `LegacyCore`.

**Pourquoi un serveur HTTP embarqué plutôt qu'une UI native (Swing/JavaFX) ?** Multi-plateforme
sans effort, accessible depuis le téléphone, aucune dépendance, et l'interface peut évoluer
sans recompiler le Java. L'illusion d'application native est obtenue par l'icône de barre
système (`Tray.java`) + l'ouverture en mode `--app=` d'Edge/Chrome.

**Pourquoi zéro dépendance ?** Le jar doit rester autonome (~180 Ko), démarrer instantanément
et ne jamais casser à cause d'une lib. C'est pour ça que l'encodeur QR est écrit à la main
plutôt que d'embarquer ZXing.

**Format des séquences (PPBREC01)** : magic 8 octets, puis par trame
`int64 timestampMs | uint16 universe | uint16 length | length octets`. Simple, streamable,
rejouable au timing exact. L'écriture passe par une queue + thread dédié pour que le thread
réseau ne touche jamais le disque.

## Pièges rencontrés (et comment ils ont été résolus)

### 1. Jar corrompu par un rebuild à chaud
**Symptôme :** `java.util.zip.ZipFile invalid LOC header (bad signature)` sur les ressources web.
**Cause :** `copy /Y` sur un jar tenu ouvert par une JVM en cours d'exécution.
**Résolu :** BUILD.bat tue les instances (PowerShell `Get-CimInstance` filtré sur `PixelPusherBridge`)
avant de compiler, et vérifie l'`errorlevel` des copies.

### 2. macOS : « rien ne se passe » au lancement
**Cause :** `/usr/bin/java` existe **toujours** sur macOS, même sans Java installé — c'est un
stub Apple qui affiche une erreur et sort en code 1. Le lanceur le prenait pour un Java valide.
**Résolu :** fonction `java_ok()` dans `packaging/macos/PixelPusherBridge` qui teste réellement
`"$c" -version >/dev/null 2>&1`. Et toute erreur produit désormais un dialogue AppleScript visible.

### 3. Instances multiples
**Symptôme :** LED erratiques ; log montrant « Port web 7350 indisponible, essai suivant… ».
**Cause :** plusieurs lancements successifs → plusieurs bridges poussant simultanément vers les pushers.
**Résolu :** `Main.detectRunningInstance()` scanne 7350→7360 via `/api/status` au démarrage ;
si une instance répond, on ouvre son interface et on quitte. Contourné en mode `--restart-delay`
(le redémarrage volontaire doit pouvoir remplacer l'instance).

### 4. Reed-Solomon du QR code
**Symptôme :** QR non décodables malgré une structure visuellement correcte.
**Cause :** le polynôme générateur est construit en ordre croissant mais consommé en ordre
décroissant par la division.
**Résolu :** inversion du tableau `gen` après construction (`Qr.rsEcc`). Validé contre le vecteur
de référence du standard : données v1-M "HELLO WORLD" → ECC `[196,35,39,119,235,215,231,226,93,23]`.

### 5. OpenCV comme juge de QR
`cv2.QRCodeDetector` échoue sur des QR **valides** (vérifié : le décodeur mathématique les lit
parfaitement, OpenCV renvoie une chaîne vide). Ne jamais s'en servir comme référence —
utiliser `tools/validate_qr.py`.

### 6. Fausses erreurs dans les logs
Le code legacy écrivait des messages informatifs sur `System.err` (« Building a new DeviceRegistry »,
« Starting a new instance of the discovery listener ») et `java.util.logging` déversait un message
par annonce de pusher (chaque seconde). Résolu : ces 2 lignes passées en `System.out`,
`java.util.logging` limité à WARNING vers stdout, `registry.setLogging()` lié au mode debug,
et `LogBus` ne compte plus les lignes de stack trace comme des erreurs distinctes.

### 7. Zip macOS depuis Windows — RÉSOLU

**Symptôme :** un zip créé sous Windows perd le bit exécutable du launcher, l'app ne
démarre pas, sans message.

**Diagnostic initial (incomplet) :** « il faut assembler sous Unix ». On a donc vécu des
mois avec un livrable macOS qui ne pouvait pas être produit sur la machine de
développement — et qui, en pratique, finissait toujours par dater d'une version
antérieure au jar.

**Vraie cause :** ce n'est pas le format zip, c'est l'outil. Le format stocke les
permissions Unix dans le champ *external attributes* de chaque entrée ; les outils
Windows le laissent simplement vide. Il faut aussi que `create_system` vaille 3 (Unix),
sans quoi macOS ignore le champ.

**Résolu :** `tools/make_livrables.py` écrit l'archive lui-même et pose les bits
explicitement (0755 dossiers / lanceur / `.command`, 0644 le reste), **puis relit
l'archive produite** et échoue si le lanceur n'y est pas exécutable. Il tourne sur
n'importe quel système. `packaging/make_mac_app.sh` reste utilisable sous Mac et Linux,
les deux produisent le même contenu.

**Leçon générale :** « il faut le faire sur l'autre système » mérite toujours d'être
questionné. Ici la contrainte était fausse, et elle coûtait un livrable périmé.

### 8. Avertissement « would increase delay, but autothrottle is disabled »
Ce n'est **pas** une erreur : le pusher signale qu'il reçoit plus vite qu'il ne peut suivre.
Solution utilisateur : activer l'auto-throttle dans Configuration.
**Fait depuis :** `LegacyMessages` traduit et reclasse ce message en WARN, et le `Diagnostic`
le détecte pour suggérer directement l'auto-throttle.

### 9. L'interface web qui ne démarre plus (Windows, 2026-07)

**Symptôme :** l'application tourne, les LED fonctionnent, mais la page est
injoignable. Le log dit « Aucun port web disponible entre 7350 et 7360 » alors que
`netstat` montre le processus **en écoute sur ces onze ports**.

**Cause :** `com.sun.net.httpserver` repose sur `java.nio.channels.Selector`, qui
ouvre une connexion en boucle locale pour son mécanisme de réveil. Quand un
pare-feu ou un antivirus la bloque, `Selector.open()` échoue avec « Unable to
establish loopback connection ». Or `HttpServer.create()` **réserve le port avant
d'échouer et ne le relâche jamais** : les onze tentatives condamnaient onze ports
pour la durée du processus. Pire, ces ports acceptent les connexions sans jamais
répondre, si bien que `detectRunningInstance` n'y voyait aucune instance et
laissait démarrer un doublon — la cause exacte du piège n°3.

**Résolu :** `WebServer.jdkServerUsable()` teste le sélecteur *avant* toute
réservation ; on ne parcourt les ports suivants que sur un vrai conflit ; et
`MiniHttpServer` (sockets bloquantes, expose l'API `HttpExchange` pour que les
handlers soient réutilisés à l'identique) prend le relais. En dernier recours, un
port libre quelconque : mieux vaut une interface sur un port inhabituel que pas
d'interface.

**À retenir :** les sockets bloquantes fonctionnaient parfaitement pendant que NIO
échouait. Ne jamais supposer qu'un échec réseau est global.

### 10. Un octet Java est signé

Trois défauts distincts de cet audit viennent tous de là : `sLinearExp[(int)intensity]`
(index négatif dès qu'un canal DMX dépasse 127), et deux `catch (NullPointerException)`
trop étroits qui laissaient remonter l'`ArrayIndexOutOfBoundsException` jusqu'à tuer
le thread de réception. **Sur ce projet, tout octet issu du réseau se masque avec
`& 0xff`.** `Pixel.setColorAntilog` le faisait déjà correctement : c'est le contraste
qui a permis de trouver le bug.

### 11. `DatagramPacket` réutilisé : toujours `setLength()`

`DatagramSocket.receive()` écrase la longueur du paquet avec la taille réellement
reçue, et cette longueur devient **la capacité maximale de la réception suivante**.
Sans réinitialisation, la capacité ne fait que décroître : un ArtPoll de 14 octets
condamnait ensuite toutes les trames DMX. `SacnReceiver` le faisait déjà, pas
`ArtNetReceiver` ni le socket de découverte.

### 12. Un blackout qui n'éteint pas

Mettre les pixels à zéro ne sert à rien tant que la source émet : la trame suivante
rallume tout 25 ms plus tard. Un blackout d'urgence doit **couper l'entrée**, pas
seulement l'affichage — c'est ce que fait la touche blackout d'une console. D'où
`Blackout.java` et son état verrouillé.

### 13. Ne jamais écrire dans une socket cliente depuis un thread temps réel

Les logs partaient directement dans les connexions SSE, depuis le thread appelant —
y compris celui qui reçoit l'Art-Net. Un navigateur qui cesse de lire (onglet en
veille, téléphone verrouillé) remplit le tampon TCP et **bloque l'écriture**, donc
la réception DMX. Règle générale : entre un producteur temps réel et un
consommateur réseau, il faut une file bornée et un thread dédié.

### 14. Le repli de compilation cassait la cible Java 11

`BUILD.bat` relançait `javac` **sans** `--release 11` dès que la première tentative échouait,
quelle qu'en soit la cause. Un jar en bytecode 21 pouvait ainsi partir chez un client : sur un
poste équipé d'un Java plus ancien, `UnsupportedClassVersionError` — invisible, puisque le
lanceur passe par `javaw` (pas de console) ou par le bundle `.app`.
**Résolu :** le repli n'est autorisé que si `build\javac_err.txt` mentionne réellement l'option
(`--release`, `release version`, `invalid target release`) ; toute autre erreur arrête le build.
Et surtout, la version majeure du bytecode produit est relue après coup (octets 6-7 de
`Main.class`, **55 = Java 11**) : une autre valeur fait échouer la compilation. Le script
vérifie aussi la copie de `web/*.html` (un jar sans interface passait pour un build réussi) et
signale une incohérence de version entre `AppConfig.VERSION`, `Info.plist` et `CHANGELOG.md`.

**Piège dans le piège :** un `exit /b 1` placé dans un bloc parenthésé **imbriqué** termine
bien le script, mais `cmd.exe` perd le code de retour — l'appelant reçoit 0. La première
version du correctif affichait donc l'erreur de compilation puis annonçait un succès à
`cmd /c BUILD.bat` (vérifié : code 0, aucun jar produit). Les sorties en erreur passent
désormais par `goto :erreur_compilation`, étiquette placée au premier niveau du script.
Règle : dans un `.bat`, **jamais de `exit /b` à plus d'un niveau de parenthèses**.

### 15. Le livrable macOS n'était pas reproductible

Le zip distribué contenait « Signer l'app (optionnel).command » que `make_mac_app.sh` ne copiait
pas : l'archive avait été complétée à la main, la release suivante l'aurait donc perdu.
**Résolu :** le script est la seule source de vérité — il contrôle la présence de chaque élément
*avant* d'assembler, embarque `LICENSE` (la licence MIT l'exige « in all copies »), et normalise
les droits (`chmod -R go+rX` + `755` sur le lanceur). Sans cette normalisation, l'umask de la
machine de build produisait une app en 0700 : parfaitement fonctionnelle pour le compte qui
avait décompressé l'archive, impossible à lancer depuis un autre compte du même Mac — cas
courant sur un poste de régie partagé.

### 16. macOS 15 : l'autorisation « Réseau local »

Depuis Sequoia, toute application qui émet en broadcast/multicast déclenche une demande
d'autorisation « Réseau local ». Un refus est **totalement silencieux côté Java** : les
datagrammes sont jetés, aucune exception, aucun log — plus aucun pusher découvert alors que
tout paraît normal. Le texte de la demande vient de `NSLocalNetworkUsageDescription`, désormais
présent dans `packaging/macos/Info.plist`. Si un Mac ne découvre rien : Réglages Système →
Confidentialité et sécurité → Réseau local. Le `Diagnostic` rappelle désormais ce point
de lui-même, mais uniquement sur macOS, quand aucun pusher n'est détecté.

### 17. La plage de ports web est un invariant partagé (`AppConfig.PORT_SCAN_RANGE`)

Deux boucles totalement indépendantes balaient la même plage de ports :

- `WebServer.bind()` cherche un port libre pour l'interface, à partir du port configuré ;
- `Main.detectRunningInstance()` cherche une instance déjà en marche, sur la même plage.

Elles **doivent** couvrir exactement `port → port + PORT_SCAN_RANGE`. Chacune écrivait sa
propre borne en dur (`base + 10`), et c'était une bombe à retardement : élargir la première
sans la seconde donne un bridge qui démarre sur un port que le verrou d'instance ne regarde
pas. Le second lancement ne voit alors **aucune** instance, démarre, et deux bridges poussent
simultanément vers les mêmes pushers — LED erratiques, sans le moindre message d'erreur.
C'est exactement le piège n°3, dans une variante indétectable à la lecture.

**Résolu :** la borne vit à un seul endroit, `AppConfig.PORT_SCAN_RANGE` (valeur 10), avec
l'invariant écrit en Javadoc juste au-dessus. Les deux boucles la lisent, ainsi que les
messages d'erreur (« Ports X à Y tous indisponibles ») qui affichaient auparavant une plage
recopiée à la main — donc susceptible de mentir.
**Règle :** une constante partagée par deux fichiers qui doivent rester d'accord ne se
duplique jamais, même quand la valeur « ne changera jamais ».

### 18. `MiniHttpServer` ferme la connexion dès que le handler rend la main

Le serveur du JDK et le serveur de secours n'ont pas le même cycle de vie, et un même
handler doit pourtant fonctionner sur les deux :

| | serveur du JDK | `MiniHttpServer` |
|---|---|---|
| après le `return` du handler | l'échange **survit** : on peut continuer à écrire depuis un autre thread | la socket est **fermée** (`handleOne` appelle `finish()` puis rend la connexion) |

Conséquence pour `/api/logs`, qui est un flux SSE volontairement infini : sur le serveur de
secours, rendre la main coupe le journal au bout d'une ligne — le navigateur se reconnecte
en boucle et l'onglet Logs clignote sans rien afficher. Il faut donc **tenir la ligne** dans
le handler (`client.awaitClose()`), et seulement là. Faire la même chose sur le serveur du
JDK serait un défaut symétrique : un thread du pool web immobilisé pour rien, par client
connecté au journal.

**Résolu :** `handleSse()` n'attend que si `miniServer != null`. C'est sans danger de ce
côté : le serveur de secours accepte 64 connexions et le nombre de clients du journal est
plafonné à 20. Le raisonnement est écrit sur place, dans les deux fichiers.
**Règle :** dès qu'un handler fait quelque chose d'inhabituel avec la durée de vie de
l'échange (flux continu, réponse différée), vérifier explicitement les **deux** serveurs.

## Validations effectuées

- **Mapping bout en bout** : faux pusher + émetteur Art-Net → couleurs reçues au pixel près.
- **Ordre des couleurs à chaud** : RGB → GRB vérifié sur les octets reçus (200,100,50 → 100,200,50).
- **Tests de lignes** : L1 seule, L2 seule, toutes — chaque fois le reste est bien noir.
- **Watchdog** : blackout après le délai configuré, réarmement au retour du signal.
- **Enregistreur** : 5 s à 40 Hz rejouées à l'identique, le direct est ignoré pendant la lecture
  puis rétabli ; aucune trame perdue avec l'écriture asynchrone.
- **Arrêt/redémarrage** : JVM terminée en 1 s, port libéré, blackout envoyé avant sortie ;
  redémarrage → nouvelle instance opérationnelle en ~5 s.
- **Verrou d'instance** : 2ᵉ lancement quitte proprement (code 0), la 1ʳᵉ reste intacte.
- **Calculateur DMX** : reproduit la logique réelle du mapping (170 px RGB/univers, passage
  d'univers quand il ne reste plus assez de canaux pour une LED entière).
- **QR** : 6 formes d'URL validées par décodeur indépendant.
- **Icône système** : vérifiée visuellement sur Windows (menu clic droit fonctionnel).
- **`build.sh`** : exécuté sur une copie complète du projet (41 sources hors `*Test.java`,
  bytecode relu à 55, jar contenant `web/index.html`, `web/mobile.html` et
  `META-INF/LICENSE`, `Main-Class` correct, contrôle de version 1.6.0 concordant).
  Chemin d'erreur « JDK absent » vérifié en vidant le `PATH` : message explicite, code 1.
  Le script écrit le jar dans `build/` puis le déplace : sous Unix le remplacement crée un
  nouvel inode, une instance en marche n'est donc pas corrompue (contrairement à Windows,
  où `BUILD.bat` doit arrêter les instances au préalable).

## Idées non implémentées (backlog)

**Priorité, issue de l'audit de juillet 2026 (voir `AUDIT.md`) :**

- **Licence du cœur réseau** — bloquant pour une vente. `src/com/heroicrobot/…`
  vient d'un dépôt publié *sans fichier de licence* : par défaut, tous droits
  réservés. Il faut une autorisation écrite de Jas Strong / Heroic Robotics avant
  toute commercialisation. Le dossier `reference/` (copie intégrale du projet
  d'origine) est volontairement exclu du dépôt public.
- **Authentification de l'interface** — le serveur écoute sur toutes les
  interfaces sans mot de passe : sur le réseau d'un lieu, n'importe qui peut
  déclencher un blackout. Piste retenue : jeton dans l'URL du QR code, et refus
  des requêtes non locales sans jeton. À faire avant une diffusion large.
  *Partiellement couvert :* l'arrêt et le redémarrage sont déjà réservés à
  l'ordinateur qui exécute le bridge (`WebServer.isLocalRequest`, qui répond
  « même ordinateur » et non « même réseau » — la boucle locale seule ne suffisait
  pas, l'interface étant très souvent ouverte par son adresse LAN). Le reste des
  commandes est volontairement ouvert : c'est ce qui rend l'accès téléphone par
  QR code utilisable sans configuration.
- Le détail des findings de l'audit reste dans `audit-findings.json` et
  `audit-reste.json`, avec pour chacun le correctif appliqué ou proposé.

**Fait depuis (ne pas rouvrir) :**

- **CSRF** — traité : les requêtes portant un en-tête `Origin` étranger sont
  refusées en 403 avec un message explicite dans les logs ; une page web ouverte
  à côté sur le poste de régie ne peut plus poster sur `/api/action`.
- **Écritures de fichiers atomiques** — configuration, presets et métadonnées de
  séquences passent tous par `.tmp` + `sync()` + `ATOMIC_MOVE`, avec repli sur un
  déplacement simple (plusieurs systèmes de fichiers signalent l'échec par une
  `FileSystemException` générique, pas par `AtomicMoveNotSupportedException` :
  rattraper `IOException` largement, sinon le repli ne se déclenche jamais).
  La configuration garde en plus une copie de secours de la dernière version
  saine, relue automatiquement si le fichier principal devient illisible.
- **Fusion des presets** — un preset complète la configuration au lieu de la
  remplacer : une clé absente du fichier garde sa valeur courante. Sans cela,
  tout réglage ajouté dans une version ultérieure du logiciel retombait à son
  défaut codé en dur au premier chargement de preset.
- **Options de ligne de commande non persistantes** — `--port` s'applique au
  lancement courant et n'est jamais écrit dans `config.properties` ni photographié
  dans un preset (`AppConfig.webPortOverride`). Règle générale : une option de
  dépannage ne modifie pas la configuration de l'utilisateur.
- **Watchdog et lecture de séquence** — pendant un scénario de test ou la lecture
  d'un enregistrement, les pixels sont alimentés par le bridge lui-même et
  `universeLastSeen` ne bouge plus : le watchdog est exempté, sans quoi il
  intercalait une trame noire en plein spectacle. La purge des tables du moniteur,
  elle, a lieu **avant** ce test d'exemption — et ne supprime jamais l'univers vu
  le plus récemment, sinon `lastDmxAt()` retomberait à zéro et le blackout de
  sécurité ne partirait plus jamais pour un délai réglé au-delà de 60 s.
- **Bornes du serveur web** — délai de requête de 20 s
  (`sun.net.httpserver.maxReqTime`, à poser **avant** le premier
  `HttpServer.create`), pool de threads borné, connexions simultanées plafonnées
  côté serveur de secours. Ne jamais poser `maxRspTime` : il couperait le flux de
  logs, volontairement infini.

**Autres idées :**

- Démarrage automatique au boot (explicitement **refusé** par l'utilisateur, ne pas ajouter).
- Limite de puissance électrique par pusher (le legacy a `setTotalPowerLimit`, non exposé).
- Multi-pusher : tout est prévu et testé côté code, mais jamais éprouvé avec 2 pushers réels.
- Signature Apple Developer + notarisation (nécessite un compte payant à 99 €/an).
- Fenêtre native embarquée (JavaFX WebView) pour se passer d'Edge/Chrome — gros surcoût,
  la solution `--app=` est jugée suffisante.

## Configuration de référence (validée)

Ce que la chaîne de développement suppose, sans identifier de machine particulière :

- **Compilation** avec un JDK installé : `BUILD.bat` sous Windows, `build.sh` sous macOS ou
  Linux — les deux produisent le même jar et relisent la version du bytecode (55 = Java 11).
  L'**empaquetage macOS se fait désormais depuis n'importe quel système** avec
  `tools/make_livrables.py` (voir piège n°7, résolu). Chaîne complète sur une seule
  machine : `VERIFIER-TOUT.bat` puis `python3 tools/make_livrables.py`.
- Cible de validation : **Mac Apple Silicon sous macOS 13 ou plus récent**, un contrôleur
  PixelPusher 8 lignes × 96 px en firmware 141, MadMapper comme source Art-Net.
- Sources également utilisées en exploitation : grandMA3, BEYOND, Resolume.

Les paramètres propres à une installation (adressage IP, nom des machines) n'ont pas leur
place ici : ce fichier part dans le dépôt public.
