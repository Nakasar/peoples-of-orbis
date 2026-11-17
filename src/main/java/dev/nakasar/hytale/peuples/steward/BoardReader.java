package dev.nakasar.hytale.peuples.steward;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.annex.AnnexPage;
import dev.nakasar.hytale.peuples.annex.Annexes;
import dev.nakasar.hytale.peuples.annex.OuvragePage;
import dev.nakasar.hytale.peuples.population.PeoplePage;
import dev.nakasar.hytale.peuples.population.Population;
import dev.nakasar.hytale.peuples.request.BoardPage;
import dev.nakasar.hytale.peuples.village.HamletRegistry;
import dev.nakasar.hytale.peuples.village.PlotPage;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.WorkshopPage;
import dev.nakasar.hytale.peuples.world.DomainPage;
import dev.nakasar.hytale.peuples.world.Hamlets;
import dev.nakasar.hytale.peuples.world.TradePage;
import dev.nakasar.hytale.peuples.world.WorldPage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le panneau du village lu et cliqué par le gestionnaire, sans rien recopier : chaque onglet est la vraie page du
 * panneau ({@link BoardPage}, {@link PlotPage}...), bâtie hors écran dans des constructeurs qui notent ce qu'elle
 * afficherait (textes visibles, boutons et leur action, boutons grisés). Le gestionnaire lit donc exactement l'écran,
 * et un « clic » envoie à la page la même donnée que le bouton du client : mêmes fonctions, mêmes contrôles de rang, de
 * faveurs et de matériaux, mêmes messages de refus (gardés par {@link CapturingPlayerRef}).
 * <p>
 * Toujours sur le thread du monde. Les pages sont gardées par conversation ({@link StewardSession}) : une sélection
 * (le terrain choisi dans Projets, la page des habitants) tient d'un appel à l'autre, comme à l'écran.
 */
public final class BoardReader {

    /** Ce que demande un bouton avant d'être cliqué par la parole. */
    public enum Kind {
        /** Lecture ou navigation, ou action sans dépense : faite tout de suite. */
        FREE,
        /** Dépense (faveurs, matériaux, offrande) ou destruction : le joueur clique Confirmer. */
        CONFIRM,
        /** Ouvre une autre fenêtre (onglet, texte, boutique) : jamais par la parole. */
        FORBIDDEN
    }

    /** Un onglet du panneau : id donné au modèle, page bâtie, et ce que demande chaque action (premier préfixe trouvé). */
    public enum Tab {
        TABLEAU("tableau", "Tableau (demandes, chantier, dépôt)", new String[][]{
                {"Take", "FREE"}, {"TurnIn", "FREE"}, {"Abandon", "CONFIRM"}}),
        PROJETS("projets", "Projets (emplacements à bâtir)", new String[][]{
                {"Select", "FREE"}, {"Choose", "FREE"}, {"Clear", "FREE"}, {"Action", "CONFIRM"}}),
        PRODUCTION("production", "Production (annexes)", new String[][]{
                {"Grant", "CONFIRM"}, {"Destroy", "CONFIRM"}}),
        OUVRAGES("ouvrages", "Ouvrages (infrastructures)", new String[][]{
                {"Grant", "CONFIRM"}, {"Row", "CONFIRM"}}),
        MONDE("monde", "Monde (voisins, routes, agenda)", new String[][]{}),
        DOMAINE("domaine", "Domaine (armoiries, couleurs, lieux-dits)", new String[][]{
                {"ColourPrev", "FREE"}, {"ColourNext", "FREE"}, {"EmblemPrev", "FREE"}, {"EmblemNext", "FREE"},
                {"Colours", "FREE"}, {"ArmsApply", "CONFIRM"}, {"Embroider", "CONFIRM"}, {"Embassy", "CONFIRM"},
                {"Expedition", "CONFIRM"}}),
        COMMERCE("commerce", "Commerce (routes commerciales, étal)", new String[][]{
                {"Out", "FREE"}, {"In", "FREE"}, {"Raise", "CONFIRM"}, {"Pay", "CONFIRM"}, {"Close", "CONFIRM"},
                {"Open", "CONFIRM"}, {"Buy", "CONFIRM"}}),
        HABITANTS("habitants", "Habitants (générations)", new String[][]{
                {"Prev", "FREE"}, {"Next", "FREE"}, {"OrientPrev", "FREE"}, {"OrientNext", "FREE"}, {"OrientApply", "CONFIRM"},
                {"Orient", "FREE"}, {"Refuge", "FREE"}, {"Name", "CONFIRM"}}),
        ATELIER("atelier", "Atelier du lieu-dit (demandes, maître, amélioration)", new String[][]{
                {"Take", "FREE"}, {"TurnIn", "FREE"}, {"Share", "FREE"}, {"Abandon", "CONFIRM"}, {"Master", "CONFIRM"},
                {"Upgrade", "CONFIRM"}, {"Shop", "FORBIDDEN"}, {"Specialty", "FORBIDDEN"}});

        private final String id;
        private final String label;
        private final String[][] policy;

        Tab(String id, String label, String[][] policy) {
            this.id = id;
            this.label = label;
            this.policy = policy;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public static Tab byId(String id) {
            for (Tab t : values()) {
                if (t.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                    return t;
                }
            }
            return null;
        }

        /** Ce que demande l'action `action` de cet onglet ; inconnue : confirmation (prudence). */
        public Kind kind(String action) {
            if (action == null || action.startsWith("Tab") || action.equals("Text")) {
                return Kind.FORBIDDEN;
            }
            for (String[] p : policy) {
                if (action.startsWith(p[0])) {
                    return Kind.valueOf(p[1]);
                }
            }
            return Kind.CONFIRM;
        }
    }

    /** Un bouton de la page : son action (donnée du clic), son libellé, grisé ou non. */
    public record Button(String action, String label, String labelKey, boolean disabled, Map<String, String> data) {
    }

    /** Ce que la page afficherait : lignes et boutons dans l'ordre de l'écran ({@link #text}). */
    public record Snapshot(Tab tab, List<String> lines, List<Button> buttons, String text) {

        public Button button(String action) {
            for (Button b : buttons) {
                if (b.action().equals(action)) {
                    return b;
                }
            }
            return null;
        }

        /** Le contenu sans les boutons : sert à voir si un clic a changé quelque chose. */
        String signature() {
            return String.join("\n", lines);
        }
    }

    private BoardReader() {
    }

    /** Les onglets que ce village montre (comme {@code BoardTabs.fill}) ; un lieu-dit n'a que son atelier. */
    public static List<Tab> tabs(Village village) {
        List<Tab> out = new ArrayList<>();
        if (village == null) {
            return out;
        }
        if (HamletRegistry.isHamlet(village.id())) {
            out.add(Tab.ATELIER);
            return out;
        }
        out.add(Tab.TABLEAU);
        if (Plots.plots(village).length > 0) {
            out.add(Tab.PROJETS);
        }
        if (!Annexes.catalog(village).isEmpty()) {
            out.add(Tab.PRODUCTION);
        }
        if (!Annexes.catalog(village, Workshop.Annex.INFRASTRUCTURE).isEmpty()) {
            out.add(Tab.OUVRAGES);
        }
        out.add(Tab.MONDE);
        out.add(Tab.DOMAINE);
        out.add(Tab.COMMERCE);
        if (Population.applies(village)) {
            out.add(Tab.HABITANTS);
        }
        return out;
    }

    /** La page de l'onglet pour ce joueur (hors écran), ou null si le village ne l'a pas. */
    static InteractiveCustomUIPage<?> page(World world, Tab tab, CapturingPlayerRef player, Village village) {
        return switch (tab) {
            case TABLEAU -> new BoardPage(player, village);
            case PROJETS -> new PlotPage(player, village);
            case PRODUCTION -> new AnnexPage(player, village);
            case OUVRAGES -> new OuvragePage(player, village);
            case MONDE -> new WorldPage(player, village);
            case DOMAINE -> new DomainPage(player, village);
            case COMMERCE -> new TradePage(player, village);
            case HABITANTS -> new PeoplePage(player, village);
            case ATELIER -> {
                Hamlets.Place h = HamletRegistry.isHamlet(village.id())
                        ? Hamlets.get(world, village.id().substring(HamletRegistry.PREFIX.length())) : null;
                Workshop w = h == null ? null : Hamlets.workshop(h);
                yield w == null ? null : new WorkshopPage(player, village, w);
            }
        };
    }

    /** Ce que la page `page` montrerait au joueur `ref` maintenant, en texte dans la langue `language`. */
    static Snapshot read(Tab tab, InteractiveCustomUIPage<?> page, Ref<EntityStore> ref, Store<EntityStore> store, String language) {
        List<Object> order = new ArrayList<>();
        Recorder cmd = new Recorder(order);
        Events events = new Events(order);
        page.build(ref, cmd, events, store);
        Map<String, Message> labels = new HashMap<>();
        for (Object o : order) {
            if (o instanceof TextEntry t && t.selector.endsWith(".Text")) {
                labels.put(element(t.selector), t.message);
            }
        }
        List<String> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        text.append("Onglet ").append(tab.label()).append('\n');
        String lastLine = null;
        Map<String, Boolean> seen = new HashMap<>();
        for (Object o : order) {
            if (o instanceof TextEntry t) {
                String element = element(t.selector);
                if (skipped(element) || cmd.hidden(element) || labels.get(element) == t.message && events.bound(element)) {
                    continue;
                }
                String line = clean(TextRender.text(t.message, language));
                if (line.isEmpty()) {
                    continue;
                }
                lines.add(line);
                text.append(line).append('\n');
                lastLine = line;
            } else if (o instanceof EventEntry e) {
                String element = e.selector;
                String action = e.data.get("Action");
                if (action == null || skipped(element) || cmd.hidden(element) || seen.put(action, true) != null) {
                    continue;
                }
                if (tab.kind(action) == Kind.FORBIDDEN) {
                    continue;
                }
                Message label = labels.get(element);
                String shown = label != null ? clean(TextRender.text(label, language)) : lastLine == null ? action : lastLine;
                if (shown.length() > 90) {
                    shown = shown.substring(0, 87) + "...";
                }
                boolean disabled = cmd.disabled(element);
                buttons.add(new Button(action, shown, label == null ? null : label.getMessageId(), disabled, e.data));
                text.append("  [bouton ").append(action).append("] ").append(shown);
                if (disabled) {
                    text.append(" (grisé)");
                }
                if (tab.kind(action) == Kind.CONFIRM) {
                    text.append(" (demande la confirmation du joueur)");
                }
                text.append('\n');
            }
        }
        return new Snapshot(tab, List.copyOf(lines), List.copyOf(buttons), text.toString().strip());
    }

    /** Envoie à la page la donnée du bouton, comme le client quand le joueur clique (thread du monde). */
    static void click(InteractiveCustomUIPage<?> page, Ref<EntityStore> ref, Store<EntityStore> store, Button button) {
        JsonObject json = new JsonObject();
        button.data().forEach(json::addProperty);
        page.handleDataEvent(ref, store, json.toString());
    }

    private static String element(String selector) {
        int dot = selector.indexOf('.');
        return dot < 0 ? selector : selector.substring(0, dot);
    }

    /** Barre des onglets et horloge : chaque page les porte, le modèle les connaît déjà. */
    private static boolean skipped(String element) {
        return element.startsWith("#Tab") || element.equals("#Clock") || element.equals("#Leave");
    }

    private static String clean(String s) {
        return s.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").strip();
    }

    private record TextEntry(String selector, Message message) {
    }

    private record EventEntry(String selector, Map<String, String> data) {
    }

    /** Note les textes, la visibilité et les grisés posés par la page, dans l'ordre. */
    private static final class Recorder extends UICommandBuilder {
        private final List<Object> order;
        private final Map<String, Boolean> visible = new HashMap<>();
        private final Map<String, Boolean> disabled = new HashMap<>();

        Recorder(List<Object> order) {
            this.order = order;
        }

        boolean hidden(String element) {
            return Boolean.FALSE.equals(visible.get(element));
        }

        boolean disabled(String element) {
            return Boolean.TRUE.equals(disabled.get(element));
        }

        @Override
        public UICommandBuilder set(String selector, Message message) {
            if (selector != null && message != null && (selector.endsWith(".TextSpans") || selector.endsWith(".Text"))) {
                order.add(new TextEntry(selector, message));
            }
            return this;
        }

        @Override
        public UICommandBuilder set(String selector, String value) {
            if (selector != null && value != null && (selector.endsWith(".TextSpans") || selector.endsWith(".Text"))) {
                order.add(new TextEntry(selector, Message.raw(value)));
            }
            return this;
        }

        @Override
        public UICommandBuilder set(String selector, boolean value) {
            if (selector != null && selector.endsWith(".Visible")) {
                visible.put(element(selector), value);
            } else if (selector != null && selector.endsWith(".Disabled")) {
                disabled.put(element(selector), value);
            }
            return this;
        }
    }

    /** Note les boutons (liaison Activating) et la donnée de leur clic, dans l'ordre. */
    private static final class Events extends UIEventBuilder {
        private final List<Object> order;
        private final Map<String, Boolean> bound = new LinkedHashMap<>();

        Events(List<Object> order) {
            this.order = order;
        }

        boolean bound(String element) {
            return bound.containsKey(element);
        }

        @Override
        public UIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector, EventData data) {
            record(type, selector, data);
            return this;
        }

        @Override
        public UIEventBuilder addEventBinding(CustomUIEventBindingType type, String selector, EventData data, boolean locks) {
            record(type, selector, data);
            return this;
        }

        private void record(CustomUIEventBindingType type, String selector, EventData data) {
            if (type != CustomUIEventBindingType.Activating || selector == null || data == null) {
                return;
            }
            bound.put(selector, true);
            order.add(new EventEntry(selector, new LinkedHashMap<>(data.events())));
        }
    }
}
