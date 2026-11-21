#!/usr/bin/env python3
"""La place du marché des villages (Kevin, 21 novembre 2026).

« Le Marché, bâtiment disponible de base dans le village dès le palier 0, accueille le marchand du village (au lieu de
l'avoir sur la place du cœur du village). Il permet d'accéder à la boutique qui évolue avec la réputation du joueur.
Le Marché est également l'endroit où les marchands itinérants des autres villages se postent pour vendre et acheter,
avec un emplacement d'étal qui change d'aspect en fonction de l'origine du vendeur itinérant. Au palier 2, deux
spécialisations : marché intérieur, marché extérieur (un comptoir fixe qui achète et vend aux marchands itinérants, par
qui le joueur achète, et qui prend ses commandes). »

Ce script écrit, en blocs vanilla seulement (règle n° 2 d'AGENTS.md, comme les enclos de tools/generate_pens.py) :
  Server/Prefabs/Nakasar/Peuples/Markets/<Peuple>_Marche[_T2|_T3|_T4].prefab.json
      la place pavée (13 × 9), l'étal du marchand du village à gauche, l'emplacement vide de l'étal de passage à
      droite (quatre poteaux), des lumières du peuple ; les paliers d'apparence 2 à 4 ajoutent du décor sans changer
      l'emprise ni les places (tools/building_tiers.py) ;
  Server/Prefabs/Nakasar/Peuples/Markets/Etal_<Peuple>.prefab.json
      l'étal d'un marchand itinérant de ce peuple (bois, toile, marchandises de son pays), posé pour son séjour sur
      l'emplacement de passage par le Java (world/Markets, Sites.pasteTemporary) puis rendu ;
  Server/Prefabs/Nakasar/Peuples/Markets/<Peuple>_Comptoir.prefab.json
      le comptoir fixe du marché extérieur, posé devant la place quand la spécialité est choisie ;
  Server/BarterShops/Nakasar_Peuples_Comptoir_<Hôte>_<Origine>.json
      ce que le comptoir revend des marchands d'un autre peuple : les marchandises des voyageurs de l'origine
      (Nakasar_Peuples_Travel_<Origine>, tools/generate_world_life.py), payées en faveurs du village hôte ;
  Server/Prefabs/Nakasar/Peuples/Markets/<Peuple>_Depot.prefab.json
      le comptoir de dépôt-vente du marché intérieur (même place que le comptoir extérieur), étagères derrière ;
  Server/BarterShops/Nakasar_Peuples_Marche_<Peuple>_Jour.json
      l'étal du jour de marché du marché intérieur (un jour sur sept) : la première offre de chaque bâtiment de métier
      du village, moins chère et en double, plus un lot du luxe de terroir du peuple ;
  Server/Prefabs/Nakasar/Peuples/Markets/<Peuple>_Noir.prefab.json et Server/BarterShops/Nakasar_Peuples_Marche_<Peuple>_
  Noir.json / _Recel.json
      l'étal du receleur du marché noir (Kevin, 21 novembre 2026), là où se tiennent le comptoir et le dépôt-vente, ouvert
      la nuit seulement : ce que le peuple refuse d'échanger (luxes d'ailleurs, métal chez les Kweebecs), et le rachat
      du butin des raids contre des faveurs ; sans perte de réputation ;
  Server/Nakasar/Dialogues/<Peuple>/<Peuple>_Comptoir.json, <Peuple>_Depot.json, <Peuple>_Noir.json et
  Monde/Monde_Marchand_Etal.json
      le commis du comptoir, celui du dépôt-vente, le receleur et le marchand posté à l'étal ;
  le bloc de langue « Place du marché » des deux fichiers .lang (textes du Java world/Markets compris).

Géométrie (coordonnées du prefab, rotation 0, devant = +Z, sol y = 0) : reprise telle quelle par world/Markets.
    python3 tools/generate_markets.py      (avant tools/generate_village_layouts.py, qui lit les emprises)
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/Server"
OUT = RES / "Prefabs/Nakasar/Peuples/Markets"
BARTER = RES / "BarterShops"
DIALOGUES = RES / "Nakasar/Dialogues"
BLOCKS = Path(__file__).resolve().parent / "vanilla-block-ids-0.7.2.txt"
ITEMS = Path(__file__).resolve().parent / "vanilla-item-ids-0.7.2.txt"

LANG_BEGIN = "# ---- Place du marché (généré par tools/generate_markets.py, ne pas modifier à la main) ----"
LANG_END = "# ---- Fin de la place du marché ----"

PEOPLES = ["Kweebec", "Trork", "Feran", "Renonce", "Slothian"]
NAMES = {"Kweebec": ("kweebec", "Kweebec"), "Trork": ("trork", "Trork"), "Feran": ("feran", "Feran"),
         "Renonce": ("renoncé", "Forsaken"), "Slothian": ("slothian", "Slothian")}

# ---- Géométrie : world/Markets la reprend (mêmes valeurs) ----
HALF_X, HALF_Z = 6, 4          # place de 13 × 9 blocs
VILLAGE_STALL = (-4, -2)       # étal du marchand du village (il se tient au centre, derrière le comptoir)
GUEST_STALL = (4, -2)          # emplacement de l'étal de passage
COUNTER = (0, 2)               # comptoir du marché extérieur (le commis au centre)

# Style d'un peuple : bois (poteaux, comptoir), toile de l'auvent, sol, liseré, lumière, marchandises posées sur le
# comptoir et derrière (décor vanilla qui n'est pas un coffre : un étal rendu ne doit jamais emporter d'objets).
STYLE = {
    "Kweebec": dict(wood="Wood_Hardwood", cloth="Green", floor="Soil_Pathway", edge="Soil_Gravel_Mossy",
                    light="Furniture_Kweebec_Lantern", goods=["Furniture_Village_Pot", "Deco_Kweebec_Plush"],
                    back=["Plant_Hay_Bundle", "Deco_Bucket"], extra=["Plant_Flower_Bushy_Purple", "Plant_Fern"]),
    "Trork": dict(wood="Wood_Deadwood", cloth="Hide", floor="Soil_Dirt", edge="Soil_Gravel",
                  light="Furniture_Crude_Torch", goods=["Deco_Bone_Pile", "Deco_Bone_Skulls"],
                  back=["Deco_Trork_Cage", "Plant_Hay_Bundle"], extra=["Deco_Bone_Spike", "Deco_Bone_Ribs"]),
    "Feran": dict(wood="Wood_Drywood", cloth="Orange", floor="Soil_Gravel_Sand", edge="Soil_Sand",
                  light="Furniture_Feran_Torch", goods=["Furniture_Feran_Pot", "Furniture_Feran_Pot_Secondary"],
                  back=["Deco_Bone_Ribs_Feran", "Furniture_Feran_Pot"], extra=["Furniture_Feran_Planter", "Deco_Coral_Shell"]),
    "Renonce": dict(wood="Wood_Softwood", cloth="Blue", floor="Soil_Gravel", edge="Soil_Snow",
                    light="Furniture_Lumberjack_Lantern", goods=["Deco_Tankard", "Deco_Mug"],
                    back=["Plant_Hay_Bundle", "Deco_Bucket"], extra=["Deco_Rope", "Plant_Hay_Bundle"]),
    "Slothian": dict(wood="Wood_Tropicalwood", cloth="Red", floor="Rock_Basalt_Cobble", edge="Soil_Mud_Dry",
                     light="Furniture_Jungle_Torch", goods=["Furniture_Jungle_Pot", "Furniture_Jungle_Pot_Secondary"],
                     back=["Deco_Scroll", "Furniture_Jungle_Pot"], extra=["Furniture_Jungle_Planter", "Deco_Book_Pile_Small"]),
}


def block(blocks, x, y, z, name, rot=0):
    b = {"x": x, "y": y, "z": z, "name": name}
    if rot:
        b["rotation"] = rot
    blocks.append(b)


def stall(cx, cz, style, posts=True, counter=True, roof=True, goods=True, wide=False):
    """Un étal de 5 × 3 autour de (cx, cz) : poteaux aux quatre coins, comptoir devant (+Z), auvent de toile au-dessus,
    marchandises sur le comptoir et derrière. Le vendeur se tient en (cx, cz). `wide` : comptoir sur toute la largeur."""
    out = []
    wood = style["wood"]
    if posts:
        for dx in (-2, 2):
            for dz in (-1, 1):
                for y in (1, 2, 3):
                    block(out, cx + dx, y, cz + dz, wood + "_Beam")
    if counter:
        for dx in ((-2, -1, 0, 1, 2) if wide else (-1, 0, 1)):
            if wide and dx in (-2, 2):
                continue  # les poteaux sont déjà là
            block(out, cx + dx, 1, cz + 1, wood + "_Planks")
    if goods:
        block(out, cx - 1, 2, cz + 1, style["goods"][0])
        block(out, cx + 1, 2, cz + 1, style["goods"][1])
        block(out, cx - 1, 1, cz - 1, style["back"][0])
        block(out, cx + 1, 1, cz - 1, style["back"][1])
    if roof:
        for dx in range(-2, 3):
            for dz in range(-1, 2):
                block(out, cx + dx, 4, cz + dz, f"Cloth_Roof_{style['cloth']}_Flat")
    return out


def depot(style):
    """Le dépôt-vente : un comptoir sur toute la largeur, des étagères pleines et des caisses derrière le commis."""
    out = stall(0, 0, style, wide=True, goods=False)
    for dx in (-1, 1):
        block(out, dx, 1, -1, "Furniture_Village_Shelf_Full")
    block(out, -1, 2, 1, style["goods"][0])
    block(out, 1, 2, 1, "Furniture_Village_Crate")
    return out


def black_stall(style):
    """L'étal du receleur : auvent de cuir sombre, une lanterne, des crânes et des peaux sur le comptoir."""
    dark = dict(style, cloth="Leather")
    out = stall(0, 0, dark, wide=True, goods=False)
    block(out, -1, 2, 1, "Deco_Bone_Skulls")
    block(out, 1, 2, 1, style["light"])
    block(out, 0, 1, -1, "Furniture_Village_Crate")
    return out


def market(people, tier):
    """La place du marché du peuple au palier d'apparence `tier` (1 à 4) : même emprise et mêmes places à chaque palier."""
    s = STYLE[people]
    blocks = []
    for x in range(-HALF_X, HALF_X + 1):
        for z in range(-HALF_Z, HALF_Z + 1):
            edge = abs(x) == HALF_X or abs(z) == HALF_Z
            block(blocks, x, 0, z, s["edge"] if edge else s["floor"])
    blocks += stall(*VILLAGE_STALL, s)
    # Emplacement de passage : quatre poteaux nus, l'étal d'un marchand itinérant vient s'y poser.
    blocks += stall(*GUEST_STALL, s, counter=False, roof=False, goods=False)
    for x, z in ((-HALF_X, -HALF_Z), (HALF_X, -HALF_Z), (-HALF_X, HALF_Z), (HALF_X, HALF_Z)):
        block(blocks, x, 1, z, s["light"])
    if tier >= 2:
        # Palier 2 : des lumières au milieu des côtés, du décor du peuple entre les étals.
        block(blocks, -HALF_X, 1, 0, s["light"])
        block(blocks, HALF_X, 1, 0, s["light"])
        block(blocks, 0, 1, -HALF_Z, s["extra"][0])
    if tier >= 3:
        block(blocks, -1, 1, -HALF_Z, s["extra"][1])
        block(blocks, 1, 1, -HALF_Z, s["extra"][1])
        block(blocks, -HALF_X + 1, 1, -HALF_Z, s["back"][0])
        block(blocks, HALF_X - 1, 1, -HALF_Z, s["back"][1])
    if tier >= 4:
        # Palier 4 : un auvent de plus au fond, entre les deux étals.
        for x in (-1, 0, 1):
            for z in (-3, -2):
                block(blocks, x, 4, z, f"Cloth_Roof_{s['cloth']}_Flat")
        block(blocks, -1, 1, -3, s["goods"][0])
        block(blocks, 1, 1, -3, s["goods"][1])
    return doc(blocks)


def doc(blocks):
    return {"version": 8, "blockIdVersion": 11, "anchorX": 0, "anchorY": 0, "anchorZ": 0, "blocks": blocks}


# ---- Langue ----
FR, EN = {}, {}


def t(key, fr, en):
    FR[key] = fr
    EN[key] = en


def texts():
    k = "market."
    t("building.Marche", "Place du marché", "Market square")
    t(k + "guest.arrived", "{merchant} s'installe à l'étal du marché de {village} jusqu'au matin.",
      "{merchant} sets up at the {village} market stall until morning.")
    t(k + "guest.left", "{merchant} replie son étal au marché de {village}.", "{merchant} packs up the {village} market stall.")
    t(k + "guest.name", "Marchand de {village}", "Merchant from {village}")
    t(k + "guest.greet", "Je repars au matin : profitez-en, voyageur.", "I leave at dawn: make the most of it, traveller.")
    t(k + "guest.rank", "Je ne commerce qu'avec les amis de {village} (rang Toléré).",
      "I only trade with friends of {village} (rank Tolerated).")
    t(k + "guest.gone", "Ce marchand a déjà replié son étal.", "This merchant has already packed up.")
    t(k + "stand.guest", "Étal de passage", "Visiting stall")
    t(k + "stand.guestSub", "{people}, de {village}", "{people}, from {village}")
    t(k + "stand.counter", "Comptoir du marché", "Market counter")
    t(k + "stand.counterSub", "{open} étals sur {total} achetés", "{open} of {total} stalls bought")
    t(k + "counter.never", "Aucun marchand {people} n'est encore passé au marché.", "No {people} merchant has come to the market yet.")
    t(k + "counter.old", "Le dernier marchand {people} est passé il y a {days} jours : tout est vendu.",
      "The last {people} merchant came {days} days ago: all sold out.")
    t(k + "counter.tab", "{people}", "{people}")
    t(k + "counter.none", "Le comptoir n'a encore rien acheté aux marchands de passage.",
      "The counter hasn't bought anything from visiting merchants yet.")
    t(k + "counter.bought", "Le comptoir de {village} achète le reste de l'étal de {merchant}.",
      "The {village} counter buys what is left of {merchant}'s stall.")
    t(k + "counter.placed", "Le comptoir du marché de {village} ouvre ses volets.", "The {village} market counter opens its shutters.")
    t(k + "counter.keeper", "Commis du comptoir", "Counter clerk")
    # Commandes du joueur au comptoir.
    t(k + "order.title", "Commande au comptoir de {village}", "Order at the {village} counter")
    t(k + "order.hint", "Ce que les marchands des autres peuples peuvent apporter. Le comptoir l'achète au premier "
                        "qui passe et le garde pour vous.", "What merchants of other peoples can bring. The counter buys "
                        "it from the first who comes by and keeps it for you.")
    t(k + "order.sub", "{count} pour {price} faveurs", "{count} for {price} favours")
    t(k + "order.current", "Votre commande", "Your order")
    t(k + "order.none", "Aucun marchand ne vend ici ce que {village} peut acheter.", "No merchant sells anything {village} may buy.")
    t(k + "order.back", "Retour", "Back")
    t(k + "order.cancel", "Annuler ma commande", "Cancel my order")
    t(k + "order.out", "Commandes en cours : {count} sur {max}", "Open orders: {count} of {max}")
    t(k + "order.mine", "Votre commande : {item}, {state}", "Your order: {item}, {state}")
    t(k + "order.mineNone", "Vous n'avez pas de commande ici.", "You have no order here.")
    t(k + "order.rule", "On paie en retirant la commande. Le comptoir fait savoir aux voyageurs ce qu'il cherche.",
      "You pay when you collect. The counter lets travellers know what it is looking for.")
    t(k + "order.state.waiting", "en attente d'un marchand", "waiting for a merchant")
    t(k + "order.state.ready", "prête au comptoir", "ready at the counter")
    t(k + "order.placed", "Commande passée : {count} {item}. Le comptoir l'achètera au premier marchand qui en porte.",
      "Order placed: {count} {item}. The counter will buy it from the first merchant who carries some.")
    t(k + "order.already", "Vous avez déjà une commande à ce comptoir.", "You already have an order at this counter.")
    t(k + "order.full", "Le comptoir a déjà {max} commandes en cours.", "The counter already has {max} open orders.")
    t(k + "order.cancelled", "Commande annulée.", "Order cancelled.")
    t(k + "order.ready", "Le comptoir de {village} a acheté votre commande ({count} {item}) à {merchant}.",
      "The {village} counter bought your order ({count} {item}) from {merchant}.")
    t(k + "order.readyImport", "Le comptoir de {village} a reçu votre commande ({count} {item}) par la route commerciale.",
      "The {village} counter received your order ({count} {item}) by trade route.")
    t(k + "order.wanted", "Le comptoir de {village} cherche {item} : il le fait savoir aux voyageurs.",
      "The {village} counter is looking for {item}: it tells travellers.")
    t(k + "order.expired", "Votre commande de {item} au marché de {village} est oubliée après {days} jours.",
      "Your order of {item} at the {village} market is dropped after {days} days.")
    t(k + "order.notReady", "Votre commande attend encore un marchand qui en porte.", "Your order is still waiting for a merchant who carries some.")
    t(k + "order.noFavors", "Il faut {price} faveurs de {people} pour retirer la commande.",
      "You need {price} {people} favours to collect the order.")
    t(k + "order.collected", "Vous retirez {count} {item} pour {price} faveurs.", "You collect {count} {item} for {price} favours.")
    t(k + "order.noRoom", "Votre sac est plein.", "Your bag is full.")
    t(k + "order.notHere", "Ce village n'a pas de comptoir du marché.", "This village has no market counter.")
    # Dépôt-vente du marché intérieur.
    t(k + "consign.title", "Dépôt-vente de {village}", "{village} consignment")
    t(k + "consign.hint", "Ce que vous portez et que le village peut vendre. Le commis vend le tout au prochain "
                          "marchand de passage ou au jour de marché, et vous garde le produit.", "What you carry that the "
                          "village can sell. The clerk sells it all to the next visiting merchant or on market day, and "
                          "keeps the money for you.")
    t(k + "consign.sub", "{count} pour {price} faveurs", "{count} for {price} favours")
    t(k + "consign.none", "Vous ne portez rien que {village} puisse vendre.", "You carry nothing {village} can sell.")
    t(k + "consign.mine", "En dépôt : {count} {item}", "On consignment: {count} {item}")
    t(k + "consign.mineNone", "Rien en dépôt.", "Nothing on consignment.")
    t(k + "consign.rule", "Un dépôt à la fois, jusqu'à un chargement. Le produit des ventes se retire auprès du commis.",
      "One consignment at a time, up to one load. Collect the proceeds from the clerk.")
    t(k + "consign.placed", "Vous laissez {count} {item} au dépôt-vente ({price} faveurs à la vente).",
      "You leave {count} {item} on consignment ({price} favours when sold).")
    t(k + "consign.already", "Vous avez déjà des marchandises en dépôt ici.", "You already have goods on consignment here.")
    t(k + "consign.sold", "Le dépôt-vente de {village} a vendu vos {count} {item} : {price} faveurs vous attendent.",
      "The {village} consignment sold your {count} {item}: {price} favours are waiting for you.")
    t(k + "consign.proceeds", "Vous retirez {price} faveurs du dépôt-vente.", "You collect {price} favours from the consignment stall.")
    t(k + "consign.waiting", "Vos {count} {item} attendent encore un acheteur.", "Your {count} {item} are still waiting for a buyer.")
    t(k + "consign.returned", "Le dépôt-vente est fermé : le commis vous rend vos {count} {item}.",
      "The consignment stall is closed: the clerk gives back your {count} {item}.")
    t(k + "collect.nothing", "Je n'ai rien pour vous pour l'instant.", "I have nothing for you yet.")
    t(k + "depot.placed", "Le dépôt-vente du marché de {village} ouvre ses étagères.", "The {village} market consignment stall opens.")
    # Jours de marché du marché intérieur.
    t(k + "stand.day", "Jour de marché", "Market day")
    t(k + "stand.daySub", "Prix des artisans, aujourd'hui seulement", "Artisan prices, today only")
    t(k + "day.today", "C'est jour de marché à {village} : étals des artisans, prix bas et un lot de {luxury}.",
      "It is market day in {village}: artisan stalls, low prices and a lot of {luxury}.")
    t(k + "day.next", "Prochain jour de marché dans {days} jours.", "Next market day in {days} days.")
    # Marché noir.
    t(k + "noir.placed", "Un étal sombre s'installe au marché de {village}.", "A shady stall sets up at the {village} market.")
    t(k + "noir.closed", "Le receleur ne vient qu'à la nuit tombée ({hour} h).", "The fence only comes after nightfall ({hour}:00).")
    t(k + "stand.noir", "Marché noir", "Black market")
    t(k + "stand.noirSub", "La nuit seulement", "Night only")
    t(k + "stand.noirSale", "Sous le manteau", "Under the counter")
    t(k + "stand.noirFence", "Recel", "Buy-back")
    t("world.needs.reason.order", "un joueur l'a commandé au comptoir", "a player ordered it at the counter")


def dialogue_texts(did, npc_fr, npc_en, role_fr, role_en, lines):
    base = f"dialogue.{did}."
    t(base + "npc", npc_fr, npc_en)
    t(base + "role", role_fr, role_en)
    for key, (fr, en) in lines.items():
        t(base + key, fr, en)


def resp(did, key, effects=None, next_=None, conditions=None, fail=None, hint=None):
    r = {"TextKey": f"nakasar_peuples.dialogue.{did}.{key}"}
    if hint:
        r["HintKey"] = hint
    if conditions:
        r["Conditions"] = conditions
    if fail:
        r["FailKey"] = fail
    if effects:
        r["Effects"] = effects
    if next_:
        r["Next"] = next_
    return r


def market_action(action):
    return [{"Type": "WorldAction", "Action": action}]


def counter_dialogue(people):
    did = f"{people}_Comptoir"
    rank = lambda r: [{"Type": "Rank", "Min": r}]
    accueil = {
        "Id": "Accueil",
        "TextKey": f"nakasar_peuples.dialogue.{did}.accueil",
        "Responses": [
            resp(did, "voir", market_action("MarketCounterShop"), conditions=rank("Tolere"),
                 fail="nakasar_peuples.dialogue.fail.rank.Tolere"),
            resp(did, "commander", market_action("MarketOrder"), conditions=rank("Ami"),
                 fail="nakasar_peuples.dialogue.fail.rank.Ami"),
            resp(did, "retirer", market_action("MarketCollect"), conditions=rank("Tolere"),
                 fail="nakasar_peuples.dialogue.fail.rank.Tolere"),
            resp(did, "aurevoir"),
        ],
    }
    refus = {"Id": "Refus", "TextKey": f"nakasar_peuples.dialogue.{did}.refus", "Responses": [resp(did, "aurevoir")]}
    d = {
        "$Comment": "Le commis du comptoir du marché extérieur (place du marché, palier 2), généré par "
                    "tools/generate_markets.py : ce que le comptoir a acheté aux marchands de passage, les commandes du "
                    "joueur (effets WorldAction Market*, world/WorldDialogueTypes et world/Markets).",
        "NpcNameKey": f"nakasar_peuples.dialogue.{did}.npc",
        "RoleKey": f"nakasar_peuples.dialogue.{did}.role",
        "People": people,
        "Entries": [{"Node": "Refus", "Conditions": [{"Type": "Rank", "Max": "Etranger"}]}, {"Node": "Accueil"}],
        "Nodes": [accueil, refus],
    }
    fr_people, en_people = NAMES[people]
    dialogue_texts(did, "Commis du comptoir", "Counter clerk", "comptoir du marché", "market counter", {
        "accueil": ("Les marchands de passage me laissent ce qu'ils n'ont pas vendu, et je cherche pour vous ce qu'ils "
                    "n'ont pas apporté.", "Visiting merchants leave me what they didn't sell, and I look out for what "
                    "they didn't bring."),
        "refus": ("Le comptoir ne traite qu'avec les amis du village.", "The counter only deals with friends of the village."),
        "voir": ("Voir ce que les marchands ont laissé.", "See what the merchants left."),
        "commander": ("Passer une commande.", "Place an order."),
        "retirer": ("Où en est ma commande ?", "How is my order doing?"),
        "aurevoir": ("Au revoir.", "Goodbye."),
    })
    return d


def depot_dialogue(people):
    did = f"{people}_Depot"
    rank = lambda r: [{"Type": "Rank", "Min": r}]
    accueil = {
        "Id": "Accueil",
        "TextKey": f"nakasar_peuples.dialogue.{did}.accueil",
        "Responses": [
            resp(did, "deposer", market_action("MarketConsign"), conditions=rank("Tolere"),
                 fail="nakasar_peuples.dialogue.fail.rank.Tolere"),
            resp(did, "commander", market_action("MarketOrder"), conditions=rank("Ami"),
                 fail="nakasar_peuples.dialogue.fail.rank.Ami"),
            resp(did, "retirer", market_action("MarketCollect"), conditions=rank("Tolere"),
                 fail="nakasar_peuples.dialogue.fail.rank.Tolere"),
            resp(did, "aurevoir"),
        ],
    }
    refus = {"Id": "Refus", "TextKey": f"nakasar_peuples.dialogue.{did}.refus", "Responses": [resp(did, "aurevoir")]}
    d = {
        "$Comment": "Le commis du dépôt-vente du marché intérieur (place du marché, palier 2), généré par "
                    "tools/generate_markets.py : dépôt-vente, commandes aux marchands itinérants, retrait du produit des "
                    "ventes et des commandes (effets WorldAction Market*, world/WorldDialogueTypes et world/Markets).",
        "NpcNameKey": f"nakasar_peuples.dialogue.{did}.npc",
        "RoleKey": f"nakasar_peuples.dialogue.{did}.role",
        "People": people,
        "Entries": [{"Node": "Refus", "Conditions": [{"Type": "Rank", "Max": "Etranger"}]}, {"Node": "Accueil"}],
        "Nodes": [accueil, refus],
    }
    dialogue_texts(did, "Commis du dépôt-vente", "Consignment clerk", "dépôt-vente du marché", "market consignment", {
        "accueil": ("Laissez-moi vos marchandises : je les vends aux marchands de passage et aux jours de marché, et je "
                    "vous garde l'argent.", "Leave me your goods: I sell them to visiting merchants and on market days, "
                    "and keep the money for you."),
        "refus": ("Le dépôt-vente ne traite qu'avec les amis du village.", "The consignment stall only deals with friends of the village."),
        "deposer": ("Déposer des marchandises.", "Leave goods for sale."),
        "commander": ("Passer une commande aux marchands.", "Order from the merchants."),
        "retirer": ("Qu'avez-vous pour moi ?", "What do you have for me?"),
        "aurevoir": ("Au revoir.", "Goodbye."),
    })
    return d


SKIP_WORKSHOP = ("AP_", "LD_", "E1_", "E2_", "E3_", "Marche")
DAY_ROWS = 6  # plus le luxe : sept lignes, celles de l'échoppe (reputation/ShopPage ROWS)
TERROIR = {"Kweebec": "Plant_Fruit_Azure", "Trork": "Ingredient_Leather_Heavy", "Feran": "Ingredient_Spices",
           "Renonce": "Food_Cheese", "Slothian": "Ingredient_Crystal_Purple"}  # world/TradeGoods.LUXURIES
TERROIR_PRICE = 6


def day_shops(errors):
    """Étal du jour de marché : la première offre de chaque bâtiment de métier du village, moins chère, en double."""
    for people in PEOPLES:
        slots = []
        prefix = f"Nakasar_Peuples_W_{people}_"
        for path in sorted(BARTER.glob(prefix + "*_Base_1.json")):
            wid = path.name[len(prefix):-len("_Base_1.json")]
            if wid.startswith(SKIP_WORKSHOP) or len(slots) >= DAY_ROWS:
                continue
            shop = json.loads(path.read_text(encoding="utf-8"))
            trades = [s["Trade"] for s in shop["TradeSlots"] if s.get("Trade")]
            if not trades:
                continue
            tr = trades[0]
            out = dict(tr["Output"])
            inputs = [dict(i) for i in tr["Input"]]
            if inputs[0]["Quantity"] > 1:
                inputs[0]["Quantity"] -= 1
            else:
                out["Quantity"] *= 2
            if any(s["Trade"]["Output"]["ItemId"] == out["ItemId"] for s in slots):
                continue
            slots.append({"Type": "Fixed", "Trade": {"Output": out, "Input": inputs, "Stock": tr.get("Stock", 6) * 2}})
        if not slots:
            errors.append(f"aucune boutique de bâtiment pour l'étal du jour {people} (tools/generate_workshops.py)")
        slots.append({"Type": "Fixed", "Trade": {
            "Output": {"ItemId": TERROIR[people], "Quantity": 1},
            "Input": [{"ItemId": f"Nakasar_Peuples_Favor_{people}", "Quantity": TERROIR_PRICE}], "Stock": 2}})
        sid = f"Marche_{people}_Jour"
        (BARTER / f"Nakasar_Peuples_{sid}.json").write_text(json.dumps({
            "$Comment": f"L'étal du jour de marché des villages {people} (marché intérieur, un jour sur sept), généré "
                        "par tools/generate_markets.py : la première offre de chaque bâtiment de métier, une faveur de "
                        "moins (ou le double pour une faveur), stock doublé, et un lot du luxe de terroir.",
            "DisplayNameKey": f"nakasar_peuples.barter.{sid}.title",
            "RefreshInterval": {"Days": 7},
            "RestockHour": 6,
            "TradeSlots": slots,
        }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        t(f"barter.{sid}.title", "Jour de marché", "Market day")


# ---- Marché noir ----
LUX_ALL = ["Plant_Fruit_Azure", "Ingredient_Leather_Heavy", "Ingredient_Spices", "Food_Cheese", "Ingredient_Crystal_Purple",
           "Ingredient_Bar_Gold", "Ingredient_Bolt_Silk", "Food_Fish_Raw_Rare", "Food_Pie_Apple"]  # world/TradeGoods.LUXURIES
BLACK_LUX_PRICE = 9          # un luxe d'ailleurs, sans route ni terroir : trois fois le prix du jour de marché
BLACK_EXTRA = {
    # Le métal que les Kweebecs refusent d'échanger (world/TradeGoods.isMetal).
    "Kweebec": [("Ingredient_Bar_Iron", 2, 4), ("Ingredient_Bar_Copper", 2, 3), ("Tool_Pickaxe_Iron", 1, 8)],
    "Trork": [("Weapon_Sword_Iron", 1, 8)],
    "Feran": [("Ingredient_Bar_Silver", 1, 6)],
    "Renonce": [("Ingredient_Bar_Thorium", 1, 8)],
    "Slothian": [("Ingredient_Bar_Silver", 1, 6)],
}
# Le butin des raids que le receleur rachète, par peuple (assaillants : tools/generate_raids.py, generate_zones.py,
# generate_slothians.py) : (objet, nombre, faveurs).
RECEL = {
    "Kweebec": [("Weapon_Battleaxe_Stone_Trork", 1, 2), ("Weapon_Mace_Crude", 1, 1), ("Weapon_Club_Crude", 1, 1),
                ("Ingredient_Hide_Medium", 4, 1), ("Ingredient_Bone_Fragment", 8, 1)],
    "Trork": [("Ingredient_Hide_Light", 6, 1), ("Ingredient_Hide_Medium", 4, 1), ("Ingredient_Bone_Fragment", 8, 1),
              ("Weapon_Daggers_Bone", 1, 1)],
    "Feran": [("Ingredient_Chitin_Sturdy", 4, 1), ("Ingredient_Bone_Fragment", 8, 1), ("Ingredient_Hide_Scaled", 4, 1)],
    "Renonce": [("Ingredient_Hide_Dark", 4, 1), ("Ingredient_Ice_Essence", 4, 1), ("Ingredient_Bone_Fragment", 8, 1)],
    "Slothian": [("Ingredient_Void_Essence", 4, 1), ("Ingredient_Voidheart", 1, 3), ("Ingredient_Bone_Fragment", 8, 1)],
}


def black_shops(errors, items):
    for people in PEOPLES:
        fav = f"Nakasar_Peuples_Favor_{people}"
        sale = [{"Type": "Fixed", "Trade": {"Output": {"ItemId": item, "Quantity": 1},
                                            "Input": [{"ItemId": fav, "Quantity": BLACK_LUX_PRICE}], "Stock": 1}}
                for item in LUX_ALL if item != TERROIR[people]][:7 - len(BLACK_EXTRA[people])]
        sale += [{"Type": "Fixed", "Trade": {"Output": {"ItemId": item, "Quantity": n},
                                             "Input": [{"ItemId": fav, "Quantity": price}], "Stock": 2}}
                 for item, n, price in BLACK_EXTRA[people]]
        buy = [{"Type": "Fixed", "Trade": {"Output": {"ItemId": fav, "Quantity": price},
                                           "Input": [{"ItemId": item, "Quantity": n}], "Stock": 6}}
               for item, n, price in RECEL[people]]
        for kind, slots, fr, en, comment in (
                ("Noir", sale, "Sous le manteau", "Under the counter",
                 "ce que le peuple refuse d'échanger : luxes d'ailleurs sans route ni terroir, métal, au prix fort"),
                ("Recel", buy, "Recel du butin", "Loot buy-back",
                 "le butin des assaillants des raids, racheté contre des faveurs du village")):
            for s in slots:
                for st in [s["Trade"]["Output"]] + s["Trade"]["Input"]:
                    if not st["ItemId"].startswith("Nakasar_") and st["ItemId"] not in items:
                        errors.append(f"marché noir {people} : objet vanilla inconnu {st['ItemId']}")
            sid = f"Marche_{people}_{kind}"
            (BARTER / f"Nakasar_Peuples_{sid}.json").write_text(json.dumps({
                "$Comment": f"Marché noir des villages {people} (receleur de la place du marché, la nuit), généré par "
                            f"tools/generate_markets.py : {comment}.",
                "DisplayNameKey": f"nakasar_peuples.barter.{sid}.title",
                "RefreshInterval": {"Days": 3},
                "RestockHour": 20,
                "TradeSlots": slots,
            }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            t(f"barter.{sid}.title", fr, en)


def black_dialogue(people):
    did = f"{people}_Noir"
    rank = lambda r: [{"Type": "Rank", "Min": r}]
    accueil = {
        "Id": "Accueil",
        "TextKey": f"nakasar_peuples.dialogue.{did}.accueil",
        "Responses": [
            resp(did, "voir", market_action("MarketBlackShop"), conditions=rank("Tolere"),
                 fail="nakasar_peuples.dialogue.fail.rank.Tolere"),
            resp(did, "vendre", market_action("MarketFenceShop"), conditions=rank("Tolere"),
                 fail="nakasar_peuples.dialogue.fail.rank.Tolere"),
            resp(did, "retirer", market_action("MarketCollect")),
            resp(did, "aurevoir"),
        ],
    }
    d = {
        "$Comment": "Le receleur du marché noir (place du marché, palier 2), la nuit seulement, généré par "
                    "tools/generate_markets.py (effets WorldAction MarketBlackShop, MarketFenceShop, MarketCollect). "
                    "Aucune perte de réputation (Kevin, 21 novembre 2026).",
        "NpcNameKey": f"nakasar_peuples.dialogue.{did}.npc",
        "RoleKey": f"nakasar_peuples.dialogue.{did}.role",
        "People": people,
        "Entries": [{"Node": "Accueil"}],
        "Nodes": [accueil],
    }
    dialogue_texts(did, "Receleur", "Fence", "marché noir", "black market", {
        "accueil": ("Pas si fort... Ce que le village ne vend pas, moi je l'ai. Et ce que vous avez pris aux pillards, je "
                    "le rachète.", "Not so loud... What the village won't sell, I have. And what you took from the "
                    "raiders, I buy back."),
        "voir": ("Qu'avez-vous sous le manteau ?", "What do you have under the counter?"),
        "vendre": ("J'ai du butin à vendre.", "I have loot to sell."),
        "retirer": ("Le commis m'a laissé quelque chose ?", "Did the clerk leave something for me?"),
        "aurevoir": ("Je n'ai rien vu.", "I saw nothing."),
    })
    return d


def guest_dialogue():
    did = "Monde_Marchand_Etal"
    d = {
        "$Comment": "Un marchand itinérant posté à l'étal de passage d'une place du marché, généré par "
                    "tools/generate_markets.py (effets WorldAction MarketGuestShop et Rumour, world/WorldDialogueTypes).",
        "NpcNameKey": f"nakasar_peuples.dialogue.{did}.npc",
        "RoleKey": f"nakasar_peuples.dialogue.{did}.role",
        "Entries": [{"Node": "Accueil"}],
        "Nodes": [{
            "Id": "Accueil",
            "TextKey": f"nakasar_peuples.dialogue.{did}.accueil",
            "Responses": [
                resp(did, "troc", market_action("MarketGuestShop")),
                resp(did, "rumeur", [{"Type": "WorldAction", "Action": "Rumour"}]),
                resp(did, "aurevoir"),
            ],
        }],
    }
    dialogue_texts(did, "Marchand de passage", "Visiting merchant", "étal de passage", "visiting stall", {
        "accueil": ("Bienvenue à mon étal ! Je viens de loin et je repars au matin.",
                    "Welcome to my stall! I come from afar and leave at dawn."),
        "troc": ("Montrez-moi vos marchandises.", "Show me your wares."),
        "rumeur": ("Quelles nouvelles de la route ?", "Any news from the road?"),
        "aurevoir": ("Au revoir.", "Goodbye."),
    })
    return d


def counter_shops(errors):
    """Comptoir de l'hôte : les marchandises des voyageurs de chaque autre peuple, payées en faveurs de l'hôte."""
    for old in list(BARTER.glob("Nakasar_Peuples_Comptoir_*.json")) + list(BARTER.glob("Nakasar_Peuples_Marche_*_*.json")):
        old.unlink()
    for origin in PEOPLES:
        path = BARTER / f"Nakasar_Peuples_Travel_{origin}.json"
        if not path.exists():
            errors.append(f"boutique des voyageurs absente : {path.name} (tools/generate_world_life.py)")
            continue
        travel = json.loads(path.read_text(encoding="utf-8"))
        for host in PEOPLES:
            if host == origin:
                continue
            slots = []
            for slot in travel["TradeSlots"]:
                trade = slot.get("Trade")
                if trade is None or trade["Output"]["ItemId"].startswith("Nakasar_"):
                    continue  # la faveur du peuple d'origine ne se revend pas
                item = trade["Output"]["ItemId"]
                if host == "Kweebec" and is_metal(item):
                    continue
                price = sum(i["Quantity"] for i in trade["Input"])
                slots.append({"Type": "Fixed", "Trade": {
                    "Output": dict(trade["Output"]),
                    "Input": [{"ItemId": f"Nakasar_Peuples_Favor_{host}", "Quantity": price}],
                    "Stock": max(1, (trade.get("Stock", 6) + 1) // 2)}})
            sid = f"Comptoir_{host}_{origin}"
            (BARTER / f"Nakasar_Peuples_{sid}.json").write_text(json.dumps({
                "$Comment": f"Ce que le comptoir du marché {host} revend des marchands {origin} de passage, généré par "
                            "tools/generate_markets.py : marchandises des voyageurs de l'origine, au même prix, payées en "
                            "faveurs de l'hôte ; la moitié du stock (le comptoir n'achète que le reste d'un étal).",
                "DisplayNameKey": f"nakasar_peuples.barter.{sid}.title",
                "RefreshInterval": {"Days": 2},
                "RestockHour": 6,
                "TradeSlots": slots,
            }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            t(f"barter.{sid}.title", f"Comptoir : marchandises {NAMES[origin][0]}s", f"Counter: {NAMES[origin][1]} wares")


METALS = ("Iron", "Copper", "Gold", "Silver", "Thorium", "Cobalt", "Adamantite", "Mithril", "Steel", "Bronze")


def is_metal(item):
    """Même règle que world/TradeGoods.isMetal (les Kweebecs n'échangent aucun métal)."""
    if item.startswith(("Ore_", "Ingredient_Bar_", "Ingredient_Stud_")):
        return True
    return item.startswith(("Tool_", "Weapon_", "Armor_", "Rock_Gold")) and any(m in item for m in METALS)


def write_langs():
    for locale, table in (("fr-FR", FR), ("en-US", EN)):
        path = RES / f"Languages/{locale}/nakasar_peuples.lang"
        text = path.read_text(encoding="utf-8")
        block_text = "\n".join([LANG_BEGIN] + [f"{k} = {v}" for k, v in table.items()] + [LANG_END]) + "\n"
        if LANG_BEGIN in text:
            before, rest = text.split(LANG_BEGIN, 1)
            after = rest.split(LANG_END, 1)[1].lstrip("\n")
            text = before + block_text + after
        else:
            existing = {line.split("=", 1)[0].strip() for line in text.splitlines() if "=" in line and not line.startswith("#")}
            dup = sorted(k for k in table if k in existing)
            if dup:
                raise SystemExit(f"clés déjà présentes dans {locale} : {dup[:10]}")
            text = text.rstrip("\n") + "\n" + block_text
        path.write_text(text, encoding="utf-8")
        print(f"{path.relative_to(ROOT)} : {len(table)} clés")


def main():
    known = {line.strip().lstrip("*") for line in BLOCKS.read_text(encoding="utf-8").splitlines() if line.strip()}
    errors = []
    OUT.mkdir(parents=True, exist_ok=True)
    docs = {}
    for people in PEOPLES:
        s = STYLE[people]
        docs[f"{people}_Marche"] = market(people, 1)
        for tier in (2, 3, 4):
            docs[f"{people}_Marche_T{tier}"] = market(people, tier)
        docs[f"Etal_{people}"] = doc(stall(0, 0, s))
        docs[f"{people}_Comptoir"] = doc(stall(0, 0, s, wide=True))
        docs[f"{people}_Depot"] = doc(depot(s))
        docs[f"{people}_Noir"] = doc(black_stall(s))
    for name, d in docs.items():
        seen = set()
        for b in d["blocks"]:
            if b["name"].lstrip("*") not in known:
                errors.append(f"{name} : bloc vanilla inconnu {b['name']}")
            cell = (b["x"], b["y"], b["z"])
            if cell in seen:
                errors.append(f"{name} : deux blocs en {cell}")
            seen.add(cell)
        (OUT / f"{name}.prefab.json").write_text(json.dumps(d, indent=2) + "\n", encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)} : {len(docs)} prefabs")
    texts()
    counter_shops(errors)
    day_shops(errors)
    black_shops(errors, {line.strip().lstrip("*") for line in ITEMS.read_text(encoding="utf-8").splitlines() if line.strip()})
    for people in PEOPLES:
        (DIALOGUES / people / f"{people}_Noir.json").write_text(
            json.dumps(black_dialogue(people), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        path = DIALOGUES / people / f"{people}_Depot.json"
        path.write_text(json.dumps(depot_dialogue(people), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        path = DIALOGUES / people / f"{people}_Comptoir.json"
        path.write_text(json.dumps(counter_dialogue(people), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (DIALOGUES / "Monde" / "Monde_Marchand_Etal.json").write_text(
        json.dumps(guest_dialogue(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if errors:
        for e in errors:
            print("ERREUR", e)
        raise SystemExit(1)
    write_langs()


if __name__ == "__main__":
    main()
