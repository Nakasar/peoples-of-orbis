package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villages à placement libre (Kevin, 18 novembre 2026) : à la fondation, le cœur est posé, puis chaque bâtiment du plan
 * type trouve sa propre place autour de lui ({@link FreePlanner}), de sorte que chaque village soit différent et que la
 * taille d'un bâtiment ne dépende plus d'une parcelle dessinée à la main.
 *
 * <p>Le plan type ({@code Server/Nakasar/VillageLayouts}) reste la source des bâtiments, métiers, chantiers, zones
 * maximales et paliers d'apparence. Le village garde seulement le déplacement de chaque bâtiment et terrain
 * ({@code Placement} : « id x z quarts », « @E1 x z quarts » pour un terrain) ; son plan propre en est recalculé
 * ({@link #of}) avec ses sentiers ({@link StreetPlanner}). Tout le reste du mod lit ce plan propre par
 * {@link Chantiers#layout} sans rien savoir du placement.
 *
 * <p>Les villages fondés avant (sans {@code Placement}) gardent leur plan type, sans migration.
 */
public final class FreePlans {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final String STYLES = "/nakasar-peuples/sentiers-styles.json";
    /** {@code -Dnakasar.plans.fixes=true} : les nouveaux villages reprennent le plan type (retour en arrière). */
    public static final boolean DISABLED = Boolean.getBoolean("nakasar.plans.fixes");

    private static final Map<String, VillageLayout> PLACED = new ConcurrentHashMap<>();
    private static volatile StreetPlanner.Styles styles;

    private FreePlans() {
    }

    /** Vrai si un nouveau village de ce plan type se place librement (chaque bâtiment a sa zone maximale). */
    public static boolean enabled(VillageLayout base) {
        if (DISABLED || base == null || base.isPlaced() || base.buildings().length == 0) {
            return false;
        }
        for (VillageLayout.Building b : base.buildings()) {
            if (b.plot() == null && b.zone() == null) {
                return false;
            }
        }
        return true;
    }

    /** Rayon que le placement ne dépasse pas : celui du plan type, plus un quart (un plan libre est moins serré). */
    public static int maxRadius(VillageLayout base) {
        return base.radius() * 5 / 4 + 4;
    }

    /** Rayon à charger et à réserver autour d'un site pour ce plan type. */
    public static int siteRadius(VillageLayout base) {
        return enabled(base) ? maxRadius(base) : base.radius();
    }

    /** Unités à placer : chaque bâtiment hors terrain (sa zone maximale), chaque terrain de projet. */
    static List<FreePlanner.Unit> units(VillageLayout base) {
        List<FreePlanner.Unit> units = new ArrayList<>();
        for (VillageLayout.Building b : base.buildings()) {
            if (b.plot() != null) {
                continue;
            }
            int[] r = b.zone() != null ? b.zone() : BuildingPaliers.reserved(base, b);
            units.add(new FreePlanner.Unit(b.id(), b.tier(), r, b.x(), b.z(), false));
        }
        for (VillageLayout.Plot p : base.plots()) {
            units.add(new FreePlanner.Unit("@" + p.id(), p.tier(), p.rect(), p.x(), p.z(), true));
        }
        return units;
    }

    /** Graine d'un site : même monde, même site, même plan. */
    static long seed(World world, VillageLayout base, int cx, int cz) {
        long s = world.getWorldConfig().getSeed();
        return s * 0x9E3779B97F4A7C15L ^ (long) cx * 73856093L ^ (long) cz * 19349663L ^ base.getId().hashCode();
    }

    /** Sol du site (cœur en (cx, cz)) lu dans le monde, mis en cache colonne par colonne. */
    private static FreePlanner.Ground ground(World world, int cx, int cz) {
        Terrain terrain = new Terrain(world);
        Map<Long, int[]> cache = new HashMap<>();
        return new FreePlanner.Ground() {
            private int[] column(int x, int z) {
                return cache.computeIfAbsent(StreetPlanner.key(x, z), k -> {
                    int h = terrain.surface(cx + x, cz + z);
                    boolean wet = h != Terrain.NONE && terrain.hasFluid(cx + x, h + 1, cz + z);
                    return new int[] {h, wet ? 1 : 0};
                });
            }

            @Override
            public int height(int x, int z) {
                int h = column(x, z)[0];
                return h == Terrain.NONE ? FreePlanner.NONE : h;
            }

            @Override
            public boolean wet(int x, int z) {
                return column(x, z)[1] == 1;
            }
        };
    }

    /**
     * Calcule le placement d'un village du plan type `base` au cœur (cx, cz) : terrain déjà chargé sur
     * {@link #siteRadius}. Thread du monde.
     */
    public static FreePlanner.Result plan(World world, VillageLayout base, int cx, int cz) {
        int p = base.plazaRadius();
        Terrain terrain = new Terrain(world);
        int h0 = terrain.medianSurface(cx - p, cx + p, cz - p, cz + p, 1);
        if (h0 == Terrain.NONE) {
            return new FreePlanner.Result(Map.of(), Map.of(), "Place");
        }
        long t = System.nanoTime();
        FreePlanner.Result result = FreePlanner.place(units(base), p, maxRadius(base), h0, ground(world, cx, cz), seed(world, base, cx, cz));
        LOGGER.atFine().log("Nakasar Peuples : placement libre %s en %d %d : %s (%.0f ms)", base.getId(), cx, cz,
                result.ok() ? "ok" : "sans place pour " + result.failed(), (System.nanoTime() - t) / 1e6);
        return result;
    }

    /** Écriture du placement pour la sauvegarde du village. */
    public static String[] encode(FreePlanner.Result result) {
        List<String> out = new ArrayList<>();
        result.moves().forEach((id, move) -> out.add(id + " " + move.encode()));
        return out.toArray(String[]::new);
    }

    /** Plan propre d'un village (plan type si le village n'a pas de placement libre), ou null. */
    public static VillageLayout of(Village village) {
        VillageLayout base = village.layout() == null ? null : VillageLayout.get(village.layout());
        String[] placement = village.placement();
        if (base == null || placement.length == 0) {
            return base;
        }
        return placed(base, placement);
    }

    /** Plan propre du plan type `base` pour ce placement (mis en cache). */
    public static VillageLayout placed(VillageLayout base, String[] placement) {
        String key = base.getId() + "#" + System.identityHashCode(base) + "#" + String.join("|", placement);
        return PLACED.computeIfAbsent(key, k -> build(base, placement));
    }

    private static VillageLayout build(VillageLayout base, String[] placement) {
        Map<String, Placing> moves = new HashMap<>();
        for (String line : placement) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 4) {
                continue;
            }
            try {
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                int rot = Integer.parseInt(p[3]);
                if (p[0].startsWith("@")) {
                    VillageLayout.Plot plot = base.plot(p[0].substring(1));
                    if (plot != null) {
                        moves.put(p[0], Placing.ofCorner(plot.x(), plot.z(), x, z, rot));
                    }
                } else {
                    VillageLayout.Building b = base.building(p[0]);
                    if (b != null) {
                        moves.put(p[0], Placing.ofAnchor(b.x(), b.z(), x, z, rot));
                    }
                }
            } catch (NumberFormatException e) {
                LOGGER.atWarning().log("Nakasar Peuples : placement illisible « %s » (%s)", line, base.getId());
            }
        }
        VillageLayout placed = base.placed(moves, null);
        // Sentiers et décor recalculés sur les emprises placées (même graine pour le même placement).
        List<StreetPlanner.Site> sites = new ArrayList<>();
        for (VillageLayout.Building b : placed.buildings()) {
            if (b.plot() == null) {
                int[] r = b.zone() != null ? b.zone() : b.rect();
                sites.add(new StreetPlanner.Site(b.id(), b.tier(), r, b.prefab() != null
                        && (b.prefab().contains("/Lampposts/") || b.prefab().contains("/Seats/"))));
            }
        }
        List<StreetPlanner.Site> plotSites = new ArrayList<>();
        for (VillageLayout.Plot p : placed.plots()) {
            plotSites.add(new StreetPlanner.Site(p.id(), p.tier(), p.rect(), false));
        }
        List<StreetPlanner.Step> steps = StreetPlanner.plan(styles(), placed.people().id(), sites, plotSites,
                placed.plazaRadius(), placed.radius(), String.join("|", placement).hashCode());
        VillageLayout.StreetStep[] streets = new VillageLayout.StreetStep[steps.size()];
        for (int i = 0; i < streets.length; i++) {
            StreetPlanner.Step s = steps.get(i);
            streets[i] = VillageLayout.StreetStep.of(s.tier(), s.paths().toArray(String[]::new), s.decor().toArray(String[]::new));
        }
        return base.placed(moves, streets);
    }

    static StreetPlanner.Styles styles() {
        if (styles == null) {
            synchronized (FreePlans.class) {
                if (styles == null) {
                    try (InputStream in = FreePlans.class.getResourceAsStream(STYLES)) {
                        styles = in == null ? new StreetPlanner.Styles()
                                : StreetPlanner.Styles.read(new InputStreamReader(in, StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        LOGGER.atWarning().withCause(e).log("Nakasar Peuples : %s illisible, villages libres sans sentiers", STYLES);
                        styles = new StreetPlanner.Styles();
                    }
                }
            }
        }
        return styles;
    }

    /** Oublie les plans propres (rechargement des assets). */
    public static void clearCache() {
        PLACED.clear();
    }

    // ---- Données situées par rapport à un bâtiment dans les générateurs (enclos, panneaux) ----

    /** Rectangle {x0, x1, z0, z1} du plan type lié au bâtiment `buildingId`, à sa place dans le village. */
    public static int[] rect(Village village, String buildingId, int[] r) {
        VillageLayout layout = Chantiers.layout(village);
        Placing t = layout == null ? null : layout.placing(buildingId);
        return t == null || r == null ? r : t.rect(r);
    }
}
