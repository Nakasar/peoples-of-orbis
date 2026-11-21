#!/usr/bin/env python3
"""Copie dans le mod les prefabs vanilla qu'il cite (Kevin, 21 novembre 2026 : le mod ne référence plus aucun prefab
vanilla de bâtiment ou de structure, il colle sa propre copie, tools/mod_prefabs.py).

Cherche dans src/main (Java, plans, paliers...) les ids `Nakasar/Peuples/<chemin vanilla>` dont la copie manque, et
copie `Server/Prefabs/<chemin vanilla>.prefab.json` d'Assets.zip tel quel dans
src/main/resources/Server/Prefabs/Nakasar/Peuples/<chemin vanilla>.prefab.json. À lancer là où se trouve Assets.zip
(PC du jeu), après avoir changé un générateur qui prend un nouveau prefab vanilla :

    python3 tools/copy_vanilla_prefabs.py Assets.zip [--dry-run]

validateAssets refuse ensuite tout chemin vanilla cité et toute copie absente.
"""
import argparse
import re
import zipfile
from pathlib import Path

from mod_prefabs import MOD

ROOT = Path(__file__).resolve().parent.parent
PREFABS = ROOT / "src/main/resources/Server/Prefabs"
SOURCES = (ROOT / "src/main/java", ROOT / "src/main/resources")
TOKEN = re.compile(re.escape(MOD) + r"((?:[A-Za-z0-9_\-]+/)+[A-Za-z0-9_\-]+)")


def wanted(vanilla):
    out = set()
    for base in SOURCES:
        for f in base.rglob("*"):
            if not f.is_file() or PREFABS in f.parents or f.suffix not in (".java", ".json", ".lang", ".ui", ".txt", ".md"):
                continue
            for m in TOKEN.finditer(f.read_text(encoding="utf-8", errors="replace")):
                if m.group(1) in vanilla:
                    out.add(m.group(1))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("assets", help="Assets.zip du jeu")
    parser.add_argument("--dry-run", action="store_true", help="liste les copies manquantes sans rien écrire")
    args = parser.parse_args()
    with zipfile.ZipFile(args.assets) as z:
        vanilla = {n[len("Server/Prefabs/"):-len(".prefab.json")] for n in z.namelist()
                   if n.startswith("Server/Prefabs/") and n.endswith(".prefab.json")}
        missing = sorted(p for p in wanted(vanilla) if not (PREFABS / (MOD + p + ".prefab.json")).exists())
        for p in missing:
            print(("à copier " if args.dry_run else "copié ") + MOD + p)
            if not args.dry_run:
                out = PREFABS / (MOD + p + ".prefab.json")
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(z.read(f"Server/Prefabs/{p}.prefab.json"))
    print(f"{len(missing)} prefab(s) {'manquant(s)' if args.dry_run else 'copié(s)'}")


if __name__ == "__main__":
    main()
