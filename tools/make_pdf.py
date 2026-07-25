#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Génère « PixelPusher Bridge - Presentation et Installation.pdf ».

Le PDF était jusqu'ici maintenu à la main : il annonçait encore la version 1.5.0
alors que le logiciel était en 1.6.0. Un document commercial qui décrit une
version qui n'existe plus est pire que pas de document du tout. Il est donc
généré depuis ce script, et le numéro de version est lu directement dans
AppConfig.java : il ne peut plus mentir.

Dépendance : reportlab (seule dépendance du projet, et uniquement pour cet
outil de génération — le logiciel livré, lui, reste sans aucune dépendance).

    pip install reportlab
    python3 tools/make_pdf.py
"""
import io
import os
import re
import sys

try:
    from reportlab.lib import colors
    from reportlab.lib.enums import TA_CENTER
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import (BaseDocTemplate, Frame, NextPageTemplate, PageBreak,
                                    PageTemplate, Paragraph, Spacer, Table, TableStyle)
except ImportError:
    print("reportlab est requis : pip install reportlab")
    sys.exit(1)

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SORTIE = os.path.join(RACINE, "PixelPusher Bridge - Presentation et Installation.pdf")

# ---------------------------------------------------------------- identité
FOND = colors.HexColor("#0b0e14")
PANEL = colors.HexColor("#171c27")
ACC = colors.HexColor("#6366f1")
ACC2 = colors.HexColor("#22d3ee")
OK = colors.HexColor("#34d399")
WARN = colors.HexColor("#fbbf24")
TEXTE = colors.HexColor("#1a2030")
MUTED = colors.HexColor("#5d6a80")
LIGNE = colors.HexColor("#d7deea")
ZEBRE = colors.HexColor("#f4f6fa")

AUTEUR = "Pierre Yves Mansour — Collectif WSK"
DEPOT = "github.com/pymenvert/pixelpusherbridge"

# Coche et flèche : on s'en tient à des caractères présents dans l'encodage
# WinAnsi d'Helvetica. Les jolis glyphes de ZapfDingbats (✔, ➔) sortaient en
# carrés vides selon le lecteur de PDF — un symbole illisible est pire qu'un
# symbole banal.
COCHE = '<b><font color="#34d399">&#8226;</font></b>'
FLECHE = '<b><font color="#6366f1">&#187;</font></b>'


def version_du_code():
    """Lit la version dans AppConfig.java : le PDF ne peut pas se desynchroniser."""
    chemin = os.path.join(RACINE, "src", "com", "pixelpusher", "bridge", "AppConfig.java")
    with io.open(chemin, encoding="utf-8") as f:
        m = re.search(r'VERSION\s*=\s*"([^"]+)"', f.read())
    if not m:
        raise SystemExit("Version introuvable dans AppConfig.java")
    return m.group(1)


VERSION = version_du_code()

# ---------------------------------------------------------------- styles
def style(nom, **kw):
    base = dict(fontName="Helvetica", fontSize=9.2, leading=13.2, textColor=TEXTE)
    base.update(kw)
    return ParagraphStyle(nom, **base)


S_CORPS = style("corps")
S_PETIT = style("petit", fontSize=8.4, leading=11.6, textColor=MUTED)
S_TITRE_SECTION = style("section", fontName="Helvetica-Bold", fontSize=8.6, leading=11,
                        textColor=ACC, spaceAfter=1)
S_H1 = style("h1", fontName="Helvetica-Bold", fontSize=15.5, leading=19, spaceAfter=7)
S_CELL_TITRE = style("cellt", fontName="Helvetica-Bold", fontSize=9, leading=12)
S_CELL = style("cell", fontSize=8.8, leading=12.2)
S_NUM = style("num", fontName="Helvetica-Bold", fontSize=11, leading=13,
              textColor=colors.white, alignment=TA_CENTER)
S_NOTE = style("note", fontSize=8.6, leading=12, textColor=colors.HexColor("#7a5c00"))


def p(t, s=S_CORPS):
    return Paragraph(t, s)


def section(titre, sous_titre):
    return [p(titre.upper(), S_TITRE_SECTION), p(sous_titre, S_H1)]


def tableau_deux_colonnes(lignes, largeur_gauche=42 * mm):
    """Tableau « intitulé / explication », le motif dominant du document."""
    data = [[p(g, S_CELL_TITRE), p(d, S_CELL)] for g, d in lignes]
    t = Table(data, colWidths=[largeur_gauche, 173 * mm - largeur_gauche])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("ROWBACKGROUNDS", (0, 0), (-1, -1), [colors.white, ZEBRE]),
        ("LINEBELOW", (0, 0), (-1, -2), 0.4, LIGNE),
        ("LINEBEFORE", (0, 0), (0, -1), 2.2, ACC),
    ]))
    return t


def etapes(items):
    """Liste numérotée : pastille ronde + titre + explication."""
    data = []
    for i, (titre, texte) in enumerate(items, 1):
        pastille = Table([[p(str(i), S_NUM)]], colWidths=[7.5 * mm], rowHeights=[7.5 * mm])
        pastille.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, -1), ACC),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("ROUNDEDCORNERS", [3.75 * mm] * 4),
            ("TOPPADDING", (0, 0), (-1, -1), 1),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
        ]))
        data.append([pastille, p("<b>%s</b><br/>%s" % (titre, texte), S_CELL)])
    t = Table(data, colWidths=[11 * mm, 162 * mm])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ("LEFTPADDING", (0, 0), (0, -1), 0),
    ]))
    return t


def encadre(texte, couleur_fond="#fff8e6", couleur_bord=WARN, st=S_NOTE):
    t = Table([[p(texte, st)]], colWidths=[173 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(couleur_fond)),
        ("LINEBEFORE", (0, 0), (0, -1), 2.2, couleur_bord),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
    ]))
    return t


# ---------------------------------------------------------------- gabarits
def couverture(canv, doc):
    canv.saveState()
    l, h = A4
    canv.setFillColor(FOND)
    canv.rect(0, 0, l, h, stroke=0, fill=1)
    # bandeau de couleurs : rappel des rubans LED
    couleurs = ["#f87171", "#fbbf24", "#34d399", "#22d3ee", "#6366f1"]
    largeur = l / len(couleurs)
    for i, c in enumerate(couleurs):
        canv.setFillColor(colors.HexColor(c))
        canv.rect(i * largeur, h - 9 * mm, largeur, 9 * mm, stroke=0, fill=1)

    canv.setFillColor(colors.white)
    canv.setFont("Helvetica-Bold", 34)
    canv.drawCentredString(l / 2, h - 92 * mm, "PixelPusher Bridge")
    canv.setFont("Helvetica", 12.5)
    canv.setFillColor(colors.HexColor("#9aa7bc"))
    canv.drawCentredString(l / 2, h - 104 * mm,
                           "Pont Art-Net / sACN vers PixelPusher, avec interface web complète")

    # trois pastilles
    etiquettes = ["VERSION " + VERSION, "macOS + WINDOWS", "INSTALLATION EN 2 CLICS"]
    canv.setFont("Helvetica-Bold", 9)
    largeurs = [canv.stringWidth(e, "Helvetica-Bold", 9) + 14 * mm for e in etiquettes]
    total = sum(largeurs) + 6 * mm * (len(etiquettes) - 1)
    x = (l - total) / 2
    y = h - 124 * mm
    for e, w in zip(etiquettes, largeurs):
        canv.setFillColor(colors.HexColor("#1f2735"))
        canv.setStrokeColor(ACC)
        canv.setLineWidth(0.8)
        canv.roundRect(x, y, w, 9 * mm, 4.5 * mm, stroke=1, fill=1)
        canv.setFillColor(colors.HexColor("#c7d2fe"))
        canv.drawCentredString(x + w / 2, y + 3.2 * mm, e)
        x += w + 6 * mm

    canv.setFont("Helvetica", 10)
    canv.setFillColor(colors.HexColor("#8b98ad"))
    canv.drawCentredString(l / 2, 62 * mm,
                           "Présentation  ·  Installation  ·  Premiers pas  ·  Dépannage")
    canv.setFont("Helvetica", 8)
    canv.setFillColor(colors.HexColor("#5d6a80"))
    canv.drawCentredString(l / 2, 26 * mm,
                           "© 2026 %s · logiciel libre (MIT)" % AUTEUR)
    canv.drawCentredString(l / 2, 21 * mm,
                           "cœur réseau basé sur le projet open source robot-head/PixelPusher-artnet")
    canv.restoreState()


def page_courante(canv, doc):
    canv.saveState()
    l, h = A4
    canv.setFont("Helvetica", 7.5)
    canv.setFillColor(MUTED)
    canv.drawString(18 * mm, h - 12 * mm, "PixelPusher Bridge — présentation & installation")
    canv.drawRightString(l - 18 * mm, h - 12 * mm, "v" + VERSION)
    canv.setStrokeColor(LIGNE)
    canv.setLineWidth(0.5)
    canv.line(18 * mm, h - 14 * mm, l - 18 * mm, h - 14 * mm)

    canv.line(18 * mm, 15 * mm, l - 18 * mm, 15 * mm)
    canv.setFont("Helvetica", 7)
    canv.drawString(18 * mm, 11 * mm, "© 2026 %s" % AUTEUR)
    canv.drawCentredString(l / 2, 11 * mm, DEPOT)
    canv.drawRightString(l - 18 * mm, 11 * mm, "— %d —" % (doc.page - 1))
    canv.restoreState()


# ---------------------------------------------------------------- contenu
def construire():
    histoire = []
    a = histoire.append

    # ---------------- page 2 : présentation
    # Sans ce basculement, BaseDocTemplate garde le gabarit de la page 1 pour
    # tout le document : la couverture etait redessinee derriere chaque page.
    a(NextPageTemplate("courante"))
    a(PageBreak())
    a(Spacer(1, 2 * mm))
    for x in section("présentation", "Qu'est-ce que PixelPusher Bridge ?"):
        a(x)
    a(p("PixelPusher Bridge est le maillon entre ton logiciel lumière (MadMapper, grandMA, "
        "Resolume, console, médiaserveur…) et tes contrôleurs PixelPusher qui pilotent les "
        "rubans LED. Il reçoit le signal Art-Net ou sACN sur le réseau et le transmet aux "
        "pushers, en continu et sans saccade. Il doit simplement rester lancé tant que les "
        "LED doivent répondre."))
    a(Spacer(1, 5 * mm))

    chaine = [[
        p("<b>Logiciel lumière</b><br/><font size=7.5 color='#5d6a80'>MadMapper, grandMA,<br/>"
          "Resolume, console…</font>", S_CELL),
        p(FLECHE + "<br/><font size=7 color='#5d6a80'>Art-Net / sACN<br/>port 6454</font>", S_CELL),
        p("<b>PixelPusher Bridge</b><br/><font size=7.5 color='#5d6a80'>cet outil, sur Mac ou PC<br/>"
          "+ interface web</font>", S_CELL),
        p(FLECHE + "<br/><font size=7 color='#5d6a80'>protocole<br/>PixelPusher</font>", S_CELL),
        p("<b>PixelPushers</b><br/><font size=7.5 color='#5d6a80'>contrôleurs<br/>+ rubans LED</font>",
          S_CELL),
    ]]
    t = Table(chaine, colWidths=[42 * mm, 24 * mm, 45 * mm, 24 * mm, 38 * mm])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("BACKGROUND", (0, 0), (0, 0), ZEBRE),
        ("BACKGROUND", (2, 0), (2, 0), colors.HexColor("#eef0ff")),
        ("BACKGROUND", (4, 0), (4, 0), ZEBRE),
        ("BOX", (2, 0), (2, 0), 1.2, ACC),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    a(t)
    a(Spacer(1, 7 * mm))

    a(p("Les points forts", S_H1))
    a(tableau_deux_colonnes([
        (COCHE + " Fluidité d'abord",
         "Le cœur réseau est le bridge open source éprouvé de Heroic Robotics, quasiment intact. "
         "L'interface, l'enregistreur et les outils tournent dans des threads séparés : zéro "
         "impact sur le flux LED."),
        (COCHE + " Fiabilité vérifiée",
         "La version %s est issue d'un audit complet : 90 défauts identifiés et traités, dont six "
         "capables d'interrompre une représentation. <b>313 tests automatisés</b> et un test de "
         "bout en bout qui contrôle les couleurs réellement reçues par les LED tournent avant "
         "chaque publication." % VERSION),
        (COCHE + " Installation en 2 clics",
         "Aucune configuration manuelle : sur Mac, l'app télécharge même Java toute seule si "
         "besoin. Sur PC, un double-clic suffit."),
        (COCHE + " Une vraie app de bureau",
         "Icône de barre système avec point vert (bridge en marche) : clic droit pour ouvrir "
         "l'interface, blackout, redémarrer ou arrêter. Fermer la fenêtre ne coupe pas le bridge."),
        (COCHE + " Téléphone en un scan",
         "Bouton « Afficher le QR code » sur le tableau de bord : scanne-le avec l'appareil photo "
         "et l'interface de contrôle s'ouvre — blackout, luminosité, tests dans la poche."),
        (COCHE + " Pensé pour le spectacle",
         "Blackout d'urgence verrouillé, limite de puissance électrique, watchdog de signal, "
         "presets par salle, lecture de séquences en boucle sans console."),
        (COCHE + " Auto-dépannage",
         "Diagnostic complet en un clic avec un conseil concret par problème, moniteur DMX en "
         "direct, guide intégré pour néophyte, rapport téléchargeable pour se faire aider."),
    ]))

    # ---------------- page 3 : fonctionnalités
    a(PageBreak())
    a(Spacer(1, 2 * mm))
    for x in section("fonctionnalités", "Ce que l'interface te permet de faire"):
        a(x)
    a(tableau_deux_colonnes([
        ("Tableau de bord",
         "Vue d'ensemble en temps réel : la check-list « Est-ce que tout marche ? », les débits "
         "Art-Net avec courbes, les univers actifs, la consommation électrique, chaque "
         "PixelPusher détecté (IP, lignes, FPS, univers utilisés), et le QR code téléphone."),
        ("Blackout verrouillé",
         "<b>Nouveau en %s.</b> Le bouton Blackout ne se contente pas d'éteindre : il ignore les "
         "données entrantes jusqu'à une reprise explicite. Sans cela, la trame suivante de la "
         "console rallumait tout 25 ms plus tard — le bouton d'urgence était sans effet tant que "
         "la source tournait." % VERSION),
        ("Limite de puissance",
         "<b>Nouveau en %s.</b> Renseigne l'ampérage de ton alimentation : au-delà, toutes les LED "
         "sont atténuées proportionnellement plutôt que de laisser l'alim s'effondrer (chute de "
         "tension, couleurs qui virent, protection qui coupe). Jauge de consommation en direct." % VERSION),
        ("Diagnostic en 1 clic",
         "Passe tout en revue (réseau, pushers, données, configuration, système). Chaque problème "
         "vient avec sa solution — il repère les univers que ta source envoie mais qu'aucun pusher "
         "n'écoute, et détecte qu'un autre logiciel occupe déjà le port Art-Net."),
        ("Tests intégrés",
         "10 scénarios sans logiciel externe : blanc 100 % (test d'alim), cycle RVB (ordre des "
         "couleurs), dégradé (sens des rubans), chenillard (pixels morts), 1 couleur par ligne "
         "(câblage)… et le test de lignes manuel, un bouton par sortie de chaque pusher."),
        ("Séquences",
         "Enregistre ce que ta console envoie (tous les univers, timing exact) puis rejoue-le à "
         "l'identique, en boucle si besoin : l'installation tourne seule, sans console."),
        ("Presets",
         "Photographie toute la configuration sous un nom (« Salle A », « Tournée »…) et "
         "recharge-la en un clic, à chaud."),
        ("Adressage DMX",
         "Calculateur intégré : type de LED, LED par barre, barres par ligne, adresses de départ "
         "%s la map complète barre par barre, fidèle au mapping réel du bridge. Export CSV." % FLECHE),
        ("Moniteur DMX",
         "Les 512 canaux d'un univers en barres colorées, en direct : tu vois exactement ce que ta "
         "source envoie, canal par canal."),
        ("Sécurité spectacle",
         "Watchdog : si la source coupe, blackout automatique après N secondes (réarmement auto, "
         "inhibé pendant une séquence ou un test). Blackout à l'arrêt."),
        ("Téléphone",
         "Scanne le QR code du tableau de bord (même WiFi) : interface tactile simplifiée — "
         "blackout, luminosité, tests rapides, presets, séquences."),
        ("Logs & guide",
         "Journal temps réel filtrable et téléchargeable, messages du cœur réseau traduits en "
         "français avec un conseil concret ; onglet Guide pas-à-pas pour démarrer de zéro."),
    ], largeur_gauche=38 * mm))

    # ---------------- page 4 : installation
    a(PageBreak())
    a(Spacer(1, 2 * mm))
    for x in section("installation", "Sur Mac (Apple Silicon et Intel)"):
        a(x)
    a(etapes([
        ("Dézippe le fichier",
         "Double-clique <b>PixelPusher Bridge (macOS).zip</b> — l'app apparaît, accompagnée du "
         "script optionnel de signature."),
        ("Range l'app (optionnel)", "Glisse-la dans le dossier <b>Applications</b>."),
        ("Premier lancement",
         "<b>Clic droit sur l'app %s Ouvrir %s Ouvrir.</b> macOS demande confirmation une seule "
         "fois car l'app n'est pas signée Apple. Ensuite : double-clic normal." % (FLECHE, FLECHE)),
        ("Java (automatique)",
         "Si Java n'est pas présent, l'app propose de le télécharger toute seule (~45 Mo, une "
         "seule fois, connexion internet requise)."),
        ("Autorisation « Réseau local »",
         "Sur <b>macOS 15 (Sequoia) et plus récent</b>, le système la demande au premier "
         "lancement : <b>accepte</b>. C'est elle qui permet de découvrir les PixelPushers."),
        ("C'est lancé",
         "L'interface s'ouvre dans une fenêtre dédiée, une icône apparaît dans la barre de menus "
         "(point vert = en marche). Fermer la fenêtre ne coupe pas le bridge."),
    ]))
    a(Spacer(1, 3 * mm))
    a(encadre(
        "<b>Aucun PixelPusher détecté sur un Mac récent ?</b> Vérifie l'autorisation "
        "<b>Réglages Système %s Confidentialité et sécurité %s Réseau local</b> et active "
        "<i>PixelPusher Bridge</i>. Un refus est <b>totalement silencieux</b> : aucune erreur, "
        "simplement plus aucun pusher trouvé alors que tout paraît normal.<br/><br/>"
        "<b>« L'app est endommagée »</b> (fréquent après un transfert par internet) : double-clique "
        "le script <i>Signer l'app (optionnel).command</i> fourni dans le zip, ou colle dans le "
        "Terminal : <font face='Courier'>xattr -cr \"/Applications/PixelPusher Bridge.app\"</font>"
        % (FLECHE, FLECHE)))
    a(Spacer(1, 8 * mm))

    for x in section("installation", "Sur PC Windows"):
        a(x)
    a(etapes([
        ("Ouvre le dossier",
         "<b>PixelPusher Bridge (Windows)</b> — copie-le où tu veux (Bureau, Documents…)."),
        ("Lance l'app",
         "Double-clique <b>PixelPusher Bridge.bat</b>. Relancer l'app alors qu'elle tourne déjà "
         "rouvre simplement l'interface : jamais de doublon."),
        ("Pare-feu (1<sup>re</sup> fois)",
         "Autorise Java sur les réseaux privés — indispensable pour détecter les PixelPushers et "
         "recevoir l'Art-Net."),
        ("Pour arrêter",
         "Clic droit sur l'icône de la barre système %s <b>Arrêter</b>, ou le bouton Arrêter dans "
         "l'interface. En secours : <i>Arreter PixelPusher Bridge.bat</i>." % FLECHE),
    ]))
    a(Spacer(1, 3 * mm))
    a(encadre(
        "<b>Prérequis : Java 11 ou plus récent.</b> Le lanceur le vérifie réellement et ouvre "
        "adoptium.net si besoin (installation gratuite, 2 minutes). En cas d'échec, il laisse une "
        "trace dans <font face='Courier'>%USERPROFILE%\\.pixelpusherbridge\\launcher.log</font>."
        "<br/><br/><b>Mise à jour</b> (Mac comme PC) : remplace simplement le fichier "
        "<i>PixelPusherBridge.jar</i>. Ta configuration, tes presets et tes séquences sont conservés.",
        couleur_fond="#eef4ff", couleur_bord=ACC,
        st=style("noteb", fontSize=8.6, leading=12, textColor=colors.HexColor("#1e3a8a"))))

    # ---------------- page 5 : premiers pas
    a(PageBreak())
    a(Spacer(1, 2 * mm))
    for x in section("premiers pas", "De zéro jusqu'aux LED allumées"):
        a(x)
    a(etapes([
        ("Branche le PixelPusher",
         "Rubans sur les sorties du pusher, pusher sur le même réseau que l'ordinateur (câble "
         "Ethernet conseillé — le WiFi peut saccader). Sur sa carte SD, <i>pixel.rc</i> doit avoir "
         "<b>artnet_universe=1</b> et <b>artnet_channel=1</b> (ou plus) : à 0, rien ne s'allume."),
        ("Vérifie la détection",
         "Lance le bridge : le pusher apparaît sur le Tableau de bord en moins de 10 secondes, "
         "« en ligne » et « mappé ». Sinon : bouton <b>Lancer le diagnostic</b>."),
        ("Teste sans logiciel",
         "Onglet Tests : « Blanc 100 %% », puis « Cycle RVB » — quand l'écran dit ROUGE, les LED "
         "doivent être rouges. Sinon : Configuration %s Ordre des couleurs (effet immédiat)." % FLECHE),
        ("Configure ta source",
         "Protocole Art-Net, destination = l'IP de l'ordinateur qui fait tourner le bridge (ou "
         "broadcast), port 6454. <b>Décalage classique : univers 0 côté logiciel = univers 1 ici.</b> "
         "L'onglet Adressage DMX calcule la map exacte."),
        ("Protège ton alimentation",
         "Configuration %s Puissance électrique : renseigne l'ampérage de ton alim. Le bridge "
         "atténuera plutôt que de la laisser s'effondrer en plein spectacle." % FLECHE),
        ("En spectacle",
         "Active le watchdog (5–30 s conseillé), sauvegarde un preset, et scanne le QR code pour "
         "garder blackout et luminosité sous la main sur ton téléphone."),
    ]))

    # ---------------- page 6 : dépannage
    a(PageBreak())
    a(Spacer(1, 2 * mm))
    for x in section("dépannage", "Les symptômes les plus fréquents"):
        a(x)
    entetes = [p("<b>Symptôme</b>", S_CELL), p("<b>Solution</b>", S_CELL)]
    lignes = [
        ("Pusher non détecté",
         "Alimentation ? Même réseau / VLAN ? Pare-feu : autoriser Java (UDP entrant 7331). "
         "Sur macOS 15+ : autorisation <b>Réseau local</b>."),
        ("Détecté mais « non mappé »",
         "<i>artnet_universe</i> / <i>artnet_channel</i> à 0 dans pixel.rc %s mettre 1 et "
         "redémarrer le pusher." % FLECHE),
        ("Données reçues, rien ne s'allume",
         "Décalage d'univers 0/1 côté source, ou mauvais univers %s le diagnostic le détecte." % FLECHE),
        ("Mauvaises couleurs",
         "Test « Cycle RVB », puis Configuration %s Ordre des couleurs." % FLECHE),
        ("LED anormalement sombres",
         "Le limiteur de puissance est peut-être actif : la jauge du tableau de bord l'indique. "
         "Vérifie aussi la luminosité globale."),
        ("Saccades",
         "Câble plutôt que WiFi ; sinon +1–2 ms de délai additionnel, ou active l'auto-throttle "
         "(le diagnostic le conseille quand un pusher le réclame)."),
        ("L'interface est injoignable",
         "Un pare-feu ou un antivirus bloque les connexions locales dont Java a besoin. Le bridge "
         "bascule tout seul sur son serveur de secours et le signale dans les logs ; autorise Java "
         "pour revenir au fonctionnement normal."),
        ("Le port Art-Net ne reçoit rien",
         "Un autre logiciel de la machine occupe déjà le port 6454 (MadMapper, Resolume, un autre "
         "node). Le diagnostic le dit explicitement."),
        ("Image figée après fermeture",
         "Normal : les LED gardent la dernière trame. Active « Blackout à l'arrêt » ou clique "
         "Blackout."),
    ]
    data = [entetes] + [[p(g, S_CELL), p(d, S_CELL)] for g, d in lignes]
    t = Table(data, colWidths=[52 * mm, 121 * mm], repeatRows=1)
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BACKGROUND", (0, 0), (-1, 0), PANEL),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, ZEBRE]),
        ("LINEBELOW", (0, 1), (-1, -2), 0.4, LIGNE),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
    ]))
    a(t)
    a(Spacer(1, 8 * mm))

    a(p("Infos pratiques", S_H1))
    a(tableau_deux_colonnes([
        ("Interface web", "<font face='Courier'>http://localhost:7350</font> sur la machine du "
                          "bridge — port modifiable dans Configuration."),
        ("Téléphone", "QR code du tableau de bord, ou l'adresse courte affichée à côté "
                      "(même réseau WiFi)."),
        ("Tes données", "Dossier <font face='Courier'>.pixelpusherbridge</font> (configuration, "
                        "presets, séquences, journaux) — conservé lors des mises à jour."),
        ("En cas de problème", "Tableau de bord %s <b>Rapport</b> : fichier de diagnostic complet "
                               "à envoyer pour se faire aider." % FLECHE),
        ("Sources & mises à jour", "<font face='Courier'>%s</font>" % DEPOT),
        ("Licence", "Logiciel libre (MIT) — © 2026 %s. Cœur réseau crédité à Heroic Robotics / "
                    "robot-head." % AUTEUR),
    ], largeur_gauche=42 * mm))
    return histoire


def main():
    doc = BaseDocTemplate(
        SORTIE, pagesize=A4,
        leftMargin=18 * mm, rightMargin=18 * mm,
        topMargin=20 * mm, bottomMargin=20 * mm,
        title="PixelPusher Bridge — Présentation & Installation",
        author=AUTEUR,
        subject="Pont Art-Net / sACN vers PixelPusher — version %s" % VERSION,
        keywords="Art-Net, sACN, PixelPusher, LED, DMX, spectacle, MadMapper",
    )
    cadre = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="corps")
    doc.addPageTemplates([
        PageTemplate(id="couverture", frames=[cadre], onPage=couverture),
        PageTemplate(id="courante", frames=[cadre], onPage=page_courante),
    ])
    doc.build(construire())
    taille = os.path.getsize(SORTIE)
    print("PDF généré : %s" % os.path.basename(SORTIE))
    print("Version %s, %d octets" % (VERSION, taille))


if __name__ == "__main__":
    main()
