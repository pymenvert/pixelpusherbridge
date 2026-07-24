package com.pixelpusher.bridge;

import java.util.List;

import com.heroicrobot.dropbit.devices.pixelpusher.PixelPusher;
import com.heroicrobot.dropbit.devices.pixelpusher.Strip;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Generateur de scenarios de test : verification du cablage, de l'ordre des
 * couleurs, du sens des strips et de la fluidite, sans logiciel Art-Net externe.
 *
 * Scenarios disponibles :
 *  - solid    : couleur unie partout
 *  - white    : blanc 100% partout
 *  - rainbow  : arc-en-ciel anime le long de chaque strip
 *  - gradient : degrade noir -> couleur le long de chaque strip (verifie le SENS)
 *  - chase    : point lumineux qui parcourt chaque strip
 *  - rgbcycle : rouge / vert / bleu / blanc en alternance (verifie l'ordre des couleurs)
 *  - strips   : une couleur differente par strip (verifie le cablage)
 *  - line     : allume UNE ligne precise (ou toutes les lignes d'un pusher precis)
 *  - lineseq  : allume les lignes une par une, en sequence automatique
 *  - pusherseq: allume les pushers un par un (toutes leurs lignes)
 *  - blackout : tout eteint
 *
 * Quand un test est actif, les donnees DMX entrantes sont ignorees (mute),
 * puis retablies automatiquement a l'arret du test.
 */
public class TestPatterns implements Runnable {

  public static final String[] PATTERNS = {
      "solid", "white", "rainbow", "gradient", "chase", "rgbcycle",
      "strips", "line", "lineseq", "pusherseq", "blackout"
  };

  private final LegacyCore core;
  private volatile boolean enabled = false;
  private volatile String pattern = "solid";
  private volatile int color = 0xff0000;
  private volatile double brightness = 1.0; // 0..1
  private volatile double speed = 1.0;      // 0.1 .. 5
  private volatile long t0 = System.currentTimeMillis();

  // cible pour le scenario "line" : index du pusher (-1 = tous) et de la
  // ligne dans ce pusher (-1 = toutes les lignes du pusher)
  private volatile int linePusher = 0;
  private volatile int lineStrip = 0;

  // position courante (affichee dans l'interface) pour les sequences
  private volatile String currentLabel = "";

  // palette pour "strips" et les sequences (identification du cablage)
  private static final int[] STRIP_COLORS = {
      0xff0000, 0x00ff00, 0x0000ff, 0xffff00,
      0xff00ff, 0x00ffff, 0xffffff, 0xff8000
  };

  public TestPatterns(LegacyCore core) {
    this.core = core;
  }

  public void start() {
    Thread t = new Thread(this, "TestPatterns");
    t.setDaemon(true);
    t.start();
  }

  public synchronized void configure(boolean enabled, String pattern, int color,
      double brightness, double speed, int linePusher, int lineStrip) {
    boolean wasEnabled = this.enabled;
    if (pattern != null && isValidPattern(pattern)) {
      this.pattern = pattern;
    }
    this.color = color & 0xffffff;
    this.brightness = Math.max(0.0, Math.min(1.0, brightness));
    this.speed = Math.max(0.1, Math.min(5.0, speed));
    this.linePusher = linePusher;
    this.lineStrip = lineStrip;
    this.enabled = enabled;
    if (enabled && !wasEnabled) {
      t0 = System.currentTimeMillis();
      core.setMuteDmx(true);
      LogBus.info("Mode test ACTIVE (scenario=" + this.pattern + ") - donnees Art-Net/sACN ignorees");
    } else if (!enabled && wasEnabled) {
      core.setMuteDmx(false);
      currentLabel = "";
      blackoutOnce();
      LogBus.info("Mode test desactive - donnees Art-Net/sACN retablies");
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getPattern() {
    return pattern;
  }

  public String getCurrentLabel() {
    return currentLabel;
  }

  private static boolean isValidPattern(String p) {
    for (String s : PATTERNS) {
      if (s.equals(p)) {
        return true;
      }
    }
    return false;
  }

  private void blackoutOnce() {
    core.blackoutAll();
  }

  @Override
  public void run() {
    while (true) {
      try {
        if (!enabled || core.getRegistry() == null) {
          Thread.sleep(100);
          continue;
        }
        renderFrame();
        Thread.sleep(33); // ~30 fps
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        LogBus.error("TestPatterns: " + e);
        try {
          Thread.sleep(500);
        } catch (InterruptedException ie) {
          return;
        }
      }
    }
  }

  private void renderFrame() {
    List<PixelPusher> pushers = core.getRegistry().getPushers();
    if (pushers.isEmpty()) {
      currentLabel = "Aucun PixelPusher détecté";
      return;
    }
    double t = (System.currentTimeMillis() - t0) / 1000.0 * speed;
    String p = pattern;
    double b = brightness;

    // pre-calculs pour les sequences
    int totalLines = 0;
    for (PixelPusher pu : pushers) {
      totalLines += pu.getNumberOfStrips();
    }
    int seqLine = -1;
    int seqPusher = -1;
    if ("lineseq".equals(p) && totalLines > 0) {
      // 1,2 s par ligne a vitesse 1x
      seqLine = (int) (t / 1.2) % totalLines;
    } else if ("pusherseq".equals(p)) {
      // 2 s par pusher a vitesse 1x
      seqPusher = (int) (t / 2.0) % pushers.size();
    }

    int globalLine = 0;
    int pi = 0;
    for (PixelPusher pusher : pushers) {
      List<Strip> strips = pusher.getStrips();
      int si = 0;
      for (Strip strip : strips) {
        int len = strip.getLength();
        boolean lit;
        int stripColor = color;

        if ("line".equals(p)) {
          boolean pusherMatch = (linePusher < 0) || (linePusher == pi);
          boolean stripMatch = (lineStrip < 0) || (lineStrip == si);
          lit = pusherMatch && stripMatch;
        } else if ("lineseq".equals(p)) {
          lit = (globalLine == seqLine);
          if (lit) {
            currentLabel = "Pusher " + (pi + 1) + " — ligne " + (si + 1);
          }
        } else if ("pusherseq".equals(p)) {
          lit = (pi == seqPusher);
          if (lit && si == 0) {
            currentLabel = "Pusher " + (pi + 1) + " — toutes les lignes";
          }
          stripColor = STRIP_COLORS[si % STRIP_COLORS.length];
        } else {
          lit = true;
        }

        for (int i = 0; i < len; i++) {
          int c;
          if (!lit) {
            c = 0;
          } else if ("solid".equals(p) || "line".equals(p) || "lineseq".equals(p)) {
            c = scale(stripColor, b);
          } else if ("pusherseq".equals(p)) {
            c = scale(stripColor, b);
          } else if ("white".equals(p)) {
            c = scale(0xffffff, b);
          } else if ("blackout".equals(p)) {
            c = 0;
          } else if ("rainbow".equals(p)) {
            float hue = (float) (((double) i / Math.max(1, len)) + t * 0.15);
            c = scale(java.awt.Color.HSBtoRGB(hue - (float) Math.floor(hue), 1f, 1f) & 0xffffff, b);
          } else if ("gradient".equals(p)) {
            // pixel 0 sombre -> dernier pixel plein : montre le SENS de la strip
            c = scale(color, b * (i + 1) / (double) len);
          } else if ("chase".equals(p)) {
            int pos = (int) (t * 20) % Math.max(1, len);
            int dist = Math.min(Math.abs(i - pos), len - Math.abs(i - pos));
            c = (dist <= 2) ? scale(color, b * (1.0 - dist * 0.35)) : 0;
          } else if ("rgbcycle".equals(p)) {
            int phase = (int) t % 4;
            int base = phase == 0 ? 0xff0000 : phase == 1 ? 0x00ff00
                     : phase == 2 ? 0x0000ff : 0xffffff;
            c = scale(base, b);
          } else if ("strips".equals(p)) {
            c = scale(STRIP_COLORS[si % STRIP_COLORS.length], b);
          } else {
            c = 0;
          }
          strip.setPixel(c, i);
        }
        si++;
        globalLine++;
      }
      pi++;
    }

    if ("line".equals(p)) {
      String pl = linePusher < 0 ? "tous les pushers" : "pusher " + (linePusher + 1);
      String sl = lineStrip < 0 ? "toutes les lignes" : "ligne " + (lineStrip + 1);
      currentLabel = pl + " — " + sl;
    } else if ("rgbcycle".equals(p)) {
      int phase = (int) t % 4;
      currentLabel = phase == 0 ? "ROUGE" : phase == 1 ? "VERT" : phase == 2 ? "BLEU" : "BLANC";
    } else if (!"lineseq".equals(p) && !"pusherseq".equals(p)) {
      currentLabel = "";
    }
  }

  private static int scale(int rgb, double factor) {
    if (factor >= 1.0) {
      return rgb;
    }
    if (factor <= 0) {
      return 0;
    }
    int r = (int) (((rgb >> 16) & 0xff) * factor);
    int g = (int) (((rgb >> 8) & 0xff) * factor);
    int bl = (int) ((rgb & 0xff) * factor);
    return (r << 16) | (g << 8) | bl;
  }
}
