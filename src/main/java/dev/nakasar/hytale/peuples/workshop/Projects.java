package dev.nakasar.hytale.peuples.workshop;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageBoard;
import dev.nakasar.hytale.peuples.world.TradeAccess;
import dev.nakasar.hytale.peuples.world.TradeRoutes;
import dev.nakasar.hytale.peuples.village.WorkshopState;

import java.util.ArrayList;
import java.util.List;

/**
 * Les catégories des projets de terrain (Kevin, 2026-11-16) : au plus un projet par catégorie, sauf l'Artisanat
 * (jusqu'à {@value #CRAFT_MAX}), et leurs effets sur le village.
 * <ul>
 *   <li>{@value #DEFENSE} (Caserne) : 1 + niveau gardes de plus autour du cœur en raid ; spécialité {@value #GARRISON} :
 *   +1 garde dans chaque lieu-dit tenu, chaque ouvrage et chaque expédition ; gardes des ouvrages tenus +25 % de vie par
 *   niveau (annex/Ouvrages.barracks) ;</li>
 *   <li>{@value #DIPLOMACY} (Ambassade, l'ancienne Maison du conseil) : règles du conseil (world/Allegiance) ;</li>
 *   <li>{@value #KNOWLEDGE} (Bibliothèque) : savoir-faire +{@value #KNOWLEDGE_PERCENT} % pour tous les bâtiments de métier ;</li>
 *   <li>{@value #CULT} (Temple) : essor +{@value #ESSOR_PERCENT} % ; spécialité {@value #PROTECTION} : un raid perdu ne
 *   brûle un bâtiment qu'une fois sur deux ;</li>
 *   <li>{@value #CRAFT} (ateliers) : consomment leurs {@code Inputs} chaque matin (workshop/Inputs) ;</li>
 *   <li>{@value #COMMERCE} (Halle marchande) : une place de route commerciale de plus par niveau ; spécialités
 *   {@value #BROKERS} (crédit de route) et {@value #WAREHOUSES} (accès plus long).</li>
 * </ul>
 * Les projets n'ont plus de spécialité à choisir (Kevin, 2026-11-21) : dès le niveau II, un projet a les effets de
 * toutes les « spécialités » de son catalogue ({@link Workshop#full()}), par exemple Milice et Garnison à la fois.
 */
public final class Projects {

    public static final String DEFENSE = "Defense";
    public static final String DIPLOMACY = "Diplomatie";
    public static final String KNOWLEDGE = "Savoir";
    public static final String CULT = "Culte";
    public static final String CRAFT = "Artisanat";
    public static final String COMMERCE = "Commerce";
    /** Halle marchande : une faveur versée au crédit d'une route vaut {@value #BROKER_CREDIT}. */
    public static final String BROKERS = "Courtiers";
    public static final int BROKER_CREDIT = 4;
    /** Halle marchande : une livraison de caravane ouvre l'accès {@value #WAREHOUSE_DAYS} jours. */
    public static final String WAREHOUSES = "Entrepots";
    public static final int WAREHOUSE_DAYS = 10;
    public static final int CRAFT_MAX = 3;
    public static final String GARRISON = "Garnison";
    public static final String MILITIA = "Milice";
    public static final String PROTECTION = "Protection";
    public static final int KNOWLEDGE_PERCENT = 15;
    /** Bibliothèque : les commandes de maître demandent un quart de moins ({@value #MASTERS_PERCENT} %). */
    public static final String MASTERS = "Maitres";
    public static final int MASTERS_PERCENT = 75;
    /** Bibliothèque : lieux-dits à moins de {@value #CARTOGRAPHERS_RANGE} blocs marqués sur la carte. */
    public static final String CARTOGRAPHERS = "Cartographes";
    public static final int CARTOGRAPHERS_RANGE = 600;
    public static final int ESSOR_PERCENT = 10;

    private Projects() {
    }

    /** Nombre de projets de la catégorie qu'un terrain peut encore accueillir dans ce village. */
    public static int max(String category) {
        return CRAFT.equals(category) ? CRAFT_MAX : 1;
    }

    /** Le projet ouvert (bâti, villageois arrivé) de cette catégorie, ou null. */
    public static Workshop opened(Village village, String category) {
        for (Workshop w : Workshops.opened(village)) {
            if (category.equals(w.category()) && !w.legacy()) {
                return w;
            }
        }
        return null;
    }

    /** Niveau du projet ouvert de la catégorie (0 sans). */
    public static int level(Village village, String category) {
        Workshop w = opened(village, category);
        return w == null ? 0 : village.workshop(w.getId()).level();
    }

    /** Vrai si le projet ouvert de la catégorie a cette spécialité (niveau II et plus ; toutes pour un bâtiment de projet). */
    public static boolean specialty(Village village, String category, String specialty) {
        Workshop w = opened(village, category);
        if (w == null) {
            return false;
        }
        WorkshopState s = village.workshop(w.getId());
        if (s.level() < Workshops.SPECIALTY_LEVEL) {
            return false;
        }
        // Bâtiment de projet sans spécialité à choisir : il a tous les effets de son catalogue dès le niveau II.
        return w.isFull() ? w.full().covers(specialty) : specialty.equals(s.specialty());
    }

    /** Gardes de plus de la Caserne en raid : 1 + son niveau. */
    public static int raidGuards(Village village) {
        int level = level(village, DEFENSE);
        return level <= 0 ? 0 : 1 + level;
    }

    /** +1 garde (lieux-dits, ouvrages, expéditions) avec la spécialité Garnison de la Caserne. */
    public static int garrisonBonus(Village village) {
        return village != null && specialty(village, DEFENSE, GARRISON) ? 1 : 0;
    }

    /** Savoir-faire gagné avec la Bibliothèque (+15 %, arrondi au-dessus). */
    public static int knowledge(Village village, int gained) {
        return gained > 0 && opened(village, KNOWLEDGE) != null ? (gained * (100 + KNOWLEDGE_PERCENT) + 99) / 100 : gained;
    }

    /**
     * Quantité d'une commande de maître pour ce village : un quart de moins (arrondi au-dessus) avec les Maîtres de la
     * Bibliothèque, pour la récolte, la fabrication et les plantations. Les autres demandes gardent leur quantité.
     */
    public static int masterNeeded(Village village, dev.nakasar.hytale.peuples.request.RequestTemplate t) {
        int needed = t.needed();
        if (!t.master() || needed <= 1 || village == null || !specialty(village, KNOWLEDGE, MASTERS)) {
            return needed;
        }
        return switch (t.type()) {
            case GATHER, CRAFT, PLANT -> Math.max(1, (needed * MASTERS_PERCENT + 99) / 100);
            default -> needed;
        };
    }

    /** Essor gagné avec le Temple (+10 %, arrondi au-dessus). */
    public static int essor(Village village, int gained) {
        return gained > 0 && opened(village, CULT) != null ? (gained * (100 + ESSOR_PERCENT) + 99) / 100 : gained;
    }

    // ---- Verrouillage par l'accès aux ressources (Kevin, 17 novembre 2026 ; doc des routes commerciales) ----

    /**
     * Ce qui manque au village pour choisir le projet `w` (champs {@code Requires}, {@code RequiresLuxuries},
     * {@code RequiresRoutes}), ou null. Une ressource compte si le village y a accès (route, demande) ou la produit
     * ({@link dev.nakasar.hytale.peuples.world.TradeAccess#available}).
     */
    public static Message projectMissing(World world, Village village, Workshop w) {
        if (w == null || world == null) {
            return null;
        }
        if (w.requires().length == 0 && w.requiresLuxuries() <= 0 && w.requiresRoutes() <= 0) {
            return null;
        }
        long day = GameClock.day(world.getEntityStore().getStore());
        @SuppressWarnings("unchecked")
        java.util.Map<String, dev.nakasar.hytale.peuples.world.TradeCatalogue.Source>[] catalogue = new java.util.Map[1];
        List<String> items = new ArrayList<>();
        for (String item : w.requires()) {
            if (!TradeAccess.available(world, village, item, day, catalogue)) {
                items.add(item);
            }
        }
        int luxuries = w.requiresLuxuries() <= 0 ? 0 : Math.max(0, w.requiresLuxuries() - TradeAccess.count(world, village, day));
        int routes = w.requiresRoutes() <= 0 ? 0 : Math.max(0, w.requiresRoutes() - TradeRoutes.used(world, village));
        if (items.isEmpty() && luxuries == 0 && routes == 0) {
            return null;
        }
        Message out = null;
        if (!items.isEmpty()) {
            out = Message.translation(Workshops.KEYS + "requires.items").param("list", names(items, "requires.and"));
        }
        if (luxuries > 0) {
            out = join(out, Message.translation(Workshops.KEYS + "requires.luxuries").param("count", w.requiresLuxuries()));
        }
        if (routes > 0) {
            out = join(out, Message.translation(Workshops.KEYS + "requires.routes").param("count", w.requiresRoutes()));
        }
        return out;
    }

    /** Ce qui manque pour la spécialité (une des ressources {@code Requires} au moins), ou null. */
    public static Message specialtyMissing(World world, Village village, Workshop.Specialty s) {
        if (s == null || world == null || s.requires().length == 0) {
            return null;
        }
        long day = GameClock.day(world.getEntityStore().getStore());
        @SuppressWarnings("unchecked")
        java.util.Map<String, dev.nakasar.hytale.peuples.world.TradeCatalogue.Source>[] catalogue = new java.util.Map[1];
        for (String item : s.requires()) {
            if (TradeAccess.available(world, village, item, day, catalogue)) {
                return null;
            }
        }
        return Message.translation(Workshops.KEYS + "requires.items").param("list", names(java.util.Arrays.asList(s.requires()), "requires.or"));
    }

    private static Message join(Message a, Message b) {
        return a == null ? b : Message.translation(Workshops.KEYS + "requires.join").param("a", a).param("b", b);
    }

    private static Message names(List<String> items, String glue) {
        Message out = null;
        for (String item : items) {
            Message one = VillageBoard.itemName(item);
            out = out == null ? one : Message.translation(Workshops.KEYS + glue).param("a", out).param("b", one);
        }
        return out == null ? Message.empty() : out;
    }

    /** Crédit d'une faveur versée à une route par ce village (Courtiers de la Halle marchande : 4, sinon 3). */
    public static int creditPerFavour(Village village) {
        return village != null && specialty(village, COMMERCE, BROKERS) ? BROKER_CREDIT : TradeRoutes.CREDIT_PER_FAVOUR;
    }

    /** Jours d'accès d'une livraison de caravane au village (Entrepôts de la Halle marchande : 10, sinon 7). */
    public static int deliveryDays(Village village) {
        return village != null && !village.isHamlet() && specialty(village, COMMERCE, WAREHOUSES) ? WAREHOUSE_DAYS : TradeAccess.DELIVERY_DAYS;
    }

    /** Vrai si le Temple protège le village de l'incendie de ce raid perdu (une fois sur deux). */
    public static boolean spared(Village village) {
        return specialty(village, CULT, PROTECTION) && java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
    }
}
