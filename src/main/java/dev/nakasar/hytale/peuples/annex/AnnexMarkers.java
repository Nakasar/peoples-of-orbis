package dev.nakasar.hytale.peuples.annex;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.BlockMapMarkersResource;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.nakasar.hytale.peuples.village.AnnexState;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.workshop.Workshop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Marqueurs des avant-postes de métier sur la carte du monde en jeu. Pas de bloc à marqueur sur place (comme la
 * bannière d'un village) : un fournisseur de marqueurs par monde, nourri d'un instantané des avant-postes posés
 * (en travaux ou debout) refait sur le fil du monde. Un joueur voit les avant-postes d'un village dès qu'il a
 * découvert ce village (marqueur de sa bannière révélé), comme le village lui-même. Les ouvrages ont leur propre icône :
 * fanion à la couleur de bannière du village qui les tient (propriétaire ou occupant), gris en ruine, et clignotant en
 * rouge (alternance toutes les {@value #BLINK_MILLIS} ms) pendant un siège.
 */
public final class AnnexMarkers {
    public static final String PROVIDER = "nakasar_peuples_annexes";
    public static final String ICON = "Nakasar_Peuples_Annex.png";
    public static final String COMPASS_ICON = "Nakasar_Peuples_AnnexCompass.png";
    private static final String KEYS = Annexes.KEYS;

    /** Préfixe des icônes des ouvrages (tools/generate_map_icons.py) : {@code <préfixe><Couleur>.png}, {@code Siege}, {@code Ruin}. */
    public static final String OUVRAGE_ICON = "Nakasar_Peuples_Ouvrage_";
    /** Demi-période du clignotement d'un ouvrage assiégé, en ms. */
    public static final long BLINK_MILLIS = 700L;

    /**
     * Un marqueur prêt à envoyer, son marqueur alterné (ouvrage assiégé : il clignote, sinon null), et l'id du marqueur de
     * bannière du village à avoir découvert (null : visible de tous).
     */
    private record Entry(MapMarker marker, MapMarker blink, String villageMarker) {
    }

    private static final Map<World, List<Entry>> SNAPSHOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private AnnexMarkers() {
    }

    /** Refait l'instantané du monde (fil du monde) et branche le fournisseur au premier passage. */
    public static void refresh(World world) {
        List<Entry> entries = new ArrayList<>();
        BlockMapMarkersResource banners = BlockMapMarkersResource.of(world);
        for (Village village : Villages.registry(world).all()) {
            String villageMarker = null;
            if (village.hasBanner()) {
                int[] b = village.banner();
                BlockMapMarkersResource.BlockMapMarkerData data = banners.getMarker(b[0], b[1], b[2]);
                villageMarker = data == null ? null : data.getMarkerId();
            }
            for (AnnexState a : village.annexes()) {
                if (a.granted()) {
                    continue; // fondation encore dans un sac : rien sur le terrain
                }
                Workshop w = Workshop.get(a.id());
                Message building = w != null ? Message.translation(w.nameKey()) : Message.raw(a.id());
                // Ouvrages : l'occupant, le siège ou la ruine se lisent dans le nom du repère.
                Village holder = a.occupied() ? Villages.registry(world).byId(a.occupant()) : null;
                Village besieger = a.besieged() ? Villages.registry(world).byId(a.siegeBy()) : null;
                String key = a.ruined() ? "marker.ruin" : a.working() ? "marker.works" : besieger != null ? "marker.besieged"
                        : holder != null ? "marker.occupied" : "marker";
                Message name = Message.translation(KEYS + key)
                        .param("building", building).param("village", village.name())
                        .param("holder", holder == null ? "?" : holder.name()).param("by", besieger == null ? "?" : besieger.name());
                String id = "Nakasar_Peuples_Annex_" + village.id() + "_" + a.id();
                Transform at = new Transform(a.x() + 0.5, a.y(), a.z() + 0.5);
                String icon = ICON;
                String compass = COMPASS_ICON;
                MapMarker blink = null;
                if (w != null && w.isAnnex() && w.annex().infrastructure()) {
                    // Ouvrage (Kevin, 2026-11-17) : fanion à la couleur de bannière du village qui le tient, gris en ruine,
                    // et il clignote en rouge pendant un siège.
                    Village keeper = holder != null ? holder : village;
                    String colour = a.ruined() ? "Ruin"
                            : dev.nakasar.hytale.peuples.world.Heraldry.COLOURS[dev.nakasar.hytale.peuples.world.Heraldry.of(world, keeper).colour()];
                    icon = OUVRAGE_ICON + colour + ".png";
                    compass = OUVRAGE_ICON + colour + "Compass.png";
                    if (besieger != null && !a.ruined()) {
                        blink = new MapMarkerBuilder(id, OUVRAGE_ICON + "Siege.png", at).withName(name)
                                .withCompassImage(OUVRAGE_ICON + "SiegeCompass.png").build();
                    }
                }
                MapMarker marker = new MapMarkerBuilder(id, icon, at).withName(name).withCompassImage(compass).build();
                entries.add(new Entry(marker, blink, villageMarker));
            }
        }
        boolean first = SNAPSHOTS.put(world, List.copyOf(entries)) == null;
        if (first) {
            WorldMapManager.MarkerProvider provider = AnnexMarkers::update;
            world.getWorldMapManager().addMarkerProvider(PROVIDER, provider);
        }
    }

    private static void update(World world, Player player, MarkersCollector collector) {
        List<Entry> entries = SNAPSHOTS.get(world);
        if (entries == null || entries.isEmpty()) {
            return;
        }
        PlayerWorldData data = player.getPlayerConfigData().getPerWorldData(world.getName());
        boolean lit = (System.currentTimeMillis() / BLINK_MILLIS) % 2 == 0;
        for (Entry e : entries) {
            if (e.villageMarker() == null || (data != null && data.isMarkerRevealed(e.villageMarker()))) {
                collector.add(e.blink() != null && lit ? e.blink() : e.marker());
            }
        }
    }

    public static void clearAll() {
        SNAPSHOTS.clear();
    }
}
