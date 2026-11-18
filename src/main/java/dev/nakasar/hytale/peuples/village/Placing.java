package dev.nakasar.hytale.peuples.village;

/**
 * Déplacement rigide d'un bâtiment (ou d'un terrain) du plan type vers sa place dans un village à placement libre
 * ({@link FreePlans}) : rotation de {@code rot} quarts de tour (même convention que PrefabRotation : (x, z) devient
 * (z, -x) à chaque quart) autour d'un pivot, puis translation. Tout ce que le plan type situe par rapport au bâtiment
 * (point de collage, emprise, zone maximale, versions des paliers, panneau de métier, poste et logis des PNJ, enclos,
 * décor) suit le même déplacement, ce qui garde intactes les données des générateurs.
 *
 * <p>Les pivots sont en demi-blocs (coordonnées doublées) : le centre de la case d'ancre d'un bâtiment ({@code 2x + 1})
 * ou le coin commun aux quatre cases centrales d'un terrain carré de taille paire ({@code 2x}), pour que la rotation
 * d'un ensemble de cases tombe exactement sur des cases.
 */
public record Placing(int from2X, int from2Z, int to2X, int to2Z, int rot) {

    /** Déplacement d'un bâtiment dont la case d'ancre (fx, fz) du plan va en (tx, tz), tourné de `rot`. */
    public static Placing ofAnchor(int fx, int fz, int tx, int tz, int rot) {
        return new Placing(2 * fx + 1, 2 * fz + 1, 2 * tx + 1, 2 * tz + 1, rot & 3);
    }

    /** Déplacement d'un terrain carré de taille paire centré (coin) en (fx, fz) vers (tx, tz). */
    public static Placing ofCorner(int fx, int fz, int tx, int tz, int rot) {
        return new Placing(2 * fx, 2 * fz, 2 * tx, 2 * tz, rot & 3);
    }

    /** Rotation de `rot` quarts de tour d'un vecteur (convention PrefabRotation). */
    public static int[] rotate(int x, int z, int rot) {
        for (int i = 0; i < (rot & 3); i++) {
            int t = x;
            x = z;
            z = -t;
        }
        return new int[] {x, z};
    }

    /** La case (x, z) du plan, à sa place dans le village. */
    public int[] point(int x, int z) {
        int[] r = rotate(2 * x + 1 - from2X, 2 * z + 1 - from2Z, rot);
        return new int[] {Math.floorDiv(to2X + r[0] - 1, 2), Math.floorDiv(to2Z + r[1] - 1, 2)};
    }

    /** Le rectangle de cases {x0, x1, z0, z1} du plan, à sa place dans le village. */
    public int[] rect(int[] r) {
        if (r == null) {
            return null;
        }
        int[] a = point(r[0], r[2]);
        int[] b = point(r[1], r[3]);
        return new int[] {Math.min(a[0], b[0]), Math.max(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[1], b[1])};
    }

    /** Lacet (0 sud, 1 est, 2 nord, 3 ouest) d'un objet du plan, une fois le bâtiment tourné. */
    public int yaw(int yaw) {
        return (yaw + rot) & 3;
    }

    /** Emprise relative au point de collage {minX, maxX, minZ, maxZ} après la rotation. */
    public int[] extents(int minX, int maxX, int minZ, int maxZ) {
        int[] a = rotate(minX, minZ, rot);
        int[] b = rotate(maxX, maxZ, rot);
        return new int[] {Math.min(a[0], b[0]), Math.max(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[1], b[1])};
    }

    /** Vrai si le déplacement ne change rien. */
    public boolean identity() {
        return rot == 0 && from2X == to2X && from2Z == to2Z;
    }

    /** Écriture compacte « x z rot » (cases d'arrivée, pivot de même nature que le départ). */
    public String encode() {
        return Math.floorDiv(to2X, 2) + " " + Math.floorDiv(to2Z, 2) + " " + rot;
    }
}
