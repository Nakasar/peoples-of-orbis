package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.village.Village;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Registre du commerce d'un village (refonte des écrans de commerce, Kevin, 21 novembre 2026) : les derniers événements
 * des routes, caravanes, manques, commandes et achats, lus dans le sous-onglet « Registre » de l'onglet Commerce au lieu
 * de se perdre dans le tchat. Chaque annonce du commerce ({@link TradeRoutes#announce}) y laisse une ligne.
 *
 * <p>État sauvé ({@link WorldLifeRegistry.Part#TRADE_LOG}) : « village » = prochain numéro, « village|n » =
 * « jour;heure;sorte;objet;message » (message au format du codec vanilla). {@value #KEEP} lignes par village.
 */
public final class TradeLog {

    public static final int KEEP = 24;

    /** Sorte d'événement : sa couleur et le filtre qui la montre. */
    public enum Kind {
        ARRIVAL("#9ed36a", "Caravans"),
        LOSS("#e07468", "Caravans"),
        NEED("#e8a65a", "Needs"),
        COVER("#7fa8d8", "Needs"),
        ORDER("#e8a65a", "Orders"),
        PURCHASE("#9ed36a", "Purchases"),
        ROUTE("#9ed36a", "Routes");

        public final String colour;
        public final String filter;

        Kind(String colour, String filter) {
            this.colour = colour;
            this.filter = filter;
        }
    }

    /** Une ligne du registre. */
    public record Entry(long day, int hour, Kind kind, String item, Message text) {
    }

    private TradeLog() {
    }

    private static String encode(Message m) {
        try {
            BsonValue v = Message.CODEC.encode(m, new ExtraInfo());
            return new BsonDocument("m", v).toJson().replace('\n', ' ');
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Message decode(String json) {
        try {
            return Message.CODEC.decode(BsonDocument.parse(json).get("m"), new ExtraInfo());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Note un événement dans le registre du village. */
    public static void add(World world, Village v, Kind kind, String item, Message text) {
        if (world == null || v == null || text == null) {
            return;
        }
        String json = encode(text);
        if (json == null) {
            return;
        }
        WorldLifeRegistry reg = WorldLife.registry(world);
        long n = reg.getLong(WorldLifeRegistry.Part.TRADE_LOG, v.id(), 0L);
        var store = world.getEntityStore().getStore();
        String value = GameClock.day(store) + ";" + Math.max(0, GameClock.hour(store)) + ";" + kind.name() + ";"
                + (item == null ? "" : item) + ";" + json;
        reg.put(WorldLifeRegistry.Part.TRADE_LOG, v.id() + "|" + (n % KEEP), value);
        reg.put(WorldLifeRegistry.Part.TRADE_LOG, v.id(), Long.toString(n + 1));
    }

    /** Les lignes du village, la plus récente d'abord. */
    public static List<Entry> of(World world, Village v) {
        List<Entry> out = new ArrayList<>();
        if (world == null || v == null) {
            return out;
        }
        WorldLifeRegistry reg = WorldLife.registry(world);
        long n = reg.getLong(WorldLifeRegistry.Part.TRADE_LOG, v.id(), 0L);
        for (long k = n - 1; k >= Math.max(0, n - KEEP); k--) {
            String s = reg.get(WorldLifeRegistry.Part.TRADE_LOG, v.id() + "|" + (k % KEEP));
            Entry e = s == null ? null : parse(s);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static Entry parse(String s) {
        String[] p = s.split(";", 5);
        if (p.length < 5) {
            return null;
        }
        try {
            Message m = decode(p[4]);
            return m == null ? null : new Entry(Long.parseLong(p[0]), Integer.parseInt(p[1]), Kind.valueOf(p[2]),
                    p[3].isEmpty() ? null : p[3], m);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Oublie le registre d'un village disparu. */
    public static void forget(World world, Village v) {
        WorldLifeRegistry reg = WorldLife.registry(world);
        reg.put(WorldLifeRegistry.Part.TRADE_LOG, v.id(), null);
        for (int k = 0; k < KEEP; k++) {
            reg.put(WorldLifeRegistry.Part.TRADE_LOG, v.id() + "|" + k, null);
        }
    }
}
