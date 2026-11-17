package dev.nakasar.hytale.peuples.steward;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.Message;
import dev.nakasar.hytale.peuples.annex.Annexes;
import dev.nakasar.hytale.peuples.annex.Infrastructures;
import dev.nakasar.hytale.peuples.annex.Ouvrages;
import dev.nakasar.hytale.peuples.population.Population;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.request.VillageRequest;
import dev.nakasar.hytale.peuples.village.Chantiers;
import dev.nakasar.hytale.peuples.village.Plots;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.VillageLayout;
import dev.nakasar.hytale.peuples.workshop.Workshop;
import dev.nakasar.hytale.peuples.workshop.Workshops;
import dev.nakasar.hytale.peuples.world.Colours;
import dev.nakasar.hytale.peuples.world.Embassies;
import dev.nakasar.hytale.peuples.world.Hamlets;
import dev.nakasar.hytale.peuples.world.Sieges;
import dev.nakasar.hytale.peuples.world.TradeCatalogue;
import dev.nakasar.hytale.peuples.world.TradeGoods;
import dev.nakasar.hytale.peuples.world.TradePage;
import dev.nakasar.hytale.peuples.world.TradeRoutes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Ce que le gestionnaire peut faire pour le joueur : un outil nommé par action, qui appelle directement la fonction du
 * jeu derrière le bouton du panneau (pas l'interface ; Kevin, 17 novembre 2026). Mêmes fonctions, donc mêmes contrôles de
 * rang, de faveurs et de matériaux ; leurs refus passent par le joueur capturé ({@link CapturingPlayerRef}) et sont
 * montrés tels quels.
 * <p>
 * Chaque action est d'abord <b>résolue</b> ({@link #resolve}) : paramètres vérifiés contre l'état du moment, libellé
 * lisible, et ce qu'il faudra appeler. Une action qui dépense ou détruit ({@link Spec#confirm}) attend le clic du joueur
 * sur Confirmer ; elle est résolue de nouveau à ce moment-là (l'état a pu changer). Toujours sur le thread du monde.
 */
final class StewardActions {

    /** Un outil d'action : nom, description pour le modèle, confirmation, paramètres {nom, type, description}. */
    record Spec(String name, String description, boolean confirm, Scope scope, String[][] params) {
    }

    enum Scope { VILLAGE, HAMLET, BOTH }

    /** Résultat d'un appel : fait ou refusé, et le refus rendu par la fonction (les autres passent par le tchat capturé). */
    record Outcome(boolean ok, Message why) {
        static Outcome of(boolean ok) {
            return new Outcome(ok, null);
        }

        static Outcome of(Message why) {
            return new Outcome(why == null, why);
        }
    }

    /** Une action prête : son libellé, si elle attend Confirmer, et l'appel. */
    record Resolved(String label, boolean confirm, Supplier<Outcome> exec) {
    }

    /** Paramètres invalides : le message pour le modèle. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    private static final String CASE = "Numéro de case, 1 à 3.";

    static final List<Spec> SPECS = List.of(
            new Spec("prendre_demande", "Prend une demande du tableau (ou de l'atelier du lieu-dit).", false, Scope.BOTH,
                    new String[][]{{"case", "integer", CASE}}),
            new Spec("abandonner_demande", "Abandonne une demande prise par le joueur.", true, Scope.BOTH,
                    new String[][]{{"case", "integer", CASE}}),
            new Spec("rendre_demandes", "Rend les objets portés pour les demandes prises (comme le bouton Rendre).", false, Scope.BOTH,
                    new String[][]{}),
            new Spec("choisir_projet", "Choisit le projet d'un emplacement (rien n'est dépensé).", false, Scope.VILLAGE,
                    new String[][]{{"emplacement", "string", "Id de l'emplacement (ex. E1)."}, {"projet", "string", "Id du projet."}}),
            new Spec("retirer_projet", "Retire le projet choisi d'un emplacement (avant le chantier).", false, Scope.VILLAGE,
                    new String[][]{{"emplacement", "string", "Id de l'emplacement."}}),
            new Spec("lancer_chantier", "Lance le chantier du projet choisi (prend les matériaux au dépôt).", true, Scope.VILLAGE,
                    new String[][]{{"emplacement", "string", "Id de l'emplacement."}}),
            new Spec("demolir", "Démolit le bâtiment d'un emplacement.", true, Scope.VILLAGE,
                    new String[][]{{"emplacement", "string", "Id de l'emplacement."}}),
            new Spec("accorder_annexe", "Accorde une annexe ou un ouvrage : le joueur reçoit la fondation à poser.", true, Scope.VILLAGE,
                    new String[][]{{"annexe", "string", "Id de l'annexe ou de l'ouvrage."}}),
            new Spec("detruire_annexe", "Détruit une annexe ou un ouvrage du village.", true, Scope.VILLAGE,
                    new String[][]{{"annexe", "string", "Id de l'annexe ou de l'ouvrage."}}),
            new Spec("reprendre_ouvrage", "Envoie reprendre un ouvrage du village occupé par un rival.", true, Scope.VILLAGE,
                    new String[][]{{"ouvrage", "string", "Id de l'ouvrage (lu dans ouvrages)."}}),
            new Spec("rebatir_ouvrage", "Rebâtit un ouvrage en ruine (matériaux).", true, Scope.VILLAGE,
                    new String[][]{{"ouvrage", "string", "Id de l'ouvrage."}}),
            new Spec("attaquer_ouvrage", "Demande l'attaque d'un ouvrage rival (offrande).", true, Scope.VILLAGE,
                    new String[][]{{"ouvrage", "string", "Id de l'ouvrage."}}),
            new Spec("ouvrir_route", "Ouvre une route commerciale avec un partenaire possible (offrande).", true, Scope.VILLAGE,
                    new String[][]{{"partenaire", "string", "Id du partenaire."}}),
            new Spec("monter_route", "Monte d'un niveau une route commerciale (offrande).", true, Scope.VILLAGE,
                    new String[][]{{"partenaire", "string", "Id du partenaire de la route."}}),
            new Spec("payer_credit", "Paie du crédit sur une route commerciale (une faveur).", true, Scope.VILLAGE,
                    new String[][]{{"partenaire", "string", "Id du partenaire de la route."}}),
            new Spec("fermer_route", "Ferme une route commerciale.", true, Scope.VILLAGE,
                    new String[][]{{"partenaire", "string", "Id du partenaire de la route."}}),
            new Spec("regler_envoi", "Règle une case d'une route : ce que le village envoie, ou ce qu'il reçoit.", false, Scope.VILLAGE,
                    new String[][]{{"partenaire", "string", "Id du partenaire de la route."},
                            {"sens", "string", "envoi (ce que le village envoie) ou reception (ce que le partenaire envoie)."},
                            {"case", "integer", "Numéro de case, 1 au niveau de la route."},
                            {"objet", "string", "Id de l'objet, ou vide pour vider la case."}}),
            new Spec("acheter_etal", "Achète un lot de l'étal contre une faveur.", true, Scope.VILLAGE,
                    new String[][]{{"objet", "string", "Id de l'objet de l'étal."}}),
            new Spec("ambassade", "Envoie une ambassade vers un lieu-dit (offrande).", true, Scope.VILLAGE,
                    new String[][]{{"lieu_dit", "string", "Id du lieu-dit."}}),
            new Spec("expedition", "Lance une expédition contre un lieu-dit (offrande).", true, Scope.VILLAGE,
                    new String[][]{{"lieu_dit", "string", "Id du lieu-dit."}}),
            new Spec("porter_couleurs", "Le joueur prend (ou quitte) les couleurs du village.", false, Scope.VILLAGE,
                    new String[][]{}),
            new Spec("refuge_suivant", "Passe au refuge suivant pour les raids.", false, Scope.VILLAGE,
                    new String[][]{}),
            new Spec("nommer_enfant", "Donne un des noms proposés au nouveau-né.", true, Scope.VILLAGE,
                    new String[][]{{"nom", "integer", "Numéro du nom proposé."}}),
            new Spec("orienter_apprenti", "Oriente un apprenti vers un poste.", true, Scope.VILLAGE,
                    new String[][]{{"habitant", "string", "Id de l'apprenti."}, {"poste", "string", "Id du poste."}}),
            new Spec("devenir_maitre", "Prend la demande de maître de l'atelier du lieu-dit.", true, Scope.HAMLET,
                    new String[][]{}),
            new Spec("ameliorer_atelier", "Lance l'amélioration de l'atelier du lieu-dit (matériaux).", true, Scope.HAMLET,
                    new String[][]{}),
            new Spec("prendre_part", "Prend la part du joueur dans la production de l'atelier.", false, Scope.HAMLET,
                    new String[][]{}));

    private StewardActions() {
    }

    static Spec spec(String name) {
        for (Spec s : SPECS) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    static boolean applies(Spec s, Village v) {
        boolean hamlet = StewardData.hamlet(v);
        return s.scope() == Scope.BOTH || (s.scope() == Scope.HAMLET) == hamlet;
    }

    /** Les outils d'action de ce village, au format de l'API Claude. */
    static void tools(Village v, JsonArray out) {
        for (Spec s : SPECS) {
            if (!applies(s, v)) {
                continue;
            }
            JsonObject props = new JsonObject();
            JsonArray required = new JsonArray();
            for (String[] p : s.params()) {
                JsonObject prop = new JsonObject();
                prop.addProperty("type", p[1]);
                prop.addProperty("description", p[2]);
                props.add(p[0], prop);
                if (!p[0].equals("objet") || !s.name().equals("regler_envoi")) {
                    required.add(p[0]);
                }
            }
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", props);
            schema.add("required", required);
            JsonObject tool = new JsonObject();
            tool.addProperty("name", s.name());
            tool.addProperty("description", s.description() + (s.confirm() ? " Attend la confirmation du joueur." : ""));
            tool.add("input_schema", schema);
            out.add(tool);
        }
    }

    /** Vérifie l'action contre l'état du moment et prépare son appel ; {@link Invalid} si elle ne tient pas. */
    static Resolved resolve(StewardData.Ctx c, Spec s, JsonObject in) {
        Village v = c.village();
        boolean hamlet = StewardData.hamlet(v);
        return switch (s.name()) {
            case "prendre_demande" -> {
                int slot = slot(in, "case");
                VillageRequest r = request(c, slot);
                String title = c.t(Requests.title(r.template()));
                if (hamlet) {
                    Workshop w = hamletWorkshop(c);
                    yield new Resolved("Prendre la demande « " + title + " »", false,
                            () -> Outcome.of(Workshops.take(c.world(), c.store(), c.ref(), c.player(), v, w, slot)));
                }
                yield new Resolved("Prendre la demande « " + title + " »", false,
                        () -> Outcome.of(Requests.take(c.world(), c.store(), c.ref(), c.player(), v, slot)));
            }
            case "abandonner_demande" -> {
                int slot = slot(in, "case");
                VillageRequest r = request(c, slot);
                yield new Resolved("Abandonner la demande « " + c.t(Requests.title(r.template())) + " »", true,
                        () -> Outcome.of(Requests.abandon(c.world(), c.store(), c.ref(), c.player(), r.id())));
            }
            case "rendre_demandes" -> new Resolved("Rendre les objets des demandes", false,
                    () -> Outcome.of(Requests.turnIn(c.world(), c.store(), c.ref(), c.player(), v) > 0));
            case "choisir_projet" -> {
                VillageLayout.Plot plot = plot(c, in);
                String project = str(in, "projet");
                VillageLayout.Building b = null;
                for (VillageLayout.Building x : Plots.catalog(v, plot.id())) {
                    if (x.id().equalsIgnoreCase(project == null ? "" : project)) {
                        b = x;
                    }
                }
                if (b == null) {
                    throw new Invalid("Projet inconnu pour " + plot.id() + " : lis le sujet projets.");
                }
                String id = b.id();
                yield new Resolved("Choisir " + c.t(Message.translation(Chantiers.buildingKey(id))) + " sur " + plot.id(), false,
                        () -> Outcome.of(Plots.choose(c.world(), c.store(), c.ref(), c.player(), v, plot, id)));
            }
            case "retirer_projet" -> {
                VillageLayout.Plot plot = plot(c, in);
                yield new Resolved("Retirer le projet de " + plot.id(), false,
                        () -> Outcome.of(Plots.clear(c.store(), c.ref(), c.player(), v, plot)));
            }
            case "lancer_chantier" -> {
                VillageLayout.Plot plot = plot(c, in);
                String project = v.plot(plot.id()).project();
                if (project == null) {
                    throw new Invalid("Aucun projet choisi sur " + plot.id() + ".");
                }
                if (v.isBuilt(project)) {
                    throw new Invalid("Le bâtiment de " + plot.id() + " est déjà debout.");
                }
                yield new Resolved("Lancer le chantier " + c.t(Message.translation(Chantiers.buildingKey(project))) + " sur " + plot.id(), true,
                        () -> Outcome.of(Plots.start(c.world(), c.store(), c.ref(), c.player(), v, plot)));
            }
            case "demolir" -> {
                VillageLayout.Plot plot = plot(c, in);
                String project = v.plot(plot.id()).project();
                if (project == null || !v.isBuilt(project)) {
                    throw new Invalid("Rien à démolir sur " + plot.id() + ".");
                }
                yield new Resolved("Démolir " + c.t(Message.translation(Chantiers.buildingKey(project))) + " sur " + plot.id(), true,
                        () -> Outcome.of(Plots.demolish(c.world(), c.store(), c.ref(), c.player(), v, plot)));
            }
            case "accorder_annexe" -> {
                Workshop w = annex(c, in);
                yield new Resolved("Accorder " + c.t(Message.translation(w.nameKey())), true,
                        () -> Outcome.of(Annexes.grant(c.world(), c.store(), c.ref(), c.player(), v, w)));
            }
            case "detruire_annexe" -> {
                Workshop w = annex(c, in);
                yield new Resolved("Détruire " + c.t(Message.translation(w.nameKey())), true,
                        () -> Outcome.of(Annexes.destroy(c.world(), c.store(), c.ref(), c.player(), v, w)));
            }
            case "reprendre_ouvrage", "rebatir_ouvrage", "attaquer_ouvrage" -> {
                Infrastructures.Work w = work(c, in, !s.name().equals("attaquer_ouvrage"));
                String name = c.t(Message.translation(w.workshop().nameKey())) + " de " + w.owner().name();
                String owner = w.owner().id();
                String type = w.workshop().getId();
                yield switch (s.name()) {
                    case "reprendre_ouvrage" -> new Resolved("Reprendre " + name, true,
                            () -> Outcome.of(Ouvrages.retake(c.world(), c.store(), c.ref(), v, fresh(c, owner, type))));
                    case "rebatir_ouvrage" -> new Resolved("Rebâtir " + name, true,
                            () -> Outcome.of(Ouvrages.rebuild(c.world(), c.store(), c.ref(), c.player(), v, w.workshop())));
                    default -> new Resolved("Attaquer " + name, true,
                            () -> Outcome.of(Ouvrages.request(c.world(), c.store(), c.ref(), v, fresh(c, owner, type))));
                };
            }
            case "ouvrir_route" -> {
                Village o = partner(c, in, false);
                yield new Resolved("Ouvrir une route avec " + o.name(), true,
                        () -> Outcome.of(TradeRoutes.open(c.world(), c.store(), c.ref(), v, o)));
            }
            case "monter_route", "payer_credit", "fermer_route" -> {
                Village o = partner(c, in, true);
                TradeRoutes.Route r = TradeRoutes.between(c.world(), v, o);
                yield switch (s.name()) {
                    case "monter_route" -> new Resolved("Monter la route avec " + o.name(), true,
                            () -> Outcome.of(TradeRoutes.raise(c.world(), c.store(), c.ref(), v, r)));
                    case "payer_credit" -> new Resolved("Payer du crédit sur la route avec " + o.name(), true,
                            () -> Outcome.of(TradeRoutes.payCredit(c.world(), c.store(), c.ref(), v, r)));
                    default -> new Resolved("Fermer la route avec " + o.name(), true,
                            () -> Outcome.of(TradeRoutes.close(c.world(), c.store(), c.ref(), v, r)));
                };
            }
            case "regler_envoi" -> {
                Village o = partner(c, in, true);
                TradeRoutes.Route r = TradeRoutes.between(c.world(), v, o);
                String sens = str(in, "sens");
                boolean export = sens == null || !sens.toLowerCase(java.util.Locale.ROOT).startsWith("r");
                int j = integer(in, "case") - 1;
                if (j < 0 || j >= r.level()) {
                    throw new Invalid("Case hors de la route (niveau " + r.level() + ").");
                }
                Village sender = export ? v : o;
                Village receiver = export ? o : v;
                String item = str(in, "objet");
                if (item != null && (item.isBlank() || item.equalsIgnoreCase("vide"))) {
                    item = null;
                }
                if (item != null && (!TradeCatalogue.of(c.world(), sender, r.key(), c.day()).containsKey(item)
                        || !TradeGoods.allowed(receiver.people(), item))) {
                    throw new Invalid("Objet impossible sur cette case : lis le sujet commerce.");
                }
                String chosen = item;
                String what = chosen == null ? "vide" : c.t(Message.translation(TradeGoods.itemKey(chosen)));
                yield new Resolved((export ? "Envoyer " : "Recevoir ") + what + " (case " + (j + 1) + ", route avec " + o.name() + ")", false,
                        () -> Outcome.of(TradeRoutes.setCase(c.world(), c.store(), c.ref(), v, r, sender, j, chosen)));
            }
            case "acheter_etal" -> {
                String item = str(in, "objet");
                if (item == null || TradeGoods.itemKey(item) == null) {
                    throw new Invalid("Objet inconnu : lis le sujet commerce.");
                }
                yield new Resolved("Acheter " + TradePage.stallLot(item) + " × " + c.t(Message.translation(TradeGoods.itemKey(item))) + " à l'étal",
                        true, () -> Outcome.of(TradePage.buyLot(c.world(), c.store(), c.ref(), v, item)));
            }
            case "ambassade", "expedition" -> {
                Hamlets.Place h = Hamlets.get(c.world(), str(in, "lieu_dit"));
                if (h == null) {
                    throw new Invalid("Lieu-dit inconnu : lis le sujet domaine.");
                }
                boolean embassy = s.name().equals("ambassade");
                yield new Resolved((embassy ? "Ambassade vers " : "Expédition contre ") + c.t(h.name()), true,
                        () -> Outcome.of(embassy ? Embassies.request(c.world(), c.store(), c.ref(), v, h)
                                : Sieges.request(c.world(), c.store(), c.ref(), v, h)));
            }
            case "porter_couleurs" -> new Resolved("Prendre ou quitter les couleurs du village", false, () -> {
                c.player().sendMessage(Colours.toggle(c.world(), c.store(), c.ref(), v));
                return Outcome.of(true);
            });
            case "refuge_suivant" -> new Resolved("Refuge suivant", false,
                    () -> Outcome.of(Population.cycleRefuge(c.store(), c.ref(), c.player(), v)));
            case "nommer_enfant" -> {
                String[] names = Population.state(v).proposals();
                int n = integer(in, "nom") - 1;
                if (names == null || n < 0 || n >= names.length) {
                    throw new Invalid("Nom inconnu : lis le sujet habitants.");
                }
                yield new Resolved("Nommer l'enfant " + names[n], true,
                        () -> Outcome.of(Population.name(c.world(), c.store(), c.ref(), c.player(), v, n)));
            }
            case "orienter_apprenti" -> {
                String who = str(in, "habitant");
                String post = str(in, "poste");
                var h = Population.state(v).byId(who);
                Population.Post target = null;
                for (Population.Post p : Population.orientChoices(v)) {
                    if (p.id().equals(post)) {
                        target = p;
                    }
                }
                if (h == null || target == null) {
                    throw new Invalid("Apprenti ou poste inconnu : lis le sujet habitants.");
                }
                yield new Resolved("Orienter " + c.t(h.fullName()) + " vers " + c.t(target.name()), true,
                        () -> Outcome.of(Population.orient(c.world(), c.store(), c.ref(), c.player(), v, who, post)));
            }
            case "devenir_maitre" -> {
                Workshop w = hamletWorkshop(c);
                yield new Resolved("Prendre la demande de maître", true,
                        () -> Outcome.of(Workshops.takeMaster(c.world(), c.store(), c.ref(), c.player(), v, w)));
            }
            case "ameliorer_atelier" -> {
                Workshop w = hamletWorkshop(c);
                yield new Resolved("Améliorer l'atelier", true,
                        () -> Outcome.of(Workshops.startUpgrade(c.world(), c.store(), c.ref(), c.player(), v, w)));
            }
            case "prendre_part" -> {
                Workshop w = hamletWorkshop(c);
                yield new Resolved("Prendre ma part de la production", false,
                        () -> Outcome.of(Workshops.takeShare(c.store(), c.ref(), c.player(), v, w)));
            }
            default -> throw new Invalid("Action inconnue.");
        };
    }

    // ---------------------------------------------------------------------------------------------------- paramètres

    private static String str(JsonObject in, String key) {
        return in != null && in.has(key) && in.get(key).isJsonPrimitive() ? in.get(key).getAsString().trim() : null;
    }

    private static int integer(JsonObject in, String key) {
        try {
            return in != null && in.has(key) ? in.get(key).getAsInt() : -1;
        } catch (RuntimeException e) {
            try {
                return Integer.parseInt(str(in, key));
            } catch (RuntimeException e2) {
                return -1;
            }
        }
    }

    /** Case 1 à 3 donnée par le modèle → index 0 à 2. */
    private static int slot(JsonObject in, String key) {
        int n = integer(in, key);
        if (n < 1 || n > Requests.SLOTS) {
            throw new Invalid("Numéro de case invalide (1 à " + Requests.SLOTS + ").");
        }
        return n - 1;
    }

    private static VillageRequest request(StewardData.Ctx c, int slot) {
        Village v = c.village();
        VillageRequest[] board = StewardData.hamlet(v) ? v.workshop(hamletWorkshop(c).getId()).requests() : v.requests();
        if (slot >= board.length || board[slot] == null || board[slot].template() == null) {
            throw new Invalid("Pas de demande dans cette case.");
        }
        return board[slot];
    }

    private static Workshop hamletWorkshop(StewardData.Ctx c) {
        Workshop w = StewardData.hamletWorkshop(c.world(), c.village());
        if (w == null) {
            throw new Invalid("Le lieu-dit n'a pas d'atelier.");
        }
        return w;
    }

    private static VillageLayout.Plot plot(StewardData.Ctx c, JsonObject in) {
        String id = str(in, "emplacement");
        for (VillageLayout.Plot p : Plots.plots(c.village())) {
            if (p.id().equalsIgnoreCase(id == null ? "" : id)) {
                return p;
            }
        }
        throw new Invalid("Emplacement inconnu : lis le sujet projets.");
    }

    private static Workshop annex(StewardData.Ctx c, JsonObject in) {
        String id = str(in, "annexe");
        List<Workshop> all = new ArrayList<>(Annexes.catalog(c.village()));
        all.addAll(Annexes.catalog(c.village(), Workshop.Annex.INFRASTRUCTURE));
        for (Workshop w : all) {
            if (w.getId().equals(id)) {
                return w;
            }
        }
        throw new Invalid("Annexe inconnue : lis le sujet production ou ouvrages.");
    }

    /** L'ouvrage de clé `ouvrage` : un ouvrage du village (`own`) ou une cible qu'il peut attaquer. */
    private static Infrastructures.Work work(StewardData.Ctx c, JsonObject in, boolean own) {
        String key = str(in, "ouvrage");
        List<Infrastructures.Work> list = own ? Infrastructures.all(c.world()) : Ouvrages.targets(c.world(), c.village(), c.day());
        for (Infrastructures.Work w : list) {
            if (Ouvrages.key(w).equals(key) && (!own || w.owner() == c.village())) {
                return w;
            }
        }
        throw new Invalid("Ouvrage inconnu : lis le sujet ouvrages.");
    }

    private static Infrastructures.Work fresh(StewardData.Ctx c, String owner, String type) {
        return Ouvrages.find(c.world(), owner, type);
    }

    /** Un partenaire : avec une route ouverte (`route`), ou parmi les partenaires possibles. */
    private static Village partner(StewardData.Ctx c, JsonObject in, boolean route) {
        Village o = TradeRoutes.village(c.world(), str(in, "partenaire"));
        if (o == null) {
            throw new Invalid("Partenaire inconnu : lis le sujet commerce.");
        }
        if (route && TradeRoutes.between(c.world(), c.village(), o) == null) {
            throw new Invalid("Pas de route avec " + o.name() + ".");
        }
        if (!route && !TradeRoutes.candidates(c.world(), c.village()).contains(o)) {
            throw new Invalid(o.name() + " n'est pas un partenaire possible maintenant.");
        }
        return o;
    }

    /** Le jour du jeu, pour les journaux. */
    static long day(StewardData.Ctx c) {
        return GameClock.day(c.store());
    }
}
