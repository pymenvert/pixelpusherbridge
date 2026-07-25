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
5. **macOS 15 (Sequoia) et plus récent** : au premier lancement, le système demande
   l'autorisation **« Réseau local »**. Il faut **accepter** — c'est elle qui permet
   de découvrir les PixelPushers.
6. Le navigateur s'ouvre sur l'interface : `http://localhost:7350`.

> **Aucun PixelPusher détecté sur un Mac ?** Vérifie l'autorisation *Réseau local* :
> **Réglages Système → Confidentialité et sécurité → Réseau local**, et active
> *PixelPusher Bridge* (ou *Java* / *Terminal* si tu l'as lancé en ligne de commande).
> Un refus est **totalement silencieux** : aucune erreur, aucun message, simplement
> plus aucun pusher trouvé alors que tout paraît normal. Le diagnostic intégré
> rappelle ce point quand il ne voit aucun pusher.

> Si macOS dit « l'app est endommagée » après un transfert par internet : Terminal →
> `xattr -cr "/Applications/PixelPusher Bridge.app"` puis relance.

### Windows

1. Ouvre `dist/PixelPusher Bridge (Windows)/`.
2. Double-clique `PixelPusher Bridge.bat`. (`Arreter PixelPusher Bridge.bat` force l'arrêt en cas de besoin.)

### Ce que contiennent les livrables

Chaque livrable embarque le texte de la **licence MIT**, comme celle-ci l'exige
(« in all copies ») :

| Livrable | Contenu |
|---|---|
| `PixelPusherBridge.jar` | l'application complète + `META-INF/LICENSE` |
| `PixelPusher Bridge (Windows)/` | le jar, les deux lanceurs `.bat`, `LICENSE` |
| `PixelPusher Bridge (macOS).zip` | l'app (lanceur, `Info.plist`, icône, jar, `LICENSE`), le script de signature optionnel et `LICENSE` à la racine de l'archive |

Ajoute `LISEZ-MOI.txt` (à la racine du projet) à côté des binaires que tu
distribues : c'est le pense-bête destiné à la personne qui reçoit le logiciel —
version, auteur, licence, adresse de l'interface, où trouver de l'aide.

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

La version 1.6 est issue d'un **audit complet** du logiciel : six défauts capables
d'interrompre une représentation ont été corrigés, dont un qui arrêtait la réception
Art-Net dès qu'une LED dépassait 50 % avec la correction gamma activée, et un autre
qui condamnait toutes les trames DMX après un simple paquet de découverte réseau.
Le rapport intégral est dans [AUDIT.md](AUDIT.md).

### Blackout d'urgence

Le bouton **Blackout** ne se contente pas d'éteindre : il **verrouille**. Tant qu'il
est actif, les données entrantes sont ignorées — sinon la trame suivante de la
console rallumerait tout 25 ms plus tard. Il faut cliquer sur **Reprendre** pour
rendre la main. Disponible dans l'en-tête, sur le téléphone et dans l'icône système.

### Limite de puissance électrique

Renseigne l'ampérage de ton alimentation dans *Configuration → Puissance électrique*.
Au-delà, le bridge atténue proportionnellement toutes les LED plutôt que de laisser
l'alimentation s'effondrer (chute de tension, couleurs qui virent, protection qui
coupe). Une jauge affiche la consommation réelle, annoncée par les PixelPushers
eux-mêmes.

### Tests automatisés

```
RUN-TESTS.bat
```

313 vérifications sans aucune dépendance : conversions de puissance, reclassement
des messages du cœur réseau, échappement JSON, filtrage des noms de fichiers,
serveur HTTP de secours de bout en bout, syntaxe des interfaces web, encodeur QR.

### Tout vérifier avant un spectacle ou une publication

```
VERIFIER-TOUT.bat
```

Enchaîne les trois étapes et s'arrête à la première qui échoue :

1. **compilation** (`BUILD.bat`) — jar régénéré, cible Java 11 contrôlée ;
2. **banc de tests** (`RUN-TESTS.bat`) — 313 vérifications + interfaces web + QR ;
3. **test de bout en bout** — un faux PixelPusher et une source Art-Net simulée
   vérifient la chaîne complète réseau → mapping → trames → LED
   (`tools/smoke_test.py`, nécessite Python 3 ; l'étape est ignorée avec un
   message si Python est absent).

Si cette commande finit en vert, l'ensemble a été vérifié sans le moindre
matériel. **Ne publie pas une version qui ne passe pas cette commande.**

## Recompiler après une modification

1. Arrête le bridge (bouton ⏹ ou le .bat d'arrêt) — il verrouille son jar.
2. Compile :
   - **Windows** : double-clique `BUILD.bat` ;
   - **macOS / Linux** : `./build.sh`.
   Les deux nécessitent un **JDK** (pas seulement un JRE) et produisent le même
   jar en bytecode Java 11, vérifié après coup.
3. Le jar est régénéré dans `dist/` et recopié dans le dossier Windows et l'app macOS.
4. **Mise à jour d'un Mac déjà installé** : remplace `PixelPusherBridge.jar` dans l'app (clic droit → *Afficher le contenu du paquet* → `Contents/Resources/`).
5. Zip macOS complet : `python3 tools/make_livrables.py` — **depuis n'importe quel
   système, Windows compris**. (`packaging/make_mac_app.sh` reste disponible pour
   qui préfère le faire sous Mac ou Linux.)

> **Chaîne de fabrication d'une release**, entièrement réalisable depuis une
> seule machine :
>
> ```
> VERIFIER-TOUT.bat                  compile, teste, valide la chaîne réseau → LED
> python3 tools/make_livrables.py      assemble le livrable macOS
> ```
>
> Le zip macOS devait autrefois être assemblé sous Unix : une archive fabriquée
> par un outil Windows perd le bit exécutable du lanceur et l'app ne démarre
> plus, sans message. Ce n'est pas le format zip qui est en cause mais l'outil —
> le format stocke bien les permissions Unix. `make_livrables.py` les écrit
> explicitement, **puis relit l'archive produite** et refuse de la valider si le
> lanceur n'y est pas exécutable.

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
   MiniHttpServer  Serveur HTTP de secours   Blackout     Blackout verrouillé
   Watchdog        Blackout auto si signal perdu
   LegacyMessages  Traduction des messages du cœur réseau
   Net             Adresses IPv4 du réseau local (QR code, accès téléphone)
   Names           Noms de fichiers sûrs     Json  Qr  Tray
web/index.html     Interface complète (un seul fichier, aucun framework)
web/mobile.html    Interface téléphone
build.sh           Compilation macOS / Linux (équivalent de BUILD.bat)
```

Pour ajouter une fonctionnalité : endpoint dans `WebServer`, logique dans une classe du package `bridge`, UI dans `index.html`. Ne jamais toucher au legacy — passer par `LegacyCore`.
