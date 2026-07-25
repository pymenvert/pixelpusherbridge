package com.pixelpusher.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Bus de logs central : capture System.out / System.err (donc tout le code
 * legacy sans le modifier), garde un historique en memoire, ecrit dans un
 * fichier avec rotation, et notifie les abonnes (flux SSE de l'interface web).
 *
 * REGLE CRITIQUE : log() est appele par TOUS les threads, y compris celui qui
 * recoit l'Art-Net (paquet malforme, pixel hors ruban...). Aucune I/O ne doit
 * donc etre faite sur le thread appelant, ni sous le verrou global : le fichier
 * est ecrit par un thread dedie « log-writer » qui depile une file bornee, par
 * lots, avec un seul flush par lot. Meme principe que l'enregistreur de
 * sequences et que le dispatcher SSE du serveur web.
 */
public final class LogBus {

  public static final class Entry {
    public final long seq;
    public final long ts;
    public final String level; // INFO / WARN / ERROR
    public final String msg;

    Entry(long seq, long ts, String level, String msg) {
      this.seq = seq;
      this.ts = ts;
      this.level = level;
      this.msg = msg;
    }

    public String toJson() {
      StringBuilder sb = new StringBuilder(msg.length() + 64);
      sb.append("{\"seq\":").append(seq)
        .append(",\"ts\":").append(ts)
        .append(",\"level\":\"").append(level)
        .append("\",\"msg\":\"").append(Json.esc(msg)).append("\"}");
      return sb.toString();
    }
  }

  public interface Listener {
    void onLog(Entry entry);
  }

  private static final int MAX_ENTRIES = 3000;
  private static final long MAX_LOG_FILE_BYTES = 5L * 1024 * 1024;

  private static final Object LOCK = new Object();
  private static final ArrayDeque<Entry> RING = new ArrayDeque<Entry>(MAX_ENTRIES);
  private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
  private static long seqCounter = 0;

  // suivi des erreurs pour le tableau de bord / diagnostic
  private static final java.util.concurrent.atomic.AtomicLong ERROR_COUNT =
      new java.util.concurrent.atomic.AtomicLong();
  private static volatile String lastError = "";
  private static volatile long lastErrorTs = 0;

  public static long getErrorCount() {
    return ERROR_COUNT.get();
  }

  public static String getLastError() {
    return lastError;
  }

  public static long getLastErrorTs() {
    return lastErrorTs;
  }

  private static PrintStream consoleOut;
  private static PrintStream consoleErr;
  private static File logFile;
  // fileWriter, fileBytes et writeErrorSignale n'appartiennent qu'au thread
  // « log-writer » une fois install() termine : aucun autre thread n'y touche.
  private static PrintWriter fileWriter;
  private static long fileBytes = 0;
  private static boolean writeErrorSignale = false;
  private static long rotationRateeTs = 0;
  private static final long ROTATION_RETRY_MS = 60000;
  private static final SimpleDateFormat FILE_TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
  private static final Date TS_DATE = new Date();

  // file bornee vers le thread d'ecriture : si elle deborde (disque bloque),
  // on perd des lignes de journal plutot que de ralentir un thread temps reel
  private static final int FILE_QUEUE_CAPACITY = 4096;
  private static final BlockingQueue<Entry> FILE_QUEUE =
      new ArrayBlockingQueue<Entry>(FILE_QUEUE_CAPACITY);
  private static final java.util.concurrent.atomic.AtomicLong FILE_DROPPED =
      new java.util.concurrent.atomic.AtomicLong();
  private static volatile boolean writerStop = false;
  private static volatile Thread writerThread;

  private LogBus() {
  }

  /** Installe la capture de System.out/System.err et le fichier de log. */
  public static void install(File dir) {
    consoleOut = System.out;
    consoleErr = System.err;
    try {
      logFile = new File(dir, "bridge.log");
      rotateIfNeeded();
      openWriter();
    } catch (IOException e) {
      consoleErr.println("LogBus: fichier de log indisponible : " + e);
      fileWriter = null;
    }
    startWriter();
    try {
      System.setOut(new PrintStream(new LineTee(consoleOut, "INFO"), true, "UTF-8"));
      System.setErr(new PrintStream(new LineTee(consoleErr, "ERROR"), true, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      // UTF-8 existe toujours
    }
  }

  private static void rotateIfNeeded() {
    if (logFile != null && logFile.exists() && logFile.length() > MAX_LOG_FILE_BYTES) {
      File old = new File(logFile.getParentFile(), "bridge.log.1");
      if (old.exists()) {
        old.delete();
      }
      logFile.renameTo(old);
    }
  }

  /**
   * Ouvre bridge.log en ajout. autoFlush=false : c'est le thread d'ecriture qui
   * flushe une fois par lot, pour ne jamais faire payer une I/O a l'appelant.
   */
  private static void openWriter() throws IOException {
    // BufferedWriter explicite : sans lui chaque println traverse l'encodeur
    // avec un tampon de 8 Ko seulement, et le thread d'ecriture n'arrive pas a
    // suivre une rafale (source qui inonde le port 6454), ce qui fait deborder
    // la file et perdre des lignes de journal.
    fileWriter = new PrintWriter(new java.io.BufferedWriter(new OutputStreamWriter(
        new FileOutputStream(logFile, true), "UTF-8"), 32768));
    fileBytes = logFile.length();
  }

  private static void closeWriter() {
    PrintWriter w = fileWriter;
    fileWriter = null;
    if (w != null) {
      try {
        w.flush();
        w.close();
      } catch (RuntimeException ignored) {
        // fermeture au pire moment : rien de plus a faire
      }
    }
  }

  /** Demarre le thread d'ecriture et garantit un dernier flush a l'extinction. */
  private static void startWriter() {
    Thread t = new Thread(new Runnable() {
      public void run() {
        writerLoop();
      }
    }, "log-writer");
    t.setDaemon(true);
    writerThread = t;
    t.start();
    try {
      Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
        public void run() {
          shutdown();
        }
      }, "log-writer-arret"));
    } catch (RuntimeException ignored) {
      // JVM deja en cours d'arret : le thread demon suffira
    }
  }

  /** Vide la file et ferme le journal proprement (appele par le shutdown hook). */
  public static void shutdown() {
    writerStop = true;
    Thread t = writerThread;
    if (t == null) {
      return;
    }
    t.interrupt();
    try {
      t.join(1500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void writerLoop() {
    ArrayList<Entry> batch = new ArrayList<Entry>(2048);
    long arretDepuis = 0;
    while (true) {
      try {
        Entry first = FILE_QUEUE.poll(500, TimeUnit.MILLISECONDS);
        if (first != null) {
          batch.add(first);
          FILE_QUEUE.drainTo(batch, 2047);
        }
      } catch (InterruptedException e) {
        // demande d'arret : on vide tout ce qui reste avant de sortir
        FILE_QUEUE.drainTo(batch);
      }
      if (!batch.isEmpty()) {
        try {
          writeBatch(batch);
        } catch (RuntimeException e) {
          // ce thread ne doit jamais mourir : s'il disparait, plus aucune ligne
          // n'est ecrite sur disque jusqu'au prochain redemarrage et la file se
          // remplit en silence. On abandonne le lot et on continue.
          consoleErr.println("LogBus: lot de journal abandonne : " + e);
        }
        batch.clear();
      }
      if (writerStop) {
        if (arretDepuis == 0) {
          arretDepuis = System.currentTimeMillis();
        }
        // on laisse un court sursis : les autres shutdown hooks (Main) loguent
        // encore leurs derniers messages pendant qu'on ferme.
        if (FILE_QUEUE.isEmpty() && System.currentTimeMillis() - arretDepuis > 400) {
          closeWriter();
          return;
        }
      }
    }
  }

  /** Ecrit un lot de lignes, un seul flush, puis verifie la taille du fichier. */
  private static void writeBatch(List<Entry> batch) {
    PrintWriter w = fileWriter;
    if (w == null) {
      return; // pas de fichier : la console a deja tout affiche
    }
    // on ne consomme le compteur de pertes qu'une fois certain d'avoir un
    // fichier ou l'annoncer, sinon l'information disparaitrait sans trace
    long perdues = FILE_DROPPED.getAndSet(0);
    try {
      if (perdues > 0) {
        w.println(FILE_TS.format(new Date()) + " [WARN] " + perdues
            + " ligne(s) de journal perdue(s) : ecriture disque trop lente.");
      }
      for (int i = 0; i < batch.size(); i++) {
        Entry e = batch.get(i);
        // FILE_TS et TS_DATE n'appartiennent qu'a ce thread : reutiliser la meme
        // Date evite une allocation par ligne pendant les rafales
        TS_DATE.setTime(e.ts);
        String line = FILE_TS.format(TS_DATE) + " [" + e.level + "] " + e.msg;
        w.println(line);
        fileBytes += line.length() + 2; // approximation suffisante pour la rotation
      }
      w.flush();
      if (w.checkError() && !writeErrorSignale) {
        writeErrorSignale = true;
        warn("Journal : l'ecriture de bridge.log a echoue (disque plein ou inaccessible). "
            + "Les logs restent visibles dans l'interface.");
      }
    } catch (RuntimeException e) {
      if (!writeErrorSignale) {
        writeErrorSignale = true;
        consoleErr.println("LogBus: ecriture du journal impossible : " + e);
      }
    }
    rotateNow();
  }

  /**
   * Rotation en cours d'execution : sans cela bridge.log grossit sans borne
   * tant que le processus tourne (une machine de spectacle reste allumee des
   * jours entiers). Appelee par le seul thread d'ecriture.
   *
   * Le delai de reprise apres echec est indispensable : sous Windows,
   * renameTo() echoue si bridge.log.1 est ouvert ailleurs (editeur de texte,
   * antivirus, synchronisation cloud). Sans garde-fou, fileBytes resterait
   * au-dessus du seuil et chaque lot declencherait une nouvelle fermeture /
   * reouverture du fichier accompagnee de son message d'archivage : le journal
   * se remplirait de ce message a la vitesse d'ecriture du disque. Le delai ne
   * s'applique qu'apres un echec : une rotation reussie n'empeche jamais la
   * suivante, sinon la taille du journal ne serait plus bornee.
   */
  private static void rotateNow() {
    if (fileWriter == null || logFile == null || fileBytes <= MAX_LOG_FILE_BYTES) {
      return;
    }
    long now = System.currentTimeMillis();
    if (rotationRateeTs != 0 && now - rotationRateeTs < ROTATION_RETRY_MS) {
      return; // derniere tentative echouee il y a peu : inutile de s'acharner
    }
    closeWriter();
    rotateIfNeeded();
    try {
      openWriter();
      writeErrorSignale = false;
      if (fileBytes > MAX_LOG_FILE_BYTES) {
        // l'archivage n'a pas eu lieu : on le signale sur la console d'origine
        // (jamais via log(), qui reviendrait aussitot dans ce fichier)
        rotationRateeTs = now;
        consoleErr.println("LogBus: rotation de bridge.log impossible (bridge.log.1 "
            + "verrouille ?), nouvelle tentative dans " + (ROTATION_RETRY_MS / 1000) + " s.");
      } else {
        rotationRateeTs = 0;
        info("Journal : bridge.log a atteint " + (MAX_LOG_FILE_BYTES / (1024 * 1024))
            + " Mo, archive en bridge.log.1 et redemarre.");
      }
    } catch (IOException e) {
      rotationRateeTs = now;
      fileWriter = null;
      consoleErr.println("LogBus: rotation du journal impossible : " + e);
    }
  }

  public static void info(String msg) {
    log("INFO", msg);
  }

  public static void warn(String msg) {
    log("WARN", msg);
  }

  public static void error(String msg) {
    log("ERROR", msg);
  }

  public static void log(String rawLevel, String rawMsg) {
    if (rawMsg == null || rawMsg.length() == 0) {
      return;
    }
    // Le coeur legacy ecrit tout sur System.err : ses avertissements arrivent
    // donc ici en ERROR alors que ce n'en sont pas. On les reclasse et on les
    // traduit avant tout comptage, sinon le tableau de bord affiche des erreurs
    // pour des situations parfaitement normales.
    String level = rawLevel;
    String msg = rawMsg;
    LegacyMessages.Classified reclasse = LegacyMessages.classify(rawLevel, rawMsg);
    if (reclasse != null) {
      level = reclasse.level;
      msg = reclasse.message;
    }
    if ("ERROR".equals(level)) {
      // les lignes de stack trace ("at ...", "Caused by...") sont des suites
      // de l'erreur precedente : on ne les compte pas comme nouvelles erreurs
      String t = msg.trim();
      boolean continuation = t.startsWith("at ") || t.startsWith("Caused by")
          || t.startsWith("...") || t.startsWith("java.") && lastErrorTs > 0
          && System.currentTimeMillis() - lastErrorTs < 200;
      if (!continuation) {
        ERROR_COUNT.incrementAndGet();
        lastError = msg;
        lastErrorTs = System.currentTimeMillis();
      }
    }
    Entry e;
    synchronized (LOCK) {
      e = new Entry(++seqCounter, System.currentTimeMillis(), level, msg);
      if (RING.size() >= MAX_ENTRIES) {
        RING.pollFirst();
      }
      RING.addLast(e);
      // depot dans la file du thread d'ecriture : jamais bloquant, jamais d'I/O
      // sous le verrou. On depose ici (et pas apres) pour que l'ordre du fichier
      // suive l'ordre des numeros de sequence.
      if (!FILE_QUEUE.offer(e)) {
        FILE_DROPPED.incrementAndGet();
      }
    }
    for (Listener l : LISTENERS) {
      try {
        l.onLog(e);
      } catch (RuntimeException ignored) {
        // un abonne defaillant ne doit jamais bloquer le bridge
      }
    }
  }

  public static void addListener(Listener l) {
    LISTENERS.add(l);
  }

  // removeListener() supprime : aucun appelant dans tout le projet. Il laissait
  // croire que les abonnes SSE etaient desabonnes un par un, alors que le
  // serveur web enregistre UN seul abonne permanent qui alimente une file
  // partagee. Le code mort envoyait sur une fausse piste en cas de recherche de
  // fuite.

  /** Historique depuis un numero de sequence (0 = tout). */
  public static List<Entry> getSince(long seq) {
    synchronized (LOCK) {
      List<Entry> out = new ArrayList<Entry>();
      for (Entry e : RING) {
        if (e.seq > seq) {
          out.add(e);
        }
      }
      return out;
    }
  }

  public static void clear() {
    synchronized (LOCK) {
      RING.clear();
    }
    // « Effacer les logs » doit aussi remettre a zero les compteurs affiches sur
    // le tableau de bord : sinon l'interface continue d'annoncer des erreurs
    // qu'elle n'est plus capable de montrer.
    ERROR_COUNT.set(0);
    lastError = "";
    lastErrorTs = 0;
    LegacyMessages.reset();
  }

  /**
   * OutputStream qui decoupe en lignes : chaque ligne part dans le bus de logs
   * ET vers la console d'origine.
   */
  private static final class LineTee extends OutputStream {
    private final PrintStream original;
    private final String level;
    private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256);

    LineTee(PrintStream original, String level) {
      this.original = original;
      this.level = level;
    }

    private void emit() {
      String line;
      try {
        line = buf.toString("UTF-8");
      } catch (UnsupportedEncodingException e) {
        line = buf.toString();
      }
      buf.reset();
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      if (line.trim().length() > 0) {
        log(level, line);
      }
    }

    @Override
    public synchronized void write(int b) {
      original.write(b);
      if ((b & 0xff) == '\n') {
        emit();
      } else {
        buf.write(b);
        if (buf.size() > 4000) { // ligne anormalement longue : on coupe
          emit();
        }
      }
    }

    @Override
    public void flush() {
      original.flush();
    }
  }
}
