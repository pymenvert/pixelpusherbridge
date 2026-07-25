package com.pixelpusher.bridge;

import static com.pixelpusher.bridge.Harness.check;
import static com.pixelpusher.bridge.Harness.egal;
import static com.pixelpusher.bridge.Harness.groupe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Tests de bout en bout du serveur HTTP de secours ET des garde-fous que le
 * serveur web applique a toute requete entrante.
 *
 * Le serveur de secours prend le relais quand le serveur du JDK ne peut pas
 * demarrer (pare-feu bloquant le selecteur NIO). C'est donc du code neuf sur le
 * chemin de l'interface : il doit etre couvert serieusement, y compris sur les
 * cas tordus (requete malformee, corps enorme, connexion reutilisee, charge).
 *
 * Les garde-fous de WebServer (controle d'origine, limite de taille du corps,
 * lecture des valeurs du formulaire) sont des methodes privees : ce sont des
 * details d'implementation, pas une API. On les sollicite donc par reflexion,
 * en leur presentant de VRAIS echanges HTTP produits par le serveur de secours
 * — pas des objets simules qui ne prouveraient rien.
 */
public final class HttpTests {

  private HttpTests() {
  }

  // Methodes privees de WebServer, resolues une fois au demarrage du groupe.
  private static Method mCheckOrigin;
  private static Method mIsLocalRequest;
  private static Method mParseForm;
  private static Method mReadAll;
  private static Method mParseDouble;
  private static Method mParseInt;
  private static Method mParseLong;

  static void run() throws Exception {
    groupe("Serveur HTTP de secours");

    try {
      resoudreMethodes();
    } catch (NoSuchMethodException e) {
      // Un garde-fou a disparu de WebServer : on le dit en clair avant de
      // laisser l'echec remonter, sinon le banc s'arreterait sur une trace
      // incomprehensible.
      check("les garde-fous de WebServer sont toujours en place (" + e.getMessage() + ")", false);
      throw e;
    }

    MiniHttpServer serveur = MiniHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final int port = serveur.getPort();
    check("le serveur obtient un port", port > 0);

    serveur.createContext("/", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        repond(ex, 200, "text/plain; charset=utf-8", "racine");
      }
    });
    serveur.createContext("/api/status", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        repond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
      }
    });
    serveur.createContext("/api/echo", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = ex.getRequestBody().read(buf)) > 0) {
          bos.write(buf, 0, n);
        }
        repond(ex, 200, "text/plain; charset=utf-8",
            ex.getRequestMethod() + "|" + ex.getRequestURI().getQuery() + "|"
            + new String(bos.toByteArray(), StandardCharsets.UTF_8));
      }
    });
    serveur.createContext("/api/flux", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0); // longueur inconnue : flux continu
        OutputStream os = ex.getResponseBody();
        for (int i = 0; i < 3; i++) {
          os.write(("data: trame" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
          os.flush();
        }
        os.close();
      }
    });
    serveur.createContext("/api/boum", new HttpHandler() {
      public void handle(HttpExchange ex) {
        throw new IllegalStateException("panne simulee dans un handler");
      }
    });

    // ----- endpoints qui exercent les garde-fous reels de WebServer -----

    // Reproduit ce que fait SafeHandler avant d'appeler le moindre handler :
    // si checkOrigin refuse, il a deja emis lui-meme sa reponse 403.
    serveur.createContext("/api/origine", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        Object autorise;
        try {
          autorise = mCheckOrigin.invoke(null, ex);
        } catch (Exception e) {
          repond(ex, 500, "text/plain; charset=utf-8", "reflexion : " + e);
          return;
        }
        if (Boolean.TRUE.equals(autorise)) {
          repond(ex, 200, "text/plain; charset=utf-8", "commande acceptee");
        }
      }
    });
    serveur.createContext("/api/local", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        try {
          repond(ex, 200, "text/plain; charset=utf-8",
              String.valueOf(mIsLocalRequest.invoke(null, ex)));
        } catch (Exception e) {
          repond(ex, 500, "text/plain; charset=utf-8", "reflexion : " + e);
        }
      }
    });
    // Le corps trop volumineux remonte sous la forme d'une IOException dediee,
    // que SafeHandler traduit en 413 : on verifie ici le type exact et la
    // taille a partir de laquelle il est leve.
    serveur.createContext("/api/formulaire", new HttpHandler() {
      public void handle(HttpExchange ex) throws IOException {
        try {
          Map<?, ?> champs = (Map<?, ?>) mParseForm.invoke(null, ex);
          repond(ex, 200, "text/plain; charset=utf-8",
              champs.size() + "|" + champs.get("action") + "|" + champs.get("nom"));
        } catch (InvocationTargetException e) {
          Throwable cause = e.getCause();
          repond(ex, 413, "text/plain; charset=utf-8",
              cause.getClass().getSimpleName() + "|" + (cause instanceof IOException));
        } catch (Exception e) {
          repond(ex, 500, "text/plain; charset=utf-8", "reflexion : " + e);
        }
      }
    });
    serveur.start();

    try {
      routage(port);
      corpsEtMethodes(port);
      casTordus(port);
      connexionReutilisee(port);
      flux(port);
      charge(port);
      protectionCsrf(port);
      corpsTropVolumineux(port);
      valeursDuFormulaire();
    } finally {
      serveur.stop();
    }

    // Apres stop(), le port doit etre reellement libere : c'est tout l'interet
    // d'avoir remplace le serveur qui fuyait ses ports.
    Thread.sleep(200);
    boolean liberable = true;
    try {
      MiniHttpServer reprise = MiniHttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      reprise.stop();
    } catch (IOException e) {
      liberable = false;
    }
    check("le port est reellement libere a l'arret", liberable);
  }

  private static void resoudreMethodes() throws Exception {
    mCheckOrigin = methodePrivee("checkOrigin", HttpExchange.class);
    mIsLocalRequest = methodePrivee("isLocalRequest", HttpExchange.class);
    mParseForm = methodePrivee("parseForm", HttpExchange.class);
    mReadAll = methodePrivee("readAll", InputStream.class, int.class);
    mParseDouble = methodePrivee("parseDouble", String.class, double.class);
    mParseInt = methodePrivee("parseInt", String.class, int.class);
    mParseLong = methodePrivee("parseLong", String.class, long.class);
  }

  private static Method methodePrivee(String nom, Class<?>... signature) throws Exception {
    Method m = WebServer.class.getDeclaredMethod(nom, signature);
    m.setAccessible(true);
    return m;
  }

  // ------------------------------------------------------------------
  private static void routage(int port) throws Exception {
    Reponse r = get(port, "/");
    egal("la racine repond 200", 200, r.code);
    egal("le corps de la racine est correct", "racine", r.corps);
    egal("le type de contenu est transmis", "text/plain; charset=utf-8",
        r.entetes.get("content-type"));

    r = get(port, "/api/status");
    egal("le contexte le plus specifique gagne", "{\"ok\":true}", r.corps);

    r = get(port, "/api/status?depuis=42");
    egal("une chaine de requete n'empeche pas le routage", 200, r.code);

    r = get(port, "/nexistepas");
    egal("un chemin inconnu tombe sur la racine (comportement du JDK)", 200, r.code);
  }

  // ------------------------------------------------------------------
  private static void corpsEtMethodes(int port) throws Exception {
    Reponse r = post(port, "/api/echo?u=1", "action=blackout&valeur=2");
    egal("un POST est bien recu", 200, r.code);
    egal("methode, requete et corps arrivent intacts",
        "POST|u=1|action=blackout&valeur=2", r.corps);

    r = post(port, "/api/echo", "");
    egal("un POST sans corps fonctionne", 200, r.code);

    // Accents : le corps doit survivre a l'aller-retour en UTF-8.
    r = post(port, "/api/echo", "nom=Répétition générale");
    check("les accents traversent le serveur",
        r.corps.contains("Répétition générale"));

    StringBuilder gros = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      gros.append("x");
    }
    r = post(port, "/api/echo", "data=" + gros);
    egal("un corps de 5 Ko passe", 200, r.code);
    check("le corps de 5 Ko est complet", r.corps.length() > 5000);
  }

  // ------------------------------------------------------------------
  private static void casTordus(int port) throws Exception {
    // Corps annonce au-dela de la limite : refus immediat, sans jamais allouer
    // la memoire correspondante.
    Socket s = new Socket();
    s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
    s.setSoTimeout(4000);
    s.getOutputStream().write(("POST /api/echo HTTP/1.1\r\nHost: x\r\n"
        + "Content-Length: 999999999\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    s.getOutputStream().flush();
    Reponse r = lire(s.getInputStream());
    egal("un corps enorme est refuse (413)", 413, r.code);
    s.close();

    r = brut(port, "n'importe quoi\r\n\r\n");
    egal("une ligne de requete malformee renvoie 400", 400, r.code);

    r = brut(port, "POST /api/echo HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n");
    egal("un Content-Length non numerique renvoie 400", 400, r.code);

    r = brut(port, "POST /api/echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n");
    egal("le mode chunked non supporte renvoie 411", 411, r.code);

    r = get(port, "/api/boum");
    egal("une exception dans un handler renvoie 500 au lieu de couper", 500, r.code);

    // Le serveur doit survivre a tout ce qui precede.
    r = get(port, "/api/status");
    egal("le serveur repond toujours apres les cas tordus", 200, r.code);
  }

  // ------------------------------------------------------------------
  private static void connexionReutilisee(int port) throws Exception {
    Socket s = new Socket();
    s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
    s.setSoTimeout(4000);
    OutputStream out = s.getOutputStream();
    InputStream in = s.getInputStream();

    out.write("GET /api/status HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    out.flush();
    Reponse r1 = lire(in);
    egal("premiere requete sur la connexion", 200, r1.code);

    out.write("GET /api/status HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    out.flush();
    Reponse r2 = lire(in);
    egal("seconde requete sur la MEME connexion", 200, r2.code);
    egal("meme corps", r1.corps, r2.corps);
    s.close();

    // Connection: close doit etre respecte.
    s = new Socket();
    s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
    s.setSoTimeout(4000);
    s.getOutputStream().write(("GET /api/status HTTP/1.1\r\nHost: x\r\n"
        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    s.getOutputStream().flush();
    lire(s.getInputStream());
    egal("le serveur ferme quand le client le demande", -1, s.getInputStream().read());
    s.close();
  }

  // ------------------------------------------------------------------
  private static void flux(int port) throws Exception {
    Socket s = new Socket();
    s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
    s.setSoTimeout(5000);
    s.getOutputStream().write("GET /api/flux HTTP/1.1\r\nHost: x\r\n\r\n"
        .getBytes(StandardCharsets.US_ASCII));
    s.getOutputStream().flush();

    ByteArrayOutputStream tout = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    int n;
    try {
      while ((n = s.getInputStream().read(buf)) > 0) {
        tout.write(buf, 0, n);
      }
    } catch (IOException ignored) {
    }
    String reponse = new String(tout.toByteArray(), StandardCharsets.UTF_8);
    s.close();

    check("le flux annonce un type event-stream", reponse.contains("text/event-stream"));
    check("le flux ne declare pas de Content-Length", !reponse.contains("Content-Length"));
    check("le flux annonce la fermeture de connexion", reponse.contains("Connection: close"));
    check("les trois trames sont arrivees", reponse.contains("data: trame0")
        && reponse.contains("data: trame1") && reponse.contains("data: trame2"));
  }

  // ------------------------------------------------------------------
  private static void charge(final int port) throws Exception {
    final int clients = 30;
    final CountDownLatch depart = new CountDownLatch(1);
    final CountDownLatch fini = new CountDownLatch(clients);
    final AtomicInteger reussites = new AtomicInteger();
    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < clients; i++) {
      Thread t = new Thread(new Runnable() {
        public void run() {
          try {
            depart.await();
            Reponse r = get(port, "/api/status");
            if (r.code == 200 && "{\"ok\":true}".equals(r.corps)) {
              reussites.incrementAndGet();
            }
          } catch (Exception ignored) {
          } finally {
            fini.countDown();
          }
        }
      });
      t.setDaemon(true);
      threads.add(t);
      t.start();
    }
    depart.countDown();
    fini.await(25, TimeUnit.SECONDS);
    egal(clients + " requetes simultanees toutes servies", clients, reussites.get());
  }

  // ------------------------------------------------------------------
  /**
   * Protection CSRF et restrictions d'origine.
   *
   * Sans ce controle, n'importe quel onglet ouvert sur l'ordinateur de regie
   * peut poster sur /api/action : un POST en form-urlencoded est une requete
   * « simple » au sens du navigateur, il part sans autorisation prealable et la
   * page malveillante n'a meme pas besoin d'en lire la reponse pour arreter le
   * bridge en pleine representation.
   */
  private static void protectionCsrf(int port) throws Exception {
    groupe("Protection CSRF et origine des commandes");

    Reponse r = postAvec(port, "/api/origine", "action=blackout", "");
    egal("un POST sans en-tete Origin est accepte (outils en ligne de commande)",
        200, r.code);

    r = postAvec(port, "/api/origine", "action=blackout", "Origin: http://x\r\n");
    egal("un POST venu de l'interface elle-meme est accepte", 200, r.code);

    r = postAvec(port, "/api/origine", "action=blackout", "Origin: HTTP://X\r\n");
    egal("la comparaison ignore la casse (un navigateur peut normaliser)", 200, r.code);

    r = postAvec(port, "/api/origine", "action=stop", "Origin: http://page-piegee.example\r\n");
    egal("un POST venu d'une AUTRE page est refuse", 403, r.code);
    check("le refus est explique a l'operateur", r.corps.contains("Origine non autoris"));

    r = postAvec(port, "/api/origine", "action=stop", "Origin: http://x:9999\r\n");
    egal("un autre service de la meme machine, sur un autre port, est refuse", 403, r.code);

    r = postAvec(port, "/api/origine", "action=stop", "Origin: null\r\n");
    egal("un Origin « null » (iframe bac a sable) est refuse", 403, r.code);

    r = postAvec(port, "/api/origine", "action=stop", "Origin: http://x.evil.example\r\n");
    egal("un hote qui se contente de finir par le bon nom est refuse", 403, r.code);

    // La lecture n'est jamais bloquee : seules les commandes le sont.
    r = brut(port, "GET /api/origine HTTP/1.1\r\nHost: x\r\n"
        + "Origin: http://page-piegee.example\r\nConnection: close\r\n\r\n");
    egal("une simple lecture n'est pas concernee par le controle d'origine", 200, r.code);

    // Arret et redemarrage sont en plus reserves a l'ordinateur du bridge.
    r = get(port, "/api/local");
    egal("une requete venue de la boucle locale est bien reconnue comme locale "
        + "(arret et redemarrage autorises depuis la regie)", "true", r.corps);
  }

  // ------------------------------------------------------------------
  /**
   * Refus d'un corps de requete trop volumineux.
   *
   * Deux garde-fous distincts : le refus immediat quand la taille annoncee
   * depasse la limite (inutile de faire transiter des mega-octets pour les
   * rejeter ensuite), et le controle PENDANT la lecture, qui empeche un client
   * qui ment sur sa taille de faire grossir la memoire du bridge sans limite.
   */
  private static void corpsTropVolumineux(int port) throws Exception {
    groupe("Refus d'un corps de requete trop volumineux");

    Reponse r = postAvec(port, "/api/formulaire", "action=blackout&nom=Salle A", "");
    egal("un formulaire normal est accepte", 200, r.code);
    egal("ses champs sont decodes", "2|blackout|Salle A", r.corps);

    r = postAvec(port, "/api/formulaire", "nom=" + repeter('x', 65536 - 4), "");
    egal("un corps pile a la limite de 64 Ko passe encore", 200, r.code);

    r = postAvec(port, "/api/formulaire", "nom=" + repeter('x', 65536 - 3), "");
    egal("un octet de plus et le corps est refuse", 413, r.code);
    egal("le refus est bien l'exception dediee, traduite en 413 par le serveur",
        "RequestTooLargeException|true", r.corps);

    r = postAvec(port, "/api/formulaire", "nom=" + repeter('x', 200000), "");
    egal("un corps de 200 Ko est refuse", 413, r.code);

    // Controle PENDANT la lecture : un flux qui ne dit pas sa taille ne doit
    // pas pouvoir etre entierement charge en memoire avant d'etre rejete.
    egal("un flux sous la limite est lu en entier", 1000,
        ((byte[]) mReadAll.invoke(null, new ByteArrayInputStream(new byte[1000]), 65536)).length);
    egal("un flux exactement a la limite passe", 65536,
        ((byte[]) mReadAll.invoke(null, new ByteArrayInputStream(new byte[65536]), 65536)).length);

    Throwable refus = null;
    try {
      mReadAll.invoke(null, new ByteArrayInputStream(new byte[65537]), 65536);
    } catch (InvocationTargetException e) {
      refus = e.getCause();
    }
    check("un flux plus gros que la limite est refuse", refus instanceof IOException);
    check("... par l'exception dediee que le serveur traduit en 413",
        refus != null && "RequestTooLargeException".equals(refus.getClass().getSimpleName()));

    // Un client qui ment sur la taille de son corps (ou qui n'en annonce
    // aucune) ne doit pas pouvoir faire grossir la memoire du bridge : la
    // lecture s'arrete DES le depassement, elle ne rejette pas apres coup.
    final long[] lus = { 0 };
    final long fleuve = 4L * 1024 * 1024;
    InputStream tresGros = new InputStream() {
      public int read() {
        if (lus[0] >= fleuve) {
          return -1;
        }
        lus[0]++;
        return 0;
      }

      public int read(byte[] b, int off, int len) {
        if (lus[0] >= fleuve) {
          return -1;
        }
        lus[0] += len;
        return len;
      }

      public void close() {
      }
    };
    Throwable coupe = null;
    try {
      mReadAll.invoke(null, tresGros, 65536);
    } catch (InvocationTargetException e) {
      coupe = e.getCause();
    }
    check("un corps de 4 Mo qui n'annonce pas sa taille est coupe", coupe instanceof IOException);
    check("... des le depassement, sans avoir ete charge en memoire ("
        + lus[0] + " octets lus au lieu de " + fleuve + ")",
        lus[0] > 0 && lus[0] <= 65536 + 3 * 8192);
  }

  // ------------------------------------------------------------------
  /**
   * Lecture des valeurs envoyees par l'interface.
   *
   * Double.parseDouble accepte « NaN », « Infinity » et « -Infinity ». Un champ
   * ainsi rempli traversait tous les bornages (Math.min/max propagent NaN) et
   * finissait dans la configuration ; /api/config renvoyait alors un JSON
   * contenant le litteral NaN, que JSON.parse refuse. L'interface restait figee
   * au chargement alors que le bridge, lui, fonctionnait parfaitement.
   */
  private static void valeursDuFormulaire() throws Exception {
    groupe("Lecture des valeurs envoyees par l'interface");

    egal("une valeur normale est lue", 0.42, reel("0.42", 5.0));
    egal("les espaces autour de la valeur sont toleres", 0.42, reel("  0.42  ", 5.0));
    egal("« NaN » est refuse (il figerait l'interface au chargement)", 5.0, reel("NaN", 5.0));
    egal("« Infinity » est refuse", 5.0, reel("Infinity", 5.0));
    egal("« -Infinity » est refuse", 5.0, reel("-Infinity", 5.0));
    egal("un texte quelconque est refuse", 5.0, reel("abc", 5.0));
    egal("un champ vide est refuse", 5.0, reel("", 5.0));
    egal("un champ absent est refuse", 5.0, reel(null, 5.0));
    egal("une valeur negative est bien lue (le bornage est fait plus loin)",
        -3.0, reel("-3", 5.0));

    egal("entier : valeur normale", 42, mParseInt.invoke(null, " 42 ", Integer.valueOf(7)));
    egal("entier : texte refuse", 7, mParseInt.invoke(null, "abc", Integer.valueOf(7)));
    egal("entier : champ absent refuse", 7,
        mParseInt.invoke(null, new Object[] { null, Integer.valueOf(7) }));

    // Last-Event-ID : le navigateur renvoie ce numero apres une coupure pour ne
    // recevoir que la suite du journal. Absent, on repart de tout l'historique.
    egal("Last-Event-ID absent => on repart de zero (tout l'historique)", 0L,
        mParseLong.invoke(null, new Object[] { null, Long.valueOf(0) }));
    egal("Last-Event-ID valide => reprise a ce numero", 1234L,
        mParseLong.invoke(null, "1234", Long.valueOf(0)));
    egal("Last-Event-ID fantaisiste => on repart de zero", 0L,
        mParseLong.invoke(null, "abc", Long.valueOf(0)));
  }

  private static Object reel(String saisie, double defaut) throws Exception {
    return mParseDouble.invoke(null, new Object[] { saisie, Double.valueOf(defaut) });
  }

  private static String repeter(char c, int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      sb.append(c);
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------
  // Petit client HTTP de test (on ne peut pas utiliser HttpURLConnection :
  // il masquerait justement les details qu'on veut verifier).
  // ------------------------------------------------------------------

  private static final class Reponse {
    int code;
    String corps = "";
    final Map<String, String> entetes = new HashMap<String, String>();
  }

  private static Reponse get(int port, String chemin) throws IOException {
    return brut(port, "GET " + chemin + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
  }

  private static Reponse post(int port, String chemin, String corps) throws IOException {
    return postAvec(port, chemin, corps, "");
  }

  /** POST avec des en-tetes supplementaires (chacun termine par CRLF). */
  private static Reponse postAvec(int port, String chemin, String corps, String entetesSup)
      throws IOException {
    byte[] data = corps.getBytes(StandardCharsets.UTF_8);
    return brut(port, "POST " + chemin + " HTTP/1.1\r\nHost: x\r\n" + entetesSup
        + "Content-Type: application/x-www-form-urlencoded\r\n"
        + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n" + corps);
  }

  private static Reponse brut(int port, String requete) throws IOException {
    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
      s.setSoTimeout(5000);
      s.getOutputStream().write(requete.getBytes(StandardCharsets.UTF_8));
      s.getOutputStream().flush();
      return lire(s.getInputStream());
    } finally {
      try {
        s.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static Reponse lire(InputStream in) throws IOException {
    Reponse r = new Reponse();
    String ligne = ligne(in);
    if (ligne == null) {
      return r;
    }
    String[] parts = ligne.split(" ");
    if (parts.length >= 2) {
      try {
        r.code = Integer.parseInt(parts[1]);
      } catch (NumberFormatException ignored) {
      }
    }
    int longueur = -1;
    String h;
    while ((h = ligne(in)) != null && !h.isEmpty()) {
      int c = h.indexOf(':');
      if (c > 0) {
        String cle = h.substring(0, c).trim().toLowerCase();
        String val = h.substring(c + 1).trim();
        r.entetes.put(cle, val);
        if ("content-length".equals(cle)) {
          longueur = Integer.parseInt(val);
        }
      }
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    if (longueur > 0) {
      byte[] buf = new byte[longueur];
      int lu = 0;
      while (lu < longueur) {
        int n = in.read(buf, lu, longueur - lu);
        if (n < 0) {
          break;
        }
        lu += n;
      }
      bos.write(buf, 0, lu);
    }
    r.corps = new String(bos.toByteArray(), StandardCharsets.UTF_8);
    return r;
  }

  private static String ligne(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\r') {
          sb.setLength(len - 1);
        }
        return sb.toString();
      }
      sb.append((char) c);
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  private static void repond(HttpExchange ex, int code, String type, String corps)
      throws IOException {
    byte[] data = corps.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.sendResponseHeaders(code, data.length);
    OutputStream os = ex.getResponseBody();
    os.write(data);
    os.close();
  }
}
