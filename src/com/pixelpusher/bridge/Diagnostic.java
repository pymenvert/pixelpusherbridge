package com.pixelpusher.bridge;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.heroicrobot.dropbit.devices.pixelpusher.PixelPusher;
import com.heroicrobot.dropbit.registry.DeviceRegistry;
import com.heroicrobot.pixelpusher.artnet.ArtNetReceiver;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;
import com.heroicrobot.pixelpusher.artnet.SacnReceiver;

/**
 * Diagnostic complet : passe en revue reseau, materiel, configuration et
 * systeme, et produit une liste de verifications avec un conseil concret
 * pour chaque probleme detecte. Egalement exportable en rapport texte
 * (pratique pour demander de l'aide a distance).
 */
public class Diagnostic {

  public static final class Check {
    final String level; // "ok" | "warn" | "error"
    final String title;
    final String advice;

    Check(String level, String title, String advice) {
      this.level = level;
      this.title = title;
      this.advice = advice;
    }
  }

  private final AppConfig cfg;
  private final LegacyCore core;
  private final TestPatterns tests;
  private final Recorder recorder;
  private Blackout blackout; // optionnel
  private volatile int webPort = -1;

  public void setBlackout(Blackout b) {
    this.blackout = b;
  }

  public Diagnostic(AppConfig cfg, LegacyCore core, TestPatterns tests, Recorder recorder) {
    this.cfg = cfg;
    this.core = core;
    this.tests = tests;
    this.recorder = recorder;
  }

  public void setWebPort(int port) {
    this.webPort = port;
  }

  public List<Check> runChecks() {
    List<Check> out = new ArrayList<Check>();
    long now = System.currentTimeMillis();

    // ---- serveur web ----
    ok(out, "Interface web active sur le port " + webPort, "");

    // ---- reception Art-Net ----
    if (core.isArtnetListening()) {
      ok(out, "Écoute Art-Net sur le port 6454", "");
    } else {
      err(out, "Le port Art-Net 6454 n'est pas ouvert"
          + (core.getArtnetBindError() != null ? " (" + core.getArtnetBindError() + ")" : ""),
          "Un autre logiciel utilise peut-être ce port (autre node Art-Net ?). "
          + "Le bridge réessaie automatiquement toutes les 5 s.");
    }
    if (!core.isArtnetThreadAlive()) {
      err(out, "Le thread de réception Art-Net est arrêté",
          "Redémarre le bridge (bouton ↻ en haut).");
    }
    if (cfg.isSacnEnabled() && !core.isSacnThreadAlive()) {
      warn(out, "sACN activé mais le thread de réception n'est pas démarré",
          "Redémarre le bridge pour démarrer la réception sACN.");
    }

    // ---- pushers ----
    DeviceRegistry registry = core.getRegistry();
    List<PixelPusher> pushers = registry != null ? registry.getPushers()
        : new ArrayList<PixelPusher>();
    if (pushers.isEmpty()) {
      err(out, "Aucun PixelPusher détecté sur le réseau",
          "Vérifie : alimentation du pusher, câble réseau, même réseau/VLAN que cette machine, "
          + "pare-feu Windows/Mac autorisant Java en UDP entrant (port 7331).");
    } else {
      ok(out, pushers.size() + " PixelPusher(s) détecté(s)", "");
      int i = 1;
      for (PixelPusher p : pushers) {
        String id = "Pusher " + i + " (" + safeIp(p) + ")";
        int lastSeen;
        try {
          lastSeen = registry.lastSeen(p);
        } catch (RuntimeException e) {
          lastSeen = -1;
        }
        if (lastSeen > 3) {
          warn(out, id + " : instable (vu il y a " + lastSeen + " s)",
              "Réseau chargé ou WiFi ? Préfère un câble. Les annonces arrivent normalement chaque seconde.");
        }
        if (p.getArtnetUniverse() == 0 && p.getArtnetChannel() == 0) {
          err(out, id + " : univers/canal Art-Net à 0 — non mappé",
              "Édite le fichier pixel.rc sur la carte SD du pusher : mets artnet_universe=1 et artnet_channel=1 (ou plus).");
        }
        long periodUs = p.getUpdatePeriod();
        if (periodUs > 33000) { // < 30 fps
          warn(out, id + " : période de mise à jour élevée ("
              + Math.round(1000000.0 / periodUs) + " fps)",
              "Beaucoup de pixels par ligne ou réseau chargé. C'est la capacité réelle du pusher.");
        }
        long extra = p.getExtraDelay();
        if (extra > 0) {
          warn(out, id + " : le bridge ralentit de " + extra + " ms pour le suivre",
              "L'auto-throttle compense un pusher qui ne tient pas le rythme. Si ce délai "
              + "monte sans cesse, réduis le nombre de pixels par ligne ou passe sur un "
              + "réseau filaire gigabit dédié.");
        }
        i++;
      }
      if (core.getMappedPushers().isEmpty()) {
        err(out, "Aucun pusher n'est mappé en Art-Net",
            "Aucune LED ne peut s'allumer. Vérifie artnet_universe / artnet_channel dans pixel.rc (doivent être ≥ 1).");
      }
    }

    // ---- donnees DMX ----
    long lastDmx = Watchdog.lastDmxAt();
    if (lastDmx == 0) {
      warn(out, "Aucune donnée DMX reçue depuis le démarrage",
          "Lance ta source (MadMapper, grandMA, console…) et vérifie qu'elle envoie en Art-Net vers l'IP "
          + "de cette machine (ou en broadcast) sur le port 6454.");
    } else if (now - lastDmx > 10000) {
      warn(out, "Plus de données DMX depuis " + (now - lastDmx) / 1000 + " s",
          "Source arrêtée ou en pause ?");
    } else {
      ok(out, "Données DMX en cours de réception", "");
      // univers recus vs univers ecoutes
      List<Integer> unused = new ArrayList<Integer>();
      for (Map.Entry<Integer, Long> e : ArtNetReceiver.universeLastSeen.entrySet()) {
        if (now - e.getValue().longValue() > 30000) {
          continue;
        }
        boolean used = false;
        for (PixelPusher p : pushers) {
          int start = p.getArtnetUniverse();
          int end = p.getLastUniverse();
          if (end < start) {
            end = start;
          }
          if (e.getKey().intValue() >= start && e.getKey().intValue() <= end) {
            used = true;
            break;
          }
        }
        if (!used) {
          unused.add(e.getKey());
        }
      }
      if (!unused.isEmpty() && !pushers.isEmpty()) {
        warn(out, "Univers reçus mais écoutés par aucun pusher : " + unused,
            "Ta source envoie sur ces univers mais aucune LED n'y est mappée. "
            + "Vérifie l'univers de départ de ta source (rappel : univers 0 côté source = univers 1 ici) "
            + "ou utilise l'onglet Adressage DMX pour calculer la map.");
      }
    }

    // ---- mode en cours ----
    if (blackout != null && blackout.isActive()) {
      warn(out, "BLACKOUT actif — les données DMX entrantes sont ignorées",
          "Les LED resteront éteintes tant que tu n'auras pas cliqué sur « Reprendre » "
          + "(bandeau en haut de la page, ou icône de la barre système).");
    }
    if (tests.isEnabled()) {
      warn(out, "Mode test actif — le direct Art-Net est ignoré",
          "Désactive le mode test (onglet Tests) pour reprendre le flux normal.");
    }
    if (recorder.isPlaying()) {
      warn(out, "Lecture de séquence en cours — le direct Art-Net est ignoré",
          "Arrête la lecture (onglet Séquences) pour reprendre le flux normal.");
    }

    // ---- configuration ----
    if (cfg.getFrameLimit() < 30) {
      warn(out, "Limite de trames basse (" + cfg.getFrameLimit() + " Hz)",
          "En dessous de 30 Hz les animations paraissent saccadées. Conseillé : 60–85 Hz.");
    }
    if (cfg.getExtraDelayMs() > 10) {
      warn(out, "Délai additionnel élevé (" + cfg.getExtraDelayMs() + " ms)",
          "Ça limite fortement le framerate. Conseillé : 0 ms (1–5 ms si réseau saturé).");
    }
    if (cfg.getBrightness() < 0.06) {
      warn(out, "Luminosité globale presque à zéro ("
          + Math.round(cfg.getBrightness() * 100) + " %)",
          "Les LED paraîtront éteintes ! Configuration → Luminosité globale.");
    }
    if (cfg.getWatchdogSec() > 0 && cfg.getWatchdogSec() < 5) {
      warn(out, "Watchdog très court (" + cfg.getWatchdogSec() + " s)",
          "Risque de blackout intempestif sur une simple pause de la source. Conseillé : 5–30 s.");
    }

    // ---- signaux remontes par le coeur legacy ----
    long demandesRalentissement = LegacyMessages.getThrottleRequests();
    if (demandesRalentissement > 0 && !cfg.isAutoThrottle()) {
      long ago = (now - LegacyMessages.getThrottleRequestTs()) / 1000;
      warn(out, "Un PixelPusher a demandé à ralentir " + demandesRalentissement
          + " fois (dernière il y a " + ago + " s), mais l'auto-throttle est désactivé",
          "Le pusher reçoit les trames plus vite qu'il ne peut les afficher : des trames "
          + "sont perdues et l'animation peut saccader. Active « Auto-throttle » dans "
          + "Configuration → Fluidité, ou descends la limite de trames vers 60 Hz.");
    }
    long pixelsHorsRuban = LegacyMessages.getPixelOutOfRange();
    if (pixelsHorsRuban > 0) {
      warn(out, pixelsHorsRuban + " écriture(s) vers un pixel qui n'existe pas",
          "Le mapping Art-Net vise plus de pixels que le ruban n'en compte réellement. "
          + "Vérifie pixels_per_strip dans le pixel.rc du pusher et le nombre de canaux "
          + "envoyés par ta source. L'onglet Adressage DMX recalcule la map exacte.");
    }
    long paquetsMalformes = LegacyMessages.getMalformedPackets();
    if (paquetsMalformes > 0) {
      warn(out, paquetsMalformes + " paquet(s) Art-Net malformé(s) ignoré(s)",
          "Un autre logiciel émet peut-être sur le port 6454, ou le réseau perd des paquets. "
          + "Si le compteur grimpe en continu, isole le réseau lumière du réseau bureautique.");
    }
    String firmware = LegacyMessages.getFirmwareWarning();
    if (firmware != null) {
      warn(out, firmware,
          "Mets à jour le firmware du PixelPusher pour garantir la compatibilité. "
          + "Le bridge fonctionne quand même, mais certains comportements ne sont pas garantis.");
    }

    // ---- limite de puissance electrique ----
    if (cfg.getPowerLimitAmps() > 0) {
      double scale = 1.0;
      if (registry != null) {
        try {
          scale = registry.getPowerScale();
        } catch (RuntimeException ignored) {
        }
      }
      long unites = 0;
      for (PixelPusher p : pushers) {
        try {
          unites += p.getPowerTotal();
        } catch (RuntimeException ignored) {
        }
      }
      if (!pushers.isEmpty() && unites == 0) {
        warn(out, "Limite de puissance configurée mais aucun pusher n'annonce sa consommation",
            "Le limiteur ne peut pas agir : ce firmware ne remonte pas la mesure de puissance. "
            + "Utilise plutôt la luminosité globale pour maîtriser la consommation, et "
            + "dimensionne l'alimentation au pire cas (blanc plein).");
      } else if (scale < 0.999) {
        warn(out, "Limiteur de puissance actif : les LED sont à "
            + Math.round(scale * 100) + " % de leur intensité",
            "La consommation demandée dépasse la limite de " + cfg.getPowerLimitAmps()
            + " A. C'est le comportement attendu, le limiteur protège ton alimentation. "
            + "Si c'est trop sombre, augmente la limite — seulement si l'alimentation "
            + "le permet réellement — ou baisse l'intensité côté source.");
      } else {
        ok(out, "Limite de puissance : " + cfg.getPowerLimitAmps()
            + " A, non atteinte (consommation estimée "
            + String.format(java.util.Locale.US, "%.2f",
                unites * cfg.getMilliampsPerChannel() / 255.0 / 1000.0) + " A)", "");
      }
    }

    // ---- systeme ----
    String corruption = AppConfig.getCorruptionMessage();
    if (corruption != null) {
      warn(out, "La configuration a été réinitialisée au démarrage",
          corruption + " Revérifie tes réglages (ordre des couleurs, luminosité, "
          + "limite de puissance) avant le spectacle.");
    }
    File dir = AppConfig.configDir();
    if (!dir.canWrite()) {
      err(out, "Dossier de configuration non accessible en écriture : " + dir,
          "La config et les enregistrements ne peuvent pas être sauvegardés. Vérifie les permissions.");
    }
    long freeMb = dir.getFreeSpace() / (1024 * 1024);
    if (freeMb >= 0 && freeMb < 500) {
      warn(out, "Espace disque faible (" + freeMb + " Mo libres)",
          "Les enregistrements de séquences peuvent échouer. Libère de l'espace.");
    }
    Runtime rt = Runtime.getRuntime();
    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long maxMb = rt.maxMemory() / (1024 * 1024);
    if (maxMb > 0 && usedMb * 100 / maxMb > 85) {
      warn(out, "Mémoire Java presque pleine (" + usedMb + "/" + maxMb + " Mo)",
          "Redémarre le bridge. Si ça revient souvent, signale-le.");
    } else {
      ok(out, "Mémoire : " + usedMb + " Mo utilisés / " + maxMb + " Mo max", "");
    }
    if (LogBus.getErrorCount() > 0) {
      long ago = (now - LogBus.getLastErrorTs()) / 1000;
      warn(out, LogBus.getErrorCount() + " erreur(s) dans les logs (dernière il y a "
          + ago + " s)", "Onglet Logs → filtre « Erreurs » pour les détails. "
          + "Dernière : " + LogBus.getLastError());
    } else {
      ok(out, "Aucune erreur dans les logs", "");
    }

    return out;
  }

  private static String safeIp(PixelPusher p) {
    try {
      return p.getIp() != null ? p.getIp().getHostAddress() : "?";
    } catch (RuntimeException e) {
      return "?";
    }
  }

  private static void ok(List<Check> l, String t, String a) {
    l.add(new Check("ok", t, a));
  }

  private static void warn(List<Check> l, String t, String a) {
    l.add(new Check("warn", t, a));
  }

  private static void err(List<Check> l, String t, String a) {
    l.add(new Check("error", t, a));
  }

  public String toJson() {
    List<Check> checks = runChecks();
    int errors = 0, warns = 0;
    StringBuilder sb = new StringBuilder(2048);
    sb.append("{\"checks\":[");
    boolean first = true;
    for (Check c : checks) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      if ("error".equals(c.level)) {
        errors++;
      } else if ("warn".equals(c.level)) {
        warns++;
      }
      sb.append("{\"level\":\"").append(c.level)
        .append("\",\"title\":\"").append(Json.esc(c.title))
        .append("\",\"advice\":\"").append(Json.esc(c.advice)).append("\"}");
    }
    sb.append("],\"errors\":").append(errors).append(",\"warnings\":").append(warns).append('}');
    return sb.toString();
  }

  /** Rapport texte complet (support / aide a distance). */
  public String toTextReport(String statusJson, String configJson) {
    StringBuilder sb = new StringBuilder(8192);
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    sb.append("==========================================================\n");
    sb.append(" PixelPusher Bridge v").append(AppConfig.VERSION)
      .append(" - Rapport de diagnostic\n");
    sb.append(" Généré le ").append(df.format(new Date())).append('\n');
    sb.append("==========================================================\n\n");
    sb.append("Système : ").append(System.getProperty("os.name")).append(' ')
      .append(System.getProperty("os.version")).append(" / ")
      .append(System.getProperty("os.arch")).append('\n');
    sb.append("Java    : ").append(System.getProperty("java.version")).append('\n');
    sb.append("Dossier : ").append(AppConfig.configDir()).append("\n\n");

    sb.append("---------------- VÉRIFICATIONS ----------------\n");
    for (Check c : runChecks()) {
      String icon = "ok".equals(c.level) ? "[OK]  " : "warn".equals(c.level) ? "[ATT] " : "[ERR] ";
      sb.append(icon).append(c.title).append('\n');
      if (c.advice.length() > 0) {
        sb.append("      -> ").append(c.advice).append('\n');
      }
    }

    sb.append("\n---------------- CONFIGURATION ----------------\n");
    sb.append(configJson).append('\n');
    sb.append("\n---------------- ÉTAT ----------------\n");
    sb.append(statusJson).append('\n');
    sb.append("\n---------------- DERNIERS LOGS ----------------\n");
    List<LogBus.Entry> logs = LogBus.getSince(0);
    int start = Math.max(0, logs.size() - 150);
    SimpleDateFormat lf = new SimpleDateFormat("HH:mm:ss.SSS");
    for (int i = start; i < logs.size(); i++) {
      LogBus.Entry e = logs.get(i);
      sb.append(lf.format(new Date(e.ts))).append(" [").append(e.level).append("] ")
        .append(e.msg).append('\n');
    }
    return sb.toString();
  }
}
