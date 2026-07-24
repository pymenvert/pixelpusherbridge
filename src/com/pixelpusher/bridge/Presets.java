package com.pixelpusher.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Presets de configuration nommes (ex. "Salle A", "Tournee") stockes dans
 * ~/.pixelpusherbridge/presets/&lt;nom&gt;.properties.
 * Un preset est une photo complete de la configuration courante.
 */
public final class Presets {

  private Presets() {
  }

  public static File presetsDir() {
    File dir = new File(AppConfig.configDir(), "presets");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  static String sanitize(String name) {
    if (name == null) {
      return "";
    }
    String s = name.replaceAll("[^\\p{L}\\p{N} _()-]", "").trim();
    return s.length() > 40 ? s.substring(0, 40) : s;
  }

  public static List<String> list() {
    List<String> names = new ArrayList<String>();
    File[] files = presetsDir().listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.getName().endsWith(".properties")) {
          names.add(f.getName().replace(".properties", ""));
        }
      }
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }

  /** Photographie la configuration courante dans un preset. */
  public static boolean save(String rawName, AppConfig cfg) {
    String name = sanitize(rawName);
    if (name.isEmpty()) {
      return false;
    }
    Properties snapshot = cfg.snapshot();
    try {
      FileOutputStream out = new FileOutputStream(new File(presetsDir(), name + ".properties"));
      snapshot.store(out, "PixelPusher Bridge - preset");
      out.close();
      LogBus.info("Preset enregistre : " + name);
      return true;
    } catch (IOException e) {
      LogBus.error("Preset : sauvegarde impossible : " + e);
      return false;
    }
  }

  /** Charge un preset dans la configuration courante (sans l'appliquer). */
  public static boolean load(String rawName, AppConfig cfg) {
    String name = sanitize(rawName);
    File f = new File(presetsDir(), name + ".properties");
    if (!f.isFile()) {
      return false;
    }
    try {
      Properties p = new Properties();
      FileInputStream in = new FileInputStream(f);
      p.load(in);
      in.close();
      cfg.replaceWith(p);
      cfg.save();
      LogBus.info("Preset charge : " + name);
      return true;
    } catch (IOException e) {
      LogBus.error("Preset : chargement impossible : " + e);
      return false;
    }
  }

  public static boolean delete(String rawName) {
    String name = sanitize(rawName);
    File f = new File(presetsDir(), name + ".properties");
    boolean ok = f.delete();
    if (ok) {
      LogBus.info("Preset supprime : " + name);
    }
    return ok;
  }

  public static String listJson() {
    StringBuilder sb = new StringBuilder(128);
    sb.append('[');
    boolean first = true;
    for (String n : list()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(Json.esc(n)).append('"');
    }
    sb.append(']');
    return sb.toString();
  }
}
