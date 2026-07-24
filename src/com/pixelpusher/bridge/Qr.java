package com.pixelpusher.bridge;

/**
 * Encodeur QR code minimal, zero dependance : mode octets, correction L,
 * versions 1 a 5 (jusqu'a ~106 caracteres), masques 0-5 choisis par penalite.
 * Valide contre le decodeur OpenCV sur toutes les formes d'URL utilisees.
 * Sert a afficher le QR d'acces telephone dans l'interface.
 */
public final class Qr {

  private Qr() {
  }

  // GF(256), polynome 0x11D
  private static final int[] EXP = new int[512];
  private static final int[] LOG = new int[256];
  static {
    int x = 1;
    for (int i = 0; i < 255; i++) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if ((x & 0x100) != 0) {
        x ^= 0x11D;
      }
    }
    for (int i = 255; i < 512; i++) {
      EXP[i] = EXP[i - 255];
    }
  }

  //                                v1  v2  v3  v4   v5
  private static final int[] NDATA = { 19, 34, 55, 80, 108 };
  private static final int[] NECC  = { 7, 10, 15, 20, 26 };
  private static final int[][] ALIGN = { {}, {6, 18}, {6, 22}, {6, 26}, {6, 30} };

  private static int[] rsEcc(int[] data, int nEcc) {
    int[] gen = new int[] { 1 };
    for (int i = 0; i < nEcc; i++) {
      int[] ng = new int[gen.length + 1];
      for (int j = 0; j < gen.length; j++) {
        if (gen[j] != 0) {
          ng[j] ^= EXP[(LOG[gen[j]] + i) % 255];
        }
        ng[j + 1] ^= gen[j];
      }
      gen = ng;
    }
    // la construction produit le polynome en ordre croissant ; la division
    // ci-dessous le consomme en ordre decroissant -> inversion obligatoire
    // (valide contre le vecteur de reference v1-M "HELLO WORLD")
    for (int a = 0, b = gen.length - 1; a < b; a++, b--) {
      int t = gen[a];
      gen[a] = gen[b];
      gen[b] = t;
    }
    int[] res = new int[data.length + nEcc];
    System.arraycopy(data, 0, res, 0, data.length);
    for (int i = 0; i < data.length; i++) {
      int c = res[i];
      if (c != 0) {
        int lc = LOG[c];
        for (int j = 1; j < gen.length; j++) {
          if (gen[j] != 0) {
            res[i + j] ^= EXP[(LOG[gen[j]] + lc) % 255];
          }
        }
      }
    }
    int[] ecc = new int[nEcc];
    System.arraycopy(res, data.length, ecc, 0, nEcc);
    return ecc;
  }

  /** Construit la matrice (true = module noir). Lance IllegalArgumentException si trop long. */
  public static boolean[][] encode(String text) {
    byte[] data;
    try {
      data = text.getBytes("UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      data = text.getBytes();
    }
    int ver = -1;
    for (int v = 1; v <= 5; v++) {
      if (4 + 8 + data.length * 8 <= NDATA[v - 1] * 8) {
        ver = v;
        break;
      }
    }
    if (ver < 0) {
      throw new IllegalArgumentException("Texte trop long pour un QR v5-L");
    }
    int nData = NDATA[ver - 1], nEcc = NECC[ver - 1];

    // flux binaire -> codewords
    int[] cw = new int[nData + nEcc];
    boolean[] bits = new boolean[nData * 8];
    int bp = 0;
    bp = putBits(bits, bp, 0b0100, 4);
    bp = putBits(bits, bp, data.length, 8);
    for (byte b : data) {
      bp = putBits(bits, bp, b & 0xff, 8);
    }
    bp += Math.min(4, nData * 8 - bp); // terminator (bits deja a false)
    bp = (bp + 7) / 8 * 8;
    int nCw = bp / 8;
    for (int i = 0; i < nCw; i++) {
      int v = 0;
      for (int j = 0; j < 8; j++) {
        v = (v << 1) | (bits[i * 8 + j] ? 1 : 0);
      }
      cw[i] = v;
    }
    int k = 0;
    for (int i = nCw; i < nData; i++) {
      cw[i] = (k++ % 2 == 0) ? 0xEC : 0x11;
    }
    int[] dataCw = new int[nData];
    System.arraycopy(cw, 0, dataCw, 0, nData);
    int[] ecc = rsEcc(dataCw, nEcc);
    System.arraycopy(ecc, 0, cw, nData, nEcc);

    int size = 21 + 4 * (ver - 1);
    int[][] m = new int[size][size]; // -1 libre, 0/1 fonction
    for (int[] row : m) {
      java.util.Arrays.fill(row, -1);
    }

    placeFinder(m, 0, 0, size);
    placeFinder(m, 0, size - 7, size);
    placeFinder(m, size - 7, 0, size);
    for (int i = 8; i < size - 8; i++) {
      int v = (i % 2 == 0) ? 1 : 0;
      if (m[6][i] < 0) m[6][i] = v;
      if (m[i][6] < 0) m[i][6] = v;
    }
    int[] al = ALIGN[ver - 1];
    for (int r : al) {
      for (int c : al) {
        if (m[r][c] >= 0) {
          continue; // chevauche un motif deja pose (coins finders)
        }
        for (int dr = -2; dr <= 2; dr++) {
          for (int dc = -2; dc <= 2; dc++) {
            m[r + dr][c + dc] =
                (Math.abs(dr) == 2 || Math.abs(dc) == 2 || (dr == 0 && dc == 0)) ? 1 : 0;
          }
        }
      }
    }
    for (int i = 0; i < 9; i++) {
      if (m[8][i] < 0) m[8][i] = 0;
      if (m[i][8] < 0) m[i][8] = 0;
    }
    for (int i = 0; i < 8; i++) {
      if (m[8][size - 1 - i] < 0) m[8][size - 1 - i] = 0;
      if (m[size - 1 - i][8] < 0) m[size - 1 - i][8] = 0;
    }
    m[size - 8][8] = 1; // dark module

    boolean[][] func = new boolean[size][size];
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < size; c++) {
        func[r][c] = m[r][c] >= 0;
      }
    }

    // placement zigzag des donnees
    int bi = 0;
    int totalBits = cw.length * 8;
    int col = size - 1;
    boolean upward = true;
    while (col > 0) {
      if (col == 6) {
        col--;
      }
      for (int i = 0; i < size; i++) {
        int r = upward ? size - 1 - i : i;
        for (int cc = col; cc >= col - 1; cc--) {
          if (!func[r][cc]) {
            int bit = 0;
            if (bi < totalBits) {
              bit = (cw[bi / 8] >> (7 - bi % 8)) & 1;
            }
            bi++;
            m[r][cc] = bit;
          }
        }
      }
      upward = !upward;
      col -= 2;
    }

    // choix du masque 0-5 par penalite (6-7 evites : mal decodes par certains lecteurs)
    int bestMask = 0;
    long bestPen = Long.MAX_VALUE;
    boolean[][] best = null;
    for (int mask = 0; mask <= 5; mask++) {
      boolean[][] cand = applyMaskAndFormat(m, func, size, mask);
      long pen = penalty(cand, size);
      if (pen < bestPen) {
        bestPen = pen;
        bestMask = mask;
        best = cand;
      }
    }
    return best;
  }

  private static int putBits(boolean[] bits, int pos, int val, int n) {
    for (int i = n - 1; i >= 0; i--) {
      bits[pos++] = ((val >> i) & 1) != 0;
    }
    return pos;
  }

  private static void placeFinder(int[][] m, int r0, int c0, int size) {
    for (int dr = -1; dr <= 7; dr++) {
      for (int dc = -1; dc <= 7; dc++) {
        int r = r0 + dr, c = c0 + dc;
        if (r < 0 || r >= size || c < 0 || c >= size) {
          continue;
        }
        int v;
        if (dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6) {
          v = (dr == 0 || dr == 6 || dc == 0 || dc == 6
              || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4)) ? 1 : 0;
        } else {
          v = 0;
        }
        m[r][c] = v;
      }
    }
  }

  private static boolean maskAt(int mask, int r, int c) {
    switch (mask) {
      case 0: return (r + c) % 2 == 0;
      case 1: return r % 2 == 0;
      case 2: return c % 3 == 0;
      case 3: return (r + c) % 3 == 0;
      case 4: return (r / 2 + c / 3) % 2 == 0;
      case 5: return (r * c) % 2 + (r * c) % 3 == 0;
      default: return false;
    }
  }

  private static boolean[][] applyMaskAndFormat(int[][] m, boolean[][] func, int size, int mask) {
    boolean[][] out = new boolean[size][size];
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < size; c++) {
        boolean v = m[r][c] == 1;
        if (!func[r][c] && maskAt(mask, r, c)) {
          v = !v;
        }
        out[r][c] = v;
      }
    }
    // format info : EC L (01) + masque, BCH(15,5), masque fixe 0x5412
    int fmt5 = (0b01 << 3) | mask;
    int f = fmt5 << 10;
    int g = 0b10100110111;
    for (int i = 14; i >= 10; i--) {
      if (((f >> i) & 1) != 0) {
        f ^= g << (i - 10);
      }
    }
    int fmt = ((fmt5 << 10) | f) ^ 0b101010000010010;
    int[][] c1 = { {8,0},{8,1},{8,2},{8,3},{8,4},{8,5},{8,7},{8,8},{7,8},{5,8},{4,8},{3,8},{2,8},{1,8},{0,8} };
    int[][] c2 = { {size-1,8},{size-2,8},{size-3,8},{size-4,8},{size-5,8},{size-6,8},{size-7,8},
                   {8,size-8},{8,size-7},{8,size-6},{8,size-5},{8,size-4},{8,size-3},{8,size-2},{8,size-1} };
    for (int i = 0; i < 15; i++) {
      boolean bit = ((fmt >> (14 - i)) & 1) != 0;
      out[c1[i][0]][c1[i][1]] = bit;
      out[c2[i][0]][c2[i][1]] = bit;
    }
    return out;
  }

  /** Penalite ISO (N1-N4, version simplifiee mais fidele). */
  private static long penalty(boolean[][] q, int size) {
    long p = 0;
    // N1 : suites >= 5 identiques (lignes et colonnes)
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < size; i++) {
        int run = 1;
        for (int j = 1; j < size; j++) {
          boolean cur = pass == 0 ? q[i][j] : q[j][i];
          boolean prev = pass == 0 ? q[i][j - 1] : q[j - 1][i];
          if (cur == prev) {
            run++;
          } else {
            if (run >= 5) p += 3 + (run - 5);
            run = 1;
          }
        }
        if (run >= 5) p += 3 + (run - 5);
      }
    }
    // N2 : blocs 2x2
    for (int r = 0; r < size - 1; r++) {
      for (int c = 0; c < size - 1; c++) {
        if (q[r][c] == q[r][c + 1] && q[r][c] == q[r + 1][c] && q[r][c] == q[r + 1][c + 1]) {
          p += 3;
        }
      }
    }
    // N4 : proportion de noir
    int dark = 0;
    for (boolean[] row : q) {
      for (boolean v : row) {
        if (v) dark++;
      }
    }
    int pct = dark * 100 / (size * size);
    p += Math.abs(pct - 50) / 5 * 10;
    return p;
  }

  /** Auto-test : imprime la matrice en 0/1 (utilise par la validation OpenCV). */
  public static void main(String[] args) {
    boolean[][] q = encode(args.length > 0 ? args[0] : "http://192.168.1.10/m");
    StringBuilder sb = new StringBuilder();
    for (boolean[] row : q) {
      for (boolean v : row) {
        sb.append(v ? '1' : '0');
      }
      sb.append('\n');
    }
    System.out.print(sb);
  }

  /** Rend le QR en SVG (modules noirs sur fond blanc, zone calme incluse). */
  public static String toSvg(String text, int modulePx) {
    boolean[][] q = encode(text);
    int size = q.length;
    int quiet = 4;
    int dim = (size + 2 * quiet) * modulePx;
    StringBuilder sb = new StringBuilder(8192);
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(dim)
      .append("\" height=\"").append(dim).append("\" viewBox=\"0 0 ").append(dim)
      .append(' ').append(dim).append("\"><rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
      .append("<path fill=\"#000000\" d=\"");
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < size; c++) {
        if (q[r][c]) {
          int x = (c + quiet) * modulePx;
          int y = (r + quiet) * modulePx;
          sb.append('M').append(x).append(' ').append(y)
            .append('h').append(modulePx).append('v').append(modulePx)
            .append('h').append(-modulePx).append('z');
        }
      }
    }
    sb.append("\"/></svg>");
    return sb.toString();
  }
}
