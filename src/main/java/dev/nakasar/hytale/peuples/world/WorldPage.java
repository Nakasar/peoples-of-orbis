package dev.nakasar.hytale.peuples.world;

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
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.annex.Routes;
import dev.nakasar.hytale.peuples.raid.Raids;
import dev.nakasar.hytale.peuples.request.BoardTabs;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * L'onglet « Monde » du panneau du village, refondu (Kevin, 21 novembre 2026 : canvas « Onglet Monde repensé »
 * https://claude.ai/artifact/LAQ1qPd4sBDPsY4Vviafoa, « on implémente ») : ce que le monde vivant fait autour du village.
 * <ul>
 *   <li>en haut, quatre tuiles : voisins (par relation), prochain événement ou ce qui se passe, voyageurs du village,
 *   campements et troupeau ;</li>
 *   <li>à gauche, la <b>carte des alentours</b> qu'on clique : tous les voisins à moins de 2400 blocs (une forme par
 *   relation, le nom tourné vers l'extérieur), les routes balisées en pointillés, voyageurs, auberges, campements,
 *   troupeau, escarmouche ; puis la liste des voisins (un bouton chacun, le choisi encadré) ;</li>
 *   <li>à droite, la <b>fiche du voisin choisi</b> : relation sur une jauge à 20 crans et ce vers quoi elle tend, route,
 *   auberge, trêve, dernière escarmouche, voyages d'ici, voyageurs sur la liaison, ce que le joueur peut faire, et
 *   « Marquer sur la carte » ({@link WorldWaypoints}) ; puis <b>Ce qui vient</b> (ce qui se passe maintenant en tête,
 *   l'agenda, chaque ligne dit où ; ce qui manque, avec un bouton Bâtir) ;</li>
 *   <li>en bas, une rumeur du moment ({@link Rumours}).</li>
 * </ul>
 * Mise en page par peuple ({@code WorldPage_<Peuple>.ui}, {@code tools/generate_world_tab.py}) : les tailles de la
 * carte sont celles de MAP_* là-bas.
 */
public final class WorldPage extends InteractiveCustomUIPage<WorldPage.Event> {

    /** Textes communs au panneau (onglets, heure, titre) : bloc du monde vivant. */
    public static final String KEYS = WorldLife.KEYS + "page.";
    /** Textes de l'onglet refondu (tools/generate_world_tab.py). */
    public static final String TAB_KEYS = WorldLife.KEYS + "tab.";
    private static final String LAYOUT = "Pages/Nakasar/Peuples/WorldPage.ui";
    private static final List<String> THEMED = List.of("Kweebec", "Trork", "Feran", "Renonce", "Slothian");

    // ---- Tailles de tools/generate_world_tab.py ----
    private static final int MAP_W = 540;
    private static final int MAP_CX = 270;
    private static final int MAP_CY = 150;
    private static final int MAP_R = 120;
    private static final int MARKS = 10;
    private static final int MARK = 30;
    private static final int NAME_W = 150;
    private static final int TRAIL_DOTS = 7;
    private static final int POIS = 12;
    private static final int POI = 18;
    private static final int ROWS = 16;
    private static final int SEGMENTS = 20;
    private static final int CHIPS = 4;
    private static final int TRACKS = 2;
    private static final int AGENDA = 6;
    private static final int STATS = 4;

    private static final String[] STATES = {"Ally", "Friendly", "Neutral", "Tense", "Rival"};
    private static final String[] STATE_KEYS = {"ally", "friendly", "neutral", "tense", "rival"};
    private static final String[] STATE_COLORS = {"#9ee37d", "#5fb8a5", "#b3bfcc", "#e9a24c", "#ef6f5e"};
    private static final String[] POI_KINDS = {"Camp", "Herd", "Inn", "Caravan", "Merchant", "Pilgrim", "Messenger", "Skirmish"};
    private static final String[] FLAGS = {"Road", "Inn", "Truce", "Trav", "Fight"};
    private static final String[] CHIP_ICONS = {"Road", "NoRoad", "Inn", "Truce", "Swords", "Fight"};
    private static final String[] HINT_ICONS = {"Swords", "Truce", "Shield", "Inn", "Road", "Scroll", "Cart"};
    private static final String[] AGENDA_ICONS = {"Swords", "Flame", "Wheat", "Fair", "Herd"};
    private static final String[] TRACK_ICONS = {"Cart", "Bag", "Staff", "Scroll"};
    private static final String GOLD = "#e8c46a";
    private static final String RED_TEXT = "#ffb4a8";
    /** Couleur du peuple pour son nom dans la liste et la fiche. */
    private static final Map<String, String> PEOPLE_COLORS = Map.of("Kweebec", "#b9e59a", "Trork", "#f0b48e", "Feran", "#f0d08a",
            "Renonce", "#a9dcef", "Slothian", "#cdb8f0");

    /** Donnée d'un clic : {@code Tab<X>}, {@code Nb<i>}, {@code Mk<i>}, {@code Mark}. */
    public static final class Event {
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                .add()
                .build();

        String action;
    }

    private final String villageId;
    /** Voisin dont la fiche est ouverte (id de village), ou null : le plus pressant. */
    private String selected;

    public WorldPage(PlayerRef playerRef, Village village) {
        super(playerRef, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.villageId = village.id();
    }

    /** Ouvre l'onglet Monde du village (thread du monde). */
    public static void open(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, Village village) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new WorldPage(playerRef, village));
        }
    }

    static String layout(People people) {
        return people != null && THEMED.contains(people.id()) ? "Pages/Nakasar/Peuples/WorldPage_" + people.id() + ".ui" : LAYOUT;
    }

    /** Les liaisons du village, de la plus courte à la plus longue. */
    private static List<Links.Link> links(World world, Village village) {
        List<Links.Link> links = new ArrayList<>(Links.of(world, village));
        links.sort(Comparator.comparingDouble(Links.Link::length));
        return links;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        Village village = Villages.registry(world).byId(villageId);
        cmd.append(layout(village == null ? null : village.people()));
        if (village == null) {
            cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + "orphan"));
            return;
        }
        cmd.set("#PageTitle.TextSpans", Message.translation(KEYS + "title").param("village", village.name()));
        BoardTabs.fill(cmd, events, store, village);
        long day = GameClock.day(store);
        List<Links.Link> links = links(world, village);
        Links.Link sel = pick(links);
        List<Entry> agenda = agenda(world, store, village, links, day);
        stats(cmd, world, village, links, agenda);
        map(cmd, events, world, village, links, sel);
        list(cmd, events, world, village, links, sel, day);
        sheet(cmd, events, world, village, links, sel, day);
        agendaRows(cmd, events, agenda, village);
        rumour(cmd, world, store, ref, village, day);
    }

    /** La liaison choisie : celle du joueur, sinon une escarmouche en cours, sinon la plus proche. */
    private Links.Link pick(List<Links.Link> links) {
        if (links.isEmpty()) {
            return null;
        }
        for (Links.Link l : links) {
            if (l.other(villageId).id().equals(selected)) {
                return l;
            }
        }
        for (Links.Link l : links) {
            if (Relations.fighting(l.key())) {
                return l;
            }
        }
        return links.get(0);
    }

    // ---- Tuiles ----

    private static void stats(UICommandBuilder cmd, World world, Village v, List<Links.Link> links, List<Entry> agenda) {
        int[] counts = new int[STATES.length];
        for (Links.Link l : links) {
            counts[stateIndex(Relations.value(world, l.a(), l.b()))]++;
        }
        // Voisins
        cmd.set("#StLabel0.TextSpans", Message.translation(TAB_KEYS + "st.neighbours"));
        cmd.set("#StValue0.TextSpans", Message.translation(TAB_KEYS + "st.neighboursValue").param("count", links.size()));
        List<Message> parts = new ArrayList<>();
        for (int s : new int[] {0, 1, 3, 4}) {
            if (counts[s] > 0) {
                parts.add(Message.translation(TAB_KEYS + "st.count." + STATE_KEYS[s]).param("count", counts[s]));
            }
        }
        cmd.set("#StSub0.TextSpans", links.isEmpty() ? Message.translation(TAB_KEYS + "st.none")
                : parts.isEmpty() ? Message.translation(TAB_KEYS + "st.count.neutral") : join(parts));
        // Prochain événement, ou ce qui se passe
        Entry first = agenda.isEmpty() ? null : agenda.get(0);
        boolean now = first != null && first.tone() >= Entry.GOLD;
        cmd.set("#StLabel1.TextSpans", Message.translation(TAB_KEYS + (now ? "st.now" : "st.next")));
        if (first == null || first.days() == Long.MAX_VALUE) {
            cmd.set("#StValue1.TextSpans", Message.translation(TAB_KEYS + "st.nothing"));
            cmd.set("#StSub1.TextSpans", Message.raw(" "));
            cmd.set("#StWarn1.Visible", false);
        } else {
            cmd.set("#StValue1.TextSpans", first.shortName());
            cmd.set("#StValue1.Style.TextColor", first.tone() == Entry.RED || first.tone() == Entry.RED_LATER ? "#ef6f5e" : GOLD);
            cmd.set("#StSub1.TextSpans", first.place() == null ? first.when() : join(List.of(first.when(), first.place())));
            cmd.set("#StWarn1.Visible", first.tone() == Entry.RED);
        }
        // Sur les routes
        int out = 0;
        int in = 0;
        for (Travellers.Traveller t : Travellers.all(world)) {
            if (v.id().equals(t.from())) {
                out++;
            } else if (v.id().equals(t.to())) {
                in++;
            }
        }
        cmd.set("#StLabel2.TextSpans", Message.translation(TAB_KEYS + "st.roads"));
        cmd.set("#StValue2.TextSpans", Message.translation(TAB_KEYS + "st.roadsValue").param("count", out + in));
        cmd.set("#StValue2.Style.TextColor", out + in > 0 ? GOLD : "#e6edf3");
        cmd.set("#StSub2.TextSpans", out + in == 0 ? Message.translation(TAB_KEYS + "st.roadsNone")
                : Message.translation(TAB_KEYS + "st.roadsSub").param("out", out).param("in", in));
        // Autour du village
        int camps = 0;
        for (Camps.Camp c : Camps.all(world)) {
            if (v.id().equals(c.village())) {
                camps++;
            }
        }
        cmd.set("#StLabel3.TextSpans", Message.translation(TAB_KEYS + "st.around"));
        cmd.set("#StValue3.TextSpans", Message.translation(TAB_KEYS + "st.aroundValue").param("count", camps));
        double[] herd = Calendar.herdAt();
        cmd.set("#StSub3.TextSpans", herd != null && distance(v, herd[0], herd[1]) <= Links.MAX_LENGTH
                ? Message.translation(TAB_KEYS + "st.herd").param("distance", distance(v, herd[0], herd[1]))
                : Message.translation(TAB_KEYS + "st.noHerd"));
        for (int i = 0; i < STATS; i++) {
            if (i != 1) {
                cmd.set("#StWarn" + i + ".Visible", false);
            }
        }
    }

    // ---- Carte ----

    private static void map(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, List<Links.Link> links,
                            Links.Link sel) {
        cmd.set("#MapTitle.TextSpans", Message.translation(TAB_KEYS + "map.title"));
        cmd.set("#MapHint.TextSpans", Message.translation(TAB_KEYS + "map.hint"));
        for (int s = 0; s < STATES.length; s++) {
            cmd.set("#Lg" + STATES[s] + ".TextSpans", Message.translation(TAB_KEYS + "state." + STATE_KEYS[s]));
        }
        cmd.set("#LgCamp.TextSpans", Message.translation(TAB_KEYS + "legend.camp"));
        cmd.set("#LgHerd.TextSpans", Message.translation(TAB_KEYS + "legend.herd"));
        cmd.set("#LgInn.TextSpans", Message.translation(TAB_KEYS + "legend.inn"));
        cmd.set("#LgTrav.TextSpans", Message.translation(TAB_KEYS + "legend.traveller"));

        int dot = 0;
        for (int i = 0; i < MARKS; i++) {
            if (i >= links.size()) {
                cmd.set("#Mk" + i + ".Visible", false);
                cmd.set("#MkName" + i + ".Visible", false);
                continue;
            }
            Links.Link l = links.get(i);
            Village other = l.other(v.id());
            double[] p = point(v, other.x(), other.z());
            boolean chosen = l == sel;
            int s = stateIndex(Relations.value(world, l.a(), l.b()));
            place(cmd, "#Mk" + i, p[0] - MARK / 2.0, p[1] - MARK / 2.0, MARK, MARK);
            cmd.set("#MkHalo" + i + ".Visible", chosen);
            for (int k = 0; k < STATES.length; k++) {
                cmd.set("#Mk" + STATES[k] + i + ".Visible", k == s);
            }
            cmd.set("#MkBtn" + i + ".TooltipTextSpans", Message.raw(other.name()));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MkBtn" + i, EventData.of("Action", "Mk" + i));
            tag(cmd, "#MkName" + i, "#MkNameR" + i, "#MkNameL" + i, p, MARK / 2.0 + 1, 16, Message.raw(other.name()));
            cmd.set("#MkNameR" + i + ".Style.TextColor", chosen ? GOLD : "#e6edf3");
            cmd.set("#MkNameL" + i + ".Style.TextColor", chosen ? GOLD : "#e6edf3");
            // Pointillés : route balisée, ou chemin vers le voisin choisi.
            boolean road = Routes.gap(world, v.x() + 0.5, v.z() + 0.5, other.x() + 0.5, other.z() + 0.5) <= 0;
            if (road || chosen) {
                for (int k = 1; k <= TRAIL_DOTS && dot < MARKS * TRAIL_DOTS; k++) {
                    double f = k / (TRAIL_DOTS + 1.0);
                    place(cmd, "#Tp" + dot, MAP_CX + (p[0] - MAP_CX) * f - 2, MAP_CY + (p[1] - MAP_CY) * f - 2, 4, 4);
                    cmd.set("#Tp" + dot + ".Visible", true);
                    cmd.set("#TpOn" + dot + ".Visible", chosen);
                    cmd.set("#TpOff" + dot + ".Visible", !chosen);
                    dot++;
                }
            }
        }
        for (; dot < MARKS * TRAIL_DOTS; dot++) {
            cmd.set("#Tp" + dot + ".Visible", false);
        }

        // Puces : escarmouches, voyageurs du village, campements, troupeau, auberges.
        List<Object[]> pois = new ArrayList<>();
        for (Links.Link l : links) {
            double[] at = Relations.fightAt(l.key());
            if (at != null) {
                pois.add(new Object[] {"Skirmish", at[0], at[1], Message.translation(TAB_KEYS + "poi.skirmish")});
            }
        }
        for (Travellers.Traveller t : Travellers.all(world)) {
            boolean leaving = v.id().equals(t.from());
            if (!leaving && !v.id().equals(t.to())) {
                continue;
            }
            Village from = Villages.registry(world).byId(t.from());
            Village to = Villages.registry(world).byId(t.to());
            if (from == null || to == null) {
                continue;
            }
            double length = Math.max(1, Links.path(world, from, to).length());
            double f = Math.min(1, t.at / length);
            pois.add(new Object[] {travellerPoi(t.kind()), from.x() + (to.x() - from.x()) * f, from.z() + (to.z() - from.z()) * f, null});
        }
        for (Camps.Camp c : Camps.all(world)) {
            if (v.id().equals(c.village())) {
                pois.add(new Object[] {"Camp", (double) c.x(), (double) c.z(), Message.translation(Camps.KEYS + "type." + c.type().key())});
            }
        }
        double[] herd = Calendar.herdAt();
        if (herd != null && distance(v, herd[0], herd[1]) <= Links.MAX_LENGTH) {
            pois.add(new Object[] {"Herd", herd[0], herd[1], Message.translation(KEYS + "compass.herd")});
        }
        for (Links.Link l : links) {
            Inns.Inn inn = Inns.get(world, l.key());
            if (inn != null && inn.open()) {
                pois.add(new Object[] {"Inn", (double) inn.x(), (double) inn.z(), null});
            }
        }
        int n = 0;
        for (Object[] o : pois) {
            if (n >= POIS) {
                break;
            }
            double[] p = point(v, (Double) o[1], (Double) o[2]);
            place(cmd, "#Po" + n, p[0] - POI / 2.0, p[1] - POI / 2.0, POI, POI);
            cmd.set("#Po" + n + ".Visible", true);
            for (String k : POI_KINDS) {
                cmd.set("#Po" + k + n + ".Visible", k.equals(o[0]));
            }
            if (o[3] instanceof Message tagText) {
                tag(cmd, "#PoTag" + n, "#PoTagR" + n, "#PoTagL" + n, p, POI / 2.0 + 2, 14, tagText);
            } else {
                cmd.set("#PoTag" + n + ".Visible", false);
            }
            n++;
        }
        for (; n < POIS; n++) {
            cmd.set("#Po" + n + ".Visible", false);
            cmd.set("#PoTag" + n + ".Visible", false);
        }
    }

    /** Pastille de carte et picto de suivi de chaque sorte de voyageur (textures du tableau, pas des objets). */
    private static final Map<Travellers.Kind, String[]> TRAVELLER_ICONS = Map.of(
            Travellers.Kind.CARAVAN, new String[] {"Caravan", "Cart"},
            Travellers.Kind.MERCHANT, new String[] {"Merchant", "Bag"},
            Travellers.Kind.PILGRIM, new String[] {"Pilgrim", "Staff"},
            Travellers.Kind.MESSENGER, new String[] {"Messenger", "Scroll"});

    private static String travellerPoi(Travellers.Kind kind) {
        return TRAVELLER_ICONS.get(kind)[0];
    }

    private static String trackIcon(Travellers.Kind kind) {
        return TRAVELLER_ICONS.get(kind)[1];
    }

    /** Point de la carte d'un lieu (est à droite, sud en bas : Requests.direction, +z = sud). */
    private static double[] point(Village v, double x, double z) {
        double dx = x - v.x();
        double dz = z - v.z();
        double d = Math.hypot(dx, dz);
        double r = radius(d);
        return new double[] {MAP_CX + (d < 1 ? 0 : r * dx / d), MAP_CY + (d < 1 ? 0 : r * dz / d)};
    }

    /** Rayon sur la carte : 1000 blocs aux trois quarts du bord, puis jusqu'au bord à 2400 blocs (Links.MAX_LENGTH). */
    static double radius(double blocks) {
        if (blocks <= 1000) {
            return MAP_R * 0.75 * blocks / 1000.0;
        }
        double t = Math.min(1.0, (blocks - 1000) / (Links.MAX_LENGTH - 1000.0));
        return MAP_R * (0.75 + 0.25 * t);
    }

    /** Étiquette d'un point, tournée vers l'extérieur de la carte (à droite à l'est, à gauche à l'ouest). */
    private static void tag(UICommandBuilder cmd, String box, String right, String left, double[] p, double gap, int height, Message text) {
        boolean east = p[0] >= MAP_CX - 2;
        double x = east ? p[0] + gap : p[0] - gap - NAME_W;
        x = Math.max(2, Math.min(MAP_W - NAME_W - 2, x));
        place(cmd, box, x, p[1] - height / 2.0, NAME_W, height);
        cmd.set(box + ".Visible", true);
        cmd.set(right + ".Visible", east);
        cmd.set(left + ".Visible", !east);
        cmd.set((east ? right : left) + ".TextSpans", text);
    }

    private static void place(UICommandBuilder cmd, String id, double left, double top, int width, int height) {
        Anchor a = new Anchor();
        a.setLeft(Value.of((int) Math.round(left)));
        a.setTop(Value.of((int) Math.round(top)));
        a.setWidth(Value.of(width));
        a.setHeight(Value.of(height));
        cmd.setObject(id + ".Anchor", a);
    }

    // ---- Liste ----

    private static void list(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, List<Links.Link> links,
                             Links.Link sel, long day) {
        cmd.set("#NbTitle.TextSpans", Message.translation(TAB_KEYS + "list.title").param("count", links.size()));
        cmd.set("#NbHint.TextSpans", Message.translation(TAB_KEYS + "list.hint"));
        cmd.set("#NbNone.Visible", links.isEmpty());
        if (links.isEmpty()) {
            cmd.set("#NbNone.TextSpans", Message.translation(KEYS + "neighbours.none"));
        }
        cmd.set("#NbMore.Visible", links.size() > ROWS);
        if (links.size() > ROWS) {
            cmd.set("#NbMore.TextSpans", Message.translation(TAB_KEYS + "list.more").param("count", links.size() - ROWS));
        }
        for (int i = 0; i < ROWS; i++) {
            if (i >= links.size()) {
                cmd.set("#Nb" + i + ".Visible", false);
                continue;
            }
            Links.Link l = links.get(i);
            Village other = l.other(v.id());
            int s = stateIndex(Relations.value(world, l.a(), l.b()));
            cmd.set("#Nb" + i + ".Visible", true);
            cmd.set("#NbOn" + i + ".Visible", l == sel);
            for (int k = 0; k < STATES.length; k++) {
                cmd.set("#Nb" + STATES[k] + i + ".Visible", k == s);
            }
            cmd.set("#NbName" + i + ".TextSpans", Message.raw(other.name()));
            cmd.set("#NbPeople" + i + ".TextSpans", Message.translation(other.people().nameKey()));
            cmd.set("#NbPeople" + i + ".Style.TextColor", PEOPLE_COLORS.getOrDefault(other.people().id(), "#c9d1d9"));
            boolean[] flags = flags(world, v, l, day);
            for (int k = 0; k < FLAGS.length; k++) {
                cmd.set("#NbFlag" + FLAGS[k] + i + ".Visible", flags[k]);
            }
            cmd.set("#NbDist" + i + ".TextSpans", Message.translation(TAB_KEYS + "list.dist")
                    .param("distance", (int) Math.round(l.length())).param("direction", direction(v, other.x(), other.z())));
            cmd.set("#NbState" + i + ".TextSpans", Message.translation(TAB_KEYS + "state." + STATE_KEYS[s]));
            cmd.set("#NbState" + i + ".Style.TextColor", STATE_COLORS[s]);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Nb" + i, EventData.of("Action", "Nb" + i));
        }
    }

    /** Pictos d'une ligne : route balisée, auberge ouverte, trêve, voyageur en route, escarmouche en cours. */
    private static boolean[] flags(World world, Village v, Links.Link l, long day) {
        Village other = l.other(v.id());
        boolean road = Routes.gap(world, v.x() + 0.5, v.z() + 0.5, other.x() + 0.5, other.z() + 0.5) <= 0;
        Inns.Inn inn = Inns.get(world, l.key());
        boolean truce = Relations.truce(world, l.a(), l.b(), day);
        boolean trav = false;
        for (Travellers.Traveller t : Travellers.all(world)) {
            if (Links.key(t.from(), t.to()).equals(l.key())) {
                trav = true;
                break;
            }
        }
        return new boolean[] {road, inn != null && inn.open(), truce, trav, Relations.fighting(l.key())};
    }

    // ---- Fiche ----

    private void sheet(UICommandBuilder cmd, UIEventBuilder events, World world, Village v, List<Links.Link> links,
                       Links.Link l, long day) {
        cmd.set("#ShNone.Visible", l == null);
        cmd.set("#ShBody.Visible", l != null);
        if (l == null) {
            cmd.set("#ShNone.TextSpans", Message.translation(TAB_KEYS + "sheet.none"));
            return;
        }
        Village other = l.other(v.id());
        int value = Relations.value(world, l.a(), l.b());
        int s = stateIndex(value);
        for (int k = 0; k < STATES.length; k++) {
            cmd.set("#Sh" + STATES[k] + ".Visible", k == s);
        }
        cmd.set("#ShName.TextSpans", Message.raw(other.name()));
        cmd.set("#ShPeople.TextSpans", Message.translation(other.people().nameKey()));
        cmd.set("#ShPeople.Style.TextColor", PEOPLE_COLORS.getOrDefault(other.people().id(), "#c9d1d9"));
        boolean marked = other.id().equals(WorldWaypoints.of(world, playerRef.getUuid()));
        cmd.set("#ShMarkText.TextSpans", Message.translation(TAB_KEYS + (marked ? "sheet.unmark" : "sheet.mark")));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ShMark", EventData.of("Action", "Mark"));

        boolean road = Routes.gap(world, v.x() + 0.5, v.z() + 0.5, other.x() + 0.5, other.z() + 0.5) <= 0;
        int pathLength = (int) Math.round(Links.path(world, v, other).length());
        Message dir = direction(v, other.x(), other.z());
        cmd.set("#ShWhere.TextSpans", road
                ? Message.translation(TAB_KEYS + "sheet.where").param("distance", (int) Math.round(l.length())).param("direction", dir)
                : Message.translation(TAB_KEYS + "sheet.whereLand").param("distance", (int) Math.round(l.length())).param("direction", dir)
                .param("path", pathLength));

        // Relation : état, valeur, jauge, ce vers quoi elle tend.
        cmd.set("#ShRelLabel.TextSpans", Message.translation(TAB_KEYS + "sheet.relation"));
        cmd.set("#ShRelState.TextSpans", Message.translation(TAB_KEYS + "state." + STATE_KEYS[s]));
        cmd.set("#ShRelState.Style.TextColor", STATE_COLORS[s]);
        cmd.set("#ShRelValue.TextSpans", Message.translation(TAB_KEYS + "sheet.value").param("value", signed(value)));
        int tick = Math.max(0, Math.min(SEGMENTS - 1, (value + 100) / 10));
        for (int k = 0; k < SEGMENTS; k++) {
            cmd.set("#ShSeg" + k + ".Visible", segmentState(k) == s);
            cmd.set("#ShTick" + k + ".Visible", k == tick);
        }
        for (int k = 0; k < STATES.length; k++) {
            cmd.set("#ShZone" + STATES[k] + ".TextSpans", Message.translation(TAB_KEYS + "state." + STATE_KEYS[k]));
            cmd.set("#ShZone" + STATES[k] + ".Style.TextColor", k == s ? STATE_COLORS[k] : "#8796a6");
        }
        int target = Relations.base(l.a().people(), l.b().people());
        int truceUntil = Relations.truceUntil(world, l.a(), l.b());
        long truceDays = truceUntil == Integer.MIN_VALUE ? -1 : truceUntil - day + 1;
        cmd.set("#ShTend.TextSpans", truceDays > 0
                ? Message.translation(TAB_KEYS + "sheet.tendTruce").param("days", truceDays).param("target", signed(target))
                : Message.translation(TAB_KEYS + "sheet.tend").param("target", signed(target)));

        // Pastilles : route, auberge, trêve, escarmouche.
        List<Object[]> chips = new ArrayList<>();
        chips.add(road ? new Object[] {"Road", Message.translation(TAB_KEYS + "sheet.road"), "#d6dde6"}
                : new Object[] {"NoRoad", Message.translation(TAB_KEYS + "sheet.noRoad"), "#8796a6"});
        Inns.Inn inn = Inns.get(world, l.key());
        int traffic = Links.traffic(world, l.key(), day);
        if (inn != null && inn.open()) {
            chips.add(new Object[] {"Inn", Message.translation(TAB_KEYS + "sheet.inn").param("level", inn.level()), GOLD});
        } else if (inn != null && inn.building()) {
            chips.add(new Object[] {"Inn", Message.translation(TAB_KEYS + "sheet.innWorks"), GOLD});
        } else if (l.length() >= Inns.MIN_LENGTH) {
            chips.add(new Object[] {"Inn", Message.translation(TAB_KEYS + "sheet.innSoon").param("count", Math.min(traffic, Inns.TRAFFIC_1))
                    .param("need", Inns.TRAFFIC_1), "#a8b6c4"});
        }
        if (truceDays > 0) {
            chips.add(new Object[] {"Truce", Message.translation(TAB_KEYS + "sheet.truce").param("days", truceDays), "#a9dcef"});
        }
        boolean fighting = Relations.fighting(l.key());
        if (fighting) {
            chips.add(new Object[] {"Fight", Message.translation(TAB_KEYS + "sheet.fight"), RED_TEXT});
        } else {
            Object[] last = Relations.last(world, l.key());
            if (last != null && day - (Long) last[0] <= Links.WINDOW_DAYS) {
                String winner = (String) last[1];
                String k = winner == null ? "neighbours.lastDraw" : winner.equals(v.id()) ? "neighbours.lastWon" : "neighbours.lastLost";
                chips.add(new Object[] {"Swords", Message.translation(KEYS + k).param("when", when(day - (Long) last[0], true)), "#e9a24c"});
            }
        }
        for (int k = 0; k < CHIPS; k++) {
            boolean shown = k < chips.size();
            cmd.set("#ShChip" + k + ".Visible", shown);
            if (!shown) {
                continue;
            }
            Object[] c = chips.get(k);
            for (String icon : CHIP_ICONS) {
                cmd.set("#ShChip" + icon + k + ".Visible", icon.equals(c[0]));
            }
            cmd.set("#ShChipRed" + k + ".Visible", "Fight".equals(c[0]));
            cmd.set("#ShChipText" + k + ".TextSpans", (Message) c[1]);
            cmd.set("#ShChipText" + k + ".Style.TextColor", (String) c[2]);
        }

        // Voyages partis d'ici vers ce voisin (Kevin, 16 novembre 2026).
        int[] trips = Links.trips(world, v.id(), other.id());
        cmd.set("#ShTrips.TextSpans", Message.translation(TAB_KEYS + "sheet.trips"));
        boolean any = trips[0] > 0;
        for (String id : new String[] {"#ShTripsGone", "#ShTripsGoneTxt", "#ShTripsOk", "#ShTripsOkTxt", "#ShTripsLost", "#ShTripsLostTxt"}) {
            cmd.set(id + ".Visible", any);
        }
        cmd.set("#ShTripsNone.Visible", !any);
        if (any) {
            cmd.set("#ShTripsGone.TextSpans", Message.raw(String.valueOf(trips[0])));
            cmd.set("#ShTripsGoneTxt.TextSpans", Message.translation(TAB_KEYS + "sheet.gone"));
            cmd.set("#ShTripsOk.TextSpans", Message.raw(String.valueOf(trips[1])));
            cmd.set("#ShTripsOkTxt.TextSpans", Message.translation(TAB_KEYS + "sheet.ok"));
            cmd.set("#ShTripsLost.TextSpans", Message.raw(String.valueOf(trips[2])));
            cmd.set("#ShTripsLost.Style.TextColor", trips[2] > 0 ? "#ef8a7a" : "#a8b6c4");
            cmd.set("#ShTripsLostTxt.TextSpans", Message.translation(TAB_KEYS + "sheet.lost"));
        } else {
            cmd.set("#ShTripsNone.TextSpans", Message.translation(TAB_KEYS + "sheet.noTrips"));
        }

        // Voyageurs sur la liaison, dans un sens ou dans l'autre.
        List<Travellers.Traveller> onLink = new ArrayList<>();
        for (Travellers.Traveller t : Travellers.all(world)) {
            if ((v.id().equals(t.from()) && other.id().equals(t.to())) || (other.id().equals(t.from()) && v.id().equals(t.to()))) {
                onLink.add(t);
            }
        }
        for (int k = 0; k < TRACKS; k++) {
            boolean shown = k < onLink.size();
            cmd.set("#ShTr" + k + ".Visible", shown);
            if (!shown) {
                continue;
            }
            Travellers.Traveller t = onLink.get(k);
            boolean leaving = v.id().equals(t.from());
            Village fromV = leaving ? v : other;
            Village toV = leaving ? other : v;
            Routes.Path path = Links.path(world, fromV, toV);
            double length = Math.max(1, path.length());
            double at = Math.min(t.at, length);
            int left = (int) Math.round(length - at);
            double speed = path.beacons() > 0 ? Walk.ROUTE_SPEED : Walk.SPEED;
            int minutes = (int) Math.round(left / speed / 60.0);
            String icon = trackIcon(t.kind());
            for (String name : TRACK_ICONS) {
                cmd.set("#ShTr" + name + k + ".Visible", name.equals(icon));
            }
            cmd.set("#ShTrKind" + k + ".TextSpans", Message.translation(Travellers.KEYS + "kind." + t.kind().key()));
            cmd.set("#ShTrText" + k + ".TextSpans", Message.translation(TAB_KEYS + (leaving ? "sheet.trOut" : "sheet.trIn"))
                    .param("village", other.name()));
            cmd.set("#ShTrEta" + k + ".TextSpans", minutes < 1
                    ? Message.translation(TAB_KEYS + "sheet.etaSoon").param("left", left)
                    : Message.translation(TAB_KEYS + "sheet.eta").param("left", left).param("minutes", minutes));
            cmd.set("#ShTrFrom" + k + ".TextSpans", Message.raw(fromV.name()));
            cmd.set("#ShTrTo" + k + ".TextSpans", Message.raw(toV.name()));
            cmd.set("#ShTrBar" + k + ".Value", (float) (at / length));
        }

        // Ce que le joueur peut faire, du plus pressant au plus calme.
        Object[] hint = hint(world, v, l, value, truceDays, fighting, road, inn, traffic, pathLength, !onLink.isEmpty(), day);
        for (String name : HINT_ICONS) {
            cmd.set("#ShHintIco" + name + ".Visible", name.equals(hint[0]));
        }
        boolean warn = (Boolean) hint[2];
        cmd.set("#ShHintWarn.Visible", warn);
        cmd.set("#ShHintInfo.Visible", !warn);
        cmd.set("#ShHintText.TextSpans", (Message) hint[1]);
    }

    /** {picto, texte, alerte}. */
    private static Object[] hint(World world, Village v, Links.Link l, int value, long truceDays, boolean fighting, boolean road,
                                 Inns.Inn inn, int traffic, int pathLength, boolean travellers, long day) {
        Village other = l.other(v.id());
        if (fighting) {
            double[] at = Relations.fightAt(l.key());
            double x = at == null ? l.middle()[0] : at[0];
            double z = at == null ? l.middle()[1] : at[1];
            return new Object[] {"Swords", Message.translation(TAB_KEYS + "hint.fight").param("distance", distance(v, x, z))
                    .param("direction", direction(v, x, z)).param("reputation", Relations.SKIRMISH_REPUTATION).param("village", v.name()), true};
        }
        int hour = Relations.pendingHour(world, l.key(), day);
        if (hour >= 0) {
            return new Object[] {"Swords", Message.translation(TAB_KEYS + "hint.pending").param("hour", hour), true};
        }
        if (truceDays > 0) {
            return new Object[] {"Truce", Message.translation(TAB_KEYS + "hint.truce").param("days", truceDays), false};
        }
        if (value <= Relations.RIVAL) {
            return new Object[] {"Scroll", Message.translation(TAB_KEYS + "hint.parley").param("parley", Relations.PARLEY)
                    .param("days", Relations.TRUCE_DAYS), value < -50};
        }
        if (travellers) {
            return new Object[] {"Cart", Message.translation(TAB_KEYS + "hint.escort").param("reputation", Travellers.ESCORT_REPUTATION), false};
        }
        if (value >= Relations.ALLY && l.length() <= Relations.REINFORCE_RANGE) {
            return new Object[] {"Shield", Message.translation(TAB_KEYS + "hint.reinforce").param("range", Relations.REINFORCE_RANGE), false};
        }
        if (inn != null && inn.open()) {
            return new Object[] {"Inn", Message.translation(TAB_KEYS + "hint.inn"), false};
        }
        if (inn == null && l.length() >= Inns.MIN_LENGTH) {
            return new Object[] {"Inn", Message.translation(TAB_KEYS + "hint.innSoon").param("need", Inns.TRAFFIC_1).param("count", traffic), false};
        }
        if (!road) {
            return new Object[] {"Road", Message.translation(TAB_KEYS + "hint.noRoad").param("path", pathLength), false};
        }
        return new Object[] {"Cart", Message.translation(TAB_KEYS + "hint.default"), false};
    }

    // ---- Ce qui vient ----

    /**
     * Une ligne : dans combien de jours (Long.MAX_VALUE : manque), heure (ordre), quand, quoi, nom court (tuile), où
     * (ou null), ici, ton ({@link #PLAIN}, {@link #RED_LATER}, {@link #GOLD}, {@link #RED}), picto.
     */
    private record Entry(long days, int hour, Message when, Message what, Message shortName, Message place, boolean here, int tone,
                         String icon) {
        static final int PLAIN = 0;
        static final int RED_LATER = 1;
        static final int GOLD = 2;
        static final int RED = 3;
    }

    private static List<Entry> agenda(World world, Store<EntityStore> store, Village v, List<Links.Link> links, long day) {
        List<Entry> now = new ArrayList<>();
        Message nowWhen = Message.translation(TAB_KEYS + "ag.now");
        Message live = Message.translation(TAB_KEYS + "ag.live");
        Message here = Message.translation(TAB_KEYS + "pl.here");
        if (Raids.isActive(v)) {
            now.add(new Entry(-1, 0, live, Message.translation(TAB_KEYS + "ag.raidNow"), Message.translation(TAB_KEYS + "ev.raid"), here, true,
                    Entry.RED, "Flame"));
        }
        for (Links.Link l : links) {
            if (Relations.fighting(l.key())) {
                double[] at = Relations.fightAt(l.key());
                double x = at == null ? l.middle()[0] : at[0];
                double z = at == null ? l.middle()[1] : at[1];
                now.add(new Entry(-1, 0, live, Message.translation(TAB_KEYS + "ag.skirmish").param("village", l.other(v.id()).name()),
                        Message.translation(TAB_KEYS + "ev.skirmish"), Message.translation(TAB_KEYS + "pl.roadDist")
                        .param("direction", direction(v, x, z)).param("distance", distance(v, x, z)), false, Entry.RED, "Swords"));
            }
        }
        if (Calendar.running("feast", v)) {
            now.add(new Entry(-1, 0, nowWhen, Message.translation(TAB_KEYS + "ag.feast"), Message.translation(TAB_KEYS + "ev.feast"), here,
                    true, Entry.GOLD, "Wheat"));
        }
        int feasts = 0;
        boolean fairRunning = false;
        for (Village other : Villages.registry(world).all()) {
            if (!other.id().equals(v.id()) && Calendar.running("feast", other)) {
                feasts++;
            }
            if (Calendar.running("fair", other)) {
                fairRunning = true;
                boolean isHere = other.id().equals(v.id());
                now.add(new Entry(-1, 0, nowWhen, Message.translation(TAB_KEYS + "ag.fair"), Message.translation(TAB_KEYS + "ev.fair"),
                        isHere ? here : Message.translation(TAB_KEYS + "pl.village").param("village", other.name())
                                .param("direction", direction(v, other.x(), other.z())), isHere, Entry.GOLD, "Fair"));
            }
        }
        if (feasts > 0) {
            now.add(new Entry(-1, 0, nowWhen, Message.translation(TAB_KEYS + "ag.feasts").param("count", feasts),
                    Message.translation(TAB_KEYS + "ev.feast"), Message.translation(TAB_KEYS + "pl.elsewhere"), false, Entry.GOLD, "Wheat"));
        }
        double[] herd = Calendar.herdAt();
        if (herd != null) {
            now.add(new Entry(-1, 0, nowWhen, Message.translation(TAB_KEYS + "ag.herd"), Message.translation(TAB_KEYS + "ev.herd"),
                    Message.translation(TAB_KEYS + "pl.far").param("direction", direction(v, herd[0], herd[1]))
                            .param("distance", distance(v, herd[0], herd[1])), false, Entry.GOLD, "Herd"));
        }

        List<Entry> later = new ArrayList<>();
        for (Links.Link l : links) {
            int hour = Relations.pendingHour(world, l.key(), day);
            if (hour >= 0 && !Relations.fighting(l.key())) {
                double[] mid = l.middle();
                later.add(new Entry(0, hour, withHour(when(0, false), hour), Message.translation(TAB_KEYS + "ag.skirmish")
                        .param("village", l.other(v.id()).name()), Message.translation(TAB_KEYS + "ev.skirmish"),
                        Message.translation(TAB_KEYS + "pl.road").param("direction", direction(v, mid[0], mid[1])), false, Entry.RED_LATER, "Swords"));
            }
        }
        if (!Raids.isActive(v) && v.raid().scheduled()) {
            long d = Math.max(0, v.raid().night() - day);
            later.add(new Entry(d, GameClock.DUSK, d == 0 ? Message.translation(KEYS + "agenda.tonight") : when(d, false),
                    Message.translation(TAB_KEYS + "ag.raid"), Message.translation(TAB_KEYS + "ev.raid"), here, true, Entry.RED_LATER, "Flame"));
        }
        if (!Calendar.running("feast", v) && Calendar.hasFarm(v)) {
            long d = Calendar.upcoming(world, store, "feast", Calendar.FEAST_EVERY, 3) - day;
            later.add(new Entry(d, Calendar.FEAST_HOUR, withHour(when(d, false), Calendar.FEAST_HOUR), Message.translation(TAB_KEYS + "ag.feast"),
                    Message.translation(TAB_KEYS + "ev.feast"), here, true, Entry.PLAIN, "Wheat"));
        }
        Object[] fair = fairRunning ? null : fair(world, store);
        if (fair != null && fair[0] instanceof Village host) {
            long d = (Long) fair[1];
            boolean isHere = host.id().equals(v.id());
            later.add(new Entry(d, Calendar.FAIR_HOUR, withHour(when(d, false), Calendar.FAIR_HOUR), Message.translation(TAB_KEYS + "ag.fair"),
                    Message.translation(TAB_KEYS + "ev.fair"), isHere ? here : Message.translation(TAB_KEYS + "pl.village")
                    .param("village", host.name()).param("direction", direction(v, host.x(), host.z())), isHere, Entry.PLAIN, "Fair"));
        }
        if (herd == null) {
            long d = Calendar.upcoming(world, store, "migration", Calendar.MIGRATION_EVERY, 5) - day;
            later.add(new Entry(d, 0, when(d, false), Message.translation(TAB_KEYS + "ag.migration"), Message.translation(TAB_KEYS + "ev.migration"),
                    Message.translation(TAB_KEYS + "pl.random"), false, Entry.PLAIN, "Herd"));
        }
        later.sort(Comparator.comparingLong(Entry::days).thenComparingInt(Entry::hour));
        List<Entry> out = new ArrayList<>(now);
        out.addAll(later);
        // Ce qui manque (fête, foire) : la ligne du bas, avec un bouton vers Bâtir pour la ferme.
        if (!Calendar.hasFarm(v)) {
            out.add(new Entry(Long.MAX_VALUE, 0, null, Message.translation(KEYS + "village.noFeast"), null, null, false, Entry.PLAIN, "Feast"));
        } else if (!fairRunning && (fair == null || fair[0] == null)) {
            out.add(new Entry(Long.MAX_VALUE, 0, null, Message.translation(KEYS + "world.noFair"), null, null, false, Entry.PLAIN, "Fair"));
        }
        return out;
    }

    private static void agendaRows(UICommandBuilder cmd, UIEventBuilder events, List<Entry> agenda, Village v) {
        cmd.set("#AgTitle.TextSpans", Message.translation(TAB_KEYS + "ag.title"));
        List<Entry> rows = new ArrayList<>();
        Entry lock = null;
        for (Entry e : agenda) {
            if (e.days() == Long.MAX_VALUE) {
                lock = e;
            } else {
                rows.add(e);
            }
        }
        cmd.set("#AgNone.Visible", rows.isEmpty());
        if (rows.isEmpty()) {
            cmd.set("#AgNone.TextSpans", Message.translation(TAB_KEYS + "ag.none"));
        }
        for (int i = 0; i < AGENDA; i++) {
            if (i >= rows.size()) {
                cmd.set("#Ag" + i + ".Visible", false);
                continue;
            }
            Entry e = rows.get(i);
            boolean gold = e.tone() == Entry.GOLD;
            boolean red = e.tone() == Entry.RED;
            boolean nowRow = gold || red;
            cmd.set("#Ag" + i + ".Visible", true);
            cmd.set("#AgGold" + i + ".Visible", gold);
            cmd.set("#AgRed" + i + ".Visible", red);
            cmd.set("#AgDiscPlain" + i + ".Visible", !nowRow);
            cmd.set("#AgDiscGold" + i + ".Visible", gold);
            cmd.set("#AgDiscRed" + i + ".Visible", red);
            for (String name : AGENDA_ICONS) {
                cmd.set("#AgIco" + name + i + ".Visible", !nowRow && name.equals(e.icon()));
                cmd.set("#AgIcoDark" + name + i + ".Visible", nowRow && name.equals(e.icon()));
            }
            cmd.set("#AgWhen" + i + ".TextSpans", e.when());
            cmd.set("#AgWhen" + i + ".Style.TextColor", red ? RED_TEXT : gold ? "#f0d58a" : e.tone() == Entry.RED_LATER ? "#e9a24c"
                    : e.here() ? GOLD : "#b4c0cc");
            cmd.set("#AgText" + i + ".TextSpans", e.what());
            boolean hasPlace = e.place() != null;
            cmd.set("#AgPlaceHere" + i + ".Visible", hasPlace && e.here());
            cmd.set("#AgPlacePlain" + i + ".Visible", hasPlace && !e.here());
            cmd.set("#AgPlace" + i + ".TextSpans", hasPlace ? e.place() : Message.raw(" "));
            cmd.set("#AgPlace" + i + ".Style.TextColor", e.here() ? "#10180e" : "#c9d1d9");
        }
        cmd.set("#AgLock.Visible", lock != null);
        if (lock != null) {
            cmd.set("#AgLockText.TextSpans", lock.what());
            boolean farm = "Feast".equals(lock.icon());
            cmd.set("#AgLockBtn.Visible", farm);
            if (farm) {
                cmd.set("#AgLockBtnText.TextSpans", Message.translation(TAB_KEYS + "ag.build"));
                events.addEventBinding(CustomUIEventBindingType.Activating, "#AgLockBtn", EventData.of("Action", "TabBuild"));
            }
        }
    }

    // ---- Rumeur ----

    private static void rumour(UICommandBuilder cmd, World world, Store<EntityStore> store, Ref<EntityStore> ref, Village v, long day) {
        cmd.set("#RumourTitle.TextSpans", Message.translation(TAB_KEYS + "rumour.title"));
        List<Message> all = Rumours.all(world, store, ref);
        // Une rumeur par jour et par village : la même tant qu'on clique dans la page.
        cmd.set("#RumourText.TextSpans", all.isEmpty() ? Message.translation(Rumours.KEYS + "none")
                : all.get((int) Math.floorMod(day * 31 + v.id().hashCode(), (long) all.size())));
    }

    // ---- Outils ----

    private static int stateIndex(int value) {
        return switch (Relations.state(value)) {
            case "ally" -> 0;
            case "friendly" -> 1;
            case "tense" -> 3;
            case "rival" -> 4;
            default -> 2;
        };
    }

    /** État du cran k de la jauge (valeurs -100 + 10 k à -91 + 10 k). */
    private static int segmentState(int k) {
        return stateIndex(-100 + 10 * k + 5);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : value < 0 ? "−" + (-value) : "0";
    }

    /** Village de la prochaine foire (pas en cours) et dans combien de jours, ou null. */
    private static Object[] fair(World world, Store<EntityStore> store) {
        long day = GameClock.day(store);
        long d = Calendar.upcoming(world, store, "fair", Calendar.FAIR_EVERY, 7);
        Object[] f = Calendar.nextFair(world, d);
        return f == null ? null : new Object[] {f[0], d - day};
    }

    private static Message join(List<Message> parts) {
        Message m = Message.empty();
        for (int k = 0; k < parts.size(); k++) {
            if (k > 0) {
                m.insert(" · ");
            }
            m.insert(parts.get(k));
        }
        return m;
    }

    private static Message withHour(Message when, int hour) {
        return Message.translation(TAB_KEYS + "ag.whenHour").param("when", when).param("hour", hour);
    }

    private static Message when(long days, boolean past) {
        String p = past ? "ago." : "in.";
        if (days <= 0) {
            return Message.translation(KEYS + "when." + p + "today");
        }
        if (days == 1) {
            return Message.translation(KEYS + "when." + p + "one");
        }
        return Message.translation(KEYS + "when." + p + "days").param("days", days);
    }

    private static int distance(Village v, double x, double z) {
        return (int) Math.round(Math.hypot(x - v.x(), z - v.z()));
    }

    private static Message direction(Village v, double x, double z) {
        return Message.translation(Villages.MESSAGES + "dir." + Requests.direction(x - v.x(), z - v.z()));
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
        if (BoardTabs.handle(data.action, world, store, ref, playerRef, village)) {
            return;
        }
        List<Links.Link> links = links(world, village);
        String a = data.action;
        if (a.startsWith("Nb") || a.startsWith("Mk")) {
            try {
                int i = Integer.parseInt(a.substring(2));
                if (i >= 0 && i < links.size()) {
                    selected = links.get(i).other(villageId).id();
                }
            } catch (NumberFormatException ignored) {
                // action inconnue : on redessine
            }
        } else if (a.equals("Mark")) {
            Links.Link l = pick(links);
            if (l != null) {
                selected = l.other(villageId).id();
                WorldWaypoints.toggle(world, playerRef, l.other(villageId));
            }
        }
        rebuild();
    }
}
