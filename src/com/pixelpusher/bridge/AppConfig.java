package com.pixelpusher.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration persistante de l'application.
 * Stockee dans ~/.pixelpusherbridge/config.properties (survit aux mises a jour).
 */
public class AppConfig {

  public static final String VERSION = "1.6.0";

  private final Properties props = new Properties();
  private final File file;

  /** Copie de secours de la derniere configuration valide (voir save()). */
  private static final String BACKUP_NAME = "config.properties.bak";

  // ----- valeurs par defaut -----
  public static final int DEF_WEB_PORT = 7350;
  public static final String DEF_ORDER = "RGB";

  /**
   * Nombre de ports essayes apres le port configure quand celui-ci est occupe.
   *
   * INVARIANT : WebServer.bind() et Main.detectRunningInstance() DOIVENT balayer
   * exactement la meme plage (port configure -> port configure + PORT_SCAN_RANGE).
   * Si l'une des deux boucles est elargie sans l'autre, le verrou d'instance
   * unique casse en silence : un second lancement ne voit pas l'instance deja en
   * marche et deux bridges poussent simultanement vers les memes pushers, ce qui
   * rend les LED erratiques (piege n.3 de DEVNOTES.md, deja diagnostique une fois).
   */
  public static final int PORT_SCAN_RANGE = 10;

  /**
   * Surcharge de session du port web (option --port) : elle s'applique au
   * lancement courant mais n'est JAMAIS ecrite dans config.properties ni
   * photographiee dans un preset.
   *
   * Regle generale : une option de ligne de commande ne doit pas modifier la
   * configuration persistante de l'utilisateur. Avant, --port appelait
   * setWebPort() sur l'objet partage ; la premiere sauvegarde faite depuis
   * l'interface rendait le port de depannage definitif, et tous les raccourcis
   * de l'equipe pointaient alors vers le mauvais port.
   */
  private volatile int webPortOverride = -1;

  private AppConfig(File file) {
    this.file = file;
  }

  public static File configDir() {
    File dir = new File(System.getProperty("user.home"), ".pixelpusherbridge");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  /**
   * Instance isolee adossee au fichier fourni, sans passer par le dossier de
   * configuration de l'utilisateur. Reservee aux tests, qui doivent pouvoir
   * verifier les conversions et le bornage sans toucher a la vraie config.
   */
  static AppConfig forFile(File f) {
    return new AppConfig(f);
  }

  /**
   * Charge la configuration. Ne peut jamais empecher le demarrage.
   *
   * Properties.load ne leve pas seulement des IOException : une sequence
   * d'echappement invalide (un antislash suivi de « u » sans quatre chiffres
   * hexadecimaux, par exemple un chemin Windows colle a la main) provoque une
   * IllegalArgumentException. Elle n'etait pas rattrapee et remontait jusqu'au
   * main, qui mourait avant meme d'avoir demarre le coeur reseau : l'application
   * ne se lancait plus du tout, sans aucun message. Un fichier illisible (ou
   * vide, cas d'une ecriture interrompue) est desormais mis de cote ; on repart
   * si possible sur la copie de secours ecrite par save(), sinon sur les valeurs
   * par defaut.
   */
  public static AppConfig load() {
    File dir = configDir();
    File f = new File(dir, "config.properties");
    File bak = new File(dir, BACKUP_NAME);
    AppConfig cfg = new AppConfig(f);

    if (!f.exists()) {
      return cfg; // premier lancement : rien a signaler
    }

    String probleme;
    if (f.length() == 0) {
      // Fichier de taille nulle : une ecriture a ete interrompue (coupure de
      // courant, Stop-Process -Force pendant la sauvegarde).
      probleme = "fichier vide, écriture interrompue";
    } else {
      probleme = tryLoad(cfg.props, f);
      if (probleme == null) {
        return cfg; // cas normal
      }
    }

    // Le fichier principal est inutilisable : on le met de cote, puis on tente
    // la copie de secours ecrite par save() avant chaque remplacement.
    cfg.props.clear();
    File quarantaine = new File(dir, "config.properties.illisible-" + System.currentTimeMillis());
    // un fichier vide n'a rien a conserver : on le supprime au lieu de l'archiver
    boolean deplace = f.length() > 0 && f.renameTo(quarantaine);
    String msg = "Le fichier de configuration était inutilisable (" + probleme + ")."
        + (deplace ? " L'ancien fichier est conservé sous " + quarantaine.getName() + "." : "");
    if (!deplace) {
      // sinon la copie de secours faite par save() ci-dessous ecraserait le .bak
      // (notre seule version saine) avec le fichier corrompu
      f.delete();
    }

    if (bak.isFile() && bak.length() > 0 && tryLoad(cfg.props, bak) == null) {
      msg += " La sauvegarde précédente (" + BACKUP_NAME + ") a été restaurée.";
      cfg.save(); // on reecrit immediatement un config.properties valide
    } else {
      cfg.props.clear();
      msg += " Les valeurs par défaut ont été rétablies.";
    }
    corruptionMessage = msg;
    System.err.println("Config : " + corruptionMessage);
    return cfg;
  }

  /**
   * Charge un fichier de proprietes dans dest. Retourne null si tout s'est bien
   * passe, sinon une description de l'echec. En cas d'echec dest n'est pas
   * modifie (on charge dans un tampon avant de recopier), ce qui permet
   * d'enchainer plusieurs tentatives sans melanger des cles a moitie lues.
   */
  private static String tryLoad(Properties dest, File f) {
    InputStream in = null;
    try {
      Properties tampon = new Properties();
      in = new FileInputStream(f);
      tampon.load(in);
      dest.putAll(tampon);
      return null;
    } catch (Exception e) {
      // Properties.load ne leve pas que des IOException : une sequence
      // d'echappement invalide provoque une IllegalArgumentException.
      return String.valueOf(e);
    } finally {
      closeQuietly(in);
    }
  }

  /** Message a afficher si la configuration a du etre reinitialisee, sinon null. */
  private static volatile String corruptionMessage = null;

  public static String getCorruptionMessage() {
    return corruptionMessage;
  }

  /**
   * Sauvegarde atomique.
   *
   * L'ancienne version ouvrait un FileOutputStream directement sur le fichier
   * cible : celui-ci etait donc tronque AVANT l'ecriture, et toute interruption
   * dans cette fenetre (coupure, Stop-Process -Force lance par BUILD.bat ou par
   * le raccourci d'arret) laissait une configuration vide. Au relancement,
   * ordre des couleurs, luminosite et frameLimit revenaient aux valeurs par
   * defaut sans le moindre message.
   *
   * On ecrit desormais dans un fichier temporaire, on le force sur le disque,
   * on conserve la version precedente en .bak, puis on remplace la cible par un
   * renommage (atomique si le systeme de fichiers le permet).
   */
  public synchronized void save() {
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    File tmp = new File(parent, file.getName() + ".tmp");
    FileOutputStream out = null;
    try {
      out = new FileOutputStream(tmp);
      props.store(out, "PixelPusher Bridge - configuration");
      out.flush();
      try {
        out.getFD().sync(); // les octets sont reellement sur le disque
      } catch (java.io.SyncFailedException ignored) {
        // certains supports ne savent pas forcer : sans gravite
      }
      out.close();
      out = null;

      // copie de secours de la version precedente, avant de la remplacer
      if (file.isFile() && file.length() > 0) {
        try {
          java.nio.file.Files.copy(file.toPath(), new File(parent, BACKUP_NAME).toPath(),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
          // pas de copie de secours possible : on continue quand meme
        }
      }
      replace(tmp, file);
    } catch (IOException e) {
      System.err.println("Config : sauvegarde impossible : " + e);
      // fermer AVANT de supprimer : sous Windows la suppression d'un fichier
      // encore ouvert echoue et laisse trainer un .tmp
      closeQuietly(out);
      out = null;
      tmp.delete();
    } catch (RuntimeException e) {
      System.err.println("Config : sauvegarde impossible : " + e);
      closeQuietly(out);
      out = null;
      tmp.delete();
    } finally {
      closeQuietly(out);
    }
  }

  /** Remplace cible par tmp, en privilegiant un renommage atomique. */
  private static void replace(File tmp, File cible) throws IOException {
    try {
      java.nio.file.Files.move(tmp.toPath(), cible.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      return;
    } catch (Exception e) {
      // ATOMIC_MOVE n'est pas garanti partout (partages reseau, certains FS)
    }
    try {
      java.nio.file.Files.move(tmp.toPath(), cible.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return;
    } catch (Exception e) {
      // dernier recours ci-dessous
    }
    if (cible.exists() && !cible.delete()) {
      throw new IOException("remplacement de " + cible.getName() + " impossible (suppression)");
    }
    if (!tmp.renameTo(cible)) {
      throw new IOException("remplacement de " + cible.getName() + " impossible (renommage)");
    }
  }

  /** Copie des proprietes courantes (pour les presets). */
  public synchronized Properties snapshot() {
    Properties p = new Properties();
    p.putAll(props);
    return p;
  }

  /** Remplace toute la configuration (chargement d'un preset). */
  public synchronized void replaceWith(Properties p) {
    props.clear();
    props.putAll(p);
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c != null) {
      try {
        c.close();
      } catch (IOException ignored) {
      }
    }
  }

  // ----- helpers types -----
  private int getInt(String key, int def) {
    String raw = props.getProperty(key);
    if (raw == null) {
      return def;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      signaler(key, raw);
      return def;
    }
  }

  /**
   * Lecture d'un reel. Refuse NaN et les infinis : Double.parseDouble("NaN")
   * reussit, clampD(NaN, 0, 1) renvoie NaN (Math.min/max propagent NaN) et
   * toJson produisait alors "brightness":NaN, litteral que JSON.parse refuse.
   * L'interface web restait figee au chargement alors que le bridge, lui,
   * fonctionnait. Une valeur non finie est traitee comme une valeur illisible.
   */
  private double getDouble(String key, double def) {
    String raw = props.getProperty(key);
    if (raw == null) {
      return def;
    }
    try {
      double v = Double.parseDouble(raw.trim());
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        signaler(key, raw);
        return def;
      }
      return v;
    } catch (NumberFormatException e) {
      signaler(key, raw);
      return def;
    }
  }

  /**
   * Lecture d'un booleen tolerante. Boolean.parseBoolean renvoie false pour
   * tout ce qui n'est pas "true" : un fichier edite a la main avec
   * blackoutOnExit=1 desactivait donc silencieusement le blackout de sortie, et
   * packing=1 cassait le mapping. On accepte les ecritures courantes et, pour
   * toute valeur inconnue, on revient a la valeur par defaut plutot qu'a false.
   */
  private boolean getBool(String key, boolean def) {
    String raw = props.getProperty(key);
    if (raw == null) {
      return def;
    }
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (v.equals("true") || v.equals("1") || v.equals("oui") || v.equals("yes") || v.equals("on")) {
      return true;
    }
    if (v.equals("false") || v.equals("0") || v.equals("non") || v.equals("no") || v.equals("off")) {
      return false;
    }
    signaler(key, raw);
    return def;
  }

  private void set(String key, Object value) {
    props.setProperty(key, String.valueOf(value));
  }

  /**
   * Ecriture d'un reel : une valeur non finie n'est jamais persistee (elle
   * rendrait /api/config invalide). On garde la valeur precedente.
   */
  private void setD(String key, double v, double min, double max) {
    if (Double.isNaN(v) || Double.isInfinite(v)) {
      signaler(key, String.valueOf(v));
      return;
    }
    set(key, clampD(v, min, max));
  }

  /** Signale une fois par cle une valeur inexploitable, sans noyer les logs. */
  private static void signaler(String key, String raw) {
    if (signalees.add(key)) {
      System.out.println("Config : valeur inattendue pour " + key + " (\"" + raw
          + "\"), valeur par défaut appliquée.");
    }
  }

  private static final java.util.Set<String> signalees =
      java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

  // ----- proprietes -----
  /** Port d'ecoute effectif : surcharge de session (--port) si presente, sinon config. */
  public int getWebPort() {
    int o = webPortOverride;
    return o > 0 ? o : getInt("webPort", DEF_WEB_PORT);
  }
  public void setWebPort(int v) { set("webPort", v); }

  /** Port enregistre dans le fichier de configuration, sans tenir compte de --port. */
  public int getStoredWebPort() { return getInt("webPort", DEF_WEB_PORT); }

  /**
   * Applique la surcharge de session demandee en ligne de commande.
   * Valeur hors 1-65535 : surcharge ignoree (retourne false).
   */
  public boolean setWebPortOverride(int v) {
    if (v < 1 || v > 65535) {
      return false;
    }
    webPortOverride = v;
    return true;
  }

  public boolean hasWebPortOverride() { return webPortOverride > 0; }

  public String getColourOrder() {
    String v = props.getProperty("colourOrder", DEF_ORDER).trim().toUpperCase();
    if (!v.matches("RGB|RBG|GRB|GBR|BRG|BGR")) {
      return DEF_ORDER;
    }
    return v;
  }
  public void setColourOrder(String v) { set("colourOrder", v.toUpperCase()); }

  public boolean isPacking() { return getBool("packing", true); }
  public void setPacking(boolean v) { set("packing", v); }

  public boolean isDebug() { return getBool("debug", false); }
  public void setDebug(boolean v) { set("debug", v); }

  public boolean isSacnEnabled() { return getBool("sacnEnabled", true); }
  public void setSacnEnabled(boolean v) { set("sacnEnabled", v); }

  public boolean isAutoThrottle() { return getBool("autoThrottle", false); }
  public void setAutoThrottle(boolean v) { set("autoThrottle", v); }

  /**
   * Limite de puissance electrique globale, en amperes (0 = desactivee).
   * Au-dela de cette limite le bridge attenue proportionnellement toutes les LED,
   * plutot que de laisser l'alimentation s'effondrer (chute de tension, couleurs
   * qui virent, protection qui coupe en pleine representation).
   */
  public double getPowerLimitAmps() { return clampD(getDouble("powerLimitAmps", 0), 0, 2000); }
  public void setPowerLimitAmps(double v) { setD("powerLimitAmps", v, 0, 2000); }

  /**
   * Consommation d'un canal de couleur allume a fond, en milliamperes.
   * 20 mA est la valeur typique des rubans WS2812 / APA102 : un pixel RGB en
   * blanc plein consomme donc environ 60 mA. Ne sert qu'a convertir la limite
   * saisie en amperes vers les « unites de luminance » du coeur legacy.
   */
  public double getMilliampsPerChannel() {
    return clampD(getDouble("milliampsPerChannel", 20.0), 1.0, 200.0);
  }
  public void setMilliampsPerChannel(double v) { setD("milliampsPerChannel", v, 1.0, 200.0); }

  /**
   * Limite exprimee dans l'unite attendue par DeviceRegistry.setTotalPowerLimit :
   * 255 unites = un canal de couleur d'un pixel allume a fond.
   * Renvoie -1 quand la limite est desactivee (valeur « pas de limite » du legacy).
   */
  public long getPowerLimitUnits() {
    double amps = getPowerLimitAmps();
    if (amps <= 0) {
      return -1;
    }
    return Math.round(amps * 1000.0 / getMilliampsPerChannel() * 255.0);
  }

  public int getFrameLimit() { return clamp(getInt("frameLimit", 85), 1, 1000); }
  public void setFrameLimit(int v) { set("frameLimit", clamp(v, 1, 1000)); }

  public int getExtraDelayMs() { return clamp(getInt("extraDelayMs", 0), 0, 1000); }
  public void setExtraDelayMs(int v) { set("extraDelayMs", clamp(v, 0, 1000)); }

  public boolean isAntiLog() { return getBool("antiLog", false); }
  public void setAntiLog(boolean v) { set("antiLog", v); }

  public double getBrightness() { return clampD(getDouble("brightness", 1.0), 0.0, 1.0); }
  public void setBrightness(double v) { setD("brightness", v, 0.0, 1.0); }

  public boolean isOpenBrowser() { return getBool("openBrowser", true); }
  public void setOpenBrowser(boolean v) { set("openBrowser", v); }

  public boolean isExpiryEnabled() { return getBool("expiryEnabled", true); }
  public void setExpiryEnabled(boolean v) { set("expiryEnabled", v); }

  /** Watchdog : blackout automatique apres N secondes sans donnees DMX (0 = desactive). */
  public int getWatchdogSec() { return clamp(getInt("watchdogSec", 0), 0, 300); }
  public void setWatchdogSec(int v) { set("watchdogSec", clamp(v, 0, 300)); }

  /** Blackout des LED quand on arrete le bridge (sinon l'image reste figee). */
  public boolean isBlackoutOnExit() { return getBool("blackoutOnExit", true); }
  public void setBlackoutOnExit(boolean v) { set("blackoutOnExit", v); }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  private static double clampD(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  /** Serialisation JSON pour l'interface web. */
  public synchronized String toJson() {
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    sb.append("\"version\":\"").append(VERSION).append("\",");
    sb.append("\"webPort\":").append(getWebPort()).append(',');
    sb.append("\"colourOrder\":\"").append(getColourOrder()).append("\",");
    sb.append("\"packing\":").append(isPacking()).append(',');
    sb.append("\"debug\":").append(isDebug()).append(',');
    sb.append("\"sacnEnabled\":").append(isSacnEnabled()).append(',');
    sb.append("\"autoThrottle\":").append(isAutoThrottle()).append(',');
    sb.append("\"powerLimitAmps\":").append(getPowerLimitAmps()).append(',');
    sb.append("\"milliampsPerChannel\":").append(getMilliampsPerChannel()).append(',');
    sb.append("\"frameLimit\":").append(getFrameLimit()).append(',');
    sb.append("\"extraDelayMs\":").append(getExtraDelayMs()).append(',');
    sb.append("\"antiLog\":").append(isAntiLog()).append(',');
    sb.append("\"brightness\":").append(getBrightness()).append(',');
    sb.append("\"openBrowser\":").append(isOpenBrowser()).append(',');
    sb.append("\"expiryEnabled\":").append(isExpiryEnabled()).append(',');
    sb.append("\"watchdogSec\":").append(getWatchdogSec()).append(',');
    sb.append("\"blackoutOnExit\":").append(isBlackoutOnExit());
    sb.append('}');
    return sb.toString();
  }
}
