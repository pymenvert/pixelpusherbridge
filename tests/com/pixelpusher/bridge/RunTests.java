package com.pixelpusher.bridge;

import java.io.File;

/**
 * Point d'entree du banc de tests.
 *
 * Lancement : RUN-TESTS.bat (Windows) ou tools/run_tests.sh (macOS / Linux).
 * Aucun framework, aucune dependance : uniquement la bibliotheque standard,
 * comme le reste du projet.
 *
 * Ces tests ne touchent jamais a la configuration reelle de l'utilisateur :
 * tout ce qui ecrit sur disque le fait dans un dossier temporaire supprime
 * a la fin.
 */
public final class RunTests {

  public static void main(String[] args) throws Exception {
    System.out.println("==================================================");
    System.out.println(" PixelPusher Bridge - banc de tests");
    System.out.println(" Java " + System.getProperty("java.version")
        + " / " + System.getProperty("os.name"));
    System.out.println("==================================================");

    File temp = File.createTempFile("ppb-tests", "");
    if (!temp.delete() || !temp.mkdirs()) {
      System.out.println("Impossible de creer le dossier temporaire de test.");
      System.exit(1);
    }
    try {
      UnitTests.run(temp);
      HttpTests.run();
    } finally {
      supprimer(temp);
    }
    System.exit(Harness.bilan());
  }

  private static void supprimer(File f) {
    File[] enfants = f.listFiles();
    if (enfants != null) {
      for (File e : enfants) {
        supprimer(e);
      }
    }
    f.delete();
  }
}
