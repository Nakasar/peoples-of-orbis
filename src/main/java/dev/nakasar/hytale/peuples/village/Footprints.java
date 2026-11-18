package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Emprises au sol des bâtiments et terrain des villages (Kevin, 18 novembre 2026, après les simulations sur du vrai
 * relief) :
 * <ul>
 *   <li>{@link #of} : la forme réelle d'un prefab au sol (murs, poteaux, clôtures, meubles, planchers, cultures, avec
 *   ce qu'ils enferment : cour d'enclos, potager clos), au lieu de son rectangle. Seule cette forme reste à la hauteur
 *   du bâtiment ; le reste du rectangle reprend le relief (pas de « gros talus carrés »).</li>
 *   <li>{@link #dry} : aucun bâtiment du plan, à aucun palier, ne tombe dans une rivière ou un lac (sites de village).</li>
 *   <li>{@link #clearWild} : blocs vanilla laissés par la génération du monde (ruines, camps, chemins, brindilles)
 *   retirés des emprises du plan à la fondation, pour qu'ils ne gênent jamais les chantiers.</li>
 * </ul>
 */
public final class Footprints {

    /** Hauteur, au-dessus du sol du prefab, où un bloc fait partie du bâtiment debout (murs, clôtures, troncs). */
    private static final int STANDING = 3;
    /** Pas d'échantillonnage des emprises pour l'eau. */
    private static final int WATER_STEP = 2;
    /** Hauteur fouillée au-dessus du sol pour les restes de la génération du monde. */
    private static final int WILD_HEIGHT = 24;

    /** Emprise au sol relative au point de collage, par prefab et rotation : colonnes {@code (dx, dz)} empaquetées. */
    private static final Map<String, Set<Long>> CACHE = new ConcurrentHashMap<>();

    private Footprints() {
    }

    static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Colonnes (relatives au point de collage) que le prefab occupe au sol : un bloc construit au niveau du sol (plancher,
     * pavés, terre labourée) ou un bloc debout dans les {@value #STANDING} blocs au-dessus (sauf herbes et fleurs), plus
     * les colonnes qu'elles enferment, plus une case de seuil autour. Null si le prefab est introuvable.
     */
    public static Set<Long> of(String prefab, int rotation) {
        String key = prefab + "#" + Math.floorMod(rotation, 4);
        Set<Long> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        IPrefabBuffer buffer = VillageBuilder.buffer(prefab);
        if (buffer == null) {
            return null;
        }
        PrefabRotation rot = PrefabRotation.VALUES[Math.floorMod(rotation, 4)];
        Set<Long> solid = new HashSet<>();
        buffer.forEachRaw(IPrefabBuffer.iterateAllColumns(), (x, y, z, ignored, id, f, holder, c1, c2, c3, t) -> {
            if (id == BlockType.EMPTY_ID || y < 0 || y > STANDING) {
                return;
            }
            BlockType type = BlockType.getAssetMap().getAsset(id);
            String name = type == null ? null : type.getId();
            if (name == null || !counts(name, y)) {
                return;
            }
            solid.add(pack(rot.getX(x, z), rot.getZ(x, z)));
        }, (x, y, z, id, level, t) -> { }, (x, z, e, t) -> { }, null);
        Set<Long> out = closeAndPad(solid);
        CACHE.put(key, out);
        return out;
    }

    /** Vrai si le bloc `name` du prefab, à `y` au-dessus de son sol, compte dans l'emprise au sol. */
    private static boolean counts(String name, int y) {
        String base = name.startsWith("*") ? name.substring(1) : name;
        if (y == 0) {
            // Sol du prefab : seulement ce qui n'est pas du relief (plancher, pavés, terre labourée).
            return !Chantiers.natural(base) || base.contains("Tilled");
        }
        // Debout : tout sauf les herbes et fleurs sauvages (les cultures comptent : potager).
        return !Grading.isSmallPlant(base) || base.startsWith("Plant_Crop");
    }

    /** Ajoute les colonnes enfermées (cour d'enclos, potager clos) et une case de seuil autour. */
    private static Set<Long> closeAndPad(Set<Long> solid) {
        if (solid.isEmpty()) {
            return solid;
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (long c : solid) {
            int x = (int) (c >> 32), z = (int) c;
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            z0 = Math.min(z0, z);
            z1 = Math.max(z1, z);
        }
        // Remplissage depuis l'extérieur (cadre élargi d'une case) : ce qu'on n'atteint pas est enfermé.
        x0--;
        x1++;
        z0--;
        z1++;
        int w = x1 - x0 + 1, d = z1 - z0 + 1;
        boolean[] wall = new boolean[w * d];
        for (long c : solid) {
            wall[((int) (c >> 32) - x0) * d + ((int) c - z0)] = true;
        }
        boolean[] outside = new boolean[w * d];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {0, 0});
        outside[0] = true;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] a : around) {
                int i = p[0] + a[0], k = p[1] + a[1];
                if (i < 0 || k < 0 || i >= w || k >= d) {
                    continue;
                }
                int c = i * d + k;
                if (!outside[c] && !wall[c]) {
                    outside[c] = true;
                    queue.add(new int[] {i, k});
                }
            }
        }
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < w; i++) {
            for (int k = 0; k < d; k++) {
                if (!outside[i * d + k]) {
                    for (int a = -1; a <= 1; a++) {
                        for (int b = -1; b <= 1; b++) {
                            out.add(pack(x0 + i + a, z0 + k + b));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Prédicat monde : la colonne (x, z) est dans l'emprise au sol du prefab collé en (ox, oz). Si le prefab est
     * introuvable, tout le rectangle compte (comportement d'avant).
     */
    public static BiPredicate<Integer, Integer> at(String prefab, int rotation, int ox, int oz) {
        Set<Long> cols = of(prefab, rotation);
        if (cols == null || cols.isEmpty()) {
            return (x, z) -> true;
        }
        return (x, z) -> cols.contains(pack(x - ox, z - oz));
    }

    /** Rayon de terrain à charger pour examiner un site : tout le plan (paliers à venir compris). */
    public static int siteRadius(VillageLayout layout) {
        return FreePlans.siteRadius(layout) + 2;
    }

    /** Emprises du plan, relatives au cœur : chaque bâtiment (tous paliers d'apparence), chaque terrain, la place. */
    static List<int[]> planRects(VillageLayout layout) {
        List<int[]> rects = new ArrayList<>();
        for (VillageLayout.Building b : layout.buildings()) {
            rects.add(BuildingPaliers.reserved(layout, b));
        }
        for (VillageLayout.Plot p : layout.plots()) {
            rects.add(p.rect());
        }
        int p = layout.plazaRadius();
        rects.add(new int[] {-p, p, -p, p});
        return rects;
    }

    /**
     * Vrai si aucune emprise du plan centré en (cx, cz) ne touche l'eau (rivière, lac) : sondage tous les
     * {@value #WATER_STEP} blocs. Kevin 2026-11-18 : des bâtiments du bourg tombaient dans la rivière au bord de
     * laquelle le campement s'était posé.
     */
    public static boolean dry(World world, VillageLayout layout, int cx, int cz) {
        return wetBuilding(world, layout, cx, cz) == null;
    }

    /** Premier bâtiment (ou terrain, ou « place ») du plan dont l'emprise touche l'eau, ou null. */
    public static String wetBuilding(World world, VillageLayout layout, int cx, int cz) {
        if (FreePlans.enabled(layout)) {
            // Placement libre : le placeur évite lui-même l'eau ; le site ne convient pas s'il manque une place.
            FreePlanner.Result plan = FreePlans.plan(world, layout, cx, cz);
            return plan.ok() ? null : plan.failed();
        }
        Terrain terrain = new Terrain(world);
        List<String> names = new ArrayList<>();
        for (VillageLayout.Building b : layout.buildings()) {
            names.add(b.id());
        }
        for (VillageLayout.Plot p : layout.plots()) {
            names.add(p.id());
        }
        names.add("Place");
        List<int[]> rects = planRects(layout);
        for (int j = 0; j < rects.size(); j++) {
            int[] r = rects.get(j);
            for (int x = cx + r[0]; x <= cx + r[1]; x += WATER_STEP) {
                for (int z = cz + r[2]; z <= cz + r[3]; z += WATER_STEP) {
                    int h = terrain.surface(x, z);
                    if (h != Terrain.NONE && terrain.hasFluid(x, h + 1, z)) {
                        return names.get(j);
                    }
                }
            }
        }
        return null;
    }

    /**
     * À la fondation : retire des emprises du plan (paliers à venir et terrains, pas la place ni ce qui est collé tout
     * de suite) les blocs que la génération du monde y a laissés au-dessus du sol (ruines, camps, mobilier, chemins
     * surélevés, brindilles). Kevin 2026-11-18 : les blocs vanilla ne doivent pas gêner la génération ; le chantier ne
     * garde ainsi comme « blocs gênants » que ce que les joueurs posent ensuite. Rend le nombre de blocs retirés.
     */
    static int clearWild(World world, VillageLayout layout, int cx, int cz) {
        ChunkStore chunks = world.getChunkStore();
        Terrain terrain = new Terrain(world);
        int removed = 0;
        Set<Long> done = new HashSet<>();
        List<int[]> rects = new ArrayList<>();
        for (VillageLayout.Building b : layout.buildings()) {
            if (b.tier() > 0 || b.plot() != null) {
                rects.add(BuildingPaliers.reserved(layout, b));
            }
        }
        for (VillageLayout.Plot p : layout.plots()) {
            rects.add(p.rect());
        }
        for (int[] r : rects) {
            for (int x = cx + r[0]; x <= cx + r[1]; x++) {
                for (int z = cz + r[2]; z <= cz + r[3]; z++) {
                    if (!done.add(pack(x, z))) {
                        continue;
                    }
                    int top = terrain.surface(x, z);
                    if (top == Terrain.NONE) {
                        continue;
                    }
                    // Sol naturel sous un éventuel mur de ruine en pavés (compté comme « sol » par Terrain).
                    int h = top;
                    while (h > top - WILD_HEIGHT && !Chantiers.natural(String.valueOf(Blocks.name(chunks, x, h, z)))) {
                        h--;
                    }
                    for (int y = h + 1; y <= top + WILD_HEIGHT; y++) {
                        String name = Blocks.name(chunks, x, y, z);
                        if (name != null && !Chantiers.natural(name) && !People.isVillageSlab(name)) {
                            Blocks.set(chunks, x, y, z, null, 0);
                            removed++;
                        }
                    }
                }
            }
        }
        return removed;
    }
}
