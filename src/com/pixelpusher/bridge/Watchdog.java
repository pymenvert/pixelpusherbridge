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
  private volatile boolean triggered = false;

  public Watchdog(AppConfig cfg, LegacyCore core, TestPatterns tests) {
    this.cfg = cfg;
    this.core = core;
    this.tests = tests;
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

  @Override
  public void run() {
    while (true) {
      try {
        Thread.sleep(1000);
        int limit = cfg.getWatchdogSec();
        if (limit <= 0 || tests.isEnabled()) {
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
          LogBus.warn("Watchdog : aucune donnee DMX depuis " + ageSec
              + " s - blackout de securite envoye.");
          core.blackoutAll();
        } else if (ageSec < limit && triggered) {
          triggered = false;
          LogBus.info("Watchdog : signal DMX retabli.");
        }
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        LogBus.error("Watchdog : " + e);
      }
    }
  }
}
