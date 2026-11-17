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
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import building_tiers as bt  # noqa: E402
import generate_village_layouts as gvl  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
NAK = ROOT / "src/main/resources/Server/Nakasar"
OUT = ROOT / "src/main/resources/nakasar-peuples/paliers-batiments.json"
PALIERS = (1, 2, 3, 4)
# Paliers que montrent les bâtiments à niveaux (BuildingPaliers.LEVEL_PALIER : I, II, III).
LEVEL_PALIERS = (1, 3, 4)
# Décalages du point de collage essayés pour qu'une version plus grande tienne (Kevin : « alignement ») : le plus petit
# d'abord, au plus MAX_SHIFT blocs ; le bâtiment garde sa rotation (entrée tournée du même côté).
MAX_SHIFT = 8
# Avant-postes : chaque pièce bouge de moins, dans le rectangle de l'avant-poste.
ANNEX_SHIFT = 4
# Panneaux placés à la main (Kevin, 17 novembre 2026 : « le placement à la main du panneau va consister à le déplacer
# physiquement à la main sur la carte de revue ») : relevé de la carte (/village revue --relever, et avant chaque
# écrasement du monde), fichier écrit par le serveur dans nakasar-revue/panneaux-releves.json à côté de ses mondes et
# copié ici. Place relative au point de collage du prefab non tourné (repère de la carte), hauteur comprise ; remplace la
# place calculée, en jeu (tournée avec le bâtiment) et sur la carte.
MANUAL_FILE = ROOT / "tools/panneaux-releves.json"


def manual_boards():
    """(peuple, Id de la carte, palier) -> (dx, dz, lacet, dy)."""
    if not MANUAL_FILE.exists():
        return {}
    out = {}
    for c in json.loads(MANUAL_FILE.read_text(encoding="utf-8")).get("Boards", []):
        out[(c["People"], c["Id"], c["Tier"])] = (c["DX"], c["DZ"], c["Yaw"] & 3, c["DY"])
    return out


MANUAL = manual_boards()
RETOUCHES = bt.retouches()  # paliers retouchés à la main sur la carte de revue (tools/retouches-prefabs.json)
OFFSETS = sorted(((dx, dz) for dx in range(-MAX_SHIFT, MAX_SHIFT + 1) for dz in range(-MAX_SHIFT, MAX_SHIFT + 1)),
                 key=lambda o: (abs(o[0]) + abs(o[1]), abs(o[0]), o))


def rotate(x, z, rotation):
    """Comme Annexes.rotate / PrefabRotation du serveur : un quart de tour donne x' = z, z' = -x."""
    for _ in range(rotation & 3):
        x, z = z, -x
    return x, z


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


def reachable(layout, b, workshops, palier):
    """Vrai si le bâtiment peut montrer ce palier en jeu (BuildingPaliers : village + 1, ou niveau I/II/III -> 1/3/4)."""
    mine = [w for w in workshops.values() if w.get("Building") == b["Id"] and w.get("People") == layout["People"]
            and not w.get("Annex") and not w.get("Hamlet")]
    if mine and all(w.get("Legacy") for w in mine):
        return False  # projet hérité, plus construit
    leveled = bool(mine)
    if leveled:
        return palier in LEVEL_PALIERS
    return palier >= b["Tier"] + 1


# Agrandissement maximal de la place libre tracée sur la carte de revue, par côté.
ZONE_GROWTH = 24


def layout_entries(layout, workshops, layout_id, incompatible, review, zones):
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
    moved = []  # places des panneaux déplacés avec leur bâtiment (id, x, z)
    radius = layout["Radius"]
    out = {}
    report = {"grown": 0, "blocked": 0, "front": 0, "inside": 0}
    rects = {}  # emprise retenue par bâtiment et par palier (monde, relative au cœur)
    for b in layout["Buildings"]:
        bid = b["Id"]
        steps = steps_of(b["Prefab"])
        row_id = "Projet_" + bid[3:] if re.match(r"E\d_", bid) else bid
        if any((people, row_id, p) in RETOUCHES for p in PALIERS):
            # Un cran par palier, le prefab retouché à la place de la version vanilla là où il y en a un.
            steps = [RETOUCHES.get((people, row_id, p)) or steps[wanted(steps, p)] for p in PALIERS]
        chosen = []
        cx = (base[bid][0] + base[bid][1]) / 2
        cz = (base[bid][2] + base[bid][3]) / 2
        last = (0, 0, 0)
        own = next(((x, z) for k, x, z in boards if k == bid), None)
        own_yaw = next((w["Board"][2] if len(w["Board"]) > 2 else 0 for w in workshops.values()
                        if w.get("People") == people and w.get("Building") == bid and w.get("Board")
                        and not w.get("Annex") and not w.get("Hamlet")), 0)

        def rival(k):
            """Autre projet du même terrain : jamais bâti en même temps que celui-ci (son panneau n'y est pas)."""
            return bool(b.get("Plot")) and k != bid and k.startswith(b["Plot"] + "_")

        def why(r):
            """Raisons pour lesquelles l'emprise `r` (sans décalage) ne tient pas, en clair."""
            out = []
            if overlaps(r, plaza_rect, 0):
                out.append("touche la place")
            if b.get("Plot"):
                if not contains(plots[b["Plot"]], r, 1):
                    out.append(f"déborde du terrain {b['Plot']}")
            else:
                out += [f"empiète sur le terrain {k}" for k, pr in plots.items() if overlaps(r, pr)]
            out += [f"touche {k}" for k, o in reserved.items() if k != bid and overlaps(r, o) and not (
                b.get("Plot") and k.startswith(b["Plot"] + "_"))]
            covered = {}
            for k, x, z in boards + moved:
                if r[0] <= x <= r[1] and r[2] <= z <= r[3] and k != bid and not rival(k):
                    covered.setdefault((x, z), []).append(k)
            for keys in covered.values():
                out.append("couvre le panneau de " + ", ".join(dict.fromkeys(keys)))
            far = max(math.hypot(a, c) for a in r[:2] for c in r[2:])
            if far > radius:
                out.append(f"sort du rayon du village ({far:.0f} > {radius})")
            return out

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
            # Son propre panneau suit le bâtiment (board_for) ; ceux des voisins restent où ils sont.
            if any(r[0] <= x <= r[1] and r[2] <= z <= r[3] for k, x, z in boards + moved if k != bid and not rival(k)):
                return False
            # Le bâtiment reste à sa place : sa nouvelle emprise couvre le centre de l'ancienne.
            if not (r[0] <= cx <= r[1] and r[2] <= cz <= r[3]):
                return False
            return max(math.hypot(a, c) for a in r[:2] for c in r[2:]) <= radius

        def free(x, z):
            """Case où poser le panneau devant la façade : ni sur la place, ni chez un voisin, ni sur son panneau."""
            if -plaza <= x <= plaza and -plaza <= z <= plaza:
                return False
            if b.get("Plot") and not contains(plots[b["Plot"]], [x, x, z, z]):
                return False
            if not b.get("Plot") and any(contains(pr, [x, x, z, z]) for pr in plots.values()):
                return False
            if any(o[0] <= x <= o[1] and o[2] <= z <= o[3] for k, o in reserved.items() if k != bid and not rival(k)):
                return False
            if any(abs(x - bx) + abs(z - bz) <= 1 for k, bx, bz in boards + moved if k != bid and not rival(k)):
                return False
            return math.hypot(x, z) <= radius

        def board_for(r):
            """
            Panneau du bâtiment de métier au palier d'emprise `r` (Kevin, 17 novembre 2026 : « déplace le panneau de
            manière logique sur le bâtiment ou à l'intérieur ») : même façade, même distance au mur et même décalage
            depuis le milieu de la façade que sur le plan ; si cette case est prise, juste à l'intérieur de l'emprise
            (en jeu, Workshops.placeBoard entre alors dans le bâtiment jusqu'à une case libre).
            """
            ox, oz = own
            r0 = base[bid]
            yaw = own_yaw & 3
            if yaw in (0, 2):
                lateral = ox - (r0[0] + r0[1]) / 2
                x = max(r[0], min(r[1], round((r[0] + r[1]) / 2 + lateral)))
                d = oz - r0[3] if yaw == 0 else r0[2] - oz
                z = r[3] + d if yaw == 0 else r[2] - d
                inside = (x, r[3] if yaw == 0 else r[2])
                spot = (x, z)
            else:
                lateral = oz - (r0[2] + r0[3]) / 2
                z = max(r[2], min(r[3], round((r[2] + r[3]) / 2 + lateral)))
                d = ox - r0[1] if yaw == 1 else r0[0] - ox
                x = r[1] + d if yaw == 1 else r[0] - d
                inside = (r[1] if yaw == 1 else r[0], z)
                spot = (x, z)
            if free(*spot):
                return [spot[0], spot[1], yaw], False
            return [inside[0], inside[1], yaw], True

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
                if reachable(layout, b, workshops, palier):
                    r = rect_of(steps[want], b["X"], b["Z"], b["Rotation"])
                    reasons = why(r) or ["ne tient nulle part à moins de %d blocs" % MAX_SHIFT]
                    incompatible.append({"People": people, "Layout": layout_id, "Id": bid, "Palier": palier,
                                         "Voulu": steps[want].rsplit("/", 1)[-1],
                                         "Montre": steps[pick[0]].rsplit("/", 1)[-1],
                                         "Raison": " ; ".join(dict.fromkeys(reasons))})
            if pick[0] > 0:
                report["grown"] += 1
            last = pick
            path = steps[pick[0]]
            bb = bounds(path)
            ax, az = b["X"] + pick[1], b["Z"] + pick[2]
            r = rect_of(path, ax, az, b["Rotation"])
            rects.setdefault(bid, {})[palier] = r
            reserved[bid] = union(reserved[bid], r)
            entry = {"Palier": palier, "Prefab": path, "DX": pick[1], "DZ": pick[2], "MinX": r[0] - ax,
                     "MaxX": r[1] - ax, "MinZ": r[2] - az, "MaxZ": r[3] - az, "MinY": bb[2], "MaxY": bb[3]}
            manual = MANUAL.get((people, row_id, palier))
            if own is not None and manual is not None:
                mx, mz = rotate(manual[0], manual[1], b["Rotation"])
                spot, inside = [ax + mx, az + mz, (manual[2] + b["Rotation"]) & 3, manual[3]], False
                entry["Board"] = spot
                entry["Manual"] = True
                moved.append((bid, spot[0], spot[1]))
            elif own is not None and pick != (0, 0, 0):
                spot, inside = board_for(r)
                entry["Board"] = spot
                if inside:
                    entry["Inside"] = True
                    report["inside"] += 1
                else:
                    report["front"] += 1
                moved.append((bid, spot[0], spot[1]))
            chosen.append(entry)
            if own is not None:
                # Carte de revue : le panneau de la version qu'elle montre (voulue), relatif à son point de collage.
                # La carte montre le prefab sans rotation : on ramène le panneau dans le repère du prefab.
                if manual is not None:
                    rel = [manual[0], manual[1], manual[2]]
                else:
                    if pick[0] == want:
                        at = entry.get("Board") or [own[0], own[1], own_yaw & 3]
                        wx, wz = at[0] - ax, at[1] - az
                    else:
                        at, _ = board_for(rect_of(steps[want], b["X"], b["Z"], b["Rotation"]))
                        wx, wz = at[0] - b["X"], at[1] - b["Z"]
                    lx, lz = rotate(wx, wz, -b["Rotation"])
                    rel = [lx, lz, (at[2] - b["Rotation"]) & 3]
                review.setdefault(bid, {})[str(palier)] = rel + [steps[want]] + ([manual[3]] if manual else [])
        if any(c["Prefab"] != b["Prefab"] or c.get("Manual") for c in chosen):
            out[bid] = chosen

    # Place libre de chaque bâtiment à chaque palier (Kevin, 17 novembre 2026 : tracée sur la carte de revue, une
    # retouche qui y tient est valide en jeu) : son emprise retenue, agrandie côté par côté tant qu'elle ne touche ni la
    # place, ni un terrain (ou reste dans le sien), ni l'emprise réservée d'un voisin, ni le panneau d'un voisin, et
    # reste dans le rayon. Deux voisins peuvent voir le même espace libre entre eux : une retouche des deux qui s'y
    # rejoint est refusée au calcul suivant (liseré bleu).
    for b in layout["Buildings"]:
        bid = b["Id"]
        same_plot = (lambda k, b=b, bid=bid: bool(b.get("Plot")) and k != bid and k.startswith(b["Plot"] + "_"))

        def ok(r, b=b, bid=bid, same_plot=same_plot):
            if overlaps(r, plaza_rect, 0):
                return False
            if b.get("Plot"):
                if not contains(plots[b["Plot"]], r, 1):
                    return False
            elif any(overlaps(r, pr) for pr in plots.values()):
                return False
            if any(overlaps(r, o) for k, o in reserved.items() if k != bid and not same_plot(k)):
                return False
            if any(r[0] <= x <= r[1] and r[2] <= z <= r[3] for k, x, z in boards + moved
                   if k != bid and not same_plot(k)):
                return False
            return max(math.hypot(a, c) for a in r[:2] for c in r[2:]) <= radius

        for palier, r in rects.get(bid, {}).items():
            z = list(r)
            for _ in range(ZONE_GROWTH):
                grew = False
                for side, step in ((0, -1), (1, 1), (2, -1), (3, 1)):
                    t = list(z)
                    t[side] += step
                    if ok(t):
                        z = t
                        grew = True
                if not grew:
                    break
            # Repère de la carte : relatif au point de collage du plan, prefab non tourné.
            corners = [rotate(x - b["X"], c - b["Z"], -b["Rotation"]) for x in z[:2] for c in z[2:]]
            local = [min(c[0] for c in corners), max(c[0] for c in corners), min(c[1] for c in corners),
                     max(c[1] for c in corners)]
            zones.setdefault(bid, {})[str(palier)] = local
    return out, report


def annex_entries(workshops, incompatible):
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
                    if palier in LEVEL_PALIERS and a.get("Slot", 1) <= 1:
                        r = piece_rect(i, (want, 0, 0))
                        reasons = []
                        if r and not contains(frame, r):
                            reasons.append("déborde du rectangle de l'avant-poste")
                        if r and r[0] - 1 <= 0 <= r[1] + 1 and r[2] - 1 <= 0 <= r[3] + 1:
                            reasons.append("couvre le panneau")
                        reasons += [f"touche la pièce {pieces[j][0].rsplit('/', 1)[-1]}" for j in range(len(pieces))
                                    if j != i and r and piece_rect(j, current[j]) and overlaps(r, piece_rect(j, current[j]))]
                        incompatible.append({"People": w.get("People"), "Layout": None, "Id": "AP_" + a["Type"],
                                             "Workshop": wid, "Palier": palier,
                                             "Voulu": steps[want].rsplit("/", 1)[-1],
                                             "Montre": steps[pick[0]].rsplit("/", 1)[-1],
                                             "Raison": " ; ".join(dict.fromkeys(reasons or [
                                                 "ne tient nulle part à moins de %d blocs" % ANNEX_SHIFT]))})
                current[i] = pick
            lines = [f"{ladders[i][c[0]]} {pieces[i][1] + c[1]} {pieces[i][2] + c[2]} {pieces[i][3]}"
                     for i, c in enumerate(current)]
            retouched = RETOUCHES.get((w.get("People"), "AP_" + a["Type"], palier))
            if retouched:
                r = rect_of(retouched, 0, 0, 0)
                if r and contains(frame, r):
                    lines = [f"{retouched} 0 0 0"]  # palier retouché à la main : toute la composition en un prefab
                elif a.get("Slot", 1) <= 1:
                    incompatible.append({"People": w.get("People"), "Layout": None, "Id": "AP_" + a["Type"],
                                         "Workshop": wid, "Palier": palier, "Voulu": retouched.rsplit("/", 1)[-1],
                                         "Montre": lines[0].split()[0].rsplit("/", 1)[-1],
                                         "Raison": "retouche qui déborde du rectangle de l'avant-poste"})
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
        "Incompatibles": [],
        "Review": {},
        "Zones": {},
    }
    incompatible = doc["Incompatibles"]
    total = {"grown": 0, "blocked": 0}
    for f in sorted((NAK / "VillageLayouts").glob("*.json")):
        layout = json.loads(f.read_text(encoding="utf-8"))
        entries, rep = layout_entries(layout, workshops, f.stem, incompatible, doc["Review"].setdefault(f.stem, {}),
                                      doc["Zones"].setdefault(f.stem, {}))
        doc["Layouts"][f.stem] = entries
        print(f"{f.stem}: {len(entries)}/{len(layout['Buildings'])} bâtiments changent d'apparence "
              f"({rep['blocked']} paliers retenus faute de place ; panneaux déplacés : {rep['front']} devant la "
              f"façade, {rep['inside']} à l'intérieur)")
        for k in total:
            total[k] += rep[k]
    annexes, rep = annex_entries(workshops, incompatible)
    doc["Annexes"] = annexes
    print(f"avant-postes : {len(annexes)} changent d'apparence ({rep['blocked']} paliers de pièce retenus faute de place)")
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(incompatible)} paliers incompatibles (visibles en jeu, version voulue qui ne tient pas)")
    print(f"→ {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
