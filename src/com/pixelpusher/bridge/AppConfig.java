package com.pixelpusher.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Configuration persistante de l'application.
 * Stockee dans ~/.pixelpusherbridge/config.properties (survit aux mises a jour).
 */
public class AppConfig {

  public static final String VERSION = "1.6.0";

  private final Properties props = new Properties();
  private final File file;

  // ----- valeurs par defaut -----
  public static final int DEF_WEB_PORT = 7350;
  public static final String DEF_ORDER = "RGB";

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
   * ne se lancait plus du tout, sans aucun message. Un fichier illisible est
   * desormais mis de cote et l'application repart sur ses valeurs par defaut.
   */
  public static AppConfig load() {
    File f = new File(configDir(), "config.properties");
    AppConfig cfg = new AppConfig(f);
    if (f.exists()) {
      InputStream in = null;
      try {
        in = new FileInputStream(f);
        cfg.props.load(in);
      } catch (Exception e) {
        closeQuietly(in);
        in = null;
        cfg.props.clear();
        File quarantaine = new File(configDir(),
            "config.properties.illisible-" + System.currentTimeMillis());
        boolean deplace = f.renameTo(quarantaine);
        corruptionMessage = "Le fichier de configuration etait illisible (" + e
            + "). Les valeurs par defaut ont ete retablies."
            + (deplace ? " L'ancien fichier est conserve sous " + quarantaine.getName() + "." : "");
        System.err.println("Config: " + corruptionMessage);
      } finally {
        closeQuietly(in);
      }
    }
    return cfg;
  }

  /** Message a afficher si la configuration a du etre reinitialisee, sinon null. */
  private static volatile String corruptionMessage = null;

  public static String getCorruptionMessage() {
    return corruptionMessage;
  }

  public synchronized void save() {
    OutputStream out = null;
    try {
      out = new FileOutputStream(file);
      props.store(out, "PixelPusher Bridge - configuration");
    } catch (IOException e) {
      System.err.println("Config: sauvegarde impossible : " + e);
    } finally {
      closeQuietly(out);
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
    try {
      return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private double getDouble(String key, double def) {
    try {
      return Double.parseDouble(props.getProperty(key, String.valueOf(def)).trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private boolean getBool(String key, boolean def) {
    return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)).trim());
  }

  private void set(String key, Object value) {
    props.setProperty(key, String.valueOf(value));
  }

  // ----- proprietes -----
  public int getWebPort() { return getInt("webPort", DEF_WEB_PORT); }
  public void setWebPort(int v) { set("webPort", v); }

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
  public void setPowerLimitAmps(double v) { set("powerLimitAmps", clampD(v, 0, 2000)); }

  /**
   * Consommation d'un canal de couleur allume a fond, en milliamperes.
   * 20 mA est la valeur typique des rubans WS2812 / APA102 : un pixel RGB en
   * blanc plein consomme donc environ 60 mA. Ne sert qu'a convertir la limite
   * saisie en amperes vers les « unites de luminance » du coeur legacy.
   */
  public double getMilliampsPerChannel() {
    return clampD(getDouble("milliampsPerChannel", 20.0), 1.0, 200.0);
  }
  public void setMilliampsPerChannel(double v) { set("milliampsPerChannel", clampD(v, 1.0, 200.0)); }

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
  public void setBrightness(double v) { set("brightness", clampD(v, 0.0, 1.0)); }

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
