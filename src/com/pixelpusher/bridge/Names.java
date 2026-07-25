package com.pixelpusher.bridge;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Regle de nommage UNIQUE des fichiers crees a partir d'une saisie utilisateur
 * (presets, sequences enregistrees).
 *
 * C'est la seule barriere entre un nom recu par HTTP et un chemin sur le
 * disque, elle ne doit donc exister qu'a un seul endroit : Presets et Recorder
 * delegent tous les deux ici. La regle etait auparavant recopiee mot pour mot
 * dans les deux classes ; une fonction de securite dupliquee est une fonction
 * de securite qui finira par diverger (autoriser le point d'un seul cote
 * suffirait a rouvrir « .. » pour l'autre). (PixelPusherBridge)
 *
 * Deux etages :
 *
 * 1. sanitize() ne laisse passer que lettres, chiffres, espace, tiret,
 *    souligne et parentheses, tronque a 40 caracteres, et refuse en plus les
 *    noms reserves par Windows. Ce dernier point n'est pas couvert par le
 *    filtrage par caracteres : une poignee de noms designent des
 *    peripheriques. Un preset nomme « CON » ou « LPT1 » produirait un fichier
 *    impossible a creer ou a relire, avec une erreur incomprehensible pour
 *    l'utilisateur.
 *
 * 2. safeFile() reconstruit le chemin et verifie qu'il reste dans le dossier
 *    autorise. Defense en profondeur : sanitize() supprime deja tout ce qui
 *    permettrait une traversee de repertoire, mais cette verification reste
 *    valable si le jeu de caracteres autorise evolue un jour.
 */
public final class Names {

  private static final Set<String> RESERVES = new HashSet<String>(Arrays.asList(
      "CON", "PRN", "AUX", "NUL",
      "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
      "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

  /** Longueur maximale d'un nom apres nettoyage. */
  private static final int LONGUEUR_MAX = 40;

  private Names() {
  }

  /** Le nom designe-t-il un peripherique reserve par Windows ? */
  public static boolean isReservedOnWindows(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    return RESERVES.contains(name.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Nettoyage d'un nom fourni par le client HTTP.
   * Retourne "" quand il ne reste rien d'utilisable (le nom est alors refuse
   * par les appelants).
   */
  public static String sanitize(String name) {
    if (name == null) {
      return "";
    }
    String s = name.replaceAll("[^\\p{L}\\p{N} _()-]", "").trim();
    if (s.length() > LONGUEUR_MAX) {
      s = s.substring(0, LONGUEUR_MAX).trim();
    }
    return isReservedOnWindows(s) ? "" : s;
  }

  /**
   * Construit le fichier &lt;dir&gt;/&lt;base&gt;&lt;extension&gt; et verifie
   * qu'il reste bien dans le dossier autorise, ou null sinon.
   *
   * Deux lignes, aucun cout mesurable : ces appels ont lieu sur action
   * explicite de l'operateur, jamais sur le chemin temps reel.
   */
  public static File safeFile(File dir, String base, String extension) {
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
}
