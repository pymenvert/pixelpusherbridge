package com.pixelpusher.bridge;

import java.util.Iterator;
import java.util.Map;

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

  /** Age au-dela duquel un univers devenu silencieux quitte les tables du moniteur. */
  private static final long PURGE_AGE_MS = 60000;
  /** Intervalle entre deux purges : le balayage est trivial, inutile d'y aller chaque seconde. */
  private static final long PURGE_EVERY_MS = 10000;
  private long lastPurgeAt = 0;

  /**
   * Purge des tables du moniteur (universeLastSeen / lastFrame).
   *
   * Ces tables sont bornees a 512 univers cote recepteurs, mais rien ne les
   * vidait : une source mal configuree, un changement de patch ou des paquets
   * corrompus y laissaient definitivement des univers morts, chacun retenant un
   * tableau de 512 octets, et StatusService comme Diagnostic les reparcourent
   * chaque seconde. Une fois les 512 places occupees par des fantomes, un
   * univers reellement utilise ne pouvait meme plus etre suivi.
   *
   * La purge se fait ICI, sur le thread du watchdog, et JAMAIS sur le thread de
   * reception : le chemin Art-Net -> pushers ne doit porter aucun balayage.
   *
   * On conserve TOUJOURS l'univers vu le plus recemment : lastDmxAt() s'en sert
   * pour mesurer l'age du signal et le watchdog accepte un delai allant jusqu'a
   * 300 s. Effacer la derniere trace ferait retomber lastDmxAt() a 0, c'est a
   * dire « jamais recu de donnees », et le blackout de securite ne partirait
   * plus du tout des que le delai configure depasse 60 s.
   */
  private void purgeUniversSilencieux() {
    long now = System.currentTimeMillis();
    if (now - lastPurgeAt < PURGE_EVERY_MS) {
      return;
    }
    lastPurgeAt = now;
    Integer plusRecent = null;
    long meilleur = Long.MIN_VALUE;
    for (Map.Entry<Integer, Long> e : ArtNetReceiver.universeLastSeen.entrySet()) {
      long v = e.getValue().longValue();
      if (v > meilleur) {
        meilleur = v;
        plusRecent = e.getKey();
      }
    }
    Iterator<Map.Entry<Integer, Long>> it =
        ArtNetReceiver.universeLastSeen.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Integer, Long> e = it.next();
      if (e.getKey().equals(plusRecent)) {
        continue;
      }
      if (now - e.getValue().longValue() > PURGE_AGE_MS) {
        it.remove();
        ArtNetReceiver.lastFrame.remove(e.getKey());
      }
    }
    // Orphelins : les deux tables sont bornees separement, un univers a pu
    // entrer dans lastFrame alors que universeLastSeen etait deja pleine. Les
    // recepteurs ecrivent toujours universeLastSeen en premier, donc une cle
    // presente ici sans horodatage est bien un orphelin, pas une course.
    Iterator<Integer> itFrames = ArtNetReceiver.lastFrame.keySet().iterator();
    while (itFrames.hasNext()) {
      if (!ArtNetReceiver.universeLastSeen.containsKey(itFrames.next())) {
        itFrames.remove();
      }
    }
  }

  @Override
  public void run() {
    while (true) {
      try {
        Thread.sleep(1000);
        // Menage des tables du moniteur avant toute autre chose : il doit avoir
        // lieu meme quand le watchdog est desactive, en mode test ou pendant une
        // lecture de sequence, sinon les univers morts s'accumulent quand meme.
        purgeUniversSilencieux();
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
