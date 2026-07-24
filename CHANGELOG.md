# Changelog

Toutes les évolutions notables de PixelPusher Bridge.

## [1.5.0] — 2026-07-08

### Ajouté
- **Icône de barre système** (Windows) / **barre de menus** (macOS) : point vert = bridge en marche, clic droit → ouvrir l'interface, blackout, redémarrer, arrêter. Double-clic = ouvrir l'interface.
- **Fenêtre d'application dédiée** : l'interface s'ouvre dans une fenêtre sans barre de navigateur (Edge/Chrome mode app) — fermer la fenêtre ne coupe PAS le bridge, qui continue en arrière-plan (icône verte visible).
- Le QR code d'accès téléphone s'affiche désormais dans une popup via le bouton « Afficher le QR code ».
- Fichiers LICENSE (MIT), CHANGELOG, .gitignore pour la publication GitHub.

## [1.4.1] — 2026-07-08

### Corrigé
- **Verrou d'instance unique** : lancer l'app alors qu'un bridge tourne déjà ouvre simplement son interface au lieu de créer un doublon (les instances multiples poussaient toutes vers les pushers → LED erratiques).
- BUILD.bat arrête lui-même les instances avant de compiler (un jar réécrit pendant qu'une JVM le tient était corrompu silencieusement) et signale les copies échouées.

## [1.4.0] — 2026-07-08

### Ajouté
- **QR code d'accès mobile** sur le tableau de bord (encodeur QR intégré, zéro dépendance, validé contre les vecteurs de référence du standard).
- **URL courte sans port** (http://IP/m) via un mini-serveur port 80 qui redirige.
- Branding « © 2026 Pierre Yves Mansour — Collectif WSK » + script « Signer l'app (optionnel).command » (macOS).

### Corrigé
- Lanceur macOS : `/usr/bin/java` factice d'Apple correctement rejeté (symptôme : « rien ne se passe » au lancement) ; dialogues d'erreur visibles.

## [1.3.0] — 2026-07-08

### Ajouté
- **Diagnostic complet en un clic** avec un conseil concret par problème + rapport texte téléchargeable.
- **Moniteur DMX en direct** (512 canaux par univers, visualisation temps réel).
- **Onglet Guide** pas-à-pas pour néophyte.
- Compteur d'erreurs sur le tableau de bord.

### Amélioré
- Écriture disque de l'enregistreur asynchrone (aucun impact possible sur le flux réseau).
- Logs internes de la librairie legacy nettoyés (plus de fausses erreurs ni de spam).

## [1.2.0] — 2026-07-08

### Ajouté
- **Presets** de configuration nommés (sauvegarde/chargement à chaud).
- **Enregistreur/lecteur de séquences** Art-Net (timing exact, lecture en boucle sans console).
- **Interface téléphone** dédiée (/m) : blackout, luminosité, tests rapides, presets, séquences.
- **Calculateur d'adressage DMX** (map complète par barre, fidèle au mapping réel, export CSV).

## [1.1.0] — 2026-07-08

### Ajouté
- Boutons **Arrêter / Redémarrer** (relance automatique) + **Blackout** d'urgence.
- **Watchdog de signal** : blackout auto si la source coupe (réarmement automatique).
- 10 scénarios de test dont **tests de lignes** (par sortie, par pusher, séquences).
- Reconnexion automatique des ports réseau occupés (retry 5 s).

## [1.0.0] — 2026-07-08

- Première version : cœur Art-Net/sACN → PixelPusher (robot-head/PixelPusher-artnet) + interface web (tableau de bord santé, configuration à chaud, logs temps réel, tests), packaging macOS (.app avec Java auto-téléchargé) et Windows.
