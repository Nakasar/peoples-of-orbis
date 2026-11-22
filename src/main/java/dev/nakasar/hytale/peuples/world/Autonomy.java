package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.PeuplesPlugin;
import dev.nakasar.hytale.peuples.annex.Annexes;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.village.AnnexState;
import dev.nakasar.hytale.peuples.village.Chantiers;
import dev.nakasar.hytale.peuples.village.PlotState;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Terrain;
import dev.nakasar.hytale.peuples.village.Tiers;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.village.WorkshopState;
import dev.nakasar.hytale.peuples.workshop.Inputs;
import dev.nakasar.hytale.peuples.workshop.Livestock;
import dev.nakasar.hytale.peuples.workshop.Projects;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.Workshops;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les villages qui grandissent seuls (Kevin, 22 novembre 2026 : « qu'un village puisse progresser par lui-même en
 * accumulant les ressources nécessaires, établisse ses routes commerciales, construise ses bâtiments, monte de palier,
 * progresse son essor »). Document de conception : https://claude.ai/artifact/WYnNUNiD3EvpegixLFt1m7.
 * <ul>
 *   <li><b>Récolte</b> (chaque matin, dépôts chargés par la veille) : le village verse à son dépôt les matériaux de
 *   construction de son peuple qu'il met de côté (chantier du palier suivant, montée d'atelier prête, projet de terrain
 *   choisi par le conseil, avant-poste prévu) au rythme de {@link #days} jours par palier, plus les ressources du jour de
 *   ses ateliers quand ce sont des matériaux de son peuple. Jamais de luxe : les luxes viennent des routes.</li>
 *   <li><b>Essor</b> : {@link #essorPerDay} par jour, pour qu'un village seul passe les paliers en 6, 14 puis 30 jours de
 *   jeu (Kevin). Les demandes des joueurs, les caravanes et les lieux-dits s'y ajoutent.</li>
 *   <li><b>Conseil du matin</b> : savoir-faire des ateliers qui tournent ({@value #KNOWLEDGE_PER_DAY} par jour),
 *   commande de maître tenue pour faite au bout de {@value #MASTER_DAYS} jours, montée lancée quand les matériaux sont
 *   là, spécialité et projet de terrain choisis selon les besoins (les siens, ceux qu'il a appris, ceux de ses
 *   partenaires) puis ses relations commerciales, et au plus {@value #ANNEX_MAX} avant-postes fondés seuls au bourg.
 *   Les joueurs gardent la main : {@value #PLAYER_DAYS} jours pour choisir avant le conseil, et un choix de joueur n'est
 *   jamais défait.</li>
 * </ul>
 * Réglage par monde ({@code /monde veille --autonomie on|off}, partie Calendar du registre) ; sans réglage, la propriété
 * JVM {@value #PROP_ON}, sinon active.
 *
 * <p>État sauvé ({@link WorldLifeRegistry.Part#AUTONOMY}) : {@code M:village|atelier} jour où la commande de maître est
 * devenue possible, {@code S:village|atelier} jour où la spécialité est devenue possible, {@code O:village|terrain} jour
 * où le terrain s'est ouvert libre, {@code P:village|terrain} projet réservé par le conseil, {@code PL:village} dernier
 * terrain réservé, {@code A:village} avant-poste prévu (atelier|essai), {@code AL:village} dernier avant-poste fondé.
 */
public final class Autonomy {

    public static final String KEYS = WorldLife.KEYS + "autonomy.";
    public static final String PROP_ON = "nakasar.peuples.autonomie";
    /** Propriétaire d'un avant-poste fondé par le conseil (champ Owner de l'avant-poste). */
    public static final String OWNER = "village";

    private static final String KEY_ON = "autonomie";

    /** Jours de jeu visés par palier sans joueur : 0 → 1, 1 → 2, 2 → 3 (Kevin, 22 novembre 2026). */
    private static final int[] DAYS = {6, 14, 30};
    /** Savoir-faire gagné par jour par un atelier qui tourne. */
    public static final int KNOWLEDGE_PER_DAY = 10;
    /** Jours d'attente de la commande de maître avant que le conseil la tienne pour faite. */
    public static final int MASTER_DAYS = 5;
    /** Jours laissés aux joueurs pour choisir une spécialité ou un projet avant le conseil. */
    public static final int PLAYER_DAYS = 3;
    /** Jours entre deux terrains réservés par le conseil. */
    public static final int PLOT_EVERY = 3;
    /** Avant-postes fondés seuls au plus par village (Kevin). */
    public static final int ANNEX_MAX = 2;
    /** Palier où le conseil fonde des avant-postes. */
    public static final int ANNEX_TIER = 3;
    /** Jours entre deux avant-postes fondés seuls. */
    public static final int ANNEX_EVERY = 4;
    /** Directions essayées autour du village pour un avant-poste. */
    static final int ANNEX_TRIES = 12;
    /** Routes autonomes au plus par village (au lieu du plafond du monde). */
    public static final int ROUTES_PER_VILLAGE = 2;

    /** Matériaux de construction de chaque peuple, par plan (calculés une fois). */
    private static final Map<String, Set<String>> HARVESTABLE = new ConcurrentHashMap<>();
    /** Terrains du conseil sans matériaux ce jour (pas de nouvelle tenue pour ça avant demain). */
    private static final Map<String, Long> SHORT = new ConcurrentHashMap<>();

    private Autonomy() {
    }

    // ---- Réglages ----

    public static boolean on(World world) {
        String v = WorldLife.registry(world).get(WorldLifeRegistry.Part.CALENDAR, KEY_ON);
        if (v == null) {
            v = System.getProperty(PROP_ON);
        }
        return v == null || !v.trim().equalsIgnoreCase("false");
    }

    public static void setOn(World world, boolean value) {
        WorldLife.registry(world).put(WorldLifeRegistry.Part.CALENDAR, KEY_ON, Boolean.toString(value));
    }

    /** Jours de jeu visés sans joueur pour passer du palier `tier` au suivant. */
    public static int days(int tier) {
        return DAYS[Math.max(0, Math.min(DAYS.length - 1, tier))];
    }

    /** Essor gagné seul chaque jour au palier `tier` (0 au bourg). */
    public static int essorPerDay(int tier) {
        int threshold = Tiers.threshold(tier);
        return threshold <= 0 ? 0 : (threshold + days(tier) - 1) / days(tier);
    }

    private static WorldLifeRegistry reg(World world) {
        return WorldLife.registry(world);
    }

    private static String get(World world, String key) {
        return reg(world).get(WorldLifeRegistry.Part.AUTONOMY, key);
    }

    private static void put(World world, String key, String value) {
        reg(world).put(WorldLifeRegistry.Part.AUTONOMY, key, value);
    }

    private static long getLong(World world, String key) {
        return reg(world).getLong(WorldLifeRegistry.Part.AUTONOMY, key, Long.MIN_VALUE);
    }

    /** Jour noté sous `key`, noté à `day` s'il ne l'était pas. */
    private static long since(World world, String key, long day) {
        long d = getLong(world, key);
        if (d == Long.MIN_VALUE) {
            put(world, key, Long.toString(day));
            return day;
        }
        return d;
    }

    private static boolean grows(Village v) {
        return !v.isHamlet() && Chantiers.layout(v) != null;
    }

    // ---- Récolte et essor (matin, avant les besoins) ----

    /** Récolte et essor du jour de chaque village. Thread du monde, dépôts chargés. */
    static void harvest(World world, Store<EntityStore> store, long day) {
        if (!on(world)) {
            return;
        }
        for (Village v : new ArrayList<>(Villages.registry(world).all())) {
            if (!grows(v)) {
                continue;
            }
            Map<String, Integer> goods = harvestOf(world, v);
            if (!goods.isEmpty()) {
                List<ItemStack> stacks = new ArrayList<>();
                for (Map.Entry<String, Integer> e : goods.entrySet()) {
                    stacks.add(new ItemStack(e.getKey(), e.getValue()));
                }
                TradeRoutes.deliver(world, v, stacks);
                log("récolte de %s : %s", v.id(), goods);
            }
            if (v.tier() < Tiers.MAX) {
                Villages.addEssor(v, essorPerDay(v.tier()));
            }
        }
    }

    /** Ce que le village récolte ce matin (objet → quantité). */
    public static Map<String, Integer> harvestOf(World world, Village v) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> ours = harvestable(v);
        if (ours.isEmpty()) {
            return out;
        }
        Map<String, Integer> have = new HashMap<>();
        Map<String, Integer> deposit = Villages.depositContents(world, v);
        if (deposit != null) {
            have.putAll(deposit);
        }
        TradeRoutes.inbox(world, v).forEach((k, q) -> have.merge(k, q, Integer::sum));
        int days = days(v.tier());
        Map<String, Integer> target = new LinkedHashMap<>();
        for (VillageLayout.Material m : saving(world, v)) {
            if (ours.contains(m.itemId())) {
                target.merge(m.itemId(), m.quantity(), Integer::sum);
            }
        }
        Map<String, Integer> rate = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : target.entrySet()) {
            rate.put(e.getKey(), Math.max(1, (e.getValue() + days - 1) / days));
        }
        // Ressources du jour des ateliers ouverts, quand ce sont des matériaux du peuple : deux jours d'avance.
        for (Workshop w : Workshops.opened(v)) {
            for (Map.Entry<String, Integer> e : Inputs.needs(v, w).entrySet()) {
                if (ours.contains(e.getKey())) {
                    target.merge(e.getKey(), 2 * e.getValue(), Integer::sum);
                    rate.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> e : target.entrySet()) {
            int missing = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (missing > 0) {
                out.put(e.getKey(), Math.min(missing, rate.getOrDefault(e.getKey(), 1)));
            }
        }
        return out;
    }

    /** Matériaux que le village met de côté : chantier du palier suivant, montées prêtes, projets et avant-poste du conseil. */
    static List<VillageLayout.Material> saving(World world, Village v) {
        List<VillageLayout.Material> out = new ArrayList<>();
        VillageLayout layout = Chantiers.layout(v);
        if (layout == null) {
            return out;
        }
        if (v.tier() < Tiers.MAX && v.chantier() == null) {
            VillageLayout.Chantier c = layout.chantier(v.tier() + 1);
            if (c != null) {
                out.addAll(List.of(c.materials()));
            }
        }
        for (Workshop w : Workshops.opened(v)) {
            WorkshopState ws = v.workshopIfPresent(w.getId());
            if (ws == null || w.isHamlet()) {
                continue;
            }
            Workshops.Next next = Workshops.next(v, ws);
            if (next == Workshops.Next.MASTER || next == Workshops.Next.MATERIALS) {
                Workshop.Upgrade up = w.upgrade(ws.level() + 1);
                if (up != null) {
                    out.addAll(List.of(up.materials()));
                }
            }
        }
        for (VillageLayout.Plot plot : Plots.plots(v)) {
            String reserved = get(world, "P:" + v.id() + "|" + plot.id());
            PlotState s = v.plot(plot.id());
            if (reserved != null && reserved.equals(s.project()) && !s.working()) {
                out.addAll(List.of(plot.materials()));
            }
        }
        String plan = get(world, "A:" + v.id());
        Workshop annex = plan == null ? null : Workshop.get(plan.split("\\|")[0]);
        if (annex != null && annex.isAnnex()) {
            out.addAll(List.of(annex.annex().materials()));
        }
        return out;
    }

    /** Matériaux de construction du peuple du village : chantiers, terrains, montées d'ateliers, avant-postes ; sans luxe. */
    static Set<String> harvestable(Village v) {
        VillageLayout layout = Chantiers.layout(v);
        if (layout == null) {
            return Set.of();
        }
        return HARVESTABLE.computeIfAbsent(v.layout() + "|" + v.people().name(), k -> {
            Set<String> out = new LinkedHashSet<>();
            for (int t = 1; t <= Tiers.MAX; t++) {
                VillageLayout.Chantier c = layout.chantier(t);
                if (c != null) {
                    for (VillageLayout.Material m : c.materials()) {
                        out.add(m.itemId());
                    }
                }
            }
            for (VillageLayout.Plot p : layout.plots()) {
                for (VillageLayout.Material m : p.materials()) {
                    out.add(m.itemId());
                }
            }
            List<Workshop> all = new ArrayList<>(Workshop.forLayout(v.layout()));
            all.addAll(Workshop.annexes(v.people()));
            for (Workshop w : all) {
                for (int l = 2; l <= Workshops.MAX_LEVEL; l++) {
                    Workshop.Upgrade up = w.upgrade(l);
                    if (up != null) {
                        for (VillageLayout.Material m : up.materials()) {
                            out.add(m.itemId());
                        }
                    }
                }
                if (w.isAnnex()) {
                    for (VillageLayout.Material m : w.annex().materials()) {
                        out.add(m.itemId());
                    }
                }
            }
            out.removeIf(item -> TradeGoods.isLuxury(item) || !TradeGoods.allowed(v.people(), item));
            return out;
        });
    }

    // ---- Conseil du matin (après les ateliers) ----

    /** Décisions du jour de chaque village : ateliers, terrains, avant-poste. Thread du monde, dépôts chargés. */
    static void council(World world, Store<EntityStore> store, long day, Random random) {
        if (!on(world)) {
            return;
        }
        for (Village v : new ArrayList<>(Villages.registry(world).all())) {
            if (!grows(v)) {
                continue;
            }
            try {
                workshops(world, store, v, day);
                plots(world, v, day);
                annexPlan(world, store, v, day);
            } catch (RuntimeException e) {
                PeuplesPlugin.get().getLogger().atWarning().withCause(e).log("Peuples (autonomie) : erreur du conseil de %s", v.id());
            }
        }
    }

    private static void workshops(World world, Store<EntityStore> store, Village v, long day) {
        for (Workshop w : Workshops.opened(v)) {
            if (w.isHamlet() || w.isMarket()) {
                continue;
            }
            WorkshopState ws = v.workshop(w.getId());
            String key = v.id() + "|" + w.getId();
            if (ws.level() >= Workshops.MAX_LEVEL || ws.upgrading()) {
                continue;
            }
            int max = Workshops.threshold(ws.level());
            if (ws.knowledge() < max && Inputs.running(v, w)) {
                ws.setKnowledge(Math.min(max, ws.knowledge() + KNOWLEDGE_PER_DAY));
                if (ws.knowledge() >= max) {
                    note(world, v, Message.translation(KEYS + "knowledge").param("building", Message.translation(w.nameKey())), null);
                }
            }
            Workshops.Next next = Workshops.next(v, ws);
            if (next == Workshops.Next.MASTER) {
                if (day - since(world, "M:" + key, day) >= MASTER_DAYS) {
                    ws.setMasterDone(true);
                    put(world, "M:" + key, null);
                    note(world, v, Message.translation(KEYS + "master").param("building", Message.translation(w.nameKey())), null);
                    next = Workshops.Next.MATERIALS;
                }
            } else {
                put(world, "M:" + key, null);
            }
            if (next == Workshops.Next.MATERIALS) {
                upgrade(world, store, v, w, ws);
            }
            if (ws.level() >= Workshops.SPECIALTY_LEVEL && ws.specialty() == null && !ws.upgrading() && w.specialties().length > 0) {
                if (day - since(world, "S:" + key, day) >= PLAYER_DAYS) {
                    specialty(world, store, v, w, ws, day);
                }
            } else {
                put(world, "S:" + key, null);
            }
        }
    }

    /** Lance la montée de niveau si les matériaux sont au dépôt (comme un joueur, sans rang). */
    private static void upgrade(World world, Store<EntityStore> store, Village v, Workshop w, WorkshopState ws) {
        Workshop.Upgrade up = w.upgrade(ws.level() + 1);
        if (up == null) {
            return;
        }
        Map<String, Integer> missing = Workshops.missing(world, v, up);
        if (missing == null || !missing.isEmpty()) {
            return;
        }
        ItemContainer chest = Villages.depositChest(world, v);
        for (VillageLayout.Material m : up.materials()) {
            Requests.take(chest, m.itemId(), m.quantity());
        }
        long now = GameClock.seconds(store);
        ws.startUpgrade(now, now + up.hours() * 3600L);
        note(world, v, Message.translation(KEYS + "upgrade").param("village", v.name()).param("building", Message.translation(w.nameKey()))
                .param("hours", up.hours()), null);
    }

    /**
     * Spécialité choisie par le conseil (Kevin : « selon les besoins et les relations commerciales ») : celle qui produit
     * un besoin du village ou une nouvelle qu'il a apprise, sinon un besoin d'un partenaire (routes ouvertes d'abord,
     * puis partenaires possibles, du meilleur ami au moins bon), sinon la première possible.
     */
    private static void specialty(World world, Store<EntityStore> store, Village v, Workshop w, WorkshopState ws, long day) {
        List<Workshop.Specialty> valid = new ArrayList<>();
        for (Workshop.Specialty s : w.specialties()) {
            if (s != null && Projects.specialtyMissing(world, v, s) == null && Workshops.twinWith(v, w, s.id()) == null) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        Workshop.Specialty chosen = null;
        for (Set<String> wanted : wanted(world, v)) {
            for (Workshop.Specialty s : valid) {
                for (String item : wanted) {
                    if (TradeNeeds.shopMakes(s.shop(ws.level()), item)) {
                        chosen = s;
                        break;
                    }
                }
                if (chosen != null) {
                    break;
                }
            }
            if (chosen != null) {
                break;
            }
        }
        if (chosen == null) {
            chosen = valid.get(Math.floorMod((v.id() + w.getId()).hashCode(), valid.size()));
        }
        ws.setSpecialty(chosen.id());
        Workshops.draw(store, v, w, ws, true);
        Livestock.changed(store, v, w);
        note(world, v, Message.translation(KEYS + "specialty").param("village", v.name()).param("building", Message.translation(w.nameKey()))
                .param("specialty", Message.translation(chosen.nameKey())), null);
    }

    /**
     * Ce que le village cherche à produire, par ordre de priorité : ses besoins et ceux qu'il a appris, puis les besoins de
     * ses partenaires de route, puis ceux de ses partenaires possibles du plus ami au moins ami.
     */
    static List<Set<String>> wanted(World world, Village v) {
        List<Set<String>> out = new ArrayList<>();
        Set<String> own = new LinkedHashSet<>();
        for (TradeNeeds.Need n : TradeNeeds.needs(world, v)) {
            own.add(n.item());
        }
        for (TradeNeeds.News n : TradeNeeds.known(world, v)) {
            own.add(n.item());
        }
        out.add(own);
        Set<String> partners = new LinkedHashSet<>();
        for (TradeRoutes.Route r : TradeRoutes.of(world, v)) {
            Village o = Villages.registry(world).byId(r.other(v.id()));
            if (o != null) {
                for (TradeNeeds.Need n : TradeNeeds.needs(world, o)) {
                    partners.add(n.item());
                }
            }
        }
        out.add(partners);
        List<Village> others = new ArrayList<>(TradeRoutes.candidates(world, v));
        others.sort((a, b) -> Integer.compare(Relations.value(world, v, b), Relations.value(world, v, a)));
        Set<String> friends = new LinkedHashSet<>();
        for (Village o : others) {
            for (TradeNeeds.Need n : TradeNeeds.needs(world, o)) {
                friends.add(n.item());
            }
        }
        out.add(friends);
        return out;
    }

    /** Un terrain ouvert et libre depuis {@value #PLAYER_DAYS} jours reçoit un projet du conseil (un tous les {@value #PLOT_EVERY} jours). */
    private static void plots(World world, Village v, long day) {
        for (VillageLayout.Plot plot : Plots.plots(v)) {
            String key = v.id() + "|" + plot.id();
            PlotState s = v.plot(plot.id());
            String reserved = get(world, "P:" + key);
            if (reserved != null && (!reserved.equals(s.project()) || v.isBuilt(reserved))) {
                put(world, "P:" + key, null); // un joueur a choisi autre chose, ou c'est bâti
            }
            boolean free = Plots.isOpen(v, plot) && s.project() == null && !s.working();
            if (!free) {
                put(world, "O:" + key, null);
                continue;
            }
            long opened = since(world, "O:" + key, day);
            if (day - opened < PLAYER_DAYS || day - getLong(world, "PL:" + v.id()) < PLOT_EVERY) {
                continue;
            }
            List<VillageLayout.Building> valid = new ArrayList<>();
            for (VillageLayout.Building b : Plots.catalog(v, plot.id())) {
                if (Plots.refusal(world, v, plot.id(), b) == null) {
                    valid.add(b);
                }
            }
            if (valid.isEmpty()) {
                continue;
            }
            VillageLayout.Building chosen = null;
            for (Set<String> wanted : wanted(world, v)) {
                for (VillageLayout.Building b : valid) {
                    Workshop w = Plots.workshop(v, b.id());
                    for (String item : wanted) {
                        if (w != null && TradeNeeds.shopMakes(w.baseShop(), item)) {
                            chosen = b;
                            break;
                        }
                    }
                    if (chosen != null) {
                        break;
                    }
                }
                if (chosen != null) {
                    break;
                }
            }
            if (chosen == null) {
                chosen = valid.get(Math.floorMod((v.id() + plot.id()).hashCode(), valid.size()));
            }
            Plots.reserveByVillage(v, plot, chosen.id());
            put(world, "P:" + key, chosen.id());
            put(world, "PL:" + v.id(), Long.toString(day));
            put(world, "O:" + key, null);
            note(world, v, Message.translation(KEYS + "plot").param("village", v.name()).param("plot", plot.id())
                    .param("building", Message.translation(Chantiers.buildingKey(chosen.id()))), null);
            return; // un terrain par jour
        }
    }

    /** Avant-postes fondés seuls par le conseil. */
    static int annexCount(Village v) {
        int n = 0;
        for (AnnexState a : v.annexes()) {
            if (OWNER.equals(a.owner())) {
                n++;
            }
        }
        return n;
    }

    /** Au bourg, le conseil prévoit un avant-poste de production (selon les besoins) quand ses matériaux sont là. */
    private static void annexPlan(World world, Store<EntityStore> store, Village v, long day) {
        if (v.tier() < ANNEX_TIER || get(world, "A:" + v.id()) != null || annexCount(v) >= ANNEX_MAX
                || day - getLong(world, "AL:" + v.id()) < ANNEX_EVERY) {
            return;
        }
        List<Workshop> types = new ArrayList<>();
        for (Workshop w : Annexes.catalog(v)) {
            if (v.annex(w.getId()) == null && v.tier() >= w.annex().tier()) {
                types.add(w);
            }
        }
        if (types.isEmpty()) {
            return;
        }
        Workshop chosen = null;
        for (Set<String> wanted : wanted(world, v)) {
            for (Workshop w : types) {
                if (makes(w, wanted)) {
                    chosen = w;
                    break;
                }
            }
            if (chosen != null) {
                break;
            }
        }
        if (chosen == null) {
            chosen = types.get(Math.floorMod(v.id().hashCode() + (int) day, types.size()));
        }
        put(world, "A:" + v.id(), chosen.getId() + "|0");
        log("%s prévoit l'avant-poste %s", v.id(), chosen.getId());
    }

    private static boolean makes(Workshop w, Set<String> items) {
        Workshop.Annex a = w.annex();
        if (a.delivery() != null && items.contains(a.delivery().itemId())) {
            return true;
        }
        for (VillageLayout.Material m : a.lots()) {
            if (items.contains(m.itemId())) {
                return true;
            }
        }
        for (String item : items) {
            if (TradeNeeds.shopMakes(w.baseShop(), item)) {
                return true;
            }
        }
        return false;
    }

    // ---- Pas de la vie des villages (blocs) ----

    /**
     * Le village est sous les yeux d'un joueur ou tenu par la veille : le conseil lance le chantier du terrain qu'il a
     * réservé si les matériaux sont là. Thread du monde.
     */
    public static void step(World world, Store<EntityStore> store, Village v) {
        if (!on(world) || !grows(v)) {
            return;
        }
        long day = GameClock.day(store);
        for (VillageLayout.Plot plot : Plots.plots(v)) {
            String reserved = get(world, "P:" + v.id() + "|" + plot.id());
            PlotState s = v.plot(plot.id());
            if (reserved == null || !reserved.equals(s.project()) || s.working() || v.isBuilt(reserved)) {
                continue;
            }
            Map<String, Integer> missing = Plots.missing(world, v, plot);
            if (missing == null) {
                continue;
            }
            if (!missing.isEmpty() || Projects.projectMissing(world, v, Plots.workshop(v, reserved)) != null) {
                SHORT.put(v.id(), day);
                continue;
            }
            if (Plots.startByVillage(world, store, v, plot)) {
                put(world, "P:" + v.id() + "|" + plot.id(), null);
                TradeLog.add(world, v, TradeLog.Kind.COUNCIL, null, Message.translation("nakasar_peuples.plot.startedByVillage")
                        .param("village", v.name()).param("building", Message.translation(Chantiers.buildingKey(reserved)))
                        .param("hours", plot.hours()));
            }
        }
    }

    /** Vrai si le village a un terrain du conseil à lancer (la veille le tient alors). */
    public static boolean workDue(World world, Store<EntityStore> store, Village v) {
        if (!on(world) || !grows(v)) {
            return false;
        }
        Long short_ = SHORT.get(v.id());
        if (short_ != null && short_ == GameClock.day(store)) {
            return false;
        }
        for (VillageLayout.Plot plot : Plots.plots(v)) {
            String reserved = get(world, "P:" + v.id() + "|" + plot.id());
            PlotState s = v.plot(plot.id());
            if (reserved != null && reserved.equals(s.project()) && !s.working() && !v.isBuilt(reserved)) {
                Map<String, Integer> missing = Plots.missing(world, v, plot);
                if (missing == null || missing.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Avant-poste prévu : une direction autour du village à la fois, terrain chargé (joueur proche ou veille). La place
     * qui convient ({@link Annexes#check}) reçoit la fondation ; sinon la direction suivante, et après
     * {@value #ANNEX_TRIES} essais le conseil renonce pour {@value #ANNEX_EVERY} jours. Thread du monde.
     */
    public static void stepAnnex(World world, Store<EntityStore> store, Village v, List<Vector3d> players) {
        String plan = on(world) ? get(world, "A:" + v.id()) : null;
        if (plan == null) {
            return;
        }
        String[] p = plan.split("\\|");
        Workshop w = Workshop.get(p[0]);
        int attempt = p.length > 1 ? parse(p[1]) : 0;
        long day = GameClock.day(store);
        if (w == null || !w.isAnnex() || v.annex(w.getId()) != null || attempt >= ANNEX_TRIES) {
            put(world, "A:" + v.id(), null);
            put(world, "AL:" + v.id(), Long.toString(day));
            return;
        }
        Map<String, Integer> missing = Annexes.missing(world, v, w);
        if (missing != null && !missing.isEmpty()) {
            return; // la récolte met les matériaux de côté
        }
        Workshop.Annex spec = w.annex();
        int distance = Math.min(spec.maxDistance(), Annexes.minDistance(v, spec) + 10);
        double angle = 2 * Math.PI * attempt / ANNEX_TRIES + Math.floorMod(v.id().hashCode(), 360) * Math.PI / 180;
        int x = v.x() + (int) Math.round(Math.cos(angle) * distance);
        int z = v.z() + (int) Math.round(Math.sin(angle) * distance);
        String key = "site:" + v.id() + ":" + attempt;
        boolean seen = Veille.held(world, key);
        for (Vector3d pl : players) {
            double dx = pl.x() - x;
            double dz = pl.z() - z;
            if (dx * dx + dz * dz <= 64.0 * 64.0) {
                seen = true;
            }
        }
        if (!seen) {
            if (Veille.free(world, key) && !Veille.isShort(v, day)) {
                Veille.offer(world, key, x - 40, x + 40, z - 40, z + 40, () -> false);
            }
            return;
        }
        if (missing == null) {
            return; // dépôt pas chargé : on réessaie au prochain pas
        }
        int rotation = Annexes.rotationFacing(v.x() - (x + 0.5), v.z() - (z + 0.5));
        int surface = new Terrain(world).surface(x, z);
        if (surface == Terrain.NONE) {
            return;
        }
        Message refusal = Annexes.check(world, store, null, v, w, x, surface + 1, z, rotation);
        if (refusal != null) {
            put(world, "A:" + v.id(), w.getId() + "|" + (attempt + 1));
            log("%s : place %d pour %s refusée en %d %d", v.id(), attempt, w.getId(), x, z);
            return;
        }
        Annexes.startByVillage(world, store, v, w, x, surface + 1, z, rotation, OWNER);
        put(world, "A:" + v.id(), null);
        put(world, "AL:" + v.id(), Long.toString(day));
        TradeLog.add(world, v, TradeLog.Kind.COUNCIL, null, Message.translation(KEYS + "annex").param("village", v.name())
                .param("building", Message.translation(w.nameKey())));
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return ANNEX_TRIES;
        }
    }

    // ---- Journal ----

    /** Lignes du conseil pour l'onglet Monde : avant-poste et terrain prévus, rythme. */
    public static List<Message> describe(World world, Village v) {
        List<Message> out = new ArrayList<>();
        if (!on(world) || !grows(v)) {
            return out;
        }
        String plan = get(world, "A:" + v.id());
        Workshop annex = plan == null ? null : Workshop.get(plan.split("\\|")[0]);
        if (annex != null) {
            out.add(Message.translation(KEYS + "plan.annex").param("building", Message.translation(annex.nameKey())));
        }
        for (VillageLayout.Plot plot : Plots.plots(v)) {
            String reserved = get(world, "P:" + v.id() + "|" + plot.id());
            if (reserved != null) {
                out.add(Message.translation(KEYS + "plan.plot").param("plot", plot.id())
                        .param("building", Message.translation(Chantiers.buildingKey(reserved))));
            }
        }
        return out;
    }

    /** Annonce une décision du conseil (joueurs proches) et la note au registre du village. */
    static void note(World world, Village v, Message m, String item) {
        Chantiers.announce(world, v, m, null, NotificationStyle.Default);
        TradeLog.add(world, v, TradeLog.Kind.COUNCIL, item, m);
        log("décision du conseil de %s", v.id());
    }

    private static void log(String format, Object... args) {
        PeuplesPlugin.get().getLogger().atInfo().log("Peuples (autonomie) : " + format, args);
    }

    /** Remise à zéro (rechargement du plugin). */
    public static void clearAll() {
        HARVESTABLE.clear();
        SHORT.clear();
    }
}
