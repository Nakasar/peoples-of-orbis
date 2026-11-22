#!/usr/bin/env python3
"""Icônes des onglets du panneau du village (Kevin, 17 novembre 2026 : « trop d'onglets, on se perd, les titres sont
coupés » → une icône par onglet, son nom en info-bulle).

    python3 tools/generate_tab_icons.py <dossier du jeu : …/install/release/package/game/latest>

Assets vanilla d'abord : la maison (Tableau) et le buste (Habitants) sont les icônes du client (VANILLA, sous
Client/Data/Game/Interface), passées en blanc et recadrées pour avoir la même taille et le même éclat que les autres (les
originales sont grises : teintées telles quelles, elles paraissaient éteintes). Le jeu n'a ni globe, ni balance, ni
marteau, ni couronne : ces quatre-là sont dessinées ici, en silhouettes blanches pleines comme les icônes vanilla. Le
marteau est l'onglet « Bâtir » (Kevin, 21 novembre 2026 : un seul onglet pour les emplacements, avant-postes et
ouvrages). La page teinte chaque icône par la couleur du PatchStyle (claire sur un onglet fermé, sombre sur l'aplat de
l'onglet ouvert).

Sortie : src/main/resources/Common/UI/Custom/Pages/Nakasar/Peuples/Board/Tab_<Onglet>.png (64 px, affichées en 24 px).
"""
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/Common/UI/Custom/Pages/Nakasar/Peuples/Board"
INTERFACE = "Client/Data/Game/Interface"
# Onglet → icône du client ; detail : garder les nuances (lignes des pages du livre) plutôt qu'une silhouette pleine.
VANILLA = {
    "Board": ("InGame/Pages/MapIcons/ReturnToCrossroads@2x.png", False),   # maison
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


def hammer():
    """Bâtir : un marteau de charpentier penché (le jeu n'a pas d'icône de marteau hors des objets)."""
    im, d = canvas()
    head = Image.new("RGBA", (N, N), T)
    h = ImageDraw.Draw(head)
    h.rounded_rectangle((232, 150, 280, 450), 16, fill=W)              # manche
    h.rounded_rectangle((150, 90, 380, 180), 18, fill=W)               # tête
    h.polygon([(150, 100), (82, 82), (96, 132), (150, 170)], fill=W)   # panne fendue
    h.polygon([(84, 108), (130, 116), (130, 126), (90, 120)], fill=T)
    im.alpha_composite(head.rotate(-35, resample=Image.BICUBIC, center=(256, 270)))
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
    OUT.mkdir(parents=True, exist_ok=True)
    for tab, (path, detail) in VANILLA.items():
        whiten(game / INTERFACE / path, detail).save(OUT / f"Tab_{tab}.png")
        print(f"Board/Tab_{tab}.png")
    for tab, im in (("World", globe()), ("Trade", balance()), ("Build", hammer()), ("Domain", crown())):
        im.resize((SIZE, SIZE), Image.LANCZOS).save(OUT / f"Tab_{tab}.png")
        print(f"Board/Tab_{tab}.png")


if __name__ == "__main__":
    main()
