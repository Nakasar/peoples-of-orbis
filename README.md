# Peuples d'Orbis

Mod **Hytale** (serveur `0.7.x`) de Nakasar : des villages vivants des peuples d'Orbis (Kweebecs, Trorks, Feran,
Renoncés, Slothians) qui grandissent avec l'aide du joueur, façon Millénaire. Tableau des demandes, dépôt, dialogues,
réputation par village et par peuple, échoppes de troc par rang, raids, chaînes de quêtes et zones de progression.

- Guide du joueur : **https://orbis.nakasar.xyz** (sources dans [docs/wiki](docs/wiki/), site Docusaurus dans [wiki](wiki/))
- Conception et détail technique du mod : [docs/conception/mods.md](docs/conception/mods.md),
  [zones 2 et 3](docs/conception/zones-2-3.md), [zone 4](docs/conception/zone-4.md)
- Notes d'API serveur et de PNJ (communes aux mods Nakasar) : [docs/api-0.7.2.md](docs/api-0.7.2.md),
  [docs/npc-0.7.2.md](docs/npc-0.7.2.md), [docs/npc-reference-0.7.2.md](docs/npc-reference-0.7.2.md)
- Consignes permanentes (régénération des mobs, assets vanilla d'abord, zones rouges) : [AGENTS.md](AGENTS.md)

Le mod a été développé dans le dépôt [Nakasar/hytale-modding](https://github.com/Nakasar/hytale-modding) (avec les
mods Vampires et Fractales) puis déplacé ici avec son historique git. Les numéros de PR cités dans les anciens
messages de commit sont ceux de hytale-modding.

## Contenu du dépôt

| Dossier | Contenu |
|---------|---------|
| `src/main/java` | Plugin `dev.nakasar.hytale.peuples.PeuplesPlugin` |
| `src/main/resources` | Pack d'assets embarqué dans le jar (`manifest.json` : `Nakasar:Peuples`) |
| `tools/` | Générateurs Python (villages, bâtiments, PNJ, quêtes, zones...), validateurs Java, listes d'ids vanilla |
| `docs/conception/` | Conception du mod (étapes, choix, réglages) |
| `docs/wiki/` | Pages Markdown du wiki joueur (`README.md` = accueil) et leurs images |
| `wiki/` | Site Docusaurus du wiki |
| `.github/workflows/` | CI : `build` (jar + validateurs), `wiki` (GitHub Pages), `release` (release sur étiquette) |

## Prérequis

- **JDK 25**. Le build utilise une *toolchain* Gradle : si aucun JDK 25 n'est installé, le plugin `foojay-resolver`
  le télécharge.
- Le wrapper Gradle (`./gradlew`) télécharge Gradle 9.8.1 ; l'artefact serveur `com.hypixel.hytale:Server` (version
  `hytale_version` dans `gradle.properties`) vient du Maven officiel `https://maven.hytale.com/release`.
- Pour le wiki : Node 20 ou plus.

## Construire

```bash
./gradlew build
```

Produit `build/libs/Nakasar-Peuples-<version>.jar`. `build` lance aussi les validateurs :

- `validateJson` : syntaxe de tous les JSON d'assets (le parseur du serveur est strict) ;
- `checkSystemRegistrations` : aucune classe de système enregistrée deux fois (le plugin entier échouerait) ;
- `validateRoles` (`tools/ValidateRoles.java`) : rôles de PNJ chargés par le BuilderManager du serveur ;
- `validateAssets` (`tools/ValidateAssets.java`) : chaque asset décodé avec les codecs du serveur, ids de blocs des
  prefabs, références vanilla (`tools/vanilla-*.txt`), sons stéréo, textures et syntaxe des pages `.ui` (ids `#Nom` en lettres et chiffres seulement, accolades équilibrées).

Un id vanilla que le validateur ne connaît pas s'ajoute dans `tools/vanilla-refs-0.7.2.txt`, uniquement après l'avoir
vu dans les fichiers du jeu.

## Déployer sur un serveur local

Indiquez le dossier `mods/` de votre serveur dans `gradle-local.properties` (non versionné) :

```properties
hytale.modsDir=/chemin/vers/Server/mods
```

puis `./gradlew deployToServer` (ou `./gradlew deployToServer -Phytale.modsDir=/chemin/vers/Server/mods`). Dans un
monde solo, activez **Peuples** dans la liste des mods du monde.

## Wiki

```bash
cd wiki && npm ci && npm start     # aperçu local
npm run build                      # construction (liens et ancres cassés refusés)
```

Les icônes du pack (`src/main/resources/Common/Icons` et `Common/UI`) sont copiées dans `wiki/static/jeu/` avant
chaque construction ; les pages les citent en `/jeu/...`. Pour ajouter une rubrique : la barre latérale
`wiki/sidebars.js` et la barre de sections `wiki/src/mods.js`. Les anciennes adresses `/peuples/...` du wiki commun
redirigent vers les pages de ce site.

Le workflow `wiki` publie le site sur GitHub Pages à chaque push sur `main` (dossier `wiki/static/CNAME` :
`orbis.nakasar.xyz`).

## CI/CD

| Workflow | Déclencheur | Rôle |
|----------|-------------|------|
| `build` | PR et push sur `main` | `./gradlew build` (compilation + validateurs), artefact `Nakasar-Peuples` (le jar) |
| `wiki` | PR et push sur `main` touchant le wiki ou les icônes | construit le wiki ; sur `main`, le publie sur GitHub Pages |
| `release` | étiquette `v*` | construit le jar à la version de l'étiquette et crée la release GitHub avec le jar |

Publier une version :

```bash
git tag v0.2.0 && git push origin v0.2.0
```

## Dépendances avec les autres mods Nakasar

Aucune dépendance de code. Quelques fichiers ont été copiés du mod Fractales (dépôt hytale-modding) et vivent ici :
les icônes d'hypothermie et de chaleur (`tools/generate_zones.py` ne les recopie que si `hytale-modding` est cloné à
côté de ce dépôt) et la forme des zones d'annonce (`tools/generate_quests.py`). Le `NoRegenSystem` du mod Vampires
laisse les rôles `Nakasar_Peuples_*` à ce mod.
