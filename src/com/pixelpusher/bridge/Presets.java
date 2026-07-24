package com.pixelpusher.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Presets de configuration nommes (ex. "Salle A", "Tournee") stockes dans
 * ~/.pixelpusherbridge/presets/&lt;nom&gt;.properties.
 * Un preset est une photo complete de la configuration courante.
 */
public final class Presets {

  /** Cle technique ecrite dans chaque preset, retiree au chargement. */
  private static final String CLE_VERSION = "presetVersion";

  private Presets() {
  }

  public static File presetsDir() {
    File dir = new File(AppConfig.configDir(), "presets");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  /**
   * Regle de nommage unique des fichiers crees a partir d'une saisie
   * utilisateur. C'est la SEULE barriere entre un nom recu par HTTP et un
   * chemin sur le disque : elle ne doit exister qu'a un seul endroit.
   * Recorder.sanitize() delegue ici pour cette raison (le code etait duplique
   * mot pour mot dans les deux classes, une divergence future aurait fait
   * diverger deux regles de securite). (PixelPusherBridge)
   */
  static String sanitize(String name) {
    if (name == null) {
      return "";
    }
    String s = name.replaceAll("[^\\p{L}\\p{N} _()-]", "").trim();
    if (s.length() > 40) {
      s = s.substring(0, 40).trim();
    }
    return Names.isReservedOnWindows(s) ? "" : s;
  }

  /**
   * Construit le fichier &lt;dir&gt;/&lt;base&gt;&lt;extension&gt; et verifie
   * qu'il reste bien dans le dossier autorise, ou null sinon.
   *
   * Defense en profondeur : sanitize() supprime deja tout ce qui permettrait
   * une traversee de repertoire, mais cette verification reste valable si le
   * jeu de caracteres autorise evolue un jour (ajouter le point suffirait a
   * laisser passer ".."). Deux lignes, aucun cout mesurable : ces appels ont
   * lieu sur action explicite de l'operateur, jamais sur le chemin temps reel.
   * (PixelPusherBridge)
   */
  static File safeFile(File dir, String base, String extension) {
    if (base == null || base.isEmpty()) {
      return null;
    }
    File f = new File(dir, base + extension);
    try {
      String racine = dir.getCanonicalPath();
      if (!racine.endsWith(File.separator)) {
        racine = racine + File.separator;
      }
      if (!f.getCanonicalPath().startsWith(racine)) {
        return null;
      }
      return f;
    } catch (IOException e) {
      return null;
    }
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

  /**
   * Photographie la configuration courante dans un preset.
   *
   * L'ecriture passe par un fichier temporaire puis un remplacement atomique :
   * une coupure de courant ou un disque plein en pleine ecriture laissait
   * sinon un preset a moitie ecrit, que Properties.load relit sans broncher :
   * on rechargeait alors une configuration tronquee sans le moindre message.
   * (PixelPusherBridge)
   */
  public static boolean save(String rawName, AppConfig cfg) {
    String name = sanitize(rawName);
    File dest = safeFile(presetsDir(), name, ".properties");
    if (dest == null) {
      return false;
    }
    Properties snapshot = cfg.snapshot();
    // trace de la version d'origine : permet de signaler au chargement qu'un
    // preset vient d'une version anterieure du logiciel
    snapshot.setProperty(CLE_VERSION, AppConfig.VERSION);
    File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
    try {
      FileOutputStream out = new FileOutputStream(tmp);
      try {
        snapshot.store(out, "PixelPusher Bridge - preset");
        out.flush();
        try {
          out.getFD().sync(); // les octets sont reellement sur le disque
        } catch (java.io.SyncFailedException ignored) {
          // certains supports ne savent pas forcer : sans gravite
        }
      } finally {
        out.close();
      }
      try {
        Files.move(tmp.toPath(), dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException noAtomic) {
        // Le renommage atomique n'est pas disponible partout : dossier
        // personnel redirige sur un partage reseau (profil itinerant d'un
        // poste de regie en salle), cle USB, systeme de fichiers exotique.
        // On rattrape ici toute IOException et pas seulement
        // AtomicMoveNotSupportedException : plusieurs systemes de fichiers
        // signalent l'echec par une FileSystemException generique, et sans ce
        // repli l'enregistrement du preset echouait purement et simplement au
        // lieu de se rabattre sur un remplacement simple. (PixelPusherBridge)
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      LogBus.info("Preset enregistre : " + name);
      return true;
    } catch (IOException e) {
      tmp.delete();
      LogBus.error("Preset : sauvegarde impossible : " + e);
      return false;
    }
  }

  /**
   * Charge un preset dans la configuration courante (sans l'appliquer).
   *
   * Fusion et non remplacement : on partait des valeurs du preset seules, si
   * bien que tout reglage absent du fichier (typiquement un reglage ajoute
   * dans une version ulterieure du logiciel) retombait silencieusement a son
   * defaut code en dur. Un preset enregistre avant l'arrivee du watchdog
   * remettait ainsi le blackout automatique a zero sans rien dire. On conserve
   * desormais la valeur courante des cles absentes, et on les journalise.
   * (PixelPusherBridge)
   */
  public static boolean load(String rawName, AppConfig cfg) {
    String name = sanitize(rawName);
    File f = safeFile(presetsDir(), name, ".properties");
    if (f == null || !f.isFile()) {
      return false;
    }
    Properties p = new Properties();
    InputStream in = null;
    try {
      in = new FileInputStream(f);
      p.load(in);
    } catch (IOException e) {
      LogBus.error("Preset : chargement impossible : " + e);
      return false;
    } catch (IllegalArgumentException e) {
      // sequence d'echappement invalide dans le fichier : ne doit pas remonter
      LogBus.error("Preset : fichier illisible (" + e + ") : " + name);
      return false;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignored) {
        }
      }
    }

    String versionOrigine = p.getProperty(CLE_VERSION, "");
    p.remove(CLE_VERSION); // cle technique : ne doit pas polluer la configuration

    Properties courant = cfg.snapshot();
    List<String> conservees = new ArrayList<String>();
    for (String cle : courant.stringPropertyNames()) {
      if (!p.containsKey(cle)) {
        conservees.add(cle);
      }
    }
    Properties fusion = new Properties();
    fusion.putAll(courant);
    fusion.putAll(p);
    cfg.replaceWith(fusion);
    cfg.save();

    LogBus.info("Preset charge : " + name
        + (versionOrigine.isEmpty() ? "" : " (enregistre en version " + versionOrigine + ")"));
    if (!conservees.isEmpty()) {
      conservees.sort(String.CASE_INSENSITIVE_ORDER);
      LogBus.warn("Preset « " + name + " » : " + conservees.size()
          + " réglage(s) ne figurent pas dans ce preset (probablement plus récents que lui), "
          + "leur valeur actuelle est conservée : " + String.join(", ", conservees));
    }
    return true;
  }

  public static boolean delete(String rawName) {
    String name = sanitize(rawName);
    File f = safeFile(presetsDir(), name, ".properties");
    if (f == null) {
      return false;
    }
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
