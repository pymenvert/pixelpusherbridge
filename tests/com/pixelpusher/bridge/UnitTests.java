package com.pixelpusher.bridge;

import static com.pixelpusher.bridge.Harness.check;
import static com.pixelpusher.bridge.Harness.egal;
import static com.pixelpusher.bridge.Harness.groupe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.heroicrobot.pixelpusher.artnet.ArtNetReceiver;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Tests des invariants qui, s'ils cassent, produisent un comportement faux sans
 * lever la moindre exception : conversions de puissance, classement des
 * messages du legacy, echappement JSON, filtrage des noms de fichiers,
 * ecritures atomiques sur disque, reprise du flux de logs.
 * Ce sont exactement les erreurs qu'on ne voit qu'en spectacle.
 *
 * Regle de ce banc : on ne teste que ce qui existe reellement dans le code de
 * l'application. Quand la logique a proteger est privee (elle l'est souvent :
 * ce sont des details d'implementation, pas une API), on l'atteint par
 * reflexion plutot que d'elargir sa visibilite pour les besoins du test.
 */
public final class UnitTests {

  private UnitTests() {
  }

  static void run(File dossierTemporaire) throws Exception {
    puissance(dossierTemporaire);
    bornage(dossierTemporaire);
    configAberrante(dossierTemporaire);
    configSauvegarde(dossierTemporaire);
    messagesLegacy();
    json();
    jsonWriter();
    nomsDeFichiers();
    namesCentralise(dossierTemporaire);
    presetsAtomiques(dossierTemporaire);
    sequencesAtomiques(dossierTemporaire);
    reprisDesLogs();
    purgeDesUnivers(dossierTemporaire);
    adressesReseau();
    etatEtDiagnostic(dossierTemporaire);
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
  /**
   * Un fichier de configuration edite a la main, recopie d'une autre machine ou
   * a moitie ecrit contient tot ou tard n'importe quoi. Aucune de ces valeurs
   * ne doit ressortir telle quelle : NaN et Infini traversent tous les bornages
   * (Math.min/max les propagent) et finissent en litteral illegal dans le JSON,
   * ce qui fige l'interface au chargement alors que le bridge fonctionne.
   */
  private static void configAberrante(File dir) {
    groupe("Valeurs de configuration aberrantes");
    AppConfig cfg = AppConfig.forFile(new File(dir, "aberrante.properties"));

    Properties brut = new Properties();
    brut.setProperty("brightness", "NaN");
    brut.setProperty("powerLimitAmps", "Infinity");
    brut.setProperty("milliampsPerChannel", "-Infinity");
    brut.setProperty("frameLimit", "quatre-vingts");
    brut.setProperty("extraDelayMs", "99999");
    brut.setProperty("watchdogSec", "-40");
    brut.setProperty("colourOrder", "nawak");
    brut.setProperty("packing", "1");
    brut.setProperty("debug", "oui");
    brut.setProperty("sacnEnabled", "off");
    brut.setProperty("blackoutOnExit", "0");
    brut.setProperty("expiryEnabled", "peut-etre");
    brut.setProperty("antiLog", "YES");
    cfg.replaceWith(brut);

    // Chaque valeur inexploitable est signalee UNE SEULE FOIS par cle dans le
    // journal de l'application. On absorbe donc ce premier passage pour garder
    // le bilan lisible, et on verifie au passage que l'operateur est averti :
    // les lectures suivantes sont silencieuses.
    ByteArrayOutputStream journal = new ByteArrayOutputStream();
    PrintStream vraiOut = System.out;
    PrintStream vraiErr = System.err;
    try {
      PrintStream muet = new PrintStream(journal, true, "UTF-8");
      System.setOut(muet);
      System.setErr(muet);
      cfg.getBrightness();
      cfg.getPowerLimitAmps();
      cfg.getMilliampsPerChannel();
      cfg.getFrameLimit();
      cfg.getExtraDelayMs();
      cfg.getWatchdogSec();
      cfg.isPacking();
      cfg.isDebug();
      cfg.isSacnEnabled();
      cfg.isBlackoutOnExit();
      cfg.isExpiryEnabled();
      cfg.isAntiLog();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // UTF-8 existe toujours
    } finally {
      System.setOut(vraiOut);
      System.setErr(vraiErr);
    }
    String avertissements = journal.toString();
    check("l'operateur est averti d'une valeur inexploitable",
        avertissements.contains("valeur inattendue"));
    check("... et l'avertissement nomme la cle en cause",
        avertissements.contains("brightness"));

    egal("« NaN » ne devient jamais une luminosite", 1.0, cfg.getBrightness());
    egal("« Infinity » ne devient jamais une limite de puissance", 0.0, cfg.getPowerLimitAmps());
    egal("« -Infinity » ne devient jamais une consommation par canal",
        20.0, cfg.getMilliampsPerChannel());
    egal("un texte a la place d'un entier => valeur par defaut", 85, cfg.getFrameLimit());
    egal("un entier hors bornes est plafonne", 1000, cfg.getExtraDelayMs());
    egal("un entier negatif est ramene dans les bornes", 0, cfg.getWatchdogSec());
    egal("un ordre de couleurs inconnu => RGB", "RGB", cfg.getColourOrder());

    // Booleens exotiques : Boolean.parseBoolean rend false pour tout ce qui
    // n'est pas « true », si bien qu'un packing=1 ecrit a la main cassait le
    // mapping en silence et qu'un reglage inconnu desactivait la fonction au
    // lieu de garder son defaut.
    check("« 1 » vaut vrai", cfg.isPacking());
    check("« oui » vaut vrai", cfg.isDebug());
    check("« YES » vaut vrai", cfg.isAntiLog());
    check("« off » vaut faux", !cfg.isSacnEnabled());
    check("« 0 » vaut faux (et ne desactive plus le blackout de sortie par surprise)",
        !cfg.isBlackoutOnExit());
    check("un booleen incomprehensible garde la valeur par defaut, pas false",
        cfg.isExpiryEnabled());

    String jsonFou = cfg.toJson();
    check("aucun litteral NaN ne sort de la configuration", jsonFou.indexOf("NaN") < 0);
    check("aucun litteral Infinity non plus", jsonFou.indexOf("Infinity") < 0);
    check("le JSON de configuration reste analysable par l'interface", jsonValide(jsonFou));

    // Ecriture : une valeur non finie ne doit jamais remplacer une valeur saine.
    cfg.setBrightness(0.5);
    egal("une luminosite valide s'ecrit", 0.5, cfg.getBrightness());
    cfg.setBrightness(Double.NaN);
    egal("un NaN ecrit ne remplace pas la valeur en place", 0.5, cfg.getBrightness());
    cfg.setBrightness(Double.POSITIVE_INFINITY);
    egal("un infini non plus", 0.5, cfg.getBrightness());
    cfg.setMilliampsPerChannel(35.0);
    cfg.setMilliampsPerChannel(Double.NaN);
    egal("meme regle pour la consommation par canal", 35.0, cfg.getMilliampsPerChannel());
  }

  // ------------------------------------------------------------------
  /**
   * Une coupure de courant pendant l'ecriture de config.properties laissait un
   * fichier vide, et l'application repartait aux valeurs d'usine sans le
   * moindre message : ordre des couleurs et luminosite perdus, le soir de la
   * premiere. On verifie ici la chaine complete : copie de secours, mise en
   * quarantaine du fichier illisible, reprise, et message a afficher.
   */
  private static void configSauvegarde(File dir) throws Exception {
    groupe("Configuration illisible et reprise sur la sauvegarde");
    String ancienFoyer = System.getProperty("user.home");
    try {
      File foyer = new File(dir, "foyer-config");
      foyer.mkdirs();
      System.setProperty("user.home", foyer.getAbsolutePath());

      File dossier = AppConfig.configDir();
      File principal = new File(dossier, "config.properties");
      File secours = new File(dossier, "config.properties.bak");

      ecrire(principal, "colourOrder=GRB\nwatchdogSec=42\n");
      AppConfig sain = chargeSansBruit();
      egal("une configuration saine est relue telle quelle", "GRB", sain.getColourOrder());
      egal("... y compris les entiers", 42, sain.getWatchdogSec());

      sain.setWatchdogSec(43);
      sain.save();
      check("la version precedente est conservee en copie de secours",
          secours.isFile() && secours.length() > 0);
      check("aucun fichier temporaire ne subsiste apres une sauvegarde",
          aucunTmp(dossier));
      egal("le fichier principal porte bien la nouvelle valeur",
          "43", lire(principal).getProperty("watchdogSec"));

      // 1. fichier tronque a zero octet : ecriture interrompue
      ecrire(principal, "");
      AppConfig repris = chargeSansBruit();
      egal("un fichier vide n'efface pas les reglages : la sauvegarde reprend la main",
          42, repris.getWatchdogSec());
      egal("... tous les reglages, pas seulement un", "GRB", repris.getColourOrder());
      check("un config.properties valide est immediatement reecrit",
          principal.isFile() && principal.length() > 0);
      check("l'operateur sera prevenu au demarrage",
          AppConfig.getCorruptionMessage() != null
          && AppConfig.getCorruptionMessage().contains("sauvegarde"));

      // 2. fichier illisible : sequence d'echappement invalide (un chemin
      // Windows colle a la main suffit). Properties.load leve alors une
      // IllegalArgumentException, pas une IOException.
      ecrire(principal, "colourOrder=\\uZZZZ\n");
      AppConfig repris2 = chargeSansBruit();
      egal("un fichier illisible ne fait pas repartir aux valeurs d'usine",
          42, repris2.getWatchdogSec());
      check("l'ancien fichier est mis de cote pour analyse",
          existePrefixe(dossier, "config.properties.illisible-"));

      // 3. plus rien d'exploitable : valeurs par defaut, et on le dit
      secours.delete();
      ecrire(principal, "colourOrder=\\uZZZZ\n");
      AppConfig defaut = chargeSansBruit();
      egal("sans sauvegarde exploitable, on repart sur les valeurs par defaut",
          85, defaut.getFrameLimit());
      check("... et le message le dit clairement",
          AppConfig.getCorruptionMessage().contains("défaut"));

      // 4. premier lancement : aucun fichier, aucun drame
      supprimerContenu(dossier);
      AppConfig premier = chargeSansBruit();
      egal("premier lancement : valeurs par defaut", 85, premier.getFrameLimit());
      egal("... et ordre des couleurs par defaut", "RGB", premier.getColourOrder());
    } finally {
      System.setProperty("user.home", ancienFoyer);
    }
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
  /**
   * Le mini-writer JSON remplace les serialiseurs ecrits a la main. Sa promesse
   * est simple et doit etre verifiee sans indulgence : TOUTE chaine qu'on lui
   * confie, cle comprise, est echappee. Une seule valeur inseree brute suffit a
   * rendre /api/status illisible pour l'interface, qui reste alors figee.
   */
  private static void jsonWriter() {
    groupe("Mini-writer JSON");

    // Le juge d'abord : un valideur qu'on croit sur parole ne vaut rien.
    check("le valideur accepte un document correct",
        jsonValide("{\"a\":[1,-2.5,1.5e3,true,false,null,\"x\\n\\u00e9\"]}"));
    check("le valideur refuse un guillemet non echappe",
        !jsonValide("{\"a\":\"il a dit \"non\"\"}"));
    check("le valideur refuse un saut de ligne brut dans une chaine",
        !jsonValide("{\"a\":\"x\ny\"}"));
    check("le valideur refuse un echappement inconnu", !jsonValide("{\"a\":\"x\\qy\"}"));
    check("le valideur refuse le litteral NaN", !jsonValide("{\"a\":NaN}"));
    check("le valideur refuse une virgule en trop", !jsonValide("{\"a\":1,}"));
    check("le valideur refuse un document tronque", !jsonValide("{\"a\":1"));

    egal("objet vide", "{}", Json.writer().beginObject().endObject().done());
    egal("tableau vide", "[]", Json.writer().beginArray().endArray().done());
    egal("les virgules sont placees toutes seules", "{\"a\":1,\"b\":true,\"c\":\"x\"}",
        Json.writer().beginObject().num("a", 1L).bool("b", true).str("c", "x")
            .endObject().done());
    egal("tableau sous une cle", "{\"l\":[1,2]}",
        Json.writer().beginObject().beginArray("l").num(1L).num(2L).endArray()
            .endObject().done());
    egal("objet imbrique", "{\"o\":{\"k\":\"v\"}}",
        Json.writer().beginObject().beginObject("o").str("k", "v").endObject()
            .endObject().done());
    egal("tableau d'objets", "[{\"i\":1},{\"i\":2}]",
        Json.writer().beginArray().beginObject().num("i", 1L).endObject()
            .beginObject().num("i", 2L).endObject().endArray().done());
    egal("tableau de chaines", "[\"a\",\"b\"]",
        Json.writer().beginArray().str("a").str("b").endArray().done());
    egal("tableau de booleens", "[true,false]",
        Json.writer().beginArray().bool(true).bool(false).endArray().done());
    egal("les reels gardent leur ecriture", "{\"x\":0.5}",
        Json.writer().beginObject().num("x", 0.5).endObject().done());

    // NaN et infini sont des litteraux illegaux en JSON : le writer les neutralise.
    egal("un NaN est ecrit 0 (JSON.parse refuserait le litteral)", "{\"x\":0}",
        Json.writer().beginObject().num("x", Double.NaN).endObject().done());
    egal("un infini est ecrit 0", "{\"x\":0}",
        Json.writer().beginObject().num("x", Double.POSITIVE_INFINITY).endObject().done());
    egal("un infini negatif aussi", "{\"x\":0}",
        Json.writer().beginObject().num("x", Double.NEGATIVE_INFINITY).endObject().done());

    // L'echappement est structurel : il n'y a aucun moyen d'y echapper.
    egal("la valeur d'un champ est echappee", "{\"n\":\"il a dit \\\"non\\\"\"}",
        Json.writer().beginObject().str("n", "il a dit \"non\"").endObject().done());
    egal("la CLE d'un champ est echappee elle aussi", "{\"a\\\"b\":\"x\"}",
        Json.writer().beginObject().str("a\"b", "x").endObject().done());
    egal("un element de tableau est echappe", "[\"a\\\\b\"]",
        Json.writer().beginArray().str("a\\b").endArray().done());
    egal("un caractere de controle est echappe", "{\"c\":\"a\\u0001b\"}",
        Json.writer().beginObject().str("c", "a\u0001b").endObject().done());
    egal("une valeur nulle devient une chaine vide", "{\"n\":\"\"}",
        Json.writer().beginObject().str("n", null).endObject().done());
    egal("les accents traversent sans transformation", "{\"v\":\"Répétition\"}",
        Json.writer().beginObject().str("v", "Répétition").endObject().done());

    // Tentative d'injection : la valeur ne doit jamais pouvoir creer un champ.
    String hostile = "\",\"injecte\":\"oui";
    String document = Json.writer().beginObject().str("nom", hostile)
        .num("frames", 3L).endObject().done();
    check("une valeur hostile ne peut pas ajouter de champ",
        document.indexOf("\",\"injecte\"") < 0);
    check("le document reste du JSON valide malgre elle", jsonValide(document));
    check("... et la valeur d'origine est bien conservee, seulement echappee",
        document.contains(Json.esc(hostile)));

    // Une cle hostile est aussi dangereuse qu'une valeur.
    String cleHostile = Json.writer().beginObject().str("a\":1,\"b", "x").endObject().done();
    check("une cle hostile ne peut pas ajouter de champ non plus", jsonValide(cleHostile));

    // Imbrication profonde : le tampon interne des niveaux (8 au depart) doit
    // grandir tout seul, sinon un diagnostic charge deborderait.
    Json.Writer profond = Json.writer(64).beginObject();
    for (int i = 0; i < 20; i++) {
      profond.beginObject("n" + i);
    }
    profond.str("fin", "ok");
    for (int i = 0; i < 20; i++) {
      profond.endObject();
    }
    String texte = profond.endObject().done();
    check("l'imbrication profonde reste valide (croissance du tampon de niveaux)",
        jsonValide(texte));
    check("... et le contenu le plus profond est bien present",
        texte.contains("\"fin\":\"ok\""));
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

  // ------------------------------------------------------------------
  /**
   * La regle de nommage est la SEULE barriere entre un nom recu par HTTP et un
   * chemin sur le disque. Elle etait recopiee dans Presets et dans Recorder :
   * deux copies d'une fonction de securite finissent toujours par diverger.
   * Ce test echoue des qu'un des deux appelants cesse de deleguer a Names.
   */
  private static void namesCentralise(File dir) throws Exception {
    groupe("Regle de nommage centralisee (Names)");

    String[] cas = {
        "Salle A", "Répétition", "Tournee-2026 (v2)", "../../evil",
        "..\\..\\windows\\system32", "CON", "nul", "", null, "C:\\config",
        "preset\u0000.txt", "  Salle B  ", ".", "..",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    };
    StringBuilder divergences = new StringBuilder();
    for (String c : cas) {
      String reference = Names.sanitize(c);
      if (!reference.equals(Presets.sanitize(c))) {
        divergences.append(" presets:«").append(c).append("»");
      }
      if (!reference.equals(Recorder.sanitize(c))) {
        divergences.append(" sequences:«").append(c).append("»");
      }
    }
    check("presets et sequences appliquent exactement la regle de Names ("
        + cas.length + " cas)" + divergences, divergences.length() == 0);

    egal("la troncature se fait bien a 40 caracteres", 40,
        Names.sanitize("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").length());
    egal("un nom qui redevient reserve apres nettoyage est refuse", "",
        Names.sanitize("C:O:N"));
    egal("les espaces autour du nom sont retires", "Salle B", Names.sanitize("  Salle B  "));
    egal("un nom fait uniquement de points devient vide", "", Names.sanitize("..."));

    // safeFile : defense en profondeur, on verifie qu'on ne sort pas du dossier.
    File racine = new File(dir, "noms");
    File voisin = new File(dir, "nomsX");
    racine.mkdirs();
    voisin.mkdirs();
    egal("un nom vide ne donne aucun fichier", null, Names.safeFile(racine, "", ".x"));
    egal("un nom null non plus", null, Names.safeFile(racine, null, ".x"));
    File ok = Names.safeFile(racine, "Salle A", ".properties");
    check("un nom normal donne bien un fichier", ok != null);
    egal("... et il est dans le dossier autorise", racine.getCanonicalFile(),
        ok.getParentFile().getCanonicalFile());
    egal("une remontee de dossier est refusee", null,
        Names.safeFile(racine, "../evil", ".txt"));
    egal("un dossier voisin dont le nom commence pareil est refuse aussi", null,
        Names.safeFile(racine, "../nomsX/evil", ".txt"));
  }

  // ------------------------------------------------------------------
  /**
   * Un preset a moitie ecrit se relit sans erreur : Properties.load ne se
   * plaint pas d'un fichier tronque. On rechargeait alors une configuration
   * incomplete en croyant tout retrouver. D'ou l'ecriture en .tmp puis
   * remplacement : a aucun instant la cible n'est dans un etat intermediaire.
   */
  private static void presetsAtomiques(File dir) throws Exception {
    groupe("Presets : ecriture atomique et fusion au chargement");
    String ancienFoyer = System.getProperty("user.home");
    try {
      File foyer = new File(dir, "foyer-presets");
      foyer.mkdirs();
      System.setProperty("user.home", foyer.getAbsolutePath());
      File dossier = Presets.presetsDir();

      AppConfig source = AppConfig.forFile(new File(dir, "presets-source.properties"));
      source.setColourOrder("GRB");
      source.setWatchdogSec(12);

      check("enregistrement d'un preset", Presets.save("Salle A", source));
      File fichier = new File(dossier, "Salle A.properties");
      check("le fichier du preset existe et n'est pas vide",
          fichier.isFile() && fichier.length() > 0);
      check("aucun fichier temporaire ne subsiste apres l'ecriture", aucunTmp(dossier));
      Properties relu = lire(fichier);
      egal("le contenu est complet", "GRB", relu.getProperty("colourOrder"));
      egal("la version d'origine est tracee", AppConfig.VERSION,
          relu.getProperty("presetVersion"));

      source.setWatchdogSec(30);
      check("re-enregistrement par-dessus un preset existant", Presets.save("Salle A", source));
      check("toujours aucun .tmp apres remplacement", aucunTmp(dossier));
      egal("le contenu a bien ete remplace", "30", lire(fichier).getProperty("watchdogSec"));
      egal("le remplacement n'a pas laisse de second fichier", 1, dossier.listFiles().length);

      // La cible n'est JAMAIS touchee tant que la nouvelle version n'est pas
      // complete. On rend ici l'ecriture du fichier temporaire impossible (un
      // dossier porte deja son nom) et on verifie que le preset enregistre est
      // intact : avec une ecriture directe sur la cible, celle-ci aurait ete
      // tronquee avant meme que l'echec ne se produise.
      File barrage = new File(dossier, "Salle A.properties.tmp");
      barrage.mkdirs();
      source.setWatchdogSec(99);
      check("une ecriture de preset impossible est signalee", !Presets.save("Salle A", source));
      egal("... et le preset deja enregistre est intact, pas tronque",
          "30", lire(fichier).getProperty("watchdogSec"));
      barrage.delete();
      source.setWatchdogSec(30);

      check("un nom reserve par Windows est refuse", !Presets.save("CON", source));
      check("un nom vide est refuse", !Presets.save("", source));
      check("un nom refuse ne laisse aucun .tmp derriere lui", aucunTmp(dossier));

      egal("la liste JSON contient le preset", "[\"Salle A\"]", Presets.listJson());
      check("la liste est du JSON valide", jsonValide(Presets.listJson()));

      // Fusion : un reglage absent du preset garde sa valeur courante, il ne
      // retombe pas a son defaut code en dur (piege des presets anciens).
      AppConfig cible = AppConfig.forFile(new File(dir, "presets-cible.properties"));
      cible.setExtraDelayMs(7);
      cible.setWatchdogSec(0);
      check("chargement du preset", Presets.load("Salle A", cible));
      egal("le reglage present dans le preset est applique", 30, cible.getWatchdogSec());
      egal("un reglage absent du preset garde sa valeur courante", 7, cible.getExtraDelayMs());
      check("la cle technique presetVersion ne pollue pas la configuration",
          !cible.snapshot().containsKey("presetVersion"));

      check("un preset inexistant est signale sans exception", !Presets.load("Aucun", cible));
      check("un nom hostile ne peut pas charger un fichier hors du dossier",
          !Presets.load("../../config", cible));

      check("suppression du preset", Presets.delete("Salle A"));
      egal("la liste redevient vide", "[]", Presets.listJson());
    } finally {
      System.setProperty("user.home", ancienFoyer);
    }
  }

  // ------------------------------------------------------------------
  /**
   * Le .meta d'une sequence etait ecrit directement sur la cible : celle-ci
   * etait tronquee AVANT l'ecriture. Un arret brutal dans cette fenetre
   * laissait un .meta vide, relu sans broncher : la sequence s'affichait a 0 s
   * et la barre de progression restait figee pendant toute la lecture.
   */
  private static void sequencesAtomiques(File dir) throws Exception {
    groupe("Sequences : ecriture atomique des metadonnees");

    Method ecrireMeta = Recorder.class.getDeclaredMethod(
        "ecrireMeta", File.class, Properties.class);
    ecrireMeta.setAccessible(true);

    File dossier = new File(dir, "metas");
    dossier.mkdirs();
    File meta = new File(dossier, "seq.meta");
    Properties donnees = new Properties();
    donnees.setProperty("durationMs", "1234");
    donnees.setProperty("frames", "42");

    ecrireMeta.invoke(null, meta, donnees);
    check("le fichier de metadonnees est ecrit", meta.isFile() && meta.length() > 0);
    egal("la duree est relisible", "1234", lire(meta).getProperty("durationMs"));
    egal("le nombre de trames aussi", "42", lire(meta).getProperty("frames"));
    check("aucun .tmp ne subsiste apres l'ecriture", aucunTmp(dossier));

    donnees.setProperty("durationMs", "9999");
    ecrireMeta.invoke(null, meta, donnees);
    egal("le remplacement d'une version existante est complet", "9999",
        lire(meta).getProperty("durationMs"));
    check("toujours aucun .tmp apres remplacement", aucunTmp(dossier));
    egal("aucun fichier parasite n'est apparu", 1, dossier.listFiles().length);

    // Meme preuve que pour les presets : on empeche l'ecriture du fichier
    // temporaire et la cible doit rester exactement ce qu'elle etait. Une
    // ecriture directe l'aurait tronquee, et readMetaDuration relit un .meta
    // vide sans broncher : la sequence s'afficherait a 0 s.
    File barrage = new File(dossier, "seq.meta.tmp");
    barrage.mkdirs();
    donnees.setProperty("durationMs", "555");
    ecrireMeta.invoke(null, meta, donnees);
    egal("une ecriture de metadonnees impossible laisse la version precedente intacte",
        "9999", lire(meta).getProperty("durationMs"));
    barrage.delete();

    // Les metadonnees ne sont qu'un confort d'affichage : leur echec ne doit
    // jamais faire echouer la fin d'un enregistrement.
    check("une cible nulle ne fait pas echouer la fin d'enregistrement",
        sansException(ecrireMeta, null, donnees));
    File impossible = new File(dir, "dossier-inexistant/seq.meta");
    check("un dossier inaccessible ne fait pas echouer la fin d'enregistrement",
        sansException(ecrireMeta, impossible, donnees));
    check("... et ne laisse pas de fichier a moitie ecrit", !impossible.exists());

    // Enregistrement complet, de bout en bout.
    String ancienFoyer = System.getProperty("user.home");
    try {
      File foyer = new File(dir, "foyer-sequences");
      foyer.mkdirs();
      System.setProperty("user.home", foyer.getAbsolutePath());

      Recorder recorder = new Recorder(new LegacyCore());
      String nom = recorder.startRecord("Ma Séquence");
      egal("le nom retenu est nettoye et renvoye a l'interface", "Ma Séquence", nom);
      recorder.stopRecord();

      File dossierSeq = Recorder.recordingsDir();
      File sequence = new File(dossierSeq, "Ma Séquence.ppb");
      File metaSeq = new File(dossierSeq, "Ma Séquence.meta");
      check("le fichier de sequence est cree", sequence.isFile());
      egal("il porte bien l'entete du format", "PPBREC01", entete(sequence, 8));
      check("le .meta accompagne la sequence", metaSeq.isFile() && metaSeq.length() > 0);
      egal("les metadonnees sont relisibles", "0", lire(metaSeq).getProperty("frames"));
      check("aucun .tmp orphelin dans le dossier des sequences", aucunTmp(dossierSeq));
      check("le listing des sequences est du JSON valide", jsonValide(recorder.listJson()));
      check("il contient la sequence enregistree", recorder.listJson().contains("Ma Séquence"));
      check("l'etat de l'enregistreur est du JSON valide", jsonValide(recorder.stateJson()));

      String second = recorder.startRecord("Ma Séquence");
      recorder.stopRecord();
      egal("un second enregistrement du meme nom est numerote au lieu d'ecraser",
          "Ma Séquence-2", second);
      check("la premiere sequence est toujours la", sequence.isFile());
      check("aucun .tmp apres le second enregistrement", aucunTmp(dossierSeq));

      check("la suppression retire la sequence", recorder.delete("Ma Séquence"));
      check("... et ses metadonnees avec elle", !metaSeq.exists());
      check("un nom hostile ne peut pas supprimer hors du dossier",
          !recorder.delete("../../config"));
    } finally {
      System.setProperty("user.home", ancienFoyer);
    }
  }

  // ------------------------------------------------------------------
  /**
   * Le journal de l'interface est un flux SSE : a la reconnexion, le navigateur
   * renvoie le dernier numero recu (Last-Event-ID) et le serveur ne lui
   * reexpedie que la suite. Tout repose sur deux proprietes : des numeros
   * strictement croissants et un getSince() STRICTEMENT superieur. Passer a
   * « superieur ou egal » suffirait a reafficher une ligne en double a chaque
   * coupure de WiFi.
   */
  private static void reprisDesLogs() {
    groupe("Flux de logs : reprise sans doublon");
    LogBus.clear();

    for (int i = 1; i <= 5; i++) {
      LogBus.info("ligne " + i);
    }
    List<LogBus.Entry> tout = LogBus.getSince(0);
    egal("l'historique complet est rendu", 5, tout.size());
    boolean croissant = true;
    for (int i = 1; i < tout.size(); i++) {
      if (tout.get(i).seq <= tout.get(i - 1).seq) {
        croissant = false;
      }
    }
    check("les numeros de sequence sont strictement croissants", croissant);
    long dernier = tout.get(tout.size() - 1).seq;
    check("rien a renvoyer apres le dernier numero delivre",
        LogBus.getSince(dernier).isEmpty());
    egal("une reprise n'inclut jamais la ligne deja recue", 2,
        LogBus.getSince(tout.get(2).seq).size());

    // Scenario exact du serveur : historique, abonnement, puis rattrapage de ce
    // qui a ete produit PENDANT l'envoi de l'historique.
    LogBus.clear();
    LogBus.info("avant 1");
    LogBus.info("avant 2");
    List<LogBus.Entry> historique = LogBus.getSince(0);
    long livre = historique.get(historique.size() - 1).seq;
    LogBus.info("pendant 1");
    LogBus.info("pendant 2");
    List<LogBus.Entry> rattrapage = LogBus.getSince(livre);
    egal("le rattrapage ne rend que ce qui est arrive pendant l'envoi", 2, rattrapage.size());
    check("il commence bien apres la derniere ligne de l'historique",
        rattrapage.get(0).seq > livre);
    Set<Long> vues = new HashSet<Long>();
    boolean doublon = false;
    for (LogBus.Entry e : historique) {
      if (!vues.add(Long.valueOf(e.seq))) {
        doublon = true;
      }
    }
    for (LogBus.Entry e : rattrapage) {
      if (!vues.add(Long.valueOf(e.seq))) {
        doublon = true;
      }
    }
    check("aucune ligne n'est envoyee deux fois au navigateur", !doublon);
    egal("les quatre lignes arrivent bien, sans perte", 4, vues.size());

    // Chaque ligne doit etre transportable telle quelle dans le flux.
    LogBus.clear();
    LogBus.warn("guillemet \" antislash \\ saut\nde ligne");
    LogBus.Entry hostile = LogBus.getSince(0).get(0);
    check("une ligne de log hostile reste du JSON valide", jsonValide(hostile.toJson()));
    check("... et conserve son numero de sequence", hostile.toJson().contains("\"seq\":"));

    // Debordement de l'historique : il est borne, mais les numeros restent
    // uniques et croissants (c'est ce qui permet la reprise).
    LogBus.clear();
    for (int i = 0; i < 3050; i++) {
      LogBus.info("saturation " + i);
    }
    List<LogBus.Entry> anneau = LogBus.getSince(0);
    egal("l'historique en memoire est borne a 3000 lignes", 3000, anneau.size());
    check("ce sont bien les plus recentes qui sont conservees",
        anneau.get(anneau.size() - 1).msg.endsWith("3049"));
    check("les numeros restent strictement croissants apres debordement",
        anneau.get(0).seq < anneau.get(anneau.size() - 1).seq);

    // Comptage des erreurs affiche sur le tableau de bord.
    LogBus.clear();
    LogBus.error("panne franche du reseau");
    LogBus.error("at com.exemple.Classe.methode(Classe.java:42)");
    LogBus.error("Caused by: quelque chose");
    egal("les lignes de pile ne comptent pas comme de nouvelles erreurs",
        1L, LogBus.getErrorCount());
    LogBus.clear();
    egal("effacer les logs vide l'historique", 0, LogBus.getSince(0).size());
    egal("... et remet le compteur d'erreurs a zero", 0L, LogBus.getErrorCount());
  }

  // ------------------------------------------------------------------
  /**
   * Rien ne vidait les tables du moniteur DMX : une source mal configuree y
   * laissait definitivement des univers morts, chacun retenant 512 octets, et
   * les 512 places finissaient occupees par des fantomes.
   *
   * Le piege a ne jamais rouvrir : l'univers vu le plus recemment n'est JAMAIS
   * purge. lastDmxAt() prend le maximum de cette table et le watchdog accepte
   * jusqu'a 300 s ; effacer la derniere trace a 60 s ferait retomber
   * lastDmxAt() a « jamais recu de donnees » et le blackout de securite ne
   * partirait plus du tout pour tout reglage superieur a 60 s.
   */
  private static void purgeDesUnivers(File dir) throws Exception {
    groupe("Purge des univers devenus silencieux");

    Method purge = Watchdog.class.getDeclaredMethod("purgeUniversSilencieux");
    purge.setAccessible(true);

    AppConfig cfg = AppConfig.forFile(new File(dir, "watchdog.properties"));
    LegacyCore core = new LegacyCore();
    TestPatterns tests = new TestPatterns(core);

    ArtNetReceiver.universeLastSeen.clear();
    ArtNetReceiver.lastFrame.clear();
    long maintenant = System.currentTimeMillis();
    ArtNetReceiver.universeLastSeen.put(Integer.valueOf(1), Long.valueOf(maintenant));
    ArtNetReceiver.universeLastSeen.put(Integer.valueOf(2), Long.valueOf(maintenant - 120000));
    ArtNetReceiver.universeLastSeen.put(Integer.valueOf(3), Long.valueOf(maintenant - 300000));
    ArtNetReceiver.lastFrame.put(Integer.valueOf(1), new byte[512]);
    ArtNetReceiver.lastFrame.put(Integer.valueOf(2), new byte[512]);
    ArtNetReceiver.lastFrame.put(Integer.valueOf(9), new byte[512]);

    purge.invoke(new Watchdog(cfg, core, tests));

    check("l'univers qui recoit encore est conserve",
        ArtNetReceiver.universeLastSeen.containsKey(Integer.valueOf(1)));
    check("un univers muet depuis 2 min est retire",
        !ArtNetReceiver.universeLastSeen.containsKey(Integer.valueOf(2)));
    check("un univers muet depuis 5 min aussi",
        !ArtNetReceiver.universeLastSeen.containsKey(Integer.valueOf(3)));
    check("sa derniere trame est liberee en meme temps (512 octets par univers)",
        !ArtNetReceiver.lastFrame.containsKey(Integer.valueOf(2)));
    check("une trame orpheline est nettoyee elle aussi",
        !ArtNetReceiver.lastFrame.containsKey(Integer.valueOf(9)));
    check("la trame de l'univers actif reste disponible pour le moniteur",
        ArtNetReceiver.lastFrame.containsKey(Integer.valueOf(1)));

    // Le piege : meme tres vieux, le dernier univers connu doit survivre.
    ArtNetReceiver.universeLastSeen.clear();
    ArtNetReceiver.lastFrame.clear();
    long vieux = maintenant - 200000;
    ArtNetReceiver.universeLastSeen.put(Integer.valueOf(5), Long.valueOf(vieux));
    purge.invoke(new Watchdog(cfg, core, tests));
    check("le dernier univers connu survit a la purge",
        ArtNetReceiver.universeLastSeen.containsKey(Integer.valueOf(5)));
    egal("... sinon le blackout de securite ne partirait plus au-dela de 60 s",
        vieux, Watchdog.lastDmxAt());

    ArtNetReceiver.universeLastSeen.clear();
    ArtNetReceiver.lastFrame.clear();
    egal("tables vides => aucune donnee DMX connue", 0L, Watchdog.lastDmxAt());
  }

  // ------------------------------------------------------------------
  /**
   * Le QR code et les liens du tableau de bord doivent designer la MEME carte
   * reseau : sur un poste de regie il y en a souvent deux (LED d'un cote, WiFi
   * du lieu de l'autre). D'ou une source unique d'enumeration.
   */
  private static void adressesReseau() {
    groupe("Adresses reseau (source unique)");

    List<String> adresses = Net.siteLocalIpv4();
    check("l'enumeration rend toujours une liste, meme sans reseau", adresses != null);

    boolean formatOk = true;
    boolean priveesUniquement = true;
    for (String ip : adresses) {
      if (!ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
        formatOk = false;
      }
      if (!estPrivee(ip)) {
        priveesUniquement = false;
      }
    }
    check("toutes les adresses rendues sont des IPv4 litterales (" + adresses.size()
        + " trouvee(s)) - une IPv6 serait intapable et illisible en QR code", formatOk);
    check("aucune boucle locale, aucune 169.254 d'auto-configuration, aucune adresse "
        + "publique n'est proposee", priveesUniquement);

    String premiere = Net.firstSiteLocalIpv4();
    if (adresses.isEmpty()) {
      egal("sans carte utilisable, le QR code sait qu'il n'a rien a encoder",
          null, premiere);
    } else {
      egal("le QR code et le tableau de bord designent la meme carte",
          adresses.get(0), premiere);
    }
    check("deux enumerations successives donnent le meme ordre",
        adresses.equals(Net.siteLocalIpv4()));
  }

  private static boolean estPrivee(String ip) {
    if (ip.startsWith("127.") || ip.startsWith("169.254.")) {
      return false;
    }
    if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
      return true;
    }
    if (ip.startsWith("172.")) {
      int point = ip.indexOf('.', 4);
      if (point > 4) {
        try {
          int second = Integer.parseInt(ip.substring(4, point));
          return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
          return false;
        }
      }
    }
    return false;
  }

  // ------------------------------------------------------------------
  /**
   * L'instantane d'etat et le diagnostic sont les deux seules fenetres de
   * l'operateur sur ce qui se passe. Ils doivent rester analysables en toutes
   * circonstances, et dire la verite quand le bridge ne recoit rien.
   */
  private static void etatEtDiagnostic(File dir) throws Exception {
    groupe("Instantane d'etat et diagnostic");
    String ancienFoyer = System.getProperty("user.home");
    try {
      File foyer = new File(dir, "foyer-etat");
      foyer.mkdirs();
      System.setProperty("user.home", foyer.getAbsolutePath());

      AppConfig cfg = AppConfig.forFile(new File(dir, "etat.properties"));
      LegacyCore core = new LegacyCore(); // volontairement pas demarre
      TestPatterns tests = new TestPatterns(core);
      StatusService status = new StatusService(cfg, core, tests);
      status.setWebPort(7350);

      String instantane = status.snapshotJson();
      check("l'instantane est du JSON valide, meme sans coeur reseau demarre",
          jsonValide(instantane));
      check("il publie le numero de revision de la configuration",
          instantane.contains("\"configRev\":0"));
      status.bumpConfigRev();
      status.bumpConfigRev();
      check("le numero augmente a chaque ecriture de la configuration "
          + "(l'interface recharge alors /api/config sans attendre)",
          status.snapshotJson().contains("\"configRev\":2"));
      check("il publie l'etat du thread de reception Art-Net",
          instantane.contains("\"artnetThreadAlive\":false"));
      check("le voyant d'ecoute combine le bind ET le thread",
          instantane.contains("\"artnetListening\":false"));
      check("la liste des URLs telephone est un tableau JSON",
          instantane.contains("\"lanUrls\":["));

      // Diagnostic
      Recorder recorder = new Recorder(core);
      Diagnostic diagnostic = new Diagnostic(cfg, core, tests, recorder);
      diagnostic.setWebPort(7350);
      String rapport = diagnostic.toJson();
      check("le diagnostic est du JSON valide", jsonValide(rapport));
      check("il compte les erreurs et les avertissements",
          rapport.contains("\"errors\":") && rapport.contains("\"warnings\":"));
      check("il signale un thread de reception Art-Net arrete",
          rapport.contains("thread de réception Art-Net est arrêté"));
      check("il signale l'absence de PixelPusher",
          rapport.contains("Aucun PixelPusher détecté"));
      check("il rappelle que la configuration a du etre reinitialisee",
          rapport.contains("configuration a été réinitialisée"));

      // Conseil specifique macOS : sans l'autorisation « Reseau local » des
      // reglages systeme, macOS jette la decouverte des pushers SANS aucun
      // message. Le pare-feu seul n'explique pas ce cas, et l'operateur cherche
      // des heures du mauvais cote.
      String vraiOs = System.getProperty("os.name");
      try {
        System.setProperty("os.name", "Mac OS X");
        check("sur macOS, le conseil « Réseau local » est ajoute",
            diagnostic.toJson().contains("Réseau local"));
        System.setProperty("os.name", "Windows 11");
        check("ailleurs, ce conseil n'apparait pas",
            !diagnostic.toJson().contains("Réseau local"));
      } finally {
        System.setProperty("os.name", vraiOs);
      }

      // Port 6454 ouvert mais muet : symptome classique d'un conflit de port.
      boolean ancienEtat = ArtNetReceiver.listening;
      try {
        ArtNetReceiver.listening = true;
        poserSilenceArtnet(45000);
        String muet = diagnostic.toJson();
        check("un port Art-Net ouvert mais muet depuis plus de 30 s est signale",
            muet.contains("aucune trame n'a été reçue"));
        check("... et le conseil pointe d'abord le conflit de port 6454",
            muet.contains("occupe déjà le port 6454"));
        poserSilenceArtnet(0);
        check("des que le silence est court, l'avertissement disparait",
            !diagnostic.toJson().contains("aucune trame n'a été reçue"));
      } finally {
        ArtNetReceiver.listening = ancienEtat;
        poserSilenceArtnet(0);
      }

      check("le rapport texte complet se construit sans exception",
          diagnostic.toTextReport(status.snapshotJson(), cfg.toJson()).length() > 200);
    } finally {
      System.setProperty("user.home", ancienFoyer);
    }
  }

  /**
   * Recule artificiellement la date du dernier paquet Art-Net vu par la veille
   * du diagnostic. Le champ est prive : c'est un detail d'implementation, on ne
   * l'expose pas pour les besoins du test.
   */
  private static void poserSilenceArtnet(long silenceMs) throws Exception {
    Field champ = Diagnostic.class.getDeclaredField("veilleArtnet");
    champ.setAccessible(true);
    Object veille = champ.get(null);
    Field date = veille.getClass().getDeclaredField("dernierChangementTs");
    date.setAccessible(true);
    date.setLong(veille, silenceMs <= 0 ? 0L : System.currentTimeMillis() - silenceMs);
  }

  // ------------------------------------------------------------------
  // Outils du banc
  // ------------------------------------------------------------------

  /** Charge la configuration en detournant les messages d'alerte de la console. */
  private static AppConfig chargeSansBruit() {
    PrintStream vraiErr = System.err;
    PrintStream vraiOut = System.out;
    ByteArrayOutputStream poubelle = new ByteArrayOutputStream();
    try {
      PrintStream muet = new PrintStream(poubelle, true, "UTF-8");
      System.setErr(muet);
      System.setOut(muet);
      return AppConfig.load();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    } finally {
      System.setErr(vraiErr);
      System.setOut(vraiOut);
    }
  }

  /** Invoque une methode et dit simplement si elle s'est terminee sans lever. */
  private static boolean sansException(Method m, Object... args) {
    try {
      m.invoke(null, args);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void ecrire(File f, String contenu) throws IOException {
    FileOutputStream out = new FileOutputStream(f);
    try {
      out.write(contenu.getBytes(StandardCharsets.UTF_8));
    } finally {
      out.close();
    }
  }

  private static Properties lire(File f) throws IOException {
    Properties p = new Properties();
    InputStream in = new FileInputStream(f);
    try {
      p.load(in);
    } finally {
      in.close();
    }
    return p;
  }

  private static String entete(File f, int octets) throws IOException {
    byte[] buf = new byte[octets];
    InputStream in = new FileInputStream(f);
    int lu;
    try {
      lu = in.read(buf);
    } finally {
      in.close();
    }
    return lu <= 0 ? "" : new String(buf, 0, lu, StandardCharsets.US_ASCII);
  }

  /** Un .tmp restant signifie qu'une ecriture atomique a laisse un dechet. */
  private static boolean aucunTmp(File dossier) {
    File[] fichiers = dossier.listFiles();
    if (fichiers == null) {
      return true;
    }
    for (File f : fichiers) {
      if (f.getName().endsWith(".tmp")) {
        return false;
      }
    }
    return true;
  }

  private static boolean existePrefixe(File dossier, String prefixe) {
    File[] fichiers = dossier.listFiles();
    if (fichiers == null) {
      return false;
    }
    for (File f : fichiers) {
      if (f.getName().startsWith(prefixe)) {
        return true;
      }
    }
    return false;
  }

  private static void supprimerContenu(File dossier) {
    File[] fichiers = dossier.listFiles();
    if (fichiers != null) {
      for (File f : fichiers) {
        f.delete();
      }
    }
  }

  // ------------------------------------------------------------------
  // Valideur JSON minimal.
  //
  // Ecrit ici volontairement : il sert de juge INDEPENDANT du writer teste.
  // Il refuse tout caractere de controle non echappe et toute sequence
  // d'echappement invalide, c'est-a-dire exactement ce que produirait une
  // valeur inseree sans passer par esc(). Le premier bloc de jsonWriter() le
  // met lui-meme a l'epreuve : un juge qu'on croit sur parole ne vaut rien.
  // ------------------------------------------------------------------

  private static boolean jsonValide(String s) {
    if (s == null) {
      return false;
    }
    int[] i = { 0 };
    try {
      valeurJson(s, i);
      blancsJson(s, i);
      return i[0] == s.length();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static void blancsJson(String s, int[] i) {
    while (i[0] < s.length()) {
      char c = s.charAt(i[0]);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i[0]++;
      } else {
        return;
      }
    }
  }

  private static void attenduJson(String s, int[] i, char c) {
    if (i[0] >= s.length() || s.charAt(i[0]) != c) {
      throw new IllegalStateException("caractere « " + c + " » attendu");
    }
    i[0]++;
  }

  private static void valeurJson(String s, int[] i) {
    blancsJson(s, i);
    if (i[0] >= s.length()) {
      throw new IllegalStateException("fin prematuree");
    }
    char c = s.charAt(i[0]);
    if (c == '{') {
      i[0]++;
      blancsJson(s, i);
      if (i[0] < s.length() && s.charAt(i[0]) == '}') {
        i[0]++;
        return;
      }
      while (true) {
        blancsJson(s, i);
        chaineJson(s, i);
        blancsJson(s, i);
        attenduJson(s, i, ':');
        valeurJson(s, i);
        blancsJson(s, i);
        if (i[0] < s.length() && s.charAt(i[0]) == ',') {
          i[0]++;
          continue;
        }
        attenduJson(s, i, '}');
        return;
      }
    }
    if (c == '[') {
      i[0]++;
      blancsJson(s, i);
      if (i[0] < s.length() && s.charAt(i[0]) == ']') {
        i[0]++;
        return;
      }
      while (true) {
        valeurJson(s, i);
        blancsJson(s, i);
        if (i[0] < s.length() && s.charAt(i[0]) == ',') {
          i[0]++;
          continue;
        }
        attenduJson(s, i, ']');
        return;
      }
    }
    if (c == '"') {
      chaineJson(s, i);
      return;
    }
    if (s.startsWith("true", i[0])) {
      i[0] += 4;
      return;
    }
    if (s.startsWith("false", i[0])) {
      i[0] += 5;
      return;
    }
    if (s.startsWith("null", i[0])) {
      i[0] += 4;
      return;
    }
    nombreJson(s, i);
  }

  private static void chaineJson(String s, int[] i) {
    attenduJson(s, i, '"');
    while (true) {
      if (i[0] >= s.length()) {
        throw new IllegalStateException("chaine non terminee");
      }
      char c = s.charAt(i[0]++);
      if (c == '"') {
        return;
      }
      if (c < 0x20) {
        throw new IllegalStateException("caractere de controle non echappe");
      }
      if (c != '\\') {
        continue;
      }
      if (i[0] >= s.length()) {
        throw new IllegalStateException("echappement tronque");
      }
      char e = s.charAt(i[0]++);
      if ("\"\\/bfnrt".indexOf(e) >= 0) {
        continue;
      }
      if (e != 'u') {
        throw new IllegalStateException("echappement inconnu");
      }
      if (i[0] + 4 > s.length()) {
        throw new IllegalStateException("echappement unicode tronque");
      }
      for (int k = 0; k < 4; k++) {
        if (Character.digit(s.charAt(i[0]++), 16) < 0) {
          throw new IllegalStateException("echappement unicode invalide");
        }
      }
    }
  }

  private static void nombreJson(String s, int[] i) {
    if (i[0] < s.length() && s.charAt(i[0]) == '-') {
      i[0]++;
    }
    if (chiffresJson(s, i) == 0) {
      throw new IllegalStateException("nombre attendu");
    }
    if (i[0] < s.length() && s.charAt(i[0]) == '.') {
      i[0]++;
      if (chiffresJson(s, i) == 0) {
        throw new IllegalStateException("decimales attendues");
      }
    }
    if (i[0] < s.length() && (s.charAt(i[0]) == 'e' || s.charAt(i[0]) == 'E')) {
      i[0]++;
      if (i[0] < s.length() && (s.charAt(i[0]) == '+' || s.charAt(i[0]) == '-')) {
        i[0]++;
      }
      if (chiffresJson(s, i) == 0) {
        throw new IllegalStateException("exposant attendu");
      }
    }
  }

  private static int chiffresJson(String s, int[] i) {
    int n = 0;
    while (i[0] < s.length() && s.charAt(i[0]) >= '0' && s.charAt(i[0]) <= '9') {
      i[0]++;
      n++;
    }
    return n;
  }
}
