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
  private final Blackout blackout;
  private Diagnostic diagnostic; // optionnel
  private HttpServer server;          // serveur du JDK (voie normale)
  private MiniHttpServer miniServer;  // serveur de secours (voir bind())
  private int boundPort = -1;
  private volatile boolean port80Bound = false;

  public void setDiagnostic(Diagnostic d) {
    this.diagnostic = d;
  }

  /** Etat de blackout partage avec l'icone systeme et le tableau de bord. */
  public Blackout getBlackout() {
    return blackout;
  }

  // clients SSE (flux de logs)
  private static final int SSE_MAX_CLIENTS = 20;
  /**
   * Trames en attente pour UN client avant qu'il ne soit considere perdu.
   * Doit rester superieur a la taille de l'historique du bus de logs (3000
   * lignes) : a la connexion, tout l'historique passe par cette file.
   */
  private static final int SSE_CLIENT_QUEUE = 4000;
  /** Duree sans aucune ecriture aboutie au-dela de laquelle un client est ferme. */
  private static final long SSE_STALL_MS = 30000;

  private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<SseClient>();
  private final ScheduledExecutorService pinger;
  /** File tampon entre le bus de logs et les clients SSE (voir startSseDispatcher). */
  private final java.util.concurrent.BlockingQueue<SseMsg> sseQueue =
      new java.util.concurrent.ArrayBlockingQueue<SseMsg>(2000);
  private final java.util.concurrent.atomic.AtomicLong sseDropped =
      new java.util.concurrent.atomic.AtomicLong();

  /** Ligne de log a diffuser : le JSON est construit une fois, le numero suit. */
  private static final class SseMsg {
    final long seq;
    final String json;

    SseMsg(long seq, String json) {
      this.seq = seq;
      this.json = json;
    }
  }

  /** Lignes de log non diffusees faute de place dans la file (diagnostic). */
  public long getSseDropped() {
    return sseDropped.get();
  }

  public WebServer(AppConfig cfg, LegacyCore core, TestPatterns tests, StatusService status,
      Recorder recorder) {
    this.cfg = cfg;
    this.core = core;
    this.tests = tests;
    this.status = status;
    this.recorder = recorder;
    this.blackout = new Blackout(core);
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

  /**
   * Reserve le port de l'interface.
   *
   * Deux implementations sont tentees dans l'ordre :
   *  1. le serveur du JDK (com.sun.net.httpserver), le plus eprouve ;
   *  2. a defaut le serveur de secours en sockets bloquantes (MiniHttpServer).
   *
   * Le point important est la distinction entre « port deja pris » et « le
   * serveur du JDK ne peut pas demarrer du tout ». Dans le second cas — typique
   * d'un pare-feu qui bloque la connexion en boucle locale dont NIO a besoin —
   * insister sur les ports suivants est contre-productif : HttpServer.create
   * reserve le port AVANT d'echouer et ne le relache pas, si bien que chaque
   * tentative fuit un port. On basculait alors sur onze ports condamnes, sans
   * interface, et le verrou d'instance unique ne detectait plus rien puisque
   * ces ports acceptent les connexions sans jamais repondre.
   */
  private void bind(int base) throws IOException {
    IOException lastError = null;
    if (jdkServerUsable()) {
      for (int p = base; p <= base + 10; p++) {
        try {
          server = HttpServer.create(new InetSocketAddress(p), 0);
          boundPort = p;
          return;
        } catch (IOException e) {
          lastError = e;
          if (isPortConflict(e)) {
            LogBus.warn("Port web " + p + " deja utilise, essai du suivant...");
            continue;
          }
          LogBus.warn("Le serveur web du systeme est indisponible (" + e.getMessage() + ").");
          break;
        }
      }
    } else {
      LogBus.warn("Le serveur web du systeme ne peut pas demarrer sur cette machine "
          + "(le selecteur reseau de Java est bloque).");
      LogBus.warn("Cause quasi certaine : un pare-feu ou un antivirus interdit les connexions "
          + "en boucle locale. Bascule sur le serveur de secours, sans perte de fonctionnalite.");
    }
    for (int p = base; p <= base + 10; p++) {
      try {
        miniServer = MiniHttpServer.create(new InetSocketAddress(p), 0);
        boundPort = miniServer.getPort();
        return;
      } catch (IOException e) {
        lastError = e;
      }
    }
    // Dernier recours : n'importe quel port libre. Mieux vaut une interface sur
    // un port inhabituel (le QR code et l'icone systeme donnent l'adresse) que
    // pas d'interface du tout.
    try {
      miniServer = MiniHttpServer.create(new InetSocketAddress(0), 0);
      boundPort = miniServer.getPort();
      LogBus.warn("Ports " + base + " a " + (base + 10) + " tous indisponibles : "
          + "l'interface demarre sur le port libre " + boundPort + ".");
      return;
    } catch (IOException e) {
      lastError = e;
    }
    throw new IOException("Aucun port web disponible entre " + base + " et " + (base + 10),
        lastError);
  }

  /**
   * Le serveur du JDK est-il utilisable ici ?
   *
   * Il repose sur java.nio Selector, qui ouvre une connexion en boucle locale
   * pour son mecanisme de reveil. Quand un pare-feu la bloque, HttpServer.create
   * reserve le port PUIS echoue sans le relacher : le port reste occupe pour
   * toute la duree du processus, il accepte les connexions sans jamais repondre,
   * et le verrou d'instance unique le prend pour un bridge en marche. On teste
   * donc le selecteur AVANT de reserver quoi que ce soit.
   */
  private static boolean jdkServerUsable() {
    java.nio.channels.Selector s = null;
    try {
      s = java.nio.channels.Selector.open();
      return true;
    } catch (Throwable e) {
      return false;
    } finally {
      if (s != null) {
        try {
          s.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  /** Distingue « ce port precis est pris » d'une panne generale du serveur. */
  private static boolean isPortConflict(IOException e) {
    if (e instanceof java.net.BindException) {
      return true;
    }
    String m = e.getMessage();
    return m != null && m.toLowerCase(java.util.Locale.ROOT).contains("address already in use");
  }

  /** Enregistre un endpoint sur l'implementation de serveur retenue. */
  private void route(String path, HttpHandler handler) {
    if (server != null) {
      server.createContext(path, handler);
    } else if (miniServer != null) {
      miniServer.createContext(path, handler);
    }
  }

  /** true si l'interface tourne sur le serveur de secours (affiche au diagnostic). */
  public boolean isFallbackServer() {
    return miniServer != null;
  }

  /** Demarre le serveur ; essaie le port configure puis les 10 suivants. */
  public void start() throws IOException {
    // Sans cette propriete, le serveur du JDK n'a AUCUN minuteur (valeur -1 par
    // defaut) : une connexion qui ouvre le socket puis n'envoie jamais la fin de
    // sa requete immobilise un thread web pour toujours. 20 s laissent tout le
    // temps a un client normal. Surtout ne pas poser maxRspTime : il couperait
    // le flux de logs de /api/logs, volontairement infini.
    // Doit etre pose AVANT le premier HttpServer.create (lecture statique).
    System.setProperty("sun.net.httpserver.maxReqTime", "20");

    bind(cfg.getWebPort());

    if (server != null) {
      // Pool borne : un pool sans plafond permet a n'importe quelle machine du
      // reseau de faire naitre des milliers de threads (une requete incomplete
      // par connexion) jusqu'a l'OutOfMemoryError. 24 threads suffisent, aucun
      // handler ne monopolise le sien - y compris celui du flux de logs, qui
      // rend la main des que le client est enregistre.
      server.setExecutor(Executors.newFixedThreadPool(24, new ThreadFactory() {
        private int n = 0;
        public synchronized Thread newThread(Runnable r) {
          Thread t = new Thread(r, "web-" + (n++));
          t.setDaemon(true);
          return t;
        }
      }));
    }

    route("/", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        serveStatic(ex);
      }
    });
    route("/api/status", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        sendJson(ex, 200, status.snapshotJson());
      }
    });
    route("/api/config", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
          handleConfigPost(ex);
        } else {
          sendJson(ex, 200, cfg.toJson());
        }
      }
    });
    route("/api/test", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleTestPost(ex);
      }
    });
    route("/api/action", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleAction(ex);
      }
    });
    route("/api/logs", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleSse(ex);
      }
    });
    route("/api/presets", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handlePresets(ex);
      }
    });
    route("/api/recorder", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleRecorder(ex);
      }
    });
    route("/api/dmx", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleDmxMonitor(ex);
      }
    });
    route("/qr.svg", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleQr(ex);
      }
    });
    route("/api/diagnostic/download", new SafeHandler() {
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
    route("/api/diagnostic", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        if (diagnostic == null) {
          sendJson(ex, 503, "{\"ok\":false}");
          return;
        }
        sendJson(ex, 200, diagnostic.toJson());
      }
    });
    route("/api/logs/download", new SafeHandler() {
      void doHandle(HttpExchange ex) throws IOException {
        handleLogDownload(ex);
      }
    });

    if (server != null) {
      server.start();
    } else {
      miniServer.start();
    }

    // Ping SSE toutes les 15 s pour garder les connexions ouvertes, et purge des
    // clients dont les ecritures n'avancent plus : un telephone sorti de portee
    // ne ferme pas sa connexion, il occupait une place jusqu'au redemarrage.
    // Le try/catch est indispensable : une exception ici annulerait la tache
    // periodique pour de bon (comportement de scheduleAtFixedRate).
    pinger.scheduleAtFixedRate(new Runnable() {
      public void run() {
        for (SseClient c : sseClients) {
          try {
            c.ping();
            c.closeIfStalled(SSE_STALL_MS);
          } catch (RuntimeException ignored) {
          }
        }
      }
    }, 15, 15, TimeUnit.SECONDS);

    // Diffusion des logs vers les clients SSE.
    //
    // ATTENTION, point critique : LogBus est alimente par TOUS les threads, y
    // compris celui qui recoit l'Art-Net (un paquet malforme ou un pixel hors
    // ruban y ecrit une ligne). Ecrire directement dans la socket d'un client
    // depuis ce thread le bloquerait des que le navigateur cesse de lire -
    // onglet en veille, telephone verrouille, WiFi qui decroche - et le flux
    // LED se figerait avec lui. On se contente donc de deposer la ligne dans
    // une file bornee, qu'un thread dedie vide a son rythme : le chemin
    // Art-Net -> pushers ne peut plus jamais attendre un navigateur.
    LogBus.addListener(new LogBus.Listener() {
      public void onLog(LogBus.Entry e) {
        if (!sseQueue.offer(new SseMsg(e.seq, e.toJson()))) {
          sseDropped.incrementAndGet();
        }
      }
    });
    startSseDispatcher();

    LogBus.info("Interface web disponible sur http://localhost:" + boundPort + "/");
    if (miniServer != null) {
      LogBus.warn("Interface servie par le serveur de secours : le serveur web du systeme "
          + "n'a pas pu demarrer sur cette machine. Toutes les fonctions sont disponibles.");
      LogBus.warn("Pour retablir le fonctionnement normal, autorise Java dans le pare-feu "
          + "ou l'antivirus (il bloque les connexions en boucle locale).");
    }

    // URL courte pour le telephone : un mini-serveur sur le port 80 redirige
    // vers le vrai port (http://IP/m marche alors sans taper :7350).
    // Best effort : sous Linux ou si le port est pris, on continue sans.
    if (boundPort != 80) {
      startRedirectServer();
    }
  }

  /** Redirection du port 80 vers le port reel de l'interface (URL courte telephone). */
  private void startRedirectServer() {
    final HttpHandler redirect = new SafeHandler() {
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
    };
    try {
      if (server != null) {
        HttpServer s80 = HttpServer.create(new InetSocketAddress(80), 0);
        s80.createContext("/", redirect);
        s80.setExecutor(server.getExecutor());
        s80.start();
      } else {
        // Meme raison que pour l'interface : si le serveur du JDK ne demarre
        // pas, la redirection doit passer par le serveur de secours.
        MiniHttpServer m80 = MiniHttpServer.create(new InetSocketAddress(80), 0);
        m80.createContext("/", redirect);
        m80.start();
      }
      port80Bound = true;
      status.setPort80(true);
      LogBus.info("URL courte activee : http://<ip-de-cette-machine>/m (redirige vers le port "
          + boundPort + ")");
    } catch (Exception e) {
      LogBus.info("Port 80 indisponible (" + e.getMessage()
          + ") : l'URL telephone garde le port :" + boundPort + " - le QR code s'en charge.");
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

  /**
   * Enveloppe commune a tous les endpoints.
   *
   * REGLE : aucun chemin ne doit quitter un handler sans avoir emis de reponse.
   * Un simple « return » laisserait l'echange ouvert, l'onglet du navigateur en
   * chargement jusqu'a son propre delai d'attente, et le thread web mobilise.
   */
  private abstract class SafeHandler implements HttpHandler {
    abstract void doHandle(HttpExchange ex) throws IOException;

    public final void handle(HttpExchange ex) {
      try {
        if (!checkOrigin(ex)) {
          return; // reponse 403 deja emise
        }
        doHandle(ex);
      } catch (RequestTooLargeException e) {
        try {
          sendJson(ex, 413, "{\"ok\":false,\"error\":\"Requête trop volumineuse\"}");
        } catch (IOException ignored) {
        }
      } catch (Exception e) {
        LogBus.error("Web: erreur sur " + ex.getRequestURI() + " : " + e);
        try {
          sendJson(ex, 500, "{\"ok\":false,\"error\":\"" + Json.esc(String.valueOf(e)) + "\"}");
        } catch (IOException ignored) {
        }
      }
    }
  }

  /**
   * Refuse les requetes d'ecriture emises par une autre page que l'interface.
   *
   * Sans ce controle, n'importe quel onglet ouvert sur l'ordinateur de regie
   * peut poster sur /api/action : un POST en form-urlencoded est une requete
   * « simple » au sens du navigateur, il part sans autorisation prealable, et
   * la page malveillante n'a meme pas besoin d'en lire la reponse pour arreter
   * le bridge en pleine representation.
   *
   * Le navigateur joint systematiquement un en-tete Origin aux POST. Comme
   * l'interface est servie par ce meme serveur, cet Origin correspond toujours
   * a l'en-tete Host : toute autre valeur vient d'une page tierce. Un Origin
   * absent est accepte - c'est le cas des outils en ligne de commande et des
   * scripts locaux, jamais celui d'une page web (aucune page ne peut supprimer
   * cet en-tete), donc accepter ne rouvre pas la faille.
   */
  private static boolean checkOrigin(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      return true;
    }
    String origin = ex.getRequestHeaders().getFirst("Origin");
    if (origin == null || origin.length() == 0) {
      return true;
    }
    String host = ex.getRequestHeaders().getFirst("Host");
    int sep = origin.indexOf("://");
    if (host != null && sep > 0 && origin.substring(sep + 3).equalsIgnoreCase(host)) {
      return true;
    }
    LogBus.warn("Commande refusée : elle provient d'une autre page (" + origin
        + "). Si c'est bien l'interface du bridge, recharge-la complètement.");
    sendJson(ex, 403, "{\"ok\":false,\"error\":\"Origine non autorisée\"}");
    return false;
  }

  /**
   * La requete vient-elle de l'ordinateur qui execute le bridge ?
   *
   * La boucle locale ne suffit pas. Sur la machine de regie, l'interface est
   * tres souvent ouverte par son adresse LAN et non par localhost : lien recopie
   * depuis le QR code, page laissee ouverte apres un changement de port, second
   * navigateur... L'adresse d'origine est alors celle de la carte reseau, et
   * refuser la demande donnerait un bouton « Arreter » qui ne fait rien, sans
   * explication visible. getByInetAddress ne repond que pour une adresse portee
   * par une interface de CETTE machine : la reponse reste donc bien « meme
   * ordinateur », jamais « meme reseau ».
   */
  private static boolean isLocalRequest(HttpExchange ex) {
    java.net.InetSocketAddress a = ex.getRemoteAddress();
    if (a == null || a.getAddress() == null) {
      return false;
    }
    java.net.InetAddress addr = a.getAddress();
    if (addr.isLoopbackAddress()) {
      return true;
    }
    try {
      return java.net.NetworkInterface.getByInetAddress(addr) != null;
    } catch (Exception e) {
      return false; // dans le doute on refuse : c'est le sens de ce garde-fou
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
      blackout.releaseFor("une séquence a été lancée");
      boolean ok = recorder.play(f.get("name"), loop);
      sendJson(ex, ok ? 200 : 404, "{\"ok\":" + ok + "}");
    } else if ("stopPlay".equals(action)) {
      recorder.stopPlay();
      blackout.reapplyIfActive();
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
    // Demander un test, c'est demander de la lumiere : on leve le blackout.
    // A l'inverse, sortir du mode test remet le silence si le blackout tient
    // toujours - sinon TestPatterns le levait sans le savoir.
    if (enabled) {
      blackout.releaseFor("un scénario de test a été lancé");
    }
    tests.configure(enabled, pattern, color, brightness, speed, linePusher, lineStrip);
    if (!enabled) {
      blackout.reapplyIfActive();
    }
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
      blackout.engage();
      sendJson(ex, 200, "{\"ok\":true,\"blackoutActive\":true}");
    } else if ("resume".equals(action)) {
      blackout.release();
      sendJson(ex, 200, "{\"ok\":true,\"blackoutActive\":false}");
    } else if ("stop".equals(action) || "restart".equals(action)) {
      // Arret et redemarrage sont les seules commandes dont on ne revient pas a
      // distance : une fois la JVM sortie, plus d'interface, il faut retourner
      // physiquement a la machine. Comme l'interface est joignable par tout le
      // reseau du lieu et sans mot de passe (c'est voulu : acces telephone par
      // QR code), on les reserve a l'ordinateur qui execute le bridge. Le
      // telephone n'en a pas besoin : il ne propose ni arret ni redemarrage.
      boolean restart = "restart".equals(action);
      if (!isLocalRequest(ex)) {
        LogBus.warn((restart ? "Redémarrage refusé" : "Arrêt refusé")
            + " : la demande ne vient pas de cet ordinateur.");
        sendJson(ex, 403, "{\"ok\":false,\"error\":\"L'arrêt et le redémarrage ne sont "
            + "autorisés que depuis l'ordinateur qui exécute le bridge.\"}");
        return;
      }
      sendJson(ex, 200, restart ? "{\"ok\":true,\"restarting\":true}"
          : "{\"ok\":true,\"stopping\":true}");
      Main.scheduleShutdown(restart);
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

  /**
   * Un navigateur abonne au flux de logs.
   *
   * Chaque client a sa propre file et son propre thread d'ecriture, et c'est
   * indispensable : la socket d'un client dont la liaison a disparu sans
   * fermeture propre (telephone hors de portee, WiFi coupe, veille) accepte
   * encore quelques dizaines de kilo-octets, puis BLOQUE l'ecriture le temps
   * que TCP renonce - plusieurs minutes. Avec une ecriture directe, ce seul
   * client figeait la diffusion pour tous les autres et gardait sa place
   * jusqu'au redemarrage. Ici il ne fige que son propre thread : sa file se
   * remplit, il est ferme, sa place est rendue.
   */
  private final class SseClient {
    private final HttpExchange exchange;
    private final OutputStream out;
    private final java.util.concurrent.BlockingQueue<String> outQueue =
        new java.util.concurrent.ArrayBlockingQueue<String>(SSE_CLIENT_QUEUE);
    private volatile boolean dead = false;
    /** Date de la derniere ecriture reellement aboutie (detection des liaisons mortes). */
    private volatile long lastWriteMs = System.currentTimeMillis();
    /** Dernier numero de sequence deja mis en file (protege par le moniteur). */
    private long lastSeq = 0;
    private Thread writer;

    SseClient(HttpExchange exchange, OutputStream out) {
      this.exchange = exchange;
      this.out = out;
    }

    /** Demarre le thread d'ecriture dedie a ce client. */
    void start() {
      writer = new Thread(new Runnable() {
        public void run() {
          while (!dead) {
            String frame;
            try {
              frame = outQueue.take();
            } catch (InterruptedException e) {
              return; // fermeture demandee
            }
            try {
              out.write(frame.getBytes(StandardCharsets.UTF_8));
              if (outQueue.isEmpty()) {
                out.flush(); // un seul flush par rafale
              }
              lastWriteMs = System.currentTimeMillis();
            } catch (IOException e) {
              close();
              return;
            }
          }
        }
      }, "sse-out");
      writer.setDaemon(true);
      writer.start();
    }

    /**
     * Met une trame en file d'envoi. Deux garde-fous :
     *  - un numero de sequence deja traite est ignore, car le rattrapage de
     *    l'historique et la diffusion en direct se chevauchent a la connexion ;
     *  - une file pleine signifie que le client n'absorbe plus rien : on le
     *    ferme au lieu de le garder indefiniment.
     */
    synchronized void offer(long seq, String frame) {
      if (dead) {
        return;
      }
      if (seq > 0) {
        if (seq <= lastSeq) {
          return;
        }
        lastSeq = seq;
      }
      if (!outQueue.offer(frame)) {
        close();
      }
    }

    void send(long seq, String event, String data) {
      // Le champ « id » permet au navigateur de reprendre ou il en etait apres
      // une coupure (en-tete Last-Event-ID) au lieu de redemander tout
      // l'historique a chaque reconnexion.
      offer(seq, (seq > 0 ? "id: " + seq + "\n" : "")
          + "event: " + event + "\ndata: " + data + "\n\n");
    }

    void ping() {
      offer(0, ": ping\n\n");
    }

    boolean isDead() {
      return dead;
    }

    /** Bloque le thread appelant tant que ce client vit (voir handleSse). */
    void awaitClose() {
      Thread w = writer;
      if (w == null) {
        return;
      }
      try {
        w.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /** Ferme un client dont les ecritures n'avancent plus depuis trop longtemps. */
    void closeIfStalled(long maxSilentMs) {
      if (!dead && !outQueue.isEmpty()
          && System.currentTimeMillis() - lastWriteMs > maxSilentMs) {
        close();
      }
    }

    synchronized void close() {
      if (dead) {
        return;
      }
      dead = true;
      sseClients.remove(this);
      outQueue.clear();
      if (writer != null) {
        writer.interrupt();
      }
      // Fermer l'echange vide le tampon de sortie, donc ECRIT dans la socket :
      // sur une liaison morte cette ecriture bloque le temps que TCP renonce,
      // plusieurs minutes. Or close() est appele depuis le thread de diffusion
      // (file pleine) et depuis celui des pings (purge des clients bloques) :
      // le faire sur place figerait le journal de tous les autres clients,
      // exactement ce que cette classe s'emploie a eviter. On confie donc la
      // fermeture a un thread jetable - au plus un par client, et il n'y a
      // jamais plus de SSE_MAX_CLIENTS clients. Bonus : c'est cette fermeture
      // qui debloque le thread d'ecriture s'il est coince dans un write().
      Thread closer = new Thread(new Runnable() {
        public void run() {
          try {
            exchange.close();
          } catch (RuntimeException ignored) {
          }
        }
      }, "sse-close");
      closer.setDaemon(true);
      try {
        closer.start();
      } catch (RuntimeException ignored) {
        // impossible de creer le thread : la place du client est deja rendue
      }
    }
  }

  /**
   * Thread unique qui vide la file de logs vers les clients SSE.
   * Si un client est lent, c'est ce thread qui attend - jamais le reseau Art-Net.
   */
  private void startSseDispatcher() {
    Thread t = new Thread(new Runnable() {
      public void run() {
        while (true) {
          SseMsg m;
          try {
            m = sseQueue.take();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          for (SseClient c : sseClients) {
            try {
              c.send(m.seq, "log", m.json);
            } catch (RuntimeException e) {
              // un client defaillant ne doit jamais interrompre la diffusion
            }
          }
        }
      }
    }, "sse-dispatch");
    t.setDaemon(true);
    t.start();
  }

  private void handleSse(HttpExchange ex) throws IOException {
    if (sseClients.size() >= SSE_MAX_CLIENTS) {
      // garde-fou : evite une fuite de connexions si trop d'onglets ouverts
      sendJson(ex, 503, "{\"ok\":false,\"error\":\"Trop de clients connectes\"}");
      return;
    }
    // Reprise apres coupure : le navigateur renvoie de lui-meme le dernier
    // identifiant recu, on ne lui reexpedie donc que ce qui lui manque.
    long since = parseLong(ex.getRequestHeaders().getFirst("Last-Event-ID"), 0);
    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, 0);
    OutputStream os = ex.getResponseBody();
    SseClient client = new SseClient(ex, os);
    client.start();

    // Historique, abonnement, puis rattrapage de ce qui a ete produit PENDANT
    // l'envoi de l'historique : sans ce dernier passage, toutes ces lignes
    // etaient definitivement perdues pour ce client. Les doublons eventuels
    // avec la diffusion en direct sont ecartes par le numero de sequence.
    long delivered = since;
    List<LogBus.Entry> backlog = LogBus.getSince(since);
    for (LogBus.Entry e : backlog) {
      client.send(e.seq, "log", e.toJson());
      delivered = e.seq;
    }
    if (client.isDead()) {
      return;
    }
    sseClients.add(client);
    for (LogBus.Entry e : LogBus.getSince(delivered)) {
      client.send(e.seq, "log", e.toJson());
    }
    if (client.isDead()) {
      sseClients.remove(client);
      return;
    }
    // La connexion reste ouverte : les ecritures suivantes viennent du dispatcher.
    //
    // Sauf sur le serveur de secours : celui-ci fonctionne en « un thread par
    // connexion » et ferme la socket des que le handler rend la main, ce qui
    // couperait le journal au bout d'une ligne. On tient donc la ligne ici
    // jusqu'a la fin du client. C'est sans danger : ce serveur accepte 64
    // connexions et le nombre de clients du journal est plafonne a 20.
    // Sur le serveur du JDK, l'echange survit au handler : y attendre
    // immobiliserait pour rien un thread du pool web.
    if (miniServer != null) {
      client.awaitClose();
    }
  }

  // ---------- utilitaires HTTP ----------

  /** Taille maximale acceptee pour un formulaire (les notres font quelques centaines d'octets). */
  private static final int MAX_FORM_BYTES = 65536;

  /** Corps de requete trop volumineux : SafeHandler le traduit en HTTP 413. */
  private static final class RequestTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    RequestTooLargeException(String message) {
      super(message);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    return readAll(in, Integer.MAX_VALUE);
  }

  /**
   * Lit un flux en s'arretant des que la limite est franchie.
   *
   * La verification de taille doit se faire PENDANT la lecture : la controler
   * apres coup laissait un client malveillant (ou un script qui deraille) faire
   * grossir la memoire du bridge sans limite avant qu'on ne rejette la requete.
   */
  private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      if (bos.size() + n > maxBytes) {
        in.close();
        throw new RequestTooLargeException(
            "Corps de requete trop volumineux (limite " + maxBytes + " octets)");
      }
      bos.write(buf, 0, n);
    }
    in.close();
    return bos.toByteArray();
  }

  private static Map<String, String> parseForm(HttpExchange ex) throws IOException {
    // Quand la taille est annoncee, on refuse avant meme de lire le corps :
    // inutile de faire transiter des mega-octets pour les rejeter ensuite.
    long declared = parseLong(ex.getRequestHeaders().getFirst("Content-Length"), -1);
    if (declared > MAX_FORM_BYTES) {
      throw new RequestTooLargeException("Corps de requete annonce a " + declared + " octets");
    }
    byte[] raw = readAll(ex.getRequestBody(), MAX_FORM_BYTES);
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

  private static long parseLong(String s, long def) {
    try {
      return Long.parseLong(s.trim());
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
