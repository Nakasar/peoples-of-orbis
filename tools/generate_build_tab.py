#!/usr/bin/env python3
"""Onglet « Bâtir » du panneau du village (Kevin, 21 novembre 2026 : ranger les bâtiments par usage et non plus par
lieu ; maquette https://claude.ai/artifact/1qPRAdQgED7Qq9TZJAVHQs validée « on implémente »).

Un seul onglet remplace Emplacements, Avant-postes et Ouvrages (ces pages restent, ouvertes depuis Bâtir) :

  gauche    les sept familles (Récolte et élevage, Artisanat, Commerce, Diplomatie, Militaire, Savoir et culte,
            Habitations), avec le nombre de bâtiments debout ;
  milieu    filtre « Où » (Village, Emplacement, Avant-poste, Ouvrage, Lieu-dit), filtre « Je cherche » (ce que le
            bâtiment produit), puis huit cartes (2 × 4) et la page suivante ;
  droite    le détail de la carte choisie : lieu, rôle, produits, coût (dépôt / voulu), conditions, bouton d'action.

    python3 tools/generate_build_tab.py

Écrit :
  Common/UI/Custom/Pages/Nakasar/Peuples/BuildPage_<Peuple>.ui, BuildPage.ui   (dev.nakasar.hytale.peuples.batir.BuildPage)
  Common/UI/Custom/Pages/Nakasar/Peuples/Board/Place_<Lieu>.png               pictos des cinq lieux
  Server/Languages/{fr-FR,en-US}/nakasar_peuples.lang                         bloc des textes de l'onglet

Assets vanilla d'abord : produits, coûts et familles sont des icônes d'objets vanilla (ItemIcon). Aucun objet ne dit
« dans le village » ou « hors du village » : les cinq pictos de lieu sont dessinés ici (maison sur un cercle de village,
terrain en pointillés, drapeau d'avant-poste, tour d'ouvrage, hameau à part), comme les icônes d'onglet.
Les noms des bâtiments du plan sans métier (maisons, tours) reprennent building.<Id> sans article (« l'abri ouest »
→ « Abri ouest ») : build.name.<Id>.
"""
import json
import re
from pathlib import Path

from PIL import Image, ImageDraw

from generate_board_ui import COMMON, MEMORIES, OUT, PANEL_HEIGHT, PANEL_WIDTH, THEMES, label, tab_strip

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
RES = ROOT / "src/main/resources"

LANG_BEGIN = "# ---- Onglet Bâtir (généré par tools/generate_build_tab.py, ne pas modifier à la main) ----"
LANG_END = "# ---- Fin de l'onglet Bâtir ----"

# Nombres de cases : dev.nakasar.hytale.peuples.batir.BuildPage les reprend.
FAMILIES = 7
PLACES = ("Village", "Plot", "Outpost", "Work", "Hamlet")   # BuildCatalog.Place, dans l'ordre du filtre
SEEKS = 6
CARDS = 8
CARD_OUTS = 4
CARD_COSTS = 3
DET_OUTS = 6
DET_MATS = 4
DET_REQS = 4

RAIL = 178
DETAIL = 318
GAP = 12
CARD_HEIGHT = 118
# Couleurs du liseré des cartes, dans l'ordre de BuildCatalog.Stripe.
STRIPES = {"built": "#7fc36a", "open": "#e8c46a", "working": "#d9a35a", "locked": "#e07468", "hamlet": "#7fa8d8", "legacy": "#6b7785"}

# Couleurs des pictos de lieu (celles de la maquette).
GOLD = (232, 196, 106, 255)
GREY = (151, 167, 184, 255)
AMBER = (217, 163, 90, 255)
RED = (224, 116, 104, 255)
BLUE = (127, 168, 216, 255)
FILL = (34, 50, 74, 255)
FILL_BLUE = (26, 42, 60, 255)


# ---- Pictos de lieu (dessinés sur 24 unités, rendus en 64 px) ----

def _house(d, x, y, s, c, k):
    d.polygon([((x) * k, (y + s * .45) * k), ((x + s / 2) * k, y * k), ((x + s) * k, (y + s * .45) * k),
               ((x + s) * k, (y + s) * k), (x * k, (y + s) * k)], fill=c)


def place_png(kind, path, size=64):
    k = size * 8 / 24
    n = int(24 * k)
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def circle(cx, cy, r, fill, outline, width):
        d.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), fill=fill, outline=outline, width=int(width * k))

    if kind == "Village":          # maison au milieu du cercle du village
        circle(12, 12, 10.6, FILL, GOLD, 1.7)
        _house(d, 6.5, 5.5, 11, GOLD, k)
    elif kind == "Plot":           # cercle du village, petite maison, terrain en pointillés
        circle(12, 12, 10.6, FILL, GOLD, 1.7)
        _house(d, 4.5, 4.5, 6.5, GREY, k)
        x0, y0, x1, y1 = 10.2, 10.2, 18.6, 17.6
        d.rectangle((x0 * k, y0 * k, x1 * k, y1 * k), fill=(232, 196, 106, 70))
        step = 2.1
        for i in range(int((x1 - x0) / step) + 1):          # bords en tirets
            a = x0 + i * step
            b = min(a + 1.3, x1)
            for yy in (y0, y1):
                d.rectangle((a * k, (yy - .7) * k, b * k, (yy + .7) * k), fill=GOLD)
        for i in range(int((y1 - y0) / step) + 1):
            a = y0 + i * step
            b = min(a + 1.3, y1)
            for xx in (x0, x1):
                d.rectangle(((xx - .7) * k, a * k, (xx + .7) * k, b * k), fill=GOLD)
    elif kind == "Outpost":        # petit village, chemin, drapeau dehors
        circle(7, 16, 6.2, FILL, GREY, 1.4)
        _house(d, 4.3, 12.8, 5.4, GREY, k)
        for i in range(3):
            a = 12.6 + i * 1.6
            d.line((a * k, (12.4 - i * 1.7) * k, (a + 0.9) * k, (11.4 - i * 1.7) * k), fill=AMBER, width=int(1.5 * k))
        d.rectangle((17.6 * k, 1.8 * k, 19.2 * k, 11 * k), fill=AMBER)
        d.polygon([(19.2 * k, 1.8 * k), (23.6 * k, 3.8 * k), (19.2 * k, 5.8 * k)], fill=AMBER)
    elif kind == "Work":           # petit village, chemin, tour crénelée dehors
        circle(7, 16, 6.2, FILL, GREY, 1.4)
        _house(d, 4.3, 12.8, 5.4, GREY, k)
        d.line((12.6 * k, 12.4 * k, 15 * k, 10 * k), fill=RED, width=int(1.5 * k))
        d.rectangle((15.6 * k, 6.4 * k, 22.6 * k, 22.6 * k), fill=RED)
        for x in (15.2, 17.9, 20.6):
            d.rectangle((x * k, 3 * k, (x + 2) * k, 6.6 * k), fill=RED)
        d.rectangle((18.2 * k, 10 * k, 20.2 * k, 13.2 * k), fill=FILL)
    elif kind == "Hamlet":         # village gris à gauche, hameau bleu à part
        circle(6, 17.5, 4.6, FILL, GREY, 1.2)
        circle(16.2, 8.6, 7, FILL_BLUE, BLUE, 1.6)
        _house(d, 11.6, 5.2, 4.6, BLUE, k)
        _house(d, 16.2, 7.6, 4.6, BLUE, k)
    im.resize((size, size), Image.LANCZOS).save(path)


# ---- Mise en page ----

def btn_open(w, i, id_, anchor, layout="Left", padding="Padding: (Horizontal: 6);"):
    w(f"{i}Button #{id_} {{")
    w(f"{i}  Anchor: ({anchor});")
    w(f"{i}  Style: $C.@SecondaryButtonStyle;")
    w(f"{i}  LayoutMode: {layout};")
    if padding:
        w(f"{i}  {padding}")


def page(theme_id):
    t = THEMES[theme_id]
    acc, band, title = t["accent"], t["band"], t["title"]
    emblem = (f'Background: (TexturePath: "{MEMORIES}{t["emblem"]}.png", Color: {acc});' if t["emblem"]
              else "Visible: false;")
    slot = f'Background: (TexturePath: "{COMMON}IngredientSlot.png", Border: 24);'
    slot_ok = f'Background: (TexturePath: "{COMMON}IngredientSlotValid.png", Border: 24);'
    L = []
    w = L.append
    w('$C = "../../../Common.ui";')
    w("")
    w(f"// Généré par tools/generate_build_tab.py (thème {theme_id or 'neutre'}) : ne pas modifier à la main.")
    w("")
    w(f"@Accent = {acc};")
    w("@Body = LabelStyle(FontSize: 13, TextColor: #c9d1d9, Wrap: true);")
    w("@Small = LabelStyle(FontSize: 12, TextColor: #96a9be);")
    w("@SmallWrap = LabelStyle(FontSize: 12, TextColor: #96a9be, Wrap: true);")
    w("@Head = LabelStyle(FontSize: 12, RenderBold: true, RenderUppercase: true, TextColor: #96a9be);")
    w("@Count = LabelStyle(FontSize: 12, RenderBold: true, TextColor: #e8c46a, HorizontalAlignment: Center, VerticalAlignment: Center);")
    w("@Qty = LabelStyle(FontSize: 12, RenderBold: true, TextColor: #d9e2ec, VerticalAlignment: Center);")
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
    tab_strip(w, acc, "Build")
    w("      Group {")
    w("        LayoutMode: Left;")
    w("        FlexWeight: 1;")

    # Familles
    w("        Group #Rail {")
    w("          LayoutMode: Top;")
    w(f"          Anchor: (Width: {RAIL}, Right: {GAP});")
    for f in range(FAMILIES):
        btn_open(w, "          ", f"Fam{f}", "Height: 58, Bottom: 6", padding="Padding: (Left: 4, Right: 6);")
        w(f"            Group #FamOn{f} {{ Anchor: (Width: 4, Right: 6); Background: (Color: {acc}); Visible: false; }}")
        w("            Group {")
        w("              LayoutMode: Full;")
        w("              Anchor: (Width: 38, Height: 38, Right: 8);")
        w(f"              ItemIcon #FamIcon{f} {{ Anchor: (Full: 2); ShowItemTooltip: false; }}")
        w("            }")
        w("            Group {")
        w("              LayoutMode: Middle;")
        w("              FlexWeight: 1;")
        w(f'              {label(f"FamName{f}", "LabelStyle(FontSize: 14, RenderBold: true, TextColor: #d9e2ec, Wrap: true)")}')
        w(f'              {label(f"FamCount{f}", "@Small", anchor="Height: 16")}')
        w("            }")
        w("          }")
    w("        }")

    # Milieu : filtres, cartes, pages
    w("        Group #Middle {")
    w("          LayoutMode: Top;")
    w("          FlexWeight: 1;")
    w(f"          Anchor: (Right: {GAP});")
    w("          Group {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 32, Bottom: 6);")
    w(f'            {label("WhereLabel", "@Head", anchor="Width: 76")}')
    for p in range(len(PLACES)):
        btn_open(w, "            ", f"Where{p}", "Width: 92, Height: 32, Right: 4", padding="Padding: (Horizontal: 4);")
        w("              Group {")
        w("                LayoutMode: Top;")
        w("                FlexWeight: 1;")
        w("                Group {")
        w("                  LayoutMode: Left;")
        w("                  FlexWeight: 1;")
        w(f'                  Group {{ Anchor: (Width: 22, Height: 22, Right: 4); Background: (TexturePath: "Board/Place_{PLACES[p]}.png"); }}')
        w(f'                  {label(f"WhereName{p}", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #d9e2ec, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
        w("                }")
        w(f"                Group #WhereOn{p} {{ Anchor: (Height: 2); Background: (Color: {acc}); Visible: false; }}")
        w("              }")
        w("            }")
    w("          }")
    w("          Group {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 32, Bottom: 10);")
    w(f'            {label("SeekLabel", "@Head", anchor="Width: 76")}')
    for s in range(SEEKS):
        btn_open(w, "            ", f"Seek{s}", "Width: 74, Height: 32, Right: 3", padding="Padding: (Horizontal: 3);")
        w("              Group {")
        w("                LayoutMode: Top;")
        w("                FlexWeight: 1;")
        w("                Group {")
        w("                  LayoutMode: Left;")
        w("                  FlexWeight: 1;")
        w(f"                  ItemIcon #SeekIcon{s} {{ Anchor: (Width: 22, Height: 22, Right: 3); ShowItemTooltip: false; }}")
        w(f'                  {label(f"SeekName{s}", "LabelStyle(FontSize: 11, RenderBold: true, TextColor: #d9e2ec, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
        w("                }")
        w(f"                Group #SeekOn{s} {{ Anchor: (Height: 2); Background: (Color: {acc}); Visible: false; }}")
        w("              }")
        w("            }")
    w("          }")
    rows = CARDS // 2
    for r in range(rows):
        w("          Group {")
        w("            LayoutMode: Left;")
        w(f"            Anchor: (Height: {CARD_HEIGHT}, Bottom: 8);")
        for c in range(2):
            i = r * 2 + c
            btn_open(w, "            ", f"Card{i}", f"Right: {8 if c == 0 else 0}", padding="Padding: (Right: 8);")
            w("              FlexWeight: 1;")
            # Liseré d'état : un aplat par couleur, Java n'en montre qu'un (pas de couleur de fond posée à chaud).
            w("              Group {")
            w("                LayoutMode: Full;")
            w("                Anchor: (Width: 4, Right: 8);")
            for t, colour in enumerate(STRIPES.values()):
                w(f"                Group #CardStripe{i * len(STRIPES) + t} {{ Anchor: (Full: 0); Background: (Color: {colour}); Visible: false; }}")
            w("              }")
            w("              Group {")
            w("                LayoutMode: Full;")
            w("                Anchor: (Width: 46, Height: 46, Right: 8, Bottom: 52);")
            w(f"                {slot}")
            w(f"                ItemIcon #CardIcon{i} {{ Anchor: (Full: 5); ShowItemTooltip: false; }}")
            w("              }")
            w("              Group {")
            w("                LayoutMode: Top;")
            w("                FlexWeight: 1;")
            w("                Padding: (Vertical: 7);")
            # Nom et état sur la même ligne : l'état ne déborde plus de la carte (Kevin, capture du 2026-11-21).
            w("                Group {")
            w("                  LayoutMode: Left;")
            w("                  Anchor: (Height: 20);")
            w(f'                  {label(f"CardName{i}", f"LabelStyle(FontSize: 14, RenderBold: true, TextColor: #d9e2ec, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
            w(f'                  {label(f"CardState{i}", "LabelStyle(FontSize: 11, RenderBold: true, RenderUppercase: true, TextColor: #e8c46a, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 92")}')
            w("                }")
            w("                Group {")
            w("                  LayoutMode: Left;")
            w("                  Anchor: (Height: 30, Top: 2);")
            for j in range(CARD_OUTS):
                k = i * CARD_OUTS + j
                w(f"                  Group #CardOut{k} {{")
                w("                    LayoutMode: Full;")
                w("                    Anchor: (Width: 30, Height: 30, Right: 3);")
                w(f"                    {slot}")
                w(f"                    ItemIcon #CardOutIcon{k} {{ Anchor: (Full: 3); ShowItemTooltip: false; }}")
                w("                  }")
            w(f'                  {label(f"CardFx{i}", "LabelStyle(FontSize: 12, RenderBold: true, TextColor: #7fa8d8, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
            w("                }")
            w("                Group {")
            w("                  LayoutMode: Left;")
            w("                  Anchor: (Height: 22, Top: 4);")
            w("                  Group {")
            w("                    LayoutMode: Full;")
            w("                    Anchor: (Width: 22, Height: 22, Right: 5);")
            for p, place in enumerate(PLACES):
                w(f'                    Group #CardPlace{i * len(PLACES) + p} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Place_{place}.png"); Visible: false; }}')
            w("                  }")
            w(f'                  {label(f"CardWhere{i}", "LabelStyle(FontSize: 12, RenderBold: true, TextColor: #c9d1d9, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
            w("                }")
            w("                Group {")
            w("                  LayoutMode: Left;")
            w("                  Anchor: (Height: 20, Top: 3);")
            for j in range(CARD_COSTS):
                k = i * CARD_COSTS + j
                w(f"                  Group #CardCost{k} {{")
                w("                    LayoutMode: Left;")
                w("                    Anchor: (Width: 48);")
                w(f"                    ItemIcon #CardCostIcon{k} {{ Anchor: (Width: 20, Height: 20, Right: 2); ShowItemTooltip: false; }}")
                w(f'                    {label(f"CardCostCount{k}", "@Qty", extra=" FlexWeight: 1;")}')
                w("                  }")
            w(f'                  {label(f"CardNote{i}", "LabelStyle(FontSize: 11, TextColor: #96a9be, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
            w("                }")
            w("              }")
            w("            }")
        w("          }")
    w("          Group { FlexWeight: 1; }")
    w("          Group {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 30);")
    w('            $C.@SmallSecondaryTextButton #PagePrev { @Text = ""; @Anchor = Anchor(Width: 110, Height: 30); }')
    w(f'            {label("PageInfo", "LabelStyle(FontSize: 12, TextColor: #96a9be, HorizontalAlignment: Center, VerticalAlignment: Center)", extra=" FlexWeight: 1;")}')
    w('            $C.@SmallSecondaryTextButton #PageNext { @Text = ""; @Anchor = Anchor(Width: 110, Height: 30); }')
    w("          }")
    w("        }")

    # Détail
    w("        Group #Detail {")
    w("          LayoutMode: Top;")
    w(f"          Anchor: (Width: {DETAIL});")
    w("          Padding: (Full: 14);")
    w(f"          Background: (Color: {band}(0.55));")
    w(f'          {label("DetName", f"LabelStyle(FontSize: 18, FontName: \"Secondary\", RenderBold: true, TextColor: {acc}, Wrap: true)")}')
    w("          Group {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 28, Top: 6);")
    w("            Group {")
    w("              LayoutMode: Full;")
    w("              Anchor: (Width: 28, Height: 28, Right: 8);")
    for p, place in enumerate(PLACES):
        w(f'              Group #DetPlace{p} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Place_{place}.png"); Visible: false; }}')
    w("            }")
    w(f'            {label("DetWhere", "LabelStyle(FontSize: 13, RenderBold: true, TextColor: #d9e2ec, VerticalAlignment: Center, Wrap: true)", extra=" FlexWeight: 1;")}')
    w("          }")
    w(f'          {label("DetLine", "@Body", anchor="Top: 8")}')
    w(f'          {label("DetOutHead", "@Head", anchor="Height: 18, Top: 10")}')
    w("          Group #DetOuts {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 42, Top: 4);")
    for k in range(DET_OUTS):
        w(f"            Group #DetOut{k} {{")
        w("              LayoutMode: Full;")
        w("              Anchor: (Width: 42, Height: 42, Right: 4);")
        w(f"              {slot}")
        w(f"              ItemIcon #DetOutIcon{k} {{ Anchor: (Full: 4); ShowItemTooltip: true; }}")
        w("            }")
    w("          }")
    w(f'          {label("DetCostHead", "@Head", anchor="Height: 18, Top: 10")}')
    w("          Group #DetMats {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 66, Top: 4);")
    for k in range(DET_MATS):
        w(f"            Group #DetMat{k} {{")
        w("              LayoutMode: Top;")
        w("              Anchor: (Width: 60, Right: 8);")
        w("              Group {")
        w("                LayoutMode: Full;")
        w("                Anchor: (Width: 46, Height: 46, Left: 7);")
        w(f"                Group #DetMatSlot{k} {{ Anchor: (Full: 0); {slot} }}")
        w(f"                Group #DetMatOk{k} {{ Anchor: (Full: 0); {slot_ok} Visible: false; }}")
        w(f"                ItemIcon #DetMatIcon{k} {{ Anchor: (Full: 5); ShowItemTooltip: true; }}")
        w("              }")
        w(f'              Label #DetMatCount{k} {{ Anchor: (Height: 18, Top: 2); Style: @Count; Text: ""; }}')
        w("            }")
    w("          }")
    w(f'          {label("DetCostNote", "@SmallWrap", anchor="Top: 4")}')
    w("          Group {")
    w("            LayoutMode: Top;")
    w("            Anchor: (Top: 10);")
    for k in range(DET_REQS):
        w(f"            Group #DetReq{k} {{")
        w("              LayoutMode: Left;")
        w("              Anchor: (Bottom: 5);")
        w("              Group {")
        w("                LayoutMode: Full;")
        w("                Anchor: (Width: 14, Height: 14, Right: 8, Top: 2);")
        w(f'                Group #DetReqOk{k} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: #7fc36a); }}')
        w(f'                Group #DetReqNo{k} {{ Anchor: (Full: 0); Background: (TexturePath: "Board/Dot.png", Color: #e07468); Visible: false; }}')
        w("              }")
        w(f'              {label(f"DetReqText{k}", "@Body", extra=" FlexWeight: 1;")}')
        w("            }")
    w("          }")
    w("          Group { FlexWeight: 1; }")
    w('          $C.@SmallSecondaryTextButton #DetAction { @Text = ""; @Anchor = Anchor(Height: 36); }')
    w("        }")
    w("      }")
    w("    }")
    w("  }")
    w("}")
    w("")
    w("$C.@BackButton {}")
    return "\n".join(L).replace("Style: LabelStyle(", "Style: (") + "\n"


# ---- Textes ----

FR = {
    "build.tab": "Bâtir",
    "build.title": "Bâtir : {village}",
    "build.where": "Où",
    "build.seek": "Je cherche",
    "build.family.Recolte": "Récolte et élevage",
    "build.family.Artisanat": "Artisanat",
    "build.family.Commerce": "Commerce",
    "build.family.Diplomatie": "Diplomatie",
    "build.family.Militaire": "Militaire",
    "build.family.Savoir": "Savoir et culte",
    "build.family.Habitations": "Habitations",
    "build.familyCount": "{built}/{total} debout",
    "build.place.Village": "Village",
    "build.place.Plot": "Emplacement",
    "build.place.Outpost": "Avant-poste",
    "build.place.Work": "Ouvrage",
    "build.place.Hamlet": "Lieu-dit",
    "build.placeLong.Village": "Village, bâti avec le palier",
    "build.placeLong.Plot": "Emplacement du village",
    "build.placeLong.Outpost": "Avant-poste, hors du village",
    "build.placeLong.Work": "Ouvrage, hors du village",
    "build.placeLong.Hamlet": "Lieu-dit, à rallier",
    "build.cardWhere.Village": "Village · {tier}",
    "build.cardWhere.Plot": "Village · emplacement",
    "build.cardWhere.Outpost": "Avant-poste",
    "build.cardWhere.Work": "Ouvrage · {limit} max",
    "build.cardWhere.Hamlet": "Lieu-dit",
    "build.note.tier": "avec le palier",
    "build.note.hamlet": "pris, pas bâti",
    "build.seek.Wood": "Bois",
    "build.seek.Stone": "Pierre",
    "build.seek.Food": "Vivres",
    "build.seek.Leather": "Cuir",
    "build.seek.Arms": "Armes",
    "build.seek.Potions": "Potions",
    "build.state.built": "Debout",
    "build.state.level": "Niveau {level}",
    "build.state.open": "Disponible",
    "build.state.locked": "Verrouillé",
    "build.state.working": "Chantier",
    "build.state.chosen": "Choisi",
    "build.state.granted": "Fondation",
    "build.state.hamlet": "Lieu-dit",
    "build.state.count": "{count}/{limit}",
    "build.state.legacy": "Ancien",
    "build.page": "Page {page} / {pages} · {count} bâtiments",
    "build.pagePrev": "Précédent",
    "build.pageNext": "Suivant",
    "build.empty": "Aucun bâtiment ici.",
    "build.produces": "Produit",
    "build.brings": "Apporte",
    "build.toBuild": "Pour bâtir",
    "build.howTo": "Comment l'obtenir",
    "build.line.specialties": "Spécialités : {list}.",
    "build.line.home": "Loge {count} villageois.",
    "build.line.decor": "Décor et vie du village.",
    "build.line.hamlet": "Ne se bâtit pas : le village le gagne en ralliant ou en prenant un lieu-dit (onglet Domaine).",
    "build.costNote.tier": "Bâti par le chantier du palier, avec les autres bâtiments du palier.",
    "build.costNote.plot": "{hours} h de chantier sur un emplacement libre.",
    "build.costNote.annex": "{hours} h de chantier : fondation à poser entre {min} et {max} blocs du cœur.",
    "build.req.tier": "Village au palier {tier}",
    "build.req.rank": "Rang {rank} au moins",
    "build.req.plot": "Un emplacement libre",
    "build.req.unique": "Pas déjà sur un autre emplacement",
    "build.req.category": "Catégorie pas encore pleine",
    "build.req.resources": "Ressources : {list}",
    "build.req.resourcesOk": "Ressources du projet livrées",
    "build.req.limit": "{count}/{limit} déjà fondés",
    "build.req.domain": "Rallier ou prendre le lieu-dit",
    "build.action.choose": "Choisir ce projet",
    "build.action.plots": "Voir les emplacements",
    "build.action.grant": "Recevoir la fondation",
    "build.action.regive": "Reprendre la fondation",
    "build.action.outposts": "Voir les avant-postes",
    "build.action.works": "Voir les ouvrages",
    "build.action.domain": "Voir le Domaine",
    "build.chosen": "{building} choisi sur l'emplacement {plot} : lance le chantier quand le dépôt a tout.",
    "build.noPlot": "Aucun emplacement libre pour ce projet.",
    "build.fx.Tour": "Raids vus +30 s/niveau",
    "build.fx.Poste": "Routes sans embuscade",
    "build.fx.Fortin": "Territoire +96 blocs",
    "build.fx.Forteresse": "Palier jamais perdu",
    "build.fx.Caserne": "Plus de gardes",
    "build.fx.Conseil": "Ambassades et relations",
    "build.fx.Bibliotheque": "Cartes et maîtres",
    "build.fx.Scriptorium": "Cartes et maîtres",
    "build.fx.Temple": "Protection, bénédiction",
    "build.fx.Sanctuaire": "Garde contre le Vide",
    "build.fx.Halle": "Plus d'échanges",
    "build.fx.Comptoir": "Comptoir de route",
    "build.fx.Hamlet_Caserne": "Garde du lieu-dit",
    "build.fx.Watch": "Guetteur du village",
    "build.fx.Stall": "Marchand du village",
    "build.fx.Home": "Loge des villageois",
    "build.fx.Decor": "Vie du village",
}
EN = {
    "build.tab": "Build",
    "build.title": "Build: {village}",
    "build.where": "Where",
    "build.seek": "I need",
    "build.family.Recolte": "Gathering and herding",
    "build.family.Artisanat": "Crafts",
    "build.family.Commerce": "Trade",
    "build.family.Diplomatie": "Diplomacy",
    "build.family.Militaire": "Military",
    "build.family.Savoir": "Lore and faith",
    "build.family.Habitations": "Homes",
    "build.familyCount": "{built}/{total} standing",
    "build.place.Village": "Village",
    "build.place.Plot": "Plot",
    "build.place.Outpost": "Outpost",
    "build.place.Work": "Stronghold",
    "build.place.Hamlet": "Hamlet",
    "build.placeLong.Village": "Village, built with the tier",
    "build.placeLong.Plot": "Village plot",
    "build.placeLong.Outpost": "Outpost, outside the village",
    "build.placeLong.Work": "Stronghold, outside the village",
    "build.placeLong.Hamlet": "Hamlet, to be rallied",
    "build.cardWhere.Village": "Village · {tier}",
    "build.cardWhere.Plot": "Village · plot",
    "build.cardWhere.Outpost": "Outpost",
    "build.cardWhere.Work": "Stronghold · {limit} max",
    "build.cardWhere.Hamlet": "Hamlet",
    "build.note.tier": "with the tier",
    "build.note.hamlet": "taken, not built",
    "build.seek.Wood": "Wood",
    "build.seek.Stone": "Stone",
    "build.seek.Food": "Food",
    "build.seek.Leather": "Leather",
    "build.seek.Arms": "Arms",
    "build.seek.Potions": "Potions",
    "build.state.built": "Standing",
    "build.state.level": "Level {level}",
    "build.state.open": "Available",
    "build.state.locked": "Locked",
    "build.state.working": "Building",
    "build.state.chosen": "Chosen",
    "build.state.granted": "Foundation",
    "build.state.hamlet": "Hamlet",
    "build.state.count": "{count}/{limit}",
    "build.state.legacy": "Legacy",
    "build.page": "Page {page} / {pages} · {count} buildings",
    "build.pagePrev": "Previous",
    "build.pageNext": "Next",
    "build.empty": "No building here.",
    "build.produces": "Produces",
    "build.brings": "Brings",
    "build.toBuild": "To build",
    "build.howTo": "How to get it",
    "build.line.specialties": "Specialties: {list}.",
    "build.line.home": "Houses {count} villagers.",
    "build.line.decor": "Village life and decor.",
    "build.line.hamlet": "Not built: the village gains it by rallying or taking a hamlet (Domain tab).",
    "build.costNote.tier": "Built by the tier works, with the tier's other buildings.",
    "build.costNote.plot": "{hours} h of works on a free plot.",
    "build.costNote.annex": "{hours} h of works: foundation placed {min} to {max} blocks from the heart.",
    "build.req.tier": "Village at tier {tier}",
    "build.req.rank": "Rank {rank} or better",
    "build.req.plot": "A free plot",
    "build.req.unique": "Not already on another plot",
    "build.req.category": "Category not full yet",
    "build.req.resources": "Resources: {list}",
    "build.req.resourcesOk": "Project resources delivered",
    "build.req.limit": "{count}/{limit} already founded",
    "build.req.domain": "Rally or take the hamlet",
    "build.action.choose": "Choose this project",
    "build.action.plots": "See the plots",
    "build.action.grant": "Receive the foundation",
    "build.action.regive": "Take the foundation again",
    "build.action.outposts": "See the outposts",
    "build.action.works": "See the strongholds",
    "build.action.domain": "See the Domain",
    "build.chosen": "{building} chosen on plot {plot}: start the works once the deposit has everything.",
    "build.noPlot": "No free plot for this project.",
    "build.fx.Tour": "Raids seen +30 s/level",
    "build.fx.Poste": "Roads without ambush",
    "build.fx.Fortin": "Territory +96 blocks",
    "build.fx.Forteresse": "Tier never lost",
    "build.fx.Caserne": "More guards",
    "build.fx.Conseil": "Embassies, relations",
    "build.fx.Bibliotheque": "Maps and masters",
    "build.fx.Scriptorium": "Maps and masters",
    "build.fx.Temple": "Protection, blessing",
    "build.fx.Sanctuaire": "Ward against the Void",
    "build.fx.Halle": "More trade",
    "build.fx.Comptoir": "Road trading post",
    "build.fx.Hamlet_Caserne": "Hamlet guard",
    "build.fx.Watch": "Village lookout",
    "build.fx.Stall": "Village merchant",
    "build.fx.Home": "Houses villagers",
    "build.fx.Decor": "Village life",
}

ARTICLE = re.compile(r"^(l'|la |le |les |the )", re.I)


def plain_names():
    """build.name.<Id> : nom sans article des bâtiments du plan (maisons, tours…), pour les cartes."""
    ids = set()
    for p in (RES / "Server/Nakasar/VillageLayouts").glob("*.json"):
        for b in json.loads(p.read_text(encoding="utf-8"))["Buildings"]:
            if not b.get("Plot"):
                ids.add(b["Id"])
    out = {}
    for locale in ("fr-FR", "en-US"):
        values = {}
        for line in (RES / f"Server/Languages/{locale}/nakasar_peuples.lang").read_text(encoding="utf-8").splitlines():
            if line.startswith("building.") and "=" in line:
                k, v = line.split("=", 1)
                values[k.strip()[len("building."):]] = v.strip()
        table = {}
        for i in sorted(ids):
            v = ARTICLE.sub("", values.get(i, i.replace("_", " ")))
            table[f"build.name.{i}"] = v[:1].upper() + v[1:]
        out[locale] = table
    return out


def write_langs():
    names = plain_names()
    for locale, base in (("fr-FR", FR), ("en-US", EN)):
        table = dict(base)
        table.update(names[locale])
        path = RES / f"Server/Languages/{locale}/nakasar_peuples.lang"
        text = path.read_text(encoding="utf-8")
        block = "\n".join([LANG_BEGIN] + [f"{k} = {v}" for k, v in table.items()] + [LANG_END]) + "\n"
        if LANG_BEGIN in text:
            before, rest = text.split(LANG_BEGIN, 1)
            after = rest.split(LANG_END, 1)[1].lstrip("\n")
            outside = before + after
            text = before + block + after
        else:
            # Bloc ajouté à la fin (et laissé à sa place ensuite) : le reste du fichier ne bouge pas.
            outside = text
            text = text.rstrip("\n") + "\n" + block
        existing = {line.split("=", 1)[0].strip() for line in outside.splitlines() if "=" in line and not line.startswith("#")}
        dup = sorted(k for k in table if k in existing)
        if dup:
            raise SystemExit(f"clés déjà présentes dans {locale} (hors bloc de l'onglet Bâtir) : {dup[:10]}")
        path.write_text(text, encoding="utf-8")
        print(f"{path.relative_to(ROOT)} : {len(table)} clés de l'onglet Bâtir")


def main():
    for place in PLACES:
        place_png(place, OUT / "Board" / f"Place_{place}.png")
        print(f"écrit Board/Place_{place}.png")
    for theme_id in THEMES:
        name = f"BuildPage_{theme_id}.ui" if theme_id else "BuildPage.ui"
        (OUT / name).write_text(page(theme_id), encoding="utf-8")
        print("écrit", name)
    write_langs()


if __name__ == "__main__":
    main()
