"""Prefabs du mod copiés depuis les prefabs vanilla (Kevin, 21 novembre 2026 : « on arrête de référencer les prefab
vanilla pour les bâtiments et structures ; pour tous les prefab qu'on utilise, copie le prefab dans le mod avec sa
propre référence »).

Le prefab vanilla `Server/Prefabs/<chemin>` d'Assets.zip est copié tel quel dans
`src/main/resources/Server/Prefabs/Nakasar/Peuples/<chemin>` : le mod ne colle que ces copies, sous l'id
`Nakasar/Peuples/<chemin>`. Les relevés des prefabs vanilla (tools/vanilla-prefabs-0.7.2.txt) gardent le chemin
vanilla ; les outils le traduisent avec `mod()`. tools/copy_vanilla_prefabs.py copie ceux qui manquent ;
validateAssets refuse tout chemin vanilla et toute copie absente.
"""

MOD = "Nakasar/Peuples/"


def mod(path):
    """Id du mod d'un prefab (chemin sous Server/Prefabs/ sans extension) ; un id du mod reste tel quel."""
    return path if path.startswith("Nakasar/") else MOD + path


def vanilla(path):
    """Chemin vanilla d'origine d'une copie du mod (`Nakasar/Peuples/Npc/...` → `Npc/...`)."""
    return path[len(MOD):] if path.startswith(MOD) else path
