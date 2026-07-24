#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Valide l'encodeur QR de Qr.java avec un décodeur écrit indépendamment depuis la
spécification ISO/IEC 18004 (format info → démasquage → zigzag → syndromes
Reed-Solomon → payload). Aucune ligne partagée avec l'encodeur : si le décodage
réussit, la matrice est réellement conforme.

NB : ne pas utiliser OpenCV QRCodeDetector comme juge — il échoue sur des QR
parfaitement valides (constaté pendant le développement).

Usage :
    python3 tools/validate_qr.py dist/PixelPusherBridge.jar
"""
import subprocess
import sys

JAR = sys.argv[1] if len(sys.argv) > 1 else "dist/PixelPusherBridge.jar"

# --- GF(256), polynome 0x11D
EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]

NDATA = {21: 19, 25: 34, 29: 55, 33: 80, 37: 108}
NECC = {21: 7, 25: 10, 29: 15, 33: 20, 37: 26}
ALIGN = {21: [], 25: [6, 18], 29: [6, 22], 33: [6, 26], 37: [6, 30]}


def decode(lines):
    """Décode une matrice QR (liste de chaînes '0'/'1'). Retourne (texte, info)."""
    size = len(lines)
    if size not in NDATA:
        return None, "taille inattendue %d" % size
    g = [[1 if ch == '1' else 0 for ch in row] for row in lines]

    # format info : copie du bord (indépendante de la copie principale)
    fbits = [g[size - 1 - i][8] for i in range(7)] + [g[8][size - 8 + i] for i in range(8)]
    fmt = 0
    for b in fbits:
        fmt = (fmt << 1) | b
    fmt ^= 0b101010000010010
    v, gp = fmt, 0b10100110111
    for i in range(14, 9, -1):
        if (v >> i) & 1:
            v ^= gp << (i - 10)
    if v & 0x3FF:
        return None, "BCH du format invalide"
    data5 = fmt >> 10
    ec, mask = data5 >> 3, data5 & 7
    if ec != 0b01:
        return None, "niveau de correction != L"

    # zones fonction
    func = [[False] * size for _ in range(size)]

    def mark(r0, c0, h, w):
        for r in range(r0, r0 + h):
            for c in range(c0, c0 + w):
                if 0 <= r < size and 0 <= c < size:
                    func[r][c] = True

    mark(0, 0, 9, 9)
    mark(0, size - 8, 9, 8)
    mark(size - 8, 0, 8, 9)
    for i in range(size):
        func[6][i] = func[i][6] = True
    for r in ALIGN[size]:
        for c in ALIGN[size]:
            if (r < 9 and c < 9) or (r < 9 and c > size - 10) or (r > size - 10 and c < 9):
                continue
            mark(r - 2, c - 2, 5, 5)

    def unmask(r, c):
        m = [(r + c) % 2 == 0, r % 2 == 0, c % 3 == 0, (r + c) % 3 == 0,
             (r // 2 + c // 3) % 2 == 0, (r * c) % 2 + (r * c) % 3 == 0,
             ((r * c) % 2 + (r * c) % 3) % 2 == 0,
             ((r + c) % 2 + (r * c) % 3) % 2 == 0][mask]
        return g[r][c] ^ (1 if m else 0)

    bits = []
    col, up = size - 1, True
    while col > 0:
        if col == 6:
            col -= 1
        for r in (range(size - 1, -1, -1) if up else range(size)):
            for c in (col, col - 1):
                if not func[r][c]:
                    bits.append(unmask(r, c))
        up = not up
        col -= 2

    cw = []
    for i in range(0, len(bits) // 8 * 8, 8):
        val = 0
        for b in bits[i:i + 8]:
            val = (val << 1) | b
        cw.append(val)
    cw = cw[:NDATA[size] + NECC[size]]

    # syndromes RS : tous nuls si le mot de code est valide
    for j in range(NECC[size]):
        s = 0
        for c in cw:
            s = (EXP[(LOG[s] + j) % 255] if s else 0) ^ c
        if s:
            return None, "syndrome RS %d non nul" % j

    bs = []
    for c in cw[:NDATA[size]]:
        for i in range(7, -1, -1):
            bs.append((c >> i) & 1)
    mode = 0
    for b in bs[:4]:
        mode = (mode << 1) | b
    if mode != 0b0100:
        return None, "mode != octets"
    ln = 0
    for b in bs[4:12]:
        ln = (ln << 1) | b
    out = bytearray()
    for k in range(ln):
        val = 0
        for b in bs[12 + k * 8:20 + k * 8]:
            val = (val << 1) | b
        out.append(val)
    return out.decode('utf-8'), "masque %d, version %d" % (mask, (size - 17) // 4)


TESTS = [
    "http://192.168.1.10/m",
    "http://172.22.144.1:7350/m",
    "http://10.0.0.254:7360/m",
    "http://192.168.100.100:7355/m",
    "HELLO WSK",
    "x" * 100,
]

ok = True
for t in TESTS:
    out = subprocess.run(["java", "-cp", JAR, "com.pixelpusher.bridge.Qr", t],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print("%-34s ECHEC java : %s" % (t[:34], out.stderr.strip()[:60]))
        ok = False
        continue
    val, info = decode(out.stdout.strip().split("\n"))
    if val == t:
        print("%-34s OK (%s)" % (t[:34], info))
    else:
        print("%-34s ECHEC : %s / %r" % (t[:34], info, val))
        ok = False

print("\nENCODEUR QR VALIDE" if ok else "\nDES TESTS ONT ECHOUE")
sys.exit(0 if ok else 1)
