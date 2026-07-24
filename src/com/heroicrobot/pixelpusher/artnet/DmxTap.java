package com.heroicrobot.pixelpusher.artnet;

/**
 * Point d'ecoute optionnel sur les donnees DMX entrantes (Art-Net et sACN),
 * utilise par l'enregistreur de sequences. Ne modifie en rien le flux normal.
 */
public interface DmxTap {
  /**
   * Appele pour chaque trame DMX recue et validee.
   * @param universe univers interne (Art-Net + 1)
   * @param data     buffer contenant les canaux
   * @param offset   position du premier canal dans le buffer
   * @param length   nombre de canaux
   */
  void onDmx(int universe, byte[] data, int offset, int length);
}
