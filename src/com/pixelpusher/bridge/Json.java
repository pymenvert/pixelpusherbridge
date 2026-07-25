package com.pixelpusher.bridge;

import java.util.Arrays;

/**
 * Petit utilitaire JSON - aucune dependance externe.
 *
 * Deux niveaux :
 *  - esc(), l'echappement d'une chaine, historique ;
 *  - Writer, un mini-writer qui construit un document bien forme et qui
 *    echappe TOUJOURS les chaines qu'on lui confie.
 *
 * Le writer existe parce que les serialiseurs etaient ecrits a la main, en
 * concatenant des guillemets et des virgules. Une valeur inseree sans passer
 * par esc() ne se voit pas a la relecture : le JSON reste valide tant que la
 * valeur est sage, et casse le jour ou elle contient un guillemet ou un
 * accolade. L'interface web ne peut alors plus rien afficher (JSON.parse
 * echoue), au pire moment. Avec le writer, l'echappement n'est plus une
 * discipline a tenir : il est structurel. (PixelPusherBridge)
 */
public final class Json {

  private Json() {
  }

  public static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  /** Nouveau writer avec une capacite de tampon donnee (evite les reallocations). */
  public static Writer writer(int capacite) {
    return new Writer(capacite);
  }

  /** Nouveau writer avec une capacite par defaut. */
  public static Writer writer() {
    return new Writer(256);
  }

  /**
   * Mini-writer JSON.
   *
   * Usage :
   *   Json.writer(128).beginObject()
   *      .str("nom", nomUtilisateur)   // echappe, toujours
   *      .num("frames", 1234)
   *      .bool("actif", true)
   *      .beginArray("univers").num(1).num(2).endArray()
   *      .endObject().done();
   *
   * Les virgules sont placees automatiquement : le writer retient, pour chaque
   * niveau ouvert, s'il attend encore son premier element. Aucune verification
   * de bon usage n'est faite au-dela (ce n'est pas un valideur) : les appels
   * viennent tous de code de l'application, jamais du reseau.
   *
   * Aucun appel n'a lieu sur le chemin temps reel Art-Net -> pushers.
   */
  public static final class Writer {

    private final StringBuilder sb;
    /** premier[n] : le niveau n attend-il encore son premier element ? */
    private boolean[] premier = new boolean[8];
    private int niveau = -1;

    private Writer(int capacite) {
      sb = new StringBuilder(capacite > 0 ? capacite : 64);
    }

    // ----- structure -----

    /** Ouvre un objet (racine, ou element d'un tableau). */
    public Writer beginObject() {
      sep();
      sb.append('{');
      push();
      return this;
    }

    /** Ouvre un objet sous la cle donnee. */
    public Writer beginObject(String cle) {
      cle(cle);
      sb.append('{');
      push();
      return this;
    }

    public Writer endObject() {
      pop();
      sb.append('}');
      return this;
    }

    /** Ouvre un tableau (racine, ou element d'un tableau). */
    public Writer beginArray() {
      sep();
      sb.append('[');
      push();
      return this;
    }

    /** Ouvre un tableau sous la cle donnee. */
    public Writer beginArray(String cle) {
      cle(cle);
      sb.append('[');
      push();
      return this;
    }

    public Writer endArray() {
      pop();
      sb.append(']');
      return this;
    }

    // ----- champs d'objet -----

    /** Champ chaine. La valeur est TOUJOURS echappee ; null devient "". */
    public Writer str(String cle, String valeur) {
      cle(cle);
      quote(valeur);
      return this;
    }

    /** Champ entier. */
    public Writer num(String cle, long valeur) {
      cle(cle);
      sb.append(valeur);
      return this;
    }

    /** Champ reel. Une valeur non finie serait un litteral illegal : ecrite 0. */
    public Writer num(String cle, double valeur) {
      cle(cle);
      nombre(valeur);
      return this;
    }

    /** Champ booleen. */
    public Writer bool(String cle, boolean valeur) {
      cle(cle);
      sb.append(valeur);
      return this;
    }

    // ----- elements de tableau -----

    /** Element chaine d'un tableau. Toujours echappe. */
    public Writer str(String valeur) {
      sep();
      quote(valeur);
      return this;
    }

    /** Element entier d'un tableau. */
    public Writer num(long valeur) {
      sep();
      sb.append(valeur);
      return this;
    }

    /** Element booleen d'un tableau. */
    public Writer bool(boolean valeur) {
      sep();
      sb.append(valeur);
      return this;
    }

    /** Le document produit. */
    public String done() {
      return sb.toString();
    }

    @Override
    public String toString() {
      return sb.toString();
    }

    // ----- interne -----

    private void cle(String c) {
      sep();
      quote(c);
      sb.append(':');
    }

    private void quote(String v) {
      sb.append('"').append(esc(v)).append('"');
    }

    private void nombre(double v) {
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        sb.append('0');
        return;
      }
      sb.append(v);
    }

    private void sep() {
      if (niveau < 0) {
        return; // racine : rien a separer
      }
      if (premier[niveau]) {
        premier[niveau] = false;
      } else {
        sb.append(',');
      }
    }

    private void push() {
      niveau++;
      if (niveau >= premier.length) {
        premier = Arrays.copyOf(premier, premier.length * 2);
      }
      premier[niveau] = true;
    }

    private void pop() {
      if (niveau >= 0) {
        niveau--;
      }
    }
  }
}
