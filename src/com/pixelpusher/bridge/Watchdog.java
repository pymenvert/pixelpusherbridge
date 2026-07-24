package com.pixelpusher.bridge;

import com.heroicrobot.pixelpusher.artnet.ArtNetReceiver;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Watchdog de signal : si aucune donnee DMX (Art-Net/sACN) n'est recue pendant
 * N secondes (configurable, 0 = desactive), envoie un blackout pour eviter
 * qu'une image figee reste allumee quand la source plante en plein spectacle.
 * Se rearme automatiquement des que le signal revient.
 */
public class Watchdog implements Runnable {

  private final AppConfig cfg;
  private final LegacyCore core;
  private final TestPatterns tests;
  private volatile Recorder recorder;
  private volatile boolean triggered = false;

  public Watchdog(AppConfig cfg, LegacyCore core, TestPatterns tests) {
    this.cfg = cfg;
    this.core = core;
    this.tests = tests;
  }

  /**
   * Lecteur de sequences, pour ne pas confondre une lecture avec une perte de
   * signal (voir run()). Facultatif : le watchdog fonctionne sans.
   */
  public void setRecorder(Recorder r) {
    this.recorder = r;
  }

  public void start() {
    Thread t = new Thread(this, "signal-watchdog");
    t.setDaemon(true);
    t.start();
  }

  public boolean isTriggered() {
    return triggered;
  }

  /** Timestamp (ms) de la derniere donnee DMX recue, 0 si aucune. */
  public static long lastDmxAt() {
    long last = 0;
    for (Long v : ArtNetReceiver.universeLastSeen.values()) {
      if (v.longValue() > last) {
        last = v.longValue();
      }
    }
    return last;
  }

  /** true si une sequence enregistree est en cours de lecture. */
  private boolean isPlaying() {
    Recorder r = recorder;
    return r != null && r.isPlaying();
  }

  @Override
  public void run() {
    while (true) {
      try {
        Thread.sleep(1000);
        int limit = cfg.getWatchdogSec();
        // Pendant un scenario de test ou la lecture d'une sequence, les pixels
        // sont alimentes par le bridge lui-meme : aucune trame n'arrive du
        // reseau et universeLastSeen ne bouge plus. Sans cette exemption, le
        // watchdog prenait une lecture pour une perte de signal et intercalait
        // une trame noire en plein milieu, devant public.
        if (limit <= 0 || tests.isEnabled() || isPlaying()) {
          triggered = false;
          continue;
        }
        long last = lastDmxAt();
        if (last == 0) {
          continue; // jamais recu de donnees : rien a couper
        }
        long ageSec = (System.currentTimeMillis() - last) / 1000;
        if (ageSec >= limit && !triggered) {
          triggered = true;
          LogBus.warn("Watchdog : aucune donnée DMX depuis " + ageSec
              + " s - blackout de sécurité envoyé.");
          core.blackoutAll();
        } else if (ageSec < limit && triggered) {
          triggered = false;
          LogBus.info("Watchdog : signal DMX rétabli.");
        }
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        LogBus.error("Watchdog : " + e);
      }
    }
  }
}
