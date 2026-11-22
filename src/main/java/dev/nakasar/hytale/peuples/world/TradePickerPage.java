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
import dev.nakasar.hytale.peuples.ui.ButtonText;
import java.util.List;

/**
 * Choix de la marchandise d'une case de route (refonte des écrans de commerce, Kevin, 21 novembre 2026) : à la place
 * du clic qui faisait défiler le catalogue case par case, la grille de ce que l'expéditeur peut envoyer (icône,
 * chargement par caravane, classe en points), filtrable par classe, avec le poids envoyé et reçu de la route et la
 * règle du crédit. Un clic pose la marchandise et revient à l'onglet Commerce.
 * Mise en page par peuple ({@code TradePickerPage_<Peuple>.ui}, {@code tools/generate_trade.py}).
 */
public final class TradePickerPage extends InteractiveCustomUIPage<TradePickerPage.Event> {

    /** tools/generate_trade.py PICK, PICK_FILTERS. */
    private static final int PICK = 12;
    private static final List<String> FILTERS = List.of("All", "Common", "Fine", "Luxury");
    private static final List<String> THEMED = List.of("Kweebec", "Trork", "Feran", "Renonce", "Slothian");
    private static final String KEYS = TradeRoutes.KEYS + "picker.";

    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .build();

        String action;
    }

    private final String villageId;
    private final String routeKey;
    private final boolean export;
    private final int slot;
    private String filter = "All";
    private final List<String> shown = new ArrayList<>();

    public TradePickerPage(PlayerRef playerRef, Village village, String routeKey, boolean export, int slot) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.villageId = village.id();
        this.routeKey = routeKey;
        this.export = export;
        this.slot = slot;
    }

    private static String layout(People people) {
        return people != null && THEMED.contains(people.id()) ? "Pages/Nakasar/Peuples/TradePickerPage_" + people.id() + ".ui"
                : "Pages/Nakasar/Peuples/TradePickerPage.ui";
    }

    /** Ce que l'expéditeur peut envoyer sur la route au destinataire. */
    private static List<String> catalogue(World world, Village sender, Village receiver, TradeRoutes.Route r, long day) {
        List<String> items = new ArrayList<>();
        for (String item : TradeCatalogue.of(world, sender, r.key(), day).keySet()) {
            if (TradeGoods.allowed(receiver.people(), item)) {
                items.add(item);
            }
        }
        return items;
    }

    private static boolean matches(String filter, String item) {
        return switch (filter) {
            case "Common" -> TradeGoods.grade(item) == TradeGoods.Grade.COMMON;
            case "Fine" -> TradeGoods.grade(item) == TradeGoods.Grade.FINE;
            case "Luxury" -> TradeGoods.grade(item) == TradeGoods.Grade.LUXURY;
            default -> true;
        };
    }

    private static String dots(String item) {
        return "*".repeat(TradeGoods.grade(item).weight());
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Village board = Villages.registry(world).byId(villageId);
        cmd.append(layout(board == null ? null : board.people()));
        TradeRoutes.Route r = TradeRoutes.get(world, routeKey);
        Village other = r == null || board == null ? null : TradeRoutes.village(world, r.other(board.id()));
        if (board == null || r == null || other == null) {
            cmd.set("#PageTitle.TextSpans", Message.translation(WorldPage.KEYS + "orphan"));
            return;
        }
        Village sender = export ? board : other;
        Village receiver = export ? other : board;
        long day = GameClock.day(store);
        cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + (export ? "titleOut" : "titleIn"))
                .param("partner", TradeRoutes.name(world, other)).param("case", slot + 1));
        List<String> all = catalogue(world, sender, receiver, r, day);
        for (String grade : FILTERS) {
            Message text = grade.equals("All") ? Message.translation(KEYS + "All").param("count", all.size()) : Message.translation(KEYS + grade);
            boolean on = grade.equals(filter);
            cmd.set("#Grade" + grade + "On.Visible", on);
            cmd.set("#Grade" + grade + "Button.Visible", !on);
            cmd.set("#Grade" + grade + "Label.TextSpans", text);
            if (!on) {
                cmd.set("#Grade" + grade + "Button.Text", ButtonText.of(playerRef, text));
                events.addEventBinding(CustomUIEventBindingType.Activating, "#Grade" + grade + "Button", EventData.of("Action", "Grade" + grade));
            }
        }
        cmd.set("#PickHint.TextSpans", Message.translation(KEYS + "hint").param("village", TradeRoutes.name(world, sender)));
        List<String> cases = r.exportsOf(sender.id());
        String current = slot < cases.size() ? cases.get(slot) : null;
        shown.clear();
        for (String item : all) {
            if (matches(filter, item) && shown.size() < PICK) {
                shown.add(item);
            }
        }
        cmd.set("#NoPick.Visible", all.isEmpty());
        if (all.isEmpty()) {
            cmd.set("#NoPick.TextSpans", Message.translation(KEYS + "none").param("village", TradeRoutes.name(world, sender)));
        }
        for (int i = 0; i < PICK; i++) {
            String id = "#Pick" + i;
            boolean on = i < shown.size();
            cmd.set(id + ".Visible", on);
            if (!on) {
                continue;
            }
            String item = shown.get(i);
            boolean used = cases.contains(item) && !item.equals(current);
            cmd.set(id + "Icon.ItemId", item);
            cmd.set(id + "Name.TextSpans", TradePage.itemName(item));
            cmd.set(id + "Sub.TextSpans", item.equals(current) ? Message.translation(KEYS + "current")
                    : used ? Message.translation(KEYS + "used")
                    : Message.translation(KEYS + "sub").param("count", TradeGoods.grade(item).load(r.level())));
            cmd.set(id + "Grade.TextSpans", Message.raw(dots(item)));
            cmd.set(id + ".Disabled", used || item.equals(current));
            if (!used && !item.equals(current)) {
                events.addEventBinding(CustomUIEventBindingType.Activating, id, EventData.of("Action", "Pick" + i));
            }
        }
        // Poids de la route vu du village du panneau.
        int out = TradeRoutes.weight(r.exportsOf(board.id()));
        int in = TradeRoutes.weight(r.importsOf(board.id()));
        int deficit = Math.max(0, in - out);
        cmd.set("#WeightOut.TextSpans", Message.translation(KEYS + "out").param("weight", out));
        cmd.set("#WeightIn.TextSpans", deficit <= 0 ? Message.translation(KEYS + "inOk").param("weight", in)
                : Message.translation(KEYS + "inShort").param("weight", in).param("deficit", deficit));
        cmd.set("#WeightIn.Style.TextColor", deficit <= 0 ? "#9ed36a" : "#e8a65a");
        cmd.set("#WeightBar.Value", in <= 0 ? 1f : Math.min(1f, out / (float) in));
        cmd.set("#PickRule.TextSpans", Message.translation(KEYS + "rule").param("credit", dev.nakasar.hytale.peuples.workshop.Projects.creditPerFavour(board)));
        cmd.set("#PickBack.Text", Message.translation(KEYS + "back"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PickBack", EventData.of("Action", "Back"));
        cmd.set("#PickClear.Text", Message.translation(KEYS + "clear"));
        cmd.set("#PickClear.Visible", current != null);
        if (current != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PickClear", EventData.of("Action", "Clear"));
        }
    }

    private void back(Ref<EntityStore> ref, Store<EntityStore> store, Village board) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new TradePage(playerRef, board));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Event data) {
        if (data == null || data.action == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        Village board = Villages.registry(world).byId(villageId);
        TradeRoutes.Route r = TradeRoutes.get(world, routeKey);
        if (board == null) {
            close();
            return;
        }
        String a = data.action;
        if (a.startsWith("Grade")) {
            String f = a.substring("Grade".length());
            if (FILTERS.contains(f)) {
                filter = f;
            }
            rebuild();
            return;
        }
        if (a.equals("Back") || r == null) {
            back(ref, store, board);
            return;
        }
        Village other = TradeRoutes.village(world, r.other(board.id()));
        if (other == null) {
            back(ref, store, board);
            return;
        }
        Village sender = export ? board : other;
        String item = null;
        if (a.startsWith("Pick")) {
            int i;
            try {
                i = Integer.parseInt(a.substring(4));
            } catch (NumberFormatException e) {
                return;
            }
            if (i < 0 || i >= shown.size()) {
                return;
            }
            item = shown.get(i);
        } else if (!a.equals("Clear")) {
            return;
        }
        Message why = TradeRoutes.setCase(world, store, ref, board, r, sender, slot, item);
        if (why != null) {
            playerRef.sendMessage(why);
            NotificationUtil.sendNotification(playerRef.getPacketHandler(), why, NotificationStyle.Warning);
            rebuild();
            return;
        }
        back(ref, store, board);
    }
}
