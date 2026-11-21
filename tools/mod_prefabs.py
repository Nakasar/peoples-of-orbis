"""Prefabs du mod copiés depuis les prefabs vanilla (Kevin, 21 novembre 2026 : « on arrête de référencer les prefab
vanilla pour les bâtiments et structures ; pour tous les prefab qu'on utilise, copie le prefab dans le mod avec sa
propre référence »).

Le prefab vanilla `Server/Prefabs/<chemin>` d'Assets.zip est copié tel quel dans
`src/main/resources/Server/Prefabs/Nakasar/Peuples/<chemin>` : le mod ne colle que ces copies, sous l'id
`Nakasar/Peuples/<chemin>`. Les relevés des prefabs vanilla (tools/vanilla-prefabs-0.7.2.txt) gardent le chemin
vanilla ; les outils le traduisent avec `mod()`. tools/copy_vanilla_prefabs.py copie ceux qui manquent ;
validateAssets refuse tout chemin vanilla et toute copie absente.
"""

import json
from pathlib import Path

MOD = "Nakasar/Peuples/"
# Prefabs propres à chaque bâtiment (tools/building_prefabs.py) : Batiments/<Peuple>/<Bâtiment>/..., et leur origine.
OWN = MOD + "Batiments/"
MANIFEST = Path(__file__).resolve().parent / "prefabs-batiments.json"
_SOURCES = None


def mod(path):
    """Id du mod d'un prefab (chemin sous Server/Prefabs/ sans extension) ; un id du mod reste tel quel."""
    return path if path.startswith("Nakasar/") else MOD + path


def vanilla(path):
    """Chemin vanilla d'origine d'une copie du mod (`Nakasar/Peuples/Npc/...` → `Npc/...`)."""
    return path[len(MOD):] if path.startswith(MOD) else path


def source(path):
    """Prefab d'origine (copie vanilla, enclos, marché, retouche) d'un prefab propre à un bâtiment ; sinon lui-même."""
    global _SOURCES
    if not path.startswith(OWN):
        return path
    if _SOURCES is None:
        doc = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {}
        _SOURCES = {k: v["Source"] for k, v in doc.get("Prefabs", {}).items()}
    return _SOURCES.get(path, path)


def sourced(obj):
    """Le document `obj` (plan, avant-poste, paliers) avec chaque prefab propre remplacé par son origine : les outils
    des paliers et de la carte de revue raisonnent sur les familles d'origine (échelles des versions plus grandes)."""
    if isinstance(obj, dict):
        return {k: sourced(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [sourced(v) for v in obj]
    if isinstance(obj, str) and obj.startswith(OWN):
        head, _, rest = obj.partition(" ")
        return source(head) + (" " + rest if rest else "")
    return obj
