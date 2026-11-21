#!/usr/bin/env python3
"""Prefabs propres à chaque bâtiment (Kevin, 21 novembre 2026 : « organise les prefabs par bâtiment ; les halles
marchandes doivent avoir leurs propres prefabs, comme tous les autres bâtiments, pas de réutilisation »).

Chaque bâtiment des villages (plan, artisan, place du marché, projet de terrain, avant-poste de production ou
ouvrage) a ses propres fichiers, rangés dans son dossier :

    src/main/resources/Server/Prefabs/Nakasar/Peuples/Batiments/<Peuple>/<Bâtiment>/<Bâtiment>_P<n>.prefab.json

`P<n>` est le premier palier d'apparence (1 à 4) qui montre ce prefab : un bâtiment qui garde la même allure du
palier 1 au palier 2 n'a qu'un `_P1` (le Java ne le rebâtit pas pour rien). Un avant-poste a une pièce par fichier
(`AP_<Type>_<pièce>_P<n>`), une version montrée seulement sur la carte de revue (palier voulu qui ne tient pas en jeu)
finit par `_Revue`. Le même bâtiment sur les terrains E1 à E3 garde les mêmes fichiers. Les petits décors (lanternes,
bancs) restent partagés : ce ne sont pas des bâtiments.

Les générateurs gardent leur prefab d'origine (copie vanilla du mod, enclos, place du marché, retouche de la carte) ;
ce script passe EN DERNIER (après building_growth.py puis building_tiers.py) : il copie chaque prefab d'origine dans
le dossier du bâtiment et réécrit les références des plans (VillageLayouts), des avant-postes (Workshops, Annex), des
paliers (paliers-batiments.json) et de la carte de revue (revue-batiments.json). Le manifeste
tools/prefabs-batiments.json garde, pour chaque fichier propre, son origine et l'empreinte SHA-256 de la copie :

- un fichier propre retouché à la main (empreinte différente) n'est JAMAIS écrasé ni supprimé ;
- un fichier propre intact suit son origine si elle change, et disparaît si le bâtiment ne l'utilise plus.
- les emprises (MinX… des plans et des paliers) restent mesurées sur l'origine : une retouche qui change la taille
  passe par la carte de revue (/village revue --sauver, tools/retouches-prefabs.json), qui la mesure.

Les outils qui relisent les plans déjà réécrits (building_growth.py, building_tiers.py) remontent à l'origine par
`mod_prefabs.source()`. À relancer après tout changement de plan :

    python3 tools/generate_village_layouts.py   (et les autres générateurs de plans)
    python3 tools/building_tiers.py && python3 tools/building_growth.py && python3 tools/building_tiers.py
    python3 tools/building_prefabs.py
"""
import hashlib
import json
import re
import shutil
import sys
from collections import OrderedDict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mod_prefabs import MANIFEST, OWN, source  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
PREFABS = RES / "Server/Prefabs"
LAYOUTS = RES / "Server/Nakasar/VillageLayouts"
WORKSHOPS = RES / "Server/Nakasar/Workshops"
PALIERS = RES / "nakasar-peuples/paliers-batiments.json"
REVUE = RES / "nakasar-peuples/revue-batiments.json"
DECOR = ("/Lampposts/", "/Seats/")
# Lignes de la carte de revue qui ne sont pas des bâtiments de village.
NOT_BUILDINGS = ("Coeur", "LieuDit")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def file_of(prefab):
    return PREFABS / (prefab + ".prefab.json")


def is_decor(prefab):
    return any(k in prefab for k in DECOR)


def key_of_layout(bid):
    """Bâtiment d'un plan : le même projet sur E1, E2, E3 est un seul bâtiment."""
    return re.sub(r"^E\d_", "", bid)


def key_of_row(row_id):
    return row_id[len("Projet_"):] if row_id.startswith("Projet_") else row_id


class Building:
    """Prefabs d'origine d'un bâtiment, dans l'ordre des paliers, et leur fichier propre."""

    def __init__(self, people, key):
        self.people = people
        self.key = key
        self.game = OrderedDict()  # (palier, pièce) -> origine
        self.review = []  # (palier, origine)
        self.own = {}  # origine -> id propre

    def add(self, palier, piece, prefab):
        self.game.setdefault((palier, piece), source(prefab))

    def name(self):
        folder = f"{OWN}{self.people}/{self.key}/"
        used = set()
        # Pièces du jeu : nommées au premier palier qui les montre ; avant-poste à plusieurs pièces : numéro de pièce.
        pieces = len({piece for _, piece in self.game})
        for (palier, piece), src in sorted(self.game.items()):
            if src in self.own:
                continue
            n = f"{self.key}_{piece + 1}_P{palier}" if pieces > 1 else f"{self.key}_P{palier}"
            self.own[src] = folder + n
            used.add(n)
        for palier, src in sorted(self.review):
            if src in self.own:
                continue
            n = f"{self.key}_P{palier}_Revue"
            k = 2
            while n in used:
                n = f"{self.key}_P{palier}_Revue{k}"
                k += 1
            self.own[src] = folder + n
            used.add(n)

    def map(self, prefab):
        return self.own.get(source(prefab), prefab)


def main():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {"Prefabs": {}}
    known = manifest.get("Prefabs", {})
    paliers = json.loads(PALIERS.read_text(encoding="utf-8"))
    buildings = OrderedDict()

    def building(people, key):
        return buildings.setdefault((people, key), Building(people, key))

    # 1. Plans des villages : prefab du plan (palier 1) et paliers d'apparence.
    layouts = {}
    for f in sorted(LAYOUTS.glob("*.json")):
        doc = json.loads(f.read_text(encoding="utf-8"))
        layouts[f] = doc
        lid = f.stem
        for b in doc.get("Buildings", []):
            if not b.get("Prefab") or is_decor(source(b["Prefab"])):
                continue
            bd = building(doc["People"], key_of_layout(b["Id"]))
            bd.add(1, 0, b["Prefab"])
            for e in paliers["Layouts"].get(lid, {}).get(b["Id"], []):
                if e.get("Prefab"):
                    bd.add(e["Palier"], 0, e["Prefab"])

    # 2. Avant-postes : chaque pièce de la composition, palier par palier.
    annexes = {}
    for f in sorted(WORKSHOPS.glob("*.json")):
        doc = json.loads(f.read_text(encoding="utf-8"))
        if "Annex" not in doc or doc.get("Hamlet"):
            continue
        annexes[f] = doc
        bd = building(doc["People"], "AP_" + doc["Annex"]["Type"])
        for i, line in enumerate(doc["Annex"]["Prefabs"]):
            bd.add(1, i, line.split()[0])
        for e in paliers["Annexes"].get(f.stem, []):
            for i, line in enumerate(e["Prefabs"]):
                bd.add(e["Palier"], i, line.split()[0])

    # 3. Carte de revue : versions voulues qui ne tiennent pas en jeu.
    revue_lines = REVUE.read_text(encoding="utf-8").split("\n")
    people = None
    for line in revue_lines:
        m = re.match(r'^  \{"People":"([^"]+)"', line)
        if m:
            people = m.group(1)
            continue
        if not line.startswith("    {"):
            continue
        row = json.loads(line.strip().rstrip(","))
        if row.get("Category") in NOT_BUILDINGS:
            continue
        bd = buildings.get((people, key_of_row(row["Id"])))
        if bd is None:
            continue
        for t in row.get("Tiers", []):
            for piece in t.get("Pieces", []):
                src = source(piece.split()[0])
                if src not in bd.game.values() and (t["Tier"], src) not in bd.review:
                    bd.review.append((t["Tier"], src))

    for bd in buildings.values():
        bd.name()

    # 4. Fichiers propres : copie de l'origine, sauf fichier retouché à la main.
    wanted = {}
    for bd in buildings.values():
        for src, own in bd.own.items():
            wanted[own] = src
    kept, written, removed = [], 0, 0
    new_known = {}
    for own, src in sorted(wanted.items()):
        dst, orig = file_of(own), file_of(src)
        if not orig.exists():
            raise SystemExit(f"origine absente : {src} (prefab de {own})")
        rec = known.get(own)
        if dst.exists() and rec and sha(dst) != rec.get("Sha"):
            kept.append(own)  # retouché à la main : il reste tel quel
            new_known[own] = {"Source": src, "Sha": rec.get("Sha"), "Retouche": True}
            continue
        if dst.exists() and rec is None and sha(dst) != sha(orig):
            kept.append(own)  # fichier posé à la main avant le manifeste
            new_known[own] = {"Source": src, "Sha": sha(orig), "Retouche": True}
            continue
        if not dst.exists() or sha(dst) != sha(orig):
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(orig, dst)
            written += 1
        new_known[own] = {"Source": src, "Sha": sha(dst)}
    for own, rec in known.items():
        if own in wanted:
            continue
        dst = file_of(own)
        if dst.exists() and sha(dst) == rec.get("Sha"):
            dst.unlink()
            removed += 1
            parent = dst.parent
            while parent != PREFABS and parent.exists() and not any(parent.iterdir()):
                parent.rmdir()
                parent = parent.parent
        elif dst.exists():
            print(f"ATTENTION : {own} n'est plus utilisé mais a été retouché à la main, il est gardé")
            new_known[own] = dict(rec, Retouche=True)

    # 5. Références réécrites.
    for f, doc in layouts.items():
        lid = f.stem
        for b in doc.get("Buildings", []):
            if not b.get("Prefab") or is_decor(source(b["Prefab"])):
                continue
            bd = buildings[(doc["People"], key_of_layout(b["Id"]))]
            if "/Garden" in source(b["Prefab"]):
                b["Garden"] = True  # potager : sans échafaudage, récolte libre (VillageLayout.Building.small)
            b["Prefab"] = bd.map(b["Prefab"])
            for e in paliers["Layouts"].get(lid, {}).get(b["Id"], []):
                if e.get("Prefab"):
                    e["Prefab"] = bd.map(e["Prefab"])
            review = paliers.get("Review", {}).get(lid, {}).get(b["Id"])
            if review:
                for v in review.values():
                    if len(v) > 3 and isinstance(v[3], str):
                        v[3] = bd.map(v[3])
        f.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for f, doc in annexes.items():
        bd = buildings[(doc["People"], "AP_" + doc["Annex"]["Type"])]

        def remap(line, bd=bd):
            p = line.split(" ", 1)
            return bd.map(p[0]) + ("" if len(p) == 1 else " " + p[1])

        doc["Annex"]["Prefabs"] = [remap(x) for x in doc["Annex"]["Prefabs"]]
        for e in paliers["Annexes"].get(f.stem, []):
            e["Prefabs"] = [remap(x) for x in e["Prefabs"]]
        f.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    PALIERS.write_text(json.dumps(paliers, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    out, people = [], None
    for line in revue_lines:
        m = re.match(r'^  \{"People":"([^"]+)"', line)
        if m:
            people = m.group(1)
        if line.startswith("    {"):
            comma = line.rstrip().endswith(",")
            row = json.loads(line.strip().rstrip(","))
            bd = None if row.get("Category") in NOT_BUILDINGS else buildings.get((people, key_of_row(row["Id"])))
            if bd is not None:
                for t in row.get("Tiers", []):
                    t["Pieces"] = [bd.map(p.split(" ", 1)[0]) + " " + p.split(" ", 1)[1] if " " in p else bd.map(p)
                                   for p in t.get("Pieces", [])]
                line = "    " + json.dumps(row, ensure_ascii=False, separators=(",", ":")) + ("," if comma else "")
        out.append(line)
    REVUE.write_text("\n".join(out), encoding="utf-8")

    manifest = {"$Comment": "Prefabs propres à chaque bâtiment, écrits par tools/building_prefabs.py : id -> origine copiée "
                            "et empreinte SHA-256 de la copie. Un fichier dont l'empreinte a changé a été retouché à la "
                            "main : le script le garde tel quel.",
                "Prefabs": OrderedDict(sorted(new_known.items()))}
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(buildings)} bâtiments, {len(wanted)} prefabs propres ({written} écrits, {removed} retirés, "
          f"{len(kept)} retouchés gardés) → {OWN}")


if __name__ == "__main__":
    main()
