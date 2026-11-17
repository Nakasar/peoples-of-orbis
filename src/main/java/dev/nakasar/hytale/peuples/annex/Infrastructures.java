package dev.nakasar.hytale.peuples.annex;

import com.hypixel.hytale.server.core.universe.world.World;
import dev.nakasar.hytale.peuples.village.AnnexState;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.workshop.Workshop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les effets des infrastructures (Kevin, 2026-11-16, doc « Structurer les types de bâtiments ») : un ouvrage debout et
 * pas assiégé agit pour le village qui le tient, son propriétaire ou le rival qui l'occupe.
 * <ul>
 *   <li>Tour de garde : raid annoncé {@code Warn} s plus tôt par niveau, pour chaque tour à moins de
 *   {@value #TOWER_WARN_RANGE} blocs du cœur ; embuscades deux fois moins fréquentes à moins de
 *   {@value #TOWER_AMBUSH_RANGE} blocs ;</li>
 *   <li>Poste routier : plus aucune embuscade à moins de {@value #POST_RANGE} blocs ; halte de nuit : convois,
 *   caravanes et voyageurs marchent de nuit jusqu'au poste devant eux (à {@value #NIGHT_REACH} blocs de route au plus)
 *   et y dorment jusqu'au jour ({@link #nightHalt}) ;</li>
 *   <li>Fortin : le territoire du village s'étend de {@value #FORT_TERRITORY} blocs autour ; +1 garde aux lieux-dits
 *   tenus à moins de {@value #FORT_HAMLET_RANGE} blocs ; les expéditions (lieux-dits, ouvrages) partent du fortin tenu le
 *   plus proche de leur cible s'il en est plus près que le village ({@link #expeditionStart}) ;</li>
 *   <li>Forteresse : un raid perdu brûle un bâtiment sans faire perdre de palier ; +1 lieu-dit tenu, aussi pour
 *   l'occupant qui l'a prise.</li>
 * </ul>
 */
public final class Infrastructures {

    public static final String TOWER = "Tour";
    public static final String POST = "Poste";
    public static final String FORT = "Fortin";
    public static final String FORTRESS = "Forteresse";
    public static final int TOWER_WARN_RANGE = 160;
    public static final int TOWER_AMBUSH_RANGE = 48;
    public static final int POST_RANGE = 64;
    public static final int FORT_TERRITORY = 96;
    public static final int FORT_HAMLET_RANGE = 200;
    /** Un poste routier accueille la halte de nuit s'il est à moins de tant de blocs de la route. */
    public static final int HALT_RANGE = 24;
    /** Blocs de route qu'une file accepte encore de marcher de nuit pour atteindre le poste. */
    public static final int NIGHT_REACH = 300;
    /** La file s'arrête à tant de blocs avant le point de la route le plus proche du poste. */
    public static final double HALT_BEFORE = 3;

    /** Un ouvrage du monde : son village propriétaire, son bâtiment de métier et son état. */
    public record Work(Village owner, Workshop workshop, AnnexState state) {

        public String type() {
            return workshop.annex().type();
        }

        /** Id du village qui tient l'ouvrage (l'occupant s'il est pris). */
        public String holder() {
            return state.occupied() ? state.occupant() : owner.id();
        }

        /** Debout et pas assiégé : son effet joue. */
        public boolean active() {
            return state.built() && !state.besieged();
        }

        public int level() {
            return Math.max(1, owner.workshop(workshop.getId()).level());
        }

        public double distanceSquared(double x, double z) {
            return state.distanceSquared(x, z);
        }
    }

    private Infrastructures() {
    }

    /** Tous les ouvrages du monde (fondation posée : chantier, debout, ruine), propriétaires confondus. */
    public static List<Work> all(World world) {
        List<Work> out = new ArrayList<>();
        for (Village v : Villages.registry(world).all()) {
            for (AnnexState a : v.annexes()) {
                Workshop w = Workshop.get(a.id());
                if (w != null && w.isAnnex() && w.annex().infrastructure() && !a.granted()) {
                    out.add(new Work(v, w, a));
                }
            }
        }
        return out;
    }

    /** Ouvrages actifs du type `type` tenus par `village` (les siens non pris, et ceux qu'il occupe). */
    public static List<Work> held(World world, Village village, String type) {
        List<Work> out = new ArrayList<>();
        for (Work w : all(world)) {
            if (w.active() && (type == null || w.type().equals(type)) && w.holder().equals(village.id())) {
                out.add(w);
            }
        }
        return out;
    }

    /** Secondes d'annonce de plus avant un raid : tours de garde tenues à moins de {@value #TOWER_WARN_RANGE} blocs. */
    public static int raidWarnBonus(World world, Village village) {
        int bonus = 0;
        for (Work w : held(world, village, TOWER)) {
            if (village.horizontalDistanceSquared(w.state().x(), w.state().z()) <= (double) TOWER_WARN_RANGE * TOWER_WARN_RANGE) {
                bonus += w.workshop().annex().warn() * w.level();
            }
        }
        return bonus;
    }

    /** Vrai si une embuscade en (x, z) est évitée : poste routier proche, ou une fois sur deux près d'une tour. */
    public static boolean spares(World world, double x, double z) {
        boolean tower = false;
        for (Work w : all(world)) {
            if (!w.active()) {
                continue;
            }
            double d = w.distanceSquared(x, z);
            if (POST.equals(w.type()) && d <= (double) POST_RANGE * POST_RANGE) {
                return true;
            }
            if (TOWER.equals(w.type()) && d <= (double) TOWER_AMBUSH_RANGE * TOWER_AMBUSH_RANGE) {
                tower = true;
            }
        }
        return tower && ThreadLocalRandom.current().nextBoolean();
    }

    /**
     * Halte de nuit (Kevin, 2026-11-17) : abscisse, sur la route `path`, du poste routier actif le plus proche devant la
     * file partie de `from` (à moins de {@value #NIGHT_REACH} blocs de route, ou déjà dessus), ou -1. Un poste tenu par
     * un rival du village de la file ne l'accueille pas.
     */
    public static double nightHalt(World world, Village from, Routes.Path path, double at) {
        if (path == null) {
            return -1;
        }
        double best = -1;
        for (Work w : all(world)) {
            if (!w.active() || !POST.equals(w.type())) {
                continue;
            }
            Village holder = Villages.registry(world).byId(w.holder());
            if (holder == null || (from != null && holder != from
                    && dev.nakasar.hytale.peuples.world.Relations.value(world, holder, from) <= dev.nakasar.hytale.peuples.world.Relations.RIVAL)) {
                continue;
            }
            double s = path.closest(w.state().x(), w.state().z(), at - HALT_BEFORE - 1, at + NIGHT_REACH);
            double[] p = path.point(s);
            double dx = p[0] - w.state().x();
            double dz = p[1] - w.state().z();
            if (dx * dx + dz * dz > (double) HALT_RANGE * HALT_RANGE || s >= path.length() - HALT_BEFORE) {
                continue;
            }
            if (best < 0 || s < best) {
                best = s;
            }
        }
        return best;
    }

    /** Vrai si la file doit encore marcher de nuit : un poste routier l'attend devant (sinon elle s'arrête où elle est). */
    public static boolean nightWalk(World world, Village from, Routes.Path path, double at) {
        double halt = nightHalt(world, from, path, at);
        return halt >= 0 && at < halt - HALT_BEFORE;
    }

    /** Village maître du terrain en (x, z) grâce à un fortin actif, ou null. */
    public static Village territory(World world, double x, double z) {
        for (Work w : all(world)) {
            if (w.active() && FORT.equals(w.type()) && w.distanceSquared(x, z) <= (double) FORT_TERRITORY * FORT_TERRITORY) {
                return Villages.registry(world).byId(w.holder());
            }
        }
        return null;
    }

    /**
     * Fortin d'où part une expédition de `village` vers (x, z) (Kevin, 2026-11-17) : le fortin actif qu'il tient le plus
     * près de la cible, s'il en est plus près que le cœur du village ; null sinon (départ du village).
     */
    public static Work expeditionFort(World world, Village village, double x, double z) {
        if (world == null || village == null) {
            return null;
        }
        double best = village.horizontalDistanceSquared(x, z);
        Work out = null;
        for (Work w : held(world, village, FORT)) {
            double d = w.distanceSquared(x, z);
            if (d < best) {
                best = d;
                out = w;
            }
        }
        return out;
    }

    /** Point de départ (x, z) d'une expédition de `village` vers (x, z) : son fortin le plus avancé, sinon son cœur. */
    public static double[] expeditionStart(World world, Village village, double x, double z) {
        Work f = expeditionFort(world, village, x, z);
        return f == null ? new double[] {village.x() + 0.5, village.z() + 0.5} : new double[] {f.state().x() + 0.5, f.state().z() + 0.5};
    }

    /** +1 garde au lieu-dit en (x, z) si `owner` tient un fortin à moins de {@value #FORT_HAMLET_RANGE} blocs. */
    public static int hamletGuards(World world, Village owner, double x, double z) {
        for (Work w : held(world, owner, FORT)) {
            if (w.distanceSquared(x, z) <= (double) FORT_HAMLET_RANGE * FORT_HAMLET_RANGE) {
                return 1;
            }
        }
        return 0;
    }


    /**
     * +1 lieu-dit tenu si le village tient une forteresse debout : la sienne pas prise, ou celle d'un rival qu'il occupe
     * (Kevin, 2026-11-17). Un siège en cours ne la retire pas du plafond, sinon chaque assaut ferait perdre un lieu-dit.
     */
    public static int fortressBonus(World world, Village village) {
        for (Work w : all(world)) {
            if (FORTRESS.equals(w.type()) && w.state().built() && w.holder().equals(village.id())) {
                return 1;
            }
        }
        return 0;
    }

    /** Vrai si un raid perdu ne fait pas perdre de palier au village (il tient une forteresse). */
    public static boolean keepsTier(World world, Village village) {
        return !held(world, village, FORTRESS).isEmpty();
    }

    /** Ouvrages du village (les siens, pas perdus) visables par un raid : debout, à moins de 400 blocs du cœur. */
    public static List<Work> raidTargets(World world, Village village) {
        List<Work> out = new ArrayList<>();
        for (Work w : all(world)) {
            if (w.owner() == village && w.state().built() && !w.state().occupied() && !w.state().besieged()
                    && village.horizontalDistanceSquared(w.state().x(), w.state().z()) <= 400.0 * 400.0) {
                out.add(w);
            }
        }
        return out;
    }
}
