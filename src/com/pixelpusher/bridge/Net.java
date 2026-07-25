package com.pixelpusher.bridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Adresses IPv4 « reseau local » de cette machine.
 *
 * Source unique : WebServer (QR code) et StatusService (liste d'URLs du
 * tableau de bord) enumeraient exactement la meme boucle chacun de leur cote.
 * Deux copies d'un meme filtrage reseau finissent toujours par diverger : le
 * QR code aurait pointe vers une carte et l'URL affichee vers une autre, sur
 * un poste de regie qui a souvent deux cartes (LED d'un cote, WiFi du lieu de
 * l'autre). Toute evolution du filtrage se fait desormais ici. (PixelPusherBridge)
 */
public final class Net {

  private Net() {
  }

  /**
   * Adresses IPv4 utilisables pour joindre l'interface depuis un telephone ou
   * un autre poste du meme reseau, dans l'ordre d'enumeration du systeme.
   *
   * Justification des filtres :
   *
   *  - isUp() : une carte debranchee ou desactivee porte encore son adresse.
   *    L'annoncer donnerait une URL qui ne repond pas.
   *
   *  - isLoopback() : 127.0.0.1 ne sert a rien au telephone de l'operateur,
   *    c'est precisement l'adresse que cette liste doit remplacer.
   *
   *  - isVirtual() : ecarte les sous-interfaces (alias du type eth0:1), qui
   *    ne sont pas des cartes distinctes et republieraient une adresse deja
   *    listee par leur interface parente. Attention : ce test ne dit RIEN des
   *    cartes virtuelles de Hyper-V, VMware ou Docker, qui sont pour Java des
   *    interfaces physiques ordinaires ; c'est le filtre suivant qui limite
   *    les degats, et l'interface affiche de toute facon toutes les adresses
   *    retenues pour que l'operateur choisisse la bonne.
   *
   *  - Inet4Address : les PixelPusher et les consoles lumiere parlent IPv4,
   *    et une URL IPv6 litterale (http://[fe80::1%12]/m) est intapable et
   *    illisible dans un QR code.
   *
   *  - isSiteLocalAddress() : ne garde que 10/8, 172.16/12 et 192.168/16,
   *    c'est-a-dire les plages d'un reseau de spectacle. Cela ecarte du meme
   *    coup le 169.254/16 d'auto-configuration (carte sans DHCP : personne
   *    d'autre ne peut y acceder) et une eventuelle adresse publique, qu'on
   *    ne veut ni afficher ni encoder dans un QR code.
   *
   * Ne leve jamais : une pile reseau indisponible rend une liste vide, jamais
   * une exception. Les appelants s'en servent pour afficher une aide, pas pour
   * pousser des pixels.
   */
  public static List<String> siteLocalIpv4() {
    List<String> adresses = new ArrayList<String>(4);
    try {
      Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
      if (ifs == null) {
        return adresses;
      }
      while (ifs.hasMoreElements()) {
        NetworkInterface ni = ifs.nextElement();
        try {
          if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
            continue;
          }
        } catch (Exception ignored) {
          // carte qui disparait pendant l'enumeration (dongle USB debranche,
          // VPN qui se ferme) : on l'ignore sans interrompre le balayage
          continue;
        }
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress a = addrs.nextElement();
          if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
            adresses.add(a.getHostAddress());
          }
        }
      }
    } catch (Exception ignored) {
      // pile reseau indisponible : on rend ce qui a pu etre collecte
    }
    return adresses;
  }

  /** Premiere adresse de siteLocalIpv4(), ou null si la machine n'en a aucune. */
  public static String firstSiteLocalIpv4() {
    List<String> l = siteLocalIpv4();
    return l.isEmpty() ? null : l.get(0);
  }
}
