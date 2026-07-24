package com.pixelpusher.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.heroicrobot.dropbit.registry.DeviceRegistry;
import com.heroicrobot.pixelpusher.artnet.ArtNetReceiver;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;
import com.heroicrobot.pixelpusher.artnet.SacnReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Serveur web embarque : interface de configuration, tableau de bord,
 * logs temps reel (SSE) et patterns de test.
 * Tourne dans ses propres threads - aucun impact sur le flux Art-Net.
 */
public class WebServer {

  private final AppConfig cfg;
  private final LegacyCore core;
  private final TestPatterns tests;
  private final StatusService status;
  private final Recorder recorder;
  private Diagnostic diagnostic; // optionnel
  private HttpServer server;
  private int boundPort = -1;
  private volatile boolean port80Bound = false;

  public void setDiagnostic(Diagnostic d) {
    this.diagnostic = d;
  }

  // clients SSE (flux de logs)
  private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<SseClient>();
  private final ScheduledExecutorService pinger;

  public WebServer(AppConfig cfg, LegacyCore core, TestPatterns tests, StatusService status,
      Recorder recorder) {
    this.cfg = cfg;
    this.core = core;
    this.tests = tests;
    this.status = status;
    this.recorder = recorder;
    this.pinger = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "sse-ping");
        t.setDaemon(true);
        return t;
      }
    });
  }

  public int getBoundPort() {
    return boundPort;
  }

  /** Demarre le serveur ; essaie le port configure puis les 10 suivants. */
  public void start() throws IOException {
    int base = cfg.getWebPort();
    IOException lastError = null;
    for (int p = base; p <= base + 10; p++) {
      try {
        server = HttpServer.create(new InetSocketAddress(p), 0);
        boundPort = p;
        break;
      } catch (IOException e) {
        lastError = e;
        LogBus.warn("Port web " + p + " indisponible (" + e.getMessage() + "), essai suivant...");
      }
    }
    if (server == null) {
      throw new IOException("Aucun port web disponible entre " + base + " et " + (base + 10), lastError);
    }

    server.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
      private int n = 0;
      public synchronized Thread newThread(Runnable r) {
        Thread t = new Thread(r, "web-" + (n++));
        t.setDaemon(true);
        return t;
      }
    }));

    server.createContext("/", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        serveStatic(ex);
      }
    });
    server.createContext("/api/status", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        sendJson(ex, 200, status.snapshotJson());
      }
    });
    server.createContext("/api/config", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
          handleConfigPost(ex);
        } else {
          sendJson(ex, 200, cfg.toJson());
        }
      }
    });
    server.createContext("/api/test", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleTestPost(ex);
      }
    });
    server.createContext("/api/action", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleAction(ex);
      }
    });
    server.createContext("/api/logs", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleSse(ex);
      }
    });
    server.createContext("/api/presets", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handlePresets(ex);
      }
    });
    server.createContext("/api/recorder", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleRecorder(ex);
      }
    });
    server.createContext("/api/dmx", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleDmxMonitor(ex);
      }
    });
    server.createContext("/qr.svg", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleQr(ex);
      }
    });
    server.createContext("/api/diagnostic/download", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        if (diagnostic == null) {
          sendJson(ex, 503, "{\"ok\":false}");
          return;
        }
        String report = diagnostic.toTextReport(status.snapshotJson(), cfg.toJson());
        byte[] data = report.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition",
            "attachment; filename=\"diagnostic-pixelpusher-bridge.txt\"");
        ex.sendResponseHeaders(200, data.length);
        OutputStream os = ex.getResponseBody();
        os.write(data);
        os.close();
      }
    });
    server.createContext("/api/diagnostic", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        if ("/api/diagnostic/download".equals(ex.getRequestURI().getPath())) {
          return; // gere par le contexte plus specifique
        }
        if (diagnostic == null) {
          sendJson(ex, 503, "{\"ok\":false}");
          return;
        }
        sendJson(ex, 200, diagnostic.toJson());
      }
    });
    server.createContext("/api/logs/download", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleLogDownload(ex);
      }
    });

    server.start();

    // ping SSE toutes les 15 s pour garder les connexions ouvertes
    pinger.scheduleAtFixedRate(new Runnable() {
      public void run() {
        for (SseClient c : sseClients) {
          c.ping();
        }
      }
    }, 15, 15, TimeUnit.SECONDS);

    // un seul abonne LogBus qui diffuse a tous les clients SSE
    LogBus.addListener(new LogBus.Listener() {
      public void onLog(LogBus.Entry e) {
        for (SseClient c : sseClients) {
          c.send("log", e.toJson());
        }
      }
    });

    LogBus.info("Interface web disponible sur http://localhost:" + boundPort + "/");

    // URL courte pour le telephone : un mini-serveur sur le port 80 redirige
    // vers le vrai port (http://IP/m marche alors sans taper :7350).
    // Best effort : sous Linux ou si le port est pris, on continue sans.
    if (boundPort != 80) {
      try {
        final HttpServer s80 = HttpServer.create(new InetSocketAddress(80), 0);
        s80.createContext("/", new SafeHandler() {
          void doHandle(HttpExchange ex) throws IOException {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || host.isEmpty()) {
              host = "localhost";
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
              host = host.substring(0, colon);
            }
            ex.getResponseHeaders().set("Location",
                "http://" + host + ":" + boundPort + ex.getRequestURI().toString());
            ex.sendResponseHeaders(302, -1);
            ex.close();
          }
        });
        s80.setExecutor(server.getExecutor());
        s80.start();
        port80Bound = true;
        status.setPort80(true);
        LogBus.info("URL courte activee : http://<ip-de-cette-machine>/m (redirige vers le port "
            + boundPort + ")");
      } catch (Exception e) {
        LogBus.info("Port 80 indisponible (" + e.getMessage()
            + ") : l'URL telephone garde le port :" + boundPort + " - le QR code s'en charge.");
      }
    }
  }

  /** QR code SVG de l'URL d'acces (par defaut : l'interface mobile sur le LAN). */
  private void handleQr(HttpExchange ex) throws IOException {
    String query = ex.getRequestURI().getQuery();
    String text = null;
    if (query != null) {
      for (String pair : query.split("&")) {
        if (pair.startsWith("text=")) {
          text = java.net.URLDecoder.decode(pair.substring(5), "UTF-8");
        }
      }
    }
    if (text == null) {
      // URL mobile directe (avec port : le QR n'est pas tape a la main)
      String ip = firstLanIp();
      if (ip == null) {
        sendText(ex, 404, "text/plain", "Aucune adresse LAN detectee");
        return;
      }
      text = "http://" + ip + ":" + boundPort + "/m";
    }
    if (text.length() > 110) {
      sendText(ex, 400, "text/plain", "Texte trop long");
      return;
    }
    String svg;
    try {
      svg = Qr.toSvg(text, 8);
    } catch (RuntimeException e) {
      sendText(ex, 500, "text/plain", "QR impossible : " + e);
      return;
    }
    byte[] data = svg.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "image/svg+xml");
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, data.length);
    OutputStream os = ex.getResponseBody();
    os.write(data);
    os.close();
  }

  private static String firstLanIp() {
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
            return a.getHostAddress();
          }
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  // ---------- handlers ----------

  private abstract class SafeHandler implements HttpHandler {
    abstract void doHandle(HttpExchange ex) throws IOException;

    public final void handle(HttpExchange ex) {
      try {
        doHandle(ex);
      } catch (Exception e) {
        LogBus.error("Web: erreur sur " + ex.getRequestURI() + " : " + e);
        try {
          sendJson(ex, 500, "{\"ok\":false,\"error\":\"" + Json.esc(String.valueOf(e)) + "\"}");
        } catch (IOException ignored) {
        }
      }
    }
  }

  private void serveStatic(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String resource;
    if ("/".equals(path) || "/index.html".equals(path)) {
      resource = "/web/index.html";
    } else if ("/m".equals(path) || "/mobile".equals(path) || "/mobile.html".equals(path)) {
      resource = "/web/mobile.html";
    } else {
      sendText(ex, 404, "text/plain", "404");
      return;
    }
    InputStream in = WebServer.class.getResourceAsStream(resource);
    if (in == null) {
      sendText(ex, 500, "text/plain",
          resource + " introuvable dans le jar (dossier web/ manquant au build)");
      return;
    }
    byte[] data = readAll(in);
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    ex.sendResponseHeaders(200, data.length);
    OutputStream os = ex.getResponseBody();
    os.write(data);
    os.close();
  }

  /** Moniteur DMX : valeurs brutes de la derniere trame recue pour un univers. */
  private void handleDmxMonitor(HttpExchange ex) throws IOException {
    String query = ex.getRequestURI().getQuery();
    int universe = 1;
    if (query != null) {
      for (String pair : query.split("&")) {
        if (pair.startsWith("u=")) {
          universe = parseInt(pair.substring(2), 1);
        }
      }
    }
    byte[] frame = ArtNetReceiver.lastFrame.get(Integer.valueOf(universe));
    Long seen = ArtNetReceiver.universeLastSeen.get(Integer.valueOf(universe));
    StringBuilder sb = new StringBuilder(2600);
    sb.append("{\"universe\":").append(universe);
    sb.append(",\"ageMs\":").append(seen != null ? (System.currentTimeMillis() - seen.longValue()) : -1);
    sb.append(",\"values\":[");
    if (frame != null) {
      for (int i = 0; i < frame.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(frame[i] & 0xff);
      }
    }
    sb.append("]}");
    sendJson(ex, 200, sb.toString());
  }

  private void handlePresets(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 200, "{\"presets\":" + Presets.listJson() + "}");
      return;
    }
    Map<String, String> f = parseForm(ex);
    String action = f.get("action");
    String name = f.get("name");
    if ("save".equals(action)) {
      if (Presets.save(name, cfg)) {
        sendJson(ex, 200, "{\"ok\":true,\"presets\":" + Presets.listJson() + "}");
      } else {
        sendJson(ex, 400, "{\"ok\":false,\"error\":\"Nom de preset invalide\"}");
      }
    } else if ("load".equals(action)) {
      int oldPort = cfg.getWebPort();
      boolean oldSacn = cfg.isSacnEnabled();
      if (Presets.load(name, cfg)) {
        applyLiveSettings();
        boolean restartRequired = cfg.getWebPort() != oldPort
            || (cfg.isSacnEnabled() && !oldSacn);
        sendJson(ex, 200, "{\"ok\":true,\"restartRequired\":" + restartRequired + "}");
      } else {
        sendJson(ex, 404, "{\"ok\":false,\"error\":\"Preset introuvable\"}");
      }
    } else if ("delete".equals(action)) {
      boolean ok = Presets.delete(name);
      sendJson(ex, ok ? 200 : 404,
          "{\"ok\":" + ok + ",\"presets\":" + Presets.listJson() + "}");
    } else {
      sendJson(ex, 400, "{\"ok\":false,\"error\":\"Action inconnue\"}");
    }
  }

  /** Applique la configuration courante aux composants en marche (chargement de preset). */
  private void applyLiveSettings() {
    DeviceRegistry registry = core.getRegistry();
    if (registry != null) {
      registry.setAutoThrottle(cfg.isAutoThrottle());
      registry.setTotalPowerLimit(cfg.getPowerLimitUnits());
      registry.setFrameLimit(cfg.getFrameLimit());
      registry.setExtraDelay(cfg.getExtraDelayMs());
      registry.setAntiLog(cfg.isAntiLog());
      if (cfg.isExpiryEnabled()) {
        registry.enableExpiry();
      } else {
        registry.disableExpiry();
      }
    }
    DeviceRegistry.useOverallBrightnessScale = cfg.getBrightness() < 0.999;
    DeviceRegistry.setOverallBrightnessScale(cfg.getBrightness());
    ArtNetReceiver.debug = cfg.isDebug();
    SacnReceiver.enabled = cfg.isSacnEnabled();
    core.remap(cfg.getColourOrder(), cfg.isPacking());
  }

  private void handleRecorder(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 200, "{\"state\":" + recorder.stateJson()
          + ",\"recordings\":" + recorder.listJson() + "}");
      return;
    }
    Map<String, String> f = parseForm(ex);
    String action = f.get("action");
    if ("record".equals(action)) {
      String name = recorder.startRecord(f.get("name"));
      if (name != null) {
        sendJson(ex, 200, "{\"ok\":true,\"name\":\"" + Json.esc(name) + "\"}");
      } else {
        sendJson(ex, 400, "{\"ok\":false,\"error\":\"Enregistrement deja en cours ou erreur fichier\"}");
      }
    } else if ("stopRecord".equals(action)) {
      recorder.stopRecord();
      sendJson(ex, 200, "{\"ok\":true}");
    } else if ("play".equals(action)) {
      if (tests.isEnabled()) {
        tests.configure(false, null, 0, 1, 1, 0, 0); // les tests et la lecture sont exclusifs
      }
      boolean loop = Boolean.parseBoolean(f.get("loop"));
      boolean ok = recorder.play(f.get("name"), loop);
      sendJson(ex, ok ? 200 : 404, "{\"ok\":" + ok + "}");
    } else if ("stopPlay".equals(action)) {
      recorder.stopPlay();
      sendJson(ex, 200, "{\"ok\":true}");
    } else if ("delete".equals(action)) {
      boolean ok = recorder.delete(f.get("name"));
      sendJson(ex, ok ? 200 : 404,
          "{\"ok\":" + ok + ",\"recordings\":" + recorder.listJson() + "}");
    } else {
      sendJson(ex, 400, "{\"ok\":false,\"error\":\"Action inconnue\"}");
    }
  }

  private void handleConfigPost(HttpExchange ex) throws IOException {
    Map<String, String> f = parseForm(ex);
    boolean restartRequired = false;

    String oldOrder = cfg.getColourOrder();
    boolean oldPacking = cfg.isPacking();

    if (f.containsKey("webPort")) {
      int p = parseInt(f.get("webPort"), cfg.getWebPort());
      if (p != cfg.getWebPort()) {
        if (p < 1024 || p > 65535) {
          sendJson(ex, 400, "{\"ok\":false,\"error\":\"Port web invalide (1024-65535)\"}");
          return;
        }
        cfg.setWebPort(p);
        restartRequired = true;
      }
    }
    if (f.containsKey("colourOrder")) {
      String o = f.get("colourOrder").toUpperCase();
      if (!o.matches("RGB|RBG|GRB|GBR|BRG|BGR")) {
        sendJson(ex, 400, "{\"ok\":false,\"error\":\"Ordre de couleurs invalide\"}");
        return;
      }
      cfg.setColourOrder(o);
    }
    if (f.containsKey("packing")) {
      cfg.setPacking(Boolean.parseBoolean(f.get("packing")));
    }
    if (f.containsKey("debug")) {
      cfg.setDebug(Boolean.parseBoolean(f.get("debug")));
      ArtNetReceiver.debug = cfg.isDebug();
      if (core.getRegistry() != null) {
        core.getRegistry().setLogging(cfg.isDebug());
      }
    }
    if (f.containsKey("sacnEnabled")) {
      boolean v = Boolean.parseBoolean(f.get("sacnEnabled"));
      if (v != cfg.isSacnEnabled()) {
        cfg.setSacnEnabled(v);
        SacnReceiver.enabled = v;
        if (v) {
          restartRequired = true; // le thread sACN ne demarre qu'au lancement
        }
      }
    }
    DeviceRegistry registry = core.getRegistry();
    if (f.containsKey("autoThrottle")) {
      cfg.setAutoThrottle(Boolean.parseBoolean(f.get("autoThrottle")));
      if (registry != null) {
        registry.setAutoThrottle(cfg.isAutoThrottle());
      }
    }
    // La limite de puissance et la consommation par canal se calculent ensemble :
    // la seconde sert a convertir la premiere en unites de luminance.
    if (f.containsKey("milliampsPerChannel")) {
      cfg.setMilliampsPerChannel(
          parseDouble(f.get("milliampsPerChannel"), cfg.getMilliampsPerChannel()));
    }
    if (f.containsKey("powerLimitAmps")) {
      cfg.setPowerLimitAmps(parseDouble(f.get("powerLimitAmps"), cfg.getPowerLimitAmps()));
    }
    if (f.containsKey("powerLimitAmps") || f.containsKey("milliampsPerChannel")) {
      if (registry != null) {
        registry.setTotalPowerLimit(cfg.getPowerLimitUnits());
      }
      LogBus.info("Limite de puissance : "
          + (cfg.getPowerLimitAmps() > 0
              ? cfg.getPowerLimitAmps() + " A (" + cfg.getPowerLimitUnits() + " unités, "
                  + cfg.getMilliampsPerChannel() + " mA par canal)"
              : "désactivée"));
    }
    if (f.containsKey("frameLimit")) {
      cfg.setFrameLimit(parseInt(f.get("frameLimit"), cfg.getFrameLimit()));
      if (registry != null) {
        registry.setFrameLimit(cfg.getFrameLimit());
      }
    }
    if (f.containsKey("extraDelayMs")) {
      cfg.setExtraDelayMs(parseInt(f.get("extraDelayMs"), cfg.getExtraDelayMs()));
      if (registry != null) {
        registry.setExtraDelay(cfg.getExtraDelayMs());
      }
    }
    if (f.containsKey("antiLog")) {
      cfg.setAntiLog(Boolean.parseBoolean(f.get("antiLog")));
      if (registry != null) {
        registry.setAntiLog(cfg.isAntiLog());
      }
    }
    if (f.containsKey("brightness")) {
      double b = parseDouble(f.get("brightness"), cfg.getBrightness());
      cfg.setBrightness(b);
      DeviceRegistry.useOverallBrightnessScale = cfg.getBrightness() < 0.999;
      DeviceRegistry.setOverallBrightnessScale(cfg.getBrightness());
    }
    if (f.containsKey("expiryEnabled")) {
      cfg.setExpiryEnabled(Boolean.parseBoolean(f.get("expiryEnabled")));
      if (registry != null) {
        if (cfg.isExpiryEnabled()) {
          registry.enableExpiry();
        } else {
          registry.disableExpiry();
        }
      }
    }
    if (f.containsKey("openBrowser")) {
      cfg.setOpenBrowser(Boolean.parseBoolean(f.get("openBrowser")));
    }
    if (f.containsKey("watchdogSec")) {
      cfg.setWatchdogSec(parseInt(f.get("watchdogSec"), cfg.getWatchdogSec()));
    }
    if (f.containsKey("blackoutOnExit")) {
      cfg.setBlackoutOnExit(Boolean.parseBoolean(f.get("blackoutOnExit")));
    }

    // ordre des couleurs ou packing modifie -> remappage a chaud
    if (!cfg.getColourOrder().equals(oldOrder) || cfg.isPacking() != oldPacking) {
      core.remap(cfg.getColourOrder(), cfg.isPacking());
    }

    cfg.save();
    LogBus.info("Configuration enregistree" + (restartRequired ? " (redemarrage requis pour certains changements)" : ""));
    sendJson(ex, 200, "{\"ok\":true,\"restartRequired\":" + restartRequired + "}");
  }

  private void handleTestPost(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, "{\"ok\":false,\"error\":\"POST attendu\"}");
      return;
    }
    Map<String, String> f = parseForm(ex);
    boolean enabled = Boolean.parseBoolean(f.get("enabled"));
    String pattern = f.containsKey("pattern") ? f.get("pattern") : "solid";
    int color = 0xff0000;
    if (f.containsKey("color")) {
      try {
        String c = f.get("color").replace("#", "");
        color = Integer.parseInt(c, 16);
      } catch (NumberFormatException ignored) {
      }
    }
    double brightness = parseDouble(f.get("brightness"), 100) / 100.0;
    double speed = parseDouble(f.get("speed"), 1.0);
    int linePusher = parseInt(f.get("linePusher"), 0);
    int lineStrip = parseInt(f.get("lineStrip"), 0);
    if (enabled && recorder.isPlaying()) {
      recorder.stopPlay(); // les tests et la lecture de sequence sont exclusifs
    }
    tests.configure(enabled, pattern, color, brightness, speed, linePusher, lineStrip);
    sendJson(ex, 200, "{\"ok\":true,\"testMode\":" + tests.isEnabled() + "}");
  }

  private void handleAction(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, "{\"ok\":false,\"error\":\"POST attendu\"}");
      return;
    }
    Map<String, String> f = parseForm(ex);
    String action = f.get("action");
    if ("remap".equals(action)) {
      core.remap(cfg.getColourOrder(), cfg.isPacking());
      sendJson(ex, 200, "{\"ok\":true}");
    } else if ("clearLogs".equals(action)) {
      LogBus.clear();
      sendJson(ex, 200, "{\"ok\":true}");
    } else if ("blackout".equals(action)) {
      if (tests.isEnabled()) {
        tests.configure(false, null, 0, 1, 1, 0, 0);
      }
      if (recorder.isPlaying()) {
        recorder.stopPlay();
      }
      core.blackoutAll();
      LogBus.info("Blackout manuel envoye depuis l'interface.");
      sendJson(ex, 200, "{\"ok\":true}");
    } else if ("stop".equals(action)) {
      sendJson(ex, 200, "{\"ok\":true,\"stopping\":true}");
      Main.scheduleShutdown(false);
    } else if ("restart".equals(action)) {
      sendJson(ex, 200, "{\"ok\":true,\"restarting\":true}");
      Main.scheduleShutdown(true);
    } else {
      sendJson(ex, 400, "{\"ok\":false,\"error\":\"Action inconnue\"}");
    }
  }

  private void handleLogDownload(HttpExchange ex) throws IOException {
    StringBuilder sb = new StringBuilder(65536);
    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    for (LogBus.Entry e : LogBus.getSince(0)) {
      sb.append(df.format(new java.util.Date(e.ts)))
        .append(" [").append(e.level).append("] ")
        .append(e.msg).append('\n');
    }
    byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    ex.getResponseHeaders().set("Content-Disposition",
        "attachment; filename=\"pixelpusher-bridge.log\"");
    ex.sendResponseHeaders(200, data.length);
    OutputStream os = ex.getResponseBody();
    os.write(data);
    os.close();
  }

  // ---------- SSE ----------

  private final class SseClient {
    private final HttpExchange exchange;
    private final OutputStream out;
    private volatile boolean dead = false;

    SseClient(HttpExchange exchange, OutputStream out) {
      this.exchange = exchange;
      this.out = out;
    }

    synchronized void send(String event, String data) {
      if (dead) {
        return;
      }
      try {
        out.write(("event: " + event + "\ndata: " + data + "\n\n")
            .getBytes(StandardCharsets.UTF_8));
        out.flush();
      } catch (IOException e) {
        close();
      }
    }

    synchronized void ping() {
      if (dead) {
        return;
      }
      try {
        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
      } catch (IOException e) {
        close();
      }
    }

    void close() {
      dead = true;
      sseClients.remove(this);
      try {
        exchange.close();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void handleSse(HttpExchange ex) throws IOException {
    if (sseClients.size() >= 20) {
      // garde-fou : evite une fuite de connexions si trop d'onglets ouverts
      sendJson(ex, 503, "{\"ok\":false,\"error\":\"Trop de clients connectes\"}");
      return;
    }
    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, 0);
    OutputStream os = ex.getResponseBody();
    SseClient client = new SseClient(ex, os);

    // historique d'abord, puis abonnement au flux
    List<LogBus.Entry> backlog = LogBus.getSince(0);
    for (LogBus.Entry e : backlog) {
      client.send("log", e.toJson());
    }
    sseClients.add(client);
    // la connexion reste ouverte : les ecritures suivantes viennent du listener LogBus
  }

  // ---------- utilitaires HTTP ----------

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream(32768);
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    in.close();
    return bos.toByteArray();
  }

  private static Map<String, String> parseForm(HttpExchange ex) throws IOException {
    byte[] raw = readAll(ex.getRequestBody());
    if (raw.length > 65536) {
      throw new IOException("Corps de requete trop volumineux");
    }
    String body = new String(raw, StandardCharsets.UTF_8);
    Map<String, String> map = new HashMap<String, String>();
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
      String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
      map.put(k, v);
    }
    return map;
  }

  private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
    sendText(ex, code, "application/json; charset=utf-8", json);
  }

  private static void sendText(HttpExchange ex, int code, String contentType, String body)
      throws IOException {
    byte[] data = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.sendResponseHeaders(code, data.length);
    OutputStream os = ex.getResponseBody();
    os.write(data);
    os.close();
  }

  private static int parseInt(String s, int def) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static double parseDouble(String s, double def) {
    try {
      return Double.parseDouble(s.trim());
    } catch (Exception e) {
      return def;
    }
  }
}
