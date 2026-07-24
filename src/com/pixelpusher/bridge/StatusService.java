package com.pixelpusher.bridge;

import java.util.List;
import java.util.Map;

import com.heroicrobot.dropbit.devices.pixelpusher.PixelPusher;
import com.heroicrobot.dropbit.registry.DeviceRegistry;
import com.heroicrobot.pixelpusher.artnet.ArtNetReceiver;
import com.heroicrobot.pixelpusher.artnet.SacnReceiver;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Construit l'instantane d'etat (JSON) pour le tableau de bord :
 * debits Art-Net/sACN, univers actifs, PixelPushers detectes, checks de sante.
 */
public class StatusService {

  private final AppConfig cfg;
  private final LegacyCore core;
  private final TestPatterns tests;
  private Watchdog watchdog;   // optionnel
  private Recorder recorder;   // optionnel
  private Blackout blackout;   // optionnel

  public void setBlackout(Blackout b) {
    this.blackout = b;
  }
  private volatile int webPort = -1;
  private volatile boolean port80 = false;

  public void setPort80(boolean b) {
    this.port80 = b;
    this.lanUrlsTs = 0; // force le recalcul des URLs
  }
  private final long startTime = System.currentTimeMillis();

  // cache des adresses LAN (rafraichi toutes les 30 s)
  private volatile String lanUrlsJson = "[]";
  private volatile long lanUrlsTs = 0;

  public void setWatchdog(Watchdog w) {
    this.watchdog = w;
  }

  public void setRecorder(Recorder r) {
    this.recorder = r;
  }

  public void setWebPort(int port) {
    this.webPort = port;
  }

  private String lanUrls() {
    long now = System.currentTimeMillis();
    if (now - lanUrlsTs < 30000) {
      return lanUrlsJson;
    }
    lanUrlsTs = now;
    StringBuilder sb = new StringBuilder(96);
    sb.append('[');
    boolean first = true;
    try {
      java.util.Enumeration<java.net.NetworkInterface> ifs =
          java.net.NetworkInterface.getNetworkInterfaces();
      while (ifs.hasMoreElements()) {
        java.net.NetworkInterface ni = ifs.nextElement();
        if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
          continue;
        }
        java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
          java.net.InetAddress a = addrs.nextElement();
          if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
            if (!first) {
              sb.append(',');
            }
            first = false;
            if (port80) {
              // URL courte : le mini-serveur port 80 redirige vers le vrai port
              sb.append("\"http://").append(a.getHostAddress()).append("/m\"");
            } else {
              sb.append("\"http://").append(a.getHostAddress())
                .append(':').append(webPort > 0 ? webPort : AppConfig.DEF_WEB_PORT)
                .append("/m\"");
            }
          }
        }
      }
    } catch (Exception ignored) {
    }
    sb.append(']');
    lanUrlsJson = sb.toString();
    return lanUrlsJson;
  }

  // calcul des debits (paquets/seconde)
  private long lastArtnet = 0;
  private long lastSacn = 0;
  private long lastPush = 0;
  private long lastRateTs = System.currentTimeMillis();
  private double artnetPps = 0;
  private double sacnPps = 0;
  private double pushPps = 0;

  public StatusService(AppConfig cfg, LegacyCore core, TestPatterns tests) {
    this.cfg = cfg;
    this.core = core;
    this.tests = tests;
  }

  private synchronized void updateRates() {
    long now = System.currentTimeMillis();
    long dt = now - lastRateTs;
    if (dt < 500) {
      return; // garde les valeurs precedentes entre deux calculs rapproches
    }
    long a = ArtNetReceiver.dmxPackets.get();
    long s = SacnReceiver.dmxPackets.get();
    long p = com.heroicrobot.dropbit.devices.pixelpusher.CardThread.totalPacketsSent.get();
    artnetPps = (a - lastArtnet) * 1000.0 / dt;
    sacnPps = (s - lastSacn) * 1000.0 / dt;
    pushPps = (p - lastPush) * 1000.0 / dt;
    lastArtnet = a;
    lastSacn = s;
    lastPush = p;
    lastRateTs = now;
  }

  public String snapshotJson() {
    updateRates();
    long now = System.currentTimeMillis();
    DeviceRegistry registry = core.getRegistry();

    StringBuilder sb = new StringBuilder(2048);
    sb.append('{');
    sb.append("\"version\":\"").append(AppConfig.VERSION).append("\",");
    sb.append("\"uptimeSec\":").append((now - startTime) / 1000).append(',');
    sb.append("\"artnetPps\":").append(fmt(artnetPps)).append(',');
    sb.append("\"sacnPps\":").append(fmt(sacnPps)).append(',');
    sb.append("\"artnetPacketsTotal\":").append(ArtNetReceiver.dmxPackets.get()).append(',');
    sb.append("\"sacnPacketsTotal\":").append(SacnReceiver.dmxPackets.get()).append(',');
    sb.append("\"testMode\":").append(tests.isEnabled()).append(',');
    // Regle sans exception : toute valeur de type texte passe par Json.esc,
    // meme quand elle vient d'une liste blanche. Un seul guillemet oublie
    // rendrait tout le snapshot illisible pour l'interface.
    sb.append("\"testPattern\":\"").append(Json.esc(tests.getPattern())).append("\",");
    sb.append("\"testLabel\":\"").append(Json.esc(tests.getCurrentLabel())).append("\",");
    sb.append("\"watchdogTriggered\":").append(watchdog != null && watchdog.isTriggered()).append(',');
    sb.append("\"blackoutActive\":").append(blackout != null && blackout.isActive()).append(',');
    sb.append("\"lanUrls\":").append(lanUrls()).append(',');
    sb.append("\"recorder\":").append(recorder != null ? recorder.stateJson() : "{}").append(',');
    sb.append("\"errorsTotal\":").append(LogBus.getErrorCount()).append(',');
    sb.append("\"lastError\":\"").append(Json.esc(LogBus.getLastError())).append("\",");

    // univers actifs (dernier paquet recu il y a moins de 30 s)
    sb.append("\"universes\":[");
    boolean first = true;
    for (Map.Entry<Integer, Long> e : ArtNetReceiver.universeLastSeen.entrySet()) {
      long age = now - e.getValue().longValue();
      if (age > 30000) {
        continue;
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append("{\"universe\":").append(e.getKey())
        .append(",\"ageMs\":").append(age).append('}');
    }
    sb.append("],");

    // pushers detectes
    long totalBandwidth = 0;
    int pusherCount = 0;
    long powerUnits = 0; // consommation annoncee par les pushers, en unites de luminance
    sb.append("\"pushers\":[");
    if (registry != null) {
      try {
        totalBandwidth = registry.getTotalBandwidth();
      } catch (RuntimeException ignored) {
      }
      List<PixelPusher> mapped = core.getMappedPushers();
      first = true;
      // ordre stable (meme indexation que les tests de lignes)
      for (PixelPusher p : registry.getPushers()) {
        pusherCount++;
        long pusherPower = 0;
        try {
          pusherPower = p.getPowerTotal();
        } catch (RuntimeException ignored) {
        }
        powerUnits += pusherPower;
        if (!first) {
          sb.append(',');
        }
        first = false;
        int lastSeen;
        try {
          lastSeen = registry.lastSeen(p);
        } catch (RuntimeException e) {
          lastSeen = -1;
        }
        long periodUs = p.getUpdatePeriod();
        double fps = periodUs > 0 ? 1000000.0 / periodUs : 0;
        String ip = "?";
        try {
          if (p.getIp() != null) {
            ip = p.getIp().getHostAddress();
          }
        } catch (RuntimeException ignored) {
        }
        sb.append('{')
          .append("\"mac\":\"").append(Json.esc(p.getMacAddress())).append("\",")
          .append("\"ip\":\"").append(Json.esc(ip)).append("\",")
          .append("\"strips\":").append(p.getNumberOfStrips()).append(',')
          .append("\"pixelsPerStrip\":").append(p.getPixelsPerStrip()).append(',')
          .append("\"artnetUniverse\":").append(p.getArtnetUniverse()).append(',')
          .append("\"artnetChannel\":").append(p.getArtnetChannel()).append(',')
          .append("\"group\":").append(p.getGroupOrdinal()).append(',')
          .append("\"controller\":").append(p.getControllerOrdinal()).append(',')
          .append("\"lastSeenSec\":").append(lastSeen).append(',')
          .append("\"updatePeriodUs\":").append(periodUs).append(',')
          .append("\"fps\":").append(fmt(fps)).append(',')
          .append("\"extraDelayMs\":").append(p.getExtraDelay()).append(',')
          .append("\"powerUnits\":").append(pusherPower).append(',')
          .append("\"mapped\":").append(mapped.contains(p))
          .append('}');
      }
    }
    sb.append("],");

    // ---- puissance electrique ----
    // Les pushers annoncent eux-memes leur consommation en « unites de luminance »
    // (255 = un canal de couleur d'un pixel allume a fond). On la convertit en
    // amperes avec la consommation par canal configuree, et on expose l'echelle
    // appliquee par le limiteur pour que l'interface montre qu'il agit.
    double powerScale = 1.0;
    if (registry != null) {
      try {
        powerScale = registry.getPowerScale();
      } catch (RuntimeException ignored) {
      }
    }
    double maPerChannel = cfg != null ? cfg.getMilliampsPerChannel() : 20.0;
    double amps = powerUnits * maPerChannel / 255.0 / 1000.0;
    double limitAmps = cfg != null ? cfg.getPowerLimitAmps() : 0;
    sb.append("\"power\":{")
      .append("\"units\":").append(powerUnits).append(',')
      .append("\"amps\":").append(fmt(amps, 2)).append(',')
      .append("\"limitAmps\":").append(fmt(limitAmps, 2)).append(',')
      .append("\"scale\":").append(fmt(powerScale, 3)).append(',')
      .append("\"limiting\":").append(limitAmps > 0 && powerScale < 0.999)
      .append("},");

    sb.append("\"totalBandwidth\":").append(totalBandwidth).append(',');
    sb.append("\"pushPps\":").append(fmt(pushPps)).append(',');
    sb.append("\"pushPacketsTotal\":")
      .append(com.heroicrobot.dropbit.devices.pixelpusher.CardThread.totalPacketsSent.get())
      .append(',');

    // derniere donnee DMX recue (tous univers confondus). Source unique :
    // Watchdog.lastDmxAt(), deja utilisee par le watchdog et le diagnostic.
    // Dupliquer ce calcul ferait diverger le voyant du tableau de bord et le
    // declenchement du blackout de securite.
    long lastDmx = Watchdog.lastDmxAt();

    // checks de sante ("est-ce que tout marche ?")
    // Le drapeau ArtNetReceiver.listening est pose une seule fois au bind et
    // n'est jamais remis a false : si le thread de reception meurt, il resterait
    // au vert. On exige donc aussi que le thread soit vivant.
    boolean artnetThreadOk = core.isArtnetThreadAlive();
    boolean artnetOk = core.isArtnetListening() && artnetThreadOk;
    boolean pushersOk = pusherCount > 0;
    boolean dataOk = lastDmx > 0 && (now - lastDmx) < 10000;
    boolean pushingOk = pushersOk && pushPps > 0;
    sb.append("\"health\":{");
    sb.append("\"webServer\":true,");
    sb.append("\"artnetListening\":").append(artnetOk).append(',');
    sb.append("\"artnetThreadAlive\":").append(artnetThreadOk).append(',');
    sb.append("\"artnetBindError\":\"").append(Json.esc(core.getArtnetBindError())).append("\",");
    sb.append("\"pushersDetected\":").append(pushersOk).append(',');
    sb.append("\"pusherCount\":").append(pusherCount).append(',');
    sb.append("\"dmxDataRecent\":").append(dataOk).append(',');
    sb.append("\"lastDmxAgeMs\":").append(lastDmx > 0 ? (now - lastDmx) : -1).append(',');
    sb.append("\"pushingFrames\":").append(pushingOk);
    sb.append('}');

    sb.append('}');
    return sb.toString();
  }

  private static String fmt(double d) {
    return fmt(d, 1);
  }

  private static String fmt(double d, int decimales) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return "0";
    }
    return String.format(java.util.Locale.US, "%." + decimales + "f", d);
  }
}
