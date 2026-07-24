package com.pixelpusher.bridge;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

import com.heroicrobot.pixelpusher.artnet.LegacyCore;

/**
 * Icone de barre systeme (Windows) / barre de menus (macOS).
 * Point vert = bridge en marche. Clic droit : ouvrir l'interface, blackout,
 * redemarrer, arreter. Double-clic : ouvrir l'interface.
 * L'app vit ainsi en arriere-plan comme un vrai logiciel : fermer la fenetre
 * du navigateur ne coupe rien, l'icone rappelle que le bridge tourne.
 */
public final class Tray {

  private Tray() {
  }

  /** Installe l'icone. Retourne false si l'environnement ne le permet pas. */
  public static boolean install(final LegacyCore core, final String uiUrl) {
    try {
      if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
        return false;
      }
      final TrayIcon icon = new TrayIcon(makeIcon(true), "PixelPusher Bridge — en marche");
      icon.setImageAutoSize(true);

      PopupMenu menu = new PopupMenu();

      MenuItem open = new MenuItem("Ouvrir l'interface");
      open.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          Main.openBrowserPublic(uiUrl);
        }
      });
      menu.add(open);
      menu.addSeparator();

      MenuItem blackout = new MenuItem("Blackout (tout eteindre)");
      blackout.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          core.blackoutAll();
          LogBus.info("Blackout envoye depuis l'icone systeme.");
        }
      });
      menu.add(blackout);
      menu.addSeparator();

      MenuItem restart = new MenuItem("Redemarrer le bridge");
      restart.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          Main.scheduleShutdown(true);
        }
      });
      menu.add(restart);

      MenuItem quit = new MenuItem("Arreter le bridge et quitter");
      quit.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          Main.scheduleShutdown(false);
        }
      });
      menu.add(quit);

      icon.setPopupMenu(menu);
      // double-clic = ouvrir l'interface
      icon.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          Main.openBrowserPublic(uiUrl);
        }
      });

      SystemTray.getSystemTray().add(icon);
      try {
        icon.displayMessage("PixelPusher Bridge",
            "Le bridge tourne en arriere-plan. Clic droit sur cette icone pour le menu ; "
            + "fermer la fenetre de l'interface ne l'arrete pas.",
            TrayIcon.MessageType.INFO);
      } catch (RuntimeException ignored) {
        // certaines plateformes ne supportent pas les notifications : sans gravite
      }
      LogBus.info("Icone de barre systeme installee (point vert = bridge en marche).");
      return true;
    } catch (Throwable t) {
      // environnement sans interface graphique : le bridge fonctionne sans icone
      LogBus.info("Icone systeme indisponible sur cet environnement (" + t.getClass().getSimpleName() + ").");
      return false;
    }
  }

  /** Petite icone : trois barres LED + pastille d'etat. */
  private static BufferedImage makeIcon(boolean running) {
    int s = 16;
    BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    // barres LED
    g.setColor(new Color(0xf8, 0x71, 0x71));
    g.fillRoundRect(1, 2, 10, 3, 2, 2);
    g.setColor(new Color(0x34, 0xd3, 0x99));
    g.fillRoundRect(1, 7, 10, 3, 2, 2);
    g.setColor(new Color(0x22, 0xd3, 0xee));
    g.fillRoundRect(1, 12, 10, 3, 2, 2);
    // pastille d'etat
    g.setColor(running ? new Color(0x2e, 0xcc, 0x71) : new Color(0xe7, 0x4c, 0x3c));
    g.fillOval(9, 8, 7, 7);
    g.setColor(new Color(255, 255, 255, 180));
    g.drawOval(9, 8, 7, 7);
    g.dispose();
    return img;
  }
}
