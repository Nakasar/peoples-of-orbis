package dev.nakasar.hytale.peuples.reputation;

import com.hypixel.hytale.builtin.adventure.shop.barter.BarterItemStack;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterShopAsset;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterShopState;
import com.hypixel.hytale.builtin.adventure.shop.barter.BarterTrade;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.nakasar.hytale.peuples.dialogue.Dialogue;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageBoard;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.workshop.Inputs;
import dev.nakasar.hytale.peuples.workshop.Projects;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.WorkshopPage;
import dev.nakasar.hytale.peuples.village.WorkshopState;
import dev.nakasar.hytale.peuples.workshop.Workshops;
import dev.nakasar.hytale.peuples.world.Markets;
import dev.nakasar.hytale.peuples.world.Travellers;
import dev.nakasar.hytale.peuples.world.WorldLife;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * L'échoppe du mod (refonte des écrans de commerce, Kevin, 21 novembre 2026), à la place de la page de troc vanilla :
 * le marché du village à gauche (l'échoppe du peuple, puis chaque bâtiment qui vend), les étals de l'étal choisi en
 * onglets (rangs Toléré / Ami / Hôte ou niveaux I / II / III), toujours visibles même fermés, avec la raison écrite
 * avant le clic ; une ligne par offre (objet, ce que le joueur en a, stock, prix, quantité, bouton qui dit pourquoi il
 * est grisé) ; le porte-monnaie et l'heure du nouveau stock. Le stock reste celui des boutiques vanilla
 * ({@link BarterShopState}, partagé par le monde) ; le troc prend et donne les objets comme la page vanilla.
 * Une boutique hors du marché d'un village (voyageur, auberge, lieu-dit, Klops) s'ouvre seule, sans colonne.
 * Mise en page par peuple ({@code ShopPage_<Peuple>.ui}, {@code tools/generate_shop_ui.py}).
 */
public final class ShopPage extends InteractiveCustomUIPage<ShopPage.Event> {

    static final String KEYS = "nakasar_peuples.shop.";
    private static final List<String> THEMED = List.of("Kweebec", "Trork", "Feran", "Renonce", "Slothian");
    /** tools/generate_shop_ui.py RAIL, TABS, ROWS. */
    private static final int RAIL = 8;
    private static final int TABS = 3;
    private static final int ROWS = 7;
    private static final String MERCHANT = "M";
    private static final String WORKSHOP = "W:";
    private static final String SINGLE = "S:";
    /** Place du marché (world/Markets) : marchand de passage, comptoir du marché extérieur, jour de marché. */
    private static final String GUEST = "G";
    private static final String COUNTER = "C";
    private static final String MARKET_DAY = "D";
    private static final String MARKET_KEYS = Markets.KEYS;
    private static final String RED = "#e07468";
    private static final String CREAM = "#f2ead2";

    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .build();

        String action;
    }

    /** Un étal d'une échoppe : sa boutique vanilla, son nom d'onglet, et pourquoi il est fermé (null : ouvert). */
    record Tab(String shopId, Message label, Message lock) {
    }

    /** Une échoppe du marché : l'échoppe du peuple ou un bâtiment. */
    record Stand(String key, String icon, Message name, Message sub, List<Tab> tabs) {
    }

    private final String villageId;
    private String stand;
    private int tab;
    /** Quantité choisie par ligne (nombre de trocs), remise à 1 quand l'étal change. */
    private final int[] counts = new int[ROWS];
    /** Index vanilla des offres affichées, par ligne. */
    private final int[] slots = new int[ROWS];
    private final List<String> rail = new ArrayList<>();
    /** Boutique à montrer à l'ouverture (onglet du comptoir), ou null. */
    private String wanted;

    private ShopPage(PlayerRef playerRef, Village village, String stand, int tab) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.villageId = village.id();
        this.stand = stand;
        this.tab = tab;
        java.util.Arrays.fill(counts, 1);
    }

    /** Page de la boutique `shopId` du village : son étal du marché et l'onglet de cette boutique. */
    public static ShopPage of(PlayerRef playerRef, Village village, String shopId) {
        for (Rank r : List.of(Rank.TOLERE, Rank.AMI, Rank.HOTE)) {
            if (shopId.equals(village.people().shop(r))) {
                return new ShopPage(playerRef, village, MERCHANT, r.ordinal() - Rank.TOLERE.ordinal());
            }
        }
        for (Workshop w : Workshops.opened(village)) {
            WorkshopState ws = village.workshop(w.getId());
            for (int level = 3; level >= 1; level--) {
                if (ws != null && shopId.equals(Workshops.shop(w, ws, level))) {
                    return new ShopPage(playerRef, village, WORKSHOP + w.getId(), level - 1);
                }
            }
        }
        if (shopId.equals(Markets.dayShop(village.people()))) {
            return new ShopPage(playerRef, village, MARKET_DAY, 0);
        }
        if (shopId.startsWith("Nakasar_Peuples_Comptoir_" + village.people().id() + "_")) {
            ShopPage page = new ShopPage(playerRef, village, COUNTER, 0);
            page.wanted = shopId;
            return page;
        }
        ShopPage page = new ShopPage(playerRef, village, SINGLE + shopId, 0);
        // Boutique des voyageurs : l'étal de passage du marché si son marchand y est posté (vu à l'affichage).
        page.wanted = shopId.startsWith("Nakasar_Peuples_Travel_") ? shopId : null;
        return page;
    }

    static String layout(People people) {
        return people != null && THEMED.contains(people.id()) ? "Pages/Nakasar/Peuples/ShopPage_" + people.id() + ".ui"
                : "Pages/Nakasar/Peuples/ShopPage.ui";
    }

    private static String roman(int level) {
        return level >= 3 ? "III" : level == 2 ? "II" : "I";
    }

    private static boolean exists(String shopId) {
        return shopId != null && BarterShopAsset.getAssetMap().getAsset(shopId) != null;
    }

    // ---- Étals du marché ----

    private static Message rankLock(Store<EntityStore> store, Ref<EntityStore> ref, Village v, Rank needed) {
        Reputations.Standing s = Reputations.standing(store, ref, v);
        if (s.rank().atLeast(needed)) {
            return null;
        }
        return Message.translation(KEYS + "why.rank").param("rank", Message.translation(needed.nameKey()))
                .param("missing", Math.max(1, needed.min() - s.value()));
    }

    private static Stand merchant(Store<EntityStore> store, Ref<EntityStore> ref, Village v) {
        People p = v.people();
        List<Tab> tabs = new ArrayList<>();
        for (Rank r : List.of(Rank.TOLERE, Rank.AMI, Rank.HOTE)) {
            String id = p.shop(r);
            if (exists(id)) {
                tabs.add(new Tab(id, Message.translation(r.nameKey()), rankLock(store, ref, v, r)));
            }
        }
        if (tabs.isEmpty()) {
            return null;
        }
        int open = (int) tabs.stream().filter(t -> t.lock() == null).count();
        Dialogue d = Dialogue.get(p.merchantDialogue());
        Message name = d != null && d.npcNameKey() != null ? Message.translation(d.npcNameKey())
                : Message.translation(KEYS + "merchant").param("people", Message.translation(p.nameKey()));
        return new Stand(MERCHANT, p.favorItem(), name,
                Message.translation(KEYS + "merchantSub").param("open", open).param("total", tabs.size()), tabs);
    }

    private static Stand workshop(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v, Workshop w) {
        WorkshopState ws = v.workshop(w.getId());
        if (ws == null || w.baseShop() == null) {
            return null;
        }
        Message keeper = Workshops.job(v, w) == null ? Message.translation(w.nameKey()) : WorkshopPage.keeperName(Workshops.job(v, w));
        Message closed = null;
        Message sub;
        if (!Workshops.keeperPresent(v, w)) {
            closed = Message.translation(KEYS + "why.absent").param("name", keeper).param("hour", GameClock.MORNING);
            sub = Message.translation(KEYS + "absent").param("hour", GameClock.MORNING);
        } else if (!Inputs.running(v, w)) {
            closed = Message.translation(KEYS + "why.stopped").param("list", Inputs.list(Inputs.needs(v, w)));
            sub = Message.translation(KEYS + "stopped");
        } else {
            sub = null;
        }
        Workshop.Specialty spec = w.specialty(ws.specialty());
        Message lacking = spec == null ? null : Projects.specialtyMissing(world, v, spec);
        List<Tab> tabs = new ArrayList<>();
        int offers = 0;
        for (int level = 1; level <= Workshops.MAX_LEVEL; level++) {
            Rank needed = level == 3 ? Rank.HOTE : level == 2 ? Rank.AMI : Rank.TOLERE;
            String id = Workshops.shop(w, ws, level);
            Message lock = closed;
            if (lock == null && level > ws.level()) {
                lock = Message.translation(KEYS + "why.level").param("level", roman(level));
            }
            if (lock == null) {
                lock = rankLock(store, ref, v, needed);
            }
            if (lock == null && level >= 2 && lacking != null) {
                lock = Message.translation(KEYS + "why.specialty").param("list", lacking);
            }
            if (lock == null && level >= 2 && spec == null && w.specialties().length > 0) {
                lock = Message.translation(KEYS + "why.noSpecialty");
            }
            if (!exists(id)) {
                continue;
            }
            if (lock == null) {
                BarterShopAsset a = BarterShopAsset.getAssetMap().getAsset(id);
                offers = BarterShopState.get().getResolvedTrades(a, BarterShopState.getShopGameTime(store)).length;
            }
            tabs.add(new Tab(id, Message.translation(KEYS + "level").param("level", roman(level)), lock));
        }
        if (tabs.isEmpty()) {
            return null;
        }
        if (sub == null) {
            sub = Message.translation(KEYS + "workshopSub").param("level", roman(Math.max(1, Math.min(ws.level(), 3)))).param("count", offers);
        }
        return new Stand(WORKSHOP + w.getId(), w.icon(), Message.translation(w.nameKey()), sub, tabs);
    }

    private static Stand single(String shopId) {
        BarterShopAsset a = BarterShopAsset.getAssetMap().getAsset(shopId);
        Message name = a == null || a.getDisplayNameKey() == null ? Message.raw(shopId) : Message.translation(a.getDisplayNameKey());
        return new Stand(SINGLE + shopId, null, name, Message.empty(), List.of(new Tab(shopId, name, null)));
    }

    /** Les étals de la place du marché : marchand de passage, comptoir du marché extérieur, jour de marché. */
    private static void marketPlace(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v, List<Stand> out) {
        Markets.Guest g = Markets.guest(world, v);
        Village from = g == null ? null : Villages.registry(world).byId(g.from());
        String guestShop = g == null ? null : Travellers.shop(g.people());
        if (from != null && exists(guestShop)) {
            out.add(new Stand(GUEST, g.people().favorItem(), Message.translation(MARKET_KEYS + "stand.guest"),
                    Message.translation(MARKET_KEYS + "stand.guestSub").param("people", Message.translation(g.people().nameKey()))
                            .param("village", from.name()),
                    List.of(new Tab(guestShop, Message.translation(g.people().nameKey()), rankLock(store, ref, from, Rank.TOLERE)))));
        }
        long day = GameClock.day(store);
        if (Markets.exterior(v)) {
            List<Tab> tabs = new ArrayList<>();
            int open = 0;
            for (People origin : Markets.counterOrigins(world, v, day)) {
                String id = Markets.counterShop(v, origin);
                if (tabs.size() >= TABS || !exists(id)) {
                    continue;
                }
                Message lock = Markets.counterLock(world, v, origin, day);
                if (lock == null) {
                    lock = rankLock(store, ref, v, Rank.TOLERE);
                }
                open += lock == null ? 1 : 0;
                tabs.add(new Tab(id, Message.translation(MARKET_KEYS + "counter.tab").param("people", Message.translation(origin.nameKey())), lock));
            }
            if (!tabs.isEmpty()) {
                out.add(new Stand(COUNTER, "Tool_Map", Message.translation(MARKET_KEYS + "stand.counter"),
                        Message.translation(MARKET_KEYS + "stand.counterSub").param("open", open).param("total", tabs.size()), tabs));
            }
        }
        String dayShop = Markets.dayShop(v.people());
        if (Markets.marketDay(v, day) && exists(dayShop)) {
            out.add(new Stand(MARKET_DAY, "Deco_Map", Message.translation(MARKET_KEYS + "stand.day"),
                    Message.translation(MARKET_KEYS + "stand.daySub"),
                    List.of(new Tab(dayShop, Message.translation(MARKET_KEYS + "stand.day"), rankLock(store, ref, v, Rank.TOLERE)))));
        }
    }

    /** Les étals du marché du village, l'échoppe du peuple d'abord. */
    private static List<Stand> market(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v) {
        List<Stand> out = new ArrayList<>();
        Stand m = merchant(store, ref, v);
        if (m != null) {
            out.add(m);
        }
        marketPlace(world, store, ref, v, out);
        for (Workshop w : Workshops.opened(v)) {
            if (w.isHamlet() || out.size() >= RAIL) {
                continue;
            }
            Stand s = workshop(world, store, ref, v, w);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private Stand current(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v, List<Stand> market) {
        if (stand.startsWith(SINGLE)) {
            return single(stand.substring(SINGLE.length()));
        }
        for (Stand s : market) {
            if (s.key().equals(stand)) {
                return s;
            }
        }
        return market.isEmpty() ? null : market.get(0);
    }

    // ---- Affichage ----

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Village v = Villages.registry(world).byId(villageId);
        cmd.append(layout(v == null ? null : v.people()));
        if (v == null) {
            return;
        }
        if (wanted != null && stand.startsWith(SINGLE)) {
            Markets.Guest g = Markets.guest(world, v);
            if (g != null && wanted.equals(Travellers.shop(g.people()))) {
                stand = GUEST;
            }
            wanted = null;
        }
        boolean inMarket = !stand.startsWith(SINGLE);
        List<Stand> market = inMarket ? market(world, store, ref, v) : List.of();
        Stand s = current(world, store, ref, v, market);
        if (s == null) {
            s = single(stand.substring(Math.min(stand.length(), SINGLE.length())));
        }
        stand = s.key();
        if (wanted != null) {
            for (int i = 0; i < s.tabs().size(); i++) {
                if (s.tabs().get(i).shopId().equals(wanted)) {
                    tab = i;
                }
            }
            wanted = null;
        }
        tab = Math.max(0, Math.min(tab, s.tabs().size() - 1));
        Tab t = s.tabs().get(tab);
        cmd.set("#PageTitle.TextSpans", inMarket ? Message.translation(KEYS + "market").param("village", v.name()) : s.name());
        header(cmd, store, ref, v, s, t);
        rail(cmd, events, market, inMarket);
        tabs(cmd, events, s);
        rows(cmd, events, store, ref, t);
    }

    private void header(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref, Village v, Stand s, Tab t) {
        cmd.set("#HeadIcon.Visible", s.icon() != null);
        if (s.icon() != null) {
            cmd.set("#HeadIcon.ItemId", s.icon());
        }
        cmd.set("#HeadName.TextSpans", s.name());
        cmd.set("#HeadSub.TextSpans", Message.empty().insert(Message.raw(v.name())).insert(" · ").insert(t.label()));
        Reputations.Standing st = Reputations.standing(store, ref, v);
        Rank next = st.rank().next();
        if (next == null) {
            cmd.set("#RankLine.TextSpans", Message.translation(KEYS + "rankMax").param("rank", Message.translation(st.rank().nameKey()))
                    .param("value", st.value()));
            cmd.set("#RankBar.Value", 1f);
        } else {
            cmd.set("#RankLine.TextSpans", Message.translation(KEYS + "rankLine").param("rank", Message.translation(st.rank().nameKey()))
                    .param("next", Message.translation(next.nameKey())).param("max", next.min()));
            int span = Math.max(1, next.min() - st.rank().min());
            cmd.set("#RankBar.Value", Math.max(0f, Math.min(1f, (st.value() - st.rank().min()) / (float) span)));
        }
        String currency = currency(store, t.shopId(), v);
        cmd.set("#WalletIcon.ItemId", currency);
        cmd.set("#WalletCount.TextSpans", Message.raw(Integer.toString(WorldLife.count(store, ref, currency::equals))));
        cmd.set("#WalletName.TextSpans", VillageBoard.itemName(currency));
    }

    /** Monnaie de la boutique : l'entrée de sa première offre, sinon la faveur du peuple. */
    private static String currency(Store<EntityStore> store, String shopId, Village v) {
        BarterShopAsset a = BarterShopAsset.getAssetMap().getAsset(shopId);
        if (a != null) {
            for (BarterTrade trade : BarterShopState.get().getResolvedTrades(a, BarterShopState.getShopGameTime(store))) {
                BarterItemStack[] in = trade.getInput();
                if (in != null && in.length > 0 && ItemModule.exists(in[0].getItemId())) {
                    return in[0].getItemId();
                }
            }
        }
        return v.people().favorItem();
    }

    private void rail(UICommandBuilder cmd, UIEventBuilder events, List<Stand> market, boolean shown) {
        rail.clear();
        cmd.set("#Rail.Visible", shown);
        if (!shown) {
            return;
        }
        cmd.set("#RailHeader.TextSpans", Message.translation(KEYS + "rail"));
        for (int i = 0; i < RAIL; i++) {
            boolean on = i < market.size();
            cmd.set("#Stand" + i + ".Visible", on);
            if (!on) {
                continue;
            }
            Stand s = market.get(i);
            rail.add(s.key());
            cmd.set("#StandOn" + i + ".Visible", s.key().equals(stand));
            cmd.set("#StandIcon" + i + ".Visible", s.icon() != null);
            if (s.icon() != null) {
                cmd.set("#StandIcon" + i + ".ItemId", s.icon());
            }
            cmd.set("#StandName" + i + ".TextSpans", s.name());
            cmd.set("#StandSub" + i + ".TextSpans", s.sub());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#StandButton" + i, EventData.of("Action", "Stand" + i));
        }
    }

    private void tabs(UICommandBuilder cmd, UIEventBuilder events, Stand s) {
        for (int i = 0; i < TABS; i++) {
            boolean shown = i < s.tabs().size() && s.tabs().size() > 1;
            cmd.set("#Tab" + i + ".Visible", shown);
            if (!shown) {
                continue;
            }
            Tab t = s.tabs().get(i);
            Message text = t.lock() == null ? t.label() : Message.translation(KEYS + "locked").param("tab", t.label());
            boolean on = i == tab;
            cmd.set("#TabOn" + i + ".Visible", on);
            cmd.set("#TabButton" + i + ".Visible", !on);
            cmd.set("#TabOnLabel" + i + ".TextSpans", text);
            if (!on) {
                cmd.set("#TabButton" + i + ".Text", text);
                events.addEventBinding(CustomUIEventBindingType.Activating, "#TabButton" + i, EventData.of("Action", "Tab" + i));
            }
        }
        Tab t = s.tabs().get(tab);
        cmd.set("#Locked.Visible", t.lock() != null);
        if (t.lock() != null) {
            cmd.set("#LockedLine.TextSpans", t.lock());
        }
    }

    private void rows(UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store, Ref<EntityStore> ref, Tab t) {
        BarterShopAsset a = BarterShopAsset.getAssetMap().getAsset(t.shopId());
        Instant now = BarterShopState.getShopGameTime(store);
        BarterTrade[] trades = a == null ? new BarterTrade[0] : BarterShopState.get().getResolvedTrades(a, now);
        BarterShopState.ShopInstanceState state = a == null ? null : BarterShopState.get().getOrCreateShopState(a, now);
        cmd.set("#Restock.Visible", state != null && state.getNextRefreshTime() != null);
        if (state != null && state.getNextRefreshTime() != null) {
            long hours = Math.max(1, (long) Math.ceil(Duration.between(now, state.getNextRefreshTime()).toMinutes() / 60.0));
            cmd.set("#Restock.TextSpans", hours >= 24 ? Message.translation(KEYS + "restockDays").param("days", (int) Math.ceil(hours / 24.0))
                    : Message.translation(KEYS + "restockHours").param("hours", (int) hours));
        }
        int shown = 0;
        for (int k = 0; k < trades.length && shown < ROWS; k++) {
            BarterTrade trade = trades[k];
            if (trade == null || trade.getOutput() == null || !ItemModule.exists(trade.getOutput().getItemId())) {
                continue;
            }
            row(cmd, events, store, ref, t, trade, state == null ? 0 : state.getStock(k), shown, k);
            shown++;
        }
        cmd.set("#Empty.Visible", shown == 0);
        if (shown == 0) {
            cmd.set("#Empty.TextSpans", Message.translation(KEYS + "empty"));
        }
        for (int i = shown; i < ROWS; i++) {
            cmd.set("#Row" + i + ".Visible", false);
        }
    }

    private void row(UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store, Ref<EntityStore> ref, Tab t,
                     BarterTrade trade, int stock, int i, int slot) {
        String n = Integer.toString(i);
        slots[i] = slot;
        String out = trade.getOutput().getItemId();
        int qty = trade.getOutput().getQuantity();
        BarterItemStack in = trade.getInput() == null || trade.getInput().length == 0 ? null : trade.getInput()[0];
        int price = in == null ? 0 : in.getQuantity();
        int have = in == null ? Integer.MAX_VALUE : WorldLife.count(store, ref, in.getItemId()::equals);
        int affordable = price <= 0 ? stock : have / price;
        int max = Math.max(0, Math.min(stock, affordable));
        counts[i] = Math.max(1, Math.min(counts[i], Math.max(1, max)));
        boolean locked = t.lock() != null;
        boolean poor = !locked && stock > 0 && affordable < 1;
        cmd.set("#Row" + n + ".Visible", true);
        cmd.set("#RowIcon" + n + ".ItemId", out);
        cmd.set("#RowQty" + n + ".Visible", qty > 1);
        cmd.set("#RowQty" + n + ".TextSpans", Message.raw(Integer.toString(qty)));
        cmd.set("#RowName" + n + ".TextSpans", VillageBoard.itemName(out));
        Message note;
        if (stock <= 0) {
            note = Message.translation(KEYS + "note.out");
        } else if (poor) {
            note = Message.translation(KEYS + "note.poor").param("count", price - have).param("item", VillageBoard.itemName(in.getItemId()));
        } else {
            note = Message.translation(KEYS + "note.have").param("count", WorldLife.count(store, ref, out::equals));
        }
        cmd.set("#RowNote" + n + ".TextSpans", note);
        cmd.set("#RowStock" + n + ".TextSpans", Message.translation(KEYS + "stock").param("count", stock));
        cmd.set("#RowPrice" + n + ".TextSpans", Message.raw(Integer.toString(price * counts[i])));
        cmd.set("#RowPrice" + n + ".Style.TextColor", poor ? RED : CREAM);
        cmd.set("#RowPriceIcon" + n + ".Visible", in != null);
        if (in != null) {
            cmd.set("#RowPriceIcon" + n + ".ItemId", in.getItemId());
        }
        cmd.set("#RowCount" + n + ".TextSpans", Message.raw(Integer.toString(counts[i])));
        boolean can = !locked && max >= 1;
        cmd.set("#RowLess" + n + ".Disabled", !can || counts[i] <= 1);
        cmd.set("#RowMore" + n + ".Disabled", !can || counts[i] >= max);
        cmd.set("#RowTrade" + n + ".Disabled", !can);
        cmd.set("#RowTrade" + n + ".Text", Message.translation(KEYS + "button." + (locked ? "locked" : stock <= 0 ? "out" : poor ? "poor" : "trade")));
        if (can) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RowLess" + n, EventData.of("Action", "Less" + n));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RowMore" + n, EventData.of("Action", "More" + n));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RowTrade" + n, EventData.of("Action", "Trade" + n));
        }
    }

    // ---- Clics ----

    private static int index(String action, String prefix) {
        try {
            return Integer.parseInt(action.substring(prefix.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Event data) {
        if (data == null || data.action == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        Village v = Villages.registry(world).byId(villageId);
        if (v == null) {
            close();
            return;
        }
        String a = data.action;
        if (a.startsWith("Stand")) {
            int i = index(a, "Stand");
            if (i >= 0 && i < rail.size() && !rail.get(i).equals(stand)) {
                stand = rail.get(i);
                tab = 0;
                List<Stand> market = market(world, store, ref, v);
                Stand s = current(world, store, ref, v, market);
                // Ouvre le meilleur étal ouvert du bâtiment ou de l'échoppe choisie.
                if (s != null) {
                    for (int k = 0; k < s.tabs().size(); k++) {
                        if (s.tabs().get(k).lock() == null) {
                            tab = k;
                        }
                    }
                }
                java.util.Arrays.fill(counts, 1);
            }
        } else if (a.startsWith("Tab")) {
            int i = index(a, "Tab");
            if (i >= 0 && i < TABS) {
                tab = i;
                java.util.Arrays.fill(counts, 1);
            }
        } else if (a.startsWith("Less")) {
            int i = index(a, "Less");
            if (i >= 0 && i < ROWS) {
                counts[i] = Math.max(1, counts[i] - 1);
            }
        } else if (a.startsWith("More")) {
            int i = index(a, "More");
            if (i >= 0 && i < ROWS) {
                counts[i]++;
            }
        } else if (a.startsWith("Trade")) {
            int i = index(a, "Trade");
            if (i >= 0 && i < ROWS) {
                trade(world, store, ref, v, i);
            }
        }
        rebuild();
    }

    /** Troc de la ligne `i` : vérifie l'étal, le stock et la monnaie, prend le prix, donne l'objet, baisse le stock. */
    private void trade(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v, int i) {
        List<Stand> market = stand.startsWith(SINGLE) ? List.of() : market(world, store, ref, v);
        Stand s = current(world, store, ref, v, market);
        if (s == null || tab >= s.tabs().size()) {
            return;
        }
        Tab t = s.tabs().get(tab);
        if (t.lock() != null) {
            tell(t.lock(), false);
            return;
        }
        BarterShopAsset asset = BarterShopAsset.getAssetMap().getAsset(t.shopId());
        if (asset == null) {
            return;
        }
        Instant now = BarterShopState.getShopGameTime(store);
        BarterTrade[] trades = BarterShopState.get().getResolvedTrades(asset, now);
        int slot = slots[i];
        if (slot < 0 || slot >= trades.length || trades[slot] == null) {
            return;
        }
        BarterTrade trade = trades[slot];
        int stock = BarterShopState.get().getOrCreateShopState(asset, now).getStock(slot);
        BarterItemStack in = trade.getInput() == null || trade.getInput().length == 0 ? null : trade.getInput()[0];
        int count = Math.min(counts[i], stock);
        if (in != null && in.getQuantity() > 0) {
            count = Math.min(count, WorldLife.count(store, ref, in.getItemId()::equals) / in.getQuantity());
        }
        if (count < 1) {
            tell(Message.translation(KEYS + "failed"), false);
            return;
        }
        if (!BarterShopState.get().executeTrade(asset, slot, count, now)) {
            tell(Message.translation(KEYS + "failed"), false);
            return;
        }
        if (in != null) {
            for (BarterItemStack each : trade.getInput()) {
                WorldLife.take(store, ref, each.getItemId()::equals, each.getQuantity() * count);
            }
        }
        int got = trade.getOutput().getQuantity() * count;
        Requests.give(store, ref, new ItemStack(trade.getOutput().getItemId(), got));
        String out = trade.getOutput().getItemId();
        dev.nakasar.hytale.peuples.world.TradeLog.add(world, v, dev.nakasar.hytale.peuples.world.TradeLog.Kind.PURCHASE, out,
                Message.translation(KEYS + "log").param("player", playerRef.getUsername()).param("count", got)
                        .param("item", VillageBoard.itemName(out)).param("price", in == null ? 0 : in.getQuantity() * count)
                        .param("currency", in == null ? Message.empty() : VillageBoard.itemName(in.getItemId())));
        tell(Message.translation(KEYS + "done").param("count", got).param("item", VillageBoard.itemName(trade.getOutput().getItemId())), true);
        counts[i] = 1;
    }

    private void tell(Message m, boolean ok) {
        NotificationUtil.sendNotification(playerRef.getPacketHandler(), m, ok ? NotificationStyle.Success : NotificationStyle.Warning);
    }
}
