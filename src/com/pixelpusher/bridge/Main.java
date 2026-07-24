package com.pixelpusher.bridge;

import java.io.File;
import java.io.IOException;

import com.heroicrobot.dropbit.registry.DeviceRegistry;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Point d'entree de PixelPusher Bridge.
 *
 * Demarre :
 *  1. le bus de logs (capture de tout ce que le code legacy affiche)
 *  2. le coeur legacy (discovery PixelPusher + reception Art-Net/sACN)
 *  3. le generateur de scenarios de test + le watchdog de signal
 *  4. le serveur web (interface de config / logs / monitoring)
 * puis ouvre le navigateur sur l'interface.
 */
public class Main {

  private static AppConfig cfg;
  private static LegacyCore core;
  private static TestPatterns tests;
  private static Recorder recorder;
  private static volatile boolean shutdownScheduled = false;

  public static void main(String[] args) {
    File dir = AppConfig.configDir();
    LogBus.install(dir);

    // Le logging java.util.logging du code legacy ecrit sur stderr par defaut
    // (compte comme erreur) et repete "Updating pusher from bcast." chaque
    // seconde. On le limite aux vrais avertissements, rediriges vers stdout.
    try {
      java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
      for (java.util.logging.Handler h : root.getHandlers()) {
        root.removeHandler(h);
      }
      java.util.logging.StreamHandler sh = new java.util.logging.StreamHandler(
          System.out, new java.util.logging.SimpleFormatter()) {
        @Override
        public synchronized void publish(java.util.logging.LogRecord r) {
          super.publish(r);
          flush();
        }
      };
      sh.setLevel(java.util.logging.Level.WARNING);
      root.addHandler(sh);
      root.setLevel(java.util.logging.Level.WARNING);
    } catch (RuntimeException ignored) {
    }

    // Toute exception non attrapee finit dans les logs au lieu de disparaitre.
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        LogBus.error("Exception non gérée dans le thread '" + t.getName() + "' : " + e);
        e.printStackTrace();
      }
    });

    LogBus.info("=====================================================");
    LogBus.info("PixelPusher Bridge v" + AppConfig.VERSION);
    LogBus.info("Java " + System.getProperty("java.version") + " / "
        + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    LogBus.info("Dossier de configuration : " + dir.getAbsolutePath());
    LogBus.info("=====================================================");

    cfg = AppConfig.load();

    // Options ligne de commande : --port NNNN, --restart-delay MS, --no-browser.
    // REGLE : une option de ligne de commande ne doit jamais etre ecrite dans
    // AppConfig, qui est persiste (et photographie par les presets). Elle vaut
    // pour le lancement courant seulement -> setWebPortOverride, pas setWebPort.
    boolean noBrowser = false;
    boolean isRestart = false;
    for (String a : args) {
      if ("--no-browser".equals(a)) {
        noBrowser = true;
      }
      if ("--restart-delay".equals(a)) {
        isRestart = true;
      }
    }
    for (int i = 0; i < args.length - 1; i++) {
      if ("--port".equals(args[i])) {
        try {
          int p = Integer.parseInt(args[i + 1].trim());
          if (cfg.setWebPortOverride(p)) {
            LogBus.info("Option --port " + p + " : port forcé pour ce lancement uniquement "
                + "(la configuration enregistrée n'est pas modifiée).");
          } else {
            LogBus.warn("Option --port hors plage 1-65535, ignorée : " + args[i + 1]);
          }
        } catch (NumberFormatException e) {
          LogBus.warn("Option --port invalide, ignorée : " + args[i + 1]);
        }
      } else if ("--restart-delay".equals(args[i])) {
        try {
          long ms = Long.parseLong(args[i + 1]);
          LogBus.info("Redémarrage : attente de " + ms + " ms le temps que l'ancienne instance libère les ports...");
          Thread.sleep(Math.min(ms, 10000));
        } catch (NumberFormatException e) {
          LogBus.warn("Option --restart-delay invalide, ignorée.");
        } catch (InterruptedException e) {
          // on continue
        }
      }
    }

    // Instance unique : si un bridge tourne deja sur cette machine, on ouvre
    // simplement son interface au lieu de creer un doublon (deux instances
    // pousseraient toutes les deux vers les pushers -> LED erratiques).
    if (!isRestart) {
      String existing = detectRunningInstance(cfg.getWebPort());
      if (existing != null) {
        LogBus.info("Une instance de PixelPusher Bridge tourne déjà (" + existing + ").");
        LogBus.info("Pas de doublon : ouverture de l'interface existante, puis fermeture.");
        if (!noBrowser) {
          openBrowser(existing);
        }
        System.exit(0);
      }
    }

    // 1. coeur legacy
    core = new LegacyCore();
    try {
      core.start(cfg.getColourOrder(), cfg.isPacking(), cfg.isDebug(), cfg.isSacnEnabled());
    } catch (RuntimeException e) {
      LogBus.error("Échec du démarrage du cœur Art-Net : " + e);
      e.printStackTrace();
    }

    // 2. reglages de fluidite / performance
    DeviceRegistry registry = core.getRegistry();
    if (registry != null) {
      // le logging interne du registre (1 message par annonce, chaque seconde)
      // n'est utile qu'en debug
      registry.setLogging(cfg.isDebug());
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
      DeviceRegistry.useOverallBrightnessScale = cfg.getBrightness() < 0.999;
      DeviceRegistry.setOverallBrightnessScale(cfg.getBrightness());
      LogBus.info("Réglages : frameLimit=" + cfg.getFrameLimit() + " Hz, autoThrottle="
          + cfg.isAutoThrottle() + ", extraDelay=" + cfg.getExtraDelayMs()
          + " ms, luminosité=" + Math.round(cfg.getBrightness() * 100) + "%, watchdog="
          + (cfg.getWatchdogSec() > 0 ? cfg.getWatchdogSec() + " s" : "off")
          + ", limite de puissance="
          + (cfg.getPowerLimitAmps() > 0 ? cfg.getPowerLimitAmps() + " A" : "off"));
    }

    // 3. scenarios de test + enregistreur + watchdog de signal
    tests = new TestPatterns(core);
    tests.start();
    recorder = new Recorder(core);
    Watchdog watchdog = new Watchdog(cfg, core, tests);
    // le watchdog doit connaitre le lecteur : pendant une lecture de sequence
    // aucune trame n'arrive du reseau, ce n'est pas une perte de signal
    watchdog.setRecorder(recorder);
    watchdog.start();

    // 4. serveur web
    StatusService status = new StatusService(cfg, core, tests);
    status.setWatchdog(watchdog);
    status.setRecorder(recorder);
    WebServer web = new WebServer(cfg, core, tests, status, recorder);
    status.setBlackout(web.getBlackout());
    Diagnostic diagnostic = new Diagnostic(cfg, core, tests, recorder);
    diagnostic.setBlackout(web.getBlackout());
    web.setDiagnostic(diagnostic);
    try {
      web.start();
      status.setWebPort(web.getBoundPort());
      diagnostic.setWebPort(web.getBoundPort());
    } catch (IOException e) {
      LogBus.error("Impossible de démarrer le serveur web : " + e);
      LogBus.error("Le bridge Art-Net continue de fonctionner sans interface.");
    }

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      public void run() {
        LogBus.info("Arrêt de PixelPusher Bridge.");
      }
    }, "shutdown-log"));

    // 5. icone de barre systeme (point vert + menu clic droit)
    if (web.getBoundPort() > 0) {
      Tray.install(core, web.getBlackout(), "http://localhost:" + web.getBoundPort() + "/");
    }

    if (web.getBoundPort() > 0 && cfg.isOpenBrowser() && !noBrowser) {
      openBrowser("http://localhost:" + web.getBoundPort() + "/");
    }
    // Les threads recepteurs (non-daemon) maintiennent la JVM en vie.
  }

  /** Acces public pour l'icone systeme. */
  public static void openBrowserPublic(String url) {
    openBrowser(url);
  }

  /**
   * Arret (ou redemarrage) demande depuis l'interface web.
   * Repond d'abord au client, puis : blackout optionnel -> relance eventuelle -> exit.
   */
  public static synchronized void scheduleShutdown(final boolean restart) {
    if (shutdownScheduled) {
      return;
    }
    shutdownScheduled = true;
    LogBus.info(restart ? "Redémarrage du bridge demandé..." : "Arrêt du bridge demandé...");
    Thread t = new Thread(new Runnable() {
      public void run() {
        boolean coupeIci = false; // vrai si c'est nous qui avons coupe l'entree DMX
        try {
          Thread.sleep(400); // laisse partir la reponse HTTP
          if (recorder != null) {
            recorder.stopRecord(); // ferme proprement un enregistrement en cours
            if (recorder.isPlaying()) {
              recorder.stopPlay();
            }
          }
          if (cfg != null && cfg.isBlackoutOnExit() && core != null) {
            if (tests != null && tests.isEnabled()) {
              tests.configure(false, null, 0, 1, 1, 0, 0);
            }
            // Couper l'entree AVANT d'ecrire les zeros : si la console continue
            // d'emettre, la trame suivante rallume tout avant que les CardThread
            // n'aient serialise le noir, et on confirmerait l'envoi d'une trame
            // qui n'a rien de noir (piege n.12 de DEVNOTES).
            core.setMuteDmx(true);
            coupeIci = true;
            core.blackoutAll();
            awaitBlackoutSent();
          }
          if (restart) {
            if (!relaunch()) {
              LogBus.error("Relance impossible : le bridge reste en marche.");
              // on avait coupe l'entree DMX pour garantir la trame noire :
              // puisque l'on continue, il faut imperativement la rendre a la
              // console, sinon le bridge resterait vivant mais sourd. On ne le
              // fait QUE si c'est nous qui l'avons coupee : un blackout
              // d'urgence verrouille ne doit pas etre leve par cet echec.
              if (coupeIci && core != null) {
                core.setMuteDmx(false);
                LogBus.info("Entrée DMX rétablie, la console reprend la main.");
              }
              shutdownScheduled = false;
              return;
            }
          }
        } catch (InterruptedException ignored) {
        }
        System.exit(0);
      }
    }, "shutdown");
    t.setDaemon(true);
    t.start();
  }

  /**
   * Attend que la trame noire soit reellement partie sur le reseau.
   *
   * blackoutAll() se contente d'ecrire des zeros dans les objets Strip :
   * l'emission UDP est le travail des CardThread, a la cadence configuree. Le
   * delai fixe de 250 ms qui suivait ne suffisait pas des que frameLimit descend
   * bas ou qu'extraDelay monte (l'application autorise 1 Hz et 1000 ms) : la JVM
   * sortait avant la trame noire et les rubans restaient figes sur la derniere
   * image, en pleine salle. On surveille donc le compteur de paquets reellement
   * emis par les CardThread avant de rendre la main.
   */
  private static void awaitBlackoutSent() {
    int rubans = 0;
    int pushers = 0;
    try {
      DeviceRegistry reg = core.getRegistry();
      if (reg != null) {
        if (reg.getStrips() != null) {
          rubans = reg.getStrips().size();
        }
        if (reg.getPushers() != null) {
          pushers = reg.getPushers().size();
        }
      }
    } catch (RuntimeException ignored) {
      // registre indisponible : on retombe sur l'attente minimale
    }
    if (rubans == 0 || pushers == 0) {
      return; // aucun ruban mappe : il n'y a rien a eteindre
    }
    // Cible : un datagramme par pusher, PAS un par ruban. Un CardThread empile
    // plusieurs rubans dans le meme datagramme (stripPerPacket) et ne renvoie
    // pas les rubans « non touches » : viser un paquet par ruban ne serait
    // jamais atteint des qu'un pusher en porte plus qu'il n'en tient dans un
    // datagramme, et l'arret trainerait toute l'attente maximale avant de
    // logger un faux « non confirme ». blackoutAll() marque tous les rubans
    // touches, donc chaque pusher emet au moins un paquet a la trame suivante.
    long attendus = pushers;
    long avant = com.heroicrobot.dropbit.devices.pixelpusher.CardThread.totalPacketsSent.get();
    long periodeMs = 1000L / Math.max(1, cfg.getFrameLimit()) + cfg.getExtraDelayMs();
    long attenteMax = Math.min(2500L, Math.max(300L, 4L * periodeMs));
    long limite = System.currentTimeMillis() + attenteMax;
    boolean envoye = false;
    while (System.currentTimeMillis() < limite) {
      if (com.heroicrobot.dropbit.devices.pixelpusher.CardThread.totalPacketsSent.get()
          - avant >= attendus) {
        envoye = true;
        break;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    try {
      Thread.sleep(100); // marge pour que les derniers datagrammes quittent la pile reseau
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (envoye) {
      LogBus.info("Trame noire confirmée envoyée aux pushers.");
    } else {
      LogBus.warn("Blackout non confirmé avant l'arrêt (aucun paquet émis en "
          + attenteMax + " ms) : vérifie l'état des rubans.");
    }
  }

  /** Relance une nouvelle instance de l'application (Mac + Windows). */
  private static boolean relaunch() {
    try {
      File jar = new File(Main.class.getProtectionDomain().getCodeSource()
          .getLocation().toURI());
      if (!jar.isFile()) {
        LogBus.error("Relance : jar introuvable (" + jar + ")");
        return false;
      }
      String javaHome = System.getProperty("java.home");
      boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
      File javaBin = new File(javaHome, windows ? "bin\\javaw.exe" : "bin/java");
      if (!javaBin.isFile()) {
        javaBin = new File(javaHome, windows ? "bin\\java.exe" : "bin/java");
      }
      // La nouvelle instance attend RESTART_DELAY_MS avant d'ouvrir le moindre
      // port : cela laisse a celle-ci le temps de verifier qu'elle a survecu,
      // PUIS de liberer ses ports. Ne pas descendre en dessous de
      // CHILD_CHECK_MS + une bonne marge, sinon les deux instances se
      // disputeraient le port Art-Net et le port web.
      ProcessBuilder pb = new ProcessBuilder(
          javaBin.getAbsolutePath(), "-jar", jar.getAbsolutePath(),
          "--restart-delay", String.valueOf(RESTART_DELAY_MS), "--no-browser");
      // Les sorties de l'enfant allaient dans Redirect.DISCARD : une mort
      // immediate (jar corrompu, classpath) ne laissait aucune trace nulle part
      // et il ne restait plus AUCUN bridge. On les garde dans un fichier.
      File trace = new File(AppConfig.configDir(), "relance.log");
      pb.redirectErrorStream(true);
      pb.redirectOutput(ProcessBuilder.Redirect.to(trace));
      Process child;
      try {
        child = pb.start();
      } catch (IOException io) {
        // fichier de trace impossible (dossier en lecture seule) : on relance
        // quand meme, la relance prime sur la tracabilite
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        child = pb.start();
      }

      // Verification minimale : l'enfant est-il encore vivant apres un instant ?
      // On ne peut pas attendre qu'il reponde sur son port web, car il ne peut
      // pas le prendre tant que celle-ci ne l'a pas relache.
      boolean mort;
      try {
        mort = child.waitFor(CHILD_CHECK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        mort = false;
      }
      if (mort) {
        LogBus.error("Relance : la nouvelle instance s'est arrêtée immédiatement (code "
            + child.exitValue() + "). Détails dans " + trace.getAbsolutePath());
        return false;
      }
      LogBus.info("Nouvelle instance lancée, arrêt de celle-ci.");
      return true;
    } catch (Exception e) {
      LogBus.error("Relance impossible : " + e);
      return false;
    }
  }

  /**
   * Delai laisse a la nouvelle instance avant qu'elle ouvre ses ports, et duree
   * pendant laquelle on verifie qu'elle n'est pas morte aussitot.
   */
  private static final int RESTART_DELAY_MS = 3000;
  private static final int CHILD_CHECK_MS = 1200;

  /**
   * Cherche une instance deja active sur le port configure (et les suivants, au
   * cas ou elle aurait glisse de port). Retourne son URL, ou null.
   *
   * La plage balayee ici DOIT rester identique a celle de WebServer.bind()
   * (voir AppConfig.PORT_SCAN_RANGE) : c'est cette coincidence, et elle seule,
   * qui garantit le verrou d'instance unique. Elargir une boucle sans l'autre
   * laisserait demarrer un second bridge qui pousserait vers les memes pushers.
   */
  private static String detectRunningInstance(int basePort) {
    for (int p = basePort; p <= basePort + AppConfig.PORT_SCAN_RANGE; p++) {
      try {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
            new java.net.URL("http://127.0.0.1:" + p + "/api/status").openConnection();
        c.setConnectTimeout(350);
        c.setReadTimeout(600);
        if (c.getResponseCode() == 200) {
          java.io.InputStream in = c.getInputStream();
          byte[] buf = new byte[300];
          int n = in.read(buf);
          in.close();
          String body = new String(buf, 0, Math.max(0, n),
              java.nio.charset.StandardCharsets.UTF_8);
          if (body.contains("\"version\"") && body.contains("\"uptimeSec\"")) {
            return "http://localhost:" + p + "/";
          }
        }
        c.disconnect();
      } catch (Exception ignored) {
        // rien sur ce port : on continue
      }
    }
    return null;
  }

  /**
   * Ouvre l'interface, de preference dans une fenetre d'application dediee
   * (mode --app d'Edge ou Chrome : pas de barre d'adresse, ressemble a un
   * vrai logiciel). Fermer cette fenetre n'arrete PAS le bridge.
   * Repli : navigateur par defaut.
   */
  private static void openBrowser(String url) {
    String os = System.getProperty("os.name", "").toLowerCase();
    try {
      if (os.contains("win")) {
        String[] candidates = {
            env("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            env("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            env("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
            env("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
        };
        for (String p : candidates) {
          if (new File(p).isFile()) {
            Runtime.getRuntime().exec(new String[] { p, "--app=" + url });
            LogBus.info("Interface ouverte en fenêtre d'application (" + new File(p).getName() + ").");
            return;
          }
        }
        Runtime.getRuntime().exec(new String[] { "rundll32", "url.dll,FileProtocolHandler", url });
      } else if (os.contains("mac")) {
        if (new File("/Applications/Google Chrome.app").exists()) {
          Runtime.getRuntime().exec(new String[] {
              "open", "-na", "Google Chrome", "--args", "--app=" + url });
          LogBus.info("Interface ouverte en fenêtre d'application (Chrome).");
          return;
        }
        if (new File("/Applications/Microsoft Edge.app").exists()) {
          Runtime.getRuntime().exec(new String[] {
              "open", "-na", "Microsoft Edge", "--args", "--app=" + url });
          LogBus.info("Interface ouverte en fenêtre d'application (Edge).");
          return;
        }
        Runtime.getRuntime().exec(new String[] { "open", url });
      } else {
        Runtime.getRuntime().exec(new String[] { "xdg-open", url });
      }
      LogBus.info("Ouverture du navigateur : " + url);
    } catch (IOException e) {
      LogBus.warn("Impossible d'ouvrir le navigateur automatiquement. Ouvre " + url);
    }
  }

  private static String env(String name) {
    String v = System.getenv(name);
    return v != null ? v : "";
  }
}
