#!/usr/bin/env python3
"""Icônes des onglets du panneau du village (Kevin, 17 novembre 2026 : « trop d'onglets, on se perd, les titres sont
coupés » → une icône par onglet, son nom en info-bulle).

    python3 tools/generate_tab_icons.py <dossier du jeu : …/install/release/package/game/latest>

Assets vanilla d'abord : la maison (Tableau), le livre (Projets) et le buste (Habitants) sont les icônes du client
(VANILLA, sous Client/Data/Game/Interface), passées en blanc et recadrées pour avoir la même taille et le même éclat que
les autres (les originales sont grises et le livre a une large marge : teintées telles quelles, elles paraissaient
éteintes). Le jeu n'a ni globe, ni balance, ni tour, ni couronne, ni mine : ces cinq-là sont dessinées ici, en
silhouettes blanches pleines comme les icônes vanilla. La pioche de l'entrée de mine est la silhouette de l'icône
vanilla Common/Icons/CraftingCategories/Workbench/Tools.png, lue dans Assets.zip. La page teinte chaque icône par la
couleur du PatchStyle (claire sur un onglet fermé, sombre sur l'aplat de l'onglet ouvert).

Sortie : src/main/resources/Common/UI/Custom/Pages/Nakasar/Peuples/Board/Tab_<Onglet>.png (64 px, affichées en 24 px).
"""
import io
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/Common/UI/Custom/Pages/Nakasar/Peuples/Board"
PICKAXE = "Common/Icons/CraftingCategories/Workbench/Tools.png"
INTERFACE = "Client/Data/Game/Interface"
# Onglet → icône du client ; detail : garder les nuances (lignes des pages du livre) plutôt qu'une silhouette pleine.
VANILLA = {
    "Board": ("InGame/Pages/MapIcons/ReturnToCrossroads@2x.png", False),   # maison
    "Plots": ("InGame/Hud/InputBindingIconBook@2x.png", True),             # livre
    "People": ("InGame/Pages/MapIcons/WorldOnly@2x.png", False),           # utilisateur
}

N = 512            # dessin sur 512 px, réduit à SIZE (anticrénelage)
SIZE = 64
W = (255, 255, 255, 255)
T = (0, 0, 0, 0)


def canvas():
    im = Image.new("RGBA", (N, N), T)
    return im, ImageDraw.Draw(im)


def globe():
    """Monde : la terre, méridiens et parallèles."""
    im, d = canvas()
    c, r, s = 256, 200, 34
    d.ellipse((c - r, c - r, c + r, c + r), outline=W, width=s)
    d.ellipse((c - 85, c - r, c + 85, c + r), outline=W, width=s)
    d.line((c, c - r, c, c + r), fill=W, width=s)
    d.line((c - r, c, c + r, c), fill=W, width=s)
    for dy in (-110, 110):
        hw = int((r * r - dy * dy) ** 0.5)
        d.line((c - hw + 10, c + dy, c + hw - 10, c + dy), fill=W, width=s - 6)
    return im


def balance():
    """Commerce : une balance à deux plateaux."""
    im, d = canvas()
    c = 256
    d.rectangle((c - 16, 95, c + 16, 420), fill=W)
    d.rounded_rectangle((c - 110, 410, c + 110, 450), 12, fill=W)
    d.polygon([(c - 60, 410), (c + 60, 410), (c + 20, 370), (c - 20, 370)], fill=W)
    d.ellipse((c - 34, 58, c + 34, 126), fill=W)
    d.rounded_rectangle((60, 118, 452, 148), 14, fill=W)
    for x in (110, 402):
        d.line((x, 140, x - 70, 300), fill=W, width=12)
        d.line((x, 140, x + 70, 300), fill=W, width=12)
        d.pieslice((x - 92, 210, x + 92, 380), 0, 180, fill=W)
    return im


def mine(pickaxe_png):
    """Production : une entrée de mine boisée dans la colline, la pioche vanilla devant."""
    im, d = canvas()
    d.pieslice((-10, 190, 440, 800), 180, 360, fill=W)
    d.rectangle((0, 470, N, N), fill=T)
    d.rectangle((110, 350, 300, 470), fill=T)
    d.pieslice((110, 280, 300, 420), 180, 360, fill=T)
    d.rectangle((126, 322, 156, 470), fill=W)
    d.rectangle((254, 322, 284, 470), fill=W)
    d.rectangle((108, 304, 302, 334), fill=W)
    alpha = Image.open(io.BytesIO(pickaxe_png)).convert("RGBA").split()[3].resize((300, 300), Image.BICUBIC)
    alpha = alpha.point(lambda v: 255 if v > 110 else 0).filter(ImageFilter.GaussianBlur(1.2))
    pick = Image.new("RGBA", (300, 300), W)
    pick.putalpha(alpha)
    layer = Image.new("RGBA", (N, N), T)
    layer.alpha_composite(pick, (N - 300 + 6, -4))
    # Un liseré vide autour de la pioche la détache de la colline.
    im.paste(T, (0, 0), layer.split()[3].filter(ImageFilter.MaxFilter(25)))
    im.alpha_composite(layer)
    return im


def tower():
    """Ouvrages : une tour de château fort crénelée."""
    im, d = canvas()
    d.rectangle((150, 190, 362, 470), fill=W)
    d.rectangle((120, 120, 392, 200), fill=W)
    d.rectangle((120, 60, 392, 130), fill=W)
    for x in (158, 236, 314):                           # trois créneaux entre quatre merlons
        d.rectangle((x, 60, x + 40, 122), fill=T)
    d.pieslice((206, 330, 306, 430), 180, 360, fill=T)
    d.rectangle((206, 380, 306, 470), fill=T)
    d.rectangle((240, 235, 272, 295), fill=T)
    return im


def crown():
    """Domaine : une couronne."""
    im, d = canvas()
    d.polygon([(70, 380), (70, 170), (170, 290), (256, 130), (342, 290), (442, 170), (442, 380)], fill=W)
    d.rounded_rectangle((70, 390, 442, 450), 10, fill=W)
    for x, y in ((70, 150), (256, 105), (442, 150)):
        d.ellipse((x - 32, y - 32, x + 32, y + 32), fill=W)
    for x in (160, 256, 352):
        d.ellipse((x - 22, 318, x + 22, 362), fill=T)
    return im


def whiten(path, detail, fill=0.86):
    """Icône vanilla en blanc, recadrée et centrée sur SIZE px (elle occupe fill du côté)."""
    im = Image.open(path).convert("RGBA")
    im = im.crop(im.getbbox())
    alpha = im.split()[3]
    if detail:
        lum = im.convert("L")
        lo, hi = lum.getextrema()
        shade = lum.point(lambda v: int(255 * (0.55 + 0.45 * (v - lo) / max(1, hi - lo))))
        alpha = ImageChops.multiply(alpha, shade)
    white = Image.new("RGBA", im.size, W)
    white.putalpha(alpha)
    side = int(SIZE * fill)
    r = side / max(im.size)
    white = white.resize((max(1, round(im.width * r)), max(1, round(im.height * r))), Image.LANCZOS)
    out = Image.new("RGBA", (SIZE, SIZE), T)
    out.alpha_composite(white, ((SIZE - white.width) // 2, (SIZE - white.height) // 2))
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    game = Path(sys.argv[1])
    with zipfile.ZipFile(game / "Assets.zip") as z:
        pickaxe = z.read(PICKAXE)
    OUT.mkdir(parents=True, exist_ok=True)
    for tab, (path, detail) in VANILLA.items():
        whiten(game / INTERFACE / path, detail).save(OUT / f"Tab_{tab}.png")
        print(f"Board/Tab_{tab}.png")
    for tab, im in (("World", globe()), ("Trade", balance()), ("Annexes", mine(pickaxe)), ("Works", tower()),
                    ("Domain", crown())):
        im.resize((SIZE, SIZE), Image.LANCZOS).save(OUT / f"Tab_{tab}.png")
        print(f"Board/Tab_{tab}.png")


if __name__ == "__main__":
    main()
