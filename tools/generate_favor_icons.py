#!/usr/bin/env python3
"""Jetons de faveur : une icône propre à chaque peuple (refonte des écrans de commerce, Kevin, 21 novembre 2026).

Les faveurs reprenaient des icônes vanilla (sève, fragment d'os, plumes, essence de glace, charbon, ferraille) : dans
l'échoppe, le prix « 2 sèves » se confondait avec la vraie sève vendue au même étal (le Voyageur kweebec en vend). Le jeu
n'a pas de pièce ni de jeton : ceux-ci sont dessinés ici, même gabarit pour tous (anneau doré, disque à la couleur du
peuple, symbole clair au centre) pour qu'un joueur reconnaisse « une monnaie » d'un coup d'œil. Le modèle 3D posé au sol
reste celui de l'objet vanilla (règle : assets vanilla d'abord).

    python3 tools/generate_favor_icons.py

Sortie : src/main/resources/Common/Icons/ItemsGenerated/Nakasar_Peuples_Favor_<Peuple>.png (64 px, comme les icônes
générées du jeu).
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/Common/Icons/ItemsGenerated"
N = 512
SIZE = 64
GOLD, GOLD_DARK = (232, 196, 106, 255), (150, 112, 44, 255)

# peuple → (fond du disque, couleur du symbole)
PEOPLES = {
    "Kweebec": ((47, 74, 38, 255), (158, 211, 106, 255)),
    "Trork": ((74, 34, 22, 255), (240, 226, 196, 255)),
    "Feran": ((74, 58, 23, 255), (236, 210, 154, 255)),
    "Renonce": ((23, 50, 70, 255), (191, 226, 238, 255)),
    "Slothian": ((43, 32, 67, 255), (214, 198, 240, 255)),
    "Klops": ((44, 48, 56, 255), (214, 221, 230, 255)),
}
C = N / 2


def leaf(d, col):
    # feuille en amande + nervure
    pts = []
    for i in range(61):
        t = i / 60 * math.pi
        pts.append((C + math.sin(t) * 78, C - 120 + i * 4))
    for i in range(61):
        t = i / 60 * math.pi
        pts.append((C - math.sin(t) * 78, C + 120 - i * 4))
    d.polygon(pts, fill=col)
    d.line([(C, C - 100), (C, C + 140)], fill=(47, 74, 38, 255), width=14)


def tooth(d, col):
    d.polygon([(C - 80, C - 110), (C + 80, C - 110), (C + 60, C - 20), (C + 10, C + 130), (C - 10, C + 130),
               (C - 60, C - 20)], fill=col)
    d.ellipse([C - 84, C - 140, C + 84, C - 70], fill=col)


def feather(d, col):
    pts = []
    for i in range(41):
        t = i / 40
        w = math.sin(t * math.pi) * 70
        pts.append((C - 60 + t * 120 + w * 0.7, C + 120 - t * 240 + w * 0.7))
    for i in range(40, -1, -1):
        t = i / 40
        w = math.sin(t * math.pi) * 70
        pts.append((C - 60 + t * 120 - w * 0.7, C + 120 - t * 240 - w * 0.7))
    d.polygon(pts, fill=col)
    d.line([(C - 90, C + 150), (C + 60, C - 120)], fill=(74, 58, 23, 255), width=12)


def tear(d, col):
    d.ellipse([C - 85, C - 30, C + 85, C + 140], fill=col)
    d.polygon([(C, C - 140), (C - 80, C + 30), (C + 80, C + 30)], fill=col)


def flame(d, col):
    d.ellipse([C - 80, C - 10, C + 80, C + 140], fill=col)
    d.polygon([(C - 78, C + 60), (C - 40, C - 70), (C - 10, C - 10), (C + 10, C - 140), (C + 78, C + 60)], fill=col)
    d.ellipse([C - 30, C + 50, C + 30, C + 120], fill=(43, 32, 67, 255))


def cog(d, col):
    for k in range(8):
        a = k * math.pi / 4
        ca, sa = math.cos(a), math.sin(a)
        w = 34
        pts = [(C + ca * 90 - sa * w, C + sa * 90 + ca * w), (C + ca * 140 - sa * w * .7, C + sa * 140 + ca * w * .7),
               (C + ca * 140 + sa * w * .7, C + sa * 140 - ca * w * .7), (C + ca * 90 + sa * w, C + sa * 90 - ca * w)]
        d.polygon(pts, fill=col)
    d.ellipse([C - 105, C - 105, C + 105, C + 105], fill=col)
    d.ellipse([C - 42, C - 42, C + 42, C + 42], fill=(44, 48, 56, 255))


SYMBOLS = {"Kweebec": leaf, "Trork": tooth, "Feran": feather, "Renonce": tear, "Slothian": flame, "Klops": cog}


def token(people):
    bg, fg = PEOPLES[people]
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([16, 16, N - 16, N - 16], fill=GOLD_DARK)
    d.ellipse([28, 28, N - 28, N - 28], fill=GOLD)
    d.ellipse([70, 70, N - 70, N - 70], fill=GOLD_DARK)
    d.ellipse([80, 80, N - 80, N - 80], fill=bg)
    SYMBOLS[people](d, fg)
    return im.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for p in PEOPLES:
        token(p).save(OUT / f"Nakasar_Peuples_Favor_{p}.png")
        print(f"Nakasar_Peuples_Favor_{p}.png")


if __name__ == "__main__":
    main()
