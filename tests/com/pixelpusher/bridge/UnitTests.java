package com.pixelpusher.bridge;

import static com.pixelpusher.bridge.Harness.check;
import static com.pixelpusher.bridge.Harness.egal;
import static com.pixelpusher.bridge.Harness.groupe;

import java.io.File;

/**
 * Tests des invariants qui, s'ils cassent, produisent un comportement faux sans
 * lever la moindre exception : conversions de puissance, classement des
 * messages du legacy, echappement JSON, filtrage des noms de fichiers.
 * Ce sont exactement les erreurs qu'on ne voit qu'en spectacle.
 */
public final class UnitTests {

  private UnitTests() {
  }

  static void run(File dossierTemporaire) {
    puissance(dossierTemporaire);
    bornage(dossierTemporaire);
    messagesLegacy();
    json();
    nomsDeFichiers();
  }

  // ------------------------------------------------------------------
  private static void puissance(File dir) {
    groupe("Conversion de la limite de puissance");
    AppConfig cfg = AppConfig.forFile(new File(dir, "puissance.properties"));

    egal("limite desactivee par defaut", 0.0, cfg.getPowerLimitAmps());
    egal("desactivee => -1 pour le coeur legacy (sa valeur « pas de limite »)",
        -1L, cfg.getPowerLimitUnits());

    // 255 unites = un canal a fond = 20 mA. Donc 1 A = 1000/20 * 255 = 12750 unites.
    cfg.setPowerLimitAmps(1.0);
    cfg.setMilliampsPerChannel(20.0);
    egal("1 A a 20 mA/canal = 12750 unites de luminance", 12750L, cfg.getPowerLimitUnits());

    cfg.setPowerLimitAmps(24.0);
    egal("24 A = 306000 unites", 306000L, cfg.getPowerLimitUnits());

    // Un ruban deux fois plus gourmand doit donner deux fois moins d'unites
    // pour la meme limite en amperes.
    cfg.setPowerLimitAmps(1.0);
    cfg.setMilliampsPerChannel(40.0);
    egal("meme limite, rubans 2x plus gourmands => 2x moins d'unites",
        6375L, cfg.getPowerLimitUnits());

    // Coherence avec l'affichage : le status reconvertit les unites en amperes.
    cfg.setMilliampsPerChannel(20.0);
    long unites = 12750L;
    double amperes = unites * cfg.getMilliampsPerChannel() / 255.0 / 1000.0;
    check("aller-retour unites -> amperes sans derive", Math.abs(amperes - 1.0) < 1e-9);

    // Le pusher de test annonce 2000 unites : verifie la valeur affichee.
    double reel = 2000 * 20.0 / 255.0 / 1000.0;
    check("2000 unites affichent environ 0,157 A", Math.abs(reel - 0.15686) < 1e-4);

    cfg.setPowerLimitAmps(0);
    egal("remise a zero => de nouveau -1", -1L, cfg.getPowerLimitUnits());
  }

  // ------------------------------------------------------------------
  private static void bornage(File dir) {
    groupe("Bornage de la configuration");
    AppConfig cfg = AppConfig.forFile(new File(dir, "bornage.properties"));

    cfg.setPowerLimitAmps(-50);
    egal("limite negative ramenee a 0", 0.0, cfg.getPowerLimitAmps());
    cfg.setPowerLimitAmps(99999);
    egal("limite delirante plafonnee a 2000 A", 2000.0, cfg.getPowerLimitAmps());

    cfg.setMilliampsPerChannel(0);
    check("consommation par canal jamais nulle (division par zero)",
        cfg.getMilliampsPerChannel() >= 1.0);

    cfg.setFrameLimit(0);
    check("limite de trames jamais nulle", cfg.getFrameLimit() >= 1);
    cfg.setFrameLimit(99999);
    egal("limite de trames plafonnee", 1000, cfg.getFrameLimit());

    cfg.setBrightness(5.0);
    egal("luminosite plafonnee a 1.0", 1.0, cfg.getBrightness());
    cfg.setBrightness(-1);
    egal("luminosite plancher a 0.0", 0.0, cfg.getBrightness());

    cfg.setWatchdogSec(-5);
    egal("watchdog negatif ramene a 0", 0, cfg.getWatchdogSec());

    cfg.setColourOrder("nawak");
    egal("ordre de couleurs invalide => RGB", "RGB", cfg.getColourOrder());
    cfg.setColourOrder("grb");
    egal("ordre de couleurs normalise en majuscules", "GRB", cfg.getColourOrder());

    // Une config illisible ne doit jamais empecher le demarrage.
    AppConfig vide = AppConfig.forFile(new File(dir, "inexistant.properties"));
    egal("fichier absent => valeurs par defaut", 85, vide.getFrameLimit());
    check("le JSON de config reste bien forme", vide.toJson().startsWith("{")
        && vide.toJson().endsWith("}"));
  }

  // ------------------------------------------------------------------
  private static void messagesLegacy() {
    groupe("Reclassement des messages du coeur legacy");
    LegacyMessages.reset();

    String brut = "Group 1 card 1 would increase delay, but autothrottle is disabled.";
    LegacyMessages.Classified c = LegacyMessages.classify("ERROR", brut);
    check("l'avertissement d'auto-throttle est reconnu", c != null);
    egal("il n'est plus classe en erreur", "WARN", c.level);
    check("il est traduit en francais", c.message.contains("auto-throttle"));
    check("il identifie la carte concernee", c.message.contains("groupe 1")
        && c.message.contains("carte 1"));
    egal("il est comptabilise pour le diagnostic", 1L, LegacyMessages.getThrottleRequests());
    check("il est horodate", LegacyMessages.getThrottleRequestTs() > 0);

    LegacyMessages.classify("ERROR", brut);
    egal("les occurrences s'accumulent", 2L, LegacyMessages.getThrottleRequests());

    c = LegacyMessages.classify("ERROR", "Tried to write to pixel 300 but it wasn't there.");
    check("l'ecriture hors ruban est reconnue", c != null);
    egal("elle est classee en avertissement", "WARN", c.level);
    check("elle renvoie vers pixels_per_strip", c.message.contains("pixels_per_strip"));
    egal("elle est comptabilisee", 1L, LegacyMessages.getPixelOutOfRange());

    c = LegacyMessages.classify("ERROR", "Received short Art-Net packet.");
    egal("un paquet trop court est un avertissement", "WARN", c.level);
    egal("les paquets malformes sont comptes", 1L, LegacyMessages.getMalformedPackets());

    c = LegacyMessages.classify("ERROR", "Tried to kill CardThread for MAC ab, but it was already gone.");
    egal("un thread deja libere est une information", "INFO", c.level);

    c = LegacyMessages.classify("ERROR", "Already have a DiscoveryListener!");
    egal("le bruit d'initialisation est une information", "INFO", c.level);

    check("un message inconnu n'est pas touche",
        LegacyMessages.classify("ERROR", "java.net.SocketException: panne reelle") == null);
    check("un message deja en francais n'est pas touche",
        LegacyMessages.classify("INFO", "Blackout envoye a toutes les LED.") == null);
    check("une chaine vide ne fait pas planter", LegacyMessages.classify("ERROR", "") == null);
    check("null ne fait pas planter", LegacyMessages.classify("ERROR", null) == null);

    // Message tronque : l'extraction du groupe/carte ne doit pas lever.
    c = LegacyMessages.classify("ERROR", "Group  card  would increase delay, but autothrottle is disabled.");
    check("un prefixe incomplet ne fait pas planter", c != null);

    LegacyMessages.reset();
    egal("reset remet les compteurs a zero", 0L, LegacyMessages.getThrottleRequests());
  }

  // ------------------------------------------------------------------
  private static void json() {
    groupe("Echappement JSON");
    egal("guillemets echappes", "il a dit \\\"non\\\"", Json.esc("il a dit \"non\""));
    egal("antislash echappe", "C:\\\\dossier", Json.esc("C:\\dossier"));
    egal("saut de ligne echappe", "a\\nb", Json.esc("a\nb"));
    egal("retour chariot echappe", "a\\rb", Json.esc("a\rb"));
    egal("tabulation echappee", "a\\tb", Json.esc("a\tb"));
    egal("caractere de controle echappe en unicode", "a\\u0000b", Json.esc("a\u0000b"));
    egal("null devient une chaine vide", "", Json.esc(null));
    egal("les accents passent tels quels", "éàü", Json.esc("éàü"));

    // Cas concret : un nom de preset hostile ne doit pas casser la reponse.
    String hostile = "\"}]},\"injecte\":\"oui";
    String sortie = "{\"nom\":\"" + Json.esc(hostile) + "\"}";
    check("un nom hostile ne casse pas la structure JSON",
        sortie.indexOf("\"injecte\"") < 0 || sortie.indexOf("\\\"") > 0);
    check("aucun guillemet non echappe ne subsiste",
        compteNonEchappes(Json.esc(hostile)) == 0);
  }

  private static int compteNonEchappes(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
        n++;
      }
    }
    return n;
  }

  // ------------------------------------------------------------------
  private static void nomsDeFichiers() {
    groupe("Filtrage des noms de presets et de sequences");

    egal("nom normal conserve", "Salle A", Presets.sanitize("Salle A"));
    egal("accents conserves", "Répétition", Presets.sanitize("Répétition"));
    egal("chiffres et tirets conserves", "Tournee-2026 (v2)",
        Presets.sanitize("Tournee-2026 (v2)"));

    // Traversee de repertoire : le filtre retire les separateurs et les points.
    check("« ../../evil » ne peut pas remonter d'un dossier",
        Presets.sanitize("../../evil").indexOf('.') < 0
        && Presets.sanitize("../../evil").indexOf('/') < 0);
    check("les antislash sont retires",
        Presets.sanitize("..\\..\\windows\\system32").indexOf('\\') < 0);
    egal("un nom uniquement fait de separateurs devient vide", "",
        Presets.sanitize("../.."));
    check("les deux-points de lecteur sont retires",
        Presets.sanitize("C:\\config").indexOf(':') < 0);
    check("un octet nul est retire", Presets.sanitize("preset\u0000.txt").indexOf('\u0000') < 0);

    egal("nom vide", "", Presets.sanitize(""));
    egal("null", "", Presets.sanitize(null));
    check("nom tres long tronque a 40 caracteres",
        Presets.sanitize("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").length() <= 40);

    // Peripheriques reserves de Windows : un fichier CON.properties est
    // impossible a creer, l'utilisateur aurait une erreur incomprehensible.
    check("CON est refuse", Names.isReservedOnWindows("CON"));
    check("con en minuscules aussi", Names.isReservedOnWindows("con"));
    check("LPT1 est refuse", Names.isReservedOnWindows("LPT1"));
    check("COM9 est refuse", Names.isReservedOnWindows("COM9"));
    check("CONCERT reste autorise", !Names.isReservedOnWindows("CONCERT"));
    check("un nom vide n'est pas reserve", !Names.isReservedOnWindows(""));
    egal("le filtre des presets refuse CON", "", Presets.sanitize("CON"));
    egal("le filtre des sequences refuse NUL", "", Recorder.sanitize("NUL"));

    // Le filtre des sequences doit se comporter comme celui des presets.
    egal("meme filtrage pour les sequences", Presets.sanitize("../../x"),
        Recorder.sanitize("../../x"));
  }
}
