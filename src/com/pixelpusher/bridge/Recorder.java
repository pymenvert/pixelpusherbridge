package com.pixelpusher.bridge;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import com.heroicrobot.pixelpusher.artnet.ArtNetReceiver;
import com.heroicrobot.pixelpusher.artnet.DmxTap;
import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Enregistreur / lecteur de sequences Art-Net.
 *
 * Enregistrement : capture chaque trame DMX recue (tous univers) avec son
 * horodatage dans ~/.pixelpusherbridge/recordings/&lt;nom&gt;.ppb.
 * Lecture : rejoue les trames avec le timing d'origine (boucle optionnelle),
 * en coupant le flux Art-Net entrant pendant la lecture.
 *
 * Format fichier : magic "PPBREC01" puis par trame :
 *   int64 timestampMs | uint16 universe | uint16 length | length octets.
 */
public class Recorder {

  private static final byte[] MAGIC = { 'P', 'P', 'B', 'R', 'E', 'C', '0', '1' };
  private static final long MAX_FILE_BYTES = 300L * 1024 * 1024; // garde-fou 300 Mo

  private final LegacyCore core;

  // enregistrement — l'ecriture disque est asynchrone (queue + thread dedie)
  // pour ne jamais ralentir le thread de reception Art-Net.
  private final Object recLock = new Object();
  private DataOutputStream recOut;
  private java.util.concurrent.ArrayBlockingQueue<byte[]> writeQueue;
  private Thread writerThread;
  private volatile boolean recording = false;
  private volatile String recName = "";
  private volatile long recFrames = 0;
  private volatile long recStartTs = 0;
  private volatile long recBytes = 0;
  private volatile long recDropped = 0;
  /** Renseigne quand un enregistrement s'est arrete sur une erreur disque. */
  private volatile String recError = null;

  // lecture
  private volatile boolean playing = false;
  private volatile String playName = "";
  private volatile boolean playLoop = false;
  private volatile long playStartTs = 0;
  private volatile long playDurationMs = 0;
  private volatile Thread playThread;

  public Recorder(LegacyCore core) {
    this.core = core;
  }

  public static File recordingsDir() {
    File dir = new File(AppConfig.configDir(), "recordings");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  /**
   * Nettoyage du nom fourni par le client HTTP.
   *
   * L'implementation etait recopiee mot pour mot depuis Presets : meme regex,
   * meme troncature a 40 caracteres. C'est pourtant l'unique barriere entre un
   * nom recu par le reseau et un chemin de fichier ; dupliquee, elle finit par
   * diverger (autoriser le point d'un seul cote suffirait a rouvrir ".."
   * pour l'autre). Une seule regle, un seul endroit : Names.
   * (PixelPusherBridge)
   */
  static String sanitize(String name) {
    return Names.sanitize(name);
  }

  // ------------------------------------------------------------ enregistrement

  /** Demarre un enregistrement. Retourne le nom effectif ou null si erreur. */
  public synchronized String startRecord(String rawName) {
    if (recording) {
      return null;
    }
    String name = sanitize(rawName);
    if (name.isEmpty()) {
      name = "sequence";
    }
    File f = Names.safeFile(recordingsDir(), name, ".ppb");
    int n = 2;
    while (f != null && f.exists()) {
      f = Names.safeFile(recordingsDir(), name + "-" + (n++), ".ppb");
    }
    if (f == null) {
      LogBus.error("Enregistreur : nom de séquence refusé : " + rawName);
      return null;
    }
    final String finalName = f.getName().replace(".ppb", "");
    try {
      DataOutputStream out = new DataOutputStream(
          new BufferedOutputStream(new FileOutputStream(f), 65536));
      out.write(MAGIC);
      synchronized (recLock) {
        recOut = out;
      }
    } catch (IOException e) {
      LogBus.error("Enregistreur : creation du fichier impossible : " + e);
      return null;
    }
    recName = finalName;
    recFrames = 0;
    recBytes = 8;
    recDropped = 0;
    recError = null;
    recStartTs = System.currentTimeMillis();
    writeQueue = new java.util.concurrent.ArrayBlockingQueue<byte[]>(2048);
    recording = true;

    // le tap (appele par le thread reseau) ne fait que serialiser + poster
    ArtNetReceiver.tap = new DmxTap() {
      public void onDmx(int universe, byte[] data, int offset, int length) {
        if (!recording || length < 1 || length > 512) {
          return;
        }
        byte[] frame = new byte[12 + length];
        long ts = System.currentTimeMillis();
        frame[0] = (byte) (ts >>> 56); frame[1] = (byte) (ts >>> 48);
        frame[2] = (byte) (ts >>> 40); frame[3] = (byte) (ts >>> 32);
        frame[4] = (byte) (ts >>> 24); frame[5] = (byte) (ts >>> 16);
        frame[6] = (byte) (ts >>> 8);  frame[7] = (byte) ts;
        frame[8] = (byte) (universe >>> 8); frame[9] = (byte) universe;
        frame[10] = (byte) (length >>> 8);  frame[11] = (byte) length;
        System.arraycopy(data, offset, frame, 12, length);
        java.util.concurrent.ArrayBlockingQueue<byte[]> q = writeQueue;
        if (q != null && !q.offer(frame)) {
          recDropped++; // disque trop lent : on saute la trame plutot que bloquer le reseau
        }
      }
    };

    // thread d'ecriture disque
    writerThread = new Thread(new Runnable() {
      public void run() {
        try {
          while (recording || (writeQueue != null && !writeQueue.isEmpty())) {
            byte[] frame = writeQueue.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (frame == null) {
              continue;
            }
            synchronized (recLock) {
              if (recOut == null) {
                return;
              }
              recOut.write(frame);
            }
            recFrames++;
            recBytes += frame.length;
            if (recBytes > MAX_FILE_BYTES) {
              LogBus.warn("Enregistreur : taille maximale atteinte (300 Mo), arret automatique.");
              stopRecord();
              return;
            }
          }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
          // Disque plein, cle USB retiree, dossier devenu inaccessible.
          // Il faut IMPERATIVEMENT fermer le fichier ici : stopRecord() sort
          // immediatement quand recording vaut false, si bien que le flux
          // restait ouvert pour toujours, son tampon de 64 Ko jamais vide, et
          // l'interface affichait « aucun enregistrement » comme si de rien
          // n'etait. (PixelPusherBridge)
          recError = "Enregistrement interrompu : " + e;
          LogBus.error("Enregistreur : erreur d'écriture (" + e + "). Disque plein ou "
              + "support retiré ? Le fichier est fermé avec ce qui a pu être capturé.");
          recording = false;
          ArtNetReceiver.tap = null;
          synchronized (recLock) {
            try {
              if (recOut != null) {
                recOut.flush();
                recOut.close();
              }
            } catch (IOException ignored) {
            }
            recOut = null;
          }
        }
      }
    }, "sequence-writer");
    writerThread.setDaemon(true);
    writerThread.start();

    LogBus.info("Enregistrement demarre : " + finalName
        + " (toutes les trames DMX recues sont capturees)");
    return finalName;
  }

  public synchronized void stopRecord() {
    if (!recording) {
      return;
    }
    recording = false;
    ArtNetReceiver.tap = null;
    Thread w = writerThread;
    if (w != null && w != Thread.currentThread()) {
      try {
        w.join(3000); // laisse finir d'ecrire la queue
      } catch (InterruptedException ignored) {
      }
    }
    long durationMs = System.currentTimeMillis() - recStartTs;
    synchronized (recLock) {
      try {
        if (recOut != null) {
          recOut.close();
        }
      } catch (IOException ignored) {
      }
      recOut = null;
    }
    if (recDropped > 0) {
      LogBus.warn("Enregistreur : " + recDropped + " trame(s) sautee(s) (disque lent).");
    }
    // metadonnees (duree/trames) pour un listing rapide
    Properties meta = new Properties();
    meta.setProperty("durationMs", String.valueOf(durationMs));
    meta.setProperty("frames", String.valueOf(recFrames));
    ecrireMeta(Names.safeFile(recordingsDir(), recName, ".meta"), meta);

    LogBus.info("Enregistrement termine : " + recName + " (" + recFrames
        + " trames, " + (durationMs / 1000) + " s)");
  }

  /**
   * Ecriture atomique du fichier .meta, sur le modele d'AppConfig.save().
   *
   * L'ancienne version ouvrait un FileOutputStream directement sur la cible :
   * le fichier etait donc tronque AVANT l'ecriture. Un arret brutal dans cette
   * fenetre (coupure de courant en fin de spectacle, Stop-Process -Force du
   * raccourci d'arret) laissait un .meta vide ou a moitie ecrit, que
   * readMetaDuration() relit sans broncher : la sequence apparaissait alors
   * avec une duree de 0 s et la barre de progression de lecture restait figee.
   * On ecrit desormais dans un .tmp, on force les octets sur le disque, puis on
   * remplace la cible par un renommage (atomique si le systeme de fichiers le
   * permet). Le flux est ferme dans un finally : Properties.store peut lever
   * (disque plein, support retire) et le descripteur restait sinon ouvert
   * jusqu'au prochain passage du ramasse-miettes. (PixelPusherBridge)
   */
  private static void ecrireMeta(File metaFile, Properties meta) {
    if (metaFile == null) {
      return;
    }
    File tmp = new File(metaFile.getParentFile(), metaFile.getName() + ".tmp");
    try {
      FileOutputStream out = new FileOutputStream(tmp);
      try {
        meta.store(out, "PixelPusher Bridge - sequence");
        out.flush();
        try {
          out.getFD().sync(); // les octets sont reellement sur le disque
        } catch (java.io.SyncFailedException ignored) {
          // certains supports ne savent pas forcer : sans gravite
        }
      } finally {
        out.close();
      }
      try {
        java.nio.file.Files.move(tmp.toPath(), metaFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException noAtomic) {
        // Le renommage atomique n'est pas disponible partout (partage reseau
        // d'un profil itinerant, cle USB, systeme de fichiers exotique) et
        // l'echec y est signale par une FileSystemException generique et pas
        // seulement par AtomicMoveNotSupportedException : on rattrape donc
        // toute IOException avant de se rabattre sur un remplacement simple.
        java.nio.file.Files.move(tmp.toPath(), metaFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      // Les metadonnees ne sont qu'un confort d'affichage : leur echec ne doit
      // jamais faire echouer la fin d'un enregistrement, la sequence elle-meme
      // est deja ecrite et complete.
      tmp.delete();
    }
  }

  // ------------------------------------------------------------ lecture

  /** Lance la lecture d'une sequence. Retourne false si introuvable/erreur. */
  public synchronized boolean play(String rawName, boolean loop) {
    String name = sanitize(rawName);
    final File f = Names.safeFile(recordingsDir(), name, ".ppb");
    if (f == null || !f.isFile()) {
      LogBus.error("Lecture : sequence introuvable : " + name);
      return false;
    }
    if (f.length() <= MAGIC.length) {
      LogBus.error("Lecture : la sequence « " + name + " » ne contient aucune trame "
          + "(enregistrement interrompu trop tot ?). Rien a jouer.");
      return false;
    }
    stopPlay();
    playName = name;
    playLoop = loop;
    playStartTs = System.currentTimeMillis();
    playDurationMs = readMetaDuration(name);
    playing = true;
    ArtNetReceiver.muteDmx = true; // le direct est coupe pendant la lecture
    final boolean loopF = loop;
    playThread = new Thread(new Runnable() {
      public void run() {
        try {
          do {
            playStartTs = System.currentTimeMillis();
            long debut = System.currentTimeMillis();
            if (!playFileOnce(f)) {
              break;
            }
            // Garde-fou anti-emballement : une sequence vide ou quasi vide se
            // termine instantanement. En boucle, on rouvrait alors le fichier
            // des milliers de fois par seconde et un coeur du processeur partait
            // a 100 %, au detriment du flux LED. Une passe qui ne dure pas au
            // moins 200 ms n'est pas une lecture, c'est un fichier inexploitable.
            if (loopF && System.currentTimeMillis() - debut < 200) {
              LogBus.warn("Lecture : la séquence « " + playName + " » est vide ou trop courte, "
                  + "lecture en boucle interrompue.");
              break;
            }
          } while (playing && loopF);
        } finally {
          boolean wasPlaying = playing;
          playing = false;
          if (wasPlaying) {
            // fin naturelle (pas un stop manuel) : retablir le direct
            ArtNetReceiver.muteDmx = false;
            core.blackoutAll();
            LogBus.info("Lecture terminee : " + playName + " - flux Art-Net retabli.");
          }
        }
      }
    }, "sequence-player");
    playThread.setDaemon(true);
    playThread.start();
    LogBus.info("Lecture demarree : " + name + (loop ? " (en boucle)" : "")
        + " - le flux Art-Net entrant est ignore pendant la lecture.");
    return true;
  }

  /** Rejoue le fichier une fois. Retourne false sur erreur ou stop. */
  private boolean playFileOnce(File f) {
    DataInputStream in = null;
    try {
      in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 65536));
      byte[] magic = new byte[8];
      in.readFully(magic);
      if (!Arrays.equals(magic, MAGIC)) {
        LogBus.error("Lecture : format de fichier invalide.");
        return false;
      }
      long fileBase = -1;
      long wallBase = System.currentTimeMillis();
      byte[] data = new byte[512];
      while (playing) {
        long ts;
        try {
          ts = in.readLong();
        } catch (EOFException eof) {
          return true; // fin de fichier : lecture complete
        }
        int universe = in.readUnsignedShort();
        int length = in.readUnsignedShort();
        if (length < 1 || length > 512) {
          LogBus.error("Lecture : trame corrompue, arret.");
          return false;
        }
        in.readFully(data, 0, length);
        if (fileBase < 0) {
          fileBase = ts;
        }
        long target = wallBase + (ts - fileBase);
        long wait = target - System.currentTimeMillis();
        if (wait > 5000) {
          wait = 5000; // trou anormal dans l'enregistrement : on ne bloque pas
        }
        if (wait > 0) {
          Thread.sleep(wait);
        }
        if (!playing) {
          return false;
        }
        core.injectDmx(universe, data, 0, length);
      }
      return false;
    } catch (InterruptedException e) {
      return false;
    } catch (IOException e) {
      LogBus.error("Lecture : erreur de lecture du fichier : " + e);
      return false;
    } finally {
      try {
        if (in != null) {
          in.close();
        }
      } catch (IOException ignored) {
      }
    }
  }

  public synchronized void stopPlay() {
    if (!playing) {
      return;
    }
    playing = false;
    Thread t = playThread;
    if (t != null) {
      t.interrupt();
      try {
        t.join(1500);
      } catch (InterruptedException ignored) {
      }
    }
    ArtNetReceiver.muteDmx = false;
    core.blackoutAll();
    LogBus.info("Lecture arretee - flux Art-Net retabli.");
  }

  public synchronized boolean delete(String rawName) {
    String name = sanitize(rawName);
    if (playing && name.equals(playName)) {
      stopPlay();
    }
    File f = Names.safeFile(recordingsDir(), name, ".ppb");
    File m = Names.safeFile(recordingsDir(), name, ".meta");
    if (f == null) {
      return false;
    }
    boolean ok = f.delete();
    if (m != null) {
      m.delete();
    }
    return ok;
  }

  private long readMetaDuration(String name) {
    File metaFile = Names.safeFile(recordingsDir(), name, ".meta");
    if (metaFile == null || !metaFile.isFile()) {
      return 0;
    }
    // flux ferme dans un finally : un .meta tronque faisait lever load() et le
    // descripteur restait ouvert (methode appelee a chaque listing des
    // sequences, donc plusieurs fois par session). (PixelPusherBridge)
    FileInputStream in = null;
    try {
      Properties p = new Properties();
      in = new FileInputStream(metaFile);
      p.load(in);
      return Long.parseLong(p.getProperty("durationMs", "0"));
    } catch (Exception e) {
      return 0;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  // ------------------------------------------------------------ etat / listing

  // isRecording() supprime : aucun appelant dans tout le projet (ni WebServer,
  // ni Diagnostic, ni web/, ni tests/ — l'interface lit le champ "recording" du
  // JSON produit par stateJson()). Le code mort ment sur les capacites reelles
  // de la classe. L'etat reste porte par le champ volatile recording.
  // (PixelPusherBridge)

  public boolean isPlaying() {
    return playing;
  }

  /** JSON de l'etat courant (inclus dans /api/status). */
  public String stateJson() {
    long now = System.currentTimeMillis();
    long pct = 0;
    if (playing && playDurationMs > 0) {
      pct = Math.min(100, (now - playStartTs) * 100 / playDurationMs);
    }
    return Json.writer(224)
        .beginObject()
        .bool("recording", recording)
        .str("recError", recError == null ? "" : recError)
        .str("recName", recName)
        .num("recFrames", recFrames)
        .num("recSeconds", recording ? (now - recStartTs) / 1000 : 0)
        .bool("playing", playing)
        .str("playName", playName)
        .bool("playLoop", playLoop)
        .num("playPct", pct)
        .endObject()
        .done();
  }

  /** JSON de la liste des sequences enregistrees. */
  public String listJson() {
    File[] files = recordingsDir().listFiles();
    List<File> ppb = new ArrayList<File>();
    if (files != null) {
      for (File f : files) {
        if (f.getName().endsWith(".ppb")) {
          ppb.add(f);
        }
      }
    }
    ppb.sort(new java.util.Comparator<File>() {
      public int compare(File a, File b) {
        return a.getName().compareToIgnoreCase(b.getName());
      }
    });
    Json.Writer w = Json.writer(512).beginArray();
    for (File f : ppb) {
      String name = f.getName().replace(".ppb", "");
      long dur = readMetaDuration(name);
      w.beginObject()
       .str("name", name)
       .num("sizeBytes", f.length())
       .num("durationSec", dur / 1000)
       .endObject();
    }
    return w.endArray().done();
  }
}
