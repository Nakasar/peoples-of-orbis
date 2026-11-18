package dev.nakasar.hytale.peuples.village;

import com.google.gson.Gson;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.nakasar.hytale.peuples.workshop.Workshop;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apparence des bâtiments selon leur palier (Kevin, 17 novembre 2026) : « quand un village change de palier, ses
 * bâtiments propres changent d'apparence pour le palier correspondant ; les bâtiments qui ont leurs propres niveaux ne
 * changent d'apparence que lors de leur changement de niveau propre ».
 *
 * <p>Les paliers 1 à 4 de chaque bâtiment (prefab vanilla de plus en plus grand de la même famille, comme sur la carte
 * de revue {@code /village revue}) sont calculés par {@code tools/building_growth.py} pour TENIR dans le village :
 * {@value #RESOURCE}. Un bâtiment absent du fichier garde son prefab à tous les paliers.
 *
 * <ul>
 *   <li>bâtiment propre (sans bâtiment de métier) : palier du village + 1 (campement 1, hameau 2, village 3, bourg 4) ;</li>
 *   <li>bâtiment à niveaux (métier, projet de terrain, production, ouvrage) : {@link #LEVEL_PALIER} de son niveau.</li>
 * </ul>
 * L'apparence ne redescend jamais (village qui perd un palier après un raid, reconversion) : on ne remplace un bâtiment
 * que par un palier plus haut ({@link Rebuilds}).
 */
public final class BuildingPaliers {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final String RESOURCE = "/nakasar-peuples/paliers-batiments.json";
    public static final int MAX = 4;

    /**
     * Palier d'apparence d'un bâtiment à niveaux, par niveau (I, II, III ; indice 0 inutilisé) : le niveau II demande un
     * village et le III un bourg, le bâtiment prend alors la taille des bâtiments propres de ce palier.
     */
    public static final int[] LEVEL_PALIER = {1, 1, 3, 4};

    /** {@code board} : place du panneau de métier {@code [x, z, lacet]} relative au cœur à ce palier, ou null. */
    private record Variant(String prefab, int dx, int dz, int minX, int maxX, int minZ, int maxZ, int minY, int maxY,
                           int[] board) {
    }

    private static final class Data {
        Map<String, Map<String, List<Map<String, Object>>>> Layouts;
        Map<String, List<Map<String, Object>>> Annexes;
    }

    private static volatile Map<String, Map<String, Variant[]>> layouts;
    private static volatile Map<String, String[][]> annexes;
    private static final Map<String, java.util.Optional<Workshop>> WORKSHOP_OF = new ConcurrentHashMap<>();

    private BuildingPaliers() {
    }

    private static synchronized void load() {
        if (layouts != null) {
            return;
        }
        Map<String, Map<String, Variant[]>> byLayout = new HashMap<>();
        Map<String, String[][]> byAnnex = new HashMap<>();
        try (InputStream in = BuildingPaliers.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.atWarning().log("Nakasar Peuples : %s introuvable, les bâtiments gardent leur prefab", RESOURCE);
            } else {
                Data data = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Data.class);
                if (data.Layouts != null) {
                    data.Layouts.forEach((layout, buildings) -> {
                        Map<String, Variant[]> out = new HashMap<>();
                        buildings.forEach((id, tiers) -> {
                            Variant[] v = new Variant[MAX + 1];
                            for (Map<String, Object> t : tiers) {
                                int p = num(t, "Palier");
                                if (p >= 1 && p <= MAX) {
                                    v[p] = new Variant((String) t.get("Prefab"), num(t, "DX"), num(t, "DZ"), num(t, "MinX"),
                                            num(t, "MaxX"), num(t, "MinZ"), num(t, "MaxZ"), num(t, "MinY"), num(t, "MaxY"),
                                            ints(t.get("Board")));
                                }
                            }
                            out.put(id, v);
                        });
                        byLayout.put(layout, out);
                    });
                }
                if (data.Annexes != null) {
                    data.Annexes.forEach((id, tiers) -> {
                        String[][] v = new String[MAX + 1][];
                        for (Map<String, Object> t : tiers) {
                            int p = num(t, "Palier");
                            Object lines = t.get("Prefabs");
                            if (p >= 1 && p <= MAX && lines instanceof List<?> list) {
                                v[p] = list.stream().map(String::valueOf).toArray(String[]::new);
                            }
                        }
                        byAnnex.put(id, v);
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Nakasar Peuples : %s illisible, les bâtiments gardent leur prefab", RESOURCE);
        }
        annexes = byAnnex;
        layouts = byLayout;
    }

    private static int[] ints(Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i) instanceof Number n ? n.intValue() : 0;
        }
        return out;
    }

    private static int num(Map<String, Object> t, String key) {
        Object v = t.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Variant[] variants(VillageLayout layout, VillageLayout.Building b) {
        if (layouts == null) {
            load();
        }
        Map<String, Variant[]> m = layout == null ? null : layouts.get(layout.getId());
        return m == null ? null : m.get(b.id());
    }

    /** Le bâtiment `b` du plan sous l'apparence du palier `palier` (lui-même s'il n'a pas d'autre apparence). */
    public static VillageLayout.Building at(VillageLayout layout, VillageLayout.Building b, int palier) {
        Variant[] v = variants(layout, b);
        if (v == null) {
            return b;
        }
        Variant at = v[Math.max(1, Math.min(MAX, palier))];
        if (at == null || at.prefab() == null) {
            return b;
        }
        if (at.dx() == 0 && at.dz() == 0 && at.prefab().equals(b.prefab()) && at.minX() == b.minX && at.maxX() == b.maxX
                && at.minZ() == b.minZ && at.maxZ() == b.maxZ) {
            return b;
        }
        return b.variant(at.prefab(), at.dx(), at.dz(), at.minX(), at.maxX(), at.minZ(), at.maxZ(), at.minY(), at.maxY());
    }

    /** Vrai si deux paliers du bâtiment n'ont pas le même prefab ni la même place. */
    public static boolean differ(VillageLayout layout, VillageLayout.Building b, int from, int to) {
        VillageLayout.Building a = at(layout, b, from);
        VillageLayout.Building c = at(layout, b, to);
        return !a.prefab().equals(c.prefab()) || a.x() != c.x() || a.z() != c.z();
    }

    /** Emprise relative au cœur que le bâtiment peut prendre à n'importe quel palier (réservée dans le plan). */
    public static int[] reserved(VillageLayout layout, VillageLayout.Building b) {
        if (b.zone() != null) {
            // Zone maximale du plan (Kevin, 18 novembre 2026) : réservée en entier, retouches à la main comprises.
            return b.zone();
        }
        int[] r = b.rect();
        Variant[] v = variants(layout, b);
        if (v == null) {
            return r;
        }
        for (int p = 1; p <= MAX; p++) {
            if (v[p] != null) {
                int[] o = at(layout, b, p).rect();
                r = new int[] {Math.min(r[0], o[0]), Math.max(r[1], o[1]), Math.min(r[2], o[2]), Math.max(r[3], o[3])};
            }
        }
        return r;
    }

    /** Bâtiment de métier (ou projet de terrain) logé dans le bâtiment `b` du plan, ou null : bâtiment propre. */
    public static Workshop workshopOf(VillageLayout layout, VillageLayout.Building b) {
        if (layout == null) {
            return null;
        }
        return WORKSHOP_OF.computeIfAbsent(layout.getId() + "/" + b.id(), k -> {
            for (Workshop o : Workshop.forLayout(layout.getId())) {
                if (!o.isAnnex() && !o.isHamlet() && b.id().equals(o.building())) {
                    return java.util.Optional.of(o);
                }
            }
            return java.util.Optional.empty();
        }).orElse(null);
    }

    /** Palier d'un bâtiment à niveaux au niveau `level`. */
    public static int forLevel(int level) {
        return LEVEL_PALIER[Math.max(1, Math.min(LEVEL_PALIER.length - 1, level))];
    }

    /** Palier d'un bâtiment propre quand le village est au palier `villageTier` (0 campement ... 3 bourg). */
    public static int forTier(int villageTier) {
        return Math.max(1, Math.min(MAX, Tiers.clamp(villageTier) + 1));
    }

    /**
     * Palier que le bâtiment `b` doit montrer quand le village est au palier `villageTier` : celui du village pour un
     * bâtiment propre, celui de son niveau pour un bâtiment de métier ou un projet.
     */
    public static int target(Village village, VillageLayout layout, VillageLayout.Building b, int villageTier) {
        Workshop w = workshopOf(layout, b);
        if (w == null) {
            return forTier(villageTier);
        }
        WorkshopState ws = village.workshopIfPresent(w.getId());
        return forLevel(ws == null ? 1 : ws.level());
    }

    /** Le bâtiment `b` tel qu'il est debout dans le village (palier gardé avec le village). */
    public static VillageLayout.Building current(Village village, VillageLayout layout, VillageLayout.Building b) {
        return at(layout, b, village.palier(b.id()));
    }

    /**
     * Place du panneau du bâtiment de métier `w` ({@code [x, z, lacet]} relative au cœur) pour le bâtiment tel qu'il
     * est debout (Kevin, 17 novembre 2026 : le panneau suit le bâtiment qui grandit, devant sa façade ou à
     * l'intérieur) ; celle du plan au palier 1 ou sans version plus grande.
     */
    public static int[] board(Village village, Workshop w) {
        int[] plan = w.board();
        if (w.isAnnex() || w.isHamlet() || w.building() == null) {
            return plan;
        }
        VillageLayout layout = Chantiers.layout(village);
        VillageLayout.Building b = layout == null ? null : layout.building(w.building());
        Variant[] v = b == null ? null : variants(layout, b);
        if (v == null) {
            return moveBoard(layout, b, plan);
        }
        Variant at = v[Math.max(1, Math.min(MAX, village.palier(b.id())))];
        return moveBoard(layout, b, at != null && at.board() != null && at.board().length >= 2 ? at.board() : plan);
    }

    /** Panneau {@code [x, z, lacet, (dy)]} du plan type, à sa place dans un village à placement libre. */
    private static int[] moveBoard(VillageLayout layout, VillageLayout.Building b, int[] board) {
        Placing t = b == null || layout == null ? null : layout.placing(b.id());
        if (t == null || board == null || board.length < 2) {
            return board;
        }
        int[] out = board.clone();
        int[] at = t.point(board[0], board[1]);
        out[0] = at[0];
        out[1] = at[1];
        if (out.length > 2) {
            out[2] = t.yaw(board[2]);
        }
        return out;
    }

    /** Pièces de l'avant-poste `w` au palier `palier` (« prefab dx dz rotation »). */
    public static String[] annexPrefabs(Workshop w, int palier) {
        if (annexes == null) {
            load();
        }
        String[][] v = annexes.get(w.getId());
        String[] at = v == null ? null : v[Math.max(1, Math.min(MAX, palier))];
        return at != null ? at : w.annex().prefabs();
    }

    /** Vrai si l'avant-poste `w` change de pièces entre les deux paliers. */
    public static boolean annexDiffer(Workshop w, int from, int to) {
        return !java.util.Arrays.equals(annexPrefabs(w, from), annexPrefabs(w, to));
    }

    /** Oublie le cache (rechargement des assets). */
    public static void clearCache() {
        WORKSHOP_OF.clear();
    }
}
