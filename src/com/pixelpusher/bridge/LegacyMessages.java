package com.pixelpusher.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Traduction et reclassement des messages emis par le coeur legacy.
 *
 * Le code legacy ecrit sur System.err des messages qui ne sont pas tous des
 * erreurs : certains sont de simples avertissements (le pusher demande a
 * ralentir), d'autres de la pure information (thread deja arrete a l'extinction).
 * Comme LogBus classe tout ce qui vient de System.err en ERROR, le tableau de
 * bord affichait un compteur d'erreurs alarmant pour des situations normales.
 *
 * Cette classe fait trois choses, sans toucher au legacy :
 *  - elle corrige le niveau (ERROR -> WARN ou INFO) des messages connus ;
 *  - elle les traduit en francais et les rend actionnables ;
 *  - elle compte les signaux exploitables par le Diagnostic (demandes de
 *    ralentissement, pixels hors ruban, paquets malformes, firmware trop ancien).
 *
 * ATTENTION, invariant a respecter : classify() s'execute sur le thread
 * APPELANT, y compris le thread de reception Art-Net. Plusieurs motifs traites
 * ici (paquet trop court, paquet trop long, longueur incoherente, start code non
 * nul, ArtPollReply non envoyee) sont emis par ArtNetReceiver depuis ce thread,
 * et un emetteur mal configure peut en produire des centaines par seconde. Cette
 * methode doit donc rester sans I/O, sans verrou, sans allocation tant qu'aucun
 * motif ne correspond. Les motifs du chemin chaud sont testes en premier pour
 * que le cas frequent coute deux ou trois recherches de sous-chaine, pas vingt.
 */
public final class LegacyMessages {

  /** Resultat d'un reclassement : niveau corrige + message reecrit. */
  public static final class Classified {
    public final String level;
    public final String message;

    Classified(String level, String message) {
      this.level = level;
      this.message = message;
    }
  }

  // ----- signaux exploites par le Diagnostic -----

  private static final AtomicLong THROTTLE_REQUESTS = new AtomicLong();
  private static volatile long throttleRequestTs = 0;

  private static final AtomicLong PIXEL_OUT_OF_RANGE = new AtomicLong();
  private static volatile long pixelOutOfRangeTs = 0;

  private static final AtomicLong MALFORMED_PACKETS = new AtomicLong();
  private static volatile long malformedPacketTs = 0;

  private static volatile String firmwareWarning = null;

  private LegacyMessages() {
  }

  /** Nombre de fois ou un pusher a demande a ralentir sans que l'auto-throttle soit actif. */
  public static long getThrottleRequests() {
    return THROTTLE_REQUESTS.get();
  }

  public static long getThrottleRequestTs() {
    return throttleRequestTs;
  }

  /** Nombre d'ecritures vers un pixel inexistant (mapping plus long que le ruban reel). */
  public static long getPixelOutOfRange() {
    return PIXEL_OUT_OF_RANGE.get();
  }

  public static long getPixelOutOfRangeTs() {
    return pixelOutOfRangeTs;
  }

  /** Nombre de paquets Art-Net rejetes parce que malformes. */
  public static long getMalformedPackets() {
    return MALFORMED_PACKETS.get();
  }

  public static long getMalformedPacketTs() {
    return malformedPacketTs;
  }

  /** Message de firmware trop ancien, ou null si le firmware est accepte. */
  public static String getFirmwareWarning() {
    return firmwareWarning;
  }

  /** Remet les compteurs a zero (bouton « effacer les logs »). */
  public static void reset() {
    THROTTLE_REQUESTS.set(0);
    PIXEL_OUT_OF_RANGE.set(0);
    MALFORMED_PACKETS.set(0);
    throttleRequestTs = 0;
    pixelOutOfRangeTs = 0;
    malformedPacketTs = 0;
    firmwareWarning = null;
  }

  /**
   * Reclasse et traduit un message du legacy.
   *
   * @return le message corrige, ou null si la ligne n'est pas un message legacy
   *         connu (elle part alors telle quelle avec son niveau d'origine).
   */
  public static Classified classify(String level, String msg) {
    // Court-circuit : le motif connu le plus court fait 16 caracteres. Les
    // lignes plus courtes (et les messages de l'application elle-meme, qui sont
    // deja en francais) sortent sans payer la table de correspondance.
    if (msg == null || msg.length() < 16) {
      return null;
    }

    // ---- chemin chaud : messages emis par le thread de reception Art-Net ----
    // Teste en premier : une source qui inonde le port 6454 de paquets invalides
    // fait passer ce thread ici pour chaque paquet.
    if (msg.indexOf("Received short Art-Net packet") >= 0) {
      compterPaquetMalforme();
      return new Classified("WARN", "Paquet Art-Net trop court reçu, ignoré. "
          + "Un autre logiciel émet peut-être sur le port 6454.");
    }
    if (msg.indexOf("Received excessively long Art-Net packet") >= 0) {
      compterPaquetMalforme();
      return new Classified("WARN", "Paquet Art-Net trop long reçu, ignoré.");
    }
    if (msg.indexOf("Expected Art-Net datagram length") >= 0) {
      compterPaquetMalforme();
      return new Classified("WARN", "Longueur de paquet Art-Net incohérente, paquet ignoré ("
          + msg.trim() + ").");
    }
    if (msg.indexOf("Non-zero start data received") >= 0) {
      return new Classified("INFO", "Paquet Art-Net avec un code de départ non nul ignoré "
          + "(RDM ou trame de service, ce n'est pas de l'éclairage).");
    }
    if (msg.indexOf("Failed to send ArtPollReply") >= 0) {
      return new Classified("WARN", "Réponse ArtPoll non envoyée : la source ne verra pas "
          + "ce bridge dans sa liste de nodes. Le flux DMX fonctionne quand même.");
    }

    // ---- fluidite : le pusher n'arrive plus a suivre ----
    if (msg.indexOf("autothrottle is disabled") >= 0) {
      THROTTLE_REQUESTS.incrementAndGet();
      throttleRequestTs = System.currentTimeMillis();
      return new Classified("WARN", "Le PixelPusher " + carte(msg)
          + "reçoit les trames plus vite qu'il ne peut les afficher, mais l'auto-throttle "
          + "est désactivé. Active-le dans Configuration → Fluidité, ou baisse la limite "
          + "de trames. Ce n'est pas une panne : les LED continuent de fonctionner.");
    }
    if (msg.indexOf("extra delay now ") >= 0) {
      return new Classified("INFO", "Auto-throttle : le bridge ralentit pour le PixelPusher "
          + carte(msg) + "(délai supplémentaire " + apres(msg, "extra delay now ") + " ms).");
    }

    // ---- mapping : on ecrit au-dela de la longueur reelle du ruban ----
    if (msg.indexOf("but it wasn't there") >= 0) {
      PIXEL_OUT_OF_RANGE.incrementAndGet();
      pixelOutOfRangeTs = System.currentTimeMillis();
      return new Classified("WARN", "Le mapping vise le pixel "
          + apres(msg, "Tried to write to pixel ").replace(" but it wasn't there.", "").trim()
          + ", qui n'existe pas sur ce ruban. Vérifie pixels_per_strip dans le pixel.rc "
          + "du pusher et l'univers de départ de ta source.");
    }

    // ---- firmware du pusher ----
    if (msg.indexOf("This PixelPusher Library requires firmware revision") >= 0) {
      firmwareWarning = "Firmware du PixelPusher plus ancien que celui attendu par la librairie.";
      return new Classified("WARN", "Ce PixelPusher utilise un firmware plus ancien que celui "
          + "attendu (" + apres(msg, "revision ") + " minimum). Mise à jour conseillée.");
    }
    if (msg.indexOf("This PixelPusher is using") >= 0) {
      return new Classified("WARN", "Firmware détecté sur le pusher : "
          + apres(msg, "is using ") + ".");
    }
    if (msg.indexOf("This is not expected to work") >= 0) {
      return new Classified("WARN", "Ce firmware n'est pas garanti compatible. "
          + "Si les LED se comportent mal, commence par mettre le pusher à jour.");
    }

    // ---- bruit interne du legacy : informatif, jamais une erreur ----
    if (msg.indexOf("Already have a DeviceExpiryTask") >= 0
        || msg.indexOf("Already have a DiscoveryListener") >= 0
        || msg.indexOf("This happens if you call size()") >= 0
        || msg.indexOf("DeviceRegistry being instantiated for a second time") >= 0) {
      return new Classified("INFO", "Initialisation du registre : composant déjà en place, "
          + "création ignorée (comportement normal).");
    }
    if (msg.indexOf("Interrupted terminating CardThread") >= 0) {
      return new Classified("INFO", "Thread d'envoi arrêté (extinction ou pusher disparu).");
    }
    if (msg.indexOf("but it was already gone") >= 0) {
      return new Classified("INFO", "Thread d'envoi déjà libéré pour un pusher disparu.");
    }
    if (msg.indexOf("Concurrent modification exception attempting to generate") >= 0) {
      return new Classified("WARN", "La liste des pushers a changé pendant sa relecture ; "
          + "le bridge réessaie au prochain cycle (sans conséquence sur les LED).");
    }
    if (msg.indexOf("could not resolve 0.0.0.0") >= 0) {
      return new Classified("WARN", "Impossible de résoudre l'adresse d'écoute générique. "
          + "Vérifie la configuration réseau de la machine.");
    }

    return null;
  }

  private static void compterPaquetMalforme() {
    MALFORMED_PACKETS.incrementAndGet();
    malformedPacketTs = System.currentTimeMillis();
  }

  /**
   * Transforme le prefixe legacy "Group G card C " en "(groupe G, carte C) ".
   * Renvoie une chaine vide si le message n'a pas cette forme.
   */
  private static String carte(String msg) {
    int g = msg.indexOf("Group ");
    int c = msg.indexOf(" card ");
    if (g < 0 || c < 0 || c < g) {
      return "";
    }
    String groupe = msg.substring(g + 6, c).trim();
    String reste = msg.substring(c + 6).trim();
    int sp = reste.indexOf(' ');
    String carteNum = sp > 0 ? reste.substring(0, sp) : reste;
    if (groupe.length() == 0 || carteNum.length() == 0) {
      return "";
    }
    return "(groupe " + groupe + ", carte " + carteNum + ") ";
  }

  /** Renvoie ce qui suit un marqueur dans le message, ou "" s'il est absent. */
  private static String apres(String msg, String marqueur) {
    int i = msg.indexOf(marqueur);
    if (i < 0) {
      return "";
    }
    return msg.substring(i + marqueur.length()).trim();
  }
}
