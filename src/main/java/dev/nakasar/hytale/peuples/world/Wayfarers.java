package dev.nakasar.hytale.peuples.world;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.klops.Klops;
import dev.nakasar.hytale.peuples.player.PeuplesPlayer;
import dev.nakasar.hytale.peuples.reputation.Reputations;
import dev.nakasar.hytale.peuples.request.GameClock;
import dev.nakasar.hytale.peuples.request.Requests;
import dev.nakasar.hytale.peuples.steward.Speaker;
import dev.nakasar.hytale.peuples.steward.StewardConfig;
import dev.nakasar.hytale.peuples.steward.TextRender;
import dev.nakasar.hytale.peuples.village.People;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.village.Villages;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les PNJ de passage qui parlent (Kevin, 18 novembre 2026) : le Klops errant, les voyageurs des routes (marchand
 * ambulant, pèlerin, messager, caravane), les caravaniers des routes commerciales, les marchands en tournée des lieux-dits,
 * les aubergistes et les campeurs. Pour chacun, {@link #speaker} dit au modèle qui il est, ce qu'il sait (sa route, les
 * rumeurs du moment, le courrier que porte le joueur) et ce qu'il peut faire, avec les fonctions de son dialogue :
 * <ul>
 *   <li>Klops : confier un colis ({@code KlopsParcel}) ;</li>
 *   <li>messager : prendre sa lettre, ou une lettre de paix ({@code Letter}, {@code Parley}) ;</li>
 *   <li>pèlerin, caravane, caravanier : escorter ({@code Escort}, {@code TradeEscort}) ;</li>
 *   <li>aubergiste : prendre une lettre ({@code InnLetter}).</li>
 * </ul>
 * Le troc, les quêtes des campements et les dons restent aux réponses habituelles (ils ouvrent une page ou dépensent).
 * Tout est lu sur le thread du monde, au moment du message : un voyageur a pu avancer, une rumeur s'éteindre.
 */
public final class Wayfarers {

    /** Sujet « qui je suis et où j'en suis », aussi donné d'emblée au modèle. */
    public static final String ME = "moi";
    public static final String NEWS = "nouvelles";
    public static final String MAIL = "courrier";
    public static final String KLOPS = "klops";

    private Wayfarers() {
    }

    /** Le PNJ `npc` est-il un PNJ de passage qui sait parler librement ? */
    public static boolean is(World world, Ref<EntityStore> npc) {
        if (npc == null || !npc.isValid()) {
            return false;
        }
        return Klops.isWanderer(npc) || Travellers.of(world, npc) != null || TradeCaravans.of(world, npc) != null
                || HamletTrips.of(world, npc) != null || Inns.of(world, npc) != null || Camps.of(world, npc) != null;
    }

    /** Le personnage du PNJ `npc` tel qu'il est maintenant, ou null s'il n'est pas (ou plus) de passage. */
    public static Speaker speaker(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Ref<EntityStore> npc) {
        if (npc == null || !npc.isValid()) {
            return null;
        }
        String lang = StewardConfig.get().language;
        if (Klops.isWanderer(npc)) {
            return klops(world, store, ref, player, lang);
        }
        Travellers.Traveller t = Travellers.of(world, npc);
        if (t != null) {
            return traveller(world, store, ref, player, t, lang);
        }
        TradeCaravans.Caravan c = TradeCaravans.of(world, npc);
        if (c != null) {
            return caravan(world, store, ref, player, c, lang);
        }
        HamletTrips.Trip trip = HamletTrips.of(world, npc);
        if (trip != null) {
            return trip(world, store, ref, player, trip, lang);
        }
        Inns.Inn inn = Inns.of(world, npc);
        if (inn != null) {
            return inn(world, store, ref, player, inn, lang);
        }
        Camps.Camp camp = Camps.of(world, npc);
        return camp == null ? null : camp(world, store, ref, player, camp, lang);
    }

    // ------------------------------------------------------------------------------------------------ personnages

    private static Speaker klops(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, String lang) {
        long day = GameClock.day(store);
        Village today = Klops.stop(world, day);
        StringBuilder me = new StringBuilder("Tu es le Klops errant, un petit marchand neutre, d'aucun peuple, qui va chaque jour"
                + " dans un autre village pour troquer (faveurs de tous les peuples contre rouages et marchandises) et confier"
                + " des colis à porter. Ta tournée : ");
        List<String> stops = new ArrayList<>();
        for (int d = 0; d < 4; d++) {
            Village v = Klops.stop(world, day + d);
            if (v != null) {
                stops.add((d == 0 ? "aujourd'hui " : d == 1 ? "demain " : "dans " + d + " jours ") + v.name()
                        + " (" + text(Message.translation(v.people().nameKey()), lang) + ")");
            }
        }
        me.append(stops.isEmpty() ? "aucun village pour l'instant" : String.join(", ", stops)).append('.');
        if (today != null) {
            me.append(" Le joueur est ").append(rank(store, ref, today, lang)).append(" à ").append(today.name()).append('.');
        }
        List<Speaker.Topic> topics = common(world, store, ref, player, me.toString(), lang);
        List<Speaker.Act> acts = List.of(new Speaker.Act("confier_colis",
                "Confie au joueur un colis à porter dans un autre village (tiré au hasard ; il est payé en rouages à la livraison).",
                () -> Klops.giveParcel(world, store, ref, player, Klops.stop(world, GameClock.day(store)))));
        return new Speaker("K", "le Klops errant, petit marchand ambulant qui roule de village en village",
                "Klops : enjoué, pressé et commerçant, phrases vives qui roulent, répète volontiers un mot (« Bonjour bonjour ! »,"
                        + " « Fragile, fragile ! »), parle de troc, de rouages, de colis et de la route ; neutre, ami de tous les peuples.",
                topics, acts);
    }

    private static Speaker traveller(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Travellers.Traveller t,
                                     String lang) {
        Village from = Villages.registry(world).byId(t.from);
        Village to = Villages.registry(world).byId(t.to);
        if (from == null || to == null) {
            return null;
        }
        String kind = text(Message.translation(Travellers.KEYS + "kind." + t.kind.key()), lang);
        Links.Link link = Links.of(world, t.from, t.to);
        StringBuilder me = new StringBuilder("Tu es un ").append(kind).append(" du peuple ").append(people(from.people(), lang))
                .append(", parti de ").append(from.name()).append(" pour ").append(to.name()).append(". ")
                .append(progress(t.at, link == null ? 0 : link.length())).append(' ');
        switch (t.kind) {
            case MERCHANT -> me.append("Tu troques ce que tu portes ; tes meilleurs prix vont aux amis de ton village.");
            case PILGRIM -> me.append("Les routes ne sont plus sûres : tu aimerais qu'on t'escorte jusqu'au bout.");
            case MESSENGER -> me.append("Tu portes une lettre pour ").append(to.name())
                    .append(" ; tu la confierais volontiers au joueur pour rentrer chez toi. Entre villages rivaux, ce peut être une"
                            + " lettre de paix, si le joueur est Ami des deux.");
            case CARAVAN -> me.append("Ta caravane troque et serait contente d'être escortée.");
        }
        if (player.getUuid().toString().equals(t.escort)) {
            me.append(" Le joueur t'escorte déjà.");
        }
        me.append(" Le joueur est ").append(rank(store, ref, from, lang)).append(" à ").append(from.name()).append(" et ")
                .append(rank(store, ref, to, lang)).append(" à ").append(to.name()).append('.');
        int relation = Relations.value(world, from, to);
        String state = Relations.state(relation);
        if (!state.equals("neutral")) {
            me.append(' ').append(text(Message.translation(Rumours.KEYS + "relation." + state).param("a", from.name()).param("b", to.name()), lang));
        }
        List<Speaker.Topic> topics = common(world, store, ref, player, me.toString(), lang);
        List<Speaker.Act> acts = new ArrayList<>();
        switch (t.kind) {
            case MESSENGER -> {
                acts.add(new Speaker.Act("prendre_lettre", "Le joueur prend ta lettre pour " + to.name() + " ; toi, tu rentres chez toi.",
                        () -> fresh(world, t.id) != null && Travellers.letter(world, store, ref, player, fresh(world, t.id), false)));
                if (relation <= Relations.RIVAL) {
                    acts.add(new Speaker.Act("lettre_de_paix", "Le joueur porte une lettre de paix de " + from.name() + " à " + to.name()
                            + " (il doit être Ami des deux villages) ; toi, tu rentres chez toi.",
                            () -> fresh(world, t.id) != null && Travellers.letter(world, store, ref, player, fresh(world, t.id), true)));
                }
            }
            case PILGRIM, CARAVAN -> acts.add(new Speaker.Act("escorter", "Le joueur t'escorte jusqu'à " + to.name() + ".",
                    () -> fresh(world, t.id) != null && Travellers.escort(world, player, fresh(world, t.id))));
            default -> {
            }
        }
        return new Speaker("T:" + t.id, "un " + kind + " " + people(from.people(), lang) + " sur la route de " + from.name() + " à " + to.name(),
                voice(from.people()), topics, acts);
    }

    private static Travellers.Traveller fresh(World world, String id) {
        return Travellers.get(world, id);
    }

    private static Speaker caravan(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, TradeCaravans.Caravan c,
                                   String lang) {
        Village from = TradeRoutes.village(world, c.from);
        Village to = TradeRoutes.village(world, c.to);
        if (from == null || to == null) {
            return null;
        }
        String fromName = text(TradeRoutes.name(world, from), lang);
        String toName = text(TradeRoutes.name(world, to), lang);
        String me = "Tu es caravanier d'une route commerciale, du peuple " + people(from.people(), lang) + ". "
                + text(Message.translation(TradeRoutes.KEYS + "where").param("from", TradeRoutes.name(world, from))
                .param("to", TradeRoutes.name(world, to)).param("goods", TradeCaravans.goodsLine(c.cargo)), lang)
                + " " + progress(c.at, Links.path(world, from, to).length())
                + " Une escorte est la bienvenue : les routes ne sont pas sûres. Le joueur est " + rank(store, ref, from, lang)
                + " à " + fromName + ".";
        List<Speaker.Topic> topics = common(world, store, ref, player, me, lang);
        String id = c.id;
        List<Speaker.Act> acts = List.of(new Speaker.Act("escorter", "Le joueur escorte ta caravane jusqu'à " + toName + ".", () -> {
            TradeCaravans.Caravan now = TradeCaravans.get(world, id);
            return now != null && TradeCaravans.escort(world, player, now);
        }));
        return new Speaker("C:" + id, "un caravanier " + people(from.people(), lang) + " qui mène ses bêtes de " + fromName + " à " + toName,
                voice(from.people()), topics, acts);
    }

    private static Speaker trip(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, HamletTrips.Trip t, String lang) {
        Village v = Villages.registry(world).byId(t.village());
        Hamlets.Place h = Hamlets.get(world, t.hamlet());
        if (v == null || h == null) {
            return null;
        }
        String hamlet = text(h.name(), lang);
        String leg = switch (t.leg()) {
            case HamletTrips.OUT -> "Tu vas commercer à " + hamlet + ", puis tu rentreras à " + v.name() + ".";
            case HamletTrips.STAY -> "Tu commerces ici, à " + hamlet + ", puis tu rentreras à " + v.name() + ".";
            default -> "Tu rentres à " + v.name() + " avec ce que tu as acheté à " + hamlet + ".";
        };
        String me = "Tu es un marchand en tournée des lieux-dits pour ton village, " + v.name() + " (peuple "
                + people(v.people(), lang) + ") : on achète ici, on revend là-bas. " + leg + " Le joueur est "
                + rank(store, ref, v, lang) + " à " + v.name() + ".";
        return new Speaker("L:" + t.id(), "un marchand " + people(v.people(), lang) + " en tournée entre " + v.name() + " et " + hamlet,
                voice(v.people()), common(world, store, ref, player, me, lang), List.of());
    }

    private static Speaker inn(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Inns.Inn inn, String lang) {
        String[] ids = inn.link().split("\\|");
        Village a = Villages.registry(world).byId(ids[0]);
        Village b = ids.length > 1 ? Villages.registry(world).byId(ids[1]) : null;
        Village first = Inns.firstVillage(world, inn);
        if (a == null || first == null) {
            return null;
        }
        String between = b == null ? a.name() : a.name() + " et " + b.name();
        String me = "Tu tiens une auberge de relais sur la route entre " + between + ", tenue par le peuple "
                + people(first.people(), lang) + ". Tu sers la soupe, loges les voyageurs et entends toutes les nouvelles de la route."
                + (inn.level() >= 2 ? " Ton auberge a grandi avec le passage." : "")
                + " Tu peux confier au joueur une lettre pour un des villages de la route.";
        List<Speaker.Act> acts = List.of(new Speaker.Act("prendre_lettre",
                "Confie au joueur une lettre pour " + between + " (l'un des deux, au hasard).", () -> {
            Village to = Villages.registry(world).byId(ids[ThreadLocalRandom.current().nextInt(ids.length)]);
            return to != null && Travellers.giveLetter(world, store, ref, player, to, null);
        }));
        return new Speaker("I:" + inn.link(), "l'aubergiste " + people(first.people(), lang) + " du relais entre " + between,
                voice(first.people()) + " Aubergiste : accueillant, bavard, aime colporter les nouvelles.",
                common(world, store, ref, player, me, lang), acts);
    }

    private static Speaker camp(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Camps.Camp c, String lang) {
        Village v = Villages.registry(world).byId(c.village());
        if (v == null) {
            return null;
        }
        String type = text(Message.translation(Camps.KEYS + "type." + c.type().key()), lang);
        String dir = text(Message.translation(Villages.MESSAGES + "dir." + Requests.direction(c.x() - v.x(), c.z() - v.z())), lang);
        String me = "Tu fais partie d'un " + type + " du peuple " + people(v.people(), lang) + ", installé au " + dir + " de "
                + v.name() + ". Ce que ton campement demande ou offre se règle par les réponses habituelles. Le joueur est "
                + rank(store, ref, v, lang) + " à " + v.name() + ".";
        return new Speaker("P:" + c.id, "un membre du " + type + " " + people(v.people(), lang) + " près de " + v.name(),
                voice(v.people()), common(world, store, ref, player, me, lang), List.of());
    }

    // ------------------------------------------------------------------------------------------------ ce qu'ils savent

    /** Les sujets communs : qui je suis, les nouvelles des routes, le courrier du joueur, la tournée du Klops. */
    private static List<Speaker.Topic> common(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, String me,
                                              String lang) {
        List<Speaker.Topic> out = new ArrayList<>();
        out.add(new Speaker.Topic(ME, "qui tu es, d'où tu viens, où tu vas, ce que tu peux faire pour le joueur", () -> me));
        out.add(new Speaker.Topic(NEWS, "toutes les nouvelles des routes en ce moment (voyageurs, caravanes, campements, villages, foire, vestiges)",
                () -> news(world, store, ref, lang)));
        out.add(new Speaker.Topic(MAIL, "le colis ou la lettre que porte le joueur, et où le livrer",
                () -> mail(world, store, ref, lang)));
        out.add(new Speaker.Topic(KLOPS, "où passe le Klops errant ces jours-ci", () -> klopsRoute(world, store, lang)));
        return out;
    }

    static String news(World world, Store<EntityStore> store, Ref<EntityStore> ref, String lang) {
        Set<String> lines = new LinkedHashSet<>();
        for (Message m : Rumours.all(world, store, ref)) {
            lines.add(text(m, lang));
        }
        if (lines.isEmpty()) {
            return text(Message.translation(Rumours.KEYS + "none"), lang);
        }
        return "Ce qu'on raconte sur les routes (vrai, dis-le avec les noms exacts) :\n- " + String.join("\n- ", lines);
    }

    static String mail(World world, Store<EntityStore> store, Ref<EntityStore> ref, String lang) {
        PeuplesPlayer data = PeuplesPlayer.get(store, ref);
        List<String> out = new ArrayList<>();
        String parcel = Klops.parcelDestination(data);
        Village p = parcel == null ? null : Villages.registry(world).byId(parcel);
        if (p != null) {
            out.add("Le joueur porte un colis du Klops pour " + where(store, ref, p, lang) + ", à porter près du cœur du village.");
        }
        if (data != null) {
            for (String f : data.flags()) {
                if (f.startsWith(Travellers.LETTER_FLAG)) {
                    String[] ids = f.substring(Travellers.LETTER_FLAG.length()).split(":");
                    Village to = Villages.registry(world).byId(ids[0]);
                    Village peace = ids.length > 1 ? Villages.registry(world).byId(ids[1]) : null;
                    if (to != null) {
                        out.add("Le joueur porte une lettre" + (peace == null ? "" : " de paix de " + peace.name()) + " pour "
                                + where(store, ref, to, lang) + ", à porter près du cœur du village.");
                    }
                }
            }
        }
        return out.isEmpty() ? "Le joueur ne porte ni colis ni lettre." : String.join("\n", out);
    }

    static String klopsRoute(World world, Store<EntityStore> store, String lang) {
        long day = GameClock.day(store);
        List<String> stops = new ArrayList<>();
        for (int d = 0; d < 3; d++) {
            Village v = Klops.stop(world, day + d);
            if (v != null) {
                stops.add((d == 0 ? "aujourd'hui à " : d == 1 ? "demain à " : "après-demain à ") + v.name());
            }
        }
        return stops.isEmpty() ? "Personne n'a vu le Klops ces temps-ci." : "Le Klops errant passe " + String.join(", ", stops) + ".";
    }

    /** « Chêneclair (Kweebec), à 340 blocs d'ici, direction nord-est ». */
    private static String where(Store<EntityStore> store, Ref<EntityStore> ref, Village v, String lang) {
        Vector3d at = Requests.position(store, ref);
        String name = v.name() + " (" + people(v.people(), lang) + ")";
        if (at == null) {
            return name;
        }
        double dx = v.x() - at.x();
        double dz = v.z() - at.z();
        return name + ", à " + Math.round(Math.hypot(dx, dz)) + " blocs d'ici, direction "
                + text(Message.translation(Villages.MESSAGES + "dir." + Requests.direction(dx, dz)), lang);
    }

    private static String progress(double at, double length) {
        if (length <= 0) {
            return "Tu es en route.";
        }
        double f = at / length;
        return f < 0.25 ? "Tu viens à peine de partir." : f < 0.6 ? "Tu es à peu près à mi-chemin." : "Tu touches presque au but.";
    }

    private static String rank(Store<EntityStore> store, Ref<EntityStore> ref, Village v, String lang) {
        return text(Message.translation(Reputations.rank(store, ref, v).nameKey()), lang);
    }

    private static String people(People p, String lang) {
        return text(Message.translation(p.nameKey()), lang);
    }

    private static String text(Message m, String lang) {
        return TextRender.text(m, lang);
    }

    /** La voix de chaque peuple (comme le gestionnaire, steward/Steward). */
    static String voice(People people) {
        return switch (people) {
            case KWEEBEC -> "Kweebec : jovial, bavard et serviable, attaché à sa forêt et à ses voisins.";
            case TRORK -> "Trork : bourru et direct, phrases très courtes, grammaire rudimentaire (« Toi marcher avec moi, route dangereuse »),"
                    + " respecte la force et le travail.";
            case FERAN -> "Feran : calme et imagé, parle du vent, du sable et de l'eau, accueillant mais fier.";
            case RENONCE -> "Renoncé : méfiant, parle bas, ancien du culte qui a tout quitté ; sec, prudent, loyal envers qui l'aide.";
            case SLOTHIAN -> "Slothian : lent et serein, phrases hachées de points de suspension, sage, inquiet du Vide.";
        };
    }
}
