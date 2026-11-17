package dev.nakasar.hytale.peuples.steward;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.annex.AnnexPage;
import dev.nakasar.hytale.peuples.annex.Annexes;
import dev.nakasar.hytale.peuples.annex.Infrastructures;
import dev.nakasar.hytale.peuples.annex.OuvragePage;
import dev.nakasar.hytale.peuples.annex.Ouvrages;
import dev.nakasar.hytale.peuples.player.PeuplesPlayer;
import dev.nakasar.hytale.peuples.request.PlayerRequest;
import dev.nakasar.hytale.peuples.population.Ages;
import dev.nakasar.hytale.peuples.population.Inhabitant;
import dev.nakasar.hytale.peuples.population.PeoplePage;
import dev.nakasar.hytale.peuples.population.Population;
import dev.nakasar.hytale.peuples.population.PopulationState;
import dev.nakasar.hytale.peuples.reputation.Rank;
import dev.nakasar.hytale.peuples.reputation.Reputations;
import dev.nakasar.hytale.peuples.request.RequestTemplate;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.request.VillageRequest;
import dev.nakasar.hytale.peuples.village.AnnexState;
import dev.nakasar.hytale.peuples.village.Chantiers;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.village.HamletRegistry;
import dev.nakasar.hytale.peuples.village.PlotPage;
import dev.nakasar.hytale.peuples.village.PlotState;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Tiers;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageBoard;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.village.Villages;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.WorkshopPage;
import dev.nakasar.hytale.peuples.world.Allegiance;
import dev.nakasar.hytale.peuples.world.Colours;
import dev.nakasar.hytale.peuples.world.Embassies;
import dev.nakasar.hytale.peuples.world.Hamlets;
import dev.nakasar.hytale.peuples.world.Links;
import dev.nakasar.hytale.peuples.world.Relations;
import dev.nakasar.hytale.peuples.world.Sieges;
import dev.nakasar.hytale.peuples.world.TradeAccess;
import dev.nakasar.hytale.peuples.world.TradeCatalogue;
import dev.nakasar.hytale.peuples.world.TradeGoods;
import dev.nakasar.hytale.peuples.world.TradePage;
import dev.nakasar.hytale.peuples.world.TradeRoutes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Ce que le gestionnaire sait du village, lu directement dans l'état du jeu (pas par l'interface ; Kevin, 17 novembre
 * 2026) : un texte compact par sujet, pensé pour le modèle, avec les identifiants que prennent les outils d'action
 * ({@link StewardActions}) entre crochets. Les phrases d'état réutilisent les fonctions de texte du jeu (état d'un
 * emplacement, d'une annexe, d'un ouvrage), traduites par {@link TextRender}.
 * <p>Toujours sur le thread du monde.</p>
 */
final class StewardData {

    /** Les sujets de {@code lire} : id donné au modèle, et ce qu'il couvre. */
    enum Topic {
        ETAT("etat", "palier, essor, chantier, raids, rang du joueur, dépôt"),
        DEMANDES("demandes", "les demandes du tableau et leurs récompenses"),
        PROJETS("projets", "les emplacements à bâtir et leurs projets possibles"),
        PRODUCTION("production", "les annexes de production"),
        OUVRAGES("ouvrages", "tours, postes, forts : les siens, ceux qu'il occupe, ceux qu'il peut attaquer"),
        COMMERCE("commerce", "luxes, routes commerciales, partenaires possibles, étal"),
        DOMAINE("domaine", "lieux-dits proches, propriétaires, influence, couleurs portées"),
        HABITANTS("habitants", "recensement, naissance à nommer, refuge, apprentis à orienter"),
        MONDE("monde", "villages voisins, relations, routes"),
        ATELIER("atelier", "le bâtiment de métier du lieu-dit : niveau, demandes, maître, amélioration");

        final String id;
        final String about;

        Topic(String id, String about) {
            this.id = id;
            this.about = about;
        }

        static Topic byId(String id) {
            for (Topic t : values()) {
                if (t.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                    return t;
                }
            }
            return null;
        }
    }

    /** Ce qu'il faut pour lire : le monde, le joueur, la langue. */
    record Ctx(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Village village, String language) {
        String t(Message m) {
            return TextRender.text(m, language).replaceAll("\\s*\\n\\s*", " / ").strip();
        }

        long day() {
            return GameClock.day(store);
        }
    }

    private StewardData() {
    }

    static boolean hamlet(Village v) {
        return v != null && HamletRegistry.isHamlet(v.id());
    }

    static Hamlets.Place place(World world, Village v) {
        return hamlet(v) ? Hamlets.get(world, v.id().substring(HamletRegistry.PREFIX.length())) : null;
    }

    static Workshop hamletWorkshop(World world, Village v) {
        Hamlets.Place h = place(world, v);
        return h == null ? null : Hamlets.workshop(h);
    }

    /** Les sujets qui ont un sens pour ce village (un lieu-dit n'a que son état et son atelier). */
    static List<Topic> topics(Village v) {
        List<Topic> out = new ArrayList<>();
        if (hamlet(v)) {
            out.add(Topic.ATELIER);
            return out;
        }
        out.add(Topic.ETAT);
        out.add(Topic.DEMANDES);
        if (Plots.plots(v).length > 0) {
            out.add(Topic.PROJETS);
        }
        if (!Annexes.catalog(v).isEmpty()) {
            out.add(Topic.PRODUCTION);
        }
        if (!Annexes.catalog(v, Workshop.Annex.INFRASTRUCTURE).isEmpty()) {
            out.add(Topic.OUVRAGES);
        }
        out.add(Topic.COMMERCE);
        out.add(Topic.DOMAINE);
        if (Population.applies(v)) {
            out.add(Topic.HABITANTS);
        }
        out.add(Topic.MONDE);
        return out;
    }

    /**
     * L'essor comme le sent un habitant : le PNJ ne cite jamais de chiffre (Kevin, 2026-11-17), il dit où en est le
     * village.
     */
    static String essorInWords(Village v) {
        int max = Tiers.threshold(v.tier());
        if (max <= 0) {
            return "le village a atteint sa pleine grandeur";
        }
        double f = (double) v.essor() / max;
        if (f >= 1) {
            return "le village est prêt à grandir : il n'attend plus que son prochain chantier";
        }
        if (f < 0.05) {
            return "rien n'a encore vraiment bougé, tout reste à faire";
        }
        if (f < 0.35) {
            return "le village commence tout juste à prendre de l'élan";
        }
        if (f < 0.7) {
            return "le village avance bien, on le sent grandir";
        }
        return "il ne manque plus grand-chose avant que le village grandisse";
    }

    static String read(Ctx c, Topic topic) {
        return switch (topic) {
            case ETAT -> TextRender.text(VillageBoard.body(c.world(), c.village(), c.store(), c.ref()), c.language())
                    + "\nEssor, à dire en mots (jamais en chiffres) : " + essorInWords(c.village());
            case DEMANDES -> requests(c);
            case PROJETS -> plots(c);
            case PRODUCTION -> annexes(c);
            case OUVRAGES -> works(c);
            case COMMERCE -> trade(c);
            case DOMAINE -> domain(c);
            case HABITANTS -> people(c);
            case MONDE -> world(c);
            case ATELIER -> workshop(c);
        };
    }

    // ---------------------------------------------------------------------------------------------------- demandes

    /** Titres des demandes au tableau, pour voir ce qui a changé entre deux messages du joueur. */
    static List<String> boardTitles(Ctx c) {
        Village v = c.village();
        Requests.refresh(c.store(), v);
        List<String> out = new ArrayList<>();
        for (VillageRequest r : v.requests()) {
            RequestTemplate t = r == null ? null : r.template();
            if (t != null) {
                out.add(c.t(Requests.title(t)));
            }
        }
        return out;
    }

    private static String requests(Ctx c) {
        Village v = c.village();
        Requests.refresh(c.store(), v);
        PeuplesPlayer data = PeuplesPlayer.get(c.store(), c.ref());
        StringBuilder out = new StringBuilder("Demandes du tableau (case 1 à 3) :");
        VillageRequest[] board = v.requests();
        boolean any = false;
        for (int i = 0; i < board.length; i++) {
            RequestTemplate t = board[i] == null ? null : board[i].template();
            if (t == null) {
                continue;
            }
            any = true;
            out.append("\n- [case ").append(i + 1).append("] ").append(c.t(Message.translation(t.type().nameKey()))).append(" : ")
                    .append(c.t(Requests.title(t)));
            if (t.itemId() != null && t.needed() > 1) {
                out.append(" ×").append(t.needed());
            }
            Requests.Status status = Requests.status(data, board[i]);
            if (status == Requests.Status.TAKEN && data != null) {
                PlayerRequest mine = data.request(board[i].id());
                out.append(" — PRISE par le joueur");
                if (mine != null) {
                    out.append(", ").append(Math.min(Math.max(1, t.needed()), mine.progress())).append('/').append(Math.max(1, t.needed()))
                            .append(" ; ").append(c.t(Requests.task(c.store(), c.player(), mine, t, v, Requests.position(c.store(), c.ref()))));
                }
            } else if (status == Requests.Status.DONE) {
                out.append(" — faite aujourd'hui");
            } else if (!Requests.slotOpen(c.store(), c.ref(), v, i)) {
                out.append(" — fermée (rang ").append(c.t(Message.translation(Rank.TOLERE.nameKey()))).append(" requis)");
            } else {
                out.append(" — ouverte ; récompense : essor ").append(t.essor()).append(", réputation ").append(t.reputation());
                if (t.favors() > 0) {
                    out.append(", faveurs ").append(t.favors());
                }
            }
        }
        if (!any) {
            out.append("\n(aucune)");
        }
        out.append("\nNouvelles demandes chaque matin à ").append(GameClock.MORNING).append(" h. Rendre : apporter les objets portés.");
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------- projets

    private static String plots(Ctx c) {
        Village v = c.village();
        StringBuilder out = new StringBuilder("Emplacements (choisir un projet : rang ")
                .append(c.t(Message.translation(Plots.CHOOSE_RANK.nameKey()))).append(") :");
        Message block = null;
        for (VillageLayout.Plot plot : Plots.plots(v)) {
            PlotState s = v.plot(plot.id());
            out.append("\n- [emplacement ").append(plot.id()).append("] ")
                    .append(c.t(PlotPage.stateLine(c.world(), c.store(), v, plot, s)));
            block = Plots.block(c.store(), c.ref(), v, plot);
            boolean locked = !Plots.isOpen(v, plot) || s.working() || (s.project() != null && v.isBuilt(s.project()));
            if (locked) {
                continue;
            }
            List<String> projects = new ArrayList<>();
            for (VillageLayout.Building b : Plots.catalog(v, plot.id())) {
                String refusal = b.id().equals(s.project()) ? null : Plots.refusal(c.world(), v, plot.id(), b);
                String name = c.t(Message.translation(Chantiers.buildingKey(b.id())));
                projects.add("[projet " + b.id() + "] " + name + (b.id().equals(s.project()) ? " (choisi)"
                        : refusal == null ? "" : " (impossible : " + refusal + ")"));
            }
            if (!projects.isEmpty()) {
                out.append("\n  projets : ").append(String.join(" ; ", projects));
            }
        }
        if (block != null) {
            out.append("\nPour ce joueur : ").append(c.t(block));
        }
        out.append("\nLancer un chantier prend les matériaux au dépôt ; démolir est possible ").append(Plots.DEMOLISH_DAYS)
                .append(" jours après la fin.");
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------- annexes

    private static String annexes(Ctx c) {
        Village v = c.village();
        StringBuilder out = new StringBuilder("Annexes de production :");
        Message block = Annexes.block(c.store(), c.ref(), v);
        for (Workshop w : Annexes.catalog(v)) {
            AnnexState a = v.annex(w.getId());
            out.append("\n- [annexe ").append(w.getId()).append("] ").append(c.t(Message.translation(w.nameKey()))).append(" — ")
                    .append(c.t(AnnexPage.stateLine(c.world(), c.store(), v, w, a)));
            if (a != null && Annexes.destroyable(a)) {
                out.append(" (peut être détruite)");
            }
        }
        out.append("\nAccorder une annexe donne au joueur la fondation à poser (rang ")
                .append(c.t(Message.translation(Annexes.GRANT_RANK.nameKey()))).append(").");
        if (block != null) {
            out.append("\nPour ce joueur : ").append(c.t(block));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------- ouvrages

    private static String works(Ctx c) {
        Village v = c.village();
        StringBuilder out = new StringBuilder("Ouvrages à fonder :");
        for (Workshop w : Annexes.catalog(v, Workshop.Annex.INFRASTRUCTURE)) {
            out.append("\n- [annexe ").append(w.getId()).append("] ").append(c.t(Message.translation(w.nameKey()))).append(" : ")
                    .append(Annexes.counted(v, w).size()).append('/').append(w.annex().limit());
            Message block = Annexes.block(c.store(), c.ref(), v, w.annex().tier());
            if (block != null) {
                out.append(" (").append(c.t(block)).append(')');
            }
        }
        out.append("\nOuvrages :");
        boolean any = false;
        for (Infrastructures.Work w : Infrastructures.all(c.world())) {
            boolean own = w.owner() == v;
            boolean held = !own && w.holder().equals(v.id());
            if (!own && !held) {
                continue;
            }
            any = true;
            out.append("\n- ").append(workLine(c, w));
            AnnexState a = w.state();
            if (own && a.occupied() && !a.lost() && !a.besieged()) {
                out.append(" (peut être repris)");
            } else if (own && a.ruined() && a.cleared()) {
                out.append(" (peut être rebâti)");
            } else if (own && Annexes.destroyable(a)) {
                out.append(" (peut être détruit)");
            }
        }
        for (Infrastructures.Work w : Ouvrages.targets(c.world(), v, c.day())) {
            any = true;
            out.append("\n- ").append(workLine(c, w)).append(" (cible : peut être attaqué, offrande ").append(Ouvrages.OFFERING).append(')');
        }
        if (!any) {
            out.append("\n(aucun)");
        }
        return out.toString();
    }

    private static String workLine(Ctx c, Infrastructures.Work w) {
        Village holder = Villages.registry(c.world()).byId(w.holder());
        return "[ouvrage " + Ouvrages.key(w) + "] " + c.t(Message.translation(w.workshop().nameKey())) + " de " + w.owner().name()
                + " (" + w.state().x() + ", " + w.state().z() + ") : " + c.t(OuvragePage.state(c.world(), c.store(), c.village(), w, holder));
    }

    // ---------------------------------------------------------------------------------------------------- commerce

    private static String trade(Ctx c) {
        Village v = c.village();
        long day = c.day();
        StringBuilder out = new StringBuilder();
        out.append("Luxes reçus : ").append(TradeAccess.count(c.world(), v, day)).append('/').append(TradeGoods.LUXURIES.size());
        List<TradeRoutes.Route> routes = TradeRoutes.of(c.world(), v);
        out.append("\nRoutes commerciales : ").append(routes.size()).append('/').append(TradeRoutes.slots(c.world(), v, day));
        for (TradeRoutes.Route r : routes) {
            Village o = TradeRoutes.village(c.world(), r.other(v.id()));
            out.append("\n- [partenaire ").append(o == null ? "?" : o.id()).append("] ")
                    .append(o == null ? "?" : c.t(TradeRoutes.name(c.world(), o))).append(", niveau ").append(r.level())
                    .append('/').append(TradeRoutes.MAX_LEVEL);
            if (r.suspended()) {
                out.append(", suspendue");
            } else {
                out.append(", prochaine caravane dans ").append(Math.max(0, r.next() - day)).append(" j, arrivées ").append(r.arrivals())
                        .append(", perdues ").append(r.lost()).append(", crédit ").append(r.credit(v.id()));
            }
            out.append("\n  envois (cases 1-").append(r.level()).append(") : ").append(cases(c, r.exportsOf(v.id()), r.level()))
                    .append(" ; reçus : ").append(cases(c, r.importsOf(v.id()), r.level()));
            if (o != null) {
                List<String> offer = new ArrayList<>();
                for (String item : TradeCatalogue.of(c.world(), v, r.key(), day).keySet()) {
                    if (TradeGoods.allowed(o.people(), item)) {
                        offer.add("[objet " + item + "] " + c.t(Message.translation(TradeGoods.itemKey(item))));
                    }
                }
                if (!offer.isEmpty()) {
                    out.append("\n  à envoyer possible : ").append(String.join(" ; ", offer));
                }
            }
        }
        List<Village> candidates = TradeRoutes.candidates(c.world(), v);
        out.append("\nPartenaires possibles (ouvrir : offrande ").append(TradeRoutes.OFFERING).append(") :");
        if (candidates.isEmpty()) {
            out.append(" aucun");
        }
        for (Village o : candidates) {
            out.append("\n- [partenaire ").append(o.id()).append("] ").append(c.t(TradeRoutes.name(c.world(), o))).append(", ")
                    .append(Math.round(TradeRoutes.distance(v, o))).append(" blocs");
        }
        Map<String, Integer> deposit = Villages.depositContents(c.world(), v);
        List<String> stall = new ArrayList<>();
        for (String item : TradeAccess.items(c.world(), v, day).keySet()) {
            if (deposit != null && deposit.getOrDefault(item, 0) >= TradePage.stallLot(item)) {
                stall.add("[objet " + item + "] " + TradePage.stallLot(item) + " × " + c.t(Message.translation(TradeGoods.itemKey(item))));
            }
        }
        out.append("\nÉtal (un lot contre une faveur) : ").append(stall.isEmpty() ? "vide" : String.join(" ; ", stall));
        return out.toString();
    }

    private static String cases(Ctx c, List<String> items, int level) {
        List<String> out = new ArrayList<>();
        for (int j = 0; j < level; j++) {
            String item = j < items.size() ? items.get(j) : null;
            out.add((j + 1) + "=" + (item == null ? "vide" : c.t(Message.translation(TradeGoods.itemKey(item)))));
        }
        return String.join(", ", out);
    }

    // ---------------------------------------------------------------------------------------------------- domaine

    private static String domain(Ctx c) {
        Village v = c.village();
        long day = c.day();
        StringBuilder out = new StringBuilder("Lieux-dits tenus : ").append(Allegiance.holdings(c.world(), v).size()).append('/')
                .append(Allegiance.cap(c.world(), v));
        List<Hamlets.Place> list = new ArrayList<>();
        for (Hamlets.Place h : Hamlets.all(c.world())) {
            if (h.stage() == Hamlets.BUILT && Math.sqrt(v.horizontalDistanceSquared(h.x(), h.z())) <= Allegiance.RANGE) {
                list.add(h);
            }
        }
        list.sort(Comparator.comparingInt((Hamlets.Place h) -> -Allegiance.influence(c.world(), h, v)));
        for (Hamlets.Place h : list) {
            Village owner = Allegiance.owner(c.world(), h);
            boolean mine = owner != null && owner.id().equals(v.id());
            out.append("\n- [lieu-dit ").append(h.id()).append("] ").append(c.t(h.name())).append(", ")
                    .append(Math.round(Math.sqrt(v.horizontalDistanceSquared(h.x(), h.z())))).append(" blocs, ")
                    .append(owner == null ? "libre" : mine ? "à nous" : "à " + owner.name())
                    .append(", notre influence ").append(Allegiance.influence(c.world(), h, v));
            if (!mine) {
                Message e = Embassies.blocked(c.world(), h, v, day);
                Message s = Sieges.blocked(c.world(), h, v, day);
                out.append(e == null ? " ; ambassade possible (offrande " + Embassies.OFFERING + ")" : "");
                out.append(s == null ? " ; expédition possible (offrande " + Sieges.OFFERING + ")" : "");
            }
        }
        Village worn = Colours.of(c.world(), c.store(), c.ref());
        out.append("\nCouleurs portées par le joueur : ").append(worn == null ? "aucune" : worn.id().equals(v.id()) ? "celles du village" : worn.name());
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------- habitants

    private static String people(Ctx c) {
        Village v = c.village();
        PopulationState p = Population.state(v);
        long day = c.day();
        Map<Ages.Stage, Integer> census = Population.census(v, day);
        StringBuilder out = new StringBuilder("Habitants : ").append(p.living().size()).append(" (enfants ").append(census.get(Ages.Stage.CHILD))
                .append(", apprentis ").append(census.get(Ages.Stage.APPRENTICE)).append(", adultes ").append(census.get(Ages.Stage.ADULT))
                .append(", anciens ").append(census.get(Ages.Stage.ELDER)).append("), lits ").append(Population.totalBeds(v));
        Inhabitant child = p.byId(p.newborn());
        if (child != null && child.alive() && day <= p.namingUntil()) {
            out.append("\nNaissance à nommer (rang ").append(c.t(Message.translation(Population.NAME_RANK.nameKey()))).append(") : ")
                    .append(c.t(child.fullName())).append(" ; noms proposés :");
            String[] names = p.proposals();
            for (int i = 0; i < names.length; i++) {
                out.append(" [nom ").append(i + 1).append("] ").append(names[i]);
            }
        }
        if (v.tier() >= Population.REFUGE_TIER) {
            out.append("\nRefuge en cas de raid : ").append(Population.refugeOpen(v, p)
                    ? c.t(Message.translation(Chantiers.buildingKey(p.refuge()))) : "aucun").append(" (changer : refuge suivant)");
        }
        List<Population.Post> posts = Population.orientChoices(v);
        List<String> apprentices = new ArrayList<>();
        for (Inhabitant h : p.living()) {
            if (h.stage(day) == Ages.Stage.APPRENTICE) {
                apprentices.add("[habitant " + h.id() + "] " + c.t(h.fullName()) + " (" + c.t(PeoplePage.describe(v, h, day)) + ")");
            }
        }
        if (!apprentices.isEmpty()) {
            out.append("\nApprentis à orienter (rang ").append(c.t(Message.translation(Population.HOST_RANK.nameKey()))).append(") : ")
                    .append(String.join(" ; ", apprentices));
            List<String> names = new ArrayList<>();
            for (Population.Post post : posts) {
                names.add("[poste " + post.id() + "] " + c.t(post.name()));
            }
            out.append("\nPostes possibles : ").append(names.isEmpty() ? "aucun" : String.join(" ; ", names));
        }
        List<dev.nakasar.hytale.peuples.population.ChronicleEntry> chronicle = p.chronicle();
        for (int i = chronicle.size() - 1, n = 0; i >= 0 && n < 4; i--, n++) {
            out.append(n == 0 ? "\nChronique récente : " : " / ").append(c.t(Population.chronicleLine(v, p, chronicle.get(i), day)));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------- monde

    private static String world(Ctx c) {
        Village v = c.village();
        List<Links.Link> links = new ArrayList<>(Links.of(c.world(), v));
        links.sort(Comparator.comparingDouble(Links.Link::length));
        StringBuilder out = new StringBuilder("Villages voisins :");
        if (links.isEmpty()) {
            out.append(" aucun");
        }
        for (Links.Link l : links) {
            Village o = l.other(v.id());
            int value = Relations.value(c.world(), l.a(), l.b());
            out.append("\n- ").append(o.name()).append(" (").append(c.t(Message.translation(o.people().nameKey()))).append("), ")
                    .append(Math.round(l.length())).append(" blocs, relation ")
                    .append(c.t(Message.translation(Relations.KEYS + "state." + Relations.state(value)))).append(" (").append(value).append(')');
            if (Relations.truce(c.world(), l.a(), l.b(), c.day())) {
                out.append(", trêve");
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------- atelier

    private static String workshop(Ctx c) {
        Workshop w = hamletWorkshop(c.world(), c.village());
        if (w == null) {
            return "Le lieu-dit n'a pas d'atelier.";
        }
        StringBuilder out = new StringBuilder(TextRender.text(WorkshopPage.textBody(c.store(), c.ref(), c.village(), w), c.language()));
        out.append("\nRang du joueur ici : ").append(c.t(Message.translation(Reputations.rank(c.store(), c.ref(), c.village()).nameKey())));
        out.append("\nLes demandes de l'atelier se prennent par numéro de case (1, 2, 3).");
        return out.toString();
    }
}
