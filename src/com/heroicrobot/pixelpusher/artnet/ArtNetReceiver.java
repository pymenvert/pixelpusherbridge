package com.heroicrobot.pixelpusher.artnet;

import java.io.IOException;

import com.heroicrobot.dropbit.devices.pixelpusher.PixelPusher;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.util.Enumeration;

public class ArtNetReceiver extends Thread {

  public static final int ARTNET_PORT = 6454;
  public static final byte[] header = { 0x41, 0x72, 0x74, 0x2d, 0x4e, 0x65,
      0x74, 0x0 };
  
  public static final byte[] short_name = {0x50, 0x69, 0x78, 0x65, 0x6c, 0x50, 0x75, 
	  0x73, 0x68, 0x65, 0x72, 0x20, 0x31, 0x2e, 0x31, 0x00 };
  
  public static byte[] artpoll_buf;
  public static boolean debug;

  // --- Instrumentation ajoutee pour l'interface web (PixelPusherBridge) ---
  public static final java.util.concurrent.atomic.AtomicLong dmxPackets =
      new java.util.concurrent.atomic.AtomicLong();
  public static final java.util.concurrent.ConcurrentHashMap<Integer, Long> universeLastSeen =
      new java.util.concurrent.ConcurrentHashMap<Integer, Long>();
  public static volatile boolean muteDmx = false;   // vrai pendant les patterns de test
  public static volatile boolean listening = false; // socket Art-Net lie avec succes
  public static volatile String bindError = null;   // message d'erreur si le bind echoue
  public static volatile DmxTap tap = null;         // enregistreur de sequences (optionnel)
  // moniteur DMX : derniere trame recue par univers (Art-Net + sACN)
  public static final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> lastFrame =
      new java.util.concurrent.ConcurrentHashMap<Integer, byte[]>();
  // Borne des deux tables ci-dessus. Le numero d'univers est lu sur 16 bits :
  // une source mal configuree ou des paquets corrompus font apparaitre des
  // numeros erratiques, et rien ne purgeait jamais ces tables. lastFrame
  // pouvait ainsi retenir 65536 tableaux de 512 octets (~33 Mo), pendant que
  // StatusService et Watchdog parcourent universeLastSeen a chaque seconde.
  // Au-dela de la borne on cesse simplement de suivre de NOUVEAUX univers : les
  // univers reellement utilises, deja presents, continuent d'etre mis a jour.
  // Le test est en O(1) et ne coute rien sur le chemin critique.
  // (PixelPusherBridge)
  public static final int MAX_TRACKED_UNIVERSES = 512;

  /** Vrai si cet univers peut encore etre suivi par les tables du moniteur. */
  static boolean canTrack(java.util.concurrent.ConcurrentHashMap<Integer, ?> map, Integer universe) {
    return map.size() < MAX_TRACKED_UNIVERSES || map.containsKey(universe);
  }
  // ------------------------------------------------------------------------

  byte[] buf;
  PixelPusherObserver observer;
  boolean seenPacket;

  public ArtNetReceiver(PixelPusherObserver observer, boolean debug) {
    this.observer = observer;
    ArtNetReceiver.debug = debug;
    buf = new byte[576];
    this.seenPacket = false;
    artpoll_buf = new byte[8 + 2 + 6 + 1 + 1 + 1 + 1 + 2 + 1 + 1 + 2 + 18 + 64 + 64 +
                           1 + 1+ 4 + 4 + 4 + 4 + 4 + 1 + 1 + 1 + 3 + 1 + 6 + 32 + 64];
    
    initArtPollBuf();
  }
  
  private void initArtPollBuf() {
	  int i;
	  /*
	   * ArtPoll replies look like this:    
	 char ID[8];
     unsigned short OpCode; // 0x2000
     struct ArtAddr Addr; // our ip address & port
     unsigned char VersionH;
     unsigned char Version;
     unsigned char SubSwitchH;
     unsigned char SubSwitch;
     unsigned short OEM;
     char UbeaVersion;
     char Status;
     unsigned short EstaMan;
     char ShortName[18];
     char LongName[64];
     char NodeReport[64];
     unsigned char NumPortsH;
     unsigned char NumPorts;
     unsigned char PortType[4];
     unsigned char GoodInput[4];
     unsigned char GoodOutput[4];
     unsigned char Swin[4];
     unsigned char Swout[4];
     unsigned char SwVideo;
     unsigned char SwMacro;
     unsigned char SwRemote;
     unsigned char Spare[3]; // three spare bytes
     unsigned char Style;
     unsigned char Mac[6];
     unsigned char Padding[32]; // padding
	   */
	  for (i=0; i<8; i++)
		  artpoll_buf[i] = header[i];
	  artpoll_buf[i++] = 0x00;
	  artpoll_buf[i++] = 0x21;  // ArtPollReply = 0x2100
	  
	  try {
		  InetAddress localhost = InetAddress.getLocalHost();
		  byte[] localhost_bytes = localhost.getAddress();
		  for (int j = 0; j<4; j++)
			  artpoll_buf[i++] = localhost_bytes[j];
	  } catch (Exception e) {
		  e.printStackTrace();
		  return;
	  }
	  
	  artpoll_buf[i++] = 0x36; // port 0x1936 == 6454
	  artpoll_buf[i++] = 0x19;
	  artpoll_buf[i++] = 0;	  // VersionH
	  artpoll_buf[i++] = 14;  // Version
	  artpoll_buf[i++] = 0;   // SubSwitchH
	  artpoll_buf[i++] = 0;   // SubSwitch
	  artpoll_buf[i++] = 0;   // OEM
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;   // UbeaVersion
	  artpoll_buf[i++] = 0;   // status
	  artpoll_buf[i++] = 0;   // EstaMan
	  artpoll_buf[i++] = 0;
	  for (int j=0; j<16; j++)
		  artpoll_buf[i++] = short_name[j];   // ShortName
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;  	  // pad to 18
	  
	  for (int j=0; j<15; j++)		// skip the terminator
		  artpoll_buf[i++] = short_name[j];   // LongName
	  for (int j=0; j<49; j++)
		  artpoll_buf[i++] = 0;  	  // pad to 48, acts as terminator
	  
	  	  artpoll_buf[i++] = 0x4f;	  // NodeReport
	  	  artpoll_buf[i++] = 0x4b;    // = "OK"
	  for (int j=0; j<62; j++)
		  artpoll_buf[i++] = 0;   // pad to 64
	  
	  artpoll_buf[i++] = 0;		// NumPortsH
	  artpoll_buf[i++] = 0;		// NumPortsL
	  for (int j=0; j<4; j++)
		  artpoll_buf[i++] = -1;	// PortType
	  
  }
  
  /*
   * Art-Net has some really weird representations in its ArtPollReply packet.  The 15-bit
   * port number is represented thus:
   * 
   * 
   *                    port number bits
   *  14  13  12  11  10   9   8   7   6   5   4   3   2   1   0    Mask   Field shift
   *  																
   *   1   1   1   1   1   1   1   0   0   0   0   0   0   0   0   = 0x7f00 	>> 8
   *  NS6 NS5 NS4 NS3 NS2 NS1 NS0
   *  
   *   0   0   0   0   0   0   0   1   1   1   1   0   0   0   0   = 0x00f0 	>> 4
   *                              SS3 SS2 SS1 SS0
   *                              
   *                              	               1   1   1   1   = 0x000f		>> 0
   *                                              SO3 SO2 SO1 SO0 
   *                                              
   *  Spread across NetSwitch, SubSwitch and SwOut fields. - jls                                             
   * 
   *
   */
  
  
  private void updateArtPollBuf(PixelPusher pusher) {
	  int i=0;
	  
	  int artnetUniverse = pusher.getArtnetUniverse();
	  int artNetPorts = pusher.getLastUniverse() - artnetUniverse;
	  
	  for (i=0; i<8; i++)
		  artpoll_buf[i] = header[i];
	  artpoll_buf[i++] = 0x00;
	  artpoll_buf[i++] = 0x21;  // ArtPollReply = 0x2100
	  
	  try {
		  InetAddress localhost = InetAddress.getLocalHost();
		  byte[] localhost_bytes = localhost.getAddress();
		  for (int j = 0; j<4; j++)
			  artpoll_buf[i++] = localhost_bytes[j];
	  } catch (Exception e) {
		  e.printStackTrace();
		  return;
	  }
	  artpoll_buf[i++] = 0x36; // port 0x1936 == 6454
	  artpoll_buf[i++] = 0x19;
	  artpoll_buf[i++] = 0;	  // VersionH
	  artpoll_buf[i++] = 14;  // Version
	  artpoll_buf[i++] = (byte) ((artnetUniverse & 0x7f00) >> 8);   // NetSwitch
	  artpoll_buf[i++] = (byte) ((artnetUniverse & 0x00f0) >> 4);   // SubSwitch
	  artpoll_buf[i++] = 0;   // OEM
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;   // UbeaVersion
	  artpoll_buf[i++] = 0;   // status
	  artpoll_buf[i++] = 0;   // EstaMan
	  artpoll_buf[i++] = 0;
	  for (int j=0; j<16; j++)
		  artpoll_buf[i++] = short_name[j];   // ShortName
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;  	  // pad to 18
	  for (int j=0; j<15; j++)				  // skip the terminator
		  artpoll_buf[i++] = short_name[j];   // LongName
	  
	  byte[] macAddr = pusher.getMacAddress().getBytes();
	  
	  for (int j=0; j<49; j++) {			// append this pusher's mac address to LongName
		  if (j<macAddr.length) {
			  artpoll_buf[i++] = macAddr[j];
		  } else {
			  artpoll_buf[i++] = 0;  	  // pad to 48, acts as terminator
		  }
	  }
	  	  artpoll_buf[i++] = 0x4f;	  // NodeReport
	  	  artpoll_buf[i++] = 0x4b;    // = "OK"
	  for (int j=0; j<62; j++)
		  artpoll_buf[i++] = 0;   // pad to 64
	  
	  artpoll_buf[i++] = 0;		// NumPortsH (always zero)
	  artpoll_buf[i++] = (byte) (artNetPorts & 0xff);		// NumPorts
	  for (int j=0; j<4; j++)
		  artpoll_buf[i++] = (byte) 0x80;	// PortType (Can output, DMX512).
	  for (int j=0; j<4; j++)
		  artpoll_buf[i++] = (byte) 0x80;   // GoodInput (Data received)
	  for (int j=0; j<4; j++)
		  artpoll_buf[i++] = (byte) 0x80;   // GoodOutput (Data is being transmitted)
	  for (int j=0; j<4; j++)
		  artpoll_buf[i++] = (byte) 0x0;    // SwIn (no input port)
	  for (int j=0; j<4; j++)
		  artpoll_buf[i++] = (byte) (artnetUniverse & 0x0f);   // SwOut (port address for the output ports)
	  
	  artpoll_buf[i++] = 1;					// SwVideo (showing ethernet data)
	  artpoll_buf[i++] = 0;					// SwMacro (no macros)
	  artpoll_buf[i++] = 0;					// SwRemote (no remote triggers)
	  
	  artpoll_buf[i++] = 0;					// Spare (not used, set to zero)
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;					// Style (StNode == DMX device)
	  
	  
	  artpoll_buf[i++] = 0;					// MAC address high byte
	  artpoll_buf[i++] = 0;					// We can't supply this, since some
	  artpoll_buf[i++] = 0;					// Art-Net devices make assumptions about ARP.
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;
	  
	  artpoll_buf[i++] = 0;					// BindIp (mind your own damn business)
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0;
	  artpoll_buf[i++] = 0; 
	  
	  artpoll_buf[i++] = 0; 				// BindIndex (zero if no binding)
	  
	  artpoll_buf[i++] = 14; 				// Status2 (1110b) - DHCP, 15 bit port addr
	  
	  
  }

  private void update_channel(int universe, int channel, int value) {
    try {
      PixelPusherLocation loc = observer.mapping.getPixelPusherLocation(
          universe, channel);
      // TODO: Extract color component from value
      switch (loc.getChannel()) {
      case RED:
        loc.getStrip().setPixelRed((byte) value, loc.getPixel());
        break;
      case GREEN:
        loc.getStrip().setPixelGreen((byte) value, loc.getPixel());
        break;
      case BLUE:
        loc.getStrip().setPixelBlue((byte) value, loc.getPixel());
        break;
      case ORANGE:
        loc.getStrip().setPixelOrange((byte) value, loc.getPixel());
        break;
      case WHITE:
        loc.getStrip().setPixelWhite((byte) value, loc.getPixel());
        break;
      default:
        break;
      }

    } catch (RuntimeException e) {
      // Canal non mappe, pusher qui disparait en pleine trame, index hors
      // ruban : dans tous les cas on ignore ce canal et on passe au suivant.
      // Le catch d'origine ne visait que NullPointerException, si bien qu'une
      // IndexOutOfBoundsException remontait jusqu'a tuer le thread de
      // reception. L'intention etait la meme, la portee etait trop etroite.
      // (PixelPusherBridge)
    }
  }

  // --- Reponse ArtPoll : ne rien faire de couteux sur le thread de reception ---
  // L'original enumerait TOUTES les interfaces reseau puis creait, configurait,
  // utilisait et fermait un DatagramSocket NEUF par adresse de broadcast, et
  // recommencait pour chaque pusher mappe. Une console emet un ArtPoll toutes
  // les 2,5 s : sur un portable (ethernet + wifi + interface virtuelle) avec 3
  // pushers, cela faisait 9 creations de socket et 6 lignes de log pendant
  // lesquelles socket.receive() n'etait pas appele - le tampon UDP du noyau se
  // remplissait et des trames DMX etaient perdues ("un hoquet toutes les 2-3
  // secondes"). On garde donc un socket unique, une liste d'adresses de
  // broadcast rafraichie au plus toutes les 30 s, et une journalisation limitee
  // a une ligne toutes les 30 s (chaque println traverse LogBus).
  // (PixelPusherBridge)
  private DatagramSocket artPollSocket = null;
  private InetAddress[] broadcastCache = null;
  private long broadcastCacheAt = 0;
  private long lastArtPollLog = 0;

  private InetAddress[] getBroadcastAddresses() {
    long now = System.currentTimeMillis();
    InetAddress[] cache = broadcastCache;
    if (cache != null && now - broadcastCacheAt < 30000) {
      return cache;
    }
    java.util.ArrayList<InetAddress> addrs = new java.util.ArrayList<InetAddress>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        NetworkInterface networkInterface = interfaces.nextElement();
        if (networkInterface.isLoopback())
          continue;    // Don't want to broadcast to the loopback interface
        for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
          InetAddress broadcast = interfaceAddress.getBroadcast();
          if (broadcast != null)
            addrs.add(broadcast);
        }
      }
    } catch (Exception e) {
      System.err.println("ArtPollReply : enumeration des interfaces impossible : " + e);
    }
    cache = addrs.toArray(new InetAddress[0]);
    broadcastCache = cache;
    broadcastCacheAt = now;
    return cache;
  }

  private void sendArtPollReply(byte[] buf) {
      long now = System.currentTimeMillis();
      if (now - lastArtPollLog > 30000) {
        lastArtPollLog = now;
        System.out.println("Art-Net : ArtPoll recu, reponse envoyee. <Art-Poll> VerH: "
            + buf[10] + " Ver:" + buf[11] + " TalkToMe: " + buf[12]
            + " (message limite a un toutes les 30 s)");
      }

      // send to *all* the bcast addresses we know about.
      try {
        DatagramSocket ds = artPollSocket;
        if (ds == null) {
          ds = new DatagramSocket();
          ds.setBroadcast(true);
          artPollSocket = ds;
        }
        for (InetAddress broadcast : getBroadcastAddresses()) {
          try {
            DatagramPacket dp = new DatagramPacket(artpoll_buf, artpoll_buf.length, broadcast, 0x1936);
            ds.send(dp);
          } catch (Exception e) {
            System.err.println("Failed to send ArtPollReply");
          }
        }
      } catch (Exception e) {
        System.err.println("Failed to send ArtPollReply");
        e.printStackTrace();
      }
  }
  
  private void parseArtnetPacket(DatagramPacket packet) {
    buf = packet.getData();
    for (int i = 0; header[i] > 0; i++) {
      if (header[i] != buf[i])
        return;
    }
    // If we get here, it looks like there's a packet to handle.
    if (buf[8] == 0x00 && buf[9] == 0x50) {
      // Opcode 0x5000 is DMX data
      if (!this.seenPacket) {
        System.out.println("Got an artnet packet!");
        this.seenPacket = true;
      }
      int universe = ((buf[14] & 0xff) | ((buf[15] & 0xff) << 8)) + 1;
      dmxPackets.incrementAndGet();
      Integer uKey = Integer.valueOf(universe);
      if (canTrack(universeLastSeen, uKey)) { // borne le suivi (PixelPusherBridge)
        universeLastSeen.put(uKey, Long.valueOf(System.currentTimeMillis()));
      }
      int length = ((buf[17] & 0xff) | (buf[16] & 0xff) << 8);
      int length_bytes = 18+length;
      if (length < 2) {
    	  System.err.println("Received short Art-Net packet.");
    	  return;
      }
      if (length > 512) {
    	  System.err.println("Received excessively long Art-Net packet.");
    	  return;
      }
      if (packet.getLength() < length_bytes) {
    	  System.err.println("Expected Art-Net datagram length "+length_bytes+
    			  " but received "+packet.getLength()+", which is too short.");
    	  return;
      }
      // moniteur DMX : garde une copie de la trame (visible meme en mode test)
      if (canTrack(lastFrame, uKey)) { // borne le suivi (PixelPusherBridge)
        byte[] frameCopy = new byte[length];
        System.arraycopy(buf, 18, frameCopy, 0, length);
        lastFrame.put(uKey, frameCopy);
      }
      DmxTap t = tap;
      if (t != null) {
        try {
          t.onDmx(universe, buf, 18, length);
        } catch (RuntimeException ignored) {
          // l'enregistreur ne doit jamais perturber le flux
        }
      }
      if (muteDmx)
        return; // mode test ou lecture de sequence : on ignore le direct
      for (int i = 0; i < length; i++) {
        // the channel data is in buf[i+18];
        update_channel(universe, i + 1, buf[i + 18]);
      }

    } else {
      if (buf[8] == 0x00 && buf[9] == 0x20) {
    	  if (observer.mapping.getMappedPushers().size() > 0) {
    		  for (PixelPusher pusher: observer.mapping.getMappedPushers()) {
    			  updateArtPollBuf(pusher);
    			  sendArtPollReply(buf);
    		  }
    	  } else {
    		  // We got nothing, just send the empty, single reply.
    		  sendArtPollReply(buf);
    	  }
      }
      if (buf[8] == 0x00 && buf[9] == 0x51) {
    	  // Opcode 0x5100 is Non-Zero Start DMX data.
    	  System.err.println("Non-zero start data received, but is not supported.");
      }
      return;
    }
  }

@Override
  public void run() {
    DatagramSocket socket = null;
    DatagramPacket packet = new DatagramPacket(buf, buf.length);
    // Detection d'un conflit de port avant le bind definitif. Celui-ci pose
    // SO_REUSEADDR, ce qui sous Windows laisse plusieurs programmes se lier au
    // meme port UDP : le bind reussit, listening passe a true et le diagnostic
    // affiche "Ecoute Art-Net sur le port 6454" en vert, alors que les
    // datagrammes unicast ne sont delivres qu'a UN seul des programmes. Cas le
    // plus frequent : MadMapper (ou Resolume, ou un autre node Art-Net) tourne
    // sur la meme machine et occupe deja 6454. L'operateur cherche alors du cote
    // du pare-feu et du cablage pendant des heures. Un bind exclusif prealable
    // permet de nommer le vrai probleme dans le journal. (PixelPusherBridge)
    DatagramSocket probe = null;
    try {
      probe = new DatagramSocket(null);
      probe.setReuseAddress(false);
      probe.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), ARTNET_PORT));
    } catch (IOException e) {
      System.out.println("Art-Net : ATTENTION, le port " + ARTNET_PORT + " est deja utilise par"
          + " un autre programme de cette machine (MadMapper, Resolume, un autre node Art-Net...)."
          + " En unicast, un seul programme recoit les paquets : configure ta source en broadcast,"
          + " ou ferme l'autre logiciel.");
    } finally {
      if (probe != null) {
        probe.close();
      }
    }
    // Robustesse : reessaie le bind toutes les 5 s (port occupe par une
    // ancienne instance en cours de redemarrage, interface reseau pas prete...)
    int attempts = 0;
    while (socket == null) {
      DatagramSocket tmp = null;
      try {
        tmp = new DatagramSocket(null);
        tmp.setReuseAddress(true);
        tmp.setBroadcast(true);
        tmp.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), ARTNET_PORT));
        socket = tmp;
        listening = true;
        bindError = null;
        System.out.println("Listening for Art-Net messages on " + socket.getLocalAddress() + " port "
            + socket.getLocalPort() + ", broadcast=" + socket.getBroadcast());
      } catch (IOException e) {
        if (tmp != null) {
          tmp.close();
        }
        bindError = e.toString();
        attempts++;
        if (attempts <= 3 || attempts % 12 == 0) {
          System.err.println("Art-Net : port " + ARTNET_PORT + " indisponible (" + e
              + "), nouvel essai dans 5 s...");
        }
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ie) {
          return;
        }
      }
    }
    int packetno = 0;
    while (true) {
      try {
        // receive() ecrase la longueur du paquet avec la taille reellement
        // recue, et cette longueur sert de capacite maximale a la reception
        // suivante : sans reinitialisation, la capacite ne fait que decroitre.
        // Un seul petit datagramme (un ArtPoll de 14 octets suffit) condamnait
        // ainsi toutes les trames DMX suivantes, definitivement. SacnReceiver
        // le fait deja correctement. (PixelPusherBridge)
        packet.setLength(buf.length);
        socket.receive(packet);
        parseArtnetPacket(packet);
        if (debug)
        	if (packetno % 100 == 0)
        		System.out.println("Received "+packetno+" Art-Net packets.");
        packetno++;
      } catch (IOException e) {
        e.printStackTrace();
      } catch (RuntimeException e) {
        // Filet de securite : aucune trame malformee ne doit pouvoir tuer le
        // thread de reception. On journalise et on passe a la suivante.
        // (PixelPusherBridge)
        System.err.println("Art-Net : trame ignoree apres erreur inattendue : " + e);
      }
    }
  }

}
