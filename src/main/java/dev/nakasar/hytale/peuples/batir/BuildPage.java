package dev.nakasar.hytale.peuples.batir;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.annex.AnnexPage;
import dev.nakasar.hytale.peuples.annex.Annexes;
import dev.nakasar.hytale.peuples.annex.OuvragePage;
import dev.nakasar.hytale.peuples.batir.BuildCatalog.Entry;
import dev.nakasar.hytale.peuples.batir.BuildCatalog.Family;
import dev.nakasar.hytale.peuples.batir.BuildCatalog.Place;
import dev.nakasar.hytale.peuples.batir.BuildCatalog.Req;
import dev.nakasar.hytale.peuples.batir.BuildCatalog.Seek;
import dev.nakasar.hytale.peuples.batir.BuildCatalog.Stripe;
import dev.nakasar.hytale.peuples.request.BoardTabs;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.PlotPage;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.world.DomainPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L'onglet « Bâtir » du panneau du village (Kevin, 2026-11-21), qui remplace Emplacements, Avant-postes et Ouvrages :
 * une colonne des familles d'usage à gauche, les filtres « Où » et « Je cherche », une grille de huit cartes (ce que le
 * bâtiment donne, où il se bâtit, son coût) et la fiche du bâtiment choisi (produits, coût au dépôt, conditions, action).
 * Les anciennes pages restent, ouvertes depuis la fiche. Mise en page {@code BuildPage_<Peuple>.ui}
 * ({@code tools/generate_build_tab.py}) ; données {@link BuildCatalog}.
 */
public final class BuildPage extends InteractiveCustomUIPage<BuildPage.Event> {

    private static final String LAYOUT = "Pages/Nakasar/Peuples/BuildPage.ui";
    private static final String KEYS = BuildCatalog.KEYS;
    private static final List<String> THEMED = List.of("Kweebec", "Trork", "Feran", "Renonce", "Slothian");
    /** Tailles de tools/generate_build_tab.py. */
    private static final int CARDS = 8;
    private static final int CARD_OUTS = 4;
    private static final int CARD_COSTS = 3;
    private static final int DET_OUTS = 6;
    private static final int DET_MATS = 4;
    private static final int DET_REQS = 4;
    private static final int PLACES = Place.values().length;
    private static final int STRIPES = Stripe.values().length;
    private static final String OK = "#7ed957";
    private static final String MISSING = "#e8c46a";
    private static final String[] STRIPE_COLOURS = {"#7fc36a", "#e8c46a", "#d9a35a", "#e07468", "#7fa8d8", "#8a96a3"};

    /** Donnée d'un clic : {@code Fam<i>}, {@code Where<i>}, {@code Seek<i>}, {@code Card<i>}, pages, fiche, ou un onglet. */
    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .build();

        String action;
    }

    private final String villageId;
    private int family = -1;
    private int where = -1;
    private int seek = -1;
    private int page;
    private String selected;
    private List<Entry> shown = List.of();
    private Entry detail;

    public BuildPage(PlayerRef playerRef, Village village) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.villageId = village.id();
    }

    public static void open(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, Village village) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player != null && village != null) {
            player.getPageManager().openCustomPage(ref, store, new BuildPage(playerRef, village));
        }
    }

    /** Vrai si le village a quelque chose à montrer dans l'onglet (plan, avant-postes ou ouvrages). */
    public static boolean applies(Village village) {
        return village != null && (Plots.plots(village).length > 0 || !Annexes.catalog(village).isEmpty()
                || !Annexes.catalog(village, Workshop.Annex.INFRASTRUCTURE).isEmpty()
                || dev.nakasar.hytale.peuples.village.Chantiers.layout(village) != null);
    }

    static String layout(People people) {
        return people != null && THEMED.contains(people.id()) ? "Pages/Nakasar/Peuples/BuildPage_" + people.id() + ".ui" : LAYOUT;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Village village = Villages.registry(world).byId(villageId);
        cmd.append(layout(village == null ? null : village.people()));
        if (village == null) {
            cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + "empty"));
            return;
        }
        cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + "title").param("village", village.name()));
        List<Entry> all = BuildCatalog.of(world, store, ref, village);
        if (family < 0) {
            family = 0;
            for (Family f : Family.values()) {
                if (count(all, f, false) > 0) {
                    family = f.ordinal();
                    break;
                }
            }
        }
        rail(cmd, events, all);
        filters(cmd, events);
        shown = filter(all);
        int pages = Math.max(1, (shown.size() + CARDS - 1) / CARDS);
        page = Math.max(0, Math.min(page, pages - 1));
        detail = null;
        for (Entry e : shown) {
            if (e.key().equals(selected)) {
                detail = e;
            }
        }
        if (detail == null && !shown.isEmpty()) {
            detail = shown.get(page * CARDS < shown.size() ? page * CARDS : 0);
            selected = detail.key();
        }
        for (int i = 0; i < CARDS; i++) {
            int at = page * CARDS + i;
            card(cmd, events, i, at < shown.size() ? shown.get(at) : null);
        }
        cmd.set("#PageInfo.TextSpans", shown.isEmpty() ? Message.translation(KEYS + "empty")
                : Message.translation(KEYS + "page").param("page", page + 1).param("pages", pages).param("count", shown.size()));
        cmd.set("#PagePrev.Text", Message.translation(KEYS + "pagePrev"));
        cmd.set("#PageNext.Text", Message.translation(KEYS + "pageNext"));
        cmd.set("#PagePrev.Visible", page > 0);
        cmd.set("#PageNext.Visible", page < pages - 1);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PagePrev", EventData.of("Action", "PagePrev"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PageNext", EventData.of("Action", "PageNext"));
        detail(cmd, events, world, village);
        BoardTabs.fill(cmd, events, store, village);
    }

    // ---- Colonne des familles et filtres ----

    private void rail(UICommandBuilder cmd, UIEventBuilder events, List<Entry> all) {
        for (Family f : Family.values()) {
            String n = Integer.toString(f.ordinal());
            // Un objet cherché passe outre la famille : aucune famille ne paraît choisie.
            cmd.set("#FamOn" + n + ".Visible", seek < 0 && f.ordinal() == family);
            cmd.set("#FamIcon" + n + ".ItemId", f.icon);
            cmd.set("#FamName" + n + ".TextSpans", Message.translation(KEYS + "family." + f.key));
            cmd.set("#FamCount" + n + ".TextSpans", Message.translation(KEYS + "familyCount").param("built", count(all, f, true))
                    .param("total", count(all, f, false)));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Fam" + n, EventData.of("Action", "Fam" + n));
        }
    }

    private static int count(List<Entry> all, Family f, boolean built) {
        int n = 0;
        for (Entry e : all) {
            if (e.family() == f && (!built || e.stripe() == Stripe.BUILT)) {
                n++;
            }
        }
        return n;
    }

    private void filters(UICommandBuilder cmd, UIEventBuilder events) {
        cmd.set("#WhereLabel.TextSpans", Message.translation(KEYS + "where"));
        for (Place p : Place.values()) {
            String n = Integer.toString(p.ordinal());
            cmd.set("#WhereName" + n + ".TextSpans", Message.translation(KEYS + "place." + p.key));
            cmd.set("#WhereOn" + n + ".Visible", p.ordinal() == where);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Where" + n, EventData.of("Action", "Where" + n));
        }
        cmd.set("#SeekLabel.TextSpans", Message.translation(KEYS + "seek"));
        for (Seek s : Seek.values()) {
            String n = Integer.toString(s.ordinal());
            cmd.set("#SeekIcon" + n + ".ItemId", s.icon);
            cmd.set("#SeekName" + n + ".TextSpans", Message.translation(KEYS + "seek." + s.key));
            cmd.set("#SeekOn" + n + ".Visible", s.ordinal() == seek);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Seek" + n, EventData.of("Action", "Seek" + n));
        }
    }

    /** Cartes de la famille, filtrées par endroit et par objet cherché (un objet cherché passe outre la famille). */
    private List<Entry> filter(List<Entry> all) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (seek < 0 && e.family().ordinal() != family) {
                continue;
            }
            if (where >= 0 && e.place().ordinal() != where) {
                continue;
            }
            if (seek >= 0 && !Seek.values()[seek].matches(e)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    // ---- Cartes ----

    private void card(UICommandBuilder cmd, UIEventBuilder events, int i, Entry e) {
        String n = Integer.toString(i);
        cmd.set("#Card" + n + ".Visible", e != null);
        if (e == null) {
            return;
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Card" + n, EventData.of("Action", "Card" + n));
        for (int t = 0; t < STRIPES; t++) {
            String k = Integer.toString(i * STRIPES + t);
            cmd.set("#CardStripe" + k + ".Visible", t == e.stripe().ordinal());
        }
        cmd.set("#CardIcon" + n + ".ItemId", e.icon());
        cmd.set("#CardName" + n + ".TextSpans", e.name());
        for (int j = 0; j < CARD_OUTS; j++) {
            String k = Integer.toString(i * CARD_OUTS + j);
            boolean on = j < e.outputs().size();
            cmd.set("#CardOut" + k + ".Visible", on);
            if (on) {
                cmd.set("#CardOutIcon" + k + ".ItemId", e.outputs().get(j));
            }
        }
        cmd.set("#CardFx" + n + ".Visible", e.outputs().isEmpty() && e.fx() != null);
        cmd.set("#CardFx" + n + ".TextSpans", e.fx() == null ? Message.raw(" ") : Message.translation(KEYS + "fx." + e.fx()));
        for (Place p : Place.values()) {
            String k = Integer.toString(i * PLACES + p.ordinal());
            cmd.set("#CardPlace" + k + ".Visible", p == e.place());
        }
        cmd.set("#CardWhere" + n + ".TextSpans", e.where());
        for (int j = 0; j < CARD_COSTS; j++) {
            String k = Integer.toString(i * CARD_COSTS + j);
            boolean on = j < e.cost().size();
            cmd.set("#CardCost" + k + ".Visible", on);
            if (on) {
                VillageLayout.Material m = e.cost().get(j);
                cmd.set("#CardCostIcon" + k + ".ItemId", m.itemId());
                cmd.set("#CardCostCount" + k + ".TextSpans", Message.raw(Integer.toString(m.quantity())));
            }
        }
        cmd.set("#CardNote" + n + ".TextSpans", e.note() == null ? Message.raw(" ") : e.note());
        cmd.set("#CardState" + n + ".TextSpans", e.state());
        cmd.set("#CardState" + n + ".Style.TextColor", STRIPE_COLOURS[e.stripe().ordinal()]);
    }

    // ---- Fiche ----

    private void detail(UICommandBuilder cmd, UIEventBuilder events, World world, Village village) {
        Entry e = detail;
        cmd.set("#Detail.Visible", e != null);
        if (e == null) {
            return;
        }
        cmd.set("#DetName.TextSpans", e.name());
        for (Place p : Place.values()) {
            cmd.set("#DetPlace" + p.ordinal() + ".Visible", p == e.place());
        }
        cmd.set("#DetWhere.TextSpans", Message.translation(KEYS + "placeLong." + e.place().key));
        cmd.set("#DetLine.TextSpans", e.line());
        cmd.set("#DetOutHead.TextSpans", Message.translation(KEYS + (e.outputs().isEmpty() ? "brings" : "produces")));
        for (int k = 0; k < DET_OUTS; k++) {
            boolean on = k < e.outputs().size();
            cmd.set("#DetOut" + k + ".Visible", on);
            if (on) {
                cmd.set("#DetOutIcon" + k + ".ItemId", e.outputs().get(k));
            }
        }
        cmd.set("#DetCostHead.TextSpans", Message.translation(KEYS + (e.cost().isEmpty() ? "howTo" : "toBuild")));
        Map<String, Integer> deposit = Villages.depositContents(world, village);
        for (int k = 0; k < DET_MATS; k++) {
            boolean on = k < e.cost().size();
            cmd.set("#DetMat" + k + ".Visible", on);
            if (!on) {
                continue;
            }
            VillageLayout.Material m = e.cost().get(k);
            int have = deposit == null ? 0 : Math.min(m.quantity(), deposit.getOrDefault(m.itemId(), 0));
            boolean ok = have >= m.quantity() || e.stripe() == Stripe.BUILT;
            cmd.set("#DetMatIcon" + k + ".ItemId", m.itemId());
            cmd.set("#DetMatOk" + k + ".Visible", ok);
            cmd.set("#DetMatSlot" + k + ".Visible", !ok);
            cmd.set("#DetMatCount" + k + ".TextSpans", Message.translation("nakasar_peuples.boardPage.progress")
                    .param("have", ok ? m.quantity() : have).param("need", m.quantity()));
            cmd.set("#DetMatCount" + k + ".Style.TextColor", ok ? OK : MISSING);
        }
        cmd.set("#DetCostNote.Visible", e.costNote() != null);
        cmd.set("#DetCostNote.TextSpans", e.costNote() == null ? Message.raw(" ") : e.costNote());
        List<Req> reqs = e.reqs();
        for (int k = 0; k < DET_REQS; k++) {
            boolean on = k < reqs.size();
            cmd.set("#DetReq" + k + ".Visible", on);
            if (!on) {
                continue;
            }
            Req r = reqs.get(k);
            cmd.set("#DetReqOk" + k + ".Visible", Boolean.TRUE.equals(r.ok()));
            cmd.set("#DetReqNo" + k + ".Visible", Boolean.FALSE.equals(r.ok()));
            cmd.set("#DetReqText" + k + ".TextSpans", r.text());
        }
        cmd.set("#DetAction.Visible", e.action() != null);
        if (e.action() != null) {
            cmd.set("#DetAction.Text", Message.translation(KEYS + "action." + e.action()));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DetAction", EventData.of("Action", "DetAction"));
        }
    }

    // ---- Clics ----

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
        if (action.startsWith("Fam")) {
            int i = index(action, "Fam");
            if (i >= 0 && i < Family.values().length) {
                family = i;
                seek = -1;
                page = 0;
                selected = null;
            }
        } else if (action.startsWith("Where")) {
            int i = index(action, "Where");
            if (i >= 0 && i < PLACES) {
                where = where == i ? -1 : i;
                page = 0;
                selected = null;
            }
        } else if (action.startsWith("Seek")) {
            int i = index(action, "Seek");
            if (i >= 0 && i < Seek.values().length) {
                seek = seek == i ? -1 : i;
                page = 0;
                selected = null;
            }
        } else if (action.startsWith("Card")) {
            int i = page * CARDS + index(action, "Card");
            if (i >= page * CARDS && i < shown.size()) {
                selected = shown.get(i).key();
            }
        } else if ("PagePrev".equals(action)) {
            page = Math.max(0, page - 1);
            selected = null;
        } else if ("PageNext".equals(action)) {
            page++;
            selected = null;
        } else if ("DetAction".equals(action) && detail != null) {
            if (act(world, store, ref, village, detail)) {
                return;
            }
        }
        rebuild();
    }

    /** Action de la fiche ; vrai si une autre page s'est ouverte. */
    private boolean act(World world, Store<EntityStore> store, Ref<EntityStore> ref, Village village, Entry e) {
        String target = e.target();
        switch (e.action()) {
            case "choose" -> {
                int at = target == null ? -1 : target.indexOf('@');
                if (at < 0) {
                    return false;
                }
                String building = target.substring(0, at);
                String plotId = target.substring(at + 1);
                for (VillageLayout.Plot p : Plots.plots(village)) {
                    if (p.id().equals(plotId)) {
                        if (Plots.choose(world, store, ref, playerRef, village, p, building)) {
                            playerRef.sendMessage(Message.translation(KEYS + "chosen").param("building", e.name()).param("plot", plotId));
                        }
                        PlotPage.open(store, ref, playerRef, village, plotId);
                        return true;
                    }
                }
                playerRef.sendMessage(Message.translation(KEYS + "noPlot"));
                return false;
            }
            case "plots" -> {
                PlotPage.open(store, ref, playerRef, village, target);
                return true;
            }
            case "grant", "regive" -> {
                Annexes.grant(world, store, ref, playerRef, village, e.workshop());
                return false;
            }
            case "outposts" -> {
                AnnexPage.open(store, ref, playerRef, village);
                return true;
            }
            case "works" -> {
                OuvragePage.open(store, ref, playerRef, village);
                return true;
            }
            case "domain" -> {
                DomainPage.open(store, ref, playerRef, village);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static int index(String action, String prefix) {
        try {
            return Integer.parseInt(action.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
