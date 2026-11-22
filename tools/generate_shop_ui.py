#!/usr/bin/env python3
"""Échoppe du mod : la page de troc qui remplace la page vanilla (refonte des écrans de commerce, Kevin, 21 novembre 2026).

    python3 tools/generate_shop_ui.py

La page vanilla (740 x 480, trois cartes par ligne, une seule entrée affichée, aucune idée du rang) ne se modifie pas :
reputation/ShopPage la remplace en gardant le stock vanilla (BarterShopState). Maquette « Commerce repensé », planche
Échoppe : le marché du village à gauche (l'échoppe du peuple et chaque bâtiment qui vend), les étals par rang ou par
niveau en onglets toujours visibles (fermés : grisés, avec la raison), le porte-monnaie en faveurs, une ligne par offre
(objet, stock, prix, quantité, bouton qui dit pourquoi il est grisé) et l'heure du prochain stock.

Écrit :
  Common/UI/Custom/Pages/Nakasar/Peuples/ShopPage_<Peuple>.ui, ShopPage.ui
  Server/Languages/{fr-FR,en-US}/nakasar_peuples.lang   bloc des textes de l'échoppe

Les ids sont ceux que remplit reputation/ShopPage.java (RAIL, TABS, ROWS).
"""
from pathlib import Path

from generate_board_ui import COMMON, MEMORIES, OUT, PANEL_WIDTH, THEMES, label

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"

LANG_BEGIN = "# ---- Échoppe (généré par tools/generate_shop_ui.py, ne pas modifier à la main) ----"
LANG_END = "# ---- Fin de l'échoppe ----"

RAIL = 8    # ShopPage.RAIL : étals du marché du village
TABS = 3    # ShopPage.TABS : rangs (Toléré, Ami, Hôte) ou niveaux (I, II, III)
ROWS = 7    # ShopPage.ROWS : offres (7 au plus dans les boutiques du mod)
HEIGHT = 720

FR = {}
EN = {}


def t(key, fr, en):
    if key in FR:
        raise SystemExit(f"clé en double : {key}")
    FR[key] = fr
    EN[key] = en


def page(theme_id):
    th = THEMES[theme_id]
    acc, band, title = th["accent"], th["band"], th["title"]
    emblem = (f'Background: (TexturePath: "{MEMORIES}{th["emblem"]}.png", Color: {acc});' if th["emblem"]
              else "Visible: false;")
    bar = f"Board/Bar_{theme_id}.png" if theme_id else "Board/Bar_Default.png"
    slot = f'Background: (TexturePath: "{COMMON}IngredientSlot.png", Border: 24);'
    L = []
    w = L.append
    w('$C = "../../../Common.ui";')
    w("")
    w(f"// Généré par tools/generate_shop_ui.py (thème {theme_id or 'neutre'}) : ne pas modifier à la main.")
    w("")
    w(f"@Section = LabelStyle(FontSize: 12, RenderBold: true, RenderUppercase: true, TextColor: #96a9be, VerticalAlignment: Center);")
    w("@Name = LabelStyle(FontSize: 14, RenderBold: true, TextColor: #e6edf3, VerticalAlignment: Center);")
    w("@Small = LabelStyle(FontSize: 12, TextColor: #96a9be, VerticalAlignment: Center);")
    w("@SmallRight = LabelStyle(FontSize: 12, TextColor: #96a9be, HorizontalAlignment: End, VerticalAlignment: Center);")
    w("")
    w("@Bar = ProgressBar {")
    w(f'  Background: "{COMMON}ProgressBar.png";')
    w(f'  BarTexturePath: "{bar}";')
    w("  Value: 0;")
    w("};")
    w("")
    w("$C.@PageOverlay {")
    w("  LayoutMode: Middle;")
    w("")
    w("  $C.@DecoratedContainer {")
    w(f"    Anchor: (Width: {PANEL_WIDTH}, Height: {HEIGHT});")
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
    w("      Padding: (Full: 14, Top: 10);")
    # Bandeau : étal choisi, rang, porte-monnaie.
    w("      Group {")
    w("        LayoutMode: Left;")
    w("        Anchor: (Height: 60, Bottom: 10);")
    w("        Padding: (Horizontal: 12);")
    w(f"        Background: (Color: {band}(0.7));")
    w("        Group {")
    w("          LayoutMode: Full;")
    w("          Anchor: (Width: 46, Height: 46, Top: 7, Right: 10);")
    w(f"          {slot}")
    w("          ItemIcon #HeadIcon { Anchor: (Full: 6); ShowItemTooltip: false; }")
    w("        }")
    w("        Group {")
    w("          LayoutMode: Top;")
    w("          Anchor: (Width: 300);")
    w(f'          {label("HeadName", f"LabelStyle(FontSize: 17, RenderBold: true, TextColor: {title}, VerticalAlignment: Center)", anchor="Height: 26, Top: 8")}')
    w(f'          {label("HeadSub", "@Small", anchor="Height: 18")}')
    w("        }")
    w("        Group #RankBox {")
    w("          LayoutMode: Top;")
    w("          Anchor: (Width: 260, Left: 14);")
    w(f'          {label("RankLine", "@Small", anchor="Height: 20, Top: 12")}')
    w("          @Bar #RankBar { Anchor: (Height: 8, Top: 4); }")
    w("        }")
    w("        Group { FlexWeight: 1; }")
    w("        Group #Wallet {")
    w("          LayoutMode: Left;")
    w("          Anchor: (Height: 40, Top: 10);")
    w("          Padding: (Horizontal: 10);")
    w("          Background: (Color: #000000(0.25));")
    w("          ItemIcon #WalletIcon { Anchor: (Width: 30, Height: 30, Top: 5, Right: 8); ShowItemTooltip: true; }")
    w(f'          {label("WalletCount", "LabelStyle(FontSize: 19, RenderBold: true, TextColor: #f2ead2, VerticalAlignment: Center)", anchor="Right: 6")}')
    w(f'          {label("WalletName", "@Small", anchor="")}')
    w("        }")
    w("      }")
    w("      Group {")
    w("        LayoutMode: Left;")
    w("        FlexWeight: 1;")
    # Marché du village.
    w("        Group #Rail {")
    w("          LayoutMode: Top;")
    w("          Anchor: (Width: 236, Right: 12);")
    w("          Padding: (Full: 8);")
    w(f"          Background: (Color: {band}(0.45));")
    w(f'          {label("RailHeader", "@Section", anchor="Height: 22, Bottom: 4")}')
    for i in range(RAIL):
        w(f"          Group #Stand{i} {{")
        w("            LayoutMode: Left;")
        w("            Anchor: (Height: 50, Bottom: 4);")
        w(f"            Group #StandOn{i} {{ Anchor: (Width: 4); Background: (Color: {acc}); Visible: false; }}")
        w(f"            Button #StandButton{i} {{")
        w("              FlexWeight: 1;")
        w("              Style: $C.@SecondaryButtonStyle;")
        w("              LayoutMode: Left;")
        w("              Padding: (Horizontal: 6);")
        w(f"              ItemIcon #StandIcon{i} {{ Anchor: (Width: 30, Height: 30, Top: 10, Right: 8); ShowItemTooltip: false; }}")
        w("              Group {")
        w("                LayoutMode: Top;")
        w("                FlexWeight: 1;")
        w(f'                {label(f"StandName{i}", "LabelStyle(FontSize: 13, RenderBold: true, TextColor: #e6edf3, VerticalAlignment: Center)", anchor="Height: 20, Top: 5")}')
        w(f'                {label(f"StandSub{i}", "LabelStyle(FontSize: 11, TextColor: #96a9be, VerticalAlignment: Center)", anchor="Height: 16")}')
        w("              }")
        w("            }")
        w("          }")
    w("        }")
    # Étals de l'étal choisi.
    w("        Group {")
    w("          LayoutMode: Top;")
    w("          FlexWeight: 1;")
    w("          Group {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 32, Bottom: 8);")
    for i in range(TABS):
        w(f"            Group #Tab{i} {{")
        w("              LayoutMode: Full;")
        w("              Anchor: (Width: 150, Height: 32, Right: 6);")
        w(f'              $C.@SmallSecondaryTextButton #TabButton{i} {{ @Text = ""; @Anchor = Anchor(Full: 0); }}')
        w(f"              Group #TabOn{i} {{")
        w("                LayoutMode: Full;")
        w("                Anchor: (Full: 0);")
        w(f"                Background: (Color: {acc});")
        w("                Visible: false;")
        w(f'                {label(f"TabOnLabel{i}", "LabelStyle(FontSize: 13, RenderBold: true, TextColor: #10180e, HorizontalAlignment: Center, VerticalAlignment: Center)", anchor="Full: 0")}')
        w("              }")
        w("            }")
    w("            Group { FlexWeight: 1; }")
    w(f'            {label("Restock", "@SmallRight", anchor="Width: 240")}')
    w("          }")
    w("          Group #Locked {")
    w("            LayoutMode: Left;")
    w("            Anchor: (Height: 34, Bottom: 8);")
    w("            Padding: (Horizontal: 12);")
    w("            Background: (Color: #2a2216);")
    w("            Visible: false;")
    w(f'            {label("LockedLine", "LabelStyle(FontSize: 13, TextColor: #f0d99a, VerticalAlignment: Center, Wrap: true)", extra=" FlexWeight: 1;")}')
    w("          }")
    w(f'          {label("Empty", "LabelStyle(FontSize: 14, TextColor: #96a9be, Wrap: true)", anchor="Height: 40", extra=" Visible: false;")}')
    for i in range(ROWS):
        w(f"          Group #Row{i} {{")
        w("            LayoutMode: Left;")
        w("            Anchor: (Height: 56, Bottom: 5);")
        w("            Padding: (Horizontal: 8);")
        w("            Background: (Color: #1a231d);")
        w("            Group {")
        w("              LayoutMode: Full;")
        w("              Anchor: (Width: 48, Height: 48, Top: 4, Right: 12);")
        w(f"              {slot}")
        w(f"              ItemIcon #RowIcon{i} {{ Anchor: (Full: 6); ShowItemTooltip: true; }}")
        w(f'              {label(f"RowQty{i}", "LabelStyle(FontSize: 13, RenderBold: true, TextColor: #ffffff, HorizontalAlignment: End, VerticalAlignment: End)", anchor="Right: 4, Bottom: 2, Width: 30, Height: 18")}')
        w("            }")
        w("            Group {")
        w("              LayoutMode: Top;")
        w("              FlexWeight: 1;")
        w(f'              {label(f"RowName{i}", "@Name", anchor="Height: 22, Top: 7")}')
        w(f'              {label(f"RowNote{i}", "@Small", anchor="Height: 18")}')
        w("            }")
        w(f'            {label(f"RowStock{i}", "@SmallRight", anchor="Width: 80, Right: 10")}')
        w(f'            {label(f"RowPrice{i}", "LabelStyle(FontSize: 17, RenderBold: true, TextColor: #f2ead2, HorizontalAlignment: End, VerticalAlignment: Center)", anchor="Width: 40, Right: 4")}')
        w(f"            ItemIcon #RowPriceIcon{i} {{ Anchor: (Width: 24, Height: 24, Top: 16, Right: 12); ShowItemTooltip: true; }}")
        w(f'            $C.@SmallSecondaryTextButton #RowLess{i} {{ @Text = "-"; @Anchor = Anchor(Width: 30, Height: 30, Top: 13); }}')
        w(f'            {label(f"RowCount{i}", "LabelStyle(FontSize: 14, RenderBold: true, TextColor: #e6edf3, HorizontalAlignment: Center, VerticalAlignment: Center)", anchor="Width: 34")}')
        w(f'            $C.@SmallSecondaryTextButton #RowMore{i} {{ @Text = "+"; @Anchor = Anchor(Width: 30, Height: 30, Top: 13, Right: 10); }}')
        w(f'            $C.@TextButton #RowTrade{i} {{ @Text = ""; @Anchor = Anchor(Width: 130, Height: 38, Top: 9); }}')
        w("          }")
    w("        }")
    w("      }")
    w("    }")
    w("  }")
    w("}")
    w("")
    w("$C.@BackButton {}")
    return "\n".join(L).replace("Style: LabelStyle(", "Style: (") + "\n"


def texts():
    K = "shop."
    t(K + "market", "Marché de {village}", "{village} market")
    t(K + "rail", "Marché du village", "Village market")
    t(K + "merchant", "Échoppe des {people}", "{people} stall")
    t(K + "merchantSub", "{open} étals ouverts sur {total}", "{open} of {total} stalls open")
    t(K + "workshopSub", "niveau {level} · {count} offres", "level {level} · {count} offers")
    t(K + "absent", "absent jusqu'à {hour} h", "away until {hour}:00")
    t(K + "stopped", "à l'arrêt", "stopped")
    t(K + "level", "Niveau {level}", "Level {level}")
    t(K + "locked", "{tab} · fermé", "{tab} · closed")
    t(K + "rankLine", "Tu es {rank} · {next} à {max}", "You are {rank} · {next} at {max}")
    t(K + "rankMax", "Tu es {rank} · {value} de réputation", "You are {rank} · {value} reputation")
    t(K + "restockDays", "nouveau stock dans {days} j", "new stock in {days} d")
    t(K + "restockHours", "nouveau stock dans {hours} h", "new stock in {hours} h")
    t(K + "why.rank", "Il faut être {rank} : il te manque {missing} de réputation. Les offres restent visibles pour savoir ce qui t'attend.",
      "You must be {rank}: {missing} reputation to go. The offers stay visible so you know what awaits.")
    t(K + "why.level", "Le bâtiment doit d'abord passer au niveau {level} (son chantier se lance depuis son panneau).",
      "The building must first reach level {level} (start its works from its board).")
    t(K + "why.absent", "{name} est absent jusqu'à {hour} h : l'étal rouvre à son retour.",
      "{name} is away until {hour}:00: the stall reopens when they return.")
    t(K + "why.stopped", "L'atelier est à l'arrêt : il manque {list} au dépôt du village ce matin.",
      "The workshop is stopped: {list} was missing from the village deposit this morning.")
    t(K + "why.specialty", "La spécialité manque de {list} : seul le niveau I reste ouvert.",
      "The specialty lacks {list}: only level I stays open.")
    t(K + "why.noSpecialty", "Le bâtiment n'a pas encore de spécialité : son niveau II vend comme le niveau I.",
      "The building has no specialty yet: its level II sells like level I.")
    t(K + "empty", "Rien à troquer ici pour l'instant.", "Nothing to barter here for now.")
    t(K + "stock", "reste {count}", "{count} left")
    t(K + "note.have", "tu en as {count}", "you have {count}")
    t(K + "note.out", "épuisé · revient avec le nouveau stock", "sold out · back with the new stock")
    t(K + "note.poor", "il te manque {count}", "you lack {count}")
    t(K + "button.trade", "Troquer", "Barter")
    t(K + "button.out", "Épuisé", "Sold out")
    t(K + "button.poor", "Trop cher", "Too dear")
    t(K + "button.locked", "Fermé", "Closed")
    t(K + "done", "Troqué : {count} {item}.", "Bartered: {count} {item}.")
    t(K + "log", "{player} a troqué {count} {item} contre {price} {currency}", "{player} bartered {count} {item} for {price} {currency}")
    t(K + "offers.open", "ouvert", "open")
    t(K + "offers.rank", "il faut être {rank}", "needs {rank}")
    t(K + "offers.building", "bâtiment au niveau {level}", "building at level {level}")
    t(K + "offers.rankNote", "rang {rank}", "rank {rank}")
    t(K + "offers.more", "et {count} autres", "and {count} more")
    t(K + "offers.noSpecialty", "spécialité à choisir", "specialty to pick")
    t("dialogue.hint.shop.market", "ouvre l'échoppe : un étal par rang, les fermés restent visibles",
      "opens the stall: one stand per rank, closed ones stay visible")
    t(K + "failed", "Le troc n'a pas pu se faire (stock ou inventaire).", "The barter failed (stock or inventory).")


def write_langs():
    for locale, table in (("fr-FR", FR), ("en-US", EN)):
        path = RES / f"Server/Languages/{locale}/nakasar_peuples.lang"
        text = path.read_text(encoding="utf-8")
        block = "\n".join([LANG_BEGIN] + [f"{k} = {v}" for k, v in table.items()] + [LANG_END]) + "\n"
        if LANG_BEGIN in text:
            before, rest = text.split(LANG_BEGIN, 1)
            after = rest.split(LANG_END, 1)[1].lstrip("\n")
            outside = before + after
            text = before + block + after
        else:
            outside = text
            text = text.rstrip("\n") + "\n" + block
        existing = {line.split("=", 1)[0].strip() for line in outside.splitlines() if "=" in line and not line.startswith("#")}
        dup = sorted(k for k in table if k in existing)
        if dup:
            raise SystemExit(f"clés déjà présentes dans {locale} (hors bloc de l'échoppe) : {dup[:10]}")
        path.write_text(text, encoding="utf-8")
        print(f"{path.relative_to(ROOT)} : {len(table)} clés de l'échoppe")


def main():
    for theme_id in THEMES:
        name = f"ShopPage_{theme_id}.ui" if theme_id else "ShopPage.ui"
        (OUT / name).write_text(page(theme_id), encoding="utf-8")
        print("écrit", name)
    texts()
    write_langs()


if __name__ == "__main__":
    main()
