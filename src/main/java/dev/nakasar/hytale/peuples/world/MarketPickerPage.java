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
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commandes et dépôt-vente de la place du marché ({@link Markets}), dans la mise en page de la grille des marchandises
 * des routes ({@code TradePickerPage_<Peuple>.ui}) : la grille de ce que les marchands des autres peuples peuvent
 * apporter (commande) ou de ce que le joueur porte et que le village peut vendre (dépôt-vente), filtrable par classe.
 * Un clic passe la commande ou laisse le dépôt, puis ferme la page.
 */
public final class MarketPickerPage extends InteractiveCustomUIPage<MarketPickerPage.Event> {

    /** tools/generate_trade.py PICK. */
    private static final int PICK = 12;
    private static final List<String> FILTERS = List.of("All", "Common", "Fine", "Luxury");
    private static final List<String> THEMED = List.of("Kweebec", "Trork", "Feran", "Renonce", "Slothian");
    private static final String GRADES = TradeRoutes.KEYS + "picker.";
    private static final String KEYS = Markets.KEYS;

    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .build();

        String action;
    }

    private final String villageId;
    private final boolean consign;
    private String filter = "All";
    private final List<String> shown = new ArrayList<>();

    public MarketPickerPage(PlayerRef playerRef, Village village, boolean consign) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.villageId = village.id();
        this.consign = consign;
    }

    private static String layout(People people) {
        return people != null && THEMED.contains(people.id()) ? "Pages/Nakasar/Peuples/TradePickerPage_" + people.id() + ".ui"
                : "Pages/Nakasar/Peuples/TradePickerPage.ui";
    }

    private static boolean matches(String filter, String item) {
        return switch (filter) {
            case "Common" -> TradeGoods.grade(item) == TradeGoods.Grade.COMMON;
            case "Fine" -> TradeGoods.grade(item) == TradeGoods.Grade.FINE;
            case "Luxury" -> TradeGoods.grade(item) == TradeGoods.Grade.LUXURY;
            default -> true;
        };
    }

    /** Ce que le joueur porte et que le village peut vendre : objet = nombre porté. */
    private static Map<String, Integer> carried(Store<EntityStore> store, Ref<EntityStore> ref, Village host) {
        Map<String, Integer> out = new LinkedHashMap<>();
        CombinedItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        for (short slot = 0; slot < inv.getCapacity(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && Markets.consignable(host, stack.getItemId())) {
                out.merge(stack.getItemId(), stack.getQuantity(), Integer::sum);
            }
        }
        return out;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Village host = Villages.registry(world).byId(villageId);
        cmd.append(layout(host == null ? null : host.people()));
        if (host == null) {
            cmd.set("#PageTitle.TextSpans", Message.translation(WorldPage.KEYS + "orphan"));
            return;
        }
        long day = GameClock.day(store);
        Markets.Order mine = Markets.order(world, host, playerRef.getUuid());
        Map<String, Integer> carried = consign ? carried(store, ref, host) : Map.of();
        Map<String, Markets.Offer> offers = new LinkedHashMap<>();
        if (!consign) {
            for (Markets.Offer o : Markets.catalogue(store, host)) {
                offers.put(o.item(), o);
            }
        }
        List<String> all = new ArrayList<>(consign ? carried.keySet() : offers.keySet());
        cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + (consign ? "consign.title" : "order.title")).param("village", host.name()));
        for (String grade : FILTERS) {
            Message text = grade.equals("All") ? Message.translation(GRADES + "All").param("count", all.size()) : Message.translation(GRADES + grade);
            boolean on = grade.equals(filter);
            cmd.set("#Grade" + grade + "On.Visible", on);
            cmd.set("#Grade" + grade + "Button.Visible", !on);
            cmd.set("#Grade" + grade + "Label.TextSpans", text);
            if (!on) {
                cmd.set("#Grade" + grade + "Button.Text", text);
                events.addEventBinding(CustomUIEventBindingType.Activating, "#Grade" + grade + "Button", EventData.of("Action", "Grade" + grade));
            }
        }
        cmd.set("#PickHint.TextSpans", Message.translation(KEYS + (consign ? "consign.hint" : "order.hint")));
        shown.clear();
        for (String item : all) {
            if (matches(filter, item) && shown.size() < PICK) {
                shown.add(item);
            }
        }
        cmd.set("#NoPick.Visible", all.isEmpty());
        if (all.isEmpty()) {
            cmd.set("#NoPick.TextSpans", Message.translation(KEYS + (consign ? "consign.none" : "order.none")).param("village", host.name()));
        }
        boolean blocked = consign ? Markets.consignment(world, host, playerRef.getUuid()) != null : mine != null;
        for (int i = 0; i < PICK; i++) {
            String id = "#Pick" + i;
            boolean on = i < shown.size();
            cmd.set(id + ".Visible", on);
            if (!on) {
                continue;
            }
            String item = shown.get(i);
            boolean current = mine != null && !consign && item.equals(mine.item());
            cmd.set(id + "Icon.ItemId", item);
            cmd.set(id + "Name.TextSpans", TradePage.itemName(item));
            Message sub;
            if (consign) {
                int count = Math.min(carried.get(item), Markets.consignMax(item));
                sub = Message.translation(KEYS + "consign.sub").param("count", count).param("price", Markets.consignPrice(item, count));
            } else if (current) {
                sub = Message.translation(KEYS + "order.current");
            } else {
                Markets.Offer o = offers.get(item);
                sub = Message.translation(KEYS + "order.sub").param("count", o.count()).param("price", o.total());
            }
            cmd.set(id + "Sub.TextSpans", sub);
            cmd.set(id + "Grade.TextSpans", Message.raw("*".repeat(TradeGoods.grade(item).weight())));
            cmd.set(id + ".Disabled", blocked);
            if (!blocked) {
                events.addEventBinding(CustomUIEventBindingType.Activating, id, EventData.of("Action", "Pick" + i));
            }
        }
        if (consign) {
            Markets.Consignment c = Markets.consignment(world, host, playerRef.getUuid());
            cmd.set("#WeightOut.TextSpans", c == null ? Message.translation(KEYS + "consign.mineNone")
                    : Message.translation(KEYS + "consign.mine").param("count", c.count()).param("item", TradePage.itemName(c.item())));
            int days = Markets.daysToMarket(host, day);
            cmd.set("#WeightIn.TextSpans", days == 0 ? Message.translation(KEYS + "stand.day") : Message.translation(KEYS + "day.next").param("days", days));
            cmd.set("#WeightIn.Style.TextColor", days == 0 ? "#9ed36a" : "#e8a65a");
            cmd.set("#WeightBar.Value", 1f - days / (float) Markets.WEEK);
            cmd.set("#PickRule.TextSpans", Message.translation(KEYS + "consign.rule"));
        } else {
            int count = Markets.orders(world, host).size();
            cmd.set("#WeightOut.TextSpans", Message.translation(KEYS + "order.out").param("count", count).param("max", Markets.MAX_ORDERS));
            cmd.set("#WeightIn.TextSpans", mine == null ? Message.translation(KEYS + "order.mineNone")
                    : Message.translation(KEYS + "order.mine").param("item", TradePage.itemName(mine.item()))
                    .param("state", Message.translation(KEYS + "order.state." + (mine.ready() ? "ready" : "waiting"))));
            cmd.set("#WeightIn.Style.TextColor", mine != null && mine.ready() ? "#9ed36a" : "#e8a65a");
            cmd.set("#WeightBar.Value", Math.min(1f, count / (float) Markets.MAX_ORDERS));
            cmd.set("#PickRule.TextSpans", Message.translation(KEYS + "order.rule"));
        }
        cmd.set("#PickBack.Text", Message.translation(KEYS + "order.back"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PickBack", EventData.of("Action", "Back"));
        cmd.set("#PickClear.Text", Message.translation(KEYS + "order.cancel"));
        cmd.set("#PickClear.Visible", !consign && mine != null);
        if (!consign && mine != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PickClear", EventData.of("Action", "Clear"));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Event data) {
        if (data == null || data.action == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        Village host = Villages.registry(world).byId(villageId);
        String a = data.action;
        if (host == null || a.equals("Back")) {
            close();
            return;
        }
        if (a.startsWith("Grade")) {
            String f = a.substring("Grade".length());
            if (FILTERS.contains(f)) {
                filter = f;
            }
            rebuild();
            return;
        }
        if (a.equals("Clear")) {
            Markets.cancelOrder(world, host, playerRef.getUuid());
            tell(Message.translation(KEYS + "order.cancelled"), NotificationStyle.Default);
            rebuild();
            return;
        }
        if (!a.startsWith("Pick")) {
            return;
        }
        int i;
        try {
            i = Integer.parseInt(a.substring(4));
        } catch (NumberFormatException e) {
            return;
        }
        if (i < 0 || i >= shown.size()) {
            return;
        }
        String item = shown.get(i);
        if (consign) {
            tell(Markets.consign(world, store, ref, playerRef.getUuid(), host, item), NotificationStyle.Success);
            close();
            return;
        }
        Markets.Offer offer = null;
        for (Markets.Offer o : Markets.catalogue(store, host)) {
            if (o.item().equals(item)) {
                offer = o;
            }
        }
        if (offer == null) {
            return;
        }
        Message why = Markets.placeOrder(world, store, playerRef.getUuid(), host, offer);
        if (why != null) {
            tell(why, NotificationStyle.Warning);
            rebuild();
            return;
        }
        tell(Message.translation(KEYS + "order.placed").param("count", offer.count()).param("item", TradePage.itemName(item)), NotificationStyle.Success);
        close();
    }

    private void tell(Message m, NotificationStyle style) {
        playerRef.sendMessage(m);
        NotificationUtil.sendNotification(playerRef.getPacketHandler(), m, style);
    }
}
