package com.heroicrobot.pixelpusher.artnet;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import com.heroicrobot.dropbit.devices.pixelpusher.PixelPusher;
import com.heroicrobot.dropbit.registry.DeviceRegistry;

/**
 * Facade publique autour du coeur legacy (ArtNetBridge / receivers / registry).
 * Le nouveau code (com.pixelpusher.bridge) ne parle au legacy qu'a travers cette classe,
 * ce qui permet de faire evoluer l'application sans toucher au code eprouve.
 */
public class LegacyCore {

  private PixelPusherObserver observer;
  private DeviceRegistry registry;
  private ArtNetReceiver artnetReceiver;
  private SacnReceiver sacnReceiver;
  private volatile boolean started = false;

  /** Demarre le bridge : discovery PixelPusher + reception Art-Net + sACN. */
  public synchronized void start(String colourOrder, boolean packing, boolean debug,
      boolean sacnEnabled) {
    if (started) {
      System.out.println("LegacyCore: deja demarre, start() ignore.");
      return;
    }
    ArtNetBridge.order = new ColourOrdering(colourOrder);
    ArtNetBridge.packing = packing;

    observer = new PixelPusherObserver();
    artnetReceiver = new ArtNetReceiver(observer, debug);
    sacnReceiver = new SacnReceiver(observer);
    SacnReceiver.enabled = sacnEnabled;
    ArtNetBridge.sacnReceiver = sacnReceiver;
    ArtNetBridge.artnetReceiver = artnetReceiver;

    registry = new DeviceRegistry();
    ArtNetBridge.registry = registry;
    registry.addObserver(observer);

    artnetReceiver.start();
    if (sacnEnabled) {
      sacnReceiver.start();
    }
    registry.startPushing();
    started = true;
    System.out.println("LegacyCore: bridge demarre (ordre=" + colourOrder
        + ", packing=" + packing + ", sACN=" + sacnEnabled + ")");
  }

  /**
   * Regenere le mapping Art-Net -> PixelPusher a chaud (changement d'ordre des
   * couleurs ou de mode packing) sans interrompre le flux.
   */
  public synchronized void remap(String colourOrder, boolean packing) {
    if (!started) {
      return;
    }
    ArtNetBridge.order = new ColourOrdering(colourOrder);
    ArtNetBridge.packing = packing;
    ArtNetMapping newMapping = new ArtNetMapping();
    newMapping.generateMapping(registry.getPushers(), packing);
    for (InetAddress addr : newMapping.multicastAddresses) {
      sacnReceiver.addGroup(addr);
    }
    observer.mapping = newMapping;
    System.out.println("LegacyCore: remappage effectue (ordre=" + colourOrder
        + ", packing=" + packing + ", pushers mappes=" + newMapping.getMappedPushers().size() + ")");
  }

  public boolean isStarted() {
    return started;
  }

  public DeviceRegistry getRegistry() {
    return registry;
  }

  public List<PixelPusher> getMappedPushers() {
    if (observer == null) {
      return Collections.emptyList();
    }
    return observer.mapping.getMappedPushers();
  }

  /** Coupe (ou retablit) la prise en compte des donnees DMX entrantes (mode test). */
  public void setMuteDmx(boolean mute) {
    ArtNetReceiver.muteDmx = mute;
  }

  public boolean isMuteDmx() {
    return ArtNetReceiver.muteDmx;
  }

  public boolean isArtnetListening() {
    return ArtNetReceiver.listening;
  }

  /** Le thread de reception Art-Net est-il vivant ? (diagnostic) */
  public boolean isArtnetThreadAlive() {
    return artnetReceiver != null && artnetReceiver.isAlive();
  }

  /** Le thread de reception sACN est-il vivant ? (diagnostic) */
  public boolean isSacnThreadAlive() {
    return sacnReceiver != null && sacnReceiver.isAlive();
  }

  public String getArtnetBindError() {
    return ArtNetReceiver.bindError;
  }

  /**
   * Injecte une trame DMX comme si elle venait du reseau (utilise par le
   * lecteur de sequences). channels[offset..offset+length-1] = canaux 1..length.
   */
  public void injectDmx(int universe, byte[] channels, int offset, int length) {
    PixelPusherObserver obs = observer;
    if (obs == null) {
      return;
    }
    ArtNetMapping mapping = obs.mapping;
    try {
      for (int i = 0; i < length; i++) {
        PixelPusherLocation loc = mapping.getPixelPusherLocation(universe, i + 1);
        if (loc == null) {
          continue;
        }
        byte value = channels[offset + i];
        switch (loc.getChannel()) {
          case RED:    loc.getStrip().setPixelRed(value, loc.getPixel()); break;
          case GREEN:  loc.getStrip().setPixelGreen(value, loc.getPixel()); break;
          case BLUE:   loc.getStrip().setPixelBlue(value, loc.getPixel()); break;
          case ORANGE: loc.getStrip().setPixelOrange(value, loc.getPixel()); break;
          case WHITE:  loc.getStrip().setPixelWhite(value, loc.getPixel()); break;
          default: break;
        }
      }
    } catch (RuntimeException ignored) {
      // pusher disparu en cours de trame : on saute la trame
    }
  }

  /** Met toutes les LED en noir (une seule trame). Sans effet si aucun pusher. */
  public void blackoutAll() {
    if (registry == null) {
      return;
    }
    try {
      for (com.heroicrobot.dropbit.devices.pixelpusher.Strip strip : registry.getStrips()) {
        int len = strip.getLength();
        for (int i = 0; i < len; i++) {
          strip.setPixel(0, i);
        }
      }
      System.out.println("Blackout envoye a toutes les LED.");
    } catch (RuntimeException e) {
      System.err.println("Blackout impossible : " + e);
    }
  }
}
