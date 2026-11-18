package dev.nakasar.hytale.peuples.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Placement libre des bâtiments d'un village (Kevin, 18 novembre 2026 : « placer le cœur du village, puis les autres
 * bâtiments librement sans qu'ils ne se chevauchent, de sorte que chaque village soit complètement différent », avec
 * des bâtiments aux tailles non conditionnées par des parcelles).
 *
 * <p>Chaque bâtiment est une « unité » de la taille de sa zone maximale (tous ses paliers d'apparence y tiennent), chaque
 * terrain de projet une unité de sa taille. Dans l'ordre des paliers (le campement d'abord, puis le hameau, le village
 * et le bourg), les plus grandes d'abord, chaque unité essaie {@value #CANDIDATES} places tirées au hasard autour du
 * cœur et prend la moins chère :
 * <ul>
 *   <li>refusée si elle sort du disque, touche la place, une autre unité (rue de {@value #GAP} blocs entre deux) ou de
 *   l'eau, ou si le sol n'est pas chargé ;</li>
 *   <li>coût : distance au cœur (village resserré qui grandit vers l'extérieur), relief sous l'emprise, écart de hauteur
 *   avec la place, plus une part de hasard pour que deux villages ne se ressemblent pas.</li>
 * </ul>
 * L'unité est tournée pour que la façade qui regardait la place dans le plan type regarde le cœur (porte, panneau de
 * métier et poste du PNJ restent devant).
 *
 * <p>Pur calcul (aucun appel au serveur) : le sol est lu par {@link Ground}, ce qui permet de le simuler hors du jeu.
 */
public final class FreePlanner {

    /** Rue minimale entre deux zones (comme les zones maximales des plans types). */
    public static final int GAP = 3;
    /** Écart minimal entre la place et une zone. */
    public static final int PLAZA_GAP = 3;
    /** Places essayées par unité. */
    public static final int CANDIDATES = 320;
    /** Pas de sondage du sol sous une emprise. */
    public static final int STEP = 2;
    /** Part de hasard dans le coût d'une place (en blocs de distance au cœur) : deux villages ne se ressemblent pas. */
    public static final double JITTER = 10;
    /** Au-delà de cet écart avec la place, la hauteur du bâtiment est bornée (même valeur que VillageBuilder.MAX_STEP). */
    public static final int MAX_STEP = 10;

    /** Sol relatif au cœur : hauteur de surface ({@link #NONE} si non chargé) et présence d'eau en surface. */
    public interface Ground {
        int height(int x, int z);

        boolean wet(int x, int z);
    }

    public static final int NONE = Integer.MIN_VALUE;

    /**
     * Une unité à placer : id (bâtiment, ou « @E1 » pour un terrain), palier, rectangle du plan type relatif au cœur,
     * pivot (case d'ancre du bâtiment, ou centre d'un terrain carré de taille paire : {@code corner}).
     */
    public record Unit(String id, int tier, int[] rect, int pivotX, int pivotZ, boolean corner) {
        int area() {
            return (rect[1] - rect[0] + 1) * (rect[3] - rect[2] + 1);
        }
    }

    /** Places trouvées (id → déplacement), ou l'id de la première unité sans place. */
    public record Result(Map<String, Placing> moves, Map<String, int[]> rects, String failed) {
        public boolean ok() {
            return failed == null;
        }
    }

    private FreePlanner() {
    }

    /** Lacet de la façade d'un rectangle qui regarde le cœur (règle des plans types : axe dominant). */
    public static int front(int[] rect) {
        int cx = Math.floorDiv(rect[0] + rect[1], 2);
        int cz = Math.floorDiv(rect[2] + rect[3], 2);
        return frontAt(cx, cz);
    }

    static int frontAt(int cx, int cz) {
        if (Math.abs(cx) >= Math.abs(cz)) {
            return cx > 0 ? 3 : 1;
        }
        return cz > 0 ? 2 : 0;
    }

    static boolean overlaps(int[] a, int[] b, int margin) {
        return !(a[1] + margin < b[0] || b[1] + margin < a[0] || a[3] + margin < b[2] || b[3] + margin < a[2]);
    }

    /**
     * Place toutes les unités. `plaza` : demi-côté de la place ; `maxRadius` : rayon du disque à ne pas dépasser ;
     * `h0` : hauteur de la place ; `seed` : graine du village (même graine, même sol : même plan).
     */
    public static Result place(List<Unit> units, int plaza, int maxRadius, int h0, Ground ground, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        List<Unit> order = new ArrayList<>(units);
        order.sort(Comparator.comparingInt(Unit::tier).thenComparing(Comparator.comparingInt(Unit::area).reversed())
                .thenComparing(Unit::id));
        Map<String, Placing> moves = new LinkedHashMap<>();
        Map<String, int[]> rects = new LinkedHashMap<>();
        int[] plazaRect = {-plaza, plaza, -plaza, plaza};
        double reach = plaza + PLAZA_GAP;
        for (Unit u : order) {
            int planFront = front(u.rect());
            double best = Double.MAX_VALUE;
            Placing bestMove = null;
            int[] bestRect = null;
            int half = Math.max(u.rect()[1] - u.rect()[0], u.rect()[3] - u.rect()[2]) / 2 + 1;
            List<int[]> anchors = new ArrayList<>(rects.values());
            anchors.add(plazaRect);
            for (int i = 0; i < CANDIDATES; i++) {
                int tx;
                int tz;
                if (i % 2 == 0) {
                    // Au hasard dans la couronne qui entoure ce qui est déjà placé.
                    double lo = plaza + PLAZA_GAP + half;
                    double hi = Math.min(maxRadius - half, reach + half + 16);
                    if (hi < lo) {
                        hi = lo;
                    }
                    double d = lo + (hi - lo) * Math.sqrt(rng.nextDouble());
                    double a = rng.nextDouble() * Math.PI * 2;
                    tx = (int) Math.round(Math.cos(a) * d);
                    tz = (int) Math.round(Math.sin(a) * d);
                } else {
                    // Contre une unité déjà placée (ou la place), de l'autre côté d'une rue : village serré, sans trous.
                    int[] o = anchors.get(rng.nextInt(anchors.size()));
                    int side = rng.nextInt(4);
                    int gap = GAP + 1 + rng.nextInt(3) + half;
                    int along = side < 2 ? o[2] + rng.nextInt(o[3] - o[2] + 1) : o[0] + rng.nextInt(o[1] - o[0] + 1);
                    tx = switch (side) { case 0 -> o[0] - gap; case 1 -> o[1] + gap; default -> along; };
                    tz = switch (side) { case 2 -> o[2] - gap; case 3 -> o[3] + gap; default -> along; };
                }
                int rot = (frontAt(tx, tz) - planFront) & 3;
                // Centre du rectangle tourné sur (tx, tz).
                Placing probe = u.corner() ? Placing.ofCorner(u.pivotX(), u.pivotZ(), u.pivotX(), u.pivotZ(), rot)
                        : Placing.ofAnchor(u.pivotX(), u.pivotZ(), u.pivotX(), u.pivotZ(), rot);
                int[] r0 = probe.rect(u.rect());
                int sx = tx - Math.floorDiv(r0[0] + r0[1], 2);
                int sz = tz - Math.floorDiv(r0[2] + r0[3], 2);
                Placing move = u.corner() ? Placing.ofCorner(u.pivotX(), u.pivotZ(), u.pivotX() + sx, u.pivotZ() + sz, rot)
                        : Placing.ofAnchor(u.pivotX(), u.pivotZ(), u.pivotX() + sx, u.pivotZ() + sz, rot);
                int[] r = move.rect(u.rect());
                double cost = cost(r, plazaRect, maxRadius, rects.values(), h0, ground);
                if (cost == Double.MAX_VALUE) {
                    continue;
                }
                cost += rng.nextDouble() * JITTER;
                if (cost < best) {
                    best = cost;
                    bestMove = move;
                    bestRect = r;
                }
            }
            if (bestMove == null) {
                return new Result(moves, rects, u.id());
            }
            moves.put(u.id(), bestMove);
            rects.put(u.id(), bestRect);
            for (int px : new int[] {bestRect[0], bestRect[1]}) {
                for (int pz : new int[] {bestRect[2], bestRect[3]}) {
                    reach = Math.max(reach, Math.hypot(px, pz));
                }
            }
        }
        return new Result(moves, rects, null);
    }

    private static double cost(int[] r, int[] plaza, int maxRadius, Iterable<int[]> placed, int h0, Ground ground) {
        for (int px : new int[] {r[0], r[1]}) {
            for (int pz : new int[] {r[2], r[3]}) {
                if (Math.hypot(px, pz) > maxRadius) {
                    return Double.MAX_VALUE;
                }
            }
        }
        if (overlaps(r, plaza, PLAZA_GAP)) {
            return Double.MAX_VALUE;
        }
        for (int[] o : placed) {
            if (overlaps(r, o, GAP)) {
                return Double.MAX_VALUE;
            }
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int samples = 0;
        int missing = 0;
        List<Integer> hs = new ArrayList<>();
        for (int x = r[0]; x <= r[1] + STEP - 1; x += STEP) {
            for (int z = r[2]; z <= r[3] + STEP - 1; z += STEP) {
                int sx = Math.min(x, r[1]);
                int sz = Math.min(z, r[3]);
                samples++;
                int h = ground.height(sx, sz);
                if (h == NONE) {
                    missing++;
                    continue;
                }
                if (ground.wet(sx, sz)) {
                    return Double.MAX_VALUE;
                }
                hs.add(h);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        if (hs.isEmpty() || missing * 4 > samples) {
            return Double.MAX_VALUE;
        }
        hs.sort(null);
        int median = hs.get(hs.size() / 2);
        int step = Math.abs(median - h0);
        double cx = (r[0] + r[1]) / 2.0;
        double cz = (r[2] + r[3]) / 2.0;
        double cost = Math.hypot(cx, cz);
        cost += 2.5 * (max - min);
        cost += 1.5 * Math.max(0, step - 2);
        if (step > MAX_STEP) {
            cost += 60 + 4 * (step - MAX_STEP);
        }
        return cost;
    }
}
