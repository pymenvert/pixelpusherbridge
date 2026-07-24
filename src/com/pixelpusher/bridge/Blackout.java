package com.pixelpusher.bridge;

import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Blackout d'urgence verrouille.
 *
 * POURQUOI CE N'EST PAS UN SIMPLE « tout eteindre » : mettre les pixels a noir
 * ne suffit pas. La source lumiere continue d'emettre a 40 Hz et la trame
 * suivante rallume tout environ 25 ms plus tard. Le bouton d'urgence n'avait
 * donc aucun effet perceptible tant que la console tournait - exactement la
 * situation ou on en a besoin.
 *
 * Un blackout est ici un ETAT : on ignore les donnees entrantes tant qu'il est
 * actif, comme la touche blackout d'une console lumiere. Il faut une action
 * explicite pour reprendre, et toute action qui produit volontairement de la
 * lumiere (test, lecture de sequence) le releve en le signalant.
 */
public final class Blackout {

  private final LegacyCore core;
  private volatile boolean active = false;

  public Blackout(LegacyCore core) {
    this.core = core;
  }

  public boolean isActive() {
    return active;
  }

  /** Coupe tout et verrouille : les donnees entrantes sont ignorees. */
  public synchronized void engage() {
    active = true;
    core.setMuteDmx(true);
    core.blackoutAll();
    // Une trame pouvait deja etre en cours d'assemblage au moment de la coupure :
    // on repasse une fois, un peu plus tard, pour ne rien laisser allume.
    Thread rappel = new Thread(new Runnable() {
      public void run() {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (active) {
          core.blackoutAll();
        }
      }
    }, "blackout-confirm");
    rappel.setDaemon(true);
    rappel.start();
    LogBus.warn("BLACKOUT ACTIF - les données DMX entrantes sont ignorées "
        + "jusqu'à la reprise explicite.");
  }

  /** Rend la main a la source lumiere. */
  public synchronized void release() {
    if (!active) {
      return;
    }
    active = false;
    core.setMuteDmx(false);
    LogBus.info("Blackout levé : le flux DMX entrant reprend la main.");
  }

  /**
   * Remet le silence en place apres une operation qui a pu le lever
   * (sortie de mode test, arret de lecture de sequence).
   */
  public synchronized void reapplyIfActive() {
    if (active) {
      core.setMuteDmx(true);
    }
  }

  /**
   * Leve le blackout parce que l'utilisateur demande volontairement de la
   * lumiere. Silencieux si aucun blackout n'est actif.
   */
  public synchronized void releaseFor(String raison) {
    if (!active) {
      return;
    }
    LogBus.info("Blackout levé automatiquement : " + raison + ".");
    active = false;
    core.setMuteDmx(false);
  }
}
