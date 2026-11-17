package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.nakasar.hytale.peuples.player.PeuplesPlayer;
import dev.nakasar.hytale.peuples.reputation.Rank;
import dev.nakasar.hytale.peuples.reputation.Reputations;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.workshop.Projects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Marqueurs des lieux-dits sur la carte du monde et la boussole, comme les avant-postes ({@code annex/AnnexMarkers}) :
 * un fournisseur de marqueurs par monde, nourri d'un instantané refait sur le fil du monde. Un joueur voit un lieu-dit
 * posé dès qu'il l'a découvert (arrivé sur sa place : drapeau {@code Hamlet:<id>} de {@link Hamlets}), ou s'il est à
 * moins de {@value Projects#CARTOGRAPHERS_RANGE} blocs d'un village dont la Bibliothèque a les Cartographes et où le
 * joueur est au moins Toléré.
 */
public final class HamletMarkers {
    public static final String PROVIDER = "nakasar_peuples_hamlets";
    public static final String ICON = "Nakasar_Peuples_Hamlet.png";
    public static final String COMPASS_ICON = "Nakasar_Peuples_HamletCompass.png";

    /** Un marqueur prêt à envoyer et les joueurs (en ligne au dernier instantané) qui ont découvert le lieu-dit. */
    private record Entry(MapMarker marker, String flag, int x, int z) {
    }

    private record Snapshot(List<Entry> entries, Map<UUID, Set<String>> discovered) {
    }

    private static final Map<World, Snapshot> SNAPSHOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private HamletMarkers() {
    }

    /** Refait l'instantané du monde (fil du monde) et branche le fournisseur au premier passage. */
    static void refresh(World world, Store<EntityStore> store) {
        List<Entry> entries = new ArrayList<>();
        for (Hamlets.Place h : Hamlets.all(world)) {
            if (h.stage() != Hamlets.BUILT) {
                continue; // pas encore posé (ou sans place) : rien sur le terrain
            }
            Hamlet type = h.type();
            Message people = Message.translation(type == null ? "?" : type.people().nameKey());
            int[] m = h.market();
            MapMarker marker = new MapMarkerBuilder("Nakasar_Peuples_Hamlet_" + h.id(), ICON,
                    new Transform(m[0] + 0.5, h.y, m[1] + 0.5))
                    .withName(Message.translation(Hamlets.KEYS + "marker").param("name", h.name()).param("people", people))
                    .withCompassImage(COMPASS_ICON).build();
            entries.add(new Entry(marker, Hamlets.FLAG + h.id(), m[0], m[1]));
        }
        // Cartographes de la Bibliothèque : les lieux-dits proches du village sont sur la carte de ses amis (Toléré et plus).
        List<Village> mapping = new ArrayList<>();
        for (Village v : Villages.registry(world).all()) {
            if (!v.isHamlet() && Projects.specialty(v, Projects.KNOWLEDGE, Projects.CARTOGRAPHERS)) {
                mapping.add(v);
            }
        }
        long range2 = (long) Projects.CARTOGRAPHERS_RANGE * Projects.CARTOGRAPHERS_RANGE;
        Map<UUID, Set<String>> discovered = new java.util.HashMap<>();
        for (PlayerRef p : world.getPlayerRefs()) {
            Ref<EntityStore> ref = p.getReference();
            PeuplesPlayer data = ref == null || !ref.isValid() ? null : PeuplesPlayer.get(store, ref);
            if (data == null) {
                continue;
            }
            Set<String> flags = new HashSet<>();
            List<Village> maps = new ArrayList<>();
            for (Village v : mapping) {
                if (Reputations.rank(store, ref, v).atLeast(Rank.TOLERE)) {
                    maps.add(v);
                }
            }
            for (Entry e : entries) {
                if (data.hasFlag(e.flag())) {
                    flags.add(e.flag());
                    continue;
                }
                for (Village v : maps) {
                    if (v.horizontalDistanceSquared(e.x(), e.z()) <= range2) {
                        flags.add(e.flag());
                        break;
                    }
                }
            }
            discovered.put(p.getUuid(), flags);
        }
        boolean first = SNAPSHOTS.put(world, new Snapshot(List.copyOf(entries), discovered)) == null;
        if (first) {
            WorldMapManager.MarkerProvider provider = HamletMarkers::update;
            world.getWorldMapManager().addMarkerProvider(PROVIDER, provider);
        }
    }

    private static void update(World world, Player player, MarkersCollector collector) {
        Snapshot snapshot = SNAPSHOTS.get(world);
        if (snapshot == null || snapshot.entries().isEmpty()) {
            return;
        }
        PlayerRef ref = player.getPlayerRef();
        Set<String> flags = ref == null ? null : snapshot.discovered().get(ref.getUuid());
        if (flags == null || flags.isEmpty()) {
            return;
        }
        for (Entry e : snapshot.entries()) {
            if (flags.contains(e.flag())) {
                collector.add(e.marker());
            }
        }
    }

    public static void clearAll() {
        SNAPSHOTS.clear();
    }
}
