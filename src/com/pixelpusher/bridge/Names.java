package com.pixelpusher.bridge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Regles de nommage des fichiers crees a partir d'une saisie utilisateur
 * (presets, sequences enregistrees).
 *
 * Les noms sont deja filtres par les sanitize() respectifs, qui ne laissent
 * passer que lettres, chiffres, espace, tiret, souligne et parentheses : aucune
 * traversee de repertoire n'est possible. Reste un piege propre a Windows, que
 * le filtrage par caracteres ne couvre pas : une poignee de noms sont reserves
 * par le systeme pour designer des peripheriques. Un preset nomme « CON » ou
 * « LPT1 » produirait un fichier impossible a creer ou a relire, avec une erreur
 * incomprehensible pour l'utilisateur.
 */
public final class Names {

  private static final Set<String> RESERVES = new HashSet<String>(Arrays.asList(
      "CON", "PRN", "AUX", "NUL",
      "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
      "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

  private Names() {
  }

  /** Le nom designe-t-il un peripherique reserve par Windows ? */
  public static boolean isReservedOnWindows(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    return RESERVES.contains(name.trim().toUpperCase(Locale.ROOT));
  }
}
