package com.heroicrobot.pixelpusher.artnet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;


public class SacnReceiver extends Thread {


	public static final int SOURCE_NAME_ADDR  = 44;

	//The ACN identifier string is defined to be "ASC-E1.17\0\0\0";
	public static final int ACN_IDENTIFIER_SIZE = 12;
	public static final byte[] ACN_IDENTIFIER =  {0x41, 0x53, 0x43, 0x2d, 0x45, 0x31, 0x2e, 0x31, 0x37, 0x00, 0x00, 0x00 };
	//The well-known streaming ACN port (currently the ACN port)
	public static final int SACN_PORT = 5568;
	public MulticastSocket mcSocket;
	// Instrumentation PixelPusherBridge
	public static final java.util.concurrent.atomic.AtomicLong dmxPackets =
	    new java.util.concurrent.atomic.AtomicLong();
	boolean seenPacket = false;
	private byte[] buf;

	private PixelPusherObserver observer;
	
	/*
	 * Don't reformat this or I will cut you.  - jls
	 * 
	 * 
		struct sacn_node_s {
		  
		  //Root Layer						// Field offsets:
		  uint16_t preamble;				// 0
		  uint16_t postamble;				// 2
		  uint8_t  packetId		[12];		// 4
		  uint16_t flagsLength;				// 16
		  uint8_t  vector		[4];		// 18
		  uint8_t  CID			[16];		// 22
		  //Framing Layer
		  uint16_t frmFlagsLength;			// 38
		  uint8_t  frmVector	[4];		// 40
		  uint8_t  sourceName	[64];		// 44
		  uint8_t  priority;				// 108
		  uint8_t  reservedIgnore	[2];	// 109
		  uint8_t  seqNo;					// 111
		  uint8_t  options;					// 112
		  uint8_t  universeNo      [2];		// 113
		  //DMP Layer
		  uint16_t dmpFlagsLength;			// 115
		  uint8_t  dmpVector;				// 117
		  uint8_t  addrType;				// 118
		  uint16_t firstPropertyAddr;		// 120
		  uint16_t addrIncrement;			// 122
		  uint16_t propValCount;			// 124
		  uint8_t  dataValues	[513];		// 126  NOTE:  dmx data starts at dataValues + 1
		  
		} __attribute__((packed));
	 */
	
	 public SacnReceiver(PixelPusherObserver observer) {
		    this.observer = observer;
		    buf = new byte[680];
		    this.seenPacket = false;
		    try {
				mcSocket = new MulticastSocket(SACN_PORT);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		    
	 }
	
	 // Instrumentation PixelPusherBridge : permet de désactiver sACN via la config
	 public static volatile boolean enabled = true;

	 public void addGroup(InetAddress group) {
		 if (!enabled)
			 return;
		 if (mcSocket == null) {
			 System.err.println("sACN: socket indisponible, groupe " + group + " ignore (redemarre l'app pour activer le sACN).");
			 return;
		 }
		 try {
			System.out.println("sACN: Joining multicast group "+group);
			mcSocket.joinGroup(group);
		} catch (IOException e) {
			e.printStackTrace();
		}
	 }
	 
	 private void update_channel(int universe, int channel, int value) {
		    try {
		      PixelPusherLocation loc = observer.mapping.getPixelPusherLocation(
		          universe, channel);
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
		      // Canal non mappe ou pusher disparu en pleine trame : on ignore ce
		      // canal. Portee elargie depuis NullPointerException, voir la note
		      // equivalente dans ArtNetReceiver. (PixelPusherBridge)
		    }
		  }
	
	  private void parseSacnPacket(DatagramPacket packet) {
		    buf = packet.getData();
		    if (buf.length > ACN_IDENTIFIER_SIZE) {
		    for (int i = 0; i < ACN_IDENTIFIER_SIZE; i++) {
		    		if (ACN_IDENTIFIER[i] != buf[i + 4]) { // packetId
		    	  		System.out.println("sACN:  Got a packet on the sACN port, but ID was wrong.");
		    	  		return;
		      		}
		    	}
		    }
		    // If we get here, it looks like there's a packet to handle.
		    
		      if (!this.seenPacket) {
		        System.out.println("sACN:  Got an sACN packet!");
		        this.seenPacket = true;
		      }
		      // ------------------------------------------------------------------
		      // Validation du paquet E1.31 avant toute exploitation. (PixelPusherBridge)
		      //
		      // L'original copiait 512 octets depuis l'offset 126 sans rien verifier.
		      // Le tampon fait 680 octets, donc pas de debordement visible, mais :
		      //  - un paquet court injectait dans les LED les restes du paquet
		      //    precedent, encore presents dans le tampon reutilise ;
		      //  - les paquets de SYNCHRONISATION, qui ne transportent aucun DMX,
		      //    etaient joues comme des donnees ;
		      //  - le start code DMP n'etait pas verifie, donc les trames de service
		      //    (RDM et autres) etaient traitees comme de l'eclairage.
		      // ------------------------------------------------------------------
		      int recu = packet.getLength();
		      if (recu < 126) {
		        return; // trop court pour contenir la moindre donnee DMX
		      }
		      // Vecteur de la couche framing : 0x00000002 = paquet de donnees DMX.
		      if (buf[40] != 0 || buf[41] != 0 || buf[42] != 0 || buf[43] != 2) {
		        return; // synchronisation ou extension : ce n'est pas de l'eclairage
		      }
		      // Start code DMP : seul 0 designe des niveaux d'eclairage.
		      if (buf[125] != 0) {
		        return;
		      }
		      // Nombre de canaux reellement transmis (le compteur inclut le start code),
		      // borne par ce que le datagramme contient vraiment.
		      int canaux = ((buf[123] & 0xff) << 8 | (buf[124] & 0xff)) - 1;
		      if (canaux > recu - 126) {
		        canaux = recu - 126;
		      }
		      if (canaux > 512) {
		        canaux = 512;
		      }
		      if (canaux <= 0) {
		        return;
		      }

		      int universe = ((buf[114] & 0xff) | ((buf[113] & 0xff) << 8));
		      dmxPackets.incrementAndGet();
		      ArtNetReceiver.universeLastSeen.put(Integer.valueOf(universe), Long.valueOf(System.currentTimeMillis()));
		      byte[] frameCopy = new byte[512];
		      System.arraycopy(buf, 126, frameCopy, 0, canaux);
		      ArtNetReceiver.lastFrame.put(Integer.valueOf(universe), frameCopy);
		      DmxTap t = ArtNetReceiver.tap;
		      if (t != null) {
		        try {
		          t.onDmx(universe, buf, 126, canaux);
		        } catch (RuntimeException ignored) {
		        }
		      }
		      if (ArtNetReceiver.muteDmx)
		        return; // mode test actif
		      //System.out.println("Universe = "+universe);
		      for (int i = 0; i < canaux; i++) {
		        // the channel data is in buf[i+126];
		        update_channel(universe, i + 1, buf[i + 126]);
		      }

		  }
	
	  @Override
	  public void run() {
	    DatagramPacket packet = new DatagramPacket(buf, buf.length);

	    // Robustesse : si le socket n'a pas pu etre cree (port occupe), reessaie.
	    while (mcSocket == null) {
	      try {
	        mcSocket = new MulticastSocket(SACN_PORT);
	        System.out.println("sACN: socket ouvert sur le port " + SACN_PORT);
	      } catch (IOException e) {
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
	    	packet.setLength(buf.length);
	        mcSocket.receive(packet);
	        parseSacnPacket(packet);
	        if (packetno % 100 == 0)
	          packetno++;
	      } catch (IOException e) {
	        e.printStackTrace();
	      }
	      
	    }
	  }
}
