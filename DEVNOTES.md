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

### 7. Zip macOS depuis Windows
Un zip créé sous Windows perd le bit exécutable du launcher → l'app ne démarre pas.
Toujours utiliser `packaging/make_mac_app.sh` depuis macOS ou Linux (perms attendues : 0755/0711).

### 8. Avertissement « would increase delay, but autothrottle is disabled »
Ce n'est **pas** une erreur : le pusher signale qu'il reçoit plus vite qu'il ne peut suivre.
Solution utilisateur : activer l'auto-throttle dans Configuration. Amélioration possible :
reclasser ce message en WARN plutôt qu'en rouge/erreur, et le détecter dans `Diagnostic`
pour suggérer directement l'auto-throttle.

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
- **CSRF** — une page web ouverte sur le poste de régie peut poster sur
  `/api/action`. Piste : exiger un en-tête `X-Requested-With` (les formulaires
  HTML ne peuvent pas en envoyer) et vérifier l'origine.
- Les 20 findings majeurs restants et les 47 mineurs sont listés dans
  `audit-findings.json`, avec pour chacun un correctif proposé.

**Autres idées :**

- Démarrage automatique au boot (explicitement **refusé** par l'utilisateur, ne pas ajouter).
- Limite de puissance électrique par pusher (le legacy a `setTotalPowerLimit`, non exposé).
- Multi-pusher : tout est prévu et testé côté code, mais jamais éprouvé avec 2 pushers réels.
- Signature Apple Developer + notarisation (nécessite un compte payant à 99 €/an).
- Fenêtre native embarquée (JavaFX WebView) pour se passer d'Edge/Chrome — gros surcoût,
  la solution `--app=` est jugée suffisante.

## Environnement de l'auteur

- Machine de développement : **Windows** (le JDK y est installé, c'est là que `BUILD.bat` tourne).
- Machine cible principale : **MacBook M2 sous macOS 13.2** + un PixelPusher (8 lignes × 96 px,
  firmware 141, IP 192.168.0.230) et MadMapper comme source Art-Net.
- L'utilisateur travaille aussi avec grandMA3, BEYOND, Resolume.
