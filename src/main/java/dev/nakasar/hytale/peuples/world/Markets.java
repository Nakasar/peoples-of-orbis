package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.builtin.adventure.shop.barter.BarterShopAsset;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterItemStack;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterShopState;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterTrade;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.nakasar.hytale.peuples.dialogue.Dialogue;
import dev.nakasar.hytale.peuples.dialogue.DialogueSpeech;
import dev.nakasar.hytale.peuples.dialogue.Dialogues;
import dev.nakasar.hytale.peuples.reputation.Rank;
import dev.nakasar.hytale.peuples.reputation.Reputations;
import dev.nakasar.hytale.peuples.request.Criers;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.village.Chantiers;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.Placing;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageBoard;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.village.WorkshopState;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La place du marché des villages (Kevin, 21 novembre 2026) : bâtiment du palier 0 (« Marche » des plans,
 * {@code tools/generate_markets.py}) où se tient le marchand du village ({@link Criers}), où s'installe pour un jour le
 * marchand itinérant d'un autre village (étal à l'aspect de son peuple, {@code Etal_<Peuple>}), et qui se spécialise au
 * niveau II de son bâtiment de métier :
 * <ul>
 *   <li><b>Marché extérieur</b> : un comptoir fixe ({@code <Peuple>_Comptoir}) qui rachète le reste des étals de passage
 *   (boutiques {@code Comptoir_<Hôte>_<Origine>}, ouvertes {@value #VISIT_DAYS} jours après la visite d'un marchand de
 *   cette origine) et prend les commandes du joueur ;</li>
 *   <li><b>Marché intérieur</b> : un dépôt-vente ({@code <Peuple>_Depot}) où le joueur laisse des marchandises vendues
 *   au prochain marchand de passage ou au jour de marché, les commandes aux marchands itinérants, et un jour de marché
 *   sur {@value #WEEK} (étal des artisans {@code Marche_<Peuple>_Jour}, un marchand de passage assuré).</li>
 * </ul>
 * Une commande attend un marchand du peuple qui porte l'objet (ou l'accès par une route commerciale) ; en attendant,
 * le village la fait savoir aux voyageurs (besoin {@link TradeNeeds.Reason#ORDER}). État dans
 * {@link WorldLifeRegistry.Part#MARKETS} :
 * <ul>
 *   <li>{@code G:<village>} = peuple|village d'origine|jour (le marchand de passage, jusqu'au matin suivant) ;</li>
 *   <li>{@code V:<village>|<peuple>} = jour de la dernière visite d'un marchand de ce peuple ;</li>
 *   <li>{@code O:<village>|<joueur>} = objet|nombre|prix|peuple d'origine|W (attend) ou R (prête)|jour ;</li>
 *   <li>{@code C:<village>|<joueur>} = objet|nombre|prix (dépôt-vente), {@code P:<village>|<joueur>} = faveurs à retirer ;</li>
 *   <li>{@code K:<village>} = spécialité|palier du comptoir posé, {@code M:<village>} = le marchand a changé de place.</li>
 * </ul>
 * Thread du monde.
 */
public final class Markets {

    public static final String BUILDING = "Marche";
    public static final String KEYS = "nakasar_peuples.market.";
    public static final String INTERIOR = "Interieur";
    public static final String EXTERIOR = "Exterieur";
    /** Marché noir (Kevin, 21 novembre 2026) : un receleur la nuit, sans perte de réputation. */
    public static final String BLACK = "Noir";
    /** tools/generate_markets.py VILLAGE_STALL, GUEST_STALL, COUNTER (coordonnées du prefab, devant = +Z). */
    private static final int[] VILLAGE_STALL = {-4, -2};
    private static final int[] GUEST_STALL = {4, -2};
    private static final int[] COUNTER = {0, 2};
    /** Une visite ouvre l'étal du comptoir pour ce peuple pendant ce nombre de jours. */
    public static final int VISIT_DAYS = 7;
    /** Un jour de marché tous les {@value} jours (décalé d'un village à l'autre). */
    public static final int WEEK = 7;
    /** Commandes en cours par marché ; lots d'une commande ; faveur du commis. */
    public static final int MAX_ORDERS = 3;
    public static final int ORDER_LOTS = 3;
    public static final int ORDER_FEE = 1;
    /** Une commande non retirée est oubliée après ce nombre de jours (le marché ne reste pas bloqué). */
    public static final int ORDER_DAYS = 14;
    /** Chance qu'un marchand de passage achète le dépôt-vente (sûr au jour de marché). */
    private static final double GUEST_BUYS = 0.5;

    private static final String GUEST = "G:";
    private static final String VISIT = "V:";
    private static final String ORDER = "O:";
    private static final String CONSIGN = "C:";
    private static final String PROCEEDS = "P:";
    private static final String COUNTER_MARK = "K:";
    private static final String MOVED = "M:";
    private static final String STALL_ID = "market:";

    /** PNJ posés : marchand de passage et commis, par village ; et le village de chaque PNJ. */
    private static final Map<String, Ref<EntityStore>> GUEST_NPC = new ConcurrentHashMap<>();
    private static final Map<String, Ref<EntityStore>> CLERK_NPC = new ConcurrentHashMap<>();
    private static final Map<Ref<EntityStore>, String> VILLAGE_OF = new ConcurrentHashMap<>();
    private static final Map<String, Place> PLACES = new ConcurrentHashMap<>();

    private Markets() {
    }

    // ------------------------------------------------------------------------------------------------ lieu

    /** La place posée : ancre du prefab (sol à y) et rotation. */
    record Place(int x, int y, int z, int rot) {
        int[] at(int[] local) {
            int[] r = Placing.rotate(local[0], local[1], rot);
            return new int[] {x + r[0], z + r[1]};
        }

        Vector3d stand(int[] local) {
            int[] w = at(local);
            return new Vector3d(w[0] + 0.5, y + 1, w[1] + 0.5);
        }

        /** Tourné vers le devant de la place (+Z du prefab), convention de {@link Walk}. */
        float yaw() {
            int[] f = Placing.rotate(0, 1, rot);
            return (float) Math.atan2(-f[0], f[1]);
        }
    }

    /** La place du marché debout du village, ou null. */
    static Place place(Village v) {
        if (v == null || v.isHamlet() || !v.isStanding(BUILDING)) {
            if (v != null) {
                PLACES.remove(v.id());
            }
            return null;
        }
        Place cached = PLACES.get(v.id());
        if (cached != null) {
            return cached;
        }
        VillageLayout layout = Chantiers.layout(v);
        VillageLayout.Building b = layout == null ? null : layout.building(BUILDING);
        if (b == null) {
            return null;
        }
        int y = v.siteY(BUILDING);
        Place p = new Place(v.x() + b.x(), y == Integer.MIN_VALUE ? v.y() - 1 : y, v.z() + b.z(), b.rotation());
        PLACES.put(v.id(), p);
        return p;
    }

    /** Où se tient le marchand du village : derrière son étal de la place du marché, ou null (pas de marché debout). */
    public static Vector3d merchantSpot(Village v) {
        Place p = place(v);
        return p == null ? null : p.stand(VILLAGE_STALL);
    }

    public static float merchantYaw(Village v) {
        Place p = place(v);
        return p == null ? 0f : p.yaw();
    }

    /** Le bâtiment de métier de la place du marché du village, ou null. */
    public static Workshop workshop(Village v) {
        for (Workshop w : Workshop.forLayout(v.layout())) {
            if (w.isMarket()) {
                return w;
            }
        }
        return null;
    }

    /** Spécialité choisie (niveau II et plus) : {@value #INTERIOR}, {@value #EXTERIOR}, ou null. */
    public static String specialty(Village v) {
        if (place(v) == null) {
            return null;
        }
        Workshop w = workshop(v);
        WorkshopState ws = w == null ? null : v.workshopIfPresent(w.getId());
        if (ws == null || ws.level() < 2 || ws.specialty() == null || ws.specialty().isBlank()) {
            return null;
        }
        return ws.specialty();
    }

    public static boolean exterior(Village v) {
        return EXTERIOR.equals(specialty(v));
    }

    public static boolean interior(Village v) {
        return INTERIOR.equals(specialty(v));
    }

    public static boolean black(Village v) {
        return BLACK.equals(specialty(v));
    }

    /** Ce que le receleur vend sous le manteau. */
    public static String blackShop(People people) {
        return "Nakasar_Peuples_Marche_" + people.id() + "_Noir";
    }

    /** Le butin des raids que le receleur rachète. */
    public static String fenceShop(People people) {
        return "Nakasar_Peuples_Marche_" + people.id() + "_Recel";
    }

    /** Pourquoi le marché noir est fermé (le jour), ou null s'il est ouvert. */
    public static Message blackLock(Store<EntityStore> store) {
        return GameClock.isNight(store) ? null : Message.translation(KEYS + "noir.closed").param("hour", GameClock.DUSK);
    }

    /** Les étals du receleur : sa vente ou son recel, la nuit seulement. */
    public static boolean blackShop(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Village host, boolean fence) {
        if (host == null || !black(host)) {
            DialogueSpeech.say(player, Message.translation(KEYS + "order.notHere"));
            return false;
        }
        Message closed = blackLock(store);
        if (closed != null) {
            DialogueSpeech.say(player, closed);
            return false;
        }
        return dev.nakasar.hytale.peuples.reputation.Shops.open(store, ref, player, host,
                fence ? fenceShop(host.people()) : blackShop(host.people()));
    }

    /** Vrai le jour de marché du village (marché intérieur seulement). */
    public static boolean marketDay(Village v, long day) {
        return interior(v) && Math.floorMod(day + v.id().hashCode(), WEEK) == 0;
    }

    /** Jours avant le prochain jour de marché (0 : aujourd'hui). */
    public static int daysToMarket(Village v, long day) {
        return Math.floorMod(-(day + v.id().hashCode()), WEEK);
    }

    public static String dayShop(People people) {
        return "Nakasar_Peuples_Marche_" + people.id() + "_Jour";
    }

    public static String counterShop(Village host, People origin) {
        return "Nakasar_Peuples_Comptoir_" + host.people().id() + "_" + origin.id();
    }

    // ------------------------------------------------------------------------------------------------ registre

    private static Map<String, String> part(World world) {
        return WorldLife.registry(world).part(WorldLifeRegistry.Part.MARKETS);
    }

    private static long parse(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Le marchand de passage posté au marché. */
    public record Guest(People people, String from, long day) {
    }

    public static Guest guest(World world, Village v) {
        String g = part(world).get(GUEST + v.id());
        if (g == null) {
            return null;
        }
        String[] p = g.split("\\|", -1);
        People people = p.length < 3 ? null : People.byId(p[0]);
        return people == null ? null : new Guest(people, p[1], parse(p[2], 0));
    }

    /** Jour de la dernière visite d'un marchand de ce peuple, ou {@link Long#MIN_VALUE}. */
    public static long lastVisit(World world, Village v, People people) {
        String s = part(world).get(VISIT + v.id() + "|" + people.id());
        return s == null ? Long.MIN_VALUE : parse(s, Long.MIN_VALUE);
    }

    /** Une commande du joueur. */
    public record Order(String village, String player, String item, int count, int price, People origin, boolean ready, long day) {
        String encode() {
            return item + "|" + count + "|" + price + "|" + origin.id() + "|" + (ready ? "R" : "W") + "|" + day;
        }
    }

    private static Order decodeOrder(String key, String value) {
        String[] k = key.substring(ORDER.length()).split("\\|", -1);
        String[] p = value.split("\\|", -1);
        if (k.length < 2 || p.length < 6 || People.byId(p[3]) == null) {
            return null;
        }
        return new Order(k[0], k[1], p[0], (int) parse(p[1], 1), (int) parse(p[2], 1), People.byId(p[3]), "R".equals(p[4]), parse(p[5], 0));
    }

    public static List<Order> orders(World world, Village v) {
        List<Order> out = new ArrayList<>();
        String prefix = ORDER + v.id() + "|";
        for (Map.Entry<String, String> e : part(world).entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                Order o = decodeOrder(e.getKey(), e.getValue());
                if (o != null) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    public static Order order(World world, Village v, UUID player) {
        String key = ORDER + v.id() + "|" + player;
        String value = part(world).get(key);
        return value == null ? null : decodeOrder(key, value);
    }

    private static void save(World world, Order o) {
        part(world).put(ORDER + o.village() + "|" + o.player(), o.encode());
    }

    /** Objets que le village cherche pour les commandes des joueurs (besoins portés par les voyageurs). */
    public static List<String> wanted(World world, Village v) {
        List<String> out = new ArrayList<>();
        for (Order o : orders(world, v)) {
            if (!o.ready() && !out.contains(o.item())) {
                out.add(o.item());
            }
        }
        return out;
    }

    /** Un dépôt-vente du joueur. */
    public record Consignment(String item, int count, int price) {
    }

    public static Consignment consignment(World world, Village v, UUID player) {
        String s = part(world).get(CONSIGN + v.id() + "|" + player);
        if (s == null) {
            return null;
        }
        String[] p = s.split("\\|", -1);
        return p.length < 3 ? null : new Consignment(p[0], (int) parse(p[1], 1), (int) parse(p[2], 1));
    }

    public static int proceeds(World world, Village v, UUID player) {
        String s = part(world).get(PROCEEDS + v.id() + "|" + player);
        return s == null ? 0 : (int) parse(s, 0);
    }

    // ------------------------------------------------------------------------------------------------ catalogue

    /** Ce qu'un marchand itinérant de `origin` peut apporter : objet, lot et prix d'un troc de sa boutique. */
    public record Offer(String item, People origin, int lot, int price) {
        public int count() {
            return lot * ORDER_LOTS;
        }

        public int total() {
            return price * ORDER_LOTS + ORDER_FEE;
        }
    }

    private static List<Offer> travelOffers(Store<EntityStore> store, People origin) {
        List<Offer> out = new ArrayList<>();
        BarterShopAsset a = BarterShopAsset.getAssetMap().getAsset(Travellers.shop(origin));
        if (a == null) {
            return out;
        }
        for (BarterTrade trade : BarterShopState.get().getResolvedTrades(a, BarterShopState.getShopGameTime(store))) {
            if (trade == null || trade.getOutput() == null || trade.getInput() == null || trade.getInput().length == 0) {
                continue;
            }
            String item = trade.getOutput().getItemId();
            if (item == null || item.startsWith("Nakasar_")) {
                continue;
            }
            int price = 0;
            for (BarterItemStack in : trade.getInput()) {
                price += in.getQuantity();
            }
            out.add(new Offer(item, origin, Math.max(1, trade.getOutput().getQuantity()), Math.max(1, price)));
        }
        return out;
    }

    /** Ce que le joueur peut commander au marché du village : les marchandises des marchands des autres peuples. */
    public static List<Offer> catalogue(Store<EntityStore> store, Village host) {
        Map<String, Offer> out = new LinkedHashMap<>();
        for (People origin : People.values()) {
            if (origin == host.people()) {
                continue;
            }
            for (Offer o : travelOffers(store, origin)) {
                if (TradeGoods.allowed(host.people(), o.item())) {
                    out.putIfAbsent(o.item(), o);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static boolean carries(Store<EntityStore> store, People origin, String item) {
        for (Offer o : travelOffers(store, origin)) {
            if (o.item().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Les marchandises que le dépôt-vente accepte : ce que vendent les marchands itinérants des cinq peuples et les
     * luxes, que le village peut échanger. Un bloc quelconque (terre, pierre) n'a pas d'acheteur.
     */
    public static java.util.Set<String> consignable(Store<EntityStore> store, Village host) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (People origin : People.values()) {
            for (Offer o : travelOffers(store, origin)) {
                out.add(o.item());
            }
        }
        out.addAll(TradeGoods.luxuryItems());
        out.removeIf(item -> item.startsWith("Nakasar_") || !TradeGoods.allowed(host.people(), item));
        return out;
    }

    /** Au plus un chargement de route du niveau III par dépôt. */
    public static int consignMax(String item) {
        return TradeGoods.grade(item).load(3);
    }

    /** Ce que rapporte un dépôt : le poids de la classe par chargement de route du niveau I, au moins une faveur. */
    public static int consignPrice(String item, int count) {
        TradeGoods.Grade g = TradeGoods.grade(item);
        return Math.max(1, (int) Math.round(count * g.weight() / (double) g.load(1)));
    }

    // ------------------------------------------------------------------------------------------------ arrivées

    /**
     * Un marchand itinérant ou une caravane de `from` arrive à `to` : il passe au marché (visite notée, étal de passage
     * s'il est libre), les commandes qu'il porte sont prêtes, et il achète peut-être le dépôt-vente.
     */
    public static void welcome(World world, Store<EntityStore> store, Village to, Village from) {
        if (to == null || from == null || to == from || place(to) == null) {
            return;
        }
        long day = GameClock.day(store);
        Place p = place(to);
        part(world).put(VISIT + to.id() + "|" + from.people().id(), Long.toString(day));
        Message merchant = Message.translation(KEYS + "guest.name").param("village", from.name());
        if (guest(world, to) == null) {
            part(world).put(GUEST + to.id(), from.people().id() + "|" + from.id() + "|" + day);
            WorldLife.announce(world, store, p.x(), p.z(), WorldLife.REAL_RANGE,
                    Message.translation(KEYS + "guest.arrived").param("merchant", merchant).param("village", to.name()), NotificationStyle.Default);
        }
        if (exterior(to)) {
            WorldLife.announce(world, store, p.x(), p.z(), WorldLife.REAL_RANGE,
                    Message.translation(KEYS + "counter.bought").param("village", to.name()).param("merchant", merchant), null);
        }
        for (Order o : orders(world, to)) {
            if (!o.ready() && o.origin() == from.people() && carries(store, from.people(), o.item())) {
                ready(world, to, o, Message.translation(KEYS + "order.ready").param("village", to.name()).param("count", o.count())
                        .param("item", VillageBoard.itemName(o.item())).param("merchant", merchant));
            }
        }
        if (interior(to)) {
            sellConsignments(world, to, marketDay(to, day) ? 1.0 : GUEST_BUYS, new Random());
        }
    }

    private static void ready(World world, Village v, Order o, Message message) {
        save(world, new Order(o.village(), o.player(), o.item(), o.count(), o.price(), o.origin(), true, o.day()));
        tell(world, o.player(), message, NotificationStyle.Success);
    }

    private static void tell(World world, String player, Message message, NotificationStyle style) {
        for (PlayerRef p : world.getPlayerRefs()) {
            if (p.getUuid().toString().equals(player)) {
                p.sendMessage(message);
                NotificationUtil.sendNotification(p.getPacketHandler(), message, style);
            }
        }
    }

    private static void sellConsignments(World world, Village v, double chance, Random random) {
        String prefix = CONSIGN + v.id() + "|";
        for (Map.Entry<String, String> e : new ArrayList<>(part(world).entrySet())) {
            if (!e.getKey().startsWith(prefix) || random.nextDouble() >= chance) {
                continue;
            }
            String player = e.getKey().substring(prefix.length());
            String[] c = e.getValue().split("\\|", -1);
            if (c.length < 3) {
                part(world).remove(e.getKey());
                continue;
            }
            int price = (int) parse(c[2], 1);
            String pk = PROCEEDS + v.id() + "|" + player;
            part(world).put(pk, Long.toString(parse(part(world).getOrDefault(pk, "0"), 0) + price));
            part(world).remove(e.getKey());
            tell(world, player, Message.translation(KEYS + "consign.sold").param("village", v.name()).param("count", (int) parse(c[1], 1))
                    .param("item", VillageBoard.itemName(c[0])).param("price", price), NotificationStyle.Success);
        }
    }

    // ------------------------------------------------------------------------------------------------ matin

    /** Chaque matin : le marchand de passage repart, jour de marché, commandes arrivées par les routes. */
    public static void morning(World world, Store<EntityStore> store, long day, Random random) {
        for (Village v : Villages.registry(world).all()) {
            if (v.isHamlet()) {
                continue;
            }
            Place p = place(v);
            Guest g = guest(world, v);
            if (g != null && (p == null || g.day() < day)) {
                leave(world, store, v, g, p);
            }
            if (p == null) {
                continue;
            }
            if (marketDay(v, day)) {
                if (guest(world, v) == null) {
                    Village from = visitor(world, v, random);
                    if (from != null) {
                        part(world).put(GUEST + v.id(), from.people().id() + "|" + from.id() + "|" + day);
                        part(world).put(VISIT + v.id() + "|" + from.people().id(), Long.toString(day));
                    }
                }
                sellConsignments(world, v, 1.0, random);
                TradeGoods.Luxury lux = TradeGoods.terroir(v.people());
                WorldLife.announce(world, store, v.x(), v.z(), Villages.RADIUS + WorldLife.REAL_RANGE,
                        Message.translation(KEYS + "day.today").param("village", v.name())
                                .param("luxury", lux == null ? Message.raw("?") : VillageBoard.itemName(lux.item())), NotificationStyle.Default);
            }
            for (Order o : orders(world, v)) {
                if (day - o.day() > ORDER_DAYS) {
                    cancelOrder(world, v, UUID.fromString(o.player()));
                    tell(world, o.player(), Message.translation(KEYS + "order.expired").param("village", v.name())
                            .param("item", VillageBoard.itemName(o.item())).param("days", ORDER_DAYS), NotificationStyle.Warning);
                    continue;
                }
                if (!o.ready() && TradeAccess.has(world, v, o.item(), day)) {
                    ready(world, v, o, Message.translation(KEYS + "order.readyImport").param("village", v.name()).param("count", o.count())
                            .param("item", VillageBoard.itemName(o.item())));
                }
            }
        }
    }

    /** Le marchand du jour de marché : un village d'un autre peuple, parmi les plus proches. */
    private static Village visitor(World world, Village v, Random random) {
        List<Village> others = new ArrayList<>();
        for (Village o : Villages.registry(world).all()) {
            if (o != v && !o.isHamlet() && o.people() != v.people()) {
                others.add(o);
            }
        }
        if (others.isEmpty()) {
            return null;
        }
        others.sort((a, b) -> Double.compare(WorldLife.distance(v, a), WorldLife.distance(v, b)));
        return others.get(random.nextInt(Math.min(3, others.size())));
    }

    private static void leave(World world, Store<EntityStore> store, Village v, Guest g, Place p) {
        part(world).remove(GUEST + v.id());
        Ref<EntityStore> npc = GUEST_NPC.remove(v.id());
        if (npc != null) {
            VILLAGE_OF.remove(npc);
            WorldLife.remove(store, npc);
        }
        Sites.release(world, STALL_ID + v.id());
        Village from = Villages.registry(world).byId(g.from());
        if (p != null && from != null) {
            WorldLife.announce(world, store, p.x(), p.z(), WorldLife.REAL_RANGE, Message.translation(KEYS + "guest.left")
                    .param("merchant", Message.translation(KEYS + "guest.name").param("village", from.name())).param("village", v.name()), null);
        }
    }

    // ------------------------------------------------------------------------------------------------ pas

    /** Toutes les 2 s : le marchand change de place, comptoir posé, marchand de passage et commis près des joueurs. */
    public static void step(World world, Store<EntityStore> store, List<Vector3d> players) {
        for (Village v : Villages.registry(world).all()) {
            Place p = place(v);
            if (p == null) {
                // Place brûlée ou pas encore bâtie : comptoir à recoller et marchand à replacer après la reconstruction.
                dropNpcs(store, v.id());
                part(world).remove(COUNTER_MARK + v.id());
                part(world).remove(MOVED + v.id());
                continue;
            }
            if (!part(world).containsKey(MOVED + v.id())) {
                // La place vient d'être bâtie : le marchand quitte le dépôt pour son étal.
                Criers.moveMerchant(store, v);
                part(world).put(MOVED + v.id(), "1");
            }
            boolean near = WorldLife.near(players, p.x(), p.z(), WorldLife.REAL_RANGE);
            String spec = specialty(v);
            if (spec != null && near) {
                placeCounter(world, store, v, p, spec);
            }
            Guest g = guest(world, v);
            guestNpc(world, store, v, p, g, near);
            clerkNpc(world, store, v, p, spec, near);
        }
    }

    private static void placeCounter(World world, Store<EntityStore> store, Village v, Place p, String spec) {
        String mark = spec + "|" + v.palier(BUILDING);
        if (mark.equals(part(world).get(COUNTER_MARK + v.id()))) {
            return;
        }
        int[] at = p.at(COUNTER);
        if (!Sites.loaded(world, at[0], at[1])) {
            return;
        }
        String prefab = "Nakasar/Peuples/Markets/" + v.people().id() + suffix(spec);
        if (Sites.pastePermanent(world, store, prefab, at[0], p.y(), at[1], p.rot())) {
            part(world).put(COUNTER_MARK + v.id(), mark);
            String placed = INTERIOR.equals(spec) ? "depot.placed" : BLACK.equals(spec) ? "noir.placed" : "counter.placed";
            WorldLife.announce(world, store, p.x(), p.z(), WorldLife.REAL_RANGE, Message.translation(KEYS + placed)
                    .param("village", v.name()), NotificationStyle.Success);
        }
    }

    /** Prefab et dialogue du comptoir de la spécialité : _Depot, _Noir ou _Comptoir. */
    private static String suffix(String spec) {
        return INTERIOR.equals(spec) ? "_Depot" : BLACK.equals(spec) ? "_Noir" : "_Comptoir";
    }

    private static void guestNpc(World world, Store<EntityStore> store, Village v, Place p, Guest g, boolean near) {
        Ref<EntityStore> npc = GUEST_NPC.get(v.id());
        if (npc != null && !npc.isValid()) {
            GUEST_NPC.remove(v.id());
            VILLAGE_OF.remove(npc);
            npc = null;
        }
        if (g == null || !near) {
            if (npc != null) {
                GUEST_NPC.remove(v.id());
                VILLAGE_OF.remove(npc);
                WorldLife.remove(store, npc);
            }
            return;
        }
        String stall = STALL_ID + v.id();
        if (WorldLife.registry(world).get(WorldLifeRegistry.Part.STRUCTURES, stall) == null) {
            int[] at = p.at(GUEST_STALL);
            if (Sites.loaded(world, at[0], at[1])) {
                Sites.pasteTemporary(world, store, stall, "Nakasar/Peuples/Markets/Etal_" + g.people().id(), at[0], p.y(), at[1], p.rot());
            }
        }
        if (npc == null && WorldLife.budget(world, 1)) {
            npc = WorldLife.spawn(store, Travellers.peddlerRole(g.people()), p.stand(GUEST_STALL), p.yaw());
            if (npc != null) {
                GUEST_NPC.put(v.id(), npc);
                VILLAGE_OF.put(npc, v.id());
                WorldLife.talker(npc, (w, s, player, playerRef, ref) -> open(w, s, player, playerRef, ref, "Monde_Marchand_Etal"));
            }
        }
    }

    private static void clerkNpc(World world, Store<EntityStore> store, Village v, Place p, String spec, boolean near) {
        Ref<EntityStore> npc = CLERK_NPC.get(v.id());
        if (npc != null && !npc.isValid()) {
            CLERK_NPC.remove(v.id());
            VILLAGE_OF.remove(npc);
            npc = null;
        }
        // Le receleur du marché noir ne vient qu'à la nuit tombée.
        boolean absent = BLACK.equals(spec) && !GameClock.isNight(store);
        if (spec == null || !near || absent) {
            if (npc != null) {
                CLERK_NPC.remove(v.id());
                VILLAGE_OF.remove(npc);
                WorldLife.remove(store, npc);
            }
            return;
        }
        if (npc == null && WorldLife.budget(world, 1)) {
            String dialogue = v.people().id() + suffix(spec);
            String role = BLACK.equals(spec) ? Travellers.travellerRole(v.people()) : v.people().merchantRole();
            npc = WorldLife.spawn(store, role, p.stand(COUNTER), p.yaw());
            if (npc != null) {
                CLERK_NPC.put(v.id(), npc);
                VILLAGE_OF.put(npc, v.id());
                WorldLife.talker(npc, (w, s, player, playerRef, ref) -> open(w, s, player, playerRef, ref, dialogue));
            }
        }
    }

    private static void dropNpcs(Store<EntityStore> store, String villageId) {
        for (Map<String, Ref<EntityStore>> map : List.of(GUEST_NPC, CLERK_NPC)) {
            Ref<EntityStore> npc = map.remove(villageId);
            if (npc != null) {
                VILLAGE_OF.remove(npc);
                WorldLife.remove(store, npc);
            }
        }
    }

    private static void open(World world, Store<EntityStore> store, Ref<EntityStore> player, PlayerRef playerRef, Ref<EntityStore> npc, String id) {
        Dialogue d = Dialogue.get(id);
        if (d != null) {
            Dialogues.openPage(world, store, player, playerRef, d, npc);
        }
    }

    /** Village du marché dont `npc` est le marchand de passage ou le commis, ou null. */
    public static Village villageOf(World world, Ref<EntityStore> npc) {
        String id = npc == null ? null : VILLAGE_OF.get(npc);
        return id == null ? null : Villages.registry(world).byId(id);
    }

    // ------------------------------------------------------------------------------------------------ actions

    /** Troc du marchand de passage : la boutique des voyageurs de son peuple, rang Toléré dans son village. */
    public static boolean guestShop(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Village host) {
        Guest g = host == null ? null : guest(world, host);
        Village from = g == null ? null : Villages.registry(world).byId(g.from());
        if (from == null) {
            DialogueSpeech.say(player, Message.translation(KEYS + "guest.gone"));
            return false;
        }
        if (!Reputations.rank(store, ref, from).atLeast(Rank.TOLERE)) {
            DialogueSpeech.say(player, Message.translation(KEYS + "guest.rank").param("village", from.name()));
            return false;
        }
        com.hypixel.hytale.server.core.entity.entities.Player p = store.getComponent(ref,
                com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
        if (p == null) {
            return false;
        }
        p.getPageManager().openCustomPage(ref, store, dev.nakasar.hytale.peuples.reputation.ShopPage.guest(player, host));
        return true;
    }

    /** Peuples dont l'étal du comptoir est ouvert (visite de moins de {@value #VISIT_DAYS} jours), le plus récent d'abord. */
    public static List<People> counterOrigins(World world, Village host, long day) {
        List<People> out = new ArrayList<>();
        for (People p : People.values()) {
            if (p != host.people()) {
                out.add(p);
            }
        }
        out.sort((a, b) -> Long.compare(lastVisit(world, host, b), lastVisit(world, host, a)));
        return out;
    }

    /** Pourquoi l'étal du comptoir pour ce peuple est fermé, ou null s'il est ouvert. */
    public static Message counterLock(World world, Village host, People origin, long day) {
        long last = lastVisit(world, host, origin);
        if (last == Long.MIN_VALUE) {
            return Message.translation(KEYS + "counter.never").param("people", Message.translation(origin.nameKey()));
        }
        if (day - last >= VISIT_DAYS) {
            return Message.translation(KEYS + "counter.old").param("people", Message.translation(origin.nameKey())).param("days", day - last);
        }
        return null;
    }

    /** L'échoppe du comptoir : l'étal de la dernière origine passée. */
    public static boolean counterShop(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Village host) {
        if (host == null || !exterior(host)) {
            DialogueSpeech.say(player, Message.translation(KEYS + "order.notHere"));
            return false;
        }
        long day = GameClock.day(store);
        for (People origin : counterOrigins(world, host, day)) {
            if (counterLock(world, host, origin, day) == null) {
                return dev.nakasar.hytale.peuples.reputation.Shops.open(store, ref, player, host, counterShop(host, origin));
            }
        }
        DialogueSpeech.say(player, Message.translation(KEYS + "counter.none"));
        return false;
    }

    /** Ouvre la page des commandes (ou du dépôt-vente) du marché. */
    public static boolean openPicker(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Village host, boolean consign) {
        if (host == null || specialty(host) == null || (consign && !interior(host))) {
            DialogueSpeech.say(player, Message.translation(KEYS + "order.notHere"));
            return false;
        }
        com.hypixel.hytale.server.core.entity.entities.Player p = store.getComponent(ref,
                com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
        if (p == null) {
            return false;
        }
        p.getPageManager().openCustomPage(ref, store, new MarketPickerPage(player, host, consign));
        return true;
    }

    /** Passe une commande ; rend le message à dire, ou null si elle est passée. */
    static Message placeOrder(World world, Store<EntityStore> store, UUID player, Village host, Offer offer) {
        if (order(world, host, player) != null) {
            return Message.translation(KEYS + "order.already");
        }
        if (orders(world, host).size() >= MAX_ORDERS) {
            return Message.translation(KEYS + "order.full").param("max", MAX_ORDERS);
        }
        long day = GameClock.day(store);
        Guest g = guest(world, host);
        boolean ready = (g != null && g.people() == offer.origin() && carries(store, g.people(), offer.item())) || TradeAccess.has(world, host, offer.item(), day);
        save(world, new Order(host.id(), player.toString(), offer.item(), offer.count(), offer.total(), offer.origin(), ready, day));
        return null;
    }

    static void cancelOrder(World world, Village host, UUID player) {
        part(world).remove(ORDER + host.id() + "|" + player);
    }

    /** Laisse des marchandises au dépôt-vente ; rend le message à dire. */
    static Message consign(World world, Store<EntityStore> store, Ref<EntityStore> ref, UUID player, Village host, String item) {
        if (consignment(world, host, player) != null) {
            return Message.translation(KEYS + "consign.already");
        }
        if (!consignable(store, host).contains(item)) {
            return Message.translation(KEYS + "consign.none").param("village", host.name());
        }
        int taken = WorldLife.take(store, ref, item::equals, consignMax(item));
        if (taken <= 0) {
            return Message.translation(KEYS + "consign.none").param("village", host.name());
        }
        int price = consignPrice(item, taken);
        part(world).put(CONSIGN + host.id() + "|" + player, item + "|" + taken + "|" + price);
        return Message.translation(KEYS + "consign.placed").param("count", taken).param("item", VillageBoard.itemName(item)).param("price", price);
    }

    /** Retire le produit des ventes et la commande prête (payée en faveurs du village). */
    public static boolean collect(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Village host) {
        if (host == null) {
            DialogueSpeech.say(player, Message.translation(KEYS + "order.notHere"));
            return false;
        }
        UUID uuid = player.getUuid();
        List<Message> lines = new ArrayList<>();
        int gain = proceeds(world, host, uuid);
        String favour = host.people().favorItem();
        if (gain > 0) {
            part(world).remove(PROCEEDS + host.id() + "|" + uuid);
            Requests.give(store, ref, new ItemStack(favour, gain));
            lines.add(Message.translation(KEYS + "consign.proceeds").param("price", gain));
        }
        Consignment c = consignment(world, host, uuid);
        if (c != null && !interior(host)) {
            // Plus de dépôt-vente (reconversion, place brûlée) : le commis rend les marchandises.
            part(world).remove(CONSIGN + host.id() + "|" + uuid);
            Requests.give(store, ref, new ItemStack(c.item(), c.count()));
            lines.add(Message.translation(KEYS + "consign.returned").param("count", c.count()).param("item", VillageBoard.itemName(c.item())));
            c = null;
        }
        if (c != null) {
            lines.add(Message.translation(KEYS + "consign.waiting").param("count", c.count()).param("item", VillageBoard.itemName(c.item())));
        }
        Order o = order(world, host, uuid);
        if (o != null && !o.ready()) {
            lines.add(Message.translation(KEYS + "order.notReady"));
        } else if (o != null) {
            if (WorldLife.count(store, ref, favour::equals) < o.price()) {
                lines.add(Message.translation(KEYS + "order.noFavors").param("price", o.price())
                        .param("people", Message.translation(host.people().nameKey())));
            } else {
                WorldLife.take(store, ref, favour::equals, o.price());
                Requests.give(store, ref, new ItemStack(o.item(), o.count()));
                cancelOrder(world, host, uuid);
                lines.add(Message.translation(KEYS + "order.collected").param("count", o.count())
                        .param("item", VillageBoard.itemName(o.item())).param("price", o.price()));
            }
        }
        if (lines.isEmpty()) {
            lines.add(Message.translation(KEYS + "collect.nothing"));
        }
        Message all = Message.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                all.insert(" ");
            }
            all.insert(lines.get(i));
        }
        DialogueSpeech.say(player, all);
        return true;
    }

    /** Monde déchargé, plugin arrêté. */
    public static void clearAll() {
        GUEST_NPC.clear();
        CLERK_NPC.clear();
        VILLAGE_OF.clear();
        PLACES.clear();
    }
}
