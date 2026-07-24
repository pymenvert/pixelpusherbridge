package com.pixelpusher.bridge;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;

/**
 * Serveur HTTP de secours, en sockets bloquantes.
 *
 * POURQUOI : com.sun.net.httpserver repose sur java.nio.channels.Selector, qui
 * ouvre une connexion en boucle locale pour son mecanisme de reveil. Certains
 * pare-feux et antivirus Windows bloquent cette connexion : Selector.open()
 * echoue alors avec « Unable to establish loopback connection », et l'interface
 * web ne demarre pas du tout — alors que les sockets bloquantes ordinaires,
 * elles, fonctionnent parfaitement. Sur un logiciel de spectacle, perdre toute
 * l'interface a cause d'un antivirus n'est pas acceptable.
 *
 * Ce serveur n'utilise que ServerSocket / Socket. Il implemente le strict
 * necessaire de HTTP/1.1 et surtout il expose l'API HttpExchange : les handlers
 * de WebServer fonctionnent a l'identique, qu'ils tournent sur le serveur du JDK
 * ou sur celui-ci. Aucune logique metier n'est dupliquee.
 *
 * Limites assumees : pas de compression, pas de HTTPS, pas de requetes en
 * pipeline. C'est une interface locale, ces fonctions n'ont pas d'utilite ici.
 */
public final class MiniHttpServer {

  /** Corps de requete au-dela duquel on refuse (protection memoire). */
  private static final int MAX_BODY = 256 * 1024;
  /** Taille maximale d'une ligne d'en-tete. */
  private static final int MAX_LINE = 8 * 1024;
  /** Nombre maximal d'en-tetes par requete. */
  private static final int MAX_HEADERS = 100;
  /** Connexions simultanees acceptees (au-dela : 503). */
  private static final int MAX_CONNECTIONS = 64;
  /** Delai d'inactivite d'une connexion persistante. */
  private static final int KEEPALIVE_TIMEOUT_MS = 30000;

  private final ServerSocket serverSocket;
  private final Map<String, HttpHandler> contexts = new ConcurrentHashMap<String, HttpHandler>();
  private final AtomicInteger activeConnections = new AtomicInteger();
  private volatile boolean running = false;
  private ExecutorService pool;
  private Thread acceptThread;

  private MiniHttpServer(ServerSocket socket) {
    this.serverSocket = socket;
  }

  /**
   * Ouvre le serveur sur le port demande (0 = port libre choisi par le systeme).
   * Le socket est ferme si quoi que ce soit echoue : contrairement a
   * HttpServer.create, on ne laisse jamais un port reserve derriere soi.
   */
  public static MiniHttpServer create(InetSocketAddress addr, int backlog) throws IOException {
    ServerSocket socket = new ServerSocket();
    try {
      socket.setReuseAddress(false); // on veut un echec franc si le port est pris
      socket.bind(addr, backlog > 0 ? backlog : 50);
      return new MiniHttpServer(socket);
    } catch (IOException e) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      throw e;
    }
  }

  public int getPort() {
    return serverSocket.getLocalPort();
  }

  public void createContext(String path, HttpHandler handler) {
    contexts.put(path, handler);
  }

  public void start() {
    if (running) {
      return;
    }
    running = true;
    pool = Executors.newCachedThreadPool(new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger();
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "mini-web-" + n.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    });
    acceptThread = new Thread(new Runnable() {
      public void run() {
        acceptLoop();
      }
    }, "mini-web-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  public void stop() {
    running = false;
    try {
      serverSocket.close();
    } catch (IOException ignored) {
    }
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  private void acceptLoop() {
    while (running) {
      Socket client;
      try {
        client = serverSocket.accept();
      } catch (IOException e) {
        if (running) {
          // Une erreur d'accept ne doit jamais tuer le serveur : on laisse
          // respirer puis on reessaie.
          LogBus.warn("Interface web : connexion refusee (" + e.getMessage() + ")");
          try {
            Thread.sleep(50);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          }
          continue;
        }
        return;
      }
      if (activeConnections.get() >= MAX_CONNECTIONS) {
        refuse(client);
        continue;
      }
      activeConnections.incrementAndGet();
      final Socket sock = client;
      try {
        pool.execute(new Runnable() {
          public void run() {
            try {
              serve(sock);
            } finally {
              activeConnections.decrementAndGet();
              closeQuietly(sock);
            }
          }
        });
      } catch (RuntimeException e) {
        activeConnections.decrementAndGet();
        closeQuietly(client);
      }
    }
  }

  private void refuse(Socket client) {
    try {
      client.getOutputStream().write(
          ("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().flush();
    } catch (IOException ignored) {
    }
    closeQuietly(client);
  }

  /** Traite une connexion, en boucle tant que le client garde la ligne ouverte. */
  private void serve(Socket sock) {
    try {
      sock.setTcpNoDelay(true);
      sock.setSoTimeout(KEEPALIVE_TIMEOUT_MS);
      BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), 8192);
      OutputStream out = sock.getOutputStream();
      // 100 requetes maximum par connexion : borne simple contre une connexion
      // qui monopoliserait un thread indefiniment.
      for (int i = 0; i < 100 && running && !sock.isClosed(); i++) {
        if (!handleOne(sock, in, out)) {
          return; // reponse en flux continu, ou connexion a fermer
        }
      }
    } catch (IOException e) {
      // client parti, delai depasse : rien a signaler
    } catch (RuntimeException e) {
      LogBus.warn("Interface web : requete abandonnee (" + e + ")");
    }
  }

  /** @return true si la connexion peut servir une requete de plus. */
  private boolean handleOne(Socket sock, BufferedInputStream in, OutputStream out)
      throws IOException {
    String requestLine = readLine(in);
    if (requestLine == null || requestLine.isEmpty()) {
      return false; // connexion fermee proprement par le client
    }
    // Une ligne de requete valide s'ecrit « METHODE cible HTTP/x.y », et la
    // cible commence par « / ». Sans ce controle, une ligne quelconque etait
    // interpretee comme une requete et servait la page d'accueil.
    String[] parts = requestLine.split(" ");
    if (parts.length != 3 || !parts[2].startsWith("HTTP/") || !parts[1].startsWith("/")) {
      writeSimple(out, 400, "Requete malformee");
      return false;
    }
    String method = parts[0];
    String rawTarget = parts[1];

    Headers requestHeaders = new Headers();
    for (int n = 0; n < MAX_HEADERS; n++) {
      String line = readLine(in);
      if (line == null) {
        return false;
      }
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon > 0) {
        requestHeaders.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
      }
    }

    // corps eventuel (Content-Length uniquement : aucun de nos endpoints
    // n'emet de requete chunked)
    byte[] body = new byte[0];
    String lenHeader = requestHeaders.getFirst("Content-Length");
    if (lenHeader != null) {
      long len;
      try {
        len = Long.parseLong(lenHeader.trim());
      } catch (NumberFormatException e) {
        writeSimple(out, 400, "Content-Length invalide");
        return false;
      }
      if (len < 0 || len > MAX_BODY) {
        writeSimple(out, 413, "Corps de requete trop volumineux");
        return false;
      }
      body = new byte[(int) len];
      int read = 0;
      while (read < body.length) {
        int n = in.read(body, read, body.length - read);
        if (n < 0) {
          return false;
        }
        read += n;
      }
    }
    if (requestHeaders.getFirst("Transfer-Encoding") != null) {
      writeSimple(out, 411, "Longueur de requete requise");
      return false;
    }

    URI uri;
    try {
      uri = new URI(rawTarget);
    } catch (Exception e) {
      writeSimple(out, 400, "URL invalide");
      return false;
    }

    HttpHandler handler = resolve(uri.getPath());
    if (handler == null) {
      writeSimple(out, 404, "404");
      return true;
    }

    boolean clientWantsClose = "close".equalsIgnoreCase(
        String.valueOf(requestHeaders.getFirst("Connection")));
    MiniExchange exchange = new MiniExchange(sock, method, uri, requestHeaders,
        new ByteArrayInputStream(body), out);
    try {
      handler.handle(exchange);
    } catch (Exception e) {
      LogBus.error("Interface web : erreur sur " + uri + " : " + e);
      if (!exchange.headersSent) {
        try {
          writeSimple(out, 500, "Erreur interne");
        } catch (IOException ignored) {
        }
      }
      return false;
    }
    exchange.finish();
    return exchange.keepAlive && !clientWantsClose;
  }

  /** Contexte le plus specifique dont le chemin prefixe l'URL demandee. */
  private HttpHandler resolve(String path) {
    if (path == null) {
      return null;
    }
    HttpHandler best = null;
    int bestLen = -1;
    for (Map.Entry<String, HttpHandler> e : contexts.entrySet()) {
      String ctx = e.getKey();
      if ((path.equals(ctx) || path.startsWith(ctx.endsWith("/") ? ctx : ctx + "/")
          || ctx.equals("/")) && ctx.length() > bestLen) {
        best = e.getValue();
        bestLen = ctx.length();
      }
    }
    return best;
  }

  /** Lit une ligne terminee par CRLF, sans jamais depasser MAX_LINE octets. */
  private static String readLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder(128);
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
      if (sb.length() > MAX_LINE) {
        throw new IOException("Ligne d'en-tete trop longue");
      }
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  private static void writeSimple(OutputStream out, int code, String message) throws IOException {
    byte[] data = message.getBytes(StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(128);
    sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
    sb.append("Content-Type: text/plain; charset=utf-8\r\n");
    sb.append("Content-Length: ").append(data.length).append("\r\n");
    sb.append("Connection: close\r\n\r\n");
    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    out.write(data);
    out.flush();
  }

  private static String reason(int code) {
    switch (code) {
      case 200: return "OK";
      case 302: return "Found";
      case 400: return "Bad Request";
      case 404: return "Not Found";
      case 405: return "Method Not Allowed";
      case 411: return "Length Required";
      case 413: return "Payload Too Large";
      case 500: return "Internal Server Error";
      case 503: return "Service Unavailable";
      default: return code < 400 ? "OK" : "Error";
    }
  }

  private static void closeQuietly(Socket s) {
    try {
      s.close();
    } catch (IOException ignored) {
    }
  }

  // ------------------------------------------------------------------
  // Adaptation vers l'API HttpExchange du JDK : c'est ce qui permet aux
  // handlers de WebServer de fonctionner sans la moindre modification.
  // ------------------------------------------------------------------

  private static final class MiniExchange extends HttpExchange {
    private final Socket socket;
    private final String method;
    private final URI uri;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new Headers();
    private InputStream requestBody;
    private final OutputStream rawOut;
    private OutputStream responseBody;
    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
    private int responseCode = -1;
    boolean headersSent = false;
    boolean keepAlive = true;

    MiniExchange(Socket socket, String method, URI uri, Headers requestHeaders,
        InputStream requestBody, OutputStream out) {
      this.socket = socket;
      this.method = method;
      this.uri = uri;
      this.requestHeaders = requestHeaders;
      this.requestBody = requestBody;
      this.rawOut = out;
    }

    @Override
    public void sendResponseHeaders(int code, long contentLength) throws IOException {
      if (headersSent) {
        return;
      }
      headersSent = true;
      responseCode = code;
      StringBuilder sb = new StringBuilder(256);
      sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
      if (contentLength > 0) {
        sb.append("Content-Length: ").append(contentLength).append("\r\n");
      } else if (contentLength < 0) {
        // convention du JDK : pas de corps
        sb.append("Content-Length: 0\r\n");
      } else {
        // convention du JDK : corps de longueur inconnue (SSE). On delimite par
        // la fermeture de connexion, ce que gere nativement EventSource.
        keepAlive = false;
      }
      if (!keepAlive) {
        sb.append("Connection: close\r\n");
      }
      for (Map.Entry<String, List<String>> e : responseHeaders.entrySet()) {
        for (String v : e.getValue()) {
          sb.append(e.getKey()).append(": ").append(v).append("\r\n");
        }
      }
      sb.append("\r\n");
      rawOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
      rawOut.flush();
      // Le flux rendu aux handlers ne doit jamais fermer la socket : ils
      // appellent close() en fin de reponse, ce qui doit seulement vider le
      // tampon pour laisser la connexion reutilisable.
      responseBody = new BodyStream(rawOut);
    }

    void finish() {
      try {
        if (!headersSent) {
          sendResponseHeaders(200, -1);
        }
        rawOut.flush();
      } catch (IOException ignored) {
        keepAlive = false;
      }
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return method; }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public InputStream getRequestBody() { return requestBody; }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public int getResponseCode() { return responseCode; }
    @Override public HttpPrincipal getPrincipal() { return null; }
    @Override public Object getAttribute(String name) { return attributes.get(name); }

    @Override
    public OutputStream getResponseBody() {
      if (responseBody == null) {
        // handler qui ecrit avant d'avoir envoye les en-tetes : on reste tolerant
        responseBody = new BodyStream(rawOut);
      }
      return responseBody;
    }

    @Override
    public void close() {
      try {
        rawOut.flush();
      } catch (IOException ignored) {
      }
      if (!keepAlive) {
        closeQuietly(socket);
      }
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    @Override
    public void setAttribute(String name, Object value) {
      if (value == null) {
        attributes.remove(name);
      } else {
        attributes.put(name, value);
      }
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
      if (i != null) {
        requestBody = i;
      }
      if (o != null) {
        responseBody = o;
      }
    }
  }

  /**
   * Flux de reponse rendu aux handlers : close() vide le tampon sans fermer la
   * socket, pour que la connexion reste reutilisable.
   */
  private static final class BodyStream extends OutputStream {
    private final OutputStream target;
    private volatile boolean closed = false;

    BodyStream(OutputStream target) {
      this.target = target;
    }

    @Override
    public void write(int b) throws IOException {
      if (closed) {
        throw new IOException("Flux de reponse deja ferme");
      }
      target.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (closed) {
        throw new IOException("Flux de reponse deja ferme");
      }
      target.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      if (!closed) {
        target.flush();
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      target.flush();
    }
  }

  /** Liste des chemins enregistres (diagnostic). */
  public List<String> contextPaths() {
    return new ArrayList<String>(contexts.keySet());
  }
}
