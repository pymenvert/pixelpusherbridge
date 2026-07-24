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

- Reclasser l'avertissement d'auto-throttle et le remonter dans le diagnostic (voir §8).
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
