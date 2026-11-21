package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.nakasar.hytale.peuples.village.Village;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * « Marquer sur la carte » de l'onglet Monde (Kevin, 21 novembre 2026) : un repère par joueur, sur la carte du monde et
 * la boussole, posé sur un village voisin choisi dans la fiche. Le même bouton le retire ; un nouveau repère remplace
 * l'ancien. Icône : celle du peuple du village (marqueurs de {@code tools/generate_map_icons.py}). Repères en mémoire :
 * ils ne survivent pas à un redémarrage du serveur.
 */
public final class WorldWaypoints {

    public static final String PROVIDER = "nakasar_peuples_waypoints";

    private record Waypoint(String world, String village, MapMarker marker) {
    }

    private static final Map<UUID, Waypoint> BY_PLAYER = new ConcurrentHashMap<>();
    private static final Set<World> REGISTERED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private WorldWaypoints() {
    }

    /** Id du village repéré par le joueur dans ce monde, ou null. */
    public static String of(World world, UUID player) {
        Waypoint w = BY_PLAYER.get(player);
        return w == null || !w.world().equals(world.getName()) ? null : w.village();
    }

    /** Pose (ou retire, si c'est déjà lui) le repère du joueur sur le village ; vrai s'il est posé. */
    public static boolean toggle(World world, PlayerRef player, Village village) {
        UUID id = player.getUuid();
        if (village.id().equals(of(world, id))) {
            BY_PLAYER.remove(id);
            return false;
        }
        String icon = "Nakasar_Peuples_" + village.people().id();
        MapMarker marker = new MapMarkerBuilder("Nakasar_Peuples_Waypoint_" + id, icon + ".png",
                new Transform(village.x() + 0.5, village.y(), village.z() + 0.5))
                .withName(Message.translation(WorldPage.TAB_KEYS + "sheet.marker").param("village", village.name()))
                .withCompassImage(icon + "Compass.png").build();
        BY_PLAYER.put(id, new Waypoint(world.getName(), village.id(), marker));
        if (REGISTERED.add(world)) {
            WorldMapManager.MarkerProvider provider = WorldWaypoints::update;
            world.getWorldMapManager().addMarkerProvider(PROVIDER, provider);
        }
        return true;
    }

    private static void update(World world, Player player, MarkersCollector collector) {
        PlayerRef ref = player.getPlayerRef();
        Waypoint w = ref == null ? null : BY_PLAYER.get(ref.getUuid());
        if (w != null && w.world().equals(world.getName())) {
            collector.add(w.marker());
        }
    }

    public static void clearAll() {
        BY_PLAYER.clear();
        REGISTERED.clear();
    }
}
