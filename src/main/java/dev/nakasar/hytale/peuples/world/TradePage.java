package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.nakasar.hytale.peuples.reputation.Rank;
import dev.nakasar.hytale.peuples.reputation.Reputations;
import dev.nakasar.hytale.peuples.request.BoardTabs;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.VillageBoard;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L'onglet « Commerce » du panneau du village, refait le 21 novembre 2026 (maquette « Commerce repensé ») : un résumé
 * (routes, prochaine caravane, manques, luxes, porte-monnaie) ; les routes en cartes (partenaire, niveau en pastilles,
 * équilibre, jauge de la caravane, cases en icônes qu'un clic ouvre dans {@link TradePickerPage}, actions rares derrière
 * « ... ») ; à droite ce dont le village manque (et qui en envoie), ce qu'on lui commande, l'étal des importations ; les
 * luxes en bandeau. Le sous-onglet « Registre » montre les derniers événements du commerce ({@link TradeLog}).
 * Mise en page par peuple ({@code TradePage_<Peuple>.ui}, {@code tools/generate_trade.py}).
 */
public final class TradePage extends InteractiveCustomUIPage<TradePage.Event> {

    private static final String LAYOUT = "Pages/Nakasar/Peuples/TradePage.ui";
    private static final List<String> THEMED = List.of("Kweebec", "Trork", "Feran", "Renonce", "Slothian");
    /** tools/generate_trade.py ROUTES, CANDIDATES, STALL, CASES, NEEDS, ORDERS, LOG, LOG_FILTERS. */
    private static final int ROUTES = 3;
    private static final int CANDIDATES = 3;
    private static final int STALL = 4;
    private static final int CASES = 3;
    private static final int NEEDS = 3;
    private static final int ORDERS = 2;
    private static final int LOG = 11;
    private static final List<String> FILTERS = List.of("All", "Caravans", "Needs", "Orders", "Purchases", "Routes");
    private static final String CASE_LETTERS = "abc";
    private static final String OK = "#9ed36a";
    private static final String WARN = "#e8a65a";
    private static final String MUTED = "#7f93a8";
    private static final String GOLD = "#e8c46a";
    private static final String BLUE = "#7fa8d8";
    private static final String TEXT = "#e6edf3";
    static final String KEYS = TradeRoutes.KEYS + "page.";
    public static final String TAB = KEYS + "tab";

    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .build();

        String action;
    }

    private final String villageId;
    private final List<String> routes = new ArrayList<>();
    private final List<String> candidates = new ArrayList<>();
    private final List<String> stall = new ArrayList<>();
    private boolean log;
    private String filter = "All";
    private int menu = -1;

    public TradePage(PlayerRef playerRef, Village village) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.villageId = village.id();
    }

    public static void open(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, Village village) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new TradePage(playerRef, village));
        }
    }

    static String layout(People people) {
        return people != null && THEMED.contains(people.id()) ? "Pages/Nakasar/Peuples/TradePage_" + people.id() + ".ui" : LAYOUT;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Village village = Villages.registry(world).byId(villageId);
        cmd.append(layout(village == null ? null : village.people()));
        if (village == null) {
            cmd.set("#PageTitle.TextSpans", Message.translation(WorldPage.KEYS + "orphan"));
            return;
        }
        long day = GameClock.day(store);
        cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + "title").param("village", village.name()));
        BoardTabs.fill(cmd, events, store, village);
        summary(cmd, store, ref, world, village, day);
        views(cmd, events);
        cmd.set("#MainView.Visible", !log);
        cmd.set("#LogView.Visible", log);
        if (log) {
            ledger(cmd, events, world, village, day);
            return;
        }
        luxuries(cmd, world, village, day);
        routes(cmd, events, world, village, day);
        candidates(cmd, events, world, village, day);
        needs(cmd, world, village);
        orders(cmd, world, village);
        stall(cmd, events, world, village, day);
    }

    // ---- Résumé ----

    private void summary(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref, World world, Village v, long day) {
        int used = TradeRoutes.used(world, v);
        int slots = TradeRoutes.slots(world, v, day);
        cmd.set("#SumRoutesLabel.TextSpans", Message.translation(KEYS + "sum.routes"));
        cmd.set("#SumRoutesValue.TextSpans", Message.translation(KEYS + "sum.routesValue").param("used", used).param("slots", slots));
        cmd.set("#SumRoutesSub.TextSpans", used >= slots ? Message.translation(KEYS + "sum.routesFull")
                : Message.translation(KEYS + "sum.routesFree").param("count", TradeRoutes.candidates(world, v).size()));
        // Prochaine caravane : une en route d'abord, sinon le prochain départ.
        cmd.set("#SumCaravanLabel.TextSpans", Message.translation(KEYS + "sum.caravan"));
        Message value = Message.translation(KEYS + "sum.caravanNone");
        Message sub = Message.translation(KEYS + "sum.caravanNoneSub");
        long best = Long.MAX_VALUE;
        for (TradeRoutes.Route r : TradeRoutes.of(world, v)) {
            Village o = TradeRoutes.village(world, r.other(v.id()));
            if (o == null || r.suspended()) {
                continue;
            }
            if (caravan(world, r) != null) {
                value = Message.translation(KEYS + "sum.caravanRoad");
                sub = Message.translation(KEYS + "sum.caravanTo").param("partner", TradeRoutes.name(world, o));
                best = -1;
                break;
            }
            long left = Math.max(0, r.next() - day);
            if (left < best) {
                best = left;
                value = left == 0 ? Message.translation(KEYS + "sum.caravanToday") : Message.translation(KEYS + "sum.caravanDays").param("days", left);
                sub = Message.translation(KEYS + "sum.caravanTo").param("partner", TradeRoutes.name(world, o));
            }
        }
        cmd.set("#SumCaravanValue.TextSpans", value);
        cmd.set("#SumCaravanValue.Style.TextColor", best == Long.MAX_VALUE ? MUTED : OK);
        cmd.set("#SumCaravanSub.TextSpans", sub);
        List<TradeNeeds.Need> needs = TradeNeeds.needs(world, v);
        int covered = 0;
        for (TradeNeeds.Need n : needs) {
            if (supplier(world, v, n.item()) != null) {
                covered++;
            }
        }
        cmd.set("#SumNeedsLabel.TextSpans", Message.translation(KEYS + "sum.needs"));
        cmd.set("#SumNeedsValue.TextSpans", Message.raw(Integer.toString(needs.size())));
        cmd.set("#SumNeedsValue.Style.TextColor", needs.isEmpty() ? OK : WARN);
        cmd.set("#SumNeedsSub.TextSpans", Message.translation(KEYS + "sum.needsSub").param("covered", covered));
        int count = TradeAccess.count(world, v, day);
        int target = Math.min(3, v.tier() + 1);
        int need = v.tier() >= 3 ? TradeAccess.SETTLER : TradeAccess.needAt(target);
        cmd.set("#SumLuxLabel.TextSpans", Message.translation(KEYS + "sum.lux"));
        cmd.set("#SumLuxValue.TextSpans", Message.translation(KEYS + "sum.luxValue").param("count", count).param("need", need));
        cmd.set("#SumLuxValue.Style.TextColor", count >= need ? GOLD : TEXT);
        cmd.set("#SumLuxSub.TextSpans", v.tier() >= 3 ? Message.translation(KEYS + "sum.luxSettler")
                : Message.translation(KEYS + "sum.luxTier").param("tier", Message.translation(dev.nakasar.hytale.peuples.village.Tiers.nameKey(target))));
        String favour = v.people().favorItem();
        cmd.set("#SumWalletLabel.TextSpans", Message.translation(KEYS + "sum.wallet"));
        cmd.set("#SumWalletIcon.ItemId", favour);
        cmd.set("#SumWalletValue.TextSpans", Message.raw(Integer.toString(WorldLife.count(store, ref, favour::equals))));
        cmd.set("#SumWalletSub.TextSpans", VillageBoard.itemName(favour));
    }

    private static void toggle(UICommandBuilder cmd, UIEventBuilder events, String id, Message text, boolean on, String action) {
        cmd.set("#" + id + "On.Visible", on);
        cmd.set("#" + id + "Button.Visible", !on);
        cmd.set("#" + id + "Label.TextSpans", text);
        if (!on) {
            cmd.set("#" + id + "Button.Text", text);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#" + id + "Button", EventData.of("Action", action));
        }
    }

    private void views(UICommandBuilder cmd, UIEventBuilder events) {
        toggle(cmd, events, "ViewMain", Message.translation(KEYS + "view.main"), !log, "ViewMain");
        toggle(cmd, events, "ViewLog", Message.translation(KEYS + "view.log"), log, "ViewLog");
    }

    // ---- Luxes ----

    private void luxuries(UICommandBuilder cmd, World world, Village v, long day) {
        int count = TradeAccess.count(world, v, day);
        int target = Math.min(3, v.tier() + 1);
        Message need = v.tier() >= 3 ? Message.translation(KEYS + "lux.settler").param("need", TradeAccess.SETTLER)
                : Message.translation(KEYS + "lux.next").param("tier", Message.translation(dev.nakasar.hytale.peuples.village.Tiers.nameKey(target)))
                .param("need", TradeAccess.needAt(target));
        cmd.set("#LuxHeader.TextSpans", Message.translation(KEYS + "lux.header").param("count", count).param("total", TradeGoods.LUXURIES.size()));
        cmd.set("#LuxLine.TextSpans", Message.translation(KEYS + "lux.line").param("need", need).param("bonus", TradeAccess.bonus(world, v, day)));
        TradeGoods.Luxury own = TradeGoods.terroir(v.people());
        for (int i = 0; i < TradeGoods.LUXURIES.size(); i++) {
            TradeGoods.Luxury l = TradeGoods.LUXURIES.get(i);
            cmd.set("#LuxIcon" + i + ".ItemId", l.item());
            long until = TradeAccess.until(world, v, l.item());
            Message mark;
            String colour;
            if (l == own && v.tier() >= 2) {
                mark = Message.translation(KEYS + "lux.terroir");
                colour = GOLD;
            } else if (until >= day) {
                mark = Message.translation(KEYS + "lux.days").param("days", until - day + 1);
                colour = OK;
            } else if (!TradeGoods.allowed(v.people(), l.item())) {
                mark = Message.translation(KEYS + "lux.never");
                colour = "#e07468";
            } else {
                mark = Message.translation(KEYS + "lux.missing");
                colour = MUTED;
            }
            cmd.set("#LuxMark" + i + ".TextSpans", mark);
            cmd.set("#LuxMark" + i + ".Style.TextColor", colour);
        }
    }

    // ---- Routes ----

    static Message itemName(String item) {
        return item == null ? Message.translation(KEYS + "case.empty") : Message.translation(TradeGoods.itemKey(item));
    }

    static Message gradeName(String item) {
        return Message.translation(KEYS + "grade." + TradeGoods.grade(item).key());
    }

    /** La caravane de la route en chemin, ou null. */
    private static TradeCaravans.Caravan caravan(World world, TradeRoutes.Route r) {
        for (TradeCaravans.Caravan c : TradeCaravans.all(world)) {
            if (r.key().equals(c.route)) {
                return c;
            }
        }
        return null;
    }

    private void routes(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, long day) {
        List<TradeRoutes.Route> list = TradeRoutes.of(world, v);
        routes.clear();
        cmd.set("#RoutesHeader.TextSpans", Message.translation(KEYS + "routes").param("count", list.size())
                .param("slots", TradeRoutes.slots(world, v, day)));
        cmd.set("#RoutesHint.TextSpans", Message.translation(KEYS + "routesHint"));
        cmd.set("#NoRoute.Visible", list.isEmpty());
        if (list.isEmpty()) {
            cmd.set("#NoRoute.TextSpans", Message.translation(KEYS + (v.tier() < 1 ? "noRouteTier" : "noRoute")));
        }
        for (int i = 0; i < ROUTES; i++) {
            String n = Integer.toString(i);
            if (i >= list.size()) {
                cmd.set("#Route" + n + ".Visible", false);
                continue;
            }
            TradeRoutes.Route r = list.get(i);
            routes.add(r.key());
            Village o = TradeRoutes.village(world, r.other(v.id()));
            cmd.set("#Route" + n + ".Visible", true);
            cmd.set("#RouteName" + n + ".TextSpans", o == null ? Message.raw("?") : TradeRoutes.name(world, o));
            for (int j = 0; j < CASES; j++) {
                String pip = n + CASE_LETTERS.charAt(j);
                cmd.set("#RoutePip" + pip + ".Visible", j < r.level());
                cmd.set("#RoutePipOff" + pip + ".Visible", j >= r.level());
            }
            int deficit = TradeRoutes.deficit(r, v.id());
            int credit = r.credit(v.id());
            Message balance = deficit <= 0 ? Message.translation(KEYS + "balance.ok")
                    : credit >= deficit ? Message.translation(KEYS + "balance.credit").param("credit", credit)
                    : Message.translation(KEYS + "balance.short").param("deficit", deficit).param("credit", credit);
            cmd.set("#RouteBalance" + n + ".TextSpans", balance);
            cmd.set("#RouteBalance" + n + ".Style.TextColor", deficit <= 0 || credit >= deficit ? OK : WARN);
            // Jauge : la caravane en chemin (de ce village ou vers lui), sinon les jours avant le départ.
            cmd.set("#RouteFrom" + n + ".TextSpans", Message.raw(v.name()));
            cmd.set("#RouteTo" + n + ".TextSpans", o == null ? Message.raw("?") : TradeRoutes.name(world, o));
            TradeCaravans.Caravan c = caravan(world, r);
            Message state;
            float fill;
            if (r.suspended()) {
                state = Message.translation(KEYS + "route.suspended").param("days", Math.max(0, TradeRoutes.BREAK_DAYS - (day - r.suspendedSince())));
                fill = 0f;
            } else if (c != null && o != null) {
                double length = Math.max(1.0, TradeRoutes.distance(v, o));
                double at = Math.max(0.0, Math.min(1.0, c.at() / length));
                fill = (float) (c.from().equals(v.id()) ? at : 1.0 - at);
                state = Message.translation(KEYS + "state.road");
            } else {
                long left = Math.max(0, r.next() - day);
                fill = (float) Math.max(0.0, Math.min(1.0, 1.0 - left / (double) Math.max(1, r.interval())));
                state = left == 0 ? Message.translation(KEYS + "state.today") : Message.translation(KEYS + "state.days").param("days", left);
            }
            if (r.auto()) {
                state = Message.translation(KEYS + "route.auto").param("state", state);
            }
            cmd.set("#RouteBar" + n + ".Value", fill);
            cmd.set("#RouteState" + n + ".TextSpans", state);
            cmd.set("#RouteOutLabel" + n + ".TextSpans", Message.translation(KEYS + "route.outShort"));
            cmd.set("#RouteInLabel" + n + ".TextSpans", Message.translation(KEYS + "route.inShort"));
            cases(cmd, events, r, n, "Out", r.exportsOf(v.id()));
            cases(cmd, events, r, n, "In", r.importsOf(v.id()));
            boolean raise = r.level() < TradeRoutes.MAX_LEVEL;
            cmd.set("#RouteRaise" + n + ".Visible", raise);
            if (raise) {
                cmd.set("#RouteRaise" + n + ".Text", Message.translation(KEYS + "button.raiseShort")
                        .param("level", Message.translation(KEYS + "level." + (r.level() + 1))).param("count", TradeRoutes.RAISE_OFFERING));
                events.addEventBinding(CustomUIEventBindingType.Activating, "#RouteRaise" + n, EventData.of("Action", "Raise" + n));
            }
            boolean pay = deficit > 0 && credit < deficit;
            cmd.set("#RoutePay" + n + ".Visible", pay);
            if (pay) {
                cmd.set("#RoutePay" + n + ".Text", Message.translation(KEYS + "button.payShort").param("credit", dev.nakasar.hytale.peuples.workshop.Projects.creditPerFavour(v)));
                events.addEventBinding(CustomUIEventBindingType.Activating, "#RoutePay" + n, EventData.of("Action", "Pay" + n));
            }
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RouteMore" + n, EventData.of("Action", "Menu" + n));
            cmd.set("#RouteMenu" + n + ".Visible", menu == i);
            cmd.set("#RouteTrack" + n + ".Visible", menu != i);
            if (menu == i) {
                cmd.set("#RouteMenuLine" + n + ".TextSpans", Message.translation(KEYS + "menu.line").param("arrivals", r.arrivals()).param("lost", r.lost()));
                cmd.set("#RouteClose" + n + ".Text", Message.translation(KEYS + "button.close"));
                events.addEventBinding(CustomUIEventBindingType.Activating, "#RouteClose" + n, EventData.of("Action", "Close" + n));
            }
        }
    }

    /** Les trois cases d'un côté : marchandise (icône, nom, chargement), case libre, ou case fermée au-dessus du niveau. */
    private static void cases(UICommandBuilder cmd, UIEventBuilder events, TradeRoutes.Route r, String n, String side, List<String> items) {
        for (int j = 0; j < CASES; j++) {
            String id = "#Case" + side + n + CASE_LETTERS.charAt(j);
            boolean open = j < r.level();
            String item = open && j < items.size() ? items.get(j) : null;
            cmd.set(id + ".Disabled", !open);
            cmd.set(id + "Icon.Visible", item != null);
            if (item != null) {
                cmd.set(id + "Icon.ItemId", item);
                cmd.set(id + "Name.TextSpans", itemName(item));
                cmd.set(id + "Sub.TextSpans", Message.translation(KEYS + "case.sub").param("count", TradeGoods.grade(item).load(r.level()))
                        .param("grade", gradeName(item)));
            } else if (open) {
                cmd.set(id + "Name.TextSpans", Message.translation(KEYS + "case.choose"));
                cmd.set(id + "Sub.TextSpans", Message.translation(KEYS + "case.free"));
            } else {
                cmd.set(id + "Name.TextSpans", Message.translation(KEYS + "case.locked").param("level", Message.translation(KEYS + "level." + (j + 1))));
                cmd.set(id + "Sub.TextSpans", Message.translation(KEYS + "case.lockedSub"));
            }
            if (open) {
                events.addEventBinding(CustomUIEventBindingType.Activating, id, EventData.of("Action", "Pick" + side + n + "_" + j));
            }
        }
    }

    // ---- Ouvrir une route ----

    private void candidates(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, long day) {
        List<Village> list = TradeRoutes.candidates(world, v);
        candidates.clear();
        int used = TradeRoutes.used(world, v);
        int slots = TradeRoutes.slots(world, v, day);
        boolean full = used >= slots;
        Message line;
        if (full) {
            line = Message.translation(KEYS + "open.full").param("used", used).param("slots", slots);
        } else if (list.isEmpty()) {
            line = Message.translation(KEYS + "noCandidate").param("max", Links.MAX_LENGTH);
        } else {
            line = Message.translation(KEYS + "open.line").param("used", used).param("slots", slots).param("offering", TradeRoutes.OFFERING);
        }
        cmd.set("#OpenLine.TextSpans", line);
        cmd.set("#OpenButtons.Visible", !full && !list.isEmpty());
        for (int i = 0; i < CANDIDATES; i++) {
            String n = Integer.toString(i);
            boolean shown = !full && i < list.size();
            cmd.set("#CandOpen" + n + ".Visible", shown);
            if (!shown) {
                continue;
            }
            Village o = list.get(i);
            candidates.add(o.id());
            cmd.set("#CandOpen" + n + ".Text", Message.translation(KEYS + "open.with").param("partner", TradeRoutes.name(world, o)));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CandOpen" + n, EventData.of("Action", "Open" + n));
        }
    }

    // ---- Manques et commandes ----

    /** Le village qui s'est engagé à envoyer `item` à `v`, ou null. */
    private static Village supplier(World world, Village v, String item) {
        for (TradeNeeds.Pledge p : TradeNeeds.pledgesFor(world, v)) {
            if (p.item.equals(item)) {
                return Villages.registry(world).byId(p.supplier);
            }
        }
        return null;
    }

    private void needs(UICommandBuilder cmd, World world, Village v) {
        List<TradeNeeds.Need> list = TradeNeeds.needs(world, v);
        cmd.set("#NeedsHeader.TextSpans", Message.translation(KEYS + "needs.header"));
        cmd.set("#NoNeeds.Visible", list.isEmpty());
        if (list.isEmpty()) {
            cmd.set("#NoNeeds.TextSpans", Message.translation(KEYS + "needs.none"));
        }
        for (int i = 0; i < NEEDS; i++) {
            String n = Integer.toString(i);
            boolean shown = i < list.size();
            cmd.set("#Need" + n + ".Visible", shown);
            if (!shown) {
                continue;
            }
            TradeNeeds.Need need = list.get(i);
            cmd.set("#Need" + n + "Icon.ItemId", need.item());
            cmd.set("#Need" + n + "Name.TextSpans", VillageBoard.itemName(need.item()));
            cmd.set("#Need" + n + "Sub.TextSpans", Message.translation(TradeNeeds.KEYS + "reason." + need.reason().key()));
            Village s = supplier(world, v, need.item());
            cmd.set("#Need" + n + "State.TextSpans", s == null ? Message.translation(KEYS + "needs.waiting")
                    : Message.translation(KEYS + "needs.covered").param("supplier", TradeRoutes.name(world, s)));
            cmd.set("#Need" + n + "State.Style.TextColor", s == null ? WARN : BLUE);
        }
    }

    private void orders(UICommandBuilder cmd, World world, Village v) {
        List<TradeNeeds.Pledge> list = TradeNeeds.pledgesOf(world, v);
        cmd.set("#OrdersHeader.TextSpans", Message.translation(KEYS + "orders.header"));
        cmd.set("#NoOrders.Visible", list.isEmpty());
        if (list.isEmpty()) {
            cmd.set("#NoOrders.TextSpans", Message.translation(KEYS + "orders.none"));
        }
        for (int i = 0; i < ORDERS; i++) {
            String n = Integer.toString(i);
            boolean shown = i < list.size();
            cmd.set("#Order" + n + ".Visible", shown);
            if (!shown) {
                continue;
            }
            TradeNeeds.Pledge p = list.get(i);
            Village b = Villages.registry(world).byId(p.buyer);
            cmd.set("#Order" + n + "Icon.ItemId", p.item);
            cmd.set("#Order" + n + "Name.TextSpans", VillageBoard.itemName(p.item));
            cmd.set("#Order" + n + "Sub.TextSpans", Message.translation(KEYS + "orders.for").param("buyer", b == null ? Message.raw(p.buyer) : TradeRoutes.name(world, b)));
            String key = switch (p.state) {
                case BUILD -> "orders.build";
                case ACTIVE -> "orders.active";
                default -> "orders.wait";
            };
            cmd.set("#Order" + n + "State.TextSpans", Message.translation(KEYS + key));
            cmd.set("#Order" + n + "State.Style.TextColor", p.state == TradeNeeds.State.ACTIVE ? OK : WARN);
        }
    }

    // ---- Étal ----

    /** Petit lot de l'étal : la moitié d'un chargement de niveau I (8 communes, 3 fines, 1 luxe). */
    public static int stallLot(String item) {
        return Math.max(1, TradeGoods.grade(item).load(1) / 2);
    }

    private void stall(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, long day) {
        stall.clear();
        Map<String, Integer> deposit = Villages.depositContents(world, v);
        for (String item : TradeAccess.items(world, v, day).keySet()) {
            if (deposit != null && deposit.getOrDefault(item, 0) >= stallLot(item) && stall.size() < STALL) {
                stall.add(item);
            }
        }
        cmd.set("#StallHeader.TextSpans", Message.translation(KEYS + "stall"));
        cmd.set("#NoStall.Visible", stall.isEmpty());
        if (stall.isEmpty()) {
            cmd.set("#NoStall.TextSpans", Message.translation(KEYS + (deposit == null ? "stallFar" : "stallEmpty")));
        }
        for (int i = 0; i < STALL; i++) {
            String n = Integer.toString(i);
            boolean shown = i < stall.size();
            cmd.set("#Stall" + n + ".Visible", shown);
            if (!shown) {
                continue;
            }
            String item = stall.get(i);
            cmd.set("#Stall" + n + "Icon.ItemId", item);
            cmd.set("#Stall" + n + "Name.TextSpans", itemName(item));
            cmd.set("#Stall" + n + "Sub.TextSpans", Message.translation(KEYS + "stall.sub").param("count", deposit.getOrDefault(item, 0)));
            cmd.set("#Stall" + n + "Buy.Text", Message.translation(KEYS + "button.buyShort").param("count", stallLot(item)));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Stall" + n + "Buy", EventData.of("Action", "Buy" + n));
        }
    }

    /** Achète un petit lot de l'étal contre une faveur ; rend le refus, ou null (aussi pour le gestionnaire, steward/). */
    public static Message buyLot(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v, String item) {
        if (!Reputations.rank(store, ref, v).atLeast(Rank.TOLERE)) {
            return Message.translation(TradeRoutes.KEYS + "why.rankTolere").param("village", v.name());
        }
        ItemContainer chest = Villages.depositChest(world, v);
        int lot = stallLot(item);
        if (chest == null || TradeGoods.stock(chest, item) < lot) {
            return Message.translation(KEYS + "stallEmpty");
        }
        String favour = v.people().favorItem();
        if (WorldLife.count(store, ref, favour::equals) < 1) {
            return Message.translation(TradeRoutes.KEYS + "why.offering").param("count", 1);
        }
        WorldLife.take(store, ref, favour::equals, 1);
        int got = Requests.take(chest, item, lot);
        Requests.give(store, ref, new ItemStack(item, got));
        PlayerRef player = ref == null ? null : store.getComponent(ref, PlayerRef.getComponentType());
        TradeLog.add(world, v, TradeLog.Kind.PURCHASE, item, Message.translation("nakasar_peuples.shop.log")
                .param("player", player == null ? "?" : player.getUsername()).param("count", got).param("item", itemName(item))
                .param("price", 1).param("currency", VillageBoard.itemName(favour)));
        return null;
    }

    // ---- Registre ----

    private void ledger(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, long day) {
        for (String f : FILTERS) {
            toggle(cmd, events, "Filter" + f, Message.translation(KEYS + "log." + f), f.equals(filter), "Filter" + f);
        }
        List<TradeLog.Entry> list = new ArrayList<>();
        for (TradeLog.Entry e : TradeLog.of(world, v)) {
            if (filter.equals("All") || e.kind().filter.equals(filter)) {
                list.add(e);
            }
        }
        cmd.set("#LogHint.TextSpans", Message.translation(KEYS + "log.hint").param("count", TradeLog.KEEP));
        cmd.set("#NoLog.Visible", list.isEmpty());
        if (list.isEmpty()) {
            cmd.set("#NoLog.TextSpans", Message.translation(KEYS + "log.none"));
        }
        for (int i = 0; i < LOG; i++) {
            String n = Integer.toString(i);
            boolean shown = i < list.size();
            cmd.set("#Log" + n + ".Visible", shown);
            if (!shown) {
                continue;
            }
            TradeLog.Entry e = list.get(i);
            long ago = day - e.day();
            cmd.set("#LogWhen" + n + ".TextSpans", ago <= 0 ? Message.translation(KEYS + "log.today").param("hour", e.hour())
                    : ago == 1 ? Message.translation(KEYS + "log.yesterday") : Message.translation(KEYS + "log.days").param("days", ago));
            String icon = e.item() != null ? e.item() : v.people().favorItem();
            cmd.set("#LogIcon" + n + ".ItemId", icon);
            cmd.set("#LogText" + n + ".TextSpans", e.text());
            cmd.set("#LogTag" + n + ".TextSpans", Message.translation(KEYS + "log.tag." + e.kind().name()));
            cmd.set("#LogTag" + n + ".Style.TextColor", e.kind().colour);
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

    private TradeRoutes.Route route(World world, int i) {
        return i >= 0 && i < routes.size() ? TradeRoutes.get(world, routes.get(i)) : null;
    }

    private void tell(Message m, boolean ok) {
        playerRef.sendMessage(m);
        NotificationUtil.sendNotification(playerRef.getPacketHandler(), m, ok ? NotificationStyle.Success : NotificationStyle.Warning);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Event data) {
        if (data == null || data.action == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        Village village = Villages.registry(world).byId(villageId);
        if (village == null) {
            close();
            return;
        }
        String action = data.action;
        if (BoardTabs.handle(action, world, store, ref, playerRef, village)) {
            return;
        }
        Message why = null;
        Message done = null;
        if (action.equals("ViewMain") || action.equals("ViewLog")) {
            log = action.equals("ViewLog");
        } else if (action.startsWith("Filter")) {
            String f = action.substring("Filter".length());
            if (FILTERS.contains(f)) {
                filter = f;
            }
        } else if (action.startsWith("Pick")) {
            // « PickOut1_2 » : route 1, case 2 des envois ; « PickIn… » : des réceptions.
            boolean export = action.startsWith("PickOut");
            String body = action.substring(export ? 7 : 6);
            int cut = body.indexOf('_');
            TradeRoutes.Route r = cut > 0 ? route(world, index(body.substring(0, cut), "")) : null;
            int j = cut > 0 ? index(body.substring(cut + 1), "") : -1;
            if (r != null && j >= 0) {
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().openCustomPage(ref, store, new TradePickerPage(playerRef, village, r.key(), export, j));
                    return;
                }
            }
        } else if (action.startsWith("Menu")) {
            int i = index(action, "Menu");
            menu = menu == i ? -1 : i;
        } else if (action.startsWith("Raise")) {
            TradeRoutes.Route r = route(world, index(action, "Raise"));
            if (r != null) {
                why = TradeRoutes.raise(world, store, ref, village, r);
                done = Message.translation(KEYS + "raised");
            }
        } else if (action.startsWith("Pay")) {
            TradeRoutes.Route r = route(world, index(action, "Pay"));
            if (r != null) {
                why = TradeRoutes.payCredit(world, store, ref, village, r);
                done = Message.translation(KEYS + "paid").param("credit", dev.nakasar.hytale.peuples.workshop.Projects.creditPerFavour(village));
            }
        } else if (action.startsWith("Close")) {
            TradeRoutes.Route r = route(world, index(action, "Close"));
            if (r != null) {
                why = TradeRoutes.close(world, store, ref, village, r);
                menu = -1;
            }
        } else if (action.startsWith("Open")) {
            int i = index(action, "Open");
            Village o = i >= 0 && i < candidates.size() ? TradeRoutes.village(world, candidates.get(i)) : null;
            if (o != null) {
                why = TradeRoutes.open(world, store, ref, village, o);
            }
        } else if (action.startsWith("Buy")) {
            int i = index(action, "Buy");
            if (i >= 0 && i < stall.size()) {
                why = buyLot(world, store, ref, village, stall.get(i));
                done = Message.translation(KEYS + "bought").param("count", stallLot(stall.get(i))).param("item", itemName(stall.get(i)));
            }
        }
        if (why != null) {
            tell(why, false);
        } else if (done != null) {
            tell(done, true);
        }
        rebuild();
    }
}
