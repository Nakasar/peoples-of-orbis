package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.request.Requests;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * La vie des villages (étape 5), toutes les {@value #INTERVAL} s et par monde, sur le thread du monde : un chantier
 * commence quand la jauge est pleine et que le dépôt contient ses matériaux, il se finit à son heure, et les villageois
 * à métier suivent leur routine ({@link Villagers}). Les travaux de blocs d'un village se font quand un joueur est à
 * moins de {@value Chantiers#ANNOUNCE_RADIUS} blocs ou, sans joueur proche, quand la veille le tient le temps de les
 * faire (world/Veille : chunks chargés puis relâchés, un village à la fois).
 */
public final class VillageLifeSystem extends TickingSystem<EntityStore> {

    private static final float INTERVAL = 2f;

    private static final Map<World, float[]> ELAPSED = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        float[] elapsed = ELAPSED.computeIfAbsent(world, w -> new float[1]);
        elapsed[0] += dt;
        if (elapsed[0] < INTERVAL) {
            return;
        }
        float step = elapsed[0];
        elapsed[0] = 0f;
        if (Villages.registry(world).all().isEmpty()) {
            return;
        }
        world.execute(() -> step(world, step));
    }

    private static void step(World world, float dt) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        List<Vector3d> players = new ArrayList<>();
        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            Vector3d at = ref == null || !ref.isValid() ? null : Requests.position(store, ref);
            if (at != null) {
                players.add(at);
            }
        }
        boolean veille = dev.nakasar.hytale.peuples.world.Veille.on(world);
        if (players.isEmpty() && !veille) {
            return;
        }
        if (veille) {
            dev.nakasar.hytale.peuples.world.Veille.step(world);
        }
        for (Village village : new ArrayList<>(Villages.registry(world).all())) {
            // Avant-postes de métier : chacun a sa propre distance de veille, loin du cœur.
            dev.nakasar.hytale.peuples.annex.Annexes.step(world, store, village, players, dt);
            // Avant-poste prévu par le conseil (village autonome) : une place essayée à la fois.
            dev.nakasar.hytale.peuples.world.Autonomy.stepAnnex(world, store, village, players);
            // Sans joueur proche, la veille tient le village le temps de ses travaux (world/Veille) : il travaille
            // alors comme sous les yeux d'un joueur.
            boolean seen = watched(village, players) || veille && dev.nakasar.hytale.peuples.world.Veille.held(world, village);
            if (!seen && veille && dev.nakasar.hytale.peuples.world.Veille.free(world, dev.nakasar.hytale.peuples.world.Veille.villageKey(village))
                    && workDue(world, store, village)) {
                int r = Chantiers.reach(village);
                dev.nakasar.hytale.peuples.world.Veille.offer(world, dev.nakasar.hytale.peuples.world.Veille.villageKey(village),
                        village.x() - r, village.x() + r, village.z() - r, village.z() + r,
                        () -> Chantiers.busy(village) || VillageStreets.busy(village) || Rebuilds.busy(village) || Repairs.busy(village));
            }
            // Dépôt : grand coffre à partir du palier 2.
            if (!village.depositLarge() && village.tier() >= Deposits.LARGE_TIER && seen) {
                Deposits.upgrade(world, village);
            }
            // Sentiers et décor entre les bâtiments : à la génération, après chaque chantier, et rattrapés une fois.
            if (VillageStreets.due(village) && seen) {
                VillageStreets.lay(world, village);
            }
            // Bâtiments bâtis avant les paliers d'apparence (ou recollage interrompu) : apparence de leur palier.
            if (Rebuilds.due(village) && village.chantier() == null && seen) {
                Rebuilds.catchUp(world, village);
            }
            if (Chantiers.layout(village) == null || !seen) {
                if (veille) {
                    // Générations : que des données (les tombes attendent que leur chunk soit chargé).
                    dev.nakasar.hytale.peuples.population.Population.step(world, store, village);
                }
                continue;
            }
            if (village.chantier() == null) {
                if (dev.nakasar.hytale.peuples.world.Autonomy.chantierPaid(world, village)) {
                    // Décidé et payé au conseil du matin, sans joueur : il ne reste que la pose.
                    dev.nakasar.hytale.peuples.world.Autonomy.openPaidChantier(world, village, Villagers::forgetGround);
                } else if (village.tier() < Tiers.MAX && village.essor() >= Tiers.threshold(village.tier())
                        && Chantiers.start(world, village, false, state -> Villagers.forgetGround()) == Chantiers.Block.MATERIALS
                        && !watched(village, players)) {
                    // Tenu sans joueur, matériaux absents : pas de nouvelle tenue pour ça avant demain.
                    dev.nakasar.hytale.peuples.world.Veille.markShort(village, dev.nakasar.hytale.peuples.request.GameClock.day(store));
                }
            } else if (Chantiers.due(store, village)) {
                Chantiers.finish(world, village, Villagers::forgetGround);
            } else {
                Chantiers.grow(world, village);
            }
            Plots.step(world, store, village);
            // Réparations de bâtiments brûlés : fin des travaux.
            Repairs.step(world, store, village);
            // Village autonome : le conseil lance le chantier du terrain qu'il a réservé.
            dev.nakasar.hytale.peuples.world.Autonomy.step(world, store, village);
            // Générations : naissances, âges, passations, morts, tombes.
            dev.nakasar.hytale.peuples.population.Population.step(world, store, village);
        }
        Villagers.step(world, store, players, dt);
        dev.nakasar.hytale.peuples.outpost.Settlers.step(world, store);
        dev.nakasar.hytale.peuples.workshop.Livestock.step(world, store, players);
        // Avant-postes posés sur la carte du monde.
        dev.nakasar.hytale.peuples.annex.AnnexMarkers.refresh(world);
    }

    /** Travaux de blocs qui attendent dans ce village (mêmes conditions que la boucle) : la veille le tient alors. */
    private static boolean workDue(World world, Store<EntityStore> store, Village village) {
        if (Chantiers.busy(village)) {
            return false;
        }
        if (!village.depositLarge() && village.tier() >= Deposits.LARGE_TIER && !village.isHamlet()) {
            return true;
        }
        if (VillageStreets.due(village) || Rebuilds.due(village) && village.chantier() == null) {
            return true;
        }
        if (Chantiers.layout(village) == null) {
            return false;
        }
        if (village.chantier() == null && dev.nakasar.hytale.peuples.world.Autonomy.chantierPaid(world, village)) {
            return true; // chantier décidé au matin : la pose attend la tenue
        }
        if (village.chantier() == null) {
            // Dépôt pas chargé : on ne sait pas encore s'il a les matériaux, la tenue le dira.
            Chantiers.Block b = Chantiers.blocker(world, village, false);
            if (b == Chantiers.Block.NONE || b == Chantiers.Block.DEPOSIT
                    && !dev.nakasar.hytale.peuples.world.Veille.isShort(village, dev.nakasar.hytale.peuples.request.GameClock.day(store))) {
                return true;
            }
        } else if (Chantiers.workDue(store, village)) {
            return true;
        }
        return Plots.workDue(store, village) || Repairs.workDue(store, village)
                || dev.nakasar.hytale.peuples.world.Autonomy.workDue(world, store, village);
    }

    private static boolean watched(Village village, List<Vector3d> players) {
        for (Vector3d p : players) {
            if (village.horizontalDistanceSquared(p.x(), p.z()) <= (double) Chantiers.ANNOUNCE_RADIUS * Chantiers.ANNOUNCE_RADIUS) {
                return true;
            }
        }
        return false;
    }
}
