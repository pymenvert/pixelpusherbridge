#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Faux PixelPusher — permet de tester le bridge sans matériel.

S'annonce sur le port 7331 (comme un vrai pusher) et reçoit les trames sur le
port 9897. Écrit en continu ce qu'il reçoit dans tools/pusher_stats.json, que
l'on peut lire avec check_leds.py.

Usage :
    python3 tools/fake_pusher.py [duree_secondes] [nb_lignes] [pixels_par_ligne]

Exemple :
    python3 tools/fake_pusher.py 60 8 96     # 8 lignes de 96 pixels, 60 s
"""
import socket
import struct
import threading
import time
import json
import os
import sys

DUREE = int(sys.argv[1]) if len(sys.argv) > 1 else 60
STRIPS = int(sys.argv[2]) if len(sys.argv) > 2 else 2
PPS = int(sys.argv[3]) if len(sys.argv) > 3 else 8
MAXSPP = min(4, STRIPS)

MAC = bytes([0x00, 0x11, 0x22, 0x33, 0x44, 0x55])
IP = bytes([127, 0, 0, 1])
STATS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pusher_stats.json")


def build_announce():
    """Paquet d'annonce PixelPusher (little-endian, deviceType=2)."""
    h = (MAC + IP + bytes([2, 1])
         + struct.pack('<HHHH', 1, 2, 1, 121)      # vendor, product, hwRev, swRev
         + struct.pack('<I', 100000000))            # linkSpeed
    r = bytes([STRIPS, MAXSPP]) + struct.pack('<H', PPS)
    r += struct.pack('<III', 16666, 2000, 0)        # updatePeriod(us), powerTotal, deltaSeq
    r += struct.pack('<II', 1, 1)                   # controllerOrdinal, groupOrdinal
    r += struct.pack('<HH', 1, 1)                   # artnet universe=1, channel=1
    r += struct.pack('<H', 9897) + b'\x00\x00'      # port + padding
    r += bytes(max(8, STRIPS))                      # stripFlags
    r += b'\x00\x00' + struct.pack('<III', 0, 0, 0) # pusherFlags, segments, powerDomain
    return h + r


stats = {
    "packets": 0, "frames": 0, "strips_seen": [],
    "strip_px0": {},          # {numero_ligne: [r, g, b]} premier pixel de chaque ligne
    "last_seq": 0, "started": time.time(),
}


def announcer():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    pkt = build_announce()
    while True:
        s.sendto(pkt, ("127.0.0.1", 7331))
        time.sleep(1.0)


def receiver():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 9897))
    seen = set()
    while True:
        data, _ = s.recvfrom(4096)
        stats["packets"] += 1
        if len(data) < 5:
            continue
        stats["last_seq"] = struct.unpack('<I', data[0:4])[0]
        off = 4
        while off + 1 + PPS * 3 <= len(data):
            sn = data[off]
            off += 1
            px = data[off:off + PPS * 3]
            off += PPS * 3
            seen.add(sn)
            stats["strip_px0"][str(sn)] = list(px[0:3])
            if sn == 0:
                stats["frames"] += 1
        stats["strips_seen"] = sorted(seen)


def dumper():
    while True:
        try:
            with open(STATS_PATH, "w") as f:
                json.dump(stats, f)
        except IOError:
            pass
        time.sleep(0.5)


print("Faux PixelPusher : %d ligne(s) x %d pixels, %d s" % (STRIPS, PPS, DUREE))
print("  annonce sur 127.0.0.1:7331, reception sur :9897")
print("  etat ecrit dans %s" % STATS_PATH)
for fn in (announcer, receiver, dumper):
    threading.Thread(target=fn, daemon=True).start()
try:
    time.sleep(DUREE)
except KeyboardInterrupt:
    pass
print("Termine. %d paquets recus, %d trames." % (stats["packets"], stats["frames"]))
