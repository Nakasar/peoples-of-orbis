package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.Workshops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La réparation d'un bâtiment brûlé (Kevin, 23 novembre 2026 : « les bâtiments brûlés doivent pouvoir être réparés
 * contre des ressources ; un village doit pouvoir lancer seul la réparation d'un bâtiment »). Un raid perdu ne fait plus
 * perdre de palier : le bâtiment reste en ruine jusqu'à sa réparation (ou au prochain chantier de palier).
 *
 * <p>Coût : {@value #SHARE_PERCENT} % (arrondi au-dessus) des matériaux du chantier qui l'a bâti (chantier de son
 * palier, ou du terrain pour un projet), pris au dépôt du village. Travaux : {@value #HOURS} heures de jeu sous
 * l'échafaudage du peuple, puis le prefab de son apparence est recollé au même endroit, son panneau de métier reposé et
 * ses postes repris ({@code Population.refill}). Une réparation à la fois par village. Un joueur la lance depuis
 * l'onglet Bâtir ou {@code /village reparer} ; le conseil du village la lance seul dès que le dépôt a les matériaux
 * ({@code world/Autonomy}, qui les met de côté avant ceux du palier suivant).
 */
public final class Repairs {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final String KEYS = "nakasar_peuples.repair.";
    /** Lanceur d'une réparation du conseil. */
    public static final String VILLAGE = "village";
    /** Part des matériaux du chantier d'origine que coûte la réparation. */
    public static final int SHARE_PERCENT = 30;
    /** Durée des travaux, en heures de jeu. */
    public static final int HOURS = 8;

    /** Villages dont une réparation charge ses chunks (pose ou fin des travaux). */
    private static final Set<String> BUSY = Collections.synchronizedSet(new HashSet<>());

    /** Pourquoi une réparation ne peut pas commencer. */
    public enum Block {
        NONE, NO_LAYOUT, NOT_BURNT, REPAIRING, ANOTHER, CHANTIER, BUSY, DEPOSIT, MATERIALS
    }

    private Repairs() {
    }

    // ---- Lecture ----

    /** Coût de la réparation du bâtiment `b` (vide si le village n'a pas de chantier d'origine pour lui). */
    public static List<VillageLayout.Material> cost(Village village, VillageLayout layout, VillageLayout.Building b) {
        VillageLayout.Material[] base = null;
        if (b.plot() != null) {
            for (VillageLayout.Plot p : Plots.plots(village)) {
                if (p.id().equals(b.plot())) {
                    base = p.materials();
                }
            }
        } else {
            VillageLayout.Chantier c = layout.chantier(Math.max(1, b.tier()));
            base = c == null ? null : c.materials();
        }
        List<VillageLayout.Material> out = new ArrayList<>();
        if (base != null) {
            for (VillageLayout.Material m : base) {
                out.add(new VillageLayout.Material(m.itemId(), (m.quantity() * SHARE_PERCENT + 99) / 100));
            }
        }
        return out;
    }

    /** Coût de la réparation du bâtiment `buildingId` du plan (vide si inconnu). */
    public static List<VillageLayout.Material> cost(Village village, String buildingId) {
        VillageLayout layout = Chantiers.layout(village);
        VillageLayout.Building b = layout == null ? null : layout.building(buildingId);
        return b == null ? List.of() : cost(village, layout, b);
    }

    /** Matériaux qui manquent au dépôt (objet → quantité), ou null si le dépôt n'est pas chargé. */
    public static Map<String, Integer> missing(World world, Village village, List<VillageLayout.Material> cost) {
        Map<String, Integer> deposit = Villages.depositContents(world, village);
        if (deposit == null) {
            return null;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (VillageLayout.Material m : cost) {
            int have = deposit.getOrDefault(m.itemId(), 0);
            if (have < m.quantity()) {
                out.merge(m.itemId(), m.quantity() - have, Integer::sum);
            }
        }
        return out;
    }

    /** Bâtiments brûlés que la réparation peut viser, du plus haut palier au plus bas (bâtiments de métier d'abord). */
    public static List<VillageLayout.Building> ruins(Village village) {
        VillageLayout layout = Chantiers.layout(village);
        List<VillageLayout.Building> out = new ArrayList<>();
        if (layout == null) {
            return out;
        }
        for (String id : village.burnt()) {
            VillageLayout.Building b = layout.building(id);
            if (b != null && village.isBuilt(id)) {
                out.add(b);
            }
        }
        out.sort((a, c) -> {
            boolean wa = BuildingPaliers.workshopOf(layout, a) != null;
            boolean wc = BuildingPaliers.workshopOf(layout, c) != null;
            if (wa != wc) {
                return wa ? -1 : 1;
            }
            return Integer.compare(c.tier(), a.tier());
        });
        return out;
    }

    /** Pourquoi la réparation de `buildingId` ne commence pas (NONE : elle peut commencer). */
    public static Block blocker(World world, Village village, String buildingId) {
        VillageLayout layout = Chantiers.layout(village);
        VillageLayout.Building b = layout == null ? null : layout.building(buildingId);
        if (b == null) {
            return Block.NO_LAYOUT;
        }
        if (village.isRepairing(buildingId)) {
            return Block.REPAIRING;
        }
        if (!village.isBuilt(buildingId) || !village.isBurnt(buildingId)) {
            return Block.NOT_BURNT;
        }
        if (village.repairs().length > 0) {
            return Block.ANOTHER;
        }
        ChantierState c = village.chantier();
        if (c != null && List.of(c.buildings()).contains(buildingId)) {
            return Block.CHANTIER; // le chantier du palier le rebâtit déjà
        }
        if (BUSY.contains(village.id()) || Chantiers.busy(village) || Rebuilds.busy(village)) {
            return Block.BUSY;
        }
        Map<String, Integer> missing = missing(world, village, cost(village, layout, b));
        if (missing == null) {
            return Block.DEPOSIT;
        }
        return missing.isEmpty() ? Block.NONE : Block.MATERIALS;
    }

    /** Phrase du refus (clé {@code repair.refused.<raison>}). */
    public static Message refusal(Village village, String buildingId, Block block) {
        String key = KEYS + "refused." + block.name().toLowerCase(java.util.Locale.ROOT);
        Message m = Message.translation(key).param("building", Message.translation(Chantiers.buildingKey(buildingId)));
        if (block == Block.ANOTHER && village.repairs().length > 0) {
            m = m.param("other", Message.translation(Chantiers.buildingKey(village.repairs()[0].building())));
        }
        return m;
    }

    /** Lignes de {@code /village reparer} : chaque ruine, son coût et ce qui manque, ou la réparation en cours. */
    public static List<Message> lines(World world, Store<EntityStore> store, Village village) {
        List<Message> out = new ArrayList<>();
        VillageLayout layout = Chantiers.layout(village);
        long now = GameClock.seconds(store);
        for (VillageLayout.Building b : ruins(village)) {
            Message name = Message.translation(Chantiers.buildingKey(b.id()));
            RepairState r = village.repair(b.id());
            if (r != null) {
                out.add(Message.translation(KEYS + "line.repairing").param("building", name)
                        .param("hours", (int) Math.ceil(Math.max(0, r.end() - now) / 3600.0)));
                continue;
            }
            Message cost = Message.raw("");
            Map<String, Integer> missing = missing(world, village, cost(village, layout, b));
            List<VillageLayout.Material> materials = cost(village, layout, b);
            for (int i = 0; i < materials.size(); i++) {
                VillageLayout.Material m = materials.get(i);
                int lack = missing == null ? m.quantity() : missing.getOrDefault(m.itemId(), 0);
                cost.insert(Message.translation(KEYS + "line.material").param("need", m.quantity())
                        .param("item", VillageBoard.itemName(m.itemId())).color(lack > 0 ? "#e8c46a" : "#7ed957"));
                if (i < materials.size() - 1) {
                    cost.insert(", ");
                }
            }
            out.add(Message.translation(KEYS + "line.ruin").param("building", name).param("id", b.id()).param("cost", cost));
        }
        return out;
    }

    /** Vrai si une réparation charge les chunks du village. */
    public static boolean busy(Village village) {
        return BUSY.contains(village.id());
    }

    /** Vrai si une réparation du village a fini ses heures de travaux (la veille tient alors le village). */
    public static boolean workDue(Store<EntityStore> store, Village village) {
        if (BUSY.contains(village.id())) {
            return false;
        }
        long now = GameClock.seconds(store);
        for (RepairState r : village.repairs()) {
            if (now >= r.end()) {
                return true;
            }
        }
        return false;
    }

    // ---- Travaux ----

    /**
     * Lance la réparation de `buildingId` : matériaux pris au dépôt, ruines retirées et échafaudage posé (chunks du
     * village chargés), fin dans {@value #HOURS} heures de jeu. `by` : UUID du joueur ou {@link #VILLAGE}. Rend NONE si
     * elle a commencé, sinon ce qui l'empêche.
     */
    public static Block start(World world, Store<EntityStore> store, Village village, String buildingId, String by) {
        Block blocker = blocker(world, village, buildingId);
        if (blocker != Block.NONE) {
            return blocker;
        }
        VillageLayout layout = Chantiers.layout(village);
        VillageLayout.Building plan = layout.building(buildingId);
        ItemContainer chest = Requests.depositContainer(world, village);
        for (VillageLayout.Material m : cost(village, layout, plan)) {
            Requests.take(chest, m.itemId(), m.quantity());
        }
        long now = GameClock.seconds(store);
        village.addRepair(new RepairState(buildingId, now, now + HOURS * 3600L, by));
        BUSY.add(village.id());
        try {
            load(world, village, () -> {
                try {
                    if (!village.isRepairing(buildingId)) {
                        return;
                    }
                    int palier = Chantiers.plannedPalier(village, layout, plan, village.tier());
                    VillageLayout.Building b = BuildingPaliers.at(layout, plan, palier);
                    VillageLayout.Building was = BuildingPaliers.current(village, layout, plan);
                    int h = Chantiers.siteHeight(world, village, b);
                    Chantiers.clearRuins(world, village, was, h);
                    if (was != b) {
                        Chantiers.prepare(world, new Terrain(world), village, layout, b, h); // apparence plus grande
                    }
                    village.setSiteY(b.id(), h);
                    if (!b.small()) {
                        Chantiers.site(world, village, b, h, 1);
                    }
                    Villagers.forgetGround();
                } finally {
                    BUSY.remove(village.id());
                }
            });
        } catch (RuntimeException e) {
            BUSY.remove(village.id());
            throw e;
        }
        Message name = Message.translation(Chantiers.buildingKey(buildingId));
        Chantiers.announce(world, village, Message.translation(KEYS + (VILLAGE.equals(by) ? "startedByVillage" : "started"))
                .param("village", village.name()).param("building", name).param("hours", HOURS), null, NotificationStyle.Success);
        LOGGER.atInfo().log("Nakasar Peuples : réparation de %s lancée à %s (%s)", buildingId, village.name(), by);
        return Block.NONE;
    }

    /** Finit les réparations dont les heures sont passées (village sous les yeux d'un joueur ou tenu par la veille). */
    public static void step(World world, Store<EntityStore> store, Village village) {
        if (!workDue(store, village) || Chantiers.busy(village) || Rebuilds.busy(village) || !BUSY.add(village.id())) {
            return;
        }
        long now = GameClock.seconds(store);
        try {
            load(world, village, () -> {
                try {
                    for (RepairState r : village.repairs()) {
                        if (now >= r.end()) {
                            finish(world, village, r.building());
                        }
                    }
                } finally {
                    BUSY.remove(village.id());
                }
            });
        } catch (RuntimeException e) {
            BUSY.remove(village.id());
            throw e;
        }
    }

    /** Fin sur-le-champ (chunks chargés) : échafaudage retiré, prefab recollé, panneau et postes rétablis. */
    static boolean finish(World world, Village village, String buildingId) {
        VillageLayout layout = Chantiers.layout(village);
        VillageLayout.Building plan = layout == null ? null : layout.building(buildingId);
        village.removeRepair(buildingId);
        if (plan == null || village.isStanding(buildingId)) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        int palier = Chantiers.plannedPalier(village, layout, plan, village.tier());
        VillageLayout.Building b = BuildingPaliers.at(layout, plan, palier);
        int h = Chantiers.siteHeight(world, village, b);
        Chantiers.site(world, village, b, h, 0);
        Chantiers.clearRuins(world, village, b, h);
        if (!VillageBuilder.paste(world, store, b.prefab(), village.x() + b.x(), h, village.z() + b.z(), b.rotation())) {
            LOGGER.atWarning().log("Nakasar Peuples : réparation de %s à %s, prefab %s non collé", buildingId, village.name(), b.prefab());
            return false;
        }
        Chantiers.settle(world, village, layout, b, h);
        village.markBuilt(b.id());
        village.setSiteY(b.id(), h);
        village.setPalier(b.id(), palier);
        restored(world, store, village, b.id());
        Chantiers.announce(world, village, Message.translation(KEYS + "done").param("village", village.name())
                .param("building", Message.translation(Chantiers.buildingKey(buildingId))), null, NotificationStyle.Success);
        LOGGER.atInfo().log("Nakasar Peuples : %s réparé à %s", buildingId, village.name());
        return true;
    }

    /**
     * Le bâtiment `buildingId` est de nouveau debout (réparé ou rebâti par un chantier) : son panneau de métier sera
     * reposé ({@link Workshops#refresh}) et ses postes vacants repris tout de suite.
     */
    public static void restored(World world, Store<EntityStore> store, Village village, String buildingId) {
        ChunkStore chunks = world.getChunkStore();
        for (Workshop w : Workshops.all(village)) {
            if (buildingId.equals(w.building()) && !w.isAnnex() && !w.isHamlet()) {
                WorkshopState ws = village.workshopIfPresent(w.getId());
                if (ws != null && boardGone(chunks, village, ws)) {
                    ws.clearBoard(); // retiré avec les ruines : le panneau est reposé contre le mur
                }
            }
        }
        dev.nakasar.hytale.peuples.population.Population.refill(world, store, village, buildingId);
        Villagers.forgetGround();
    }

    /**
     * Vrai si le bâtiment de métier croit avoir un panneau mais que sa case (chargée) ne le porte plus : il a été retiré
     * avec les ruines d'un bâtiment rebâti avant {@link #unhangBoards}.
     */
    public static boolean boardGone(ChunkStore chunks, Village village, WorkshopState ws) {
        if (!ws.hasBoard()) {
            return false;
        }
        int[] at = ws.board();
        if (Blocks.id(chunks, at[0], at[1], at[2]) == Blocks.UNLOADED) {
            return false;
        }
        return !Blocks.is(Blocks.name(chunks, at[0], at[1], at[2]), village.people().boardBlock());
    }

    /** Décroche le panneau des bâtiments de métier logés dans `buildingId` (incendie). */
    public static void unhangBoards(World world, Village village, String buildingId) {
        ChunkStore chunks = world.getChunkStore();
        for (Workshop w : Workshops.all(village)) {
            if (!buildingId.equals(w.building()) || w.isAnnex() || w.isHamlet()) {
                continue;
            }
            WorkshopState ws = village.workshopIfPresent(w.getId());
            if (ws == null || !ws.hasBoard()) {
                continue;
            }
            int[] at = ws.board();
            Workshops.unhangBoard(chunks, at, Blocks.rotation(chunks, at[0], at[1], at[2]));
            Blocks.clearIf(chunks, at[0], at[1], at[2], village.people().boardBlock());
            ws.clearBoard();
        }
    }

    /**
     * Charge les chunks du village puis lance `then` sur le thread du monde ; le verrou {@link #BUSY} du village est
     * rendu dans tous les cas (chargement raté ou trop long compris).
     */
    private static void load(World world, Village village, Runnable then) {
        int r = Chantiers.reach(village);
        Terrain.load(world, village.x() - r, village.x() + r, village.z() - r, village.z() + r)
                .orTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> world.execute(() -> {
                    if (error != null) {
                        BUSY.remove(village.id());
                        LOGGER.atWarning().log("Nakasar Peuples : chunks de %s non chargés pour la réparation (%s)", village.name(),
                                error.getMessage());
                        return;
                    }
                    then.run();
                }));
    }

    /** Commande d'essai : les réparations du village finissent au prochain passage. Rend leur nombre. */
    public static int hurry(Store<EntityStore> store, Village village) {
        long now = GameClock.seconds(store);
        RepairState[] all = village.repairs();
        for (RepairState r : all) {
            r.end = Math.min(r.end, now);
        }
        return all.length;
    }

    /** Abandonne la réparation (commande, village effacé) : échafaudage laissé, matériaux perdus. */
    public static void cancel(Village village, String buildingId) {
        village.removeRepair(buildingId);
    }

    /** Remise à zéro (rechargement du plugin). */
    public static void clearAll() {
        BUSY.clear();
    }
}
