#!/usr/bin/env python3
"""Onglet « Monde » du panneau du village, refondu (Kevin, 21 novembre 2026 : canvas « Onglet Monde repensé »
https://claude.ai/artifact/LAQ1qPd4sBDPsY4Vviafoa validé « on implémente »).

  haut      quatre tuiles : voisins, prochain événement (ou ce qui se passe), voyageurs, campements et troupeau ;
  gauche    la carte « Alentours » qu'on clique (tous les voisins à moins de 2400 blocs, une forme par relation, le nom
            toujours vers l'extérieur, routes balisées en pointillés, voyageurs, auberges, campements, troupeau,
            escarmouche), puis la liste des voisins (un bouton par voisin, le choisi encadré) ;
  droite    la fiche du voisin choisi (relation sur une jauge à 20 crans, ce vers quoi elle tend, route, auberge, trêve,
            voyages, voyageurs sur la liaison, ce que le joueur peut faire, « Marquer sur la carte »), puis « Ce qui
            vient » (ce qui se passe maintenant en tête, l'agenda, ce qui manque avec un bouton Bâtir) ;
  bas       une rumeur du moment (world/Rumours).

    python3 tools/generate_world_tab.py

Écrit :
  Common/UI/Custom/Pages/Nakasar/Peuples/WorldPage_<Peuple>.ui, WorldPage.ui   (dev.nakasar.hytale.peuples.world.WorldPage)
  Common/UI/Custom/Pages/Nakasar/Peuples/Board/World_*.png                     formes, pictos et repères de la carte
  Server/Languages/{fr-FR,en-US}/nakasar_peuples.lang                          bloc des textes de l'onglet

Les tailles de la carte (MAP_*, MARKS, TRAIL...) sont celles que WorldPage place à la main : les changer ici, c'est les
changer là-bas. Aucun objet vanilla ne dit « allié », « route balisée » ou « escarmouche » : formes et pictos sont
dessinés ici, en blanc teinté par la page comme les icônes d'onglet (les puces de la carte sont déjà en couleur).
Boutons sans cadre (points de la carte, lignes de la liste) : style à aplat de couleur comme les lignes vanilla de
TriggerVolumeInspectorEffectRow.ui.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

from generate_board_ui import COMMON, MEMORIES, OUT, PANEL_HEIGHT, PANEL_WIDTH, THEMES, label, tab_strip

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
RES = ROOT / "src/main/resources"
BOARD = OUT / "Board"

LANG_BEGIN = "# ---- Onglet Monde (généré par tools/generate_world_tab.py, ne pas modifier à la main) ----"
LANG_END = "# ---- Fin de l'onglet Monde ----"

# ---- Tailles partagées avec world/WorldPage ----
LEFT = 540                 # colonne de gauche (carte + liste)
MAP_H = 300                # hauteur de la carte
MAP_CX, MAP_CY = 270, 150  # centre (le village) dans la carte
MAP_R = 120                # rayon du bord : 2400 blocs ; 1000 blocs aux trois quarts
RINGS = (500, 1000, 2400)
MARKS = 10                 # voisins sur la carte
MARK = 30                  # boîte d'un point (bouton), forme de 20 au milieu
NAME_W = 150               # étiquette du nom, à droite ou à gauche du point
TRAIL_DOTS = 7             # pointillés par liaison
TRAIL = MARKS * TRAIL_DOTS
POIS = 12                  # puces : auberges, voyageurs, campements, troupeau, escarmouches
POI = 18
ROWS = 16                  # lignes de la liste (elle défile)
SEGMENTS = 20              # crans de la jauge de relation (10 points chacun)
CHIPS = 4
TRACKS = 2
AGENDA = 6
STATS = 4

STATES = ("Ally", "Friendly", "Neutral", "Tense", "Rival")
STATE_COLORS = {"Ally": "#9ee37d", "Friendly": "#5fb8a5", "Neutral": "#b3bfcc", "Tense": "#e9a24c", "Rival": "#ef6f5e"}
# Cran k (valeur -100 + 10 k ... -90 + 10 k) : rival < -50 <= tendu < -20 < neutre < 20 <= ami < 50 <= allié
SEGMENT_STATE = ["Rival"] * 5 + ["Tense"] * 3 + ["Neutral"] * 4 + ["Friendly"] * 3 + ["Ally"] * 5
ZONE_WEIGHTS = (("Rival", 5), ("Tense", 3), ("Neutral", 4), ("Friendly", 3), ("Ally", 5))
POI_KINDS = ("Camp", "Herd", "Inn", "Caravan", "Merchant", "Pilgrim", "Messenger", "Skirmish")
FLAGS = ("Road", "Inn", "Truce", "Trav", "Fight")
CHIP_ICONS = {"Road": "#d6dde6", "NoRoad": "#8796a6", "Inn": "#e8c46a", "Truce": "#a9dcef", "Swords": "#e9a24c", "Fight": "#ef6f5e"}
HINT_ICONS = ("Swords", "Truce", "Shield", "Inn", "Road", "Scroll", "Cart")
AGENDA_ICONS = ("Swords", "Flame", "Wheat", "Fair", "Herd")
TRACK_ICONS = ("Cart", "Bag", "Staff", "Scroll")

CARD = "#0c151e(0.7)"
GOLD = "#e8c46a"
RED = "#ef6f5e"


# ---------------------------------------------------------------- dessins (blanc, 4x puis réduit)

class Pen:
    """Dessin dans une grille de 24 unités, suréchantillonné."""

    def __init__(self, size, k=8):
        self.size, self.k = size, k
        self.n = size * k
        self.im = Image.new("RGBA", (self.n, self.n), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)
        self.u = self.n / 24.0

    def p(self, x, y):
        return (x * self.u, y * self.u)

    def line(self, pts, w=2.2, fill=(255, 255, 255, 255)):
        q = [self.p(*a) for a in pts]
        self.d.line(q, fill=fill, width=max(1, int(w * self.u)), joint="curve")
        r = w * self.u / 2
        for x, y in q:
            self.d.ellipse((x - r, y - r, x + r, y + r), fill=fill)

    def poly(self, pts, fill=(255, 255, 255, 255), outline=None, w=0):
        self.d.polygon([self.p(*a) for a in pts], fill=fill)
        if outline is not None:
            self.line(list(pts) + [pts[0]], w=w, fill=outline)

    def circle(self, cx, cy, r, fill=None, outline=None, w=2.2):
        box = (*self.p(cx - r, cy - r), *self.p(cx + r, cy + r))
        if fill is not None:
            self.d.ellipse(box, fill=fill)
        if outline is not None:
            self.d.ellipse(box, outline=outline, width=max(1, int(w * self.u)))

    def arc(self, cx, cy, r, a0, a1, w=2.2, fill=(255, 255, 255, 255)):
        steps = max(6, int(abs(a1 - a0) / 8))
        pts = [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps)),
                cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]
        self.line(pts, w=w, fill=fill)

    def save(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.im.resize((self.size, self.size), Image.LANCZOS).save(path)


def icon(pen, kind, ink=(255, 255, 255, 255), w=2.2):
    """Pictos en trait (grille 24)."""
    L = lambda pts, ww=w: pen.line(pts, ww, ink)  # noqa: E731
    if kind == "Swords":
        L([(4, 4), (16, 16)]); L([(20, 4), (8, 16)])
        L([(13, 19), (19, 13)]); L([(5, 13), (11, 19)])
        L([(16, 16), (20, 20)]); L([(8, 16), (4, 20)])
    elif kind == "Flame":
        pen.poly([(12, 2.5), (14, 7), (17.5, 10.5), (18, 15), (16, 19.5), (12, 21.5), (8, 19.5), (6, 15), (7, 11),
                  (9, 13), (10, 8.5)], fill=ink)
    elif kind == "Wheat":
        L([(12, 22), (12, 6)])
        for y in (7, 11.5, 16):
            pen.poly([(12, y + 3), (8, y + 1.5), (7, y - 1.5), (11, y)], fill=ink)
            pen.poly([(12, y + 3), (16, y + 1.5), (17, y - 1.5), (13, y)], fill=ink)
        pen.poly([(12, 2), (13.5, 5.5), (12, 8), (10.5, 5.5)], fill=ink)
    elif kind == "Fair":
        L([(3, 20), (21, 20)]); L([(5, 20), (12, 7), (19, 20)]); L([(9, 20), (12, 15), (15, 20)])
        L([(12, 7), (12, 2.5)]); pen.poly([(12, 2.5), (17, 4), (12, 5.8)], fill=ink)
    elif kind == "Herd":
        L([(7, 9), (4, 8), (2.5, 4.5)]); L([(17, 9), (20, 8), (21.5, 4.5)])
        pen.poly([(6.5, 8), (17.5, 8), (16, 16), (12, 21), (8, 16)], fill=None, outline=ink, w=w)
        pen.circle(9.8, 12, 1.1, fill=ink); pen.circle(14.2, 12, 1.1, fill=ink)
    elif kind in ("Road", "NoRoad"):
        L([(8, 3), (5, 21)]); L([(16, 3), (19, 21)])
        for y0 in (5, 11, 17):
            L([(12, y0), (12, y0 + 2.5)])
    elif kind == "Inn":
        L([(3.5, 11), (12, 4.5), (20.5, 11)]); L([(6, 10), (6, 20), (18, 20), (18, 10)]); L([(10, 20), (10, 15), (14, 15), (14, 20)])
    elif kind == "Truce":
        L([(6, 21), (6, 3)]); pen.poly([(6, 3.5), (19, 5), (16, 8.5), (19, 12), (6, 12)], fill=None, outline=ink, w=w)
    elif kind == "Cart":
        pen.poly([(3, 7), (15, 7), (15, 15), (3, 15)], fill=None, outline=ink, w=w)
        L([(15, 10), (18, 10), (21, 13.5), (21, 15), (15, 15)])
        pen.circle(7, 18, 2.2, outline=ink, w=w); pen.circle(17, 18, 2.2, outline=ink, w=w)
    elif kind == "Scroll":
        pen.poly([(6, 4), (17, 4), (17, 20), (6, 20)], fill=None, outline=ink, w=w)
        L([(9, 9), (14, 9)]); L([(9, 12.5), (14, 12.5)]); L([(9, 16), (12.5, 16)])
        L([(17, 4), (19.5, 4), (19.5, 7), (17, 7)])
    elif kind == "Tent":
        pen.poly([(3, 20), (12, 4), (21, 20)], fill=None, outline=ink, w=w); L([(9.5, 20), (12, 14.5), (14.5, 20)])
    elif kind == "Shield":
        pen.poly([(12, 3), (20, 6), (20, 12), (17, 18), (12, 21), (7, 18), (4, 12), (4, 6)], fill=None, outline=ink, w=w)
    elif kind == "Bag":
        pen.poly([(9, 8), (15, 8), (19, 14), (18, 20), (6, 20), (5, 14)], fill=None, outline=ink, w=w)
        L([(9, 8), (8, 4), (16, 4), (15, 8)])
    elif kind == "Staff":
        L([(7, 21), (15, 5)]); pen.arc(17, 5.5, 2.6, 180, 400, w=w, fill=ink)
    elif kind == "Lock":
        pen.poly([(5, 11), (19, 11), (19, 21), (5, 21)], fill=None, outline=ink, w=w); pen.arc(12, 9, 4.5, 180, 360, w=w, fill=ink)
        L([(7.5, 9), (7.5, 11)]); L([(16.5, 9), (16.5, 11)])
    elif kind == "Pin":
        pen.arc(12, 9.5, 5.5, 150, 390, w=w, fill=ink); L([(7.2, 12.3), (12, 21), (16.8, 12.3)]); pen.circle(12, 9.5, 1.7, fill=ink)
    else:
        raise ValueError(kind)


def mark_png(path, state):
    """Forme d'un voisin, blanche (teinte = relation) bordée de sombre."""
    pen = Pen(20)
    white, dark = (255, 255, 255, 255), (13, 18, 15, 255)
    if state == "Ally":
        pen.circle(12, 12, 10.2, outline=white, w=2.4)
        pen.circle(12, 12, 6.3, fill=dark); pen.circle(12, 12, 5.2, fill=white)
    elif state == "Friendly":
        pen.circle(12, 12, 8, fill=dark); pen.circle(12, 12, 6.8, fill=white)
    elif state == "Neutral":
        pen.circle(12, 12, 8.4, fill=dark); pen.circle(12, 12, 7.2, fill=white); pen.circle(12, 12, 4.2, fill=dark)
    elif state == "Tense":
        pen.poly([(12, 2), (22, 20.5), (2, 20.5)], fill=dark); pen.poly([(12, 4.8), (19.8, 19.2), (4.2, 19.2)], fill=white)
    else:
        pen.poly([(12, 1), (23, 12), (12, 23), (1, 12)], fill=dark); pen.poly([(12, 3.2), (20.8, 12), (12, 20.8), (3.2, 12)], fill=white)
    pen.save(path)


def halo_png(path):
    pen = Pen(30)
    pen.circle(12, 12, 11, outline=(255, 255, 255, 255), w=1.8)
    pen.save(path)


def poi_png(path, kind):
    """Puce de la carte, déjà colorée (fond or ou rouge, picto sombre)."""
    traveller = kind in ("Caravan", "Merchant", "Pilgrim", "Messenger")
    size = 16 if traveller else POI
    pen = Pen(size)
    bg = {"Skirmish": (239, 111, 94, 255)}.get(kind, (232, 196, 106, 255) if traveller else (201, 162, 78, 255))
    edge = (13, 18, 15, 255)
    if traveller:
        pen.circle(12, 12, 11.5, fill=edge); pen.circle(12, 12, 10, fill=bg)
    else:
        pen.d.rounded_rectangle((0, 0, pen.n - 1, pen.n - 1), radius=pen.n * 0.22, fill=edge)
        m = pen.n * 0.07
        pen.d.rounded_rectangle((m, m, pen.n - 1 - m, pen.n - 1 - m), radius=pen.n * 0.18, fill=bg)
    sub = {"Camp": "Tent", "Herd": "Herd", "Inn": "Inn", "Caravan": "Cart", "Merchant": "Bag", "Pilgrim": "Staff",
           "Messenger": "Scroll", "Skirmish": "Swords"}[kind]
    inner = Pen(size)
    icon(inner, sub, ink=(26, 20, 8, 255), w=2.8)
    small = inner.im.resize((int(pen.n * 0.68),) * 2, Image.LANCZOS)
    off = int(pen.n * 0.16)
    pen.im.alpha_composite(small, (off, off))
    pen.save(path)


def icon_png(path, kind, size=24):
    pen = Pen(size)
    icon(pen, kind)
    pen.save(path)


def textures():
    for st in STATES:
        mark_png(BOARD / f"World_Mark_{st}.png", st)
    halo_png(BOARD / "World_Halo.png")
    for k in POI_KINDS:
        poi_png(BOARD / f"World_Poi_{k}.png", k)
    for k in sorted({"Swords", "Flame", "Wheat", "Fair", "Herd", "Road", "NoRoad", "Inn", "Truce", "Cart", "Scroll", "Tent",
                     "Shield", "Bag", "Staff", "Lock", "Pin"}):
        icon_png(BOARD / f"World_Ico_{k}.png", k)
    print("écrit Board/World_*.png")


# ---------------------------------------------------------------- page

def radius(blocks):
    if blocks <= 1000:
        return MAP_R * 0.75 * blocks / 1000
    return MAP_R * (0.75 + 0.25 * min(1.0, (blocks - 1000) / 1400))


def page(theme_id):
    t = THEMES[theme_id]
    acc, title = t["accent"], t["title"]
    emblem = (f'Background: (TexturePath: "{MEMORIES}{t["emblem"]}.png", Color: {acc});' if t["emblem"]
              else "Visible: false;")
    here_emblem = (f'Group {{ Anchor: (Left: 4, Top: 4, Width: 14, Height: 14); Background: (TexturePath: "{MEMORIES}{t["emblem"]}.png", Color: #10180e); }}'
                   if t["emblem"] else "")
    L = []
    w = L.append
    ico = lambda name, size, color, extra="": (  # noqa: E731
        f'Group {{ Anchor: (Width: {size}, Height: {size}{extra}); Background: (TexturePath: "Board/World_Ico_{name}.png", Color: {color}); }}')

    w('$C = "../../../Common.ui";')
    w('$Sounds = "../../../Sounds.ui";')
    w("")
    w(f"// Généré par tools/generate_world_tab.py (thème {theme_id or 'neutre'}) : ne pas modifier à la main.")
    w("")
    w(f"@Accent = {acc};")
    w(f"@Section = LabelStyle(FontSize: 13, RenderBold: true, RenderUppercase: true, TextColor: {title}, VerticalAlignment: Center);")
    w("@Small = LabelStyle(FontSize: 12, TextColor: #96a9be, VerticalAlignment: Center);")
    w("@SmallWrap = LabelStyle(FontSize: 12, TextColor: #96a9be, Wrap: true);")
    w("@Tiny = LabelStyle(FontSize: 11, TextColor: #a8b6c4, VerticalAlignment: Center);")
    w("@Head = LabelStyle(FontSize: 11, RenderBold: true, RenderUppercase: true, TextColor: #96a9be, VerticalAlignment: Center);")
    w("@MapButton = (Default: (Background: (Color: #000000(0.01))), Hovered: (Background: (Color: #ffffff(0.16))), "
      "Pressed: (Background: (Color: #ffffff(0.26))), Sounds: $Sounds.@ButtonsLight);")
    w("@RowButton = (Default: (Background: (Color: #0c151e(0.7))), Hovered: (Background: (Color: #1b2a38(0.85))), "
      "Pressed: (Background: (Color: #22364a(0.9))), Sounds: $Sounds.@ButtonsLight);")
    w("")
    w("@Card = Group {")
    w(f"  Background: (Color: {CARD});")
    w("};")
    w("")
    w("@Bar = ProgressBar {")
    w(f'  Background: "{COMMON}ProgressBar.png";')
    w(f'  BarTexturePath: "{f"Board/Bar_{theme_id}.png" if theme_id else "Board/Bar_Default.png"}";')
    w("  Value: 0;")
    w("};")
    w("")
    w("$C.@PageOverlay {")
    w("  LayoutMode: Middle;")
    w("")
    w("  $C.@DecoratedContainer {")
    w(f"    Anchor: (Width: {PANEL_WIDTH}, Height: {PANEL_HEIGHT});")
    w("")
    w("    #Title {")
    w("      Group {")
    w("        LayoutMode: Center;")
    w(f"        Group #TitleEmblem {{ Anchor: (Width: 26, Height: 26, Right: 8); {emblem} }}")
    w(f'        Label #PageTitle {{ Style: (...$C.@TitleStyle, FontSize: 16, TextColor: {title}); }}')
    w("      }")
    w("    }")
    w("")
    w("    #Content {")
    w("      LayoutMode: Top;")
    w("      Padding: (Full: 17, Top: 12);")
    tab_strip(w, acc, "World")

    # ---- tuiles
    w("      Group {")
    w("        LayoutMode: Left;")
    w("        Anchor: (Height: 56, Bottom: 8);")
    for i in range(STATS):
        w(f"        @Card #St{i} {{")
        w("          LayoutMode: Full;")
        w(f"          Anchor: ({'Right: 8' if i < STATS - 1 else 'Right: 0'});")
        w("          FlexWeight: 1;")
        w(f"          Group #StWarn{i} {{ Anchor: (Full: 0); Background: (Color: {RED}(0.16)); Visible: false; }}")
        w("          Group {")
        w("            LayoutMode: Top;")
        w("            Anchor: (Full: 0);")
        w("            Padding: (Horizontal: 10, Vertical: 4);")
        w(f'            {label(f"StLabel{i}", "@Head", anchor="Height: 15")}')
        w(f'            {label(f"StValue{i}", "LabelStyle(FontSize: 16, RenderBold: true, TextColor: #e6edf3, VerticalAlignment: Center)", anchor="Height: 20")}')
        w(f'            {label(f"StSub{i}", "@Tiny", anchor="Height: 14")}')
        w("          }")
        w("        }")
    w("      }")

    w("      Group {")
    w("        LayoutMode: Left;")
    w("        FlexWeight: 1;")

    # ---- colonne gauche : carte + liste
    w("        Group #LeftColumn {")
    w("          LayoutMode: Top;")
    w(f"          Anchor: (Width: {LEFT}, Right: 14);")
    w("          @Card #Map {")
    w("            LayoutMode: Full;")
    w(f"            Anchor: (Height: {MAP_H}, Bottom: 8);")
    w(f'            {label("MapTitle", "@Section", anchor="Left: 10, Top: 6, Width: 200, Height: 16")}')
    w(f'            {label("MapHint", "LabelStyle(FontSize: 11, TextColor: #96a9be, HorizontalAlignment: End, VerticalAlignment: Center)", anchor=f"Left: {LEFT - 290}, Top: 6, Width: 280, Height: 16")}')
    for b in RINGS:
        r = round(radius(b))
        alpha = {500: 0.13, 1000: 0.18, 2400: 0.34}[b]
        w(f'            Group {{ Anchor: (Left: {MAP_CX - r}, Top: {MAP_CY - r}, Width: {2 * r}, Height: {2 * r}); '
          f'Background: (TexturePath: "Board/World_Ring{b}.png", Color: #ffffff({alpha})); }}')
        w(f'            {label(None, "LabelStyle(FontSize: 9, TextColor: #6f8296, VerticalAlignment: Center)", text=chr(34) + str(b) + chr(34), anchor=f"Left: {MAP_CX + 4}, Top: {MAP_CY - r + 1}, Width: 36, Height: 11")}')
    w(f'            Group {{ Anchor: (Left: {MAP_CX}, Top: {MAP_CY - MAP_R}, Width: 1, Height: {2 * MAP_R}); Background: (Color: #ffffff(0.07)); }}')
    w(f'            Group {{ Anchor: (Left: {MAP_CX - MAP_R}, Top: {MAP_CY}, Width: {2 * MAP_R}, Height: 1); Background: (Color: #ffffff(0.07)); }}')
    w(f'            {label("MapNorth", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #96a9be, HorizontalAlignment: Center, VerticalAlignment: Center)", text=chr(34) + "N" + chr(34), anchor=f"Left: {MAP_CX - 8}, Top: {MAP_CY - MAP_R - 15}, Width: 16, Height: 13")}')
    for k in range(TRAIL):
        w(f"            Group #Tp{k} {{ Anchor: (Left: 0, Top: 0, Width: 4, Height: 4); Visible: false;")
        w(f'              Group #TpOn{k} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: {acc}); Visible: false; }}')
        w(f'              Group #TpOff{k} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: #dcd2b4(0.4)); Visible: false; }}')
        w("            }")
    w(f'            Group #Here {{ Anchor: (Left: {MAP_CX - 11}, Top: {MAP_CY - 11}, Width: 22, Height: 22); '
      f'Background: (TexturePath: "Board/Dot.png", Color: {acc}); {here_emblem} }}')
    for j in range(POIS):
        w(f"            Group #Po{j} {{ Anchor: (Left: 0, Top: 0, Width: {POI}, Height: {POI}); LayoutMode: Full; Visible: false;")
        for k in POI_KINDS:
            w(f'              Group #Po{k}{j} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Poi_{k}.png"); Visible: false; }}')
        w("            }")
        w(f"            Group #PoTag{j} {{ Anchor: (Left: 0, Top: 0, Width: {NAME_W}, Height: 14); LayoutMode: Full; Visible: false;")
        w(f'              {label(f"PoTagR{j}", f"LabelStyle(FontSize: 10, RenderBold: true, TextColor: #d9c48a, VerticalAlignment: Center)", anchor="Full: 0")}')
        w(f'              {label(f"PoTagL{j}", f"LabelStyle(FontSize: 10, RenderBold: true, TextColor: #d9c48a, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Full: 0")}')
        w("            }")
    for i in range(MARKS):
        w(f"            Group #Mk{i} {{ Anchor: (Left: 0, Top: 0, Width: {MARK}, Height: {MARK}); LayoutMode: Full; Visible: false;")
        w(f'              Group #MkHalo{i} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Halo.png", Color: {acc}); Visible: false; }}')
        for st in STATES:
            w(f'              Group #Mk{st}{i} {{ Anchor: (Left: 5, Top: 5, Width: 20, Height: 20); Background: (TexturePath: "Board/World_Mark_{st}.png", Color: {STATE_COLORS[st]}); Visible: false; }}')
        w(f"              Button #MkBtn{i} {{ Anchor: (Full: 0); Style: @MapButton; TextTooltipStyle: $C.@DefaultTextTooltipStyle; }}")
        w("            }")
        w(f"            Group #MkName{i} {{ Anchor: (Left: 0, Top: 0, Width: {NAME_W}, Height: 16); LayoutMode: Full; Visible: false;")
        w(f'              {label(f"MkNameR{i}", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #e6edf3, VerticalAlignment: Center)", anchor="Full: 0")}')
        w(f'              {label(f"MkNameL{i}", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #e6edf3, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Full: 0")}')
        w("            }")
    # légende
    w(f"            Group {{ Anchor: (Left: 0, Top: {MAP_H - 24}, Height: 24); LayoutMode: Left; Padding: (Horizontal: 10); Background: (Color: #000000(0.22));")
    for st in STATES:
        w(f'              Group {{ Anchor: (Width: 13, Height: 13, Top: 5, Right: 3); Background: (TexturePath: "Board/World_Mark_{st}.png", Color: {STATE_COLORS[st]}); }}')
        w(f'              {label(f"Lg{st}", "@Tiny", anchor="Width: 50, Height: 24")}')
    w("              Group { Anchor: (Width: 1, Height: 14, Top: 5, Right: 8); Background: (Color: #ffffff(0.15)); }")
    for k, lid in (("Camp", "LgCamp"), ("Herd", "LgHerd"), ("Inn", "LgInn"), ("Caravan", "LgTrav")):
        size = 12
        w(f'              Group {{ Anchor: (Width: {size}, Height: {size}, Top: 6, Right: 3); Background: (TexturePath: "Board/World_Poi_{k}.png"); }}')
        w(f'              {label(lid, "@Tiny", anchor="Width: 58, Height: 24")}')
    w("            }")
    w("          }")
    # liste
    w("          Group {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 18, Bottom: 4);")
    w(f'            {label("NbTitle", "@Section", extra=" FlexWeight: 1;")}')
    w(f'            {label("NbHint", "LabelStyle(FontSize: 11, TextColor: #96a9be, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 240")}')
    w("          }")
    w(f'          {label("NbNone", "@SmallWrap", anchor="Height: 36", extra=" Visible: false;")}')
    w("          Group #NbList {")
    w("            LayoutMode: TopScrolling;")
    w("            ScrollbarStyle: $C.@DefaultScrollbarStyle;")
    w("            FlexWeight: 1;")
    for i in range(ROWS):
        w(f"            Button #Nb{i} {{")
        w("              Anchor: (Height: 34, Bottom: 4, Right: 10);")
        w("              Style: @RowButton;")
        w("              LayoutMode: Full;")
        w(f"              Group #NbOn{i} {{ Anchor: (Full: 0); Background: (Color: {acc}(0.16)); Visible: false;")
        w(f"                Group {{ Anchor: (Left: 0, Top: 0, Bottom: 0, Width: 3); Background: (Color: {acc}); }}")
        w("              }")
        w("              Group {")
        w("                LayoutMode: Left;")
        w("                Anchor: (Full: 0);")
        w("                Padding: (Horizontal: 10);")
        w("                Group {")
        w("                  LayoutMode: Full;")
        w("                  Anchor: (Width: 16, Height: 16, Top: 9, Right: 8);")
        for st in STATES:
            w(f'                  Group #Nb{st}{i} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Mark_{st}.png", Color: {STATE_COLORS[st]}); Visible: false; }}')
        w("                }")
        w(f'                {label(f"NbName{i}", "LabelStyle(FontSize: 13, RenderBold: true, TextColor: #eef2f5, VerticalAlignment: Center)", anchor="Width: 150")}')
        w(f'                {label(f"NbPeople{i}", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #c9d1d9, VerticalAlignment: Center)", anchor="Width: 76")}')
        w("                Group {")
        w("                  LayoutMode: Left;")
        w("                  Anchor: (Width: 90);")
        for f in FLAGS:
            color = {"Road": "#c9d1d9", "Inn": GOLD, "Truce": "#a9dcef", "Trav": GOLD, "Fight": RED}[f]
            name = {"Road": "Road", "Inn": "Inn", "Truce": "Truce", "Trav": "Cart", "Fight": "Swords"}[f]
            w(f'                  Group #NbFlag{f}{i} {{ Anchor: (Width: 14, Height: 14, Top: 10, Right: 4); Background: (TexturePath: "Board/World_Ico_{name}.png", Color: {color}); Visible: false; }}')
        w("                }")
        w(f'                {label(f"NbDist{i}", "LabelStyle(FontSize: 11, TextColor: #a8b6c4, HorizontalAlignment: End, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
        w(f'                {label(f"NbState{i}", "LabelStyle(FontSize: 11, RenderBold: true, RenderUppercase: true, TextColor: #b3bfcc, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 82")}')
        w("              }")
        w("            }")
    w(f'            {label("NbMore", "@Small", anchor="Height: 18")}')
    w("          }")
    w("        }")

    # ---- colonne droite : fiche + ce qui vient
    w("        Group #RightColumn {")
    w("          LayoutMode: Top;")
    w("          FlexWeight: 1;")
    w("          @Card #Sheet {")
    w("            LayoutMode: Top;")
    w("            Anchor: (Bottom: 8);")
    w("            Padding: (Horizontal: 10, Vertical: 8);")
    w(f'            {label("ShNone", "@SmallWrap", anchor="Height: 48", extra=" Visible: false;")}')
    w("            Group #ShBody {")
    w("              LayoutMode: Top;")
    # ligne 1
    w("              Group {")
    w("                LayoutMode: Left;")
    w("                Anchor: (Height: 28);")
    w("                Group {")
    w("                  LayoutMode: Full;")
    w("                  Anchor: (Width: 18, Height: 18, Top: 5, Right: 7);")
    for st in STATES:
        w(f'                  Group #Sh{st} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Mark_{st}.png", Color: {STATE_COLORS[st]}); Visible: false; }}')
    w("                }")
    w(f'                {label("ShName", "LabelStyle(FontSize: 17, RenderBold: true, TextColor: #f2ead2, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
    w(f'                {label("ShPeople", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #c9d1d9, VerticalAlignment: Center)", anchor="Width: 80, Right: 6")}')
    w("                Button #ShMark {")
    w("                  Anchor: (Width: 168, Height: 28);")
    w("                  Style: $C.@SecondaryButtonStyle;")
    w("                  LayoutMode: Center;")
    w(f"                  {ico('Pin', 14, '#d6dde6', ', Right: 5')}")
    w(f'                  {label("ShMarkText", "LabelStyle(FontSize: 12, RenderBold: true, TextColor: #d6dde6, VerticalAlignment: Center)")}')
    w("                }")
    w("              }")
    w(f'              {label("ShWhere", "@Small", anchor="Height: 18, Bottom: 4")}')
    # relation
    w("              Group {")
    w("                LayoutMode: Left;")
    w("                Anchor: (Height: 18);")
    w(f'                {label("ShRelLabel", "@Head", anchor="Width: 70")}')
    w(f'                {label("ShRelState", "LabelStyle(FontSize: 13, RenderBold: true, TextColor: #b3bfcc, VerticalAlignment: Center)", anchor="Width: 110")}')
    w(f'                {label("ShRelValue", "LabelStyle(FontSize: 12, RenderBold: true, TextColor: #a8b6c4, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
    w("              }")
    w("              Group #ShGauge {")
    w("                LayoutMode: Left;")
    w("                Anchor: (Height: 14, Top: 2);")
    for k in range(SEGMENTS):
        col = STATE_COLORS[SEGMENT_STATE[k]]
        w(f"                Group {{ LayoutMode: Full; FlexWeight: 1; Anchor: ({'Right: 1' if k < SEGMENTS - 1 else 'Right: 0'});")
        w(f"                  Group {{ Anchor: (Left: 0, Right: 0, Top: 3, Height: 8); Background: (Color: {col}(0.28)); }}")
        w(f"                  Group #ShSeg{k} {{ Anchor: (Left: 0, Right: 0, Top: 3, Height: 8); Background: (Color: {col}); Visible: false; }}")
        w(f"                  Group #ShTick{k} {{ Anchor: (Left: 2, Right: 2, Top: 0, Height: 14); Background: (Color: #f4f1e8); Visible: false; }}")
        w("                }")
    w("              }")
    w("              Group {")
    w("                LayoutMode: Left;")
    w("                Anchor: (Height: 14);")
    for st, weight in ZONE_WEIGHTS:
        w(f'                {label(f"ShZone{st}", "LabelStyle(FontSize: 10, TextColor: #8796a6, HorizontalAlignment: Center, VerticalAlignment: Center)", extra=f" FlexWeight: {weight};")}')
    w("              }")
    w(f'              {label("ShTend", "LabelStyle(FontSize: 11, TextColor: #96a9be, Wrap: true)", anchor="Height: 28, Bottom: 2")}')
    # pastilles (2 x 2)
    for r in range(CHIPS // 2):
        w("              Group {")
        w("                LayoutMode: Left;")
        w("                Anchor: (Height: 24, Bottom: 4);")
        for c in range(2):
            k = r * 2 + c
            w(f"                Group #ShChip{k} {{")
            w("                  LayoutMode: Full;")
            w("                  FlexWeight: 1;")
            w(f"                  Anchor: ({'Right: 5' if c == 0 else 'Right: 0'});")
            w("                  Background: (Color: #ffffff(0.06));")
            w(f"                  Group #ShChipRed{k} {{ Anchor: (Full: 0); Background: (Color: {RED}(0.18)); Visible: false; }}")
            w("                  Group {")
            w("                    LayoutMode: Left;")
            w("                    Anchor: (Full: 0);")
            w("                    Padding: (Horizontal: 7);")
            w("                    Group {")
            w("                      LayoutMode: Full;")
            w("                      Anchor: (Width: 14, Height: 14, Top: 5, Right: 6);")
            for name, color in CHIP_ICONS.items():
                tex = "Road" if name == "NoRoad" else ("Swords" if name == "Fight" else name)
                w(f'                      Group #ShChip{name}{k} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Ico_{tex}.png", Color: {color}); Visible: false; }}')
            w("                    }")
            w(f'                    {label(f"ShChipText{k}", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #d6dde6, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
            w("                  }")
            w("                }")
        w("              }")
    # voyages
    w("              Group {")
    w("                LayoutMode: Left;")
    w("                Anchor: (Height: 20, Bottom: 4);")
    w(f'                {label("ShTrips", "@Head", anchor="Width: 110")}')
    for k, (vid, wid, color) in enumerate((("ShTripsGone", "ShTripsGoneTxt", "#e6edf3"), ("ShTripsOk", "ShTripsOkTxt", "#9ed36a"),
                                          ("ShTripsLost", "ShTripsLostTxt", "#ef8a7a"))):
        w(f'                {label(vid, f"LabelStyle(FontSize: 14, RenderBold: true, TextColor: {color}, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 26, Right: 4")}')
        w(f'                {label(wid, "@Tiny", anchor="Width: " + ("110" if k == 2 else "60"))}')
    w(f'                {label("ShTripsNone", "@Tiny", extra=" FlexWeight: 1; Visible: false;")}')
    w("              }")
    # voyageurs sur la liaison
    for k in range(TRACKS):
        w(f"              Group #ShTr{k} {{")
        w("                LayoutMode: Top;")
        w("                Anchor: (Height: 40, Bottom: 4);")
        w("                Background: (Color: #000000(0.22));")
        w("                Padding: (Horizontal: 7, Vertical: 3);")
        w("                Group {")
        w("                  LayoutMode: Left;")
        w("                  Anchor: (Height: 18);")
        w("                  Group {")
        w("                    LayoutMode: Left;")
        w("                    Anchor: (Width: 86, Height: 16, Top: 1, Right: 6);")
        w(f"                    Background: (Color: {GOLD});")
        w("                    Padding: (Horizontal: 4);")
        w("                    Group {")
        w("                      LayoutMode: Full;")
        w("                      Anchor: (Width: 11, Height: 11, Top: 2, Right: 3);")
        for name in TRACK_ICONS:
            w(f'                      Group #ShTr{name}{k} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Ico_{name}.png", Color: #1a1408); Visible: false; }}')
        w("                    }")
        w(f'                    {label(f"ShTrKind{k}", "LabelStyle(FontSize: 9, RenderBold: true, RenderUppercase: true, TextColor: #1a1408, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
        w("                  }")
        w(f'                  {label(f"ShTrText{k}", "LabelStyle(FontSize: 12, RenderBold: true, TextColor: #e6edf3, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
        w(f'                  {label(f"ShTrEta{k}", f"LabelStyle(FontSize: 11, RenderBold: true, TextColor: {GOLD}, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 150")}')
        w("                }")
        w("                Group {")
        w("                  LayoutMode: Left;")
        w("                  Anchor: (Height: 14, Top: 2);")
        w(f'                  {label(f"ShTrFrom{k}", "LabelStyle(FontSize: 10, TextColor: #8796a6, VerticalAlignment: Center)", anchor="Width: 100")}')
        w(f"                  @Bar #ShTrBar{k} {{ FlexWeight: 1; Anchor: (Height: 6, Top: 4); }}")
        w(f'                  {label(f"ShTrTo{k}", "LabelStyle(FontSize: 10, TextColor: #8796a6, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 100")}')
        w("                }")
        w("              }")
    # ce que le joueur peut faire
    w("              Group #ShHint {")
    w("                LayoutMode: Full;")
    w("                Anchor: (Height: 46);")
    w(f"                Group #ShHintInfo {{ Anchor: (Full: 0); Background: (Color: {GOLD}(0.12)); }}")
    w(f"                Group #ShHintWarn {{ Anchor: (Full: 0); Background: (Color: {RED}(0.15)); Visible: false; }}")
    w("                Group {")
    w("                  LayoutMode: Left;")
    w("                  Anchor: (Full: 0);")
    w("                  Padding: (Horizontal: 8, Vertical: 4);")
    w("                  Group {")
    w("                    LayoutMode: Full;")
    w("                    Anchor: (Width: 16, Height: 16, Top: 2, Right: 8);")
    for name in HINT_ICONS:
        w(f'                    Group #ShHintIco{name} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/World_Ico_{name}.png", Color: #f0d58a); Visible: false; }}')
    w("                  }")
    w(f'                  {label("ShHintText", "LabelStyle(FontSize: 12, TextColor: #e6e1d2, Wrap: true)", extra=" FlexWeight: 1;")}')
    w("                }")
    w("              }")
    w("            }")
    w("          }")
    # ce qui vient
    w(f'          {label("AgTitle", "@Section", anchor="Height: 18, Bottom: 4")}')
    w(f'          {label("AgNone", "@SmallWrap", anchor="Height: 22", extra=" Visible: false;")}')
    for i in range(AGENDA):
        w(f"          @Card #Ag{i} {{")
        w("            LayoutMode: Full;")
        w("            Anchor: (Height: 32, Bottom: 4);")
        w(f"            Group #AgGold{i} {{ Anchor: (Full: 0); Background: (Color: {GOLD}(0.15)); Visible: false; }}")
        w(f"            Group #AgRed{i} {{ Anchor: (Full: 0); Background: (Color: {RED}(0.17)); Visible: false; }}")
        w("            Group {")
        w("              LayoutMode: Left;")
        w("              Anchor: (Full: 0);")
        w("              Padding: (Horizontal: 7);")
        w("              Group {")
        w("                LayoutMode: Full;")
        w("                Anchor: (Width: 24, Height: 24, Top: 4, Right: 8);")
        w(f'                Group #AgDiscPlain{i} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: #ffffff(0.09)); }}')
        w(f'                Group #AgDiscGold{i} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: {GOLD}); Visible: false; }}')
        w(f'                Group #AgDiscRed{i} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: {RED}); Visible: false; }}')
        for name in AGENDA_ICONS:
            w(f'                Group #AgIco{name}{i} {{ Anchor: (Left: 5, Top: 5, Width: 14, Height: 14); Background: (TexturePath: "Board/World_Ico_{name}.png", Color: #c9d1d9); Visible: false; }}')
            w(f'                Group #AgIcoDark{name}{i} {{ Anchor: (Left: 5, Top: 5, Width: 14, Height: 14); Background: (TexturePath: "Board/World_Ico_{name}.png", Color: #1a1408); Visible: false; }}')
        w("              }")
        w(f'              {label(f"AgWhen{i}", "LabelStyle(FontSize: 10, RenderBold: true, RenderUppercase: true, TextColor: #b4c0cc, VerticalAlignment: Center, Wrap: true)", anchor="Width: 104")}')
        w(f'              {label(f"AgText{i}", "LabelStyle(FontSize: 12, TextColor: #e6edf3, VerticalAlignment: Center, Wrap: true)", extra=" FlexWeight: 1;")}')
        w("              Group {")
        w("                LayoutMode: Full;")
        w("                Anchor: (Width: 150, Height: 20, Top: 6);")
        w(f"                Group #AgPlaceHere{i} {{ Anchor: (Full: 0); Background: (Color: {acc}); Visible: false; }}")
        w(f"                Group #AgPlacePlain{i} {{ Anchor: (Full: 0); Background: (Color: #ffffff(0.08)); Visible: false; }}")
        w(f'                {label(f"AgPlace{i}", "LabelStyle(FontSize: 10, RenderBold: true, TextColor: #c9d1d9, HorizontalAlignment: Center, VerticalAlignment: Center)", anchor="Full: 0")}')
        w("              }")
        w("            }")
        w("          }")
    w("          Group #AgLock {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 30);")
    w("            Background: (Color: #ffffff(0.04));")
    w("            Padding: (Horizontal: 8);")
    w(f"            {ico('Lock', 14, '#a8b6c4', ', Top: 8, Right: 8')}")
    w(f'            {label("AgLockText", "LabelStyle(FontSize: 12, TextColor: #c9d1d9, VerticalAlignment: Center, Wrap: true)", extra=" FlexWeight: 1;")}')
    w("            Button #AgLockBtn {")
    w("              Anchor: (Width: 90, Height: 24, Top: 3);")
    w("              Style: $C.@SecondaryButtonStyle;")
    w("              LayoutMode: Center;")
    w(f'              {label("AgLockBtnText", f"LabelStyle(FontSize: 12, RenderBold: true, TextColor: {acc}, VerticalAlignment: Center)")}')
    w("            }")
    w("          }")
    w("        }")
    w("      }")
    # rumeur
    w("      Group {")
    w("        LayoutMode: Left;")
    w("        Anchor: (Height: 24, Top: 6);")
    w(f"        {ico('Scroll', 15, title, ', Top: 4, Right: 6')}")
    w(f'        {label("RumourTitle", f"LabelStyle(FontSize: 11, RenderBold: true, RenderUppercase: true, TextColor: {title}, VerticalAlignment: Center)", anchor="Width: 120")}')
    w(f'        {label("RumourText", "LabelStyle(FontSize: 12, TextColor: #c9d1d9, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
    w("      }")
    w("    }")
    w("  }")
    w("}")
    w("")
    w("$C.@BackButton {}")
    return "\n".join(L).replace("Style: LabelStyle(", "Style: (") + "\n"


def ring_png(path, size):
    k = 4
    n = size * k
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse((k, k, n - k, n - k), outline=(255, 255, 255, 255), width=k)
    im.resize((size, size), Image.LANCZOS).save(path)


# ---------------------------------------------------------------- textes

P = "nakasar_peuples.world.tab."
TEXTS = {
    "st.neighbours": ("Voisins", "Neighbours"),
    "st.neighboursValue": ("{count} village(s)", "{count} village(s)"),
    "st.none": ("aucun à moins de 2400 blocs", "none within 2400 blocks"),
    "st.count.ally": ("{count} allié(s)", "{count} ally(ies)"),
    "st.count.friendly": ("{count} ami(s)", "{count} friendly"),
    "st.count.tense": ("{count} tendu(s)", "{count} tense"),
    "st.count.rival": ("{count} rival(aux)", "{count} rival(s)"),
    "st.count.neutral": ("tous neutres", "all neutral"),
    "st.next": ("Prochain événement", "Next event"),
    "st.now": ("En ce moment", "Right now"),
    "st.nothing": ("Rien de prévu", "Nothing planned"),
    "st.roads": ("Sur les routes", "On the roads"),
    "st.roadsValue": ("{count} voyageur(s)", "{count} traveller(s)"),
    "st.roadsSub": ("{out} part(ent) · {in} arrive(nt)", "{out} leaving · {in} arriving"),
    "st.roadsNone": ("personne en route", "nobody on the road"),
    "st.around": ("Autour du village", "Around the village"),
    "st.aroundValue": ("{count} campement(s)", "{count} camp(s)"),
    "st.herd": ("un troupeau à {distance} blocs", "a herd {distance} blocks away"),
    "st.noHerd": ("pas de troupeau en vue", "no herd in sight"),
    "ev.skirmish": ("Escarmouche", "Skirmish"),
    "ev.raid": ("Raid", "Raid"),
    "ev.feast": ("Fête des moissons", "Harvest feast"),
    "ev.fair": ("Foire des peuples", "Peoples' fair"),
    "ev.migration": ("Migration", "Migration"),
    "ev.herd": ("Troupeau", "Herd"),
    "map.title": ("Alentours", "Surroundings"),
    "map.hint": ("clique un village pour sa fiche", "click a village to see its sheet"),
    "legend.camp": ("Campement", "Camp"),
    "legend.herd": ("Troupeau", "Herd"),
    "legend.inn": ("Auberge", "Inn"),
    "legend.traveller": ("Voyageur", "Traveller"),
    "state.ally": ("Allié", "Ally"),
    "state.friendly": ("Ami", "Friendly"),
    "state.neutral": ("Neutre", "Neutral"),
    "state.tense": ("Tendu", "Tense"),
    "state.rival": ("Rival", "Rival"),
    "poi.skirmish": ("Escarmouche", "Skirmish"),
    "list.title": ("Voisins · {count}", "Neighbours · {count}"),
    "list.hint": ("du plus proche au plus loin", "nearest first"),
    "list.dist": ("{distance} blocs · {direction}", "{distance} blocks · {direction}"),
    "list.more": ("… et {count} voisin(s) de plus", "… and {count} more neighbour(s)"),
    "sheet.none": ("Aucun voisin à moins de 2400 blocs : voyageurs, auberges et foires viendront avec eux.",
                   "No neighbour within 2400 blocks: travellers, inns and fairs will come with them."),
    "sheet.mark": ("Marquer sur la carte", "Mark on the map"),
    "sheet.unmark": ("Retirer le repère", "Remove the marker"),
    "sheet.marker": ("Repère : {village}", "Marker: {village}"),
    "sheet.where": ("{distance} blocs, direction {direction}, par la route balisée",
                    "{distance} blocks, towards the {direction}, by the marked road"),
    "sheet.whereLand": ("{distance} blocs, direction {direction} ({path} blocs de marche)",
                        "{distance} blocks, towards the {direction} ({path} blocks on foot)"),
    "sheet.relation": ("Relation", "Relation"),
    "sheet.value": ("{value} sur 100", "{value} out of 100"),
    "sheet.tend": ("Tend vers {target}, d'un point tous les 3 jours. Une caravane arrivée : +5, un autre voyageur : +1.",
                   "Drifts towards {target}, one point every 3 days. A caravan arriving: +5, another traveller: +1."),
    "sheet.tendTruce": ("Trêve encore {days} jour(s), puis la relation tend vers {target}.",
                        "Truce for {days} more day(s), then the relation drifts towards {target}."),
    "sheet.road": ("Route balisée", "Marked road"),
    "sheet.noRoad": ("Pas de route balisée", "No marked road"),
    "sheet.inn": ("Auberge niv. {level}", "Inn lvl {level}"),
    "sheet.innWorks": ("Auberge en chantier", "Inn being built"),
    "sheet.innSoon": ("Auberge : {count} passage(s) sur {need}", "Inn: {count} of {need} passages"),
    "sheet.truce": ("Trêve · {days} j", "Truce · {days} d"),
    "sheet.fight": ("Escarmouche en cours", "Skirmish under way"),
    "sheet.trips": ("Voyages d'ici", "Trips from here"),
    "sheet.gone": ("partis", "left"),
    "sheet.ok": ("arrivés", "arrived"),
    "sheet.lost": ("perdus en route", "lost on the way"),
    "sheet.noTrips": ("aucun pour l'instant", "none yet"),
    "sheet.trOut": ("Part vers {village}", "Heading to {village}"),
    "sheet.trIn": ("Arrive de {village}", "Coming from {village}"),
    "sheet.eta": ("encore {left} blocs · ~{minutes} min", "{left} blocks left · ~{minutes} min"),
    "sheet.etaSoon": ("encore {left} blocs · bientôt là", "{left} blocks left · almost there"),
    "hint.fight": ("Escarmouche en cours à {distance} blocs, direction {direction} : rejoins les guerriers. +{reputation} de réputation si {village} l'emporte.",
                   "Skirmish under way {distance} blocks away, towards the {direction}: join the fighters. +{reputation} reputation if {village} wins."),
    "hint.pending": ("Escarmouche attendue vers {hour} h sur la route. Sois là pour prêter main-forte.",
                     "Skirmish expected around {hour}:00 on the road. Be there to lend a hand."),
    "hint.parley": ("Un messager entre les deux villages peut te confier une lettre de paix si tu es Ami des deux : +{parley} et {days} jours de trêve.",
                    "A messenger between the two villages can hand you a peace letter if you are Friend of both: +{parley} and a {days}-day truce."),
    "hint.truce": ("Trêve encore {days} jour(s) : pas d'escarmouche d'ici là.", "Truce for {days} more day(s): no skirmish until then."),
    "hint.escort": ("Des voyageurs sont sur cette route : escorte-les jusqu'au bout pour +{reputation} de réputation.",
                    "Travellers are on this road: escort them all the way for +{reputation} reputation."),
    "hint.reinforce": ("Allié à moins de {range} blocs : ses gardes viendront en renfort si l'un des deux villages est attaqué.",
                       "Ally within {range} blocks: its guards will come to help if either village is attacked."),
    "hint.inn": ("L'aubergiste de la route accueille les voyageurs et tient boutique à mi-chemin.",
                 "The road's innkeeper welcomes travellers and keeps a shop halfway."),
    "hint.innSoon": ("Une auberge s'élèvera sur cette route après {need} passages en 7 jours ({count} pour l'instant).",
                     "An inn will rise on this road after {need} passages in 7 days ({count} so far)."),
    "hint.noRoad": ("Pas de route balisée : les voyageurs contournent par la terre, {path} blocs de marche.",
                    "No marked road: travellers go round over land, {path} blocks on foot."),
    "hint.default": ("Chaque jour, des voyageurs peuvent partir vers ce village.", "Every day, travellers may set off for this village."),
    "ag.title": ("Ce qui vient", "What's coming"),
    "ag.none": ("Rien de prévu.", "Nothing planned."),
    "ag.now": ("Maintenant", "Now"),
    "ag.live": ("En cours", "Under way"),
    "ag.whenHour": ("{when}, {hour} h", "{when}, {hour}:00"),
    "ag.skirmish": ("Escarmouche contre {village}", "Skirmish against {village}"),
    "ag.raidNow": ("Raid au village !", "Raid on the village!"),
    "ag.raid": ("Raid attendu au village", "Raid expected on the village"),
    "ag.feast": ("Fête des moissons", "Harvest feast"),
    "ag.feasts": ("Fête des moissons dans {count} autre(s) village(s)", "Harvest feast in {count} other village(s)"),
    "ag.fair": ("Foire des peuples", "Peoples' fair"),
    "ag.herd": ("Un troupeau migre tout près", "A herd is migrating close by"),
    "ag.migration": ("Prochaine migration", "Next migration"),
    "ag.build": ("Bâtir", "Build"),
    "pl.here": ("ici", "here"),
    "pl.road": ("route {direction}", "road {direction}"),
    "pl.roadDist": ("route {direction} · {distance} blocs", "road {direction} · {distance} blocks"),
    "pl.village": ("{village} · {direction}", "{village} · {direction}"),
    "pl.far": ("{direction} · {distance} blocs", "{direction} · {distance} blocks"),
    "pl.random": ("village tiré au sort", "random village"),
    "pl.elsewhere": ("ailleurs", "elsewhere"),
    "rumour.title": ("Rumeur du jour", "Rumour of the day"),
}


def write_langs():
    for idx, locale in ((0, "fr-FR"), (1, "en-US")):
        table = {k: v[idx] for k, v in TEXTS.items()}
        path = RES / f"Server/Languages/{locale}/nakasar_peuples.lang"
        text = path.read_text(encoding="utf-8")
        keys = {"world.tab." + k: v for k, v in table.items()}
        block = "\n".join([LANG_BEGIN] + [f"{k} = {v}" for k, v in keys.items()] + [LANG_END]) + "\n"
        if LANG_BEGIN in text:
            before, rest = text.split(LANG_BEGIN, 1)
            after = rest.split(LANG_END, 1)[1].lstrip("\n")
            outside = before + after
            text = before + block + after
        else:
            outside = text
            text = text.rstrip("\n") + "\n" + block
        existing = {line.split("=", 1)[0].strip() for line in outside.splitlines() if "=" in line and not line.startswith("#")}
        dup = sorted(k for k in keys if k in existing)
        if dup:
            raise SystemExit(f"clés déjà présentes dans {locale} (hors bloc de l'onglet Monde) : {dup[:10]}")
        path.write_text(text, encoding="utf-8")
        print(f"{path.relative_to(ROOT)} : {len(keys)} clés de l'onglet Monde")


def main():
    textures()
    for b in RINGS:
        r = round(radius(b))
        ring_png(BOARD / f"World_Ring{b}.png", 2 * r)
    for theme_id in THEMES:
        name = f"WorldPage_{theme_id}.ui" if theme_id else "WorldPage.ui"
        (OUT / name).write_text(page(theme_id), encoding="utf-8")
        print("écrit", name)
    write_langs()


if __name__ == "__main__":
    main()
