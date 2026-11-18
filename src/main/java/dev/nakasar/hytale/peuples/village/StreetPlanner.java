package dev.nakasar.hytale.peuples.village;

import com.google.gson.Gson;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Sentiers et décor d'un village à placement libre ({@link FreePlans}), calculés en jeu une fois les bâtiments placés.
 * Même méthode que {@code tools/village_streets.py} pour les plans types (Kevin, 16 novembre 2026 : « un village qui
 * s'organise de manière plus agréable et vivante, avec des décorations, des petits sentiers ») :
 * <ul>
 *   <li>chaque bâtiment (et terrain) est relié, depuis la case devant sa façade côté cœur, à la place ou au sentier le
 *   plus proche déjà tracé par un A* qui contourne les emprises : réseau en arbre, palier par palier ;</li>
 *   <li>lignes lissées (Chaikin), épaissies (3 de large au campement, 2 ensuite), bords effilochés ;</li>
 *   <li>décor : lumières le long des sentiers, objets près des portes, bancs aux croisements, petits coins entre les
 *   bâtiments ; palier d'un élément = celui du sentier, relevé par les emprises voisines à venir (talus).</li>
 * </ul>
 * Styles par peuple (blocs et prefabs vanilla) : {@code nakasar-peuples/sentiers-styles.json}, écrit par le générateur.
 * Pur calcul : sortie au format des pas {@code Streets} des plans (« x z Bloc », « x z lacet P|B Id »).
 */
public final class StreetPlanner {

    /** Une emprise à desservir : id, palier, rectangle relatif au cœur, petit décor (pas de sentier vers lui). */
    public record Site(String id, int tier, int[] rect, boolean small) {
    }

    /** Sentiers et décor d'un palier. */
    public record Step(int tier, List<String> paths, List<String> decor) {
    }

    /** Fichier de styles (tools/village_streets.py). */
    public static final class Styles {
        Map<String, Integer> Constants;
        Map<String, Style> Styles;
        List<String> Gardens;
        Map<String, int[]> Bounds;

        public static Styles read(Reader reader) {
            return new Gson().fromJson(reader, Styles.class);
        }

        int constant(String key, int fallback) {
            Integer v = Constants == null ? null : Constants.get(key);
            return v == null ? fallback : v;
        }
    }

    static final class Style {
        String Path;
        String Speck;
        double SpeckShare;
        List<String> Light;
        List<String> Door;
        List<String> Bench;
        List<Pocket> Pocket;
    }

    static final class Pocket {
        int Weight;
        boolean Garden;
        List<String> Blocks;
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Styles styles;
    private final Style style;
    private final int talus;
    private final int wall;
    private final int lightEvery;
    private final int pocketGap;
    private final int pocketPath;
    private final int pocketWall;
    private final SplittableRandom rng;
    private final Noise noise;
    private final int plaza;
    private final int radius;
    private final int reach;
    private final List<Site> rects = new ArrayList<>();
    private final Set<Long> blocked = new HashSet<>();
    private final Set<Long> nearWall = new HashSet<>();
    private final Map<Long, Integer> network = new HashMap<>();
    private final Set<Long> taken = new HashSet<>();
    private final Set<Long> pathSet = new HashSet<>();
    private final Map<Integer, List<String>> decorByTier = new TreeMap<>();

    private StreetPlanner(Styles styles, Style style, int plaza, int radius, long seed) {
        this.styles = styles;
        this.style = style;
        this.talus = styles.constant("Talus", 10);
        this.wall = styles.constant("Wall", 1);
        this.lightEvery = styles.constant("LightEvery", 10);
        this.pocketGap = styles.constant("PocketGap", 7);
        this.pocketPath = styles.constant("PocketPath", 2);
        this.pocketWall = styles.constant("PocketWall", 3);
        this.rng = new SplittableRandom(seed);
        this.noise = new Noise(new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L), 7);
        this.plaza = plaza;
        this.radius = radius;
        this.reach = radius + 4;
    }

    /**
     * Pas {@code Streets} d'un village : `buildings` hors terrains, `plots` les terrains (rectangles relatifs au cœur),
     * `plaza` demi-côté de la place, `radius` rayon du village. Rend une liste vide pour un peuple sans style.
     */
    public static List<Step> plan(Styles styles, String people, List<Site> buildings, List<Site> plots, int plaza, int radius, long seed) {
        Style style = styles == null || styles.Styles == null ? null : styles.Styles.get(people);
        if (style == null) {
            return List.of();
        }
        return new StreetPlanner(styles, style, plaza, radius, seed).run(buildings, plots);
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int kx(long k) {
        return (int) (k >> 32);
    }

    private static int kz(long k) {
        return (int) k;
    }

    static int rectDist(int x, int z, int[] r) {
        int dx = Math.max(Math.max(r[0] - x, 0), x - r[1]);
        int dz = Math.max(Math.max(r[2] - z, 0), z - r[3]);
        return Math.max(dx, dz);
    }

    private boolean inPlaza(int x, int z, int pad) {
        return Math.abs(x) <= plaza + pad && Math.abs(z) <= plaza + pad;
    }

    private boolean insideAny(int x, int z, int pad) {
        for (Site s : rects) {
            if (rectDist(x, z, s.rect()) <= pad) {
                return true;
            }
        }
        return false;
    }

    private record Line(int tier, List<double[]> points, double width, String id) {
    }

    private record Door(int tier, int x, int z, int nx, int nz) {
    }

    private List<Step> run(List<Site> buildings, List<Site> plots) {
        for (Site b : buildings) {
            rects.add(b);
        }
        rects.addAll(plots);
        for (Site s : rects) {
            int[] r = s.rect();
            for (int x = r[0] - wall; x <= r[1] + wall; x++) {
                for (int z = r[2] - wall; z <= r[3] + wall; z++) {
                    blocked.add(key(x, z));
                }
            }
            for (int x = r[0] - wall - 2; x <= r[1] + wall + 2; x++) {
                for (int z = r[2] - wall - 2; z <= r[3] + wall + 2; z++) {
                    nearWall.add(key(x, z));
                }
            }
        }
        Set<Long> ring = new HashSet<>();
        for (int x = -plaza - 1; x <= plaza + 1; x++) {
            for (int z = -plaza - 1; z <= plaza + 1; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == plaza + 1) {
                    ring.add(key(x, z));
                }
            }
        }

        List<Site> targets = new ArrayList<>();
        for (Site b : buildings) {
            if (!b.small()) {
                targets.add(b);
            }
        }
        targets.addAll(plots);
        targets.sort((a, b) -> a.tier() != b.tier() ? Integer.compare(a.tier(), b.tier())
                : Integer.compare(rectDist(0, 0, a.rect()), rectDist(0, 0, b.rect())));
        List<Line> lines = new ArrayList<>();
        List<Door> doors = new ArrayList<>();
        for (Site t : targets) {
            int[] door = door(t.rect());
            if (inPlaza(door[0], door[1], 0)) {
                continue; // bâtiment collé à la place : la place sert de parvis
            }
            long start = key(door[0], door[1]);
            blocked.remove(start);
            List<Long> path = route(start, t.tier(), ring);
            if (path == null) {
                if (Boolean.getBoolean("nakasar.streets.debug")) {
                    System.err.println("sans sentier : " + t.id() + " porte " + door[0] + " " + door[1]);
                }
                continue;
            }
            doors.add(new Door(t.tier(), door[0], door[1], door[2], door[3]));
            List<double[]> pts = new ArrayList<>();
            for (long c : path) {
                pts.add(new double[] {kx(c), kz(c)});
            }
            lines.add(new Line(t.tier(), chaikin(pts, 2), t.tier() == 0 ? 1.5 : 1.0, t.id()));
            for (long c : path) {
                network.merge(c, t.tier(), Math::min);
            }
        }

        // Épaissit les lignes : cases à moins de `width` de la ligne, bord effiloché (1 case sur 3 du bord laissée).
        Map<Long, Integer> cells = new HashMap<>();
        Map<Long, Boolean> edge = new HashMap<>();
        for (Line line : lines) {
            double w = line.width();
            for (double[] p : densify(line.points(), 0.5)) {
                for (int x = (int) Math.floor(p[0] - w - 1); x < (int) Math.ceil(p[0] + w + 2); x++) {
                    for (int z = (int) Math.floor(p[1] - w - 1); z < (int) Math.ceil(p[1] + w + 2); z++) {
                        double d = Math.hypot(x - p[0], z - p[1]);
                        if (d > w + 0.35 || inPlaza(x, z, 0) || insideAny(x, z, 0)) {
                            continue;
                        }
                        long k = key(x, z);
                        if (cells.getOrDefault(k, 99) > line.tier()) {
                            cells.put(k, line.tier());
                        }
                        boolean core = d <= w - 0.5;
                        edge.put(k, edge.getOrDefault(k, true) && !core);
                    }
                }
            }
        }
        List<Long> sortedCells = new ArrayList<>(cells.keySet());
        sortedCells.sort((a, b) -> kx(a) != kx(b) ? Integer.compare(kx(a), kx(b)) : Integer.compare(kz(a), kz(b)));
        Map<Integer, List<String>> pathsByTier = new TreeMap<>();
        for (long k : sortedCells) {
            if (edge.get(k) && rng.nextDouble() < 0.3) {
                continue;
            }
            String block = rng.nextDouble() < style.SpeckShare ? style.Speck : style.Path;
            pathsByTier.computeIfAbsent(cells.get(k), t -> new ArrayList<>()).add(kx(k) + " " + kz(k) + " " + block);
            pathSet.add(k);
        }

        lights(lines);
        doorItems(doors);
        benches(lines);
        pockets();

        Set<Integer> tiers = new java.util.TreeSet<>(pathsByTier.keySet());
        tiers.addAll(decorByTier.keySet());
        List<Step> out = new ArrayList<>();
        for (int tier : tiers) {
            out.add(new Step(tier, pathsByTier.getOrDefault(tier, List.of()), decorByTier.getOrDefault(tier, List.of())));
        }
        return out;
    }

    /** Case devant l'emprise côté cœur (règle des plans types) et normale {x, z, nx, nz}. */
    private int[] door(int[] r) {
        int cx = Math.floorDiv(r[0] + r[1], 2);
        int cz = Math.floorDiv(r[2] + r[3], 2);
        if (Math.abs(cx) >= Math.abs(cz)) {
            return cx > 0 ? new int[] {r[0] - wall - 1, cz, -1, 0} : new int[] {r[1] + wall + 1, cz, 1, 0};
        }
        return cz > 0 ? new int[] {cx, r[2] - wall - 1, 0, -1} : new int[] {cx, r[3] + wall + 1, 0, 1};
    }

    private record Node(double d, long k) {
    }

    private List<Long> route(long start, int tier, Set<Long> ring) {
        Set<Long> goals = new HashSet<>(ring);
        for (Map.Entry<Long, Integer> e : network.entrySet()) {
            if (e.getValue() <= tier) {
                goals.add(e.getKey());
            }
        }
        if (goals.contains(start)) {
            return List.of(start);
        }
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<Node> heap = new PriorityQueue<>((a, b) -> Double.compare(a.d(), b.d()));
        dist.put(start, 0.0);
        heap.add(new Node(0.0, start));
        while (!heap.isEmpty()) {
            Node top = heap.poll();
            long cur = top.k();
            if (top.d() > dist.getOrDefault(cur, Double.MAX_VALUE)) {
                continue;
            }
            if (goals.contains(cur)) {
                List<Long> path = new ArrayList<>();
                path.add(cur);
                while (prev.containsKey(path.get(path.size() - 1))) {
                    path.add(prev.get(path.get(path.size() - 1)));
                }
                Collections.reverse(path);
                return path;
            }
            int x = kx(cur);
            int z = kz(cur);
            for (int[] s : STEPS) {
                int nx = x + s[0];
                int nz = z + s[1];
                long nk = key(nx, nz);
                if (Math.abs(nx) > reach || Math.abs(nz) > reach || inPlaza(nx, nz, 0) || (blocked.contains(nk) && nk != start)) {
                    continue;
                }
                if (s[0] != 0 && s[1] != 0 && (blocked.contains(key(x + s[0], z)) || blocked.contains(key(x, z + s[1])))) {
                    continue;
                }
                double step = s[0] != 0 && s[1] != 0 ? Math.sqrt(2) : 1.0;
                double cost = step * (1.0 + 1.2 * noise.at(nx, nz));
                if (nearWall.contains(nk)) {
                    cost += 0.6;
                }
                if (network.getOrDefault(nk, 99) <= tier) {
                    cost *= 0.35;
                }
                double nd = top.d() + cost;
                if (nd < dist.getOrDefault(nk, Double.MAX_VALUE)) {
                    dist.put(nk, nd);
                    prev.put(nk, cur);
                    heap.add(new Node(nd, nk));
                }
            }
        }
        return null;
    }

    private static List<double[]> chaikin(List<double[]> points, int rounds) {
        for (int i = 0; i < rounds; i++) {
            if (points.size() < 3) {
                return points;
            }
            List<double[]> out = new ArrayList<>();
            out.add(points.get(0));
            for (int j = 0; j + 1 < points.size(); j++) {
                double[] a = points.get(j);
                double[] b = points.get(j + 1);
                out.add(new double[] {0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
                out.add(new double[] {0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
            }
            out.add(points.get(points.size() - 1));
            points = out;
        }
        return points;
    }

    private static List<double[]> densify(List<double[]> points, double step) {
        List<double[]> out = new ArrayList<>();
        for (int j = 0; j + 1 < points.size(); j++) {
            double[] a = points.get(j);
            double[] b = points.get(j + 1);
            int n = Math.max(1, (int) (Math.hypot(b[0] - a[0], b[1] - a[1]) / step));
            for (int k = 0; k < n; k++) {
                out.add(new double[] {a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n});
            }
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    // ---- Décor ----

    private int decorTier(int x, int z, int base, boolean natural) {
        int reachDecor = natural ? talus : 3;
        int t = base;
        for (Site s : rects) {
            if (rectDist(x, z, s.rect()) <= reachDecor) {
                t = Math.max(t, s.tier());
            }
        }
        return t;
    }

    private List<long[]> footprint(String kind, String ident, int x, int z, int rot) {
        List<long[]> out = new ArrayList<>();
        if (kind.equals("B")) {
            out.add(new long[] {x, z});
            return out;
        }
        int[] b = styles.Bounds == null ? null : styles.Bounds.get(ident);
        if (b == null) {
            return null;
        }
        for (int ax = b[0]; ax <= b[1]; ax++) {
            for (int az = b[4]; az <= b[5]; az++) {
                int[] r = Placing.rotate(ax, az, rot);
                out.add(new long[] {x + r[0], z + r[1]});
            }
        }
        return out;
    }

    private boolean free(List<long[]> cells, int pathGap, int wallGap) {
        for (long[] c : cells) {
            int x = (int) c[0];
            int z = (int) c[1];
            if (taken.contains(key(x, z)) || inPlaza(x, z, 1) || insideAny(x, z, wallGap) || Math.max(Math.abs(x), Math.abs(z)) > reach) {
                return false;
            }
            for (int dx = -pathGap; dx <= pathGap; dx++) {
                for (int dz = -pathGap; dz <= pathGap; dz++) {
                    if (pathSet.contains(key(x + dx, z + dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean place(String kind, String ident, int x, int z, int rot, int baseTier, int pathGap, int wallGap, int halo) {
        List<long[]> fp = footprint(kind, ident, x, z, rot);
        if (fp == null || !free(fp, pathGap, wallGap)) {
            return false;
        }
        for (long[] c : fp) {
            for (int dx = -halo; dx <= halo; dx++) {
                for (int dz = -halo; dz <= halo; dz++) {
                    taken.add(key((int) c[0] + dx, (int) c[1] + dz));
                }
            }
        }
        boolean natural = (styles.Gardens != null && styles.Gardens.contains(ident)) || ident.startsWith("Plant_")
                || ident.startsWith("Rubble_") || ident.startsWith("Deco_Bone");
        int tier = decorTier(x, z, baseTier, natural);
        decorByTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(x + " " + z + " " + rot + " " + kind + " " + ident);
        return true;
    }

    private boolean place(String entry, int x, int z, int rot, int baseTier, int pathGap, int wallGap, int halo) {
        int sp = entry.indexOf(' ');
        return place(entry.substring(0, sp), entry.substring(sp + 1), x, z, rot, baseTier, pathGap, wallGap, halo);
    }

    /** Lacet (0 S, 1 E, 2 N, 3 O) d'un objet qui regarde dans la direction (nx, nz). */
    private static int facing(double nx, double nz) {
        if (Math.abs(nx) >= Math.abs(nz)) {
            return nx > 0 ? 1 : 3;
        }
        return nz > 0 ? 0 : 2;
    }

    private <T> T pick(List<T> list) {
        return list.get(rng.nextInt(list.size()));
    }

    private void lights(List<Line> lines) {
        for (Line line : lines) {
            List<double[]> dense = densify(line.points(), 1.0);
            int side = rng.nextDouble() < 0.5 ? 1 : -1;
            int walked = lightEvery / 2;
            for (int i = 1; i < dense.size() - 1; i++) {
                walked++;
                if (walked < lightEvery) {
                    continue;
                }
                double[] a = dense.get(i - 1);
                double[] b = dense.get(i + 1);
                double tx = b[0] - a[0];
                double tz = b[1] - a[1];
                double n = Math.hypot(tx, tz);
                if (n == 0) {
                    n = 1;
                }
                double nx = -tz / n * side;
                double nz = tx / n * side;
                for (double off : new double[] {line.width() + 1.2, line.width() + 2.2}) {
                    int lx = (int) Math.round(dense.get(i)[0] + nx * off);
                    int lz = (int) Math.round(dense.get(i)[1] + nz * off);
                    if (place(pick(style.Light), lx, lz, facing(-nx, -nz), line.tier(), 0, 2, 1)) {
                        walked = 0;
                        side = -side;
                        break;
                    }
                }
            }
        }
    }

    private void doorItems(List<Door> doors) {
        for (Door d : doors) {
            int tx = -d.nz();
            int tz = d.nx();
            int[] sides = rng.nextBoolean() ? new int[] {1, -1} : new int[] {-1, 1};
            int count = rng.nextInt(3) == 0 ? 1 : 2;
            for (int k = 0; k < count; k++) {
                int s = sides[k];
                for (int off : new int[] {2, 3}) {
                    int ox = d.x() + tx * s * off - d.nx();
                    int oz = d.z() + tz * s * off - d.nz();
                    if (place(pick(style.Door), ox, oz, facing(d.nx(), d.nz()), d.tier(), 0, 0, 0)) {
                        break;
                    }
                }
            }
        }
    }

    private void benches(List<Line> lines) {
        Map<Long, Set<String>> counts = new HashMap<>();
        for (Line line : lines) {
            Set<Long> seen = new HashSet<>();
            for (double[] p : line.points()) {
                seen.add(key((int) Math.round(p[0]), (int) Math.round(p[1])));
            }
            for (long c : seen) {
                counts.computeIfAbsent(c, k -> new HashSet<>()).add(line.id());
            }
        }
        List<Long> junctions = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> e : counts.entrySet()) {
            if (e.getValue().size() >= 2) {
                junctions.add(e.getKey());
            }
        }
        junctions.sort((a, b) -> {
            int da = Math.abs(kx(a)) + Math.abs(kz(a));
            int db = Math.abs(kx(b)) + Math.abs(kz(b));
            if (da != db) {
                return Integer.compare(da, db);
            }
            return kx(a) != kx(b) ? Integer.compare(kx(a), kx(b)) : Integer.compare(kz(a), kz(b));
        });
        int benches = 0;
        int[][] offs = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {3, 3}, {-3, -3}};
        for (long j : junctions) {
            if (benches >= 4 + lines.size() / 6) {
                break;
            }
            int jt = Math.min(network.getOrDefault(j, 0), 3);
            List<int[]> order = new ArrayList<>(List.of(offs));
            Collections.shuffle(order, new java.util.Random(rng.nextLong()));
            for (int[] o : order) {
                if (place(pick(style.Bench), kx(j) + o[0], kz(j) + o[1], facing(-o[0], -o[1]), jt, 0, 2, 1)) {
                    benches++;
                    break;
                }
            }
        }
    }

    private void pockets() {
        if (style.Pocket == null || style.Pocket.isEmpty()) {
            return;
        }
        List<long[]> candidates = new ArrayList<>();
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                if (Math.hypot(x, z) <= radius - 4 && !inPlaza(x, z, 2)) {
                    candidates.add(new long[] {x, z});
                }
            }
        }
        Collections.shuffle(candidates, new java.util.Random(rng.nextLong()));
        int total = 0;
        for (Pocket p : style.Pocket) {
            total += p.Weight;
        }
        List<int[]> centers = new ArrayList<>();
        outer:
        for (long[] c : candidates) {
            int x = (int) c[0];
            int z = (int) c[1];
            for (int[] o : centers) {
                if ((x - o[0]) * (x - o[0]) + (z - o[1]) * (z - o[1]) < pocketGap * pocketGap) {
                    continue outer;
                }
            }
            List<long[]> one = new ArrayList<>();
            one.add(new long[] {x, z});
            if (!free(one, pocketPath, pocketWall)) {
                continue;
            }
            // Un coin n'a de sens qu'au milieu du village bâti : un sentier à moins de 12 blocs ou une emprise à moins de 7.
            int nearPath = Integer.MAX_VALUE;
            for (Map.Entry<Long, Integer> e : network.entrySet()) {
                if (Math.abs(kx(e.getKey()) - x) <= 12 && Math.abs(kz(e.getKey()) - z) <= 12) {
                    nearPath = Math.min(nearPath, e.getValue());
                }
            }
            int nearRectDist = Integer.MAX_VALUE;
            int nearRectTier = 0;
            for (Site s : rects) {
                int d = rectDist(x, z, s.rect());
                if (d <= 7 && (d < nearRectDist || (d == nearRectDist && s.tier() < nearRectTier))) {
                    nearRectDist = d;
                    nearRectTier = s.tier();
                }
            }
            if (nearPath == Integer.MAX_VALUE && nearRectDist == Integer.MAX_VALUE) {
                continue;
            }
            int base = nearPath != Integer.MAX_VALUE ? nearPath : nearRectTier;
            int roll = rng.nextInt(total);
            Pocket kind = style.Pocket.get(style.Pocket.size() - 1);
            for (Pocket p : style.Pocket) {
                if (roll < p.Weight) {
                    kind = p;
                    break;
                }
                roll -= p.Weight;
            }
            if (kind.Garden) {
                if (styles.Gardens != null && !styles.Gardens.isEmpty()
                        && place("P", pick(styles.Gardens), x, z, rng.nextInt(4), base, pocketPath, pocketWall, 1)) {
                    centers.add(new int[] {x, z});
                }
                continue;
            }
            if (kind.Blocks == null || kind.Blocks.isEmpty()) {
                continue;
            }
            int n = 2 + rng.nextInt(4);
            int placed = 0;
            for (int i = 0; i < n * 3 && placed < n; i++) {
                int ox = x + rng.nextInt(5) - 2;
                int oz = z + rng.nextInt(5) - 2;
                if (place("B", pick(kind.Blocks), ox, oz, rng.nextInt(4), base, 1, pocketWall - 1, 0)) {
                    placed++;
                }
            }
            if (placed > 0) {
                centers.add(new int[] {x, z});
            }
        }
    }

    /** Bruit de valeur lissé 2D (déterministe), pour faire serpenter les chemins. */
    private static final class Noise {
        private final Map<Long, Double> values = new HashMap<>();
        private final SplittableRandom rng;
        private final int cell;

        Noise(SplittableRandom rng, int cell) {
            this.rng = rng;
            this.cell = cell;
        }

        private double v(int i, int j) {
            return values.computeIfAbsent(key(i, j), k -> rng.nextDouble());
        }

        double at(int x, int z) {
            double fx = (double) x / cell;
            double fz = (double) z / cell;
            int i = (int) Math.floor(fx);
            int j = (int) Math.floor(fz);
            double tx = fx - i;
            double tz = fz - j;
            tx = tx * tx * (3 - 2 * tx);
            tz = tz * tz * (3 - 2 * tz);
            double a = v(i, j) * (1 - tx) + v(i + 1, j) * tx;
            double b = v(i, j + 1) * (1 - tx) + v(i + 1, j + 1) * tx;
            return a * (1 - tz) + b * tz;
        }
    }
}
