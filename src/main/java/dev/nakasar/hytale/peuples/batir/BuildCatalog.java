package dev.nakasar.hytale.peuples.batir;

import com.hypixel.hytale.builtin.adventure.shop.barter.BarterItemStack;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterShopAsset;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterTrade;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.annex.Annexes;
import dev.nakasar.hytale.peuples.reputation.Reputations;
import dev.nakasar.hytale.peuples.village.AnnexState;
import dev.nakasar.hytale.peuples.village.Chantiers;
import dev.nakasar.hytale.peuples.village.PlotState;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Tiers;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.village.WorkshopState;
import dev.nakasar.hytale.peuples.workshop.Projects;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.world.Allegiance;
import dev.nakasar.hytale.peuples.world.Hamlets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catalogue de l'onglet « Bâtir » (Kevin, 2026-11-21) : tous les bâtiments qu'un village a ou peut avoir, classés par
 * usage (sept familles) plutôt que par endroit. Chaque fiche dit ce que le bâtiment donne (objets vendus ou livrés, ou
 * un effet en quelques mots), où il se bâtit (village au palier, emplacement, avant-poste, ouvrage, lieu-dit), ce qu'il
 * coûte et ce qui manque encore. Les règles restent celles des pages d'origine ({@link Plots}, {@link Annexes}) : ce
 * catalogue ne fait que les lire.
 */
public final class BuildCatalog {

    public static final String KEYS = "nakasar_peuples.build.";

    /** Familles d'usage, dans l'ordre de la colonne de gauche ({@code FAMILIES} de tools/generate_build_tab.py). */
    public enum Family {
        RECOLTE("Recolte", "Tool_Pickaxe_Crude"),
        ARTISANAT("Artisanat", "Bench_WorkBench"),
        COMMERCE("Commerce", "Furniture_Village_Crate"),
        DIPLOMATIE("Diplomatie", "Recipe_Page"),
        MILITAIRE("Militaire", "Weapon_Shield_Wood"),
        SAVOIR("Savoir", "Furniture_Kweebec_Candle"),
        HABITATIONS("Habitations", "Furniture_Kweebec_Bed");

        public final String key;
        public final String icon;

        Family(String key, String icon) {
            this.key = key;
            this.icon = icon;
        }
    }

    /** Où se bâtit le bâtiment, dans l'ordre du filtre « Où » ({@code PLACES} du générateur). */
    public enum Place {
        VILLAGE("Village"), PLOT("Plot"), OUTPOST("Outpost"), WORK("Work"), HAMLET("Hamlet");

        public final String key;

        Place(String key) {
            this.key = key;
        }
    }

    /** Couleur du liseré de la carte, dans l'ordre de {@code STRIPES} du générateur. */
    public enum Stripe {
        BUILT, OPEN, WORKING, LOCKED, HAMLET, LEGACY
    }

    /** Filtre « Je cherche » : objets produits qui correspondent. */
    public enum Seek {
        WOOD("Wood", "Wood_Oak_Trunk", "^Wood_|^Ingredient_(Stick|Tree_)"),
        STONE("Stone", "Ingredient_Bar_Iron", "^(Rock_|Ore_|Ingredient_Bar_)"),
        FOOD("Food", "Food_Bread", "^Food_|^Plant_Crop_|^Plant_Fruit_|^Plant_Seeds_|Flour|Dough|Salt|Spice"),
        LEATHER("Leather", "Ingredient_Hide_Light", "Hide|Leather|Fabric|Bolt|Fibre|Strap|Feathers|Cotton"),
        ARMS("Arms", "Weapon_Sword_Iron", "^(Weapon_|Armor_)"),
        POTIONS("Potions", "Potion_Health_Lesser", "^Potion_|Essence|Health|Mana|Stamina");

        public final String key;
        public final String icon;
        final Pattern items;

        Seek(String key, String icon, String items) {
            this.key = key;
            this.icon = icon;
            this.items = Pattern.compile(items);
        }

        public boolean matches(Entry e) {
            for (String o : e.outputs()) {
                if (items.matcher(o).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Une condition pour bâtir : remplie, manquante, ou neutre (ok null : indication qu'on ne vérifie pas d'avance). */
    public record Req(Boolean ok, Message text) {
    }

    /**
     * Une fiche. {@code action} : choose, plots, grant, regive, outposts, works, domain, ou null ; {@code target} : le
     * terrain (projet) ou l'id du bâtiment (avant-poste, ouvrage) sur lequel l'action porte.
     */
    public record Entry(String key, Family family, boolean herd, Place place, String icon, Message name, List<String> outputs,
                        String fx, Message line, List<VillageLayout.Material> cost, Message costNote, List<Req> reqs,
                        Stripe stripe, Message state, Message where, Message note, String action, String target,
                        Workshop workshop) {
    }

    private static final Set<String> RECOLTE = Set.of("Ferme", "Mine", "Bosquet", "Scierie", "Pecherie", "Chasse", "Potager",
            "Puits", "Entree_Oasis", "Maison_Du_Chasseur", "Piege", "Entrepot", "Grand_Bassin", "Camp_Bucherons", "Carriere",
            "Citerne", "Cabane_De_Peche", "Puits_De_Basalte", "Grand_Potager");
    private static final Set<String> HERD = Set.of("Enclos", "Fosse_Aux_Betes", "Enclos_Oasis", "Bergerie_Du_Givre",
            "Bauge_De_Veille", "Chenil");
    private static final Set<String> COMMERCE = Set.of("Halle", "Caravanserail", "Comptoir", "Poste", "Echoppe", "Echoppe_Ouest");
    private static final Set<String> DIPLOMATIE = Set.of("Conseil");
    private static final Set<String> MILITAIRE = Set.of("Caserne", "Tour", "Fortin", "Forteresse", "Tour_Est", "Hutte_Pilier",
            "Tour_Du_Fort", "Porte_Du_Monastere");
    private static final Set<String> SAVOIR = Set.of("Bibliotheque", "Temple", "Sanctuaire", "Scriptorium", "Temple_De_Veille");
    /** Effets écrits (build.fx.*) : bâtiments qui ne vendent rien mais servent. */
    private static final Set<String> FX = Set.of("Tour", "Poste", "Fortin", "Forteresse", "Caserne", "Conseil", "Bibliotheque",
            "Scriptorium", "Temple", "Sanctuaire", "Halle", "Comptoir");

    private BuildCatalog() {
    }

    /** Tout le catalogue du village, famille par famille (élevages après la récolte), puis dans l'ordre de découverte. */
    public static List<Entry> of(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village village) {
        List<Entry> out = new ArrayList<>();
        VillageLayout layout = Chantiers.layout(village);
        if (layout != null) {
            villageBuildings(out, village, layout);
            projects(out, world, store, ref, village);
        }
        if (!village.isHamlet()) {
            outposts(out, world, store, ref, village);
            works(out, world, store, ref, village);
            hamlets(out, world, village);
        }
        List<Entry> sorted = new ArrayList<>();
        for (Family f : Family.values()) {
            for (boolean herd : new boolean[] {false, true}) {
                for (Entry e : out) {
                    if (e.family() == f && e.herd() == herd) {
                        sorted.add(e);
                    }
                }
            }
        }
        return sorted;
    }

    // ---- Village : bâtiments du plan bâtis avec le palier ----

    private static void villageBuildings(List<Entry> out, Village village, VillageLayout layout) {
        Map<String, Integer> homes = new LinkedHashMap<>();
        for (VillageLayout.Job j : layout.jobs()) {
            if (j.home() != null) {
                homes.merge(j.home(), 1, Integer::sum);
            }
        }
        for (VillageLayout.Building b : layout.buildings()) {
            if (b.plot() != null) {
                continue;
            }
            Workshop w = Plots.workshop(village, b.id());
            int home = homes.getOrDefault(b.id(), 0);
            Family family = family(b.id(), w);
            List<String> outputs = outputs(w);
            String fx = fx(b.id(), w, outputs, home, family);
            Message tier = Message.translation(Tiers.nameKey(b.tier()));
            boolean built = village.isBuilt(b.id());
            boolean reached = village.tier() >= b.tier();
            Stripe stripe = built ? Stripe.BUILT : reached ? Stripe.WORKING : Stripe.LOCKED;
            VillageLayout.Chantier chantier = layout.chantier(b.tier());
            List<VillageLayout.Material> cost = chantier == null ? List.of() : List.of(chantier.materials());
            List<Req> reqs = List.of(new Req(reached, Message.translation(KEYS + "req.tier").param("tier", tier)));
            Message name = Message.translation(w != null ? w.nameKey() : KEYS + "name." + b.id());
            out.add(new Entry("V/" + b.id(), family, herd(b.id(), w), Place.VILLAGE, icon(w, family), name, outputs, fx,
                    line(w, home, fx), cost, Message.translation(KEYS + "costNote.tier"), reqs, stripe,
                    built ? builtState(village, w) : Message.translation(KEYS + "state." + (reached ? "working" : "locked")),
                    Message.translation(KEYS + "cardWhere.Village").param("tier", tier),
                    Message.translation(KEYS + "note.tier"), null, null, w));
        }
    }

    // ---- Emplacements : un projet par id (le même projet peut aller sur plusieurs terrains) ----

    private static void projects(List<Entry> out, World world, Store<EntityStore> store, Ref<EntityStore> ref, Village village) {
        Map<String, List<VillageLayout.Building>> byProject = new LinkedHashMap<>();
        for (VillageLayout.Plot p : Plots.plots(village)) {
            for (VillageLayout.Building b : Plots.catalog(village, p.id())) {
                byProject.computeIfAbsent(Plots.projectId(b), k -> new ArrayList<>()).add(b);
            }
        }
        boolean rank = Reputations.rank(store, ref, village).atLeast(Plots.CHOOSE_RANK);
        for (Map.Entry<String, List<VillageLayout.Building>> e : byProject.entrySet()) {
            String project = e.getKey();
            VillageLayout.Building first = e.getValue().get(0);
            Workshop w = Plots.workshop(village, first.id());
            // Où en est le projet : bâti, en chantier ou choisi sur un terrain ; sinon le premier terrain libre où il irait.
            VillageLayout.Plot at = null;
            Stripe stripe = null;
            String state = null;
            VillageLayout.Plot free = null;
            VillageLayout.Building freeBuilding = null;
            VillageLayout.Plot lowest = null;
            for (VillageLayout.Building b : e.getValue()) {
                VillageLayout.Plot p = plot(village, b.plot());
                if (p == null) {
                    continue;
                }
                if (lowest == null || p.tier() < lowest.tier()) {
                    lowest = p;
                }
                PlotState s = village.plot(p.id());
                if (b.id().equals(s.project())) {
                    at = p;
                    if (village.isBuilt(b.id())) {
                        stripe = Stripe.BUILT;
                        state = "built";
                    } else if (s.working()) {
                        stripe = Stripe.WORKING;
                        state = "working";
                    } else if (stripe == null) {
                        stripe = Stripe.WORKING;
                        state = "chosen";
                    }
                } else if (free == null && Plots.isOpen(village, p) && !s.working() && (s.project() == null || !village.isBuilt(s.project()))) {
                    free = p;
                    freeBuilding = b;
                }
            }
            VillageLayout.Plot shown = at != null ? at : free != null ? free : lowest;
            if (shown == null) {
                continue;
            }
            VillageLayout.Building b = freeBuilding != null ? freeBuilding : first;
            String refusal = free == null ? null : Plots.refusal(world, village, free.id(), freeBuilding);
            List<Req> reqs = new ArrayList<>();
            reqs.add(new Req(Plots.isOpen(village, shown), Message.translation(KEYS + "req.tier")
                    .param("tier", Message.translation(Tiers.nameKey(shown.tier())))));
            reqs.add(new Req(rank, Message.translation(KEYS + "req.rank").param("rank", Message.translation(Plots.CHOOSE_RANK.nameKey()))));
            if (at == null) {
                reqs.add(new Req(free != null, Message.translation(KEYS + "req.plot")));
                reqs.add(new Req(!"taken".equals(refusal), Message.translation(KEYS + "req.unique")));
                if (w != null && w.category() != null) {
                    reqs.add(new Req(!"categoryFull".equals(refusal), Message.translation(KEYS + "req.category")));
                }
            }
            Message missing = Projects.projectMissing(world, village, w);
            if (w != null && (w.requires().length > 0 || w.requiresLuxuries() > 0 || w.requiresRoutes() > 0)) {
                reqs.add(missing == null ? new Req(true, Message.translation(KEYS + "req.resourcesOk"))
                        : new Req(false, Message.translation(KEYS + "req.resources").param("list", missing)));
            }
            if (stripe == null) {
                if (w != null && w.legacy()) {
                    stripe = Stripe.LEGACY;
                    state = "legacy";
                } else if (free != null && refusal == null && rank) {
                    stripe = Stripe.OPEN;
                    state = "open";
                } else {
                    stripe = Stripe.LOCKED;
                    state = "locked";
                }
            }
            Family family = family(project, w);
            List<String> outputs = outputs(w);
            String fx = fx(project, w, outputs, 0, family);
            Message st = "built".equals(state) ? builtState(village, w) : Message.translation(KEYS + "state." + state);
            String action = "open".equals(state) ? "choose" : "plots";
            String target = at != null ? at.id() : free != null ? free.id() : shown.id();
            out.add(new Entry("P/" + project, family, herd(project, w), Place.PLOT, icon(w, family),
                    Message.translation(w != null ? w.nameKey() : Chantiers.buildingKey(b.id())), outputs, fx, line(w, 0, fx),
                    List.of(shown.materials()), Message.translation(KEYS + "costNote.plot").param("hours", shown.hours()), reqs,
                    stripe, st, Message.translation(KEYS + "cardWhere.Plot"), null, action, "open".equals(state) ? b.id() + "@" + target : target, w));
        }
    }

    private static VillageLayout.Plot plot(Village village, String id) {
        for (VillageLayout.Plot p : Plots.plots(village)) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---- Avant-postes (production) et ouvrages (infrastructures), hors du village ----

    private static void outposts(List<Entry> out, World world, Store<EntityStore> store, Ref<EntityStore> ref, Village village) {
        for (Workshop w : Annexes.catalog(village)) {
            out.add(annex(world, store, ref, village, w, Place.OUTPOST));
        }
    }

    private static void works(List<Entry> out, World world, Store<EntityStore> store, Ref<EntityStore> ref, Village village) {
        for (Workshop w : Annexes.catalog(village, Workshop.Annex.INFRASTRUCTURE)) {
            out.add(annex(world, store, ref, village, w, Place.WORK));
        }
    }

    private static Entry annex(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village village, Workshop w, Place place) {
        Workshop.Annex spec = w.annex();
        List<AnnexState> counted = Annexes.counted(village, w);
        boolean built = false;
        boolean working = false;
        boolean granted = false;
        for (AnnexState a : counted) {
            built |= a.built();
            working |= a.working();
            granted |= a.granted();
        }
        boolean work = place == Place.WORK;
        int limit = work ? spec.limit() : 1;
        boolean reached = village.tier() >= spec.tier();
        boolean rank = Reputations.rank(store, ref, village).atLeast(Annexes.GRANT_RANK);
        boolean can = Annexes.block(store, ref, village, spec.tier()) == null;
        Map<String, Integer> missing = Annexes.missing(world, village, w);
        List<Req> reqs = new ArrayList<>();
        reqs.add(new Req(reached, Message.translation(KEYS + "req.tier").param("tier", Message.translation(Tiers.nameKey(spec.tier())))));
        reqs.add(new Req(rank, Message.translation(KEYS + "req.rank").param("rank", Message.translation(Annexes.GRANT_RANK.nameKey()))));
        if (work) {
            reqs.add(new Req(counted.size() < limit, Message.translation(KEYS + "req.limit").param("count", counted.size()).param("limit", limit)));
        }
        if (spec.rule() != null) {
            reqs.add(new Req(null, Message.translation(Annexes.KEYS + "hint." + spec.rule())));
        }
        if (counted.isEmpty() || granted) {
            reqs.add(missing == null || missing.isEmpty() ? new Req(missing != null, Message.translation(KEYS + "req.resourcesOk"))
                    : new Req(false, Message.translation(KEYS + "req.resources").param("list", Annexes.materialList(missing))));
        }
        Stripe stripe;
        Message state;
        if (work && !counted.isEmpty()) {
            stripe = working || granted ? Stripe.WORKING : Stripe.BUILT;
            state = Message.translation(KEYS + "state.count").param("count", counted.size()).param("limit", limit);
        } else if (built) {
            stripe = Stripe.BUILT;
            state = builtState(village, w);
        } else if (working) {
            stripe = Stripe.WORKING;
            state = Message.translation(KEYS + "state.working");
        } else if (granted) {
            stripe = Stripe.WORKING;
            state = Message.translation(KEYS + "state.granted");
        } else if (can) {
            stripe = Stripe.OPEN;
            state = Message.translation(KEYS + "state.open");
        } else {
            stripe = Stripe.LOCKED;
            state = Message.translation(KEYS + "state.locked");
        }
        String action;
        if (can && granted) {
            action = "regive";
        } else if (can && counted.size() < limit) {
            action = "grant";
        } else {
            action = work ? "works" : "outposts";
        }
        String base = spec.type();
        Family family = family(base, w);
        List<String> outputs = outputs(w);
        String fx = fx(base, w, outputs, 0, family);
        int max = spec.maxDistance() > 0 ? spec.maxDistance() : Annexes.MAX_DISTANCE;
        Message where = work ? Message.translation(KEYS + "cardWhere.Work").param("limit", limit) : Message.translation(KEYS + "cardWhere.Outpost");
        Message line = Message.translation(Annexes.KEYS + "role." + base);
        return new Entry((work ? "W/" : "O/") + w.getId(), family, herd(base, w), place, icon(w, family),
                Message.translation(w.nameKey()), outputs, fx, line, List.of(spec.materials()),
                Message.translation(KEYS + "costNote.annex").param("hours", spec.hours()).param("min", Annexes.minDistance(village, spec))
                        .param("max", max), reqs, stripe, state, where, null, action, w.getId(), w);
    }

    // ---- Lieux-dits : pas bâtis, ralliés ou pris (onglet Domaine) ----

    private static void hamlets(List<Entry> out, World world, Village village) {
        Set<String> held = new LinkedHashSet<>();
        for (Hamlets.Place h : Allegiance.holdings(world, village)) {
            if (h.type() != null) {
                held.add(h.type().type());
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Workshop w : Workshop.hamlets(village.people())) {
            String kind = w.hamlet();
            if (!seen.add(kind)) {
                continue;
            }
            boolean has = held.contains(kind);
            String base = w.getId().contains("_LD_") ? w.getId().substring(w.getId().indexOf("_LD_") + 4) : kind;
            Family family = family(base, w);
            List<String> outputs = outputs(w);
            String fx = outputs.isEmpty() ? ("Caserne".equals(base) ? "Hamlet_Caserne" : fx(base, w, outputs, 0, family)) : null;
            out.add(new Entry("H/" + w.getId(), family, herd(base, w), Place.HAMLET, icon(w, family), Message.translation(w.nameKey()),
                    outputs, fx, Message.translation(KEYS + "line.hamlet"), List.of(), null,
                    List.of(new Req(has, Message.translation(KEYS + "req.domain"))), has ? Stripe.BUILT : Stripe.HAMLET,
                    Message.translation(KEYS + "state." + (has ? "built" : "hamlet")), Message.translation(KEYS + "cardWhere.Hamlet"),
                    Message.translation(KEYS + "note.hamlet"), "domain", null, w));
        }
    }

    // ---- Classement ----

    /** Famille d'usage d'un bâtiment par son nom de base (projet, type d'avant-poste, genre de lieu-dit, id du plan). */
    static Family family(String base, Workshop w) {
        if (RECOLTE.contains(base) || herd(base, w)) {
            return Family.RECOLTE;
        }
        if (COMMERCE.contains(base)) {
            return Family.COMMERCE;
        }
        if (DIPLOMATIE.contains(base)) {
            return Family.DIPLOMATIE;
        }
        if (MILITAIRE.contains(base) || w == null && (base.startsWith("Tour") || base.startsWith("Affut") || base.startsWith("Muret")
                || base.startsWith("Avertissement"))) {
            return Family.MILITAIRE;
        }
        if (SAVOIR.contains(base)) {
            return Family.SAVOIR;
        }
        return w != null ? Family.ARTISANAT : Family.HABITATIONS;
    }

    static boolean herd(String base, Workshop w) {
        return HERD.contains(base) || base.startsWith("Deuxieme_") || w != null && w.isPen();
    }

    /** Effet à écrire sur la carte quand le bâtiment ne vend rien (clé sous build.fx.), ou null. */
    private static String fx(String base, Workshop w, List<String> outputs, int homes, Family family) {
        if (!outputs.isEmpty()) {
            return null;
        }
        for (String k : FX) {
            if (base.equals(k) || base.startsWith(k + "_")) {
                return k;
            }
        }
        if (w == null && family == Family.MILITAIRE) {
            return "Watch";
        }
        if (w == null && base.startsWith("Echoppe")) {
            return "Stall";
        }
        if (homes > 0) {
            return "Home";
        }
        return family == Family.HABITATIONS ? "Decor" : null;
    }

    private static String icon(Workshop w, Family family) {
        return w != null && w.icon() != null ? w.icon() : family.icon;
    }

    /** Phrase de la fiche : spécialités, villageois logés, ou effet. */
    private static Message line(Workshop w, int homes, String fx) {
        if (w != null && w.specialties().length > 0) {
            Message list = Message.empty();
            boolean first = true;
            for (Workshop.Specialty s : w.specialties()) {
                if (s == null || s.nameKey() == null) {
                    continue;
                }
                if (!first) {
                    list.insert(", ");
                }
                first = false;
                list.insert(Message.translation(s.nameKey()));
            }
            return Message.translation(KEYS + "line.specialties").param("list", list);
        }
        if (homes > 0) {
            return Message.translation(KEYS + "line.home").param("count", homes);
        }
        if (fx != null) {
            return Message.translation(KEYS + "fx." + fx);
        }
        return Message.translation(KEYS + "line.decor");
    }

    /** « Debout », ou le niveau d'un bâtiment de métier. */
    private static Message builtState(Village village, Workshop w) {
        WorkshopState ws = w == null ? null : village.workshopIfPresent(w.getId());
        return ws != null && ws.level() > 0 ? Message.translation(KEYS + "state.level").param("level", ws.level())
                : Message.translation(KEYS + "state.built");
    }

    /** Ce que le bâtiment donne : sa boutique, celles de ses spécialités, ses lots d'avant-poste. */
    static List<String> outputs(Workshop w) {
        Set<String> out = new LinkedHashSet<>();
        if (w == null) {
            return List.of();
        }
        shop(out, w.baseShop());
        for (Workshop.Specialty s : w.specialties()) {
            if (s == null) {
                continue;
            }
            shop(out, s.shop(2));
            shop(out, s.shop(3));
            if (s.shareItem() != null) {
                out.add(s.shareItem());
            }
        }
        if (w.annex() != null) {
            for (VillageLayout.Material m : w.annex().lots()) {
                out.add(m.itemId());
            }
            VillageLayout.Material d = w.annex().delivery();
            if (d != null) {
                out.add(d.itemId());
            }
        }
        return new ArrayList<>(out);
    }

    private static void shop(Set<String> out, String shopId) {
        BarterShopAsset shop = shopId == null ? null : BarterShopAsset.getAssetMap().getAsset(shopId);
        BarterTrade[] trades = shop == null ? null : shop.getTrades();
        if (trades == null) {
            return;
        }
        for (BarterTrade t : trades) {
            BarterItemStack o = t == null ? null : t.getOutput();
            if (o != null && o.getItemId() != null) {
                out.add(o.getItemId());
            }
        }
    }
}
