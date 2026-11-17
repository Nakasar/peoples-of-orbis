#!/usr/bin/env python3
"""Paliers d'apparence des bâtiments EN JEU (Kevin, 17 novembre 2026).

« Quand un village change de palier, ses bâtiments propres changent d'apparence pour le palier correspondant. Les
bâtiments qui ont leurs propres niveaux ne changent d'apparence que lors de leur changement de niveau propre. »

Les paliers 1 à 4 de chaque bâtiment viennent des échelles de tools/building_tiers.py (carte de revue « /village
revue » : version plus grande de la même famille vanilla, règle n° 2 d'AGENTS.md). La carte de revue pose chaque palier
seul dans son rectangle ; ici, chaque palier doit TENIR DANS LE VILLAGE, à la place du bâtiment :

- bâtiments du plan (VillageLayouts) : même point de collage et même rotation ; l'emprise agrandie ne touche ni
  l'emprise (tous paliers) d'un autre bâtiment, ni un terrain E1-E3, ni la place, ni un panneau de bâtiment de métier,
  et reste dans le rayon du plan ; un bâtiment de projet reste dans son terrain. Sinon : la plus grande version qui
  tient (au pire celle du palier d'avant) ;
- bâtiments d'avant-poste (production, ouvrages) : chaque pièce garde sa place et sa rotation, reste dans le
  rectangle de l'avant-poste (Annex.Rect), sans chevaucher une autre pièce ni la case du panneau.

Le Java (village/BuildingPaliers) lit ce fichier ; le palier montré se décide en jeu :
- bâtiment propre (pas de bâtiment de métier) : palier du village + 1 (campement 1 ... bourg 4) ;
- bâtiment à niveaux (métier, projet de terrain, production, ouvrage) : d'après son niveau (BuildingPaliers.LEVEL_PALIER).

Sortie : src/main/resources/nakasar-peuples/paliers-batiments.json

    python3 tools/building_growth.py
"""
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import building_tiers as bt  # noqa: E402
import generate_village_layouts as gvl  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
NAK = ROOT / "src/main/resources/Server/Nakasar"
OUT = ROOT / "src/main/resources/nakasar-peuples/paliers-batiments.json"
PALIERS = (1, 2, 3, 4)
# Décalages du point de collage essayés pour qu'une version plus grande tienne (Kevin : « alignement ») : le plus petit
# d'abord, au plus MAX_SHIFT blocs ; le bâtiment garde sa rotation (entrée tournée du même côté).
MAX_SHIFT = 8
# Avant-postes : chaque pièce bouge de moins, dans le rectangle de l'avant-poste.
ANNEX_SHIFT = 4
OFFSETS = sorted(((dx, dz) for dx in range(-MAX_SHIFT, MAX_SHIFT + 1) for dz in range(-MAX_SHIFT, MAX_SHIFT + 1)),
                 key=lambda o: (abs(o[0]) + abs(o[1]), abs(o[0]), o))


def overlaps(a, b, margin=1):
    return not (a[1] + margin <= b[0] or b[1] + margin <= a[0] or a[3] + margin <= b[2] or b[3] + margin <= a[2])


def union(a, b):
    return [min(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), max(a[3], b[3])]


def contains(outer, r, inset=0):
    return r[0] >= outer[0] + inset and r[1] <= outer[1] - inset and r[2] >= outer[2] + inset and r[3] <= outer[3] - inset


def bounds(path):
    return bt.BOUNDS.get(path)


def rect_of(path, x, z, rot):
    b = bounds(path)
    return None if b is None else list(gvl.footprint(b, x, z, rot))


def steps_of(path):
    """Échelle d'un prefab : lui, puis ses versions plus grandes (celles qu'on sait mesurer)."""
    return [path] + [p for p in bt.ladder(path) if p in bt.BOUNDS]


def wanted(steps, palier):
    """Indice voulu au palier (même règle que la carte de revue : palier 1 = le prefab, puis un cran par palier)."""
    return min(palier - 1, len(steps) - 1)


def layout_entries(layout, workshops):
    people = layout["People"]
    plaza = layout["PlazaRadius"] + 1
    plaza_rect = [-plaza, plaza, -plaza, plaza]
    plots = {p["Id"]: [p["X"] - p["Size"] // 2, p["X"] + p["Size"] // 2 - 1, p["Z"] - p["Size"] // 2,
                       p["Z"] + p["Size"] // 2 - 1] for p in layout.get("Plots", [])}
    boards = []
    for w in workshops.values():
        if w.get("People") == people and w.get("Building") and w.get("Board") and not w.get("Annex") \
                and not w.get("Hamlet"):
            boards.append((w["Building"], w["Board"][0], w["Board"][1]))
    base = {b["Id"]: [b["X"] + b["MinX"], b["X"] + b["MaxX"], b["Z"] + b["MinZ"], b["Z"] + b["MaxZ"]]
            for b in layout["Buildings"]}
    reserved = dict(base)  # emprise réservée par bâtiment (union de ses paliers choisis)
    radius = layout["Radius"]
    out = {}
    report = {"grown": 0, "blocked": 0}
    for b in layout["Buildings"]:
        bid = b["Id"]
        steps = steps_of(b["Prefab"])
        chosen = []
        cx = (base[bid][0] + base[bid][1]) / 2
        cz = (base[bid][2] + base[bid][3]) / 2
        last = (0, 0, 0)

        def fits(r):
            if overlaps(r, plaza_rect, 0):
                return False
            if b.get("Plot"):
                if not contains(plots[b["Plot"]], r, 1):
                    return False
            elif any(overlaps(r, pr) for pr in plots.values()):
                return False
            # Les projets d'un même terrain se partagent son emprise (un seul y est bâti).
            if any(overlaps(r, o) for k, o in reserved.items() if k != bid and not (
                    b.get("Plot") and k.startswith(b["Plot"] + "_"))):
                return False
            if any(r[0] <= x <= r[1] and r[2] <= z <= r[3] for _, x, z in boards):
                return False
            # Le bâtiment reste à sa place : sa nouvelle emprise couvre le centre de l'ancienne.
            if not (r[0] <= cx <= r[1] and r[2] <= cz <= r[3]):
                return False
            return max(math.hypot(a, c) for a in r[:2] for c in r[2:]) <= radius

        for palier in PALIERS:
            want = wanted(steps, palier)
            pick = last
            for i in range(want, last[0], -1):
                done = False
                for dx, dz in OFFSETS:
                    r = rect_of(steps[i], b["X"] + dx, b["Z"] + dz, b["Rotation"])
                    if r is not None and fits(r):
                        pick = (i, dx, dz)
                        done = True
                        break
                if done:
                    break
            if pick[0] < want:
                report["blocked"] += 1
            if pick[0] > 0:
                report["grown"] += 1
            last = pick
            path = steps[pick[0]]
            bb = bounds(path)
            ax, az = b["X"] + pick[1], b["Z"] + pick[2]
            r = rect_of(path, ax, az, b["Rotation"])
            reserved[bid] = union(reserved[bid], r)
            chosen.append({"Palier": palier, "Prefab": path, "DX": pick[1], "DZ": pick[2], "MinX": r[0] - ax,
                           "MaxX": r[1] - ax, "MinZ": r[2] - az, "MaxZ": r[3] - az, "MinY": bb[2], "MaxY": bb[3]})
        if any(c["Prefab"] != b["Prefab"] for c in chosen):
            out[bid] = chosen
    return out, report


def annex_entries(workshops):
    out = {}
    report = {"grown": 0, "blocked": 0}
    for wid, w in sorted(workshops.items()):
        a = w.get("Annex")
        if not a:
            continue
        frame = a["Rect"]
        pieces = []
        for line in a["Prefabs"]:
            p = line.split()
            pieces.append([p[0], int(p[1]), int(p[2]), int(p[3]) if len(p) > 3 else 0])
        ladders = [steps_of(p[0]) for p in pieces]
        centres = []
        for path, x, z, rot in pieces:
            r0 = rect_of(path, x, z, rot)
            centres.append(((r0[0] + r0[1]) / 2, (r0[2] + r0[3]) / 2) if r0 else (x, z))
        current = [(0, 0, 0)] * len(pieces)

        def piece_rect(j, choice):
            path, x, z, rot = pieces[j]
            return rect_of(ladders[j][choice[0]], x + choice[1], z + choice[2], rot)

        tiers = []
        for palier in PALIERS:
            for i in range(len(pieces)):
                steps = ladders[i]
                want = wanted(steps, palier)
                pick = current[i]
                others = [piece_rect(j, current[j]) for j in range(len(pieces)) if j != i]
                for k in range(want, current[i][0], -1):
                    found = None
                    for dx, dz in OFFSETS:
                        if abs(dx) > ANNEX_SHIFT or abs(dz) > ANNEX_SHIFT:
                            continue
                        r = piece_rect(i, (k, dx, dz))
                        if r is None or not contains(frame, r):
                            continue
                        if r[0] - 1 <= 0 <= r[1] + 1 and r[2] - 1 <= 0 <= r[3] + 1:
                            continue  # panneau de l'avant-poste en (0, 0)
                        if not (r[0] <= centres[i][0] <= r[1] and r[2] <= centres[i][1] <= r[3]):
                            continue
                        if any(o and overlaps(r, o) for o in others):
                            continue
                        found = (k, dx, dz)
                        break
                    if found:
                        pick = found
                        break
                if pick[0] < want:
                    report["blocked"] += 1
                current[i] = pick
            lines = [f"{ladders[i][c[0]]} {pieces[i][1] + c[1]} {pieces[i][2] + c[2]} {pieces[i][3]}"
                     for i, c in enumerate(current)]
            report["grown"] += sum(1 for c in current if c[0] > 0)
            tiers.append({"Palier": palier, "Prefabs": lines})
        if any(t["Prefabs"] != tiers[0]["Prefabs"] for t in tiers):
            out[wid] = tiers
    return out, report


def main():
    workshops = {}
    for f in sorted((NAK / "Workshops").glob("*.json")):
        workshops[f.stem] = json.loads(f.read_text(encoding="utf-8"))
    doc = {
        "$Comment": "Paliers d'apparence des bâtiments en jeu, générés par tools/building_growth.py (ne pas modifier à "
                    "la main) ; lus par dev.nakasar.hytale.peuples.village.BuildingPaliers. Layouts : plan -> bâtiment "
                    "-> paliers 1 à 4 (prefab et emprise relative au point de collage, après rotation). Annexes : "
                    "bâtiment d'avant-poste -> paliers 1 à 4 (pièces « prefab dx dz rotation »). Un bâtiment sans "
                    "version plus grande qui tienne n'est pas listé.",
        "Layouts": {},
        "Annexes": {},
    }
    total = {"grown": 0, "blocked": 0}
    for f in sorted((NAK / "VillageLayouts").glob("*.json")):
        layout = json.loads(f.read_text(encoding="utf-8"))
        entries, rep = layout_entries(layout, workshops)
        doc["Layouts"][f.stem] = entries
        print(f"{f.stem}: {len(entries)}/{len(layout['Buildings'])} bâtiments changent d'apparence "
              f"({rep['blocked']} paliers retenus faute de place)")
        for k in total:
            total[k] += rep[k]
    annexes, rep = annex_entries(workshops)
    doc["Annexes"] = annexes
    print(f"avant-postes : {len(annexes)} changent d'apparence ({rep['blocked']} paliers de pièce retenus faute de place)")
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"→ {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
