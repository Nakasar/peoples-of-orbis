package dev.nakasar.hytale.peuples.village;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.Workshops;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remplace sur place un bâtiment debout du village par l'apparence de son nouveau palier ({@link BuildingPaliers}) :
 * au changement de palier du village (bâtiments propres), à la montée de niveau d'un bâtiment de métier ou d'un projet
 * de terrain, et en rattrapage pour les villages bâtis avant les paliers d'apparence (quand un joueur approche,
 * {@link VillageLifeSystem}).
 *
 * <p>Remplacement : les joueurs et PNJ qui sont dans l'emprise (ancienne et nouvelle) sortent du côté de la place ;
 * l'ancien bâtiment est retiré au-dessus du sol (les coffres qui contiennent quelque chose restent : objets des
 * joueurs), le panneau du bâtiment de métier est décroché ; l'emprise nouvelle est aplanie à la même hauteur, le
 * nouveau prefab collé au même point (ou décalé de quelques blocs quand le plan l'a prévu pour qu'il tienne), même
 * rotation, puis le terrain alentour reprend son relief ; le panneau est reposé contre le nouveau mur et le décor des
 * niveaux atteints remis ; un corps resté coincé est remonté au point sûr le plus proche.
 */
public final class Rebuilds {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Set<String> BUSY = Collections.synchronizedSet(new HashSet<>());
    /** Marge autour de l'emprise d'où l'on fait sortir joueurs et PNJ. */
    private static final int EVACUATE_MARGIN = 1;
    /** Rattrapage : bâtiments recollés au plus à chaque passage d'un joueur (le reste au passage suivant). */
    private static final int CATCH_UP_BATCH = 6;
    /** Recherche d'un point sûr hors de l'emprise. */
    private static final int SAFE_RADIUS = 8;

    private Rebuilds() {
    }

    /** Bâtiments debout du village dont l'apparence est en retard sur leur palier. */
    static List<VillageLayout.Building> behind(Village village, VillageLayout layout) {
        List<VillageLayout.Building> out = new ArrayList<>();
        if (layout == null) {
            return out;
        }
        for (VillageLayout.Building b : layout.buildings()) {
            if (village.isStanding(b.id()) && BuildingPaliers.target(village, layout, b, village.tier()) > village.palier(b.id())) {
                out.add(b);
            }
        }
        return out;
    }

    /** Vrai pendant que le travail du village est en cours (chunks en chargement, collage). */
    public static boolean busy(Village village) {
        return BUSY.contains(village.id());
    }

    /** Vrai si des bâtiments du village attendent leur nouvelle apparence (rien d'autre en cours au village). */
    public static boolean due(Village village) {
        VillageLayout layout = Chantiers.layout(village);
        return layout != null && village.settled() && !BUSY.contains(village.id()) && !Chantiers.busy(village)
                && !behind(village, layout).isEmpty();
    }

    /** Charge les chunks du village puis donne à chaque bâtiment en retard l'apparence de son palier. */
    public static void catchUp(World world, Village village) {
        if (!BUSY.add(village.id())) {
            return;
        }
        try {
            Chantiers.loadThen(world, village, () -> {
                try {
                    int n = raiseNow(world, village, CATCH_UP_BATCH);
                    if (n > 0) {
                        Chantiers.announce(world, village, Message.translation(Chantiers.KEYS + "grown").param("village", village.name())
                                .param("count", n), null, NotificationStyle.Success);
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

    /**
     * Sur-le-champ (chunks du village chargés) : chaque bâtiment debout en retard prend l'apparence de son palier.
     * Rend le nombre de bâtiments recollés.
     */
    public static int raiseNow(World world, Village village) {
        return raiseNow(world, village, Integer.MAX_VALUE);
    }

    /** Comme {@link #raiseNow(World, Village)}, au plus `max` bâtiments recollés (rattrapage étalé). */
    static int raiseNow(World world, Village village, int max) {
        VillageLayout layout = Chantiers.layout(village);
        int done = 0;
        for (VillageLayout.Building b : behind(village, layout)) {
            if (done >= max) {
                break;
            }
            if (raise(world, village, layout, b)) {
                done++;
            }
        }
        return done;
    }

    /**
     * Monte d'apparence le bâtiment du plan qui loge le bâtiment de métier (ou le projet) `w`, après sa montée de niveau.
     * Charge les chunks du village d'abord. Sans effet s'il n'est pas debout ou déjà à son palier.
     */
    public static void afterLevel(World world, Village village, Workshop w) {
        VillageLayout layout = Chantiers.layout(village);
        VillageLayout.Building b = layout == null || w.building() == null ? null : layout.building(w.building());
        if (b == null || !village.isStanding(b.id())) {
            return;
        }
        String key = village.id() + "/" + b.id();
        if (!BUSY.add(key)) {
            return;
        }
        try {
            Chantiers.loadThen(world, village, () -> {
                try {
                    raise(world, village, layout, b);
                } finally {
                    BUSY.remove(key);
                }
            });
        } catch (RuntimeException e) {
            BUSY.remove(key);
            throw e;
        }
    }

    /** Donne au bâtiment debout `b` l'apparence de son palier s'il est en retard ; vrai si un prefab a été recollé. */
    static boolean raise(World world, Village village, VillageLayout layout, VillageLayout.Building b) {
        if (!village.isStanding(b.id())) {
            return false;
        }
        int from = village.palier(b.id());
        int to = BuildingPaliers.target(village, layout, b, village.tier());
        if (to <= from) {
            return false;
        }
        if (!BuildingPaliers.differ(layout, b, from, to)) {
            village.setPalier(b.id(), to); // même prefab, même place : rien à recoller
            return false;
        }
        return replace(world, village, layout, b, from, to);
    }

    /** Remplace le bâtiment `b` (palier `from`, debout) par son apparence du palier `to`. */
    static boolean replace(World world, Village village, VillageLayout layout, VillageLayout.Building b, int from, int to) {
        VillageLayout.Building old = BuildingPaliers.at(layout, b, from);
        VillageLayout.Building next = BuildingPaliers.at(layout, b, to);
        int h = Chantiers.siteHeight(world, village, old);
        ChunkStore chunks = world.getChunkStore();
        int[] ro = old.rect();
        int[] rn = next.rect();
        int x0 = village.x() + Math.min(ro[0], rn[0]), x1 = village.x() + Math.max(ro[1], rn[1]);
        int z0 = village.z() + Math.min(ro[2], rn[2]), z1 = village.z() + Math.max(ro[3], rn[3]);
        if (Blocks.id(chunks, x0, h, z0) == Blocks.UNLOADED || Blocks.id(chunks, x1, h, z1) == Blocks.UNLOADED) {
            return false; // zone pas chargée : on réessaiera au prochain passage
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        int top = h + Math.max(old.height(), next.height()) + 2;
        int[] exit = exitSpot(village, x0, x1, z0, z1);
        evacuate(world, store, x0 - EVACUATE_MARGIN, x1 + EVACUATE_MARGIN, z0 - EVACUATE_MARGIN, z1 + EVACUATE_MARGIN,
                h + old.depth() - 1, top, exit);

        // Panneau du bâtiment de métier : décroché, reposé contre le nouveau mur à la fin.
        Workshop w = BuildingPaliers.workshopOf(layout, b);
        WorkshopState ws = w == null ? null : village.workshopIfPresent(w.getId());
        if (ws != null && ws.hasBoard()) {
            int[] at = ws.board();
            Workshops.unhangBoard(chunks, at, Blocks.rotation(chunks, at[0], at[1], at[2]));
            Blocks.clearIf(chunks, at[0], at[1], at[2], village.people().boardBlock());
            ws.clearBoard();
        }

        clear(world, village, old, h);
        Chantiers.prepare(world, new Terrain(world), village, layout, next, h);
        if (!VillageBuilder.paste(world, store, next.prefab(), village.x() + next.x(), h, village.z() + next.z(), next.rotation())) {
            // Prefab introuvable (ne devrait pas arriver : le générateur les vérifie) : on remet l'ancien.
            VillageBuilder.paste(world, store, old.prefab(), village.x() + old.x(), h, village.z() + old.z(), old.rotation());
            LOGGER.atWarning().log("Nakasar Peuples : %s de %s non recollé au palier %d", b.id(), village.name(), to);
            village.setPalier(b.id(), to); // on garde l'ancien bâtiment, sans nouvel essai à chaque passage
            return false;
        }
        Chantiers.settle(world, village, layout, next, h);
        village.setSiteY(b.id(), h);
        village.setPalier(b.id(), to);
        unstick(world, store, x0 - SAFE_RADIUS, x1 + SAFE_RADIUS, z0 - SAFE_RADIUS, z1 + SAFE_RADIUS);
        if (w != null && ws != null) {
            Workshops.placeBoard(world, village, w, ws);
            if (ws.hasBoard()) {
                for (int level = 2; level <= ws.level(); level++) {
                    Workshop.Upgrade up = w.upgrade(level);
                    if (up != null) {
                        Workshops.placeDecor(world, ws.board(), up.decor(), Workshops.decorTurn(village, w));
                    }
                }
            }
        }
        Villagers.forgetGround();
        LOGGER.atInfo().log("Nakasar Peuples : %s de %s passe du palier %d (%s) au palier %d (%s)", b.id(), village.name(), from,
                old.prefab(), to, next.prefab());
        return true;
    }

    /**
     * Retire l'ancien bâtiment au-dessus du sol de son emprise (comme les ruines d'un bâtiment brûlé), en gardant les
     * conteneurs qui contiennent quelque chose (objets des joueurs) ; les conteneurs vides du prefab partent avec lui.
     */
    private static void clear(World world, Village village, VillageLayout.Building b, int h) {
        ChunkStore chunks = world.getChunkStore();
        int[] r = b.rect();
        for (int x = village.x() + r[0]; x <= village.x() + r[1]; x++) {
            for (int z = village.z() + r[2]; z <= village.z() + r[3]; z++) {
                for (int y = h + 1; y <= h + b.height() + 1; y++) {
                    String name = Blocks.name(chunks, x, y, z);
                    if (name != null && !keptContainer(world, x, y, z)) {
                        Blocks.set(chunks, x, y, z, null, 0);
                    }
                }
            }
        }
    }

    /** Vrai pour un conteneur qui contient au moins un objet. */
    public static boolean keptContainer(World world, int x, int y, int z) {
        ItemContainerBlock c = BlockModule.getComponent(ItemContainerBlock.getComponentType(), world, x, y, z);
        return c != null && c.getItemContainer() != null && !c.getItemContainer().isEmpty();
    }

    /** Point hors de l'emprise, du côté de la place du village (2 blocs au-delà du bord), au sol. */
    static int[] exitSpot(Village village, int x0, int x1, int z0, int z1) {
        int cx = Math.floorDiv(x0 + x1, 2), cz = Math.floorDiv(z0 + z1, 2);
        int dx = village.x() - cx, dz = village.z() - cz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[] {dx >= 0 ? x1 + 2 : x0 - 2, cz};
        }
        return new int[] {cx, dz >= 0 ? z1 + 2 : z0 - 2};
    }

    /**
     * Fait sortir les joueurs et les PNJ qui sont dans la boîte vers le point sûr le plus proche de `exit` (x, z), avant
     * que l'ancien bâtiment soit retiré.
     */
    public static void evacuate(World world, Store<EntityStore> store, int x0, int x1, int z0, int z1, int y0, int y1, int[] exit) {
        List<Ref<EntityStore>> inside = new ArrayList<>();
        for (PlayerRef p : world.getPlayerRefs()) {
            Ref<EntityStore> r = p.getReference();
            if (inBox(store, r, x0, x1, y0, y1, z0, z1)) {
                inside.add(r);
            }
        }
        store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                TransformComponent t = chunk.getComponent(i, TransformComponent.getComponentType());
                if (t == null) {
                    continue;
                }
                Vector3d at = new Vector3d(t.getPosition());
                if (at.x() >= x0 && at.x() < x1 + 1 && at.z() >= z0 && at.z() < z1 + 1 && at.y() >= y0 && at.y() <= y1) {
                    inside.add(chunk.getReferenceTo(i));
                }
            }
        });
        if (inside.isEmpty()) {
            return;
        }
        int[] spot = new Terrain(world).safeSpot(exit[0], exit[1], SAFE_RADIUS);
        if (spot == null) {
            return;
        }
        for (Ref<EntityStore> r : inside) {
            if (r != null && r.isValid()) {
                store.putComponent(r, Teleport.getComponentType(),
                        new Teleport(new Vector3d(spot[0] + 0.5, spot[1], spot[2] + 0.5), new Rotation3f(0f, 0f, 0f)));
            }
        }
        LOGGER.atInfo().log("Nakasar Peuples : %d joueur(s) ou PNJ sortis d'un bâtiment en travaux vers %d %d %d", inside.size(),
                spot[0], spot[1], spot[2]);
    }

    private static boolean inBox(Store<EntityStore> store, Ref<EntityStore> r, int x0, int x1, int y0, int y1, int z0, int z1) {
        Vector3d at = r == null || !r.isValid() ? null : dev.nakasar.hytale.peuples.request.Requests.position(store, r);
        return at != null && at.x() >= x0 && at.x() < x1 + 1 && at.z() >= z0 && at.z() < z1 + 1 && at.y() >= y0 && at.y() <= y1;
    }

    /** Joueurs et PNJ restés coincés dans un bloc plein après le collage : remontés au point sûr le plus proche. */
    public static void unstick(World world, Store<EntityStore> store, int x0, int x1, int z0, int z1) {
        Terrain terrain = new Terrain(world);
        List<Ref<EntityStore>> refs = new ArrayList<>();
        for (PlayerRef p : world.getPlayerRefs()) {
            refs.add(p.getReference());
        }
        store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                TransformComponent t = chunk.getComponent(i, TransformComponent.getComponentType());
                if (t != null && t.getPosition().x() >= x0 && t.getPosition().x() < x1 + 1 && t.getPosition().z() >= z0
                        && t.getPosition().z() < z1 + 1) {
                    refs.add(chunk.getReferenceTo(i));
                }
            }
        });
        for (Ref<EntityStore> r : refs) {
            Vector3d at = r == null || !r.isValid() ? null : dev.nakasar.hytale.peuples.request.Requests.position(store, r);
            if (at == null || at.x() < x0 || at.x() >= x1 + 1 || at.z() < z0 || at.z() >= z1 + 1) {
                continue;
            }
            int bx = (int) Math.floor(at.x()), by = (int) Math.floor(at.y()), bz = (int) Math.floor(at.z());
            if (terrain.bodyFree(bx, by, bz)) {
                continue;
            }
            int[] spot = terrain.safeSpot(bx, bz, SAFE_RADIUS);
            if (spot != null) {
                store.putComponent(r, Teleport.getComponentType(),
                        new Teleport(new Vector3d(spot[0] + 0.5, spot[1], spot[2] + 0.5), new Rotation3f(0f, 0f, 0f)));
            }
        }
    }

    /** Oublie les remplacements en cours (rechargement du mod). */
    public static void clearAll() {
        BUSY.clear();
    }
}
