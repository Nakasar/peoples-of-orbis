package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.builtin.adventure.shop.barter.BarterItemStack;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterShopAsset;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterTrade;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.PeuplesPlugin;
import dev.nakasar.hytale.peuples.klops.Klops;
import dev.nakasar.hytale.peuples.village.Chantiers;
import dev.nakasar.hytale.peuples.village.PlotState;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageBoard;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.village.WorkshopState;
import dev.nakasar.hytale.peuples.workshop.Livestock;
import dev.nakasar.hytale.peuples.workshop.Projects;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.Workshops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les échanges commerciaux nés des besoins (Kevin, 21 novembre 2026) : « un village renoncé peut indiquer qu'il a besoin
 * de miel. Cette information transite par les voyageurs et marchands itinérants. Un village qui peut produire du miel
 * obtient l'information quand un PNJ qui la possède passe chez lui. Il va donc fabriquer le bâtiment qui peut produire du
 * miel et l'exporter vers ce village. »
 *
 * <p><b>Besoins.</b> Chaque matin, un village fait la liste de ce qui lui manque (au plus {@value #MAX_NEEDS}) : les
 * ressources du jour de ses ateliers qu'il ne produit pas et n'importe pas ({@link Reason#INPUT}), ce qui bloque un
 * bâtiment qu'il a promis de bâtir pour un autre ({@link Reason#CHAIN}, des chaînes naissent ainsi), et un luxe quand il
 * lui en manque pour le chantier du palier suivant ({@link Reason#LUXURY}).
 *
 * <p><b>Nouvelles.</b> Personne ne connaît les besoins des autres d'office. Un voyageur, une caravane de commerce, un
 * marchand de lieu-dit ou le Klops errant part avec les besoins de son village de départ et ce que ce village a appris
 * (au plus {@value #BAG} nouvelles) ; à l'arrivée, le village (ou le lieu-dit) les apprend. Une nouvelle passe de main en
 * main {@value #MAX_HOPS} fois au plus et s'oublie au bout de {@value #FORGET_DAYS} jours. Les lieux-dits sont des
 * relais : leurs marchands rapportent chez eux ce que d'autres villages y ont dit. Le Klops porte les nouvelles d'un
 * arrêt à l'autre, sans limite de distance.
 *
 * <p><b>Engagements.</b> Le village qui apprend un besoin qu'il peut couvrir (et dont le demandeur est un partenaire
 * possible : liaison, relation au-dessus de Rival, ou lieu-dit à portée) s'engage, le premier qui l'apprend : un besoin
 * n'a qu'un fournisseur, un village {@value #MAX_PLEDGES} engagements au plus.
 * <ul>
 *   <li>Il le produit déjà (catalogue des routes, ou boutique d'un de ses bâtiments ouverts) : il exporte.</li>
 *   <li>Sinon, un atelier de niveau II sans spécialité dont une spécialité le produit la prend ; ou le conseil réserve
 *   un terrain libre ouvert pour un projet dont la boutique de base le produit, et lance le chantier dès que les
 *   matériaux sont au dépôt et que les ressources demandées par le projet sont là ({@link #AUTO_START}). Un joueur Ami
 *   peut encore changer ce projet tant que les travaux n'ont pas commencé : l'engagement tombe alors.</li>
 * </ul>
 * L'export passe par une route commerciale : celle qui relie déjà les deux villages, ou une route autonome que le
 * fournisseur ouvre (places libres). La case d'une commande ne compte pas dans l'équilibre de la route : le demandeur
 * la reçoit même s'il n'a rien d'aussi lourd à rendre ; chaque livraison d'une commande ajoute {@value #ORDER_RELATION}
 * à la relation. Un engagement tombe quand le fournisseur ne produit plus (au bout de {@value #IDLE_DAYS} jours), quand
 * la route ne peut pas s'ouvrir ({@value #WAIT_DAYS} jours), quand le chantier n'a pas pu commencer
 * ({@value #BUILD_DAYS} jours) ou quand la route est fermée.
 *
 * <p>État sauvé ({@link WorldLifeRegistry.Part#TRADE_NEEDS}) :
 * <ul>
 *   <li>{@code N:<village>|<objet> = raison|depuis} : besoins du jour ;</li>
 *   <li>{@code K:<lieu>|<village>|<objet> = appris le|mains} : ce qu'un village ou un lieu-dit sait ;</li>
 *   <li>{@code B:<porteur> = village/objet/mains;...} : ce que porte un voyageur ({@code T:}, {@code TC:}, {@code HT:},
 *   {@code KLOPS}) ;</li>
 *   <li>{@code P:<fournisseur>|<demandeur>|<objet> = état|depuis|bâtiment|dernière livraison|porteur} : engagements.</li>
 * </ul>
 */
public final class TradeNeeds {

    public static final String KEYS = WorldLife.KEYS + "needs.";
    public static final int MAX_NEEDS = 3;
    public static final int BAG = 8;
    public static final int MAX_HOPS = 4;
    public static final int FORGET_DAYS = 20;
    public static final int MAX_KNOWN = 16;
    public static final int MAX_PLEDGES = 2;
    public static final int WAIT_DAYS = 14;
    public static final int BUILD_DAYS = 21;
    public static final int IDLE_DAYS = 7;
    public static final int ORDER_RELATION = 2;
    /** Le conseil lance seul le chantier du terrain réservé (choix de Kevin, 21 novembre 2026). */
    public static final boolean AUTO_START = true;
    public static final String KLOPS = "KLOPS";

    private static final String NEED = "N:";
    private static final String KNOWN = "K:";
    private static final String CARRIED = "B:";
    private static final String PLEDGE = "P:";

    /** Pourquoi un village manque d'une ressource. */
    public enum Reason {
        /** Ressource du jour d'un atelier. */
        INPUT,
        /** Ressource demandée par un bâtiment promis à un autre village. */
        CHAIN,
        /** Luxe pour le chantier du palier suivant. */
        LUXURY,
        /** Commande d'un joueur au comptoir ou au dépôt-vente de la place du marché ({@link Markets}). */
        ORDER;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Un besoin du jour. */
    public record Need(String village, String item, Reason reason, long since) {
    }

    /** Une nouvelle : à `village`, on manque d'`item` ; apprise le jour `day`, passée par `hops` mains. */
    public record News(String village, String item, long day, int hops) {
    }

    /** État d'un engagement. */
    public enum State {
        /** Le bâtiment qui produira est réservé ou en chantier. */
        BUILD,
        /** Le fournisseur produit : la route et sa case se mettent en place. */
        OPEN,
        /** La case de la commande est sur la route. */
        ACTIVE
    }

    /** Un engagement : `supplier` envoie `item` à `buyer`. */
    public static final class Pledge {
        final String supplier;
        final String buyer;
        final String item;
        State state = State.OPEN;
        long since;
        String building = "-";
        long delivered = Long.MIN_VALUE;
        String carrier = "-";

        Pledge(String supplier, String buyer, String item) {
            this.supplier = supplier;
            this.buyer = buyer;
            this.item = item;
        }

        String key() {
            return PLEDGE + supplier + "|" + buyer + "|" + item;
        }

        static Pledge parse(String key, String v) {
            String[] k = key.substring(PLEDGE.length()).split("\\|", -1);
            String[] p = v.split("\\|", -1);
            if (k.length < 3 || p.length < 5) {
                return null;
            }
            try {
                Pledge out = new Pledge(k[0], k[1], k[2]);
                out.state = State.valueOf(p[0]);
                out.since = Long.parseLong(p[1]);
                out.building = p[2];
                out.delivered = Long.parseLong(p[3]);
                out.carrier = p[4];
                return out;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        String format() {
            return state.name() + "|" + since + "|" + building + "|" + delivered + "|" + carrier;
        }

        public String supplier() {
            return supplier;
        }

        public String buyer() {
            return buyer;
        }

        public String item() {
            return item;
        }

        public State state() {
            return state;
        }

        public String building() {
            return building;
        }
    }

    private TradeNeeds() {
    }

    private static Map<String, String> part(World world) {
        return WorldLife.registry(world).part(WorldLifeRegistry.Part.TRADE_NEEDS);
    }

    private static void put(World world, String key, String value) {
        WorldLife.registry(world).put(WorldLifeRegistry.Part.TRADE_NEEDS, key, value);
    }

    // ------------------------------------------------------------------------------------------------ besoins

    /** Les besoins du jour du village (ordre de priorité). */
    public static List<Need> needs(World world, Village v) {
        List<Need> out = new ArrayList<>();
        String prefix = NEED + v.id() + "|";
        for (Map.Entry<String, String> e : part(world).entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String[] p = e.getValue().split("\\|", -1);
            try {
                out.add(new Need(v.id(), e.getKey().substring(prefix.length()), Reason.valueOf(p[0]), Long.parseLong(p[1])));
            } catch (RuntimeException ignored) {
                // entrée abîmée : refaite au matin
            }
        }
        out.sort((a, b) -> a.reason().compareTo(b.reason()));
        return out;
    }

    /** Le village manque-t-il de cet objet ce jour ? */
    public static boolean needed(World world, Village v, String item) {
        return v != null && part(world).containsKey(NEED + v.id() + "|" + item);
    }

    /** Recalcule les besoins du jour du village (les dates « depuis » restent). */
    static void refreshNeeds(World world, Village v, long day) {
        Map<String, Reason> found = new LinkedHashMap<>();
        if (!v.isHamlet()) {
            @SuppressWarnings("unchecked")
            Map<String, TradeCatalogue.Source>[] catalogue = new Map[1];
            for (Workshop w : Workshops.opened(v)) {
                for (VillageLayout.Material m : w.inputs()) {
                    if (found.size() < MAX_NEEDS && TradeGoods.allowed(v.people(), m.itemId())
                            && !TradeAccess.available(world, v, m.itemId(), day, catalogue)) {
                        found.putIfAbsent(m.itemId(), Reason.INPUT);
                    }
                }
            }
            for (Pledge p : pledgesOf(world, v)) {
                if (p.state != State.BUILD) {
                    continue;
                }
                Workshop w = Plots.workshop(v, p.building);
                for (String item : w == null ? new String[0] : w.requires()) {
                    if (found.size() < MAX_NEEDS && TradeGoods.allowed(v.people(), item)
                            && !TradeAccess.available(world, v, item, day, catalogue)) {
                        found.putIfAbsent(item, Reason.CHAIN);
                    }
                }
            }
            for (String item : Markets.wanted(world, v)) {
                if (found.size() < MAX_NEEDS) {
                    found.putIfAbsent(item, Reason.ORDER);
                }
            }
            if (found.size() < MAX_NEEDS && v.tier() < 3 && TradeAccess.missingFor(world, v, v.tier() + 1, day) > 0) {
                String lux = wantedLuxury(world, v, day);
                if (lux != null) {
                    found.putIfAbsent(lux, Reason.LUXURY);
                }
            }
        }
        String prefix = NEED + v.id() + "|";
        Map<String, Long> since = new LinkedHashMap<>();
        for (Need n : needs(world, v)) {
            since.put(n.item(), n.since());
        }
        part(world).keySet().removeIf(k -> k.startsWith(prefix));
        for (Map.Entry<String, Reason> e : found.entrySet()) {
            put(world, prefix + e.getKey(), e.getValue().name() + "|" + since.getOrDefault(e.getKey(), day));
        }
    }

    /**
     * Le luxe que le village cherche pour grandir : un luxe qui a un terroir (un peuple qui le produit), ni en accès ni du
     * sien ; le choix tourne d'un village à l'autre pour que les demandes varient.
     */
    private static String wantedLuxury(World world, Village v, long day) {
        List<TradeGoods.Luxury> pool = new ArrayList<>();
        for (TradeGoods.Luxury l : TradeGoods.LUXURIES) {
            if (l.terroir() != null && l.terroir() != v.people() && TradeGoods.allowed(v.people(), l.item())
                    && TradeAccess.wanted(world, v, l.item(), day)) {
                pool.add(l);
            }
        }
        return pool.isEmpty() ? null : pool.get(Math.floorMod(v.id().hashCode(), pool.size())).item();
    }

    // ------------------------------------------------------------------------------------------------ nouvelles

    /** Ce que sait un village ou un lieu-dit (sans ses propres besoins). */
    public static List<News> known(World world, Village holder) {
        List<News> out = new ArrayList<>();
        String prefix = KNOWN + holder.id() + "|";
        for (Map.Entry<String, String> e : part(world).entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String[] k = e.getKey().substring(prefix.length()).split("\\|", -1);
            String[] p = e.getValue().split("\\|", -1);
            if (k.length < 2 || p.length < 2) {
                continue;
            }
            try {
                out.add(new News(k[0], k[1], Long.parseLong(p[0]), Integer.parseInt(p[1])));
            } catch (NumberFormatException ignored) {
                // entrée abîmée
            }
        }
        out.sort((a, b) -> Long.compare(b.day(), a.day()));
        return out;
    }

    /** Un porteur part de `from` : il emporte ses besoins, puis ce que le lieu sait de plus frais. */
    public static void pack(World world, String carrier, Village from, long day) {
        if (from == null || carrier == null) {
            return;
        }
        List<News> bag = new ArrayList<>();
        for (Need n : needs(world, from)) {
            bag.add(new News(from.id(), n.item(), day, 0));
        }
        for (News n : known(world, from)) {
            if (bag.size() >= BAG) {
                break;
            }
            if (n.hops() < MAX_HOPS && day - n.day() <= FORGET_DAYS) {
                bag.add(n);
            }
        }
        StringBuilder b = new StringBuilder();
        for (News n : bag.subList(0, Math.min(BAG, bag.size()))) {
            b.append(b.length() == 0 ? "" : ";").append(n.village()).append('/').append(n.item()).append('/').append(n.hops());
        }
        put(world, CARRIED + carrier, b.length() == 0 ? null : b.toString());
    }

    /** Ce que porte un voyageur. */
    public static List<News> carried(World world, String carrier, long day) {
        List<News> out = new ArrayList<>();
        String s = carrier == null ? null : part(world).get(CARRIED + carrier);
        if (s == null) {
            return out;
        }
        for (String one : s.split(";")) {
            String[] p = one.split("/", -1);
            if (p.length == 3) {
                try {
                    out.add(new News(p[0], p[1], day, Integer.parseInt(p[2])));
                } catch (NumberFormatException ignored) {
                    // entrée abîmée
                }
            }
        }
        return out;
    }

    /** Le porteur arrive à `to` : le lieu apprend ce qu'il porte ; le porteur garde son sac (il peut repartir). */
    public static void unpack(World world, Store<EntityStore> store, String carrier, Village to, long day, String kind) {
        if (to == null) {
            return;
        }
        for (News n : carried(world, carrier, day)) {
            learn(world, store, to, new News(n.village(), n.item(), day, n.hops() + 1), day, kind);
        }
    }

    /** Le porteur n'est plus : son sac est vidé. */
    public static void discard(World world, String carrier) {
        if (carrier != null) {
            put(world, CARRIED + carrier, null);
        }
    }

    /** Le lieu apprend une nouvelle ; s'il ne la savait pas, il voit s'il peut y répondre. */
    static void learn(World world, Store<EntityStore> store, Village holder, News n, long day, String kind) {
        if (holder.id().equals(n.village()) || n.hops() > MAX_HOPS + 1) {
            return;
        }
        String key = KNOWN + holder.id() + "|" + n.village() + "|" + n.item();
        String was = part(world).get(key);
        int hops = n.hops();
        if (was != null) {
            String[] p = was.split("\\|", -1);
            try {
                hops = Math.min(hops, Integer.parseInt(p[1]));
            } catch (RuntimeException ignored) {
                // entrée abîmée : remplacée
            }
        }
        put(world, key, day + "|" + hops);
        trim(world, holder, key);
        // Réentendue un autre jour : le lieu y repense (une place d'engagement a pu se libérer, un atelier ouvrir).
        if (was == null || !was.startsWith(day + "|")) {
            respond(world, store, holder, n, day, kind);
        }
    }

    /** Un lieu ne garde que ses {@value #MAX_KNOWN} nouvelles les plus fraîches. */
    private static void trim(World world, Village holder, String keep) {
        List<News> all = known(world, holder);
        int kept = 0;
        for (News n : all) {
            String key = KNOWN + holder.id() + "|" + n.village() + "|" + n.item();
            if (key.equals(keep)) {
                continue; // la nouvelle qu'on vient d'apprendre reste toujours
            }
            if (++kept >= MAX_KNOWN) {
                put(world, key, null);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ engagements

    public static List<Pledge> pledges(World world) {
        List<Pledge> out = new ArrayList<>();
        for (Map.Entry<String, String> e : part(world).entrySet()) {
            if (e.getKey().startsWith(PLEDGE)) {
                Pledge p = Pledge.parse(e.getKey(), e.getValue());
                if (p != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Engagements pris par le village (il fournit). */
    public static List<Pledge> pledgesOf(World world, Village supplier) {
        List<Pledge> out = new ArrayList<>();
        for (Pledge p : pledges(world)) {
            if (p.supplier.equals(supplier.id())) {
                out.add(p);
            }
        }
        return out;
    }

    /** Engagements pris envers le village (il reçoit). */
    public static List<Pledge> pledgesFor(World world, Village buyer) {
        List<Pledge> out = new ArrayList<>();
        for (Pledge p : pledges(world)) {
            if (p.buyer.equals(buyer.id())) {
                out.add(p);
            }
        }
        return out;
    }

    private static Pledge pledgeFor(World world, String buyer, String item) {
        for (Pledge p : pledges(world)) {
            if (p.buyer.equals(buyer) && p.item.equals(item)) {
                return p;
            }
        }
        return null;
    }

    private static void save(World world, Pledge p) {
        put(world, p.key(), p.format());
    }

    private static void remove(World world, Pledge p) {
        put(world, p.key(), null);
    }

    /** Ce que `sender` envoie à `receiver` par commande (ces cases passent hors de l'équilibre de la route). */
    public static Set<String> ordered(World world, String sender, String receiver) {
        Set<String> out = new LinkedHashSet<>();
        for (Pledge p : pledges(world)) {
            if (p.state != State.BUILD && p.supplier.equals(sender) && p.buyer.equals(receiver)) {
                out.add(p.item);
            }
        }
        return out;
    }

    /** Objets que le village produit pour ses commandes grâce à un bâtiment ouvert (catalogue des routes). */
    static Set<String> orderedProduce(World world, Village v, String route) {
        Set<String> out = new LinkedHashSet<>();
        for (Pledge p : pledges(world)) {
            // Pour une route donnée : seulement les commandes de son acheteur (pas sur les autres routes du fournisseur).
            if (p.state != State.BUILD && p.supplier.equals(v.id()) && (route == null || route.equals(Links.key(p.supplier, p.buyer)))
                    && workshopMakes(v, p.item)) {
                out.add(p.item);
            }
        }
        return out;
    }

    /** Le village (ou le lieu-dit) peut-il fournir cet objet : catalogue des routes, ou boutique d'un bâtiment ouvert. */
    public static boolean canSupply(World world, Village v, String item, long day) {
        TradeCatalogue.Source s = TradeCatalogue.of(world, v, null, day).get(item);
        return (s != null && s != TradeCatalogue.Source.REEXPORT) || workshopMakes(v, item);
    }

    /** Un bâtiment ouvert du village vend-il cet objet (boutique de son niveau, base comprise) ? */
    static boolean workshopMakes(Village v, String item) {
        if (v.isHamlet()) {
            return false; // le catalogue d'un lieu-dit est déjà sa boutique
        }
        for (Workshop w : Workshops.opened(v)) {
            WorkshopState ws = v.workshopIfPresent(w.getId());
            int level = ws == null ? 1 : Math.max(1, ws.level());
            for (int l = 1; l <= level; l++) {
                if (shopMakes(ws == null ? w.baseShop() : Workshops.shop(w, ws, l), item)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean shopMakes(String shopId, String item) {
        BarterShopAsset shop = shopId == null ? null : BarterShopAsset.getAssetMap().getAsset(shopId);
        BarterTrade[] trades = shop == null ? null : shop.getTrades();
        if (trades == null) {
            return false;
        }
        for (BarterTrade t : trades) {
            BarterItemStack o = t == null ? null : t.getOutput();
            if (o != null && item.equals(o.getItemId())) {
                return true;
            }
        }
        return false;
    }

    /** Le demandeur est-il un partenaire possible du fournisseur (route déjà ouverte, ou partenaire d'une nouvelle) ? */
    static boolean reachable(World world, Village supplier, Village buyer) {
        if (TradeRoutes.between(world, supplier, buyer) != null) {
            return true;
        }
        List<Village> partners = supplier.isHamlet() ? TradeRoutes.candidates(world, buyer) : TradeRoutes.candidates(world, supplier);
        String other = supplier.isHamlet() ? supplier.id() : buyer.id();
        for (Village v : partners) {
            if (v.id().equals(other)) {
                return true;
            }
        }
        return false;
    }

    /** Le lieu vient d'apprendre un besoin : il s'engage s'il peut le couvrir, ou s'il peut bâtir de quoi le faire. */
    static void respond(World world, Store<EntityStore> store, Village holder, News n, long day, String kind) {
        Village buyer = Villages.registry(world).byId(n.village());
        if (buyer == null || buyer.isHamlet() || !needed(world, buyer, n.item()) || pledgeFor(world, buyer.id(), n.item()) != null
                || pledgesOf(world, holder).size() >= MAX_PLEDGES || !TradeGoods.allowed(buyer.people(), n.item())
                || !TradeGoods.allowed(holder.people(), n.item()) || !reachable(world, holder, buyer)) {
            return;
        }
        Message item = VillageBoard.itemName(n.item());
        Message from = Message.translation(KEYS + "carrier." + kind);
        Pledge p = new Pledge(holder.id(), buyer.id(), n.item());
        p.since = day;
        p.carrier = kind;
        if (canSupply(world, holder, n.item(), day)) {
            save(world, p);
            announce(world, holder, Message.translation(KEYS + "pledge.supply").param("carrier", from).param("buyer", buyer.name())
                    .param("item", item), TradeLog.Kind.ORDER, n.item());
            log("%s fournira %s à %s (nouvelle portée par %s)", holder.id(), n.item(), buyer.id(), kind);
            return;
        }
        if (holder.isHamlet()) {
            return;
        }
        // Un atelier de niveau II sans spécialité prend celle qui produit l'objet.
        for (Workshop w : Workshops.opened(holder)) {
            WorkshopState ws = holder.workshopIfPresent(w.getId());
            if (ws == null || ws.level() < Workshops.SPECIALTY_LEVEL || ws.specialty() != null || ws.upgrading()) {
                continue;
            }
            for (Workshop.Specialty s : w.specialties()) {
                if (s == null || !shopMakes(s.shop(ws.level()), n.item()) || Projects.specialtyMissing(world, holder, s) != null
                        || Workshops.twinWith(holder, w, s.id()) != null) {
                    continue;
                }
                ws.setSpecialty(s.id());
                Workshops.draw(store, holder, w, ws, true);
                Livestock.changed(store, holder, w);
                save(world, p);
                announce(world, holder, Message.translation(KEYS + "pledge.specialty").param("carrier", from).param("buyer", buyer.name())
                        .param("item", item).param("building", Message.translation(w.nameKey()))
                        .param("specialty", Message.translation(s.nameKey())), TradeLog.Kind.ORDER, n.item());
                log("%s spécialise %s en %s pour fournir %s à %s", holder.id(), w.getId(), s.id(), n.item(), buyer.id());
                return;
            }
        }
        // Le conseil réserve un terrain libre pour un projet dont la boutique de base produit l'objet.
        VillageLayout.Building chosen = null;
        VillageLayout.Plot where = null;
        for (VillageLayout.Plot plot : Plots.plots(holder)) {
            PlotState s = holder.plot(plot.id());
            if (!Plots.isOpen(holder, plot) || s.project() != null || s.working()) {
                continue;
            }
            for (VillageLayout.Building b : Plots.catalog(holder, plot.id())) {
                Workshop w = Plots.workshop(holder, b.id());
                if (w == null || !shopMakes(w.baseShop(), n.item())) {
                    continue;
                }
                String refusal = Plots.refusal(world, holder, plot.id(), b);
                // « requires » : le projet attend ses ressources, qui deviennent à leur tour un besoin (chaîne).
                if (refusal == null || "requires".equals(refusal)) {
                    if (chosen == null || refusal == null) {
                        chosen = b;
                        where = plot;
                    }
                    if (refusal == null) {
                        break;
                    }
                }
            }
            if (chosen != null && Plots.refusal(world, holder, where.id(), chosen) == null) {
                break;
            }
        }
        if (chosen == null) {
            return;
        }
        Plots.reserveByVillage(holder, where, chosen.id());
        p.state = State.BUILD;
        p.building = chosen.id();
        save(world, p);
        announce(world, holder, Message.translation(KEYS + "pledge.build").param("carrier", from).param("buyer", buyer.name())
                .param("item", item).param("building", Message.translation(Chantiers.buildingKey(chosen.id()))).param("plot", where.id()),
                TradeLog.Kind.ORDER, n.item());
        log("%s réserve le terrain %s pour %s afin de fournir %s à %s", holder.id(), where.id(), chosen.id(), n.item(), buyer.id());
    }

    /** Le matin : chantiers promis, routes et cases des commandes, engagements qui tombent. */
    static void pledgesMorning(World world, Store<EntityStore> store, long day) {
        for (Pledge p : pledges(world)) {
            Village supplier = Villages.registry(world).byId(p.supplier);
            Village buyer = Villages.registry(world).byId(p.buyer);
            if (supplier == null || buyer == null) {
                remove(world, p);
                continue;
            }
            Message item = VillageBoard.itemName(p.item);
            if (p.state == State.BUILD) {
                build(world, store, p, supplier, buyer, day);
                continue;
            }
            if (!canSupply(world, supplier, p.item, day)) {
                if (p.delivered == Long.MIN_VALUE ? day - p.since > IDLE_DAYS : day - p.delivered > IDLE_DAYS) {
                    end(world, p, supplier, buyer, "end.idle");
                }
                continue;
            }
            TradeRoutes.Route r = TradeRoutes.between(world, supplier, buyer);
            if (p.state == State.OPEN && !lacks(world, buyer, p.item, day) && (r == null || !r.exportsOf(supplier.id()).contains(p.item))) {
                release(world, p); // un autre l'a couvert avant que la caravane parte : rien à annoncer
                continue;
            }
            if (r == null && p.state == State.ACTIVE) {
                end(world, p, supplier, buyer, "end.route");
                continue;
            }
            if (r == null) {
                Message why = supplier.isHamlet() ? TradeRoutes.blocked(world, buyer, supplier, day)
                        : TradeRoutes.blocked(world, supplier, buyer, day);
                if (why == null && TradeRoutes.autoCount(world) >= TradeRoutes.AUTO_MAX) {
                    why = Message.raw("auto"); // plafond des routes autonomes du monde : on attend qu'une se libère
                }
                if (why != null) {
                    if (day - p.since > WAIT_DAYS) {
                        end(world, p, supplier, buyer, "end.noRoute");
                    }
                    continue;
                }
                r = supplier.isHamlet() ? TradeRoutes.create(world, buyer, supplier, true, day) : TradeRoutes.create(world, supplier, buyer, true, day);
            }
            List<String> out = r.exportsOf(supplier.id());
            if (!out.contains(p.item)) {
                if (out.size() < r.level()) {
                    out.add(p.item);
                } else if (r.auto()) {
                    Set<String> orders = ordered(world, supplier.id(), buyer.id());
                    for (int i = out.size() - 1; i >= 0; i--) {
                        if (!orders.contains(out.get(i))) {
                            out.set(i, p.item);
                            break;
                        }
                    }
                }
                TradeRoutes.save(world, r);
            }
            if (!out.contains(p.item) && day - p.since > WAIT_DAYS) {
                end(world, p, supplier, buyer, "end.noRoute"); // route pleine, tenue par un joueur
                continue;
            }
            if (out.contains(p.item) && p.state != State.ACTIVE) {
                p.state = State.ACTIVE;
                save(world, p);
                announce(world, supplier, Message.translation(KEYS + "active").param("buyer", buyer.name()).param("item", item),
                        TradeLog.Kind.ORDER, p.item);
            }
        }
    }

    /** Engagement « à bâtir » : le chantier se lance (matériaux au dépôt), puis le bâtiment ouvert, la commande s'ouvre. */
    private static void build(World world, Store<EntityStore> store, Pledge p, Village supplier, Village buyer, long day) {
        VillageLayout.Plot plot = null;
        for (VillageLayout.Plot x : Plots.plots(supplier)) {
            if (p.building.equals(supplier.plot(x.id()).project())) {
                plot = x;
            }
        }
        if (plot == null) {
            remove(world, p); // un joueur a choisi un autre projet : l'engagement tombe sans bruit
            log("%s : le projet %s n'est plus réservé, engagement envers %s retiré", supplier.id(), p.building, buyer.id());
            return;
        }
        PlotState s = supplier.plot(plot.id());
        if (supplier.isBuilt(p.building)) {
            p.state = State.OPEN;
            p.since = day;
            save(world, p);
            return;
        }
        if (s.working()) {
            return;
        }
        if (!lacks(world, buyer, p.item, day)) {
            // Le demandeur a trouvé ailleurs : le terrain est rendu tant que les travaux n'ont pas commencé.
            Plots.reserveByVillage(supplier, plot, null);
            end(world, p, supplier, buyer, "end.covered");
            return;
        }
        if (day - p.since > BUILD_DAYS) {
            Plots.reserveByVillage(supplier, plot, null);
            end(world, p, supplier, buyer, "end.build");
            return;
        }
        if (!AUTO_START) {
            return;
        }
        Workshop w = Plots.workshop(supplier, p.building);
        Map<String, Integer> missing = Plots.missing(world, supplier, plot);
        if (missing == null || !missing.isEmpty() || Projects.projectMissing(world, supplier, w) != null) {
            return; // dépôt pas chargé, matériaux ou ressources du projet qui manquent : on réessaie demain
        }
        Plots.startByVillage(world, store, supplier, plot);
    }

    private static void end(World world, Pledge p, Village supplier, Village buyer, String why) {
        release(world, p);
        announce(world, supplier, Message.translation(KEYS + why).param("buyer", buyer.name()).param("item", VillageBoard.itemName(p.item)),
                TradeLog.Kind.ORDER, p.item);
        log("%s → %s (%s) : engagement fini (%s)", supplier.id(), buyer.id(), p.item, why);
    }

    /** Une caravane arrive : les commandes livrées comptent (relation, première livraison annoncée chez le demandeur). */
    static void delivered(World world, Village from, Village to, Map<String, Integer> cargo, long day) {
        boolean any = false;
        for (Pledge p : pledges(world)) {
            if (!p.supplier.equals(from.id()) || !p.buyer.equals(to.id()) || !cargo.containsKey(p.item)) {
                continue;
            }
            if (p.delivered == Long.MIN_VALUE) {
                TradeRoutes.announce(world, to, Message.translation(KEYS + "firstDelivery").param("supplier", TradeRoutes.name(world, from))
                        .param("item", VillageBoard.itemName(p.item)), NotificationStyle.Success, TradeLog.Kind.COVER, p.item);
            }
            p.delivered = day;
            save(world, p);
            any = true;
        }
        if (any && !from.isHamlet() && !to.isHamlet()) {
            Relations.change(world, from, to, ORDER_RELATION);
        }
    }

    /** Une route est fermée ou rompue : ses commandes tombent. */
    static void routeDropped(World world, String a, String b) {
        for (Pledge p : pledges(world)) {
            boolean pair = (p.supplier.equals(a) && p.buyer.equals(b)) || (p.supplier.equals(b) && p.buyer.equals(a));
            if (!pair || p.state == State.BUILD) {
                continue;
            }
            // Route fermée (souvent par un joueur) : la commande s'arrête, le conseil ne rouvre pas la route de lui-même.
            Village s = Villages.registry(world).byId(p.supplier);
            Village b2 = Villages.registry(world).byId(p.buyer);
            if (s != null && b2 != null) {
                end(world, p, s, b2, "end.route");
            } else {
                remove(world, p);
            }
        }
    }

    /** Le demandeur manque-t-il encore de l'objet (ni produit, ni importé) ? Plus large que sa liste plafonnée. */
    static boolean lacks(World world, Village buyer, String item, long day) {
        return !TradeAccess.available(world, buyer, item, day);
    }

    /** Retire l'engagement ; sur une route tenue par un joueur, sa case de commande est rendue. */
    private static void release(World world, Pledge p) {
        remove(world, p);
        Village s = Villages.registry(world).byId(p.supplier);
        Village b = Villages.registry(world).byId(p.buyer);
        TradeRoutes.Route r = s == null || b == null ? null : TradeRoutes.between(world, s, b);
        if (r != null && !r.auto() && r.exportsOf(p.supplier).remove(p.item)) {
            TradeRoutes.save(world, r);
        }
    }

    // ------------------------------------------------------------------------------------------------ matin

    /** Une fois par jour (avant les routes) : besoins, nouvelles oubliées, tournée du Klops, engagements. */
    static void morning(World world, Store<EntityStore> store, long day, Random random) {
        List<Village> all = new ArrayList<>(Villages.registry(world).all());
        for (Village v : all) {
            refreshNeeds(world, v, day);
        }
        part(world).entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(KNOWN)) {
                return false;
            }
            try {
                return day - Long.parseLong(e.getValue().split("\\|", -1)[0]) > FORGET_DAYS;
            } catch (RuntimeException ex) {
                return true;
            }
        });
        // Le Klops arrive à son arrêt du jour avec les nouvelles de la veille, puis repart avec celles d'ici.
        Village stop = Klops.stop(world, day);
        if (stop != null) {
            unpack(world, store, KLOPS, stop, day, "klops");
            pack(world, KLOPS, stop, day);
        }
        pledgesMorning(world, store, day);
    }

    // ------------------------------------------------------------------------------------------------ paroles

    /** Ce qu'un porteur raconte de ce qu'il porte (une nouvelle au hasard), ou null. */
    public static Message rumourOf(World world, String carrier, long day) {
        List<News> bag = carried(world, carrier, day);
        Collections.shuffle(bag, ThreadLocalRandom.current());
        for (News n : bag) {
            Village v = Villages.registry(world).byId(n.village());
            if (v != null) {
                return Message.translation(KEYS + "rumour.carried").param("village", v.name()).param("item", VillageBoard.itemName(n.item()));
            }
        }
        return null;
    }

    /** Le porteur du PNJ de passage (voyageur, caravane, marchand de lieu-dit, Klops), ou null. */
    public static String carrierOf(World world, Ref<EntityStore> npc) {
        if (npc == null) {
            return null;
        }
        if (Klops.isWanderer(npc)) {
            return KLOPS;
        }
        Travellers.Traveller t = Travellers.of(world, npc);
        if (t != null) {
            return t.walkKey();
        }
        TradeCaravans.Caravan c = TradeCaravans.of(world, npc);
        if (c != null) {
            return c.walkKey();
        }
        HamletTrips.Trip trip = HamletTrips.of(world, npc);
        return trip == null ? null : trip.walkKey();
    }

    /** Rumeurs du commerce né des besoins : commandes en cours et besoins que portent les voyageurs (au plus 4). */
    static List<Message> rumours(World world, long day) {
        List<Message> out = new ArrayList<>();
        List<Pledge> all = pledges(world);
        Collections.shuffle(all, ThreadLocalRandom.current());
        for (Pledge p : all.subList(0, Math.min(4, all.size()))) {
            Village s = Villages.registry(world).byId(p.supplier);
            Village b = Villages.registry(world).byId(p.buyer);
            if (s == null || b == null) {
                continue;
            }
            Message m = p.state == State.BUILD
                    ? Message.translation(KEYS + "rumour.build").param("building", Message.translation(Chantiers.buildingKey(p.building)))
                    : Message.translation(KEYS + "rumour.supply");
            out.add(m.param("supplier", TradeRoutes.name(world, s)).param("buyer", b.name()).param("item", VillageBoard.itemName(p.item)));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : part(world).entrySet()) {
            if (!e.getKey().startsWith(CARRIED) || seen.size() >= 4) {
                continue;
            }
            for (News n : carried(world, e.getKey().substring(CARRIED.length()), day)) {
                Village v = Villages.registry(world).byId(n.village());
                if (v != null && seen.add(n.village() + "|" + n.item()) && seen.size() <= 4) {
                    out.add(Message.translation(KEYS + "rumour.need").param("village", v.name()).param("item", VillageBoard.itemName(n.item())));
                }
            }
        }
        return out;
    }

    /** Une ligne courte pour l'onglet Commerce : besoins du village et commandes, ou null s'il n'y a rien. */
    public static Message pageLine(World world, Village v) {
        List<Message> parts = new ArrayList<>();
        List<Need> needs = needs(world, v);
        if (!needs.isEmpty()) {
            parts.add(Message.translation(KEYS + "page.needs").param("list", items(needs.stream().map(Need::item).toList())));
        }
        for (Pledge p : pledgesFor(world, v)) {
            Village s = Villages.registry(world).byId(p.supplier);
            if (s != null && p.delivered != Long.MIN_VALUE) {
                parts.add(Message.translation(KEYS + "page.incoming").param("supplier", TradeRoutes.name(world, s))
                        .param("item", VillageBoard.itemName(p.item)));
            }
        }
        for (Pledge p : pledgesOf(world, v)) {
            Village b = Villages.registry(world).byId(p.buyer);
            if (b != null) {
                parts.add(Message.translation(KEYS + (p.state == State.BUILD ? "page.building" : "page.outgoing"))
                        .param("buyer", b.name()).param("item", VillageBoard.itemName(p.item)));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        Message out = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            out = Message.translation(KEYS + "page.join").param("a", out).param("b", parts.get(i));
        }
        return out;
    }

    private static Message items(List<String> items) {
        Message out = null;
        for (String item : items) {
            Message one = VillageBoard.itemName(item);
            out = out == null ? one : Message.translation(KEYS + "page.and").param("a", out).param("b", one);
        }
        return out == null ? Message.empty() : out;
    }

    /** Lignes de la commande /monde besoins pour le village. */
    static List<Message> describe(World world, Village v, long day) {
        List<Message> out = new ArrayList<>();
        for (Need n : needs(world, v)) {
            out.add(Message.translation(KEYS + "cmd.need").param("item", VillageBoard.itemName(n.item()))
                    .param("reason", Message.translation(KEYS + "reason." + n.reason().key())).param("days", day - n.since()));
        }
        for (News n : known(world, v)) {
            Village o = Villages.registry(world).byId(n.village());
            out.add(Message.translation(KEYS + "cmd.known").param("village", o == null ? n.village() : o.name())
                    .param("item", VillageBoard.itemName(n.item())).param("hops", n.hops()).param("days", day - n.day()));
        }
        for (Pledge p : pledges(world)) {
            if (!p.supplier.equals(v.id()) && !p.buyer.equals(v.id())) {
                continue;
            }
            Village s = Villages.registry(world).byId(p.supplier);
            Village b = Villages.registry(world).byId(p.buyer);
            out.add(Message.translation(KEYS + "cmd.pledge").param("supplier", s == null ? Message.raw(p.supplier) : TradeRoutes.name(world, s))
                    .param("buyer", b == null ? p.buyer : b.name()).param("item", VillageBoard.itemName(p.item))
                    .param("state", p.state.name()).param("building", p.building));
        }
        return out;
    }

    /** Admin : un village apprend tout de suite un besoin d'un autre (commande). */
    static void tell(World world, Store<EntityStore> store, Village holder, Village needy, String item, long day) {
        put(world, KNOWN + holder.id() + "|" + needy.id() + "|" + item, null);
        learn(world, store, holder, new News(needy.id(), item, day, 1), day, "klops");
    }

    private static void announce(World world, Village v, Message m) {
        announce(world, v, m, TradeLog.Kind.NEED, null);
    }

    private static void announce(World world, Village v, Message m, TradeLog.Kind kind, String item) {
        TradeRoutes.announce(world, v, m, NotificationStyle.Default, kind, item);
    }

    private static void log(String format, Object... args) {
        PeuplesPlugin.get().getLogger().atInfo().log("Peuples (besoins) : " + String.format(java.util.Locale.ROOT, format, args));
    }
}
