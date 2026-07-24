# Dossier de référence (lecture seule)

Ce dossier n'est **pas compilé** — il sert uniquement de référence pour comprendre
l'origine du projet et le matériel cible.

## `PixelPusher-artnet-original/`

Sources du projet d'origine [robot-head/PixelPusher-artnet](https://github.com/robot-head/PixelPusher-artnet)
(Heroic Robotics / Jas Strong), telles qu'elles ont été récupérées avant modification.

Utile pour **comparer** avec `../src/com/heroicrobot/` et voir exactement ce qui a été
touché (uniquement des hooks marqués `// … PixelPusherBridge`). Commande utile :

```bash
diff -r reference/PixelPusher-artnet-original/src/com/heroicrobot src/com/heroicrobot
```

Le projet d'origine n'a pas de fichier de licence — voir la note dans `../LICENSE`.

## `pixel.rc.exemple`

Exemple réel de fichier de configuration d'un PixelPusher (il se trouve sur la carte SD
du contrôleur). C'est lui qui détermine le nombre de lignes, de pixels, le type de LED
et surtout **`artnet_universe` / `artnet_channel`** : s'ils valent 0, le pusher n'est pas
mappé et aucune LED ne s'allume — cause n°1 des problèmes utilisateur.

Champs importants :

| Champ | Rôle |
|---|---|
| `strips` / `stripsattached` | nombre de sorties (lignes) |
| `pixels` | nombre de LED par ligne |
| `artnet_universe` | univers Art-Net de départ (≥ 1 !) |
| `artnet_channel` | canal de départ dans cet univers (≥ 1 !) |
| `stripN` / `orderN` | type de ruban et ordre des couleurs par sortie |
