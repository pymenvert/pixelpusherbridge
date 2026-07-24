# PixelPusher Bridge

Pont Art-Net / sACN vers PixelPusher, avec interface web complète : configuration guidée, tests intégrés, enregistreur de séquences, presets, diagnostic automatique et accès téléphone par QR code. Basé sur le bridge éprouvé [PixelPusher-artnet](https://github.com/robot-head/PixelPusher-artnet) — le cœur réseau d'origine n'a presque pas été modifié, tout le nouveau code est construit autour.

L'app vit comme un vrai logiciel : **icône de barre système** (point vert = bridge en marche, clic droit → ouvrir l'interface / blackout / redémarrer / arrêter) et interface dans une **fenêtre d'application dédiée** — fermer la fenêtre ne coupe pas le bridge, qui continue en arrière-plan.

**Licence :** MIT pour le code du bridge (voir [LICENSE](LICENSE)) · cœur réseau crédité à Heroic Robotics / robot-head · © 2026 Pierre Yves Mansour — Collectif WSK · [CHANGELOG](CHANGELOG.md)

## Installation

### macOS (Apple Silicon et Intel)

1. Dézippe `dist/PixelPusher Bridge (macOS).zip` (double-clic).
2. Glisse `PixelPusher Bridge.app` dans **Applications** (optionnel).
3. Premier lancement : **clic droit sur l'app → Ouvrir → Ouvrir** (app non signée Apple, confirmation une seule fois).
4. Si Java n'est pas installé, l'app propose de le télécharger automatiquement (~45 Mo, une seule fois).
5. Le navigateur s'ouvre sur l'interface : `http://localhost:7350`.

> Si macOS dit « l'app est endommagée » après un transfert par internet : Terminal →
> `xattr -cr "/Applications/PixelPusher Bridge.app"` puis relance.

### Windows

1. Ouvre `dist/PixelPusher Bridge (Windows)/`.
2. Double-clique `PixelPusher Bridge.bat`. (`Arreter PixelPusher Bridge.bat` force l'arrêt en cas de besoin.)

## L'interface

- **📚 Guide** — onglet pas-à-pas pour débutant : branchement, vérification, tests, configuration du logiciel lumière, mode spectacle, problèmes courants.
- **Tableau de bord** — santé du système (« est-ce que tout marche ? »), **diagnostic complet en un clic** (chaque problème détecté vient avec un conseil concret + rapport téléchargeable pour se faire aider), débits avec courbes, univers actifs, **moniteur DMX en direct** (valeurs canal par canal), liste des PixelPushers, compteur d'erreurs.
- **Tests** — 10 scénarios (blanc, cycle RVB, dégradé, arc-en-ciel, chenillard, 1 couleur/ligne, séquences lignes/pushers, noir) + **test de lignes manuel** : un bouton par sortie de chaque pusher.
- **Séquences** — enregistre ce que ta console envoie (tous univers, timing exact), rejoue-le à l'identique ou en boucle sans console. Écriture disque asynchrone : n'affecte jamais la fluidité.
- **Adressage DMX** — calculateur : type de LED, LED/barre, barres/ligne, adresses de départ → map complète par barre (univers/adresse début-fin), fidèle au mapping réel du bridge, export CSV.
- **Configuration** — chaque réglage expliqué avec fourchette conseillée. **Presets** nommés (photo complète de la config, rechargeable en un clic). Watchdog de signal (blackout auto si la source coupe), blackout à l'arrêt, luminosité globale, ordre des couleurs à chaud…
- **Logs** — temps réel, filtres, recherche, téléchargement ; fichier persistant `~/.pixelpusherbridge/bridge.log`.
- **En-tête** — Blackout / Redémarrer / Arrêter, partout.
- **📱 Téléphone** — ouvre l'adresse affichée sur le Tableau de bord : interface tactile simplifiée (blackout, luminosité, tests rapides, presets, séquences).

Données : `~/.pixelpusherbridge/` (config, presets, enregistrements, logs) — survit aux mises à jour.

## Fluidité & robustesse

Défauts pensés pour un flux sans saccade (auto-throttle off, délai 0 ms, 85 Hz). L'interface, l'enregistreur et le watchdog tournent dans des threads séparés : **aucun impact sur le flux Art-Net → LED**. Ports réseau retentés toutes les 5 s s'ils sont occupés, exceptions capturées dans les logs, limites anti-fuite, arrêt propre avec blackout.

## Recompiler après une modification

1. Arrête le bridge (bouton ⏹ ou le .bat d'arrêt) — il verrouille son jar.
2. Double-clique `BUILD.bat` (nécessite un JDK).
3. Le jar est régénéré dans `dist/` et copié dans le dossier Windows et l'app macOS.
4. **Mise à jour d'un Mac déjà installé** : remplace `PixelPusherBridge.jar` dans l'app (clic droit → *Afficher le contenu du paquet* → `Contents/Resources/`).
5. Zip macOS complet : `packaging/make_mac_app.sh` (sur Mac ou Linux — pas depuis Windows, le zip perdrait le bit exécutable).

## Architecture (pour faire évoluer l'app)

```
src/com/heroicrobot/…              Code legacy d'origine (quasi intact, + hooks de mesure)
   pixelpusher/artnet/LegacyCore   Façade : seul point de contact avec le legacy
src/com/pixelpusher/bridge/
   Main            Démarrage, arrêt/redémarrage propre
   AppConfig       Config persistante        Presets      Presets nommés
   LogBus          Logs + fichier + SSE      Recorder     Enregistreur/lecteur de séquences
   StatusService   Snapshot JSON             Diagnostic   Vérifications + rapport
   WebServer       Serveur HTTP embarqué     TestPatterns Scénarios de test
   Watchdog        Blackout auto si signal perdu
web/index.html     Interface complète (un seul fichier, aucun framework)
web/mobile.html    Interface téléphone
```

Pour ajouter une fonctionnalité : endpoint dans `WebServer`, logique dans une classe du package `bridge`, UI dans `index.html`. Ne jamais toucher au legacy — passer par `LegacyCore`.
