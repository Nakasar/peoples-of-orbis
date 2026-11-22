#!/usr/bin/env python3
"""Paliers 1 à 4 des bâtiments et carte plate de revue (Kevin, 16 novembre 2026, « parking à idées » du tableau blanc).

Deux idées regroupées :
1. « Bâtiments de base, d'artisan et avant-postes / lieux-dits : passer à 4 paliers (1-4) avec des variations
   visuelles à chaque palier. »
2. « Une map plate avec tous les prefabs posés dans des rectangles afin de pouvoir les éditer facilement. »

Retours de Kevin sur la première carte (PR #168) :
- rectangles délimités en laine rouge, de taille CONSTANTE pour les quatre paliers d'un bâtiment ;
- par défaut, TOUS les prefabs de tous les types de villages et des autres structures sur la carte ;
- pas de barrières ni de décor autour pour marquer un palier : la différence se voit sur le bâtiment lui-même
  (taille croissante, comme l'arbre du cœur kweebec qui grandit) ;
- les panneaux n'affichent rien ; un clic dessus dit le bâtiment et le palier.

Ce script écrit src/main/resources/nakasar-peuples/revue-batiments.json : la liste ordonnée des bâtiments, par
bande (un peuple, puis « Monde » pour les structures hors village), avec leurs paliers. Le Java
(dev.nakasar.hytale.peuples.review.ReviewMap, commande « /village revue ») mesure chaque prefab sur ses blocs réels,
donne à chaque bâtiment un rectangle à la taille de son plus grand palier, et y colle chaque palier au centre.

Paliers (règle n° 2 d'AGENTS.md : vanilla d'abord, rien d'autre que des prefabs vanilla et ceux du mod) :
- cœur du village : ses quatre étapes réelles (VillageHeart, palier de village 0 à 3), cumulées ;
- autres bâtiments : palier 1 = le prefab d'aujourd'hui ; paliers 2 à 4 = version de plus en plus grande de la même
  famille vanilla (CHAINS d'abord, comme maison outlander Tier0 → Tier1 → Tier2, puis les autres prefabs du même
  dossier classés par volume). Sans version plus grande, le palier reste celui d'en dessous et le panneau le dit
  (« à retoucher à la main »). Lieux-dits : les bâtiments annexes arrivent aux paliers 2 et 3.
- structures du monde vivant (auberges, camps, vestiges, Source de l'Écho) : un seul rectangle, sans palier.

    python3 tools/building_tiers.py
"""
import json
import re
from pathlib import Path

from mod_prefabs import mod, source, sourced

ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(__file__).resolve().parent
RES = ROOT / "src/main/resources/Server"
NAK = RES / "Nakasar"
JAVA = ROOT / "src/main/java/dev/nakasar/hytale/peuples"
OUT = ROOT / "src/main/resources/nakasar-peuples/revue-batiments.json"
LANG = RES / "Languages/fr-FR/nakasar_peuples.lang"

PEOPLES = ["Kweebec", "Trork", "Feran", "Renonce", "Slothian"]
PEOPLE_NAMES = {"Kweebec": "Kweebec", "Trork": "Trork", "Feran": "Feran", "Renonce": "Renoncé", "Slothian": "Slothian",
                "Monde": "Monde"}
ZONE_OF = {1: "zone 1", 2: "zone 2 (désert)", 3: "zone 3 (givre)", 4: "zone 4 (Slothians)"}
CATEGORIES = ["Coeur", "Base", "Artisan", "Projet", "Production", "Ouvrage", "LieuDit", "Auberge", "Camp", "Vestige",
              "Source"]
CATEGORY_NAMES = {"Coeur": "Cœur du village", "Base": "Bâtiment de base", "Artisan": "Bâtiment d'artisan",
                  "Projet": "Projet de terrain", "Production": "Production (dehors)",
                  "Ouvrage": "Ouvrage (infrastructure)", "LieuDit": "Lieu-dit",
                  "Auberge": "Auberge", "Camp": "Campement", "Vestige": "Vestige", "Source": "Source de l'Écho"}
# Catégories à quatre paliers ; les structures du monde n'ont qu'un rectangle.
TIERED = {"Coeur", "Base", "Artisan", "Projet", "Production", "Ouvrage", "LieuDit"}
# Catégories des projets de terrain (champ Category des Workshops, tools/plots.py).
PROJECT_CATEGORIES = {"Artisanat": "Artisanat", "Defense": "Défense", "Diplomatie": "Diplomatie", "Savoir": "Savoir",
                      "Culte": "Culte", "Elevage": "Élevage", "Commerce": "Commerce"}
# Bâtiments prévus par le doc « Structurer les types de bâtiments » (Kevin, 2026-11-16) qui n'ont pas encore de prefab :
# un emplacement réservé à leur nom (rectangle vide de la taille d'un terrain de projet, 24 × 24).
RESERVE_SIZE = (24, 24)
RESERVED = {
    people: [("Production", "Production_Regionale", "Production régionale (type régional à définir)",
              "doc Structurer les types de bâtiments : type régional, ressource de luxe du biome")]
    for people in PEOPLES}
# L'atelier slothian (Taillerie du basalte, 2026-11-17) a maintenant son prefab : plus d'emplacement réservé.
RESERVED_CATEGORY_NAMES = {}

NPC = "Nakasar/Peuples/Npc/"


# ---- Familles vanilla : version plus grande d'un prefab ----

def _house_outlander(m):
    k, n = int(m.group(1)), int(m.group(2))
    return [f"{NPC}Outlander/Houses/Tier{min(k + d, 3)}/Outlander_Houses_Tier{min(k + d, 3)}_{n:03d}" for d in (1, 2, 3)]


def _tower_outlander(m):
    k, n = int(m.group(1)), int(m.group(2))
    return [f"{NPC}Outlander/Towers/Tier{min(k + d, 3)}/Outlander_Towers_Tier{min(k + d, 3)}_{n:03d}" for d in (1, 2, 3)]


def _watch_trork(m):
    k, n = int(m.group(1)), int(m.group(2))
    return [f"{NPC}Trork/Tier_{min(k + d, 3)}/Watchtower/Trork_Tier_{min(k + d, 3)}_Watchtower_{n:03d}" for d in (1, 2, 3)]


# Échelles explicites (familles réparties sur plusieurs dossiers) : versions de plus en plus grandes, à la suite.
CHAINS = [
    (r"^Nakasar/Peuples/Npc/Outlander/Houses/Tier(\d)/Outlander_Houses_Tier\d_(\d+)$", _house_outlander),
    (r"^Nakasar/Peuples/Npc/Outlander/Towers/Tier(\d)/Outlander_Towers_Tier\d_(\d+)$", _tower_outlander),
    (r"^Nakasar/Peuples/Npc/Trork/Tier_(\d)/Watchtower/Trork_Tier_\d_Watchtower_(\d+)$", _watch_trork),
    (r"^Nakasar/Peuples/Npc/Trork/Tier_2/Store/Trork_Tier_2_Store_001$",
     lambda m: [NPC + "Trork/Tier_3/Store/Trork_Tier_3_Store_001", NPC + "Trork/Tier_3/Store/Trork_Tier_3_Store_002"]),
    (r"^Nakasar/Peuples/Npc/Kweebec/Redwood/Small_Plot/House/Kweebec_Redwood_Small_Plot_House_\d+$",
     lambda m: [NPC + "Kweebec/Redwood/Normal_Plot/House/Kweebec_Redwood_Normal_Plot_House_001",
                NPC + "Kweebec/Redwood/Normal_Plot/House/Kweebec_Redwood_Normal_Plot_House_002"]),
    (r"^Nakasar/Peuples/Npc/Kweebec/Redwood/Small_Plot/Farm/Kweebec_Redwood_Small_Plot_Farm_\d+$",
     lambda m: [NPC + "Kweebec/Redwood/Normal_Plot/Farm/Kweebec_Redwood_Normal_Plot_Farm_003",
                NPC + "Kweebec/Redwood/Normal_Plot/Farm/Kweebec_Redwood_Normal_Plot_Farm_001"]),
    (r"^Nakasar/Peuples/Npc/Kweebec/Oak/Garden/Small/Kweebec_Oak_Garden_Small_001$",
     lambda m: [NPC + "Kweebec/Oak/Garden/Medium/Kweebec_Oak_Garden_Medium_001",
                NPC + "Kweebec/Oak/Garden/Large/Kweebec_Oak_Garden_Large_001",
                NPC + "Kweebec/Oak/Garden/Large/Kweebec_Oak_Garden_Large_003"]),
    (r"^Nakasar/Peuples/Npc/Kweebec/Oak/Garden/Medium/Kweebec_Oak_Garden_Medium_001$",
     lambda m: [NPC + "Kweebec/Oak/Garden/Large/Kweebec_Oak_Garden_Large_001",
                NPC + "Kweebec/Oak/Garden/Large/Kweebec_Oak_Garden_Large_003"]),
    # Maisons-arbres kweebec : petite maison → grande maison (l'arbre grandit).
    (r"^Nakasar/Peuples/Npc/Kweebec/Oak/Houses_Small/Kweebec_Oak_Houses_Small_001$",
     lambda m: [NPC + "Kweebec/Oak/Houses_Small/Kweebec_Oak_Houses_Small_002",
                NPC + "Kweebec/Oak/Houses_Large/Kweebec_Oak_Houses_Large_001",
                NPC + "Kweebec/Oak/Houses_Large/Kweebec_Oak_Houses_Large_002"]),
    (r"^Nakasar/Peuples/Npc/Kweebec/Oak/Houses_Small/Kweebec_Oak_Houses_Small_002$",
     lambda m: [NPC + "Kweebec/Oak/Houses_Large/Kweebec_Oak_Houses_Large_001",
                NPC + "Kweebec/Oak/Houses_Large/Kweebec_Oak_Houses_Large_002"]),
    (r"^Nakasar/Peuples/Npc/Slothian/Disabled/House/Small/Slothian_Disabled_House_Small_(\d+)$",
     lambda m: [f"{NPC}Slothian/Disabled/House/Medium/Slothian_Disabled_House_Medium_{int(m.group(1)):03d}",
                f"{NPC}Slothian/Disabled/House/Medium/Tall/Slothian_Disabled_House_Medium_Tall_{(int(m.group(1)) - 1) % 2 + 1:03d}"]),
    (r"^Nakasar/Peuples/Npc/Slothian/Disabled/House/Medium/Slothian_Disabled_House_Medium_(\d+)$",
     lambda m: [f"{NPC}Slothian/Disabled/House/Medium/Tall/Slothian_Disabled_House_Medium_Tall_{(int(m.group(1)) - 1) % 2 + 1:03d}"]),
    (r"^Nakasar/Peuples/Npc/Slothian/Disabled/Gate/Slothian_Disabled_Gate_(\d+)$",
     lambda m: [NPC + "Slothian/Disabled/Gate/Rich/Slothian_Disabled_Gate_Rich_001",
                NPC + "Slothian/Disabled/Gate/Rich/Slothian_Disabled_Gate_Rich_002"]),
    (r"^Nakasar/Peuples/Npc/Feran/Tier2/Hut/Feran_Tier2_Hut_00[123]$",
     lambda m: [NPC + "Feran/Portals_Oasis/Hut/Feran_Tier2_Hut_004", NPC + "Feran/Portals_Oasis/Hut/Feran_Tier2_Hut_005"]),
    (r"^Nakasar/Peuples/Npc/Feran/Portals_Oasis/Hut/Feran_Tier2_Hut_00[123]$",
     lambda m: [NPC + "Feran/Portals_Oasis/Hut/Feran_Tier2_Hut_004", NPC + "Feran/Portals_Oasis/Hut/Feran_Tier2_Hut_005"]),
    # Enclos du mod : enclos de plus en plus grand (tools/generate_pens.py, TIER_HALF).
    (r"^Nakasar/Peuples/Pens/(\w+)_Pen$",
     lambda m: [f"Nakasar/Peuples/Pens/{m.group(1)}_Pen_T{t}" for t in (2, 3, 4)]),
    # Place du marché (tools/generate_markets.py) : même place, de plus en plus garnie.
    (r"^Nakasar/Peuples/Markets/(\w+)_Marche$",
     lambda m: [f"Nakasar/Peuples/Markets/{m.group(1)}_Marche_T{t}" for t in (2, 3, 4)]),
    # Tente trork → terrier → réserve → grande réserve.
    (r"^Nakasar/Peuples/Npc/Trork/Tent/Trork_Tent_001$",
     lambda m: [NPC + "Trork/Burrow/Trork_Burrow_Trork_Burrow_001", NPC + "Trork/Tier_2/Store/Trork_Tier_2_Store_001",
                NPC + "Trork/Tier_3/Store/Trork_Tier_3_Store_001"]),
    (r"^Nakasar/Peuples/Monuments/Incidental/Slothian/Land/Hunting/Incidental_Slothian_Slothian_Hunting_001$",
     lambda m: ["Nakasar/Peuples/Monuments/Incidental/Slothian/Land/Hunting/Incidental_Slothian_Slothian_Hunting_002"]),
    (r"^Nakasar/Peuples/Npc/Feran/Tier3/Huts/Feran_Tier3_Huts_001$",
     lambda m: [NPC + "Feran/Portals_Oasis/Pillar_Hut/Feran_Tier3_Hut_001", NPC + "Feran/Tier2/Hut/Feran_Tier2_Hut_002",
                NPC + "Feran/Tier2/Hut/Feran_Tier2_Hut_001"]),
]

# Dossiers dont les prefabs ne sont PAS des versions les uns des autres (pas d'échelle par volume) : formes différentes
# d'une même ressource ou décor varié, où « plus grand » ne veut pas dire « palier au-dessus ».
NO_FOLDER_LADDER = ("Nakasar/Peuples/Npc/Kweebec/Oak/Garden_Small", "Nakasar/Peuples/Npc/Kweebec/Autumn/Garden_Small", "Nakasar/Peuples/Npc/Kweebec/Oak/Lampposts",
                    "Nakasar/Peuples/Npc/Kweebec/Oak/Seats", "Nakasar/Peuples/Npc/Outlander/Braziers", "Nakasar/Peuples/Npc/Outlander/Spikes", "Nakasar/Peuples/Npc/Outlander/Boats",
                    "Nakasar/Peuples/Npc/Trork/Warning", "Nakasar/Peuples/Npc/Trork/Trap", "Nakasar/Peuples/Npc/Feran/Portals_Oasis/Wall", "Nakasar/Peuples/Npc/Feran/Tier2/Wall",
                    "Nakasar/Peuples/Npc/Feran/Tier3/Walls", "Nakasar/Peuples/Npc/Feran/Tier2/Entrance", "Nakasar/Peuples/Npc/Feran/Tier2/Chieftain",
                    "Nakasar/Peuples/Npc/Feran/Tier3/Chieftain")


# Prefabs que le mod colle déjà ailleurs mais absents de vanilla-prefabs-0.7.2.txt : permis dans une échelle d'une pièce
# seule (le Java les mesure sur place ; leur copie du mod est aussi mesurée par load_bounds).
UNMEASURED_OK = {"Nakasar/Peuples/Monuments/Incidental/Slothian/Land/Hunting/Incidental_Slothian_Slothian_Hunting_002"}


def load_bounds():
    bounds = {}
    for line in (TOOLS / "vanilla-prefabs-0.7.2.txt").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        p = line.split()
        if len(p) == 7:
            bounds[mod(p[0])] = tuple(int(v) for v in p[1:])
    for f in (RES / "Prefabs").rglob("*.prefab.json"):
        d = json.loads(f.read_text(encoding="utf-8"))
        bl = [b for b in d["blocks"] if b.get("name") not in (None, "Empty")]
        if not bl:
            continue
        rel = str(f.relative_to(RES / "Prefabs")).replace("\\", "/")[: -len(".prefab.json")]
        # Relatives à l'ancre, comme vanilla-prefabs-0.7.2.txt (les copies des prefabs vanilla ont une ancre).
        ax, ay, az = d.get("anchorX", 0), d.get("anchorY", 0), d.get("anchorZ", 0)
        bounds[rel] = (min(b["x"] for b in bl) - ax, max(b["x"] for b in bl) - ax, min(b["y"] for b in bl) - ay,
                       max(b["y"] for b in bl) - ay, min(b["z"] for b in bl) - az, max(b["z"] for b in bl) - az)
    return bounds


def load_lang():
    lang = {}
    for line in LANG.read_text(encoding="utf-8").splitlines():
        if " = " in line and not line.startswith("#"):
            k, v = line.split(" = ", 1)
            lang[k.strip()] = v.strip()
    return lang


BOUNDS = load_bounds()
LANG_FR = load_lang()


def nice(identifier):
    s = identifier.replace("_", " ").strip()
    return s[:1].upper() + s[1:].lower()


def volume(path):
    b = BOUNDS[path]
    return (b[1] - b[0] + 1) * (b[3] - b[2] + 1) * (b[5] - b[4] + 1)


def rot_rect(path, x, z, rot):
    b = BOUNDS.get(path)
    if b is None:
        return None
    x0, x1, z0, z1 = b[0], b[1], b[4], b[5]
    for _ in range(rot % 4):
        x0, x1, z0, z1 = -z1, -z0, x0, x1
    return [x + x0, x + x1, z + z0, z + z1]


def ladder(path):
    """Versions de plus en plus grandes de `path` (sans lui), d'abord CHAINS, puis le même dossier par volume."""
    path = source(path)  # prefab propre à un bâtiment (tools/building_prefabs.py) : échelle de son origine
    for pattern, fn in CHAINS:
        m = re.match(pattern, path)
        if m:
            out = []
            for p in fn(m):
                if (p in BOUNDS or p in UNMEASURED_OK) and p != path and p not in out:
                    out.append(p)
            return out
    if path not in BOUNDS:
        return []
    folder = path.rsplit("/", 1)[0]
    if folder.startswith(NO_FOLDER_LADDER):
        return []
    me = volume(path)
    sibs = [p for p in BOUNDS if p.rsplit("/", 1)[0] == folder and p != path and volume(p) > me]
    return sorted(sibs, key=volume)


def overlaps(a, b):
    return a and b and not (a[1] + 1 < b[0] or b[1] + 1 < a[0] or a[3] + 1 < b[2] or b[3] + 1 < a[2])


# ---- Sources ----

def layout_of(people):
    for f in (NAK / "VillageLayouts").glob("*.json"):
        d = sourced(json.loads(f.read_text(encoding="utf-8")))
        if d["People"] == people:
            return d
    raise SystemExit(f"plan de village introuvable : {people}")


def heart_entry(people):
    layout = layout_of(people)
    tiers, pieces, blocks, clear = [], [], [], []
    for step in sorted(layout["Heart"]["Steps"], key=lambda s: s["Tier"]):
        for p in step.get("Pieces", []):
            pieces.append(f"{p['Prefab']} {p['X']} {p.get('Y', 0)} {p['Z']} {p.get('Rotation', 0)}")
        if step.get("Fluids"):
            pieces.append(f"{step['Fluids']} 0 0 0 0")
        # Repère du cœur : y 0 = surface de la place ; carte : dy 0 = posé sur le sol (dy = y - 1).
        for line in step.get("Set", []):
            x, y, z, rot, name = line.split()
            blocks.append(f"{x} {int(y) - 1} {z} {rot} {name}")
        for line in step.get("Clear", []):
            x, y, z, name = line.split()
            clear.append(f"{x} {int(y) - 1} {z} {name}")
        tiers.append({"Tier": step["Tier"] + 1, "Pieces": list(pieces), "Blocks": list(blocks), "Clear": list(clear),
                      "Notes": [f"étape {step['Tier']} du cœur (palier de village {step['Tier']})"]})
    return {"People": people, "Category": "Coeur", "Id": "Coeur", "Name": "Cœur du village",
            "Source": f"plan {layout_file(people).stem}", "Tiers": tiers}


def layout_file(people):
    for f in (NAK / "VillageLayouts").glob("*.json"):
        if sourced(json.loads(f.read_text(encoding="utf-8")))["People"] == people:
            return f
    return ""


def village_entries(people):
    out = []
    seen_projects = set()
    for b in layout_of(people)["Buildings"]:
        bid = b["Id"]
        category_name = None
        if re.match(r"E\d_", bid):
            # Terrains E1, E2, E3 : même catalogue, même prefab par projet (vérifié) ; un seul exemplaire du projet.
            name_id = bid[3:]
            if name_id in seen_projects:
                continue
            seen_projects.add(name_id)
            wid = f"{people}_{bid}"
            ws = sourced(json.loads((NAK / "Workshops" / f"{wid}.json").read_text(encoding="utf-8")))
            if ws.get("Legacy"):
                continue  # projet hérité (doublait un avant-poste de production) : plus construit
            cat, bid = "Projet", "Projet_" + name_id
            category_name = "Projet de terrain · " + PROJECT_CATEGORIES.get(ws.get("Category"), ws.get("Category"))
            source = "projet de terrain (E1, E2 ou E3)"
        else:
            wid, name_id = f"{people}_{bid}", bid
            cat = "Artisan" if (NAK / "Workshops" / f"{wid}.json").exists() else "Base"
            source = f"village, palier de village {b['Tier']}"
        name = LANG_FR.get(f"workshop.{wid}.name") if cat != "Base" else None
        out.append({"People": people, "Category": cat, "Id": bid, "LayoutId": b["Id"], "Name": name or nice(name_id),
                    "CategoryName": category_name, "Source": source, "Pieces": [(b["Prefab"], 0, 0, 0, 0)],
                    "Optional": []})
    return out


def annex_entries(people):
    out = []
    for f in sorted((NAK / "Workshops").glob(f"{people}_AP_*.json")):
        d = sourced(json.loads(f.read_text(encoding="utf-8")))
        if d["Annex"].get("Slot", 1) > 1:
            continue  # place 2, 3... d'un ouvrage : copie du même bâtiment
        pieces = []
        for line in d["Annex"]["Prefabs"]:
            p = line.split()
            pieces.append((p[0], int(p[1]), 0, int(p[2]), int(p[3]) if len(p) > 3 else 0))
        production = d["Annex"].get("Family") == "Production"
        limit = d["Annex"].get("Limit", 1)
        out.append({"People": people, "Category": "Production" if production else "Ouvrage",
                    "Id": "AP_" + d["Annex"]["Type"],
                    "Name": LANG_FR.get(f"workshop.{f.stem}.name") or nice(d["Annex"]["Type"]),
                    "Source": "bâtiment de production, un par village" if production
                    else f"ouvrage, {limit} par village au plus", "Pieces": pieces, "Optional": [],
                    "Rect": d["Annex"]["Rect"]})
    return out


def hamlet_entries(people):
    out = []
    for f in sorted((NAK / "Hamlets").glob(f"{people}_*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        main, sides = [], {}
        for line in d["Buildings"]:
            p = line.split()
            piece = (p[0], int(p[1]), 0, int(p[2]), int(p[3]))
            if p[4] == "M":
                main.append(piece)
            else:
                sides.setdefault(p[4], []).append((float(p[5]), piece))
        optional = [max(sides[s], key=lambda c: c[0])[1] for s in ("W", "E") if sides.get(s)]
        out.append({"People": people, "Category": "LieuDit", "Id": "LD_" + d["Type"],
                    "Name": LANG_FR.get(f"hamlet.type.{d['Type']}") or nice(d["Type"]), "Source": "lieu-dit",
                    "Pieces": main, "Optional": optional, "Decor": [
                        " ".join(line.split()[:4] + line.split()[4:5]) for line in d.get("Decor", [])]})
    return out


def java_prefabs(file, pattern):
    text = (JAVA / file).read_text(encoding="utf-8")
    return re.findall(pattern, text)


def world_entries():
    """Structures du monde vivant hors village : auberges, campements, vestiges, Source de l'Écho (lus dans le Java)."""
    seen = set()
    out = []

    def add(cat, path, name, source):
        if path in seen:
            return
        seen.add(path)
        ident = path.rsplit("/", 1)[-1]
        if any(o["Id"] == ident for o in out):  # même nom de fichier dans deux dossiers (tentes de réfugiés)
            ident = "_".join(path.split("/")[-2:])
        out.append({"People": "Monde", "Category": cat, "Id": ident, "Name": name, "Source": source,
                    "Pieces": [(path, 0, 0, 0, 0)], "Optional": []})

    inn = (JAVA / "world/Inns.java").read_text(encoding="utf-8")
    block = inn[inn.index("public static String prefab(People people)"):]
    block = block[:block.index("}\n")]
    for zone, path in re.findall(r'(case \d|default) -> "([^"]+)"', block):
        z = 1 if zone == "default" else int(zone.split()[1])
        add("Auberge", path, f"Auberge, {ZONE_OF[z]}", "world/Inns")
    camps = (JAVA / "world/Camps.java").read_text(encoding="utf-8")
    block = camps[camps.index("static String[] prefabs(Type type, int zone)"):]
    block = block[:block.index("public static String camperRole")]
    current = None
    for line in block.splitlines():
        m = re.search(r"case (HUNTERS|WOODCUTTERS|PROSPECTORS|MERCHANTS|REFUGEES)", line)
        if m:
            current = m.group(1)
        for path in re.findall(r'"(Nakasar/Peuples/(?:Monuments|Orbis|Npc)/[^"]+)"', line):
            names = {"HUNTERS": "chasseurs", "WOODCUTTERS": "bûcherons", "PROSPECTORS": "prospecteurs",
                     "MERCHANTS": "marchands", "REFUGEES": "réfugiés"}
            add("Camp", path, f"Campement de {names[current]}", "world/Camps")
    traces = (JAVA / "world/Traces.java").read_text(encoding="utf-8")
    for kind, label in (("RUINS", "Vestige (ruine)"), ("OLD_CAMPS", "Vestige (vieux camp)")):
        block = traces[traces.index(f"private static final String[][] {kind}"):]
        block = block[:block.index("};")]
        for path in re.findall(r'"([^"]+)"', block):
            add("Vestige", path, label, "world/Traces")
    for f in (RES / "Prefabs/Nakasar/Peuples/Oasis").glob("*.prefab.json"):
        add("Source", "Nakasar/Peuples/Oasis/" + f.name[: -len(".prefab.json")], "Source de l'Écho", "zone/EchoSpring")
    return out


# ---- Paliers ----

def grow(pieces, tier, notes):
    """Pièces du palier `tier` : chaque pièce monte de (tier - 1) crans dans son échelle, sans toucher ses voisines."""
    out = list(pieces)
    for i, (path, x, y, z, rot) in enumerate(pieces):
        steps = ladder(path)
        if not steps:
            continue
        want = steps[min(tier - 2, len(steps) - 1)] if tier >= 2 else None
        if not want:
            continue
        rect = rot_rect(want, x, z, rot)
        others = [rot_rect(p[0], p[1], p[3], p[4]) for j, p in enumerate(out) if j != i]
        if len(pieces) > 1 and (rect is None or any(overlaps(rect, o) for o in others)):
            # Version plus grande qui chevaucherait une voisine : on prend la plus grande qui tient.
            fitting = [p for p in steps[: min(tier - 1, len(steps))] if not any(
                overlaps(rot_rect(p, x, z, rot), o) for o in others)]
            if not fitting:
                continue
            want = fitting[-1]
        out[i] = (want, x, y, z, rot)
    return out


def reserved_entries(people):
    return [{"People": people, "Category": cat, "Id": ident, "Name": name, "Source": source, "Reserved": True,
             "CategoryName": RESERVED_CATEGORY_NAMES.get((people, ident))}
            for cat, ident, name, source in RESERVED.get(people, [])]


def tiers_of(e):
    if e.get("Reserved"):
        return [{"Tier": t, "Pieces": [], "Blocks": [], "Clear": [], "Reserve": list(RESERVE_SIZE),
                 "Notes": ["pas encore de prefab : emplacement réservé"]} for t in (1, 2, 3, 4)]
    if e["Category"] == "Coeur":
        return e["Tiers"]
    if e["Category"] not in TIERED:
        return [{"Tier": 0, "Pieces": [fmt(p) for p in e["Pieces"]], "Blocks": list(e.get("Decor", [])), "Clear": [],
                 "Notes": []}]
    tiers = []
    previous = None
    for tier in (1, 2, 3, 4):
        base = list(e["Pieces"])
        notes = []
        pieces = grow(base, tier, notes)
        changed = [p[0].rsplit("/", 1)[-1] for p, b in zip(pieces, base) if p[0] != b[0]]
        if changed:
            notes.append("version plus grande : " + ", ".join(changed))
        extra = []
        if tier >= 2 and e["Optional"]:
            extra.append(e["Optional"][0])
            notes.append("1er bâtiment annexe")
        if tier >= 3 and len(e["Optional"]) > 1:
            extra.append(e["Optional"][1])
            notes.append("2e bâtiment annexe")
        all_pieces = [fmt(p) for p in pieces + extra]
        if tier > 1 and all_pieces == previous:
            notes.append("pas de version vanilla plus grande : à retoucher à la main")
        previous = all_pieces
        tiers.append({"Tier": tier, "Pieces": all_pieces, "Blocks": list(e.get("Decor", [])), "Clear": [], "Notes": notes})
    return tiers


GROWTH = Path(__file__).resolve().parent.parent / "src/main/resources/nakasar-peuples/paliers-batiments.json"
# Paliers retouchés à la main sur la carte de revue (Kevin, 17 novembre 2026 : « modifier complètement les prefab pour
# pouvoir faciliter leur mise à jour dans le mod, comme un éditeur ») : /village revue --sauver écrit le prefab et cette
# liste dans nakasar-revue/ à côté des mondes du serveur ; prefabs copiés dans src/main/resources/Server/Prefabs/.
RETOUCHES_FILE = Path(__file__).resolve().parent / "retouches-prefabs.json"


def retouches():
    """(peuple, Id de la carte, palier) -> prefab du mod qui remplace la composition de ce palier."""
    if not RETOUCHES_FILE.exists():
        return {}
    out = {}
    for r in json.loads(RETOUCHES_FILE.read_text(encoding="utf-8")).get("Prefabs", []):
        if r["Prefab"] not in BOUNDS:
            raise SystemExit(f"Prefab retouché absent de src/main/resources/Server/Prefabs/ : {r['Prefab']}")
        out[(r["People"], r["Id"], r["Tier"])] = r["Prefab"]
    return out


def apply_retouches(people, e, tiers, table):
    for t in tiers:
        prefab = table.get((people, e["Id"], t["Tier"]))
        if prefab:
            t["Pieces"] = [f"{prefab} 0 0 0 0"]
            t["Notes"] = [n for n in t["Notes"] if "à retoucher" not in n] + ["retouché à la main sur la carte"]


def growth_data():
    return sourced(json.loads(GROWTH.read_text(encoding="utf-8"))) if GROWTH.exists() else {}


def board_block(people):
    """Panneau vanilla dont hérite le tableau du peuple : même allure sur la carte, sans ouvrir de tableau."""
    f = RES / "Item/Items/Nakasar/Peuples" / f"Nakasar_Peuples_Board_{people}.json"
    return json.loads(f.read_text(encoding="utf-8"))["Parent"]


def add_board(people, e, tiers, growth, stats):
    """
    Panneau du bâtiment de métier là où il apparaît en jeu (Kevin, 17 novembre 2026) : « Board » du palier, « dx dz
    lacet Bloc [Fixe] » relatif à l'origine de la composition. Bâtiment du plan : place de tools/building_growth.py
    (plaquée au mur, ou dedans, par ReviewMap comme Workshops.placeBoard) ; avant-poste : sur sa fondation (Fixe).
    """
    if e.get("Reserved") or e["Category"] not in ("Base", "Artisan", "Projet", "Production", "Ouvrage"):
        return
    block = board_block(people)
    if e["Category"] in ("Production", "Ouvrage"):
        for t in tiers:
            t["Board"] = f"0 0 0 {block} Fixe"
        return
    layout = layout_file(people)
    review = growth.get("Review", {}).get(layout.stem if layout else "", {})
    bid = e.get("LayoutId", e["Id"])
    spots = review.get(bid)
    if not spots:
        return
    for t in tiers:
        spot = spots.get(str(t["Tier"]))
        if not spot:
            continue
        shown = t["Pieces"][0].split()[0] if t["Pieces"] else None
        if shown != spot[3]:
            stats["mismatch"] += 1
            continue
        # Placé à la main (relevé de la carte) : posé tel quel, à sa hauteur ; sinon cherché comme en jeu.
        t["Board"] = (f"{spot[0]} {spot[1]} {spot[2]} {block} Fixe {spot[4]}" if len(spot) > 4
                      else f"{spot[0]} {spot[1]} {spot[2]} {block}")
        stats["placed"] += 1


def add_zone(people, e, tiers, growth):
    """
    Place libre du palier au village (Kevin, 17 novembre 2026) : « Zone » « x0 x1 z0 z1 » relative à l'origine, tracée
    sur la carte ; une retouche qui y tient est valide en jeu. Bâtiment du plan : tools/building_growth.py (section
    Zones ; projet : la place commune à ses trois terrains) ; avant-poste : son rectangle (Annex.Rect).
    """
    if e.get("Reserved"):
        return
    if e["Category"] in ("Production", "Ouvrage"):
        x0, x1, z0, z1 = e["Rect"]
        for t in tiers:
            t["Zone"] = f"{x0} {x1} {z0} {z1}"
            # Rectangle de l'avant-poste : sa place allouée, tracée telle quelle sur la carte (Kevin, 18 novembre 2026).
            t["ZoneMax"] = True
        return
    if e["Category"] not in ("Base", "Artisan", "Projet"):
        return
    layout = layout_file(people)
    zones = growth.get("Zones", {}).get(layout.stem if layout else "", {})
    # Zones maximales fixées par le plan (Kevin, 18 novembre 2026) : bâtiments à « Zone » et terrains de ce plan.
    plan = sourced(json.loads(layout.read_text(encoding="utf-8"))) if layout else {}
    fixed = {b["Id"] for b in plan.get("Buildings", []) if b.get("Zone")}
    if fixed:
        fixed |= {b["Id"] for b in plan.get("Buildings", []) if b.get("Plot")}
    if e["Category"] == "Projet":
        name = e["Id"][len("Projet_"):]
        found = [z for bid, z in zones.items() if re.fullmatch(r"E\d_" + re.escape(name), bid)]
        maximal = any(bid in fixed for bid in zones if re.fullmatch(r"E\d_" + re.escape(name), bid))
    else:
        found = [zones[e["LayoutId"]]] if e.get("LayoutId") in zones else []
        maximal = e.get("LayoutId") in fixed
    for t in tiers:
        boxes = [z[str(t["Tier"])] for z in found if str(t["Tier"]) in z]
        if not boxes:
            continue
        box = [max(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), min(b[3] for b in boxes)]
        if box[0] <= box[1] and box[2] <= box[3]:
            t["Zone"] = " ".join(str(v) for v in box)
            if maximal:
                t["ZoneMax"] = True


def incompatibles():
    """(peuple, Id de la carte, palier) -> raisons : paliers dont la version ne tient pas au village."""
    f = Path(__file__).resolve().parent.parent / "src/main/resources/nakasar-peuples/paliers-batiments.json"
    if not f.exists():
        return {}
    out = {}
    for i in json.loads(f.read_text(encoding="utf-8")).get("Incompatibles", []):
        ident = i["Id"]
        m = re.match(r"(E\d)_(.+)", ident)
        reason = i["Raison"]
        if m:
            ident = "Projet_" + m.group(2)
            reason = f"terrain {m.group(1)} : {reason}"
        out.setdefault((i["People"], ident, i["Palier"]), []).append(reason)
    return out


def fmt(p):
    return f"{p[0]} {p[1]} {p[2]} {p[3]} {p[4]}"


def main():
    sections = []
    for people in PEOPLES:
        entries = ([heart_entry(people)] + village_entries(people) + annex_entries(people) + hamlet_entries(people)
                   + reserved_entries(people))
        sections.append((people, entries))
    sections.append(("Monde", world_entries()))
    doc = {
        "$Comment": "Carte de revue des bâtiments (paliers 1 à 4), générée par tools/building_tiers.py (ne pas modifier "
                    "à la main) ; lue par dev.nakasar.hytale.peuples.review.ReviewMap (/village revue). Pièces : "
                    "« chemin dx dy dz rotation » ; blocs : « dx dy dz rotation Bloc » (dy 0 = posé sur le sol, Empty "
                    "= vider) ; Clear : « dx dy dz Bloc » retiré s'il est là.",
        "World": "nakasar_revue_batiments",
        "Sections": [],
    }
    total_cells = 0
    incompatible = incompatibles()
    growth = growth_data()
    retouched = retouches()
    stats = {"placed": 0, "mismatch": 0}
    missing = set()
    for people, entries in sections:
        rows = []
        for c in CATEGORIES:
            for e in entries:
                if e["Category"] != c:
                    continue
                tiers = tiers_of(e)
                apply_retouches(people, e, tiers, retouched)
                add_board(people, e, tiers, growth, stats)
                add_zone(people, e, tiers, growth)
                for t in tiers:
                    reasons = incompatible.get((people, e["Id"], t["Tier"]))
                    if reasons:
                        # Version de ce palier qui ne tient pas au village (tools/building_growth.py) : liseré bleu.
                        t["Incompatible"] = True
                        t["Notes"] = t["Notes"] + ["ne tient pas au village : " + " ; ".join(reasons)]
                for t in tiers:
                    for line in t["Pieces"]:
                        path = line.split()[0]
                        if path not in BOUNDS and not path.startswith(("Nakasar/Peuples/Monuments/", "Nakasar/Peuples/Orbis/")) and not (
                                RES / "Prefabs" / (path + ".prefab.json")).exists():
                            missing.add(path)
                total_cells += len(tiers)
                rows.append({"Category": c, "CategoryName": e.get("CategoryName") or CATEGORY_NAMES[c], "Id": e["Id"], "Name": e["Name"],
                             "Source": e["Source"], "Tiers": tiers})
        doc["Sections"].append({"People": people, "Name": PEOPLE_NAMES[people], "Rows": rows})
    if missing:
        raise SystemExit("Prefabs inconnus : " + ", ".join(sorted(missing)))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    sections_json = []
    for s in doc["Sections"]:
        rows = ",\n".join("    " + json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in s["Rows"])
        sections_json.append(f'  {{"People":{json.dumps(s["People"])},"Name":{json.dumps(s["Name"], ensure_ascii=False)},'
                             f'"Rows":[\n{rows}\n  ]}}')
    OUT.write_text("{\n" + f' "$Comment": {json.dumps(doc["$Comment"], ensure_ascii=False)},\n'
                   + f' "World": {json.dumps(doc["World"])},\n "Sections": [\n' + ",\n".join(sections_json)
                   + "\n ]\n}\n", encoding="utf-8")
    print(f"{sum(len(s['Rows']) for s in doc['Sections'])} bâtiments et structures, {total_cells} rectangles → "
          f"{OUT.relative_to(ROOT)}")
    for s in doc["Sections"]:
        counts = {}
        flat = 0
        for r in s["Rows"]:
            counts[r["Category"]] = counts.get(r["Category"], 0) + 1
            flat += sum(1 for t in r["Tiers"] if any("à retoucher" in n for n in t["Notes"]))
        print(f"  {s['Name']}: {counts} ; paliers sans version plus grande : {flat}")
    print(f"  panneaux de métier posés : {stats['placed']} ; version différente de tools/building_growth.py (sans panneau) : {stats['mismatch']}")


if __name__ == "__main__":
    main()
