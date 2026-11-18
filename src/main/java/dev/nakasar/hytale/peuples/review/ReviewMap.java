package dev.nakasar.hytale.peuples.review;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import dev.nakasar.hytale.peuples.village.Blocks;
import dev.nakasar.hytale.peuples.village.Terrain;
import dev.nakasar.hytale.peuples.workshop.Workshops;
import dev.nakasar.hytale.peuples.village.VillageBuilder;
import org.joml.Vector3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Carte plate de revue des bâtiments (Kevin, 16 novembre 2026, « parking à idées » du tableau blanc, puis ses retours
 * du même jour) : tous les bâtiments des cinq peuples (cœur, base, artisan, projets, production, ouvrages, lieux-dits) en leurs
 * quatre paliers, et les structures du monde vivant (auberges, campements, vestiges, Source de l'Écho), chacun dans
 * son rectangle liseré de laine rouge sur un monde plat. Kevin y retouche les prefabs à la main en créatif.
 *
 * <ul>
 *   <li>Les rectangles d'un même bâtiment ont tous la même taille (la plus grande de ses paliers), le palier centré
 *   dedans ; les paliers vont d'ouest en est, les bâtiments du nord au sud, une colonne par peuple.</li>
 *   <li>Rien autour des bâtiments : le palier se voit au bâtiment lui-même (prefab plus grand de la même famille).</li>
 *   <li>Un panneau muet dans le coin sud-ouest de chaque rectangle : l'utiliser affiche le bâtiment et son palier
 *   ({@link ReviewSignInteraction}).</li>
 * </ul>
 *
 * <p>Les compositions viennent de {@code nakasar-peuples/revue-batiments.json}, écrit par
 * {@code tools/building_tiers.py}. Les tailles se mesurent ici sur les prefabs chargés, une fois par monde : le plan
 * obtenu est gardé dans {@value #PLAN_FILE} (dossier du monde) pour qu'un prefab retouché puis rechargé ne décale
 * jamais la carte. Un rectangle n'est bâti qu'une fois (liste dans {@value #STATE_FILE}). Un seul monde de revue,
 * {@value #WORLD_DEFAULT} : il est écrasé par un monde neuf quand la carte change (empreinte dans
 * {@value #SIGNATURE_FILE}) ou sur demande ({@code --rebatir}).
 */
public final class ReviewMap {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String RESOURCE = "/nakasar-peuples/revue-batiments.json";
    public static final String WORLD_DEFAULT = "nakasar_revue_batiments";
    static final String STATE_FILE = "nakasar_revue_batiments.txt";
    static final String PLAN_FILE = "nakasar_revue_batiments_plan.txt";
    static final String SIGNATURE_FILE = "nakasar_revue_batiments_carte.txt";
    /**
     * Panneaux de métier tels que la carte les a posés : « clé x y z lacet ox oz x0 x1 z0 z1 haut bloc » (place, origine
     * et rectangle du palier) ; le relevé s'en sert seul, même quand la carte a changé depuis.
     */
    static final String BOARDS_FILE = "nakasar_revue_panneaux.txt";
    /**
     * Relevé des panneaux déplacés à la main sur la carte (Kevin, 17 novembre 2026 : « le placement à la main du panneau
     * va consister à le déplacer physiquement à la main sur la carte de revue »), gardé HORS du monde de revue pour
     * survivre à son écrasement : à copier dans le dépôt ({@code tools/panneaux-releves.json}), que lit
     * {@code tools/building_growth.py}.
     */
    public static final String CAPTURE_FILE = "panneaux-releves.json";
    public static final String KEYS = "nakasar_peuples.review.";
    /** Haut du sol du monde plat (couches ci-dessous : pierre, terre, herbe). */
    static final int GROUND = 80;
    static final String GRASS = "Soil_Grass";
    /** Liseré des rectangles (Kevin : laine rouge). */
    static final String BORDER = "Cloth_Block_Wool_Red";
    /**
     * Liseré d'un palier dont la version ne tient pas au village (Kevin, 17 novembre 2026 : laine bleue) : le village
     * garde une version plus petite ({@code tools/building_growth.py}).
     */
    static final String BORDER_INCOMPATIBLE = "Cloth_Block_Wool_Blue";
    /**
     * Tracé au sol de la place libre du palier au village (Kevin, 17 novembre 2026) : une retouche qui tient dedans est
     * valide en jeu. Côté non tracé : la place continue au-delà du rectangle. Jamais enregistré dans un prefab.
     */
    public static final String ZONE = "Cloth_Block_Wool_Yellow";
    public static final String SIGN = "Nakasar_Peuples_Review_Sign";
    /** Herbe libre entre le liseré et le bâtiment (le panneau y tient). */
    static final int MARGIN = 3;
    /** Herbe entre deux rectangles d'une rangée, entre deux rangées, entre deux catégories, entre deux peuples. */
    static final int GAP = 3;
    static final int ROW_GAP = 4;
    static final int CATEGORY_GAP = 10;
    static final int SECTION_GAP = 16;
    /** Hauteur dégagée au-dessus du plus haut bloc mesuré quand on bâtit un rectangle. */
    static final int CLEAR_ABOVE = 12;
    private static final int REPORT_EVERY = 25;

    private static Data data;
    private static String signature;
    /** Version du calcul des rectangles, comptée dans {@link #signature()} (3 : zone allouée = rectangle rouge). */
    private static final int LAYOUT_VERSION = 3;
    private static final Map<String, Layout> LAYOUTS = new HashMap<>();
    private static boolean running;

    private ReviewMap() {
    }

    // ---- Données ----

    static final class Data {
        @SerializedName("World")
        String world;
        @SerializedName("Sections")
        List<Section> sections;
    }

    static final class Section {
        @SerializedName("People")
        String people;
        @SerializedName("Name")
        String name;
        @SerializedName("Rows")
        List<Row> rows;
    }

    static final class Row {
        @SerializedName("Category")
        String category;
        @SerializedName("CategoryName")
        String categoryName;
        @SerializedName("Id")
        String id;
        @SerializedName("Name")
        String name;
        @SerializedName("Source")
        String source;
        @SerializedName("Tiers")
        List<Tier> tiers;
    }

    static final class Tier {
        /** 1 à 4 ; 0 pour une structure sans paliers (auberge, campement, vestige). */
        @SerializedName("Tier")
        int tier;
        /** « chemin dx dy dz rotation » : ancre du prefab en (origine + dx, sol + dy, origine + dz). */
        @SerializedName("Pieces")
        List<String> pieces;
        /** « dx dy dz rotation Bloc », dy 0 = posé sur le sol, Empty = vider. */
        @SerializedName("Blocks")
        List<String> blocks;
        /** « dx dy dz Bloc » : retiré s'il est là (étapes du cœur). */
        @SerializedName("Clear")
        List<String> clear;
        @SerializedName("Notes")
        List<String> notes;
        /**
         * Emplacement réservé (largeur, profondeur) d'un bâtiment prévu qui n'a pas encore de prefab : rectangle vide à
         * son nom.
         */
        @SerializedName("Reserve")
        int[] reserve;
        /**
         * Panneau du bâtiment de métier là où il apparaît en jeu (Kevin, 17 novembre 2026) : « dx dz lacet Bloc »
         * relatif à l'origine, plaqué au mur ou entré dans le bâtiment comme {@link Workshops#boardSpot} ; avec
         * « Fixe » (avant-poste, sur sa fondation) posé tel quel sur le sol.
         */
        @SerializedName("Board")
        String board;
        /** Place libre au village « x0 x1 z0 z1 » relative à l'origine (tracée en {@link #ZONE}). */
        @SerializedName("Zone")
        String zone;
        /**
         * Vrai si {@link #zone} est la zone maximale fixée par le plan (Kevin, 18 novembre 2026) : le rectangle de la
         * carte s'agrandit pour la contenir en entier, on y construit le prefab à la main.
         */
        @SerializedName("ZoneMax")
        boolean zoneMax;
        /** Vrai si cette version ne tient pas au village (liseré bleu, raison dans les notes). */
        @SerializedName("Incompatible")
        boolean incompatible;
    }

    static synchronized Data data() {
        if (data == null) {
            try (InputStream in = ReviewMap.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("ressource absente " + RESOURCE);
                }
                data = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Data.class);
            } catch (IOException e) {
                throw new IllegalStateException("lecture de " + RESOURCE, e);
            }
        }
        return data;
    }

    /** Id du peuple (ou « Monde ») nommé `name`, null s'il n'a pas de colonne sur la carte. */
    public static String people(String name) {
        for (Section s : data().sections) {
            if (s.people.equalsIgnoreCase(name) || s.name.equalsIgnoreCase(name)) {
                return s.people;
            }
        }
        return null;
    }

    // ---- Plan de la carte ----

    /** Plan d'un monde de revue : les colonnes des peuples et chaque rectangle, rangé aussi par position de panneau. */
    public static final class Layout {
        final List<Column> columns = new ArrayList<>();
        final List<Spot> spots = new ArrayList<>();
        final Map<Long, Spot> signs = new HashMap<>();
    }

    static final class Column {
        Section section;
        int x0;
        int z0;
        /** Catégorie → z de sa première rangée. */
        final Map<String, Integer> categories = new LinkedHashMap<>();
    }

    /** Un rectangle : un palier d'un bâtiment. */
    static final class Spot {
        String key;
        Section section;
        Row row;
        Tier tier;
        /** Rectangle, liseré compris. */
        int x0, x1, z0, z1;
        /** Point (x, z) où tombe l'origine de la composition. */
        int ox, oz;
        int height;
        int signX, signZ;
    }

    /** Boîte mesurée d'une composition autour de son origine : x0, x1, z0, z1, haut. */
    private static int[] measure(Tier t) {
        int[] box = null;
        if (t.reserve != null && t.reserve.length == 2) {
            int w = t.reserve[0], d = t.reserve[1];
            box = new int[]{-w / 2, w - 1 - w / 2, -d / 2, d - 1 - d / 2, 1};
        }
        for (String line : t.pieces) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) {
                continue;
            }
            int[] b = VillageBuilder.bounds(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]));
            if (b == null) {
                LOGGER.atWarning().log("Nakasar Peuples : revue, prefab introuvable %s", p[0]);
                continue;
            }
            box = union(box, b[0], b[1], b[4], b[5], b[3]);
        }
        for (String line : t.blocks) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 3) {
                int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[2]);
                box = union(box, x, x, z, z, 1 + Integer.parseInt(p[1]));
            }
        }
        if (t.board != null) {
            String[] p = t.board.trim().split("\\s+");
            if (p.length >= 4) {
                int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[1]);
                box = union(box, x, x, z, z, 1);
            }
        }
        return box == null ? new int[]{-2, 2, -2, 2, 4} : box;
    }

    private static int[] union(int[] box, int x0, int x1, int z0, int z1, int top) {
        if (box == null) {
            return new int[]{x0, x1, z0, z1, top};
        }
        return new int[]{Math.min(box[0], x0), Math.max(box[1], x1), Math.min(box[2], z0), Math.max(box[3], z1),
                Math.max(box[4], top)};
    }

    private static String rowKey(Section s, Row r) {
        return s.people + "/" + r.category + "/" + r.id;
    }

    private static String tierKey(Section s, Row r, Tier t) {
        return rowKey(s, r) + "#" + t.tier;
    }

    /**
     * Plan du monde de revue `world`, calculé une fois (mesure des prefabs) puis relu de {@value #PLAN_FILE} : taille
     * de chaque bâtiment et place de chaque palier dans son rectangle. Les positions en découlent dans l'ordre du JSON.
     */
    public static synchronized Layout layout(World world) {
        Layout cached = LAYOUTS.get(world.getName());
        if (cached != null) {
            return cached;
        }
        Map<String, int[]> plan = loadPlan(world);
        boolean changed = false;
        Layout layout = new Layout();
        int x = 0;
        for (Section s : data().sections) {
            Column col = new Column();
            col.section = s;
            col.x0 = x;
            col.z0 = 0;
            int z = 0;
            int width = 0;
            String lastCategory = null;
            for (Row r : s.rows) {
                if (lastCategory != null && !lastCategory.equals(r.category)) {
                    z += CATEGORY_GAP;
                }
                lastCategory = r.category;
                col.categories.putIfAbsent(r.category, z);
                // Taille du bâtiment : la plus grande boîte de ses paliers ; place de chaque palier : centré.
                String rk = rowKey(s, r);
                int[] size = plan.get("R " + rk);
                if (size == null || r.tiers.stream().anyMatch(t -> !plan.containsKey("T " + tierKey(s, r, t)))) {
                    int w = 1, d = 1, h = 1;
                    int m = allocated(r) ? 0 : MARGIN;
                    List<int[]> boxes = new ArrayList<>();
                    for (Tier t : r.tiers) {
                        int[] b = withZone(measure(t), t);
                        boxes.add(b);
                        w = Math.max(w, b[1] - b[0] + 1);
                        d = Math.max(d, b[3] - b[2] + 1);
                        h = Math.max(h, b[4]);
                    }
                    int cw = w + 2 * m + 2, cd = d + 2 * m + 2;
                    size = new int[]{cw, cd, h};
                    plan.put("R " + rk, size);
                    for (int i = 0; i < r.tiers.size(); i++) {
                        int[] b = boxes.get(i);
                        int offX = 1 + m + (w - (b[1] - b[0] + 1)) / 2 - b[0];
                        int offZ = 1 + m + (d - (b[3] - b[2] + 1)) / 2 - b[2];
                        plan.put("T " + tierKey(s, r, r.tiers.get(i)), new int[]{offX, offZ});
                    }
                    changed = true;
                }
                int cw = size[0], cd = size[1];
                int cx = x;
                for (Tier t : r.tiers) {
                    int[] off = plan.get("T " + tierKey(s, r, t));
                    Spot spot = new Spot();
                    spot.key = tierKey(s, r, t);
                    spot.section = s;
                    spot.row = r;
                    spot.tier = t;
                    spot.x0 = cx;
                    spot.x1 = cx + cw - 1;
                    spot.z0 = z;
                    spot.z1 = z + cd - 1;
                    spot.ox = cx + off[0];
                    spot.oz = z + off[1];
                    spot.height = size[2];
                    // Zone allouée : le panneau se pose sur le liseré, pour laisser toute la zone libre à la construction.
                    boolean onBorder = allocated(r);
                    spot.signX = onBorder ? spot.x0 : spot.x0 + 1;
                    spot.signZ = onBorder ? spot.z1 : spot.z1 - 1;
                    layout.spots.add(spot);
                    layout.signs.put(pack(spot.signX, spot.signZ), spot);
                    cx += cw + GAP;
                }
                width = Math.max(width, cx - x);
                z += cd + ROW_GAP;
            }
            layout.columns.add(col);
            x += width + SECTION_GAP;
        }
        if (changed) {
            savePlan(world, plan);
        }
        LAYOUTS.put(world.getName(), layout);
        return layout;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Le rectangle dont le panneau est en (x, y, z) dans `world`, ou null. */
    static Spot spotAtSign(World world, int x, int y, int z) {
        if (y != GROUND + 1 || !world.getName().equals(worldName())) {
            return null;
        }
        return layout(world).signs.get(pack(x, z));
    }

    // ---- Monde ----

    public static String worldName() {
        return data().world == null ? WORLD_DEFAULT : data().world;
    }

    /** Le monde de revue est occupé : on ne l'écrase pas tant qu'un joueur s'y trouve. */
    public static final class OccupiedException extends RuntimeException {
        OccupiedException(String world) {
            super("monde de revue " + world + " occupé");
        }
    }

    /**
     * Empreinte de la carte (SHA-256 de {@code revue-batiments.json}) : un monde bâti d'après une autre carte est
     * écrasé (Kevin, 17 novembre 2026 : un seul monde {@value #WORLD_DEFAULT}, sans suffixe de version).
     */
    static synchronized String signature() {
        if (signature == null) {
            try (InputStream in = ReviewMap.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("ressource absente " + RESOURCE);
                }
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                md.update(in.readAllBytes());
                // Version du tracé : un changement de mesure des rectangles rebâtit aussi le monde.
                md.update(("layout:" + LAYOUT_VERSION).getBytes(StandardCharsets.UTF_8));
                byte[] hash = md.digest();
                signature = java.util.HexFormat.of().formatHex(hash, 0, 12);
            } catch (IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("empreinte de " + RESOURCE, e);
            }
        }
        return signature;
    }

    private static String savedSignature(Path dir) {
        try {
            Path f = dir.resolve(SIGNATURE_FILE);
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Le monde de revue {@value #WORLD_DEFAULT}, chargé tel quel s'il a été bâti d'après la carte actuelle, sinon
     * (carte changée, ancien monde sans empreinte) ou si `overwrite` : écrasé par un monde neuf (générateur plat
     * vanilla, créatif, sans apparition de PNJ). L'ancien dossier part dans le dossier des mondes supprimés du serveur.
     * Échoue avec {@link OccupiedException} si un joueur est dans le monde à écraser.
     */
    public static CompletableFuture<World> world(boolean overwrite) {
        String name = worldName();
        Universe universe = Universe.get();
        Path dir = universe.getWorldsPath().resolve(name);
        boolean current = !overwrite && signature().equals(savedSignature(dir));
        World loaded = universe.getWorld(name);
        if (loaded != null) {
            if (current) {
                return CompletableFuture.completedFuture(loaded);
            }
            if (loaded.getPlayerCount() > 0) {
                return CompletableFuture.failedFuture(new OccupiedException(name));
            }
            // Panneaux déplacés à la main relevés avant d'écraser le monde (le relevé est gardé hors du monde).
            return captureQuietly(loaded).thenCompose(v -> {
                if (loaded.getPlayerCount() > 0) {
                    return CompletableFuture.failedFuture(new OccupiedException(name));
                }
                LOGGER.atInfo().log("Nakasar Peuples : monde de revue %s écrasé (carte changée ou --rebatir)", name);
                universe.removeWorld(name);
                return replace(universe, name, dir);
            });
        } else if (universe.isWorldLoadable(name)) {
            if (current) {
                return universe.loadWorld(name);
            }
            return universe.loadWorld(name).thenCompose(old -> captureQuietly(old).thenCompose(v -> {
                LOGGER.atInfo().log("Nakasar Peuples : monde de revue %s écrasé (carte changée ou --rebatir)", name);
                universe.removeWorld(name);
                return replace(universe, name, dir);
            }));
        }
        return replace(universe, name, dir);
    }

    /** Relevé avant écrasement : une erreur de relevé ne bloque pas la nouvelle carte (elle est notée au journal). */
    private static CompletableFuture<Void> captureQuietly(World world) {
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        return capture(world).handle((r, e) -> {
            if (e != null) {
                LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, relevé des panneaux avant écrasement raté");
            }
            return null;
        });
    }

    /** Le monde de revue remplacé par un monde neuf : l'ancien dossier part dans les mondes supprimés du serveur. */
    private static CompletableFuture<World> replace(Universe universe, String name, Path dir) {
        synchronized (ReviewMap.class) {
            LAYOUTS.remove(name);
            PLACED.remove(name);
        }
        if (Files.exists(dir)) {
            try {
                Path trash = universe.getWorldsDeletedPath();
                Files.createDirectories(trash);
                Files.move(dir, trash.resolve(name + "_" + System.currentTimeMillis()));
            } catch (IOException e) {
                LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, ancien monde %s non déplacé", dir);
                return CompletableFuture.failedFuture(e);
            }
        }
        return create(universe, name).thenApply(world -> {
            writeLines(worldFile(world, SIGNATURE_FILE), List.of(signature()));
            return world;
        });
    }

    private static CompletableFuture<World> create(Universe universe, String name) {
        String env = Environment.UNKNOWN.getId();
        FlatWorldGenProvider flat = new FlatWorldGenProvider(FlatWorldGenProvider.DEFAULT_TINT, new FlatWorldGenProvider.Layer[] {
                new FlatWorldGenProvider.Layer(0, GROUND - 3, env, "Rock_Stone"),
                new FlatWorldGenProvider.Layer(GROUND - 3, GROUND, env, "Soil_Dirt"),
                new FlatWorldGenProvider.Layer(GROUND, GROUND + 1, env, GRASS)});
        WorldConfig config = new WorldConfig();
        config.setWorldGenProvider(flat);
        config.setDisplayName("Revue des bâtiments (Peuples d'Orbis)");
        config.setGameMode(com.hypixel.hytale.protocol.GameMode.Creative);
        config.setSpawningNPC(false);
        config.setPvpEnabled(false);
        config.setFallDamageEnabled(false);
        config.markChanged();
        Path path = universe.validateWorldPath(name);
        LOGGER.atInfo().log("Nakasar Peuples : création du monde de revue %s", name);
        return universe.makeWorld(name, path, config);
    }

    /**
     * Envoie le joueur au nord de la colonne du peuple `people` (null : la première), ou devant sa catégorie
     * `category` (préfixe d'id, null : la première rangée). Faux si le peuple n'est pas sur la carte.
     */
    public static boolean teleport(Ref<EntityStore> ref, Store<EntityStore> store, World target, String people, String category) {
        Layout layout = layout(target);
        Column col = null;
        for (Column c : layout.columns) {
            if (people == null || c.section.people.equalsIgnoreCase(people)) {
                col = c;
                break;
            }
        }
        if (col == null) {
            return false;
        }
        int z = col.z0;
        if (category != null) {
            String want = category.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace(" ", "");
            for (Map.Entry<String, Integer> e : col.categories.entrySet()) {
                String id = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (id.startsWith(want) || want.startsWith(id)) {
                    z = e.getValue();
                    break;
                }
            }
        }
        Teleport tp = Teleport.createForPlayer(target, new Vector3d(col.x0 + 2.5, GROUND + 3, z - 3.5),
                new Rotation3f(0f, (float) Math.PI, 0f));
        store.putComponent(ref, Teleport.getComponentType(), tp);
        return true;
    }

    // ---- Construction ----

    /**
     * Bâtit dans `world` les rectangles du peuple `people` (null : toute la carte) pas encore faits, ou tous si
     * `rebuild`. Un seul chantier à la fois ; les rectangles se font l'un après l'autre (chargement des chunks puis
     * collage sur le thread du monde). Les messages vont à `player`.
     */
    public static boolean build(World world, String people, boolean rebuild, PlayerRef player) {
        synchronized (ReviewMap.class) {
            if (running) {
                return false;
            }
            running = true;
        }
        try {
            Set<String> done = loadState(world);
            Deque<Spot> queue = new ArrayDeque<>();
            for (Spot s : layout(world).spots) {
                if ((people == null || s.section.people.equalsIgnoreCase(people)) && (rebuild || !done.contains(s.key))) {
                    queue.add(s);
                }
            }
            int total = queue.size();
            player.sendMessage(Message.translation(KEYS + "start").param("count", total));
            if (total == 0) {
                finish();
                return true;
            }
            world.execute(() -> next(world, queue, done, total, player));
            return true;
        } catch (RuntimeException e) {
            finish();
            throw e;
        }
    }

    private static void finish() {
        synchronized (ReviewMap.class) {
            running = false;
        }
    }

    private static void next(World world, Deque<Spot> queue, Set<String> done, int total, PlayerRef player) {
        Spot s = queue.poll();
        if (s == null) {
            finish();
            saveState(world, done);
            player.sendMessage(Message.translation(KEYS + "done").param("count", total));
            return;
        }
        int pad = 24; // prefabs plus larges que leurs bornes mesurées (pièces enterrées, arbres)
        Terrain.load(world, s.x0 - pad, s.x1 + pad, s.z0 - pad, s.z1 + pad).whenComplete((v, error) ->
                world.execute(() -> {
                    try {
                        if (error != null) {
                            LOGGER.atWarning().withCause(error).log("Nakasar Peuples : revue, chunks de %s non chargés", s.key);
                        } else {
                            buildSpot(world, s);
                            done.add(s.key);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, rectangle %s raté", s.key);
                    }
                    int left = queue.size();
                    if ((total - left) % REPORT_EVERY == 0) {
                        saveState(world, done);
                        player.sendMessage(Message.translation(KEYS + "progress").param("done", total - left).param("count", total));
                    }
                    next(world, queue, done, total, player);
                }));
    }

    private static void buildSpot(World world, Spot s) {
        ChunkStore chunks = world.getChunkStore();
        Store<EntityStore> store = world.getEntityStore().getStore();
        // Rectangle : air au-dessus, herbe dedans, liseré de laine rouge au bord (bleue si le palier ne tient pas au village).
        String border = s.tier.incompatible ? BORDER_INCOMPATIBLE : BORDER;
        for (int x = s.x0; x <= s.x1; x++) {
            for (int z = s.z0; z <= s.z1; z++) {
                for (int y = GROUND + 1; y <= GROUND + s.height + CLEAR_ABOVE; y++) {
                    if (!Blocks.isEmpty(chunks, x, y, z)) {
                        Blocks.set(chunks, x, y, z, null, 0);
                    }
                }
                boolean edge = x == s.x0 || x == s.x1 || z == s.z0 || z == s.z1;
                Blocks.set(chunks, x, GROUND, z, edge ? border : GRASS, 0);
            }
        }
        for (String line : s.tier.pieces) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) {
                continue;
            }
            VillageBuilder.paste(world, store, p[0], s.ox + Integer.parseInt(p[1]), GROUND + Integer.parseInt(p[2]),
                    s.oz + Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        }
        boolean heart = "Coeur".equals(s.row.category);
        for (String line : s.tier.blocks) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) {
                continue;
            }
            int x = s.ox + Integer.parseInt(p[0]);
            int y = GROUND + 1 + Integer.parseInt(p[1]);
            int z = s.oz + Integer.parseInt(p[2]);
            String block = p[4];
            if ("Empty".equals(block)) {
                Blocks.set(chunks, x, y, z, null, 0);
                continue;
            }
            // Le cœur pose ses blocs comme au village ; un décor de lieu-dit ne remplace jamais un bloc du bâtiment.
            String current = Blocks.name(chunks, x, y, z);
            if (heart || current == null || Terrain.isVegetation(current) || (y <= GROUND && Terrain.isGround(current))) {
                Blocks.set(chunks, x, y, z, block, Integer.parseInt(p[3]));
            }
        }
        for (String line : s.tier.clear) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 4) {
                continue;
            }
            int x = s.ox + Integer.parseInt(p[0]);
            int y = GROUND + 1 + Integer.parseInt(p[1]);
            int z = s.oz + Integer.parseInt(p[2]);
            if (p[3].equals(Blocks.name(chunks, x, y, z))) {
                Blocks.set(chunks, x, y, z, null, 0);
            }
        }
        drawZone(chunks, s);
        placeBoard(world, chunks, s);
        // Panneau muet dans le coin sud-ouest : l'utiliser dit le bâtiment et le palier.
        Blocks.set(chunks, s.signX, GROUND + 1, s.signZ, SIGN, 0);
    }

    /**
     * Boîte du palier ramenée à sa zone (Kevin, 18 novembre 2026 : zones maximales des Renoncés tracées en entier pour
     * y construire les prefabs à la main) : le liseré rouge de la carte borde la zone, un bloc à l'extérieur.
     */
    private static int[] withZone(int[] box, Tier t) {
        if (t.zone == null || !t.zoneMax) {
            return box;
        }
        String[] p = t.zone.trim().split("\\s+");
        if (p.length < 4) {
            return box;
        }
        // La zone remplace la boîte mesurée : VillageBuilder.bounds élargit chaque prefab du décalage de son ancre (une
        // quinzaine de blocs par côté pour les maisons outlanders), alors que la zone contient déjà l'emprise réelle de
        // tous les paliers (vérifiée par tools/generate_village_layouts.py). Seule la hauteur mesurée est gardée.
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                box == null ? 1 : box[4]};
    }

    /**
     * Vrai si chaque palier du bâtiment a sa zone maximale allouée par le plan du village (Kevin, 18 novembre 2026 :
     * « la taille maximale qui lui est allouée dans le village, de sorte à pouvoir construire les prefabs à la main »).
     * Le rectangle rouge est alors exactement cette zone, liseré compris autour : pas de marge ni de contour jaune.
     */
    private static boolean allocated(Row r) {
        return !r.tiers.isEmpty() && r.tiers.stream().allMatch(t -> t.zoneMax && t.zone != null);
    }

    /** Contour de la place libre du palier, sur l'herbe seulement, dans le rectangle (sans le liseré). */
    private static void drawZone(ChunkStore chunks, Spot s) {
        if (s.tier.zone == null || allocated(s.row)) {
            return;
        }
        String[] p = s.tier.zone.trim().split("\\s+");
        if (p.length < 4) {
            return;
        }
        int zx0 = s.ox + Integer.parseInt(p[0]), zx1 = s.ox + Integer.parseInt(p[1]);
        int zz0 = s.oz + Integer.parseInt(p[2]), zz1 = s.oz + Integer.parseInt(p[3]);
        for (int x = Math.max(zx0, s.x0 + 1); x <= Math.min(zx1, s.x1 - 1); x++) {
            for (int z = Math.max(zz0, s.z0 + 1); z <= Math.min(zz1, s.z1 - 1); z++) {
                boolean edge = x == zx0 || x == zx1 || z == zz0 || z == zz1;
                if (edge && Blocks.is(Blocks.name(chunks, x, GROUND, z), GRASS)) {
                    Blocks.set(chunks, x, GROUND, z, ZONE, 0);
                }
            }
        }
    }

    /** Panneau de métier du palier (bloc vanilla du peuple, sans tableau), où {@code Workshops.placeBoard} le pose. */
    private static void placeBoard(World world, ChunkStore chunks, Spot s) {
        if (s.tier.board == null) {
            return;
        }
        String[] p = s.tier.board.trim().split("\\s+");
        if (p.length < 4) {
            return;
        }
        int x = s.ox + Integer.parseInt(p[0]);
        int z = s.oz + Integer.parseInt(p[1]);
        int yaw = Integer.parseInt(p[2]) & 3;
        int[] at;
        if (p.length > 4 && "Fixe".equals(p[4])) {
            // Avant-poste (sur sa fondation) ou panneau relevé à la main (hauteur au-dessus du point de collage).
            at = new int[] {x, p.length > 5 ? GROUND + Integer.parseInt(p[5]) : GROUND + 1, z};
        } else {
            at = Workshops.boardSpot(chunks, new Terrain(world), x, z, yaw);
        }
        if (at == null) {
            LOGGER.atWarning().log("Nakasar Peuples : revue, pas de place pour le panneau de %s", s.key);
            return;
        }
        Blocks.set(chunks, at[0], at[1], at[2], p[3], yaw);
        Workshops.hangBoard(chunks, s.section.people, at[0], at[1], at[2], yaw);
        placed(world).put(s.key, new Placed(at[0], at[1], at[2], yaw, s.ox, s.oz, s.x0, s.x1, s.z0, s.z1,
                GROUND + s.height + CLEAR_ABOVE, p[3]));
    }

    /** Panneau posé par la carte : place, lacet, origine et rectangle de son palier, bloc. */
    private record Placed(int x, int y, int z, int yaw, int ox, int oz, int x0, int x1, int z0, int z1, int top, String block) {
        String line(String key) {
            return key + " " + x + " " + y + " " + z + " " + yaw + " " + ox + " " + oz + " " + x0 + " " + x1 + " " + z0
                    + " " + z1 + " " + top + " " + block;
        }
    }

    // ---- Relevé des panneaux déplacés à la main ----

    private static final Map<String, Map<String, Placed>> PLACED = new HashMap<>();

    private static synchronized Map<String, Placed> placed(World world) {
        return PLACED.computeIfAbsent(world.getName(), k -> {
            Map<String, Placed> out = new java.util.concurrent.ConcurrentHashMap<>();
            for (String line : readLines(worldFile(world, BOARDS_FILE))) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 13) {
                    try {
                        int[] n = new int[11];
                        for (int i = 0; i < 11; i++) {
                            n[i] = Integer.parseInt(p[i + 1]);
                        }
                        out.put(p[0], new Placed(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9], n[10], p[12]));
                    } catch (NumberFormatException e) {
                        LOGGER.atWarning().log("Nakasar Peuples : revue, ligne de panneau illisible « %s »", line);
                    }
                }
            }
            return out;
        });
    }

    private static void savePlaced(World world) {
        List<String> lines = new ArrayList<>();
        placed(world).forEach((k, v) -> lines.add(v.line(k)));
        java.util.Collections.sort(lines);
        writeLines(worldFile(world, BOARDS_FILE), lines);
    }

    /** Dossier des relevés de la carte, à côté des mondes du serveur (survit à l'écrasement du monde de revue). */
    public static Path captureFile() {
        return Universe.get().getWorldsPath().toAbsolutePath().resolveSibling("nakasar-revue").resolve(CAPTURE_FILE);
    }

    /** Un panneau relevé : place relative à l'origine du palier (repère du prefab non tourné, comme la carte). */
    static final class Capture {
        @SerializedName("Key")
        String key;
        @SerializedName("People")
        String people;
        @SerializedName("Category")
        String category;
        @SerializedName("Id")
        String id;
        @SerializedName("Tier")
        int tier;
        @SerializedName("DX")
        int dx;
        /** Hauteur au-dessus du point de collage du prefab (1 = posé sur le sol de la carte). */
        @SerializedName("DY")
        int dy;
        @SerializedName("DZ")
        int dz;
        @SerializedName("Yaw")
        int yaw;
    }

    static final class CaptureFile {
        @SerializedName("$Comment")
        String comment;
        @SerializedName("Boards")
        List<Capture> boards = new ArrayList<>();
    }

    /** Résultat d'un relevé : panneaux enregistrés (nouveaux ou déplacés à nouveau), introuvables, total du fichier. */
    public record CaptureResult(int moved, int missing, int total, Path file) {
    }

    /**
     * Relève les panneaux de métier de la carte `world` déplacés à la main depuis leur pose (Kevin, 17 novembre 2026)
     * et les ajoute à {@link #captureFile()}. Un panneau déjà relevé y reste tant qu'on ne le rebouge pas (la carte le
     * repose alors à cette place). Rectangle après rectangle, chunks chargés puis lecture sur le thread du monde.
     */
    public static CompletableFuture<CaptureResult> capture(World world) {
        Path file = captureFile();
        CaptureFile doc = readCaptures(file);
        Map<String, Capture> byKey = new LinkedHashMap<>();
        for (Capture c : doc.boards) {
            byKey.put(c.key, c);
        }
        // Rectangles d'après la pose (fichier du monde), pas d'après la carte actuelle qui a pu changer depuis.
        int[] counts = {0, 0};
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<String, Placed> e : new java.util.TreeMap<>(placed(world)).entrySet()) {
            String key = e.getKey();
            Placed was = e.getValue();
            chain = chain.thenCompose(v -> Terrain.load(world, was.x0(), was.x1(), was.z0(), was.z1()))
                    .thenRunAsync(() -> {
                        int[] found = findBoard(world.getChunkStore(), was);
                        if (found == null) {
                            counts[1]++;
                            return;
                        }
                        if (found[0] == was.x() && found[1] == was.y() && found[2] == was.z() && found[3] == was.yaw()) {
                            return;
                        }
                        byKey.put(key, toCapture(key, was, found));
                        counts[0]++;
                    }, world::execute);
        }
        return chain.thenApply(v -> {
            doc.boards = new ArrayList<>(byKey.values());
            doc.boards.sort(java.util.Comparator.comparing(c -> c.key));
            writeCaptures(file, doc);
            LOGGER.atInfo().log("Nakasar Peuples : revue, %d panneau(x) relevé(s), %d introuvable(s) → %s", counts[0],
                    counts[1], file);
            return new CaptureResult(counts[0], counts[1], doc.boards.size(), file);
        });
    }

    /** Relevé du panneau `key` trouvé en `found` (x, y, z, lacet). Clé « Peuple/Catégorie/Id#palier ». */
    private static Capture toCapture(String key, Placed was, int[] found) {
        int hash = key.lastIndexOf('#');
        String[] parts = key.substring(0, hash).split("/", 3);
        Capture c = new Capture();
        c.key = key;
        c.people = parts[0];
        c.category = parts.length > 1 ? parts[1] : "";
        c.id = parts.length > 2 ? parts[2] : "";
        c.tier = Integer.parseInt(key.substring(hash + 1));
        c.dx = found[0] - was.ox();
        c.dy = found[1] - GROUND;
        c.dz = found[2] - was.oz();
        c.yaw = found[3];
        return c;
    }

    /**
     * Panneau du rectangle `key` (sur le thread du monde, chunks chargés) : relevé s'il a bougé depuis sa pose, puis
     * rendu (x, y, z, lacet, bloc via {@link #boardBlock}) ; null si le palier n'a pas de panneau ou s'il n'y est plus.
     */
    static int[] captureOne(World world, String key) {
        Placed was = placed(world).get(key);
        if (was == null) {
            return null;
        }
        int[] found = findBoard(world.getChunkStore(), was);
        if (found == null) {
            return null;
        }
        if (found[0] != was.x() || found[1] != was.y() || found[2] != was.z() || found[3] != was.yaw()) {
            Path file = captureFile();
            CaptureFile doc = readCaptures(file);
            doc.boards.removeIf(c -> key.equals(c.key));
            doc.boards.add(toCapture(key, was, found));
            doc.boards.sort(java.util.Comparator.comparing(c -> c.key));
            writeCaptures(file, doc);
        }
        return found;
    }

    /** Bloc du panneau de métier posé dans le rectangle `key`, ou null. */
    static String boardBlock(World world, String key) {
        Placed was = placed(world).get(key);
        return was == null ? null : was.block();
    }

    private static void writeCaptures(Path file, CaptureFile doc) {
        doc.comment = "Panneaux de métier déplacés à la main sur la carte de revue (/village revue --relever, "
                + "relevé aussi avant chaque écrasement du monde et à chaque --sauver). À copier dans "
                + "tools/panneaux-releves.json du dépôt (lu par tools/building_growth.py). DX/DY/DZ relatifs au point de "
                + "collage du prefab non tourné, Yaw 0 face au sud, 1 à l'est, 2 au nord, 3 à l'ouest.";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                    .create().toJson(doc) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Le rectangle (liseré compris) qui contient la colonne (x, z) du monde de revue, ou null. */
    static Spot spotAt(World world, int x, int z) {
        for (Spot s : layout(world).spots) {
            if (x >= s.x0 && x <= s.x1 && z >= s.z0 && z <= s.z1) {
                return s;
            }
        }
        return null;
    }

    private static CaptureFile readCaptures(Path file) {
        try {
            if (Files.exists(file)) {
                CaptureFile doc = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), CaptureFile.class);
                if (doc != null && doc.boards != null) {
                    return doc;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, relevé %s illisible", file);
        }
        return new CaptureFile();
    }

    /**
     * Le panneau dans le rectangle de sa pose (sans le liseré) : x, y, z, lacet ; null s'il n'y est plus. Le plus proche
     * de sa place d'origine s'il y en a plusieurs.
     */
    private static int[] findBoard(ChunkStore chunks, Placed was) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int y = GROUND + 1; y <= was.top(); y++) {
            for (int x = was.x0() + 1; x < was.x1(); x++) {
                for (int z = was.z0() + 1; z < was.z1(); z++) {
                    if (Blocks.is(Blocks.name(chunks, x, y, z), was.block())) {
                        int d = Math.abs(x - was.x()) + Math.abs(y - was.y()) + Math.abs(z - was.z());
                        if (d < bestD) {
                            bestD = d;
                            best = new int[] {x, y, z, Blocks.rotation(chunks, x, y, z) & 3};
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Le monde de revue tel qu'il est (chargé au besoin), sans jamais l'écraser ; null s'il n'existe pas. */
    public static CompletableFuture<World> existingWorld() {
        String name = worldName();
        Universe universe = Universe.get();
        World loaded = universe.getWorld(name);
        if (loaded != null) {
            return CompletableFuture.completedFuture(loaded);
        }
        return universe.isWorldLoadable(name) ? universe.loadWorld(name) : CompletableFuture.completedFuture(null);
    }

    // ---- Panneau ----

    /** Ce qu'affiche le panneau d'un rectangle : titre (bâtiment), sous-titre (peuple, palier, catégorie), détails. */
    static Message[] describe(Spot s) {
        Message title = Message.raw(s.row.name);
        Message subtitle = s.tier.tier == 0
                ? Message.translation(KEYS + "signUntiered").param("people", s.section.name).param("category", s.row.categoryName)
                : Message.translation(KEYS + "sign").param("people", s.section.name).param("tier", s.tier.tier)
                        .param("category", s.row.categoryName);
        List<String> prefabs = new ArrayList<>();
        for (String line : s.tier.pieces) {
            String path = line.trim().split("\\s+")[0];
            if (!prefabs.contains(path)) {
                prefabs.add(path);
            }
        }
        List<String> notes = new ArrayList<>();
        if (s.row.source != null && !s.row.source.isEmpty()) {
            notes.add(s.row.source);
        }
        if (s.tier.notes != null) {
            notes.addAll(s.tier.notes);
        }
        Message details = Message.translation(KEYS + "signDetails").param("prefabs", String.join(", ", prefabs))
                .param("notes", String.join(" · ", notes));
        return new Message[]{title, subtitle, details};
    }

    // ---- État ----

    private static Path worldFile(World world, String name) {
        return Universe.get().getWorldsPath().resolve(world.getName()).resolve(name);
    }

    private static List<String> readLines(Path f) {
        try {
            if (Files.exists(f)) {
                return Files.readAllLines(f, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, lecture de %s", f);
        }
        return List.of();
    }

    private static void writeLines(Path f, Iterable<String> lines) {
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Nakasar Peuples : revue, écriture de %s", f);
        }
    }

    static Set<String> loadState(World world) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : readLines(worldFile(world, STATE_FILE))) {
            if (!line.isBlank()) {
                keys.add(line.trim());
            }
        }
        return keys;
    }

    private static void saveState(World world, Set<String> keys) {
        writeLines(worldFile(world, STATE_FILE), keys);
        savePlaced(world);
    }

    /** Lignes « R clé largeur profondeur hauteur » (rectangle d'un bâtiment) et « T clé dx dz » (origine d'un palier). */
    private static Map<String, int[]> loadPlan(World world) {
        Map<String, int[]> plan = new LinkedHashMap<>();
        for (String line : readLines(worldFile(world, PLAN_FILE))) {
            String[] p = line.trim().split("\\s+");
            try {
                if (p.length == 5 && p[0].equals("R")) {
                    plan.put("R " + p[1], new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])});
                } else if (p.length == 4 && p[0].equals("T")) {
                    plan.put("T " + p[1], new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3])});
                }
            } catch (NumberFormatException e) {
                LOGGER.atWarning().log("Nakasar Peuples : revue, ligne de plan illisible « %s »", line);
            }
        }
        return plan;
    }

    private static void savePlan(World world, Map<String, int[]> plan) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : plan.entrySet()) {
            StringBuilder b = new StringBuilder(e.getKey());
            for (int v : e.getValue()) {
                b.append(' ').append(v);
            }
            lines.add(b.toString());
        }
        writeLines(worldFile(world, PLAN_FILE), lines);
    }
}
