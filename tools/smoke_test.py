#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Test de bout en bout, sans matériel.

Démarre un faux PixelPusher, lance le bridge, envoie de l'Art-Net, puis vérifie
que les bonnes couleurs arrivent réellement sur les LED — c'est la seule preuve
qui compte. Contrôle aussi les fonctions critiques en spectacle : blackout
verrouillé, limiteur de puissance, endpoints de l'interface.

Contrairement au banc de tests Java (RUN-TESTS), qui vérifie des invariants de
code, celui-ci vérifie la chaîne complète : réseau → mapping → trames → LED.

Usage :
    python3 tools/smoke_test.py [chemin/vers/PixelPusherBridge.jar]

Sortie : 0 si tout est vert, 1 sinon.
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(RACINE, "dist", "PixelPusherBridge.jar")
STATS = os.path.join(RACINE, "tools", "pusher_stats.json")

echecs = []
processus = []


def ok(nom, condition, detail=""):
    if condition:
        print("   ok    %s" % nom)
    else:
        print("   ECHEC %s   %s" % (nom, detail))
        echecs.append(nom)


def lancer(args):
    p = subprocess.Popen(args, cwd=RACINE,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    processus.append(p)
    return p


def arreter_tout():
    for p in processus:
        try:
            p.terminate()
        except Exception:
            pass
    for p in processus:
        try:
            p.wait(timeout=5)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass


def api(port, chemin, donnees=None, timeout=8):
    url = "http://127.0.0.1:%d%s" % (port, chemin)
    if donnees is None:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    corps = urllib.parse.urlencode(donnees).encode("utf-8")
    req = urllib.request.Request(url, data=corps, headers={
        "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def trouver_port():
    for p in range(7350, 7361):
        try:
            api(p, "/api/status", timeout=3)
            return p
        except Exception:
            continue
    return None


def leds():
    """Couleur du premier pixel de chaque ligne, vue par le faux pusher."""
    try:
        with open(STATS) as f:
            s = json.load(f)
        return {int(k): v for k, v in s.get("strip_px0", {}).items()}
    except Exception:
        return {}


def main():
    print("=" * 55)
    print(" PixelPusher Bridge - test de bout en bout")
    print("=" * 55)

    if not os.path.isfile(JAR):
        print("Jar introuvable : %s\nLance BUILD.bat d'abord." % JAR)
        return 1

    if os.path.exists(STATS):
        os.remove(STATS)

    python = sys.executable
    print("\n-- Démarrage du faux PixelPusher et du bridge")
    lancer([python, os.path.join("tools", "fake_pusher.py"), "120", "2", "8"])
    time.sleep(2)
    lancer(["java", "-jar", JAR, "--no-browser"])
    time.sleep(9)

    port = trouver_port()
    ok("le bridge répond sur un port", port is not None,
       "aucune réponse entre 7350 et 7360")
    if port is None:
        return 1
    print("       (port %d)" % port)

    print("\n-- Découverte et réception")
    s = api(port, "/api/status")
    ok("le PixelPusher est découvert", len(s["pushers"]) >= 1)
    ok("le pusher est mappé en Art-Net", any(p["mapped"] for p in s["pushers"]))

    lancer([python, os.path.join("tools", "artnet_send.py"), "100", "200", "100", "50", "0"])
    time.sleep(5)

    s = api(port, "/api/status")
    ok("des paquets Art-Net sont reçus", float(s["artnetPps"]) > 5,
       "artnetPps=%s" % s["artnetPps"])
    ok("des trames partent vers les pushers", float(s["pushPps"]) > 5,
       "pushPps=%s" % s["pushPps"])
    ok("l'univers 1 est actif", any(u["universe"] == 1 for u in s["universes"]))

    print("\n-- Couleurs réellement reçues par les LED")
    c = leds()
    ok("la ligne 1 reçoit la couleur envoyée", c.get(0) == [200, 100, 50],
       "reçu %s au lieu de [200, 100, 50]" % c.get(0))

    print("\n-- Blackout verrouillé (la source continue d'émettre)")
    api(port, "/api/action", {"action": "blackout"})
    time.sleep(3)
    c = leds()
    ok("les LED sont éteintes", c.get(0) == [0, 0, 0], "reçu %s" % c.get(0))
    ok("elles le RESTENT malgré le flux entrant", c.get(1) == [0, 0, 0],
       "reçu %s" % c.get(1))
    ok("l'état est exposé dans le statut", api(port, "/api/status")["blackoutActive"] is True)

    print("\n-- Reprise")
    api(port, "/api/action", {"action": "resume"})
    time.sleep(3)
    c = leds()
    ok("les LED retrouvent la couleur de la source", c.get(0) == [200, 100, 50],
       "reçu %s" % c.get(0))
    ok("l'état est retombé", api(port, "/api/status")["blackoutActive"] is False)

    print("\n-- Limiteur de puissance")
    p = api(port, "/api/status")["power"]
    ok("la consommation est mesurée", p["units"] > 0, "units=%s" % p["units"])
    limite = round(float(p["amps"]) * 0.6, 2)
    api(port, "/api/config", {"powerLimitAmps": str(limite), "milliampsPerChannel": "20"})
    time.sleep(3)
    p = api(port, "/api/status")["power"]
    ok("le limiteur s'active", p["limiting"] is True)
    echelle = float(p["scale"])
    ok("il atténue au lieu d'éteindre", 0.05 < echelle < 0.99, "scale=%s" % echelle)
    c = leds()
    attendu = int(200 * echelle)
    ok("les LED sont atténuées, pas noires",
       c.get(0) and abs(c[0][0] - attendu) <= 2 and c[0][0] > 0,
       "reçu %s, attendu environ [%d, ...]" % (c.get(0), attendu))

    api(port, "/api/config", {"powerLimitAmps": "0"})
    time.sleep(3)
    c = leds()
    ok("le retrait de la limite rétablit la pleine intensité", c.get(0) == [200, 100, 50],
       "reçu %s" % c.get(0))

    print("\n-- Endpoints de l'interface")
    for chemin, attendu_type in [("/", "text/html"), ("/m", "text/html"),
                                 ("/qr.svg", "image/svg+xml")]:
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d%s" % (port, chemin),
                                        timeout=8) as r:
                ok("GET %-9s" % chemin,
                   r.status == 200 and attendu_type in r.headers.get("Content-Type", ""),
                   "%s / %s" % (r.status, r.headers.get("Content-Type")))
        except Exception as e:
            ok("GET %-9s" % chemin, False, str(e))

    for chemin in ["/api/config", "/api/diagnostic", "/api/presets",
                   "/api/recorder", "/api/dmx?u=1"]:
        try:
            api(port, chemin)
            ok("GET %-16s" % chemin, True)
        except Exception as e:
            ok("GET %-16s" % chemin, False, str(e))

    d = api(port, "/api/diagnostic")
    ok("le diagnostic ne signale aucune erreur", d["errors"] == 0,
       "%d erreur(s)" % d["errors"])

    print("\n-- Journal")
    s = api(port, "/api/status")
    ok("aucune erreur dans les logs", s["errorsTotal"] == 0,
       "%d erreur(s), dernière : %s" % (s["errorsTotal"], s["lastError"][:80]))

    print()
    print("=" * 55)
    if echecs:
        print(" %d ECHEC(S) :" % len(echecs))
        for e in echecs:
            print("   - " + e)
        print("=" * 55)
        return 1
    print(" Chaîne complète validée : réseau -> mapping -> trames -> LED.")
    print("=" * 55)
    return 0


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    finally:
        arreter_tout()
    sys.exit(code)
