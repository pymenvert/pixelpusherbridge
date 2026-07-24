#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Affiche ce que le faux PixelPusher a reçu — sert à vérifier qu'une couleur
envoyée en Art-Net arrive bien, et sur la bonne ligne.

Usage :
    python3 tools/check_leds.py            # instantané
    python3 tools/check_leds.py --watch    # rafraîchi toutes les secondes
"""
import json
import os
import sys
import time

STATS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pusher_stats.json")


def show():
    try:
        with open(STATS) as f:
            s = json.load(f)
    except (IOError, ValueError):
        print("Aucune donnee : lance d'abord tools/fake_pusher.py")
        return
    print("paquets recus : %-8d trames : %-8d lignes vues : %s"
          % (s["packets"], s["frames"], s["strips_seen"]))
    for sn in sorted(s["strip_px0"], key=int):
        rgb = s["strip_px0"][sn]
        print("  ligne %-2s  premier pixel RGB = %-16s %s"
              % (int(sn) + 1, rgb, "(eteint)" if rgb == [0, 0, 0] else ""))


if "--watch" in sys.argv:
    try:
        while True:
            print("\033[2J\033[H", end="")
            show()
            time.sleep(1)
    except KeyboardInterrupt:
        pass
else:
    show()
