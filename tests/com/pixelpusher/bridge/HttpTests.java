package com.pixelpusher.bridge;

import static com.pixelpusher.bridge.Harness.check;
import static com.pixelpusher.bridge.Harness.egal;
import static com.pixelpusher.bridge.Harness.groupe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * Tests de bout en bout du serveur HTTP de secours.
 *
 * Ce serveur prend le relais quand le serveur du JDK ne peut pas demarrer
 * (pare-feu bloquant le selecteur NIO). C'est donc du code neuf sur le chemin
 * de l'interface : il doit etre couvert serieusement, y compris sur les cas
 * tordus (requete malformee, corps enorme, connexion reutilisee, charge).
 */
public final class HttpTests {

  private HttpTests() {
  }

  static void run() throws Exception {
    groupe("Serveur HTTP de secours");

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
    serveur.start();

    try {
      routage(port);
      corpsEtMethodes(port);
      casTordus(port);
      connexionReutilisee(port);
      flux(port);
      charge(port);
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
    byte[] data = corps.getBytes(StandardCharsets.UTF_8);
    return brut(port, "POST " + chemin + " HTTP/1.1\r\nHost: x\r\n"
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
