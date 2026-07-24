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
        LogBus.error("Exception non geree dans le thread '" + t.getName() + "' : " + e);
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

    // options ligne de commande : --port NNNN, --restart-delay MS, --no-browser
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
          cfg.setWebPort(Integer.parseInt(args[i + 1]));
        } catch (NumberFormatException e) {
          LogBus.warn("Option --port invalide, ignoree : " + args[i + 1]);
        }
      } else if ("--restart-delay".equals(args[i])) {
        try {
          long ms = Long.parseLong(args[i + 1]);
          LogBus.info("Redemarrage : attente de " + ms + " ms le temps que l'ancienne instance libere les ports...");
          Thread.sleep(Math.min(ms, 10000));
        } catch (NumberFormatException e) {
          LogBus.warn("Option --restart-delay invalide, ignoree.");
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
        LogBus.info("Une instance de PixelPusher Bridge tourne deja (" + existing + ").");
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
      LogBus.error("Echec du demarrage du coeur Art-Net : " + e);
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
      LogBus.info("Reglages : frameLimit=" + cfg.getFrameLimit() + " Hz, autoThrottle="
          + cfg.isAutoThrottle() + ", extraDelay=" + cfg.getExtraDelayMs()
          + " ms, luminosite=" + Math.round(cfg.getBrightness() * 100) + "%, watchdog="
          + (cfg.getWatchdogSec() > 0 ? cfg.getWatchdogSec() + " s" : "off")
          + ", limite de puissance="
          + (cfg.getPowerLimitAmps() > 0 ? cfg.getPowerLimitAmps() + " A" : "off"));
    }

    // 3. scenarios de test + watchdog de signal
    tests = new TestPatterns(core);
    tests.start();
    Watchdog watchdog = new Watchdog(cfg, core, tests);
    watchdog.start();

    // 4. enregistreur de sequences + serveur web
    recorder = new Recorder(core);
    StatusService status = new StatusService(cfg, core, tests);
    status.setWatchdog(watchdog);
    status.setRecorder(recorder);
    WebServer web = new WebServer(cfg, core, tests, status, recorder);
    Diagnostic diagnostic = new Diagnostic(cfg, core, tests, recorder);
    web.setDiagnostic(diagnostic);
    try {
      web.start();
      status.setWebPort(web.getBoundPort());
      diagnostic.setWebPort(web.getBoundPort());
    } catch (IOException e) {
      LogBus.error("Impossible de demarrer le serveur web : " + e);
      LogBus.error("Le bridge Art-Net continue de fonctionner sans interface.");
    }

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      public void run() {
        LogBus.info("Arret de PixelPusher Bridge.");
      }
    }, "shutdown-log"));

    // 5. icone de barre systeme (point vert + menu clic droit)
    if (web.getBoundPort() > 0) {
      Tray.install(core, "http://localhost:" + web.getBoundPort() + "/");
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
    LogBus.info(restart ? "Redemarrage du bridge demande..." : "Arret du bridge demande...");
    Thread t = new Thread(new Runnable() {
      public void run() {
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
            core.blackoutAll();
            Thread.sleep(250); // laisse partir la trame noire
          }
          if (restart) {
            if (!relaunch()) {
              LogBus.error("Relance impossible : le bridge reste en marche.");
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
      ProcessBuilder pb = new ProcessBuilder(
          javaBin.getAbsolutePath(), "-jar", jar.getAbsolutePath(),
          "--restart-delay", "1500", "--no-browser");
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      pb.start();
      LogBus.info("Nouvelle instance lancee, arret de celle-ci.");
      return true;
    } catch (Exception e) {
      LogBus.error("Relance impossible : " + e);
      return false;
    }
  }

  /**
   * Cherche une instance deja active sur le port configure (et les 10 suivants,
   * au cas ou elle aurait glisse de port). Retourne son URL, ou null.
   */
  private static String detectRunningInstance(int basePort) {
    for (int p = basePort; p <= basePort + 10; p++) {
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
            LogBus.info("Interface ouverte en fenetre d'application (" + new File(p).getName() + ").");
            return;
          }
        }
        Runtime.getRuntime().exec(new String[] { "rundll32", "url.dll,FileProtocolHandler", url });
      } else if (os.contains("mac")) {
        if (new File("/Applications/Google Chrome.app").exists()) {
          Runtime.getRuntime().exec(new String[] {
              "open", "-na", "Google Chrome", "--args", "--app=" + url });
          LogBus.info("Interface ouverte en fenetre d'application (Chrome).");
          return;
        }
        if (new File("/Applications/Microsoft Edge.app").exists()) {
          Runtime.getRuntime().exec(new String[] {
              "open", "-na", "Microsoft Edge", "--args", "--app=" + url });
          LogBus.info("Interface ouverte en fenetre d'application (Edge).");
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
