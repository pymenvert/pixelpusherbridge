#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Émetteur Art-Net de test — simule un logiciel lumière.

Envoie des paquets ArtDmx à ~40 Hz vers 127.0.0.1:6454 (comme MadMapper ou une
console). Le premier pixel prend la couleur demandée, le reste est une rampe.

ATTENTION : l'univers Art-Net 0 côté source correspond à l'univers 1 côté bridge.

Usage :
    python3 tools/artnet_send.py [duree_s] [R] [G] [B] [univers_artnet]

Exemples :
    python3 tools/artnet_send.py 10 255 0 0      # rouge, 10 s, univers 0 (=1 dans le bridge)
    python3 tools/artnet_send.py 5 0 0 255 1     # bleu sur l'univers Art-Net 1 (=2)
"""
import socket
import struct
import time
import sys

dur = float(sys.argv[1]) if len(sys.argv) > 1 else 5.0
r, g, b = (int(x) for x in (sys.argv[2:5] if len(sys.argv) > 4 else (255, 128, 64)))
universe = int(sys.argv[5]) if len(sys.argv) > 5 else 0

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
data = bytearray(512)
data[0], data[1], data[2] = r, g, b
for i in range(3, 48):
    data[i] = i

pkt = (b"Art-Net\x00"
       + bytes([0x00, 0x50])            # OpCode ArtDmx
       + bytes([0, 14])                 # version
       + bytes([0, 0])                  # sequence, physical
       + struct.pack('<H', universe)    # univers (little-endian)
       + struct.pack('>H', 512)         # longueur (big-endian)
       + bytes(data))

t_end = time.time() + dur
n = 0
print("Envoi Art-Net : univers %d (= univers %d dans le bridge), RGB(%d,%d,%d), %.1f s"
      % (universe, universe + 1, r, g, b, dur))
while time.time() < t_end:
    s.sendto(pkt, ("127.0.0.1", 6454))
    n += 1
    time.sleep(0.025)
print("%d paquets ArtDmx envoyes (~%d Hz)" % (n, n / dur))
