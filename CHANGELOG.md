# Changelog

Toutes les évolutions notables de PixelPusher Bridge.

## [1.6.0] — 2026-07-25

Version de fiabilité. Un audit complet du logiciel a mis au jour six défauts
capables d'interrompre une représentation ; ils sont tous corrigés et vérifiés
sur banc de test.

### Corrigé — défauts pouvant gâcher un spectacle

- **La courbe anti-log tuait la réception Art-Net.** La table de correction était
  indexée par un octet signé : dès qu'un canal DMX dépassait 127, l'index devenait
  négatif et l'exception, non rattrapée, arrêtait définitivement le thread de
  réception. Cocher l'option et monter une LED au-dessus de 50 % suffisait à figer
  toutes les LED, sans le moindre message.
- **Un seul petit paquet réseau coupait l'Art-Net pour de bon.** Le tampon de
  réception n'était jamais réinitialisé : sa capacité ne faisait que décroître.
  Un ArtPoll de 14 octets, émis par n'importe quel node du réseau, condamnait
  toutes les trames DMX suivantes.
- **Un datagramme inconnu sur le port de découverte gelait toute l'application.**
  Un verrou interne n'était pas relâché sur une sortie anticipée. Blackout,
  watchdog, arrêt propre et interface se bloquaient alors définitivement.
- **Le blackout d'urgence ne coupait rien.** Éteindre les pixels ne servait à rien
  tant que la console continuait d'émettre : la trame suivante rallumait tout
  25 ms plus tard. Le blackout est désormais un **état verrouillé**, comme sur une
  console : les données entrantes sont ignorées jusqu'à une reprise explicite.
- **Un navigateur endormi pouvait bloquer le flux LED.** Les logs étaient écrits
  directement dans les connexions des pages ouvertes, depuis le thread qui reçoit
  l'Art-Net. Un onglet en veille ou un téléphone verrouillé suffisait à le figer.
- **L'interface web pouvait ne pas démarrer du tout**, sans explication, quand un
  pare-feu ou un antivirus bloque les connexions en boucle locale dont Java a
  besoin. Un serveur HTTP de secours prend maintenant le relais automatiquement.
- **sACN** : la longueur des paquets n'était pas vérifiée. Un paquet court
  injectait dans les LED les restes du paquet précédent, et les paquets de
  synchronisation étaient joués comme des données d'éclairage.
- **Perte de réglages** : si la configuration ne se chargeait pas, un clic sur
  Enregistrer écrasait tout par des valeurs vides. L'enregistrement est maintenant
  bloqué tant que la lecture n'a pas abouti.
- Une configuration illisible empêchait le démarrage sans message ; le fichier est
  désormais mis de côté et l'application repart sur ses valeurs par défaut.
- La lecture en boucle d'une séquence vide saturait un cœur du processeur.
- Une erreur disque en cours d'enregistrement laissait le fichier ouvert et
  l'interface affichait « aucun enregistrement » comme si tout allait bien.
- Le lanceur Windows vérifiait la présence de Java, pas qu'il fonctionne : l'échec
  était totalement silencieux. Il teste maintenant réellement la version et
  journalise dans `~/.pixelpusherbridge/launcher.log`.
- **Le watchdog éteignait tout en pleine séquence.** Pendant la lecture d'un
  enregistrement, les pixels sont alimentés par le bridge lui-même : plus rien
  n'arrive du réseau. Le watchdog y voyait une perte de signal et intercalait une
  trame noire devant public. Il est désormais mis en veille pendant une lecture
  comme pendant un scénario de test.
- **Charger un preset effaçait des réglages.** Le preset remplaçait la
  configuration au lieu de la compléter : tout réglage absent du fichier —
  typiquement un réglage apparu dans une version plus récente — retombait
  silencieusement à sa valeur d'usine. Un preset enregistré avant l'arrivée du
  watchdog remettait ainsi le blackout automatique à zéro sans rien dire. Le
  chargement fusionne maintenant, et les réglages conservés sont journalisés.
- **Une coupure pendant l'enregistrement de la configuration pouvait tout
  perdre.** Le fichier était écrit en place : une extinction brutale, un disque
  plein ou une clé retirée au mauvais moment laissaient un fichier tronqué. La
  sauvegarde passe désormais par un fichier temporaire remplacé d'un bloc, avec
  une copie de secours de la dernière version saine — reprise automatique au
  démarrage suivant si le fichier principal est illisible. Même traitement pour
  les presets et pour les informations des séquences enregistrées.
- **L'option de dépannage `--port` devenait définitive.** Le port forcé en ligne
  de commande était écrit dans la configuration dès la première sauvegarde faite
  depuis l'interface : tous les raccourcis de l'équipe pointaient ensuite vers le
  mauvais port. Il ne vaut plus que pour le lancement en cours.
- **Une page web quelconque ouverte sur le poste de régie pouvait commander le
  bridge.** Les commandes venant d'une autre page sont maintenant refusées, avec
  un message qui l'explique. Et **Arrêter / Redémarrer sont réservés à
  l'ordinateur qui exécute le bridge** : ce sont les seules commandes dont on ne
  revient pas à distance — une fois la fenêtre fermée, il faut retourner
  physiquement à la machine. Le téléphone ne les propose pas.
- **Une connexion laissée en suspens immobilisait l'interface.** Un client qui
  ouvrait la connexion sans jamais terminer sa requête monopolisait un thread web
  pour toujours ; quelques-uns suffisaient à rendre la page injoignable alors que
  les LED tournaient. Délai de requête de 20 s, nombre de threads et de
  connexions simultanées désormais bornés. Le flux de logs, lui, reste
  volontairement sans limite de durée.

### Ajouté

- **Limite de puissance électrique**, en ampères. Au-delà, toutes les LED sont
  atténuées proportionnellement pour protéger l'alimentation, au lieu de la
  laisser s'effondrer. Jauge de consommation en temps réel sur le tableau de bord
  et dans la configuration. *(Le calcul interne était cassé par une division
  entière : la limite éteignait tout au lieu d'atténuer.)*
- **Messages du cœur réseau traduits et reclassés.** Ce qui n'était pas une erreur
  n'est plus compté comme telle, et chaque message porte un conseil concret.
  L'avertissement d'auto-throttle, les écritures hors ruban, les paquets malformés
  et les firmwares trop anciens remontent désormais dans le diagnostic.
- **Banc de tests automatisés** (`RUN-TESTS.bat`) : 313 vérifications, zéro
  dépendance. Contrôle aussi la syntaxe des interfaces web et l'encodeur QR.
  `VERIFIER-TOUT.bat` y ajoute un test de bout en bout qui vérifie les couleurs
  réellement reçues par les LED, sans le moindre matériel.
- **Le livrable macOS s'assemble maintenant depuis n'importe quel système**
  (`tools/make_livrables.py`), Windows compris. Il devait auparavant être fabriqué
  sur un Mac ou sous Linux, faute de quoi le lanceur perdait son bit exécutable
  et l'app ne démarrait pas — si bien qu'en pratique le zip livré datait toujours
  d'une version antérieure au reste. Le script écrit les permissions Unix
  lui-même, puis relit l'archive et refuse de la valider si le lanceur n'y est
  pas exécutable.
- **Le PDF de présentation est désormais généré** (`tools/make_pdf.py`) et lit le
  numéro de version dans le code : il ne peut plus décrire une version périmée.
- **Vérification complète en une commande** (`VERIFIER-TOUT.bat`) : compilation,
  banc de tests, puis test de bout en bout réseau → mapping → trames → LED avec
  un faux PixelPusher. Si elle finit en vert, la chaîne entière a été vérifiée
  sans le moindre matériel. À lancer avant un spectacle ou une publication.
- **Nouvelles vérifications du diagnostic**, chacune avec son conseil :
  - port Art-Net ouvert mais **silencieux depuis plus de 30 s** — le plus souvent
    un autre logiciel de la machine (MadMapper, Resolume, un autre node, une
    instance du bridge restée ouverte) a pris le port 6454 en premier et capte
    les paquets à la place du bridge ;
  - **sACN silencieux depuis plus d'une minute** alors que les groupes multicast
    sont rejoints — le thread vivant ne prouvait rien, il reste bloqué en
    attente même quand plus une trame n'arrive (piège classique : un switch qui
    filtre le multicast sans routeur pour l'entretenir) ;
  - aucun PixelPusher détecté **sur macOS** : rappel de l'autorisation *Réglages
    Système → Confidentialité et sécurité → Réseau local*, dont le refus est
    totalement silencieux.
- **`build.sh`** : compilation depuis macOS et Linux, équivalent de `BUILD.bat`
  (même cible Java 11, même contrôle du bytecode produit).
- **`LISEZ-MOI.txt`**, à joindre aux binaires distribués : version, auteur,
  licence, adresse de l'interface, autorisation « Réseau local » de macOS, où
  trouver de l'aide.

### Amélioré

- Interface : contrastes relevés pour la lecture en pénombre de régie, navigation
  au clavier avec indicateur de focus visible, respect des préférences système de
  réduction du mouvement, courbes nettes sur écrans haute densité, vocabulaire
  homogène. Le tableau de bord se grise quand le bridge ne répond plus, au lieu
  d'afficher des chiffres périmés comme s'ils étaient vivants.
- Interface téléphone : zoom débloqué, cibles tactiles portées à 44 px.
- Les messages de succès ne s'affichent plus quand le bridge a refusé la demande.
- Tableau de bord : le voyant Art-Net distingue maintenant « port occupé, nouvel
  essai automatique » de « le thread de réception s'est arrêté, il faut
  redémarrer » — deux pannes très différentes qui affichaient le même message.
  Chaque PixelPusher indique l'univers qu'il reçoit réellement, calculé par le
  bridge et non plus deviné par la page (le calcul côté navigateur ignorait le
  mode de compactage et les rubans RGBOW). Version et licence rappelées en pied
  de page.
- Interface téléphone : chaque échec réseau dit désormais ce qui n'a **pas** été
  appliqué (« Bridge injoignable — luminosité non appliquée », « blackout NON
  appliqué »…) au lieu d'échouer en silence. Le curseur de luminosité n'affiche
  plus 100 % avant d'avoir lu la vraie valeur, et la page se répare toute seule
  au retour du bridge, sans rechargement.
- Une modification faite depuis un autre poste, ou un preset chargé depuis le
  téléphone, est reflétée immédiatement par l'interface au lieu d'attendre
  quelques secondes — plus de risque de renvoyer un réglage périmé.
- Le moniteur DMX oublie les univers devenus silencieux depuis plus d'une minute,
  au lieu de les accumuler pour la durée de la session ; et le tableau de bord
  cesse d'annoncer « aucune donnée DMX » pendant la lecture d'une séquence.

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
