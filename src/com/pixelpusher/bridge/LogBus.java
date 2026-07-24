package com.pixelpusher.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bus de logs central : capture System.out / System.err (donc tout le code
 * legacy sans le modifier), garde un historique en memoire, ecrit dans un
 * fichier avec rotation, et notifie les abonnes (flux SSE de l'interface web).
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
  private static PrintWriter fileWriter;
  private static final SimpleDateFormat FILE_TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

  private LogBus() {
  }

  /** Installe la capture de System.out/System.err et le fichier de log. */
  public static void install(File dir) {
    consoleOut = System.out;
    consoleErr = System.err;
    try {
      logFile = new File(dir, "bridge.log");
      rotateIfNeeded();
      fileWriter = new PrintWriter(new FileOutputStream(logFile, true), true);
    } catch (IOException e) {
      consoleErr.println("LogBus: fichier de log indisponible : " + e);
      fileWriter = null;
    }
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

  public static void info(String msg) {
    log("INFO", msg);
  }

  public static void warn(String msg) {
    log("WARN", msg);
  }

  public static void error(String msg) {
    log("ERROR", msg);
  }

  public static void log(String level, String msg) {
    if (msg == null || msg.length() == 0) {
      return;
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
      if (fileWriter != null) {
        fileWriter.println(FILE_TS.format(new Date(e.ts)) + " [" + level + "] " + msg);
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

  public static void removeListener(Listener l) {
    LISTENERS.remove(l);
  }

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
