package dev.nakasar.hytale.peuples.zone;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.outpost.Outposts;

/** Chaque pas : climats des joueurs ({@link Climate}) ; toutes les 5 s : croissance du matin de la ruche ({@link Hive}) et
 * avant-postes du champion ({@link Outposts}, étape 9), aussi sans joueur quand la veille est active (world/Veille). */
public final class ZoneSystem extends TickingSystem<EntityStore> {

    private float hiveClock;

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        boolean alone = world.getPlayerRefs().isEmpty();
        if (alone && !dev.nakasar.hytale.peuples.world.Veille.on(world)) {
            return;
        }
        if (!alone) {
            Climate.step(world, store, dt);
        }
        hiveClock += dt;
        if (hiveClock >= 5f) {
            hiveClock = 0f;
            if (Zones.generator(world) != null) {
                Hive.morning(world, store);
            }
            Outposts.step(world, store);
        }
    }
}
