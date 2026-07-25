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
  // Le parametre LegacyCore a disparu : depuis que le blackout est un etat
  // verrouille porte par Blackout, plus aucune ligne de cette classe ne touchait
  // au coeur legacy. (PixelPusherBridge)
  public static boolean install(final Blackout blackoutState, final String uiUrl) {
    try {
      if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
        return false;
      }
      final TrayIcon icon = new TrayIcon(makeIcon(), "PixelPusher Bridge — en marche");
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

      // Blackout et reprise sont deux entrees distinctes : un blackout
      // d'urgence ne doit jamais pouvoir etre annule par un clic de travers.
      MenuItem blackout = new MenuItem("Blackout (tout éteindre et verrouiller)");
      blackout.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          blackoutState.engage();
          LogBus.info("Blackout demandé depuis l'icône système.");
        }
      });
      menu.add(blackout);

      MenuItem reprise = new MenuItem("Reprendre (rendre la main à la console)");
      reprise.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          blackoutState.release();
        }
      });
      menu.add(reprise);
      menu.addSeparator();

      MenuItem restart = new MenuItem("Redémarrer le bridge");
      restart.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
          Main.scheduleShutdown(true);
        }
      });
      menu.add(restart);

      MenuItem quit = new MenuItem("Arrêter le bridge et quitter");
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
            "Le bridge tourne en arrière-plan. Clic droit sur cette icône pour le menu ; "
            + "fermer la fenêtre de l'interface ne l'arrête pas.",
            TrayIcon.MessageType.INFO);
      } catch (RuntimeException ignored) {
        // certaines plateformes ne supportent pas les notifications : sans gravite
      }
      LogBus.info("Icône de barre système installée (point vert = bridge en marche).");
      return true;
    } catch (Throwable t) {
      // environnement sans interface graphique : le bridge fonctionne sans icone
      LogBus.info("Icône système indisponible sur cet environnement (" + t.getClass().getSimpleName() + ").");
      return false;
    }
  }

  /**
   * Petite icone : trois barres LED + pastille verte.
   *
   * Le parametre « running » a ete supprime : il n'etait jamais appele qu'avec
   * true, l'icone n'est construite qu'une fois au demarrage et n'est jamais
   * remplacee ensuite. La pastille rouge etait donc inatteignable et laissait
   * croire a un indicateur d'etat qui n'existe pas. Pour en faire un vrai, il
   * faudrait garder la reference du TrayIcon et appeler setImage() a chaque
   * changement d'etat.
   */
  private static BufferedImage makeIcon() {
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
    // pastille verte : le bridge tourne
    g.setColor(new Color(0x2e, 0xcc, 0x71));
    g.fillOval(9, 8, 7, 7);
    g.setColor(new Color(255, 255, 255, 180));
    g.drawOval(9, 8, 7, 7);
    g.dispose();
    return img;
  }
}
