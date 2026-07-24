package com.pixelpusher.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Micro-harnais de test, sans dependance externe (le projet n'en embarque
 * aucune, ce n'est pas au banc de tests d'en introduire une).
 *
 * Chaque test est une methode appelee depuis RunTests. On declare le test avec
 * check(...) : un echec est enregistre puis la suite continue, pour obtenir le
 * bilan complet en une seule execution plutot que de s'arreter au premier.
 */
public final class Harness {

  private static final List<String> ECHECS = new ArrayList<String>();
  private static int total = 0;
  private static String groupeCourant = "";

  private Harness() {
  }

  public static void groupe(String nom) {
    groupeCourant = nom;
    System.out.println();
    System.out.println("== " + nom);
  }

  /** Verifie une condition. */
  public static void check(String description, boolean condition) {
    total++;
    if (condition) {
      System.out.println("   ok    " + description);
    } else {
      System.out.println("   ECHEC " + description);
      ECHECS.add(groupeCourant + " > " + description);
    }
  }

  /** Verifie une egalite, en affichant les deux valeurs si elles different. */
  public static void egal(String description, Object attendu, Object obtenu) {
    boolean ok = attendu == null ? obtenu == null : attendu.equals(obtenu);
    total++;
    if (ok) {
      System.out.println("   ok    " + description);
    } else {
      System.out.println("   ECHEC " + description);
      System.out.println("         attendu : " + attendu);
      System.out.println("         obtenu  : " + obtenu);
      ECHECS.add(groupeCourant + " > " + description
          + " (attendu " + attendu + ", obtenu " + obtenu + ")");
    }
  }

  /** Verifie qu'un bloc leve bien une exception. */
  public static void leve(String description, Class<? extends Throwable> attendue, Runnable bloc) {
    total++;
    try {
      bloc.run();
      System.out.println("   ECHEC " + description + " (aucune exception levee)");
      ECHECS.add(groupeCourant + " > " + description + " : aucune exception");
    } catch (Throwable t) {
      if (attendue.isInstance(t) || attendue.isInstance(t.getCause())) {
        System.out.println("   ok    " + description);
      } else {
        System.out.println("   ECHEC " + description + " (exception " + t.getClass().getName() + ")");
        ECHECS.add(groupeCourant + " > " + description + " : " + t);
      }
    }
  }

  /** Affiche le bilan. @return code de sortie du processus. */
  public static int bilan() {
    System.out.println();
    System.out.println("==================================================");
    if (ECHECS.isEmpty()) {
      System.out.println(" " + total + " verifications, tout est au vert.");
      System.out.println("==================================================");
      return 0;
    }
    System.out.println(" " + ECHECS.size() + " ECHEC(S) sur " + total + " verifications :");
    for (String e : ECHECS) {
      System.out.println("   - " + e);
    }
    System.out.println("==================================================");
    return 1;
  }
}
