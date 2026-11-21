package dev.nakasar.hytale.peuples.request;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.batir.BuildCatalog;
import dev.nakasar.hytale.peuples.batir.BuildPage;
import dev.nakasar.hytale.peuples.village.Village;
import dev.nakasar.hytale.peuples.world.DomainPage;
import dev.nakasar.hytale.peuples.world.WorldPage;

/**
 * Barre des onglets en haut du panneau du village (maquette Claude Design « Panneau du village — refonte », Kevin,
 * 16 novembre 2026) : Tableau, Bâtir (tous les bâtiments classés par usage, Kevin 2026-11-21, à la place de Projets,
 * Production et Ouvrages), Monde, Domaine (conquête des lieux-dits), Commerce (routes commerciales), Habitants
 * (générations), et l'heure du jeu. Une icône par onglet (Kevin, 17 novembre 2026 : les noms
 * coupés égaraient), son nom en info-bulle, et celui de l'onglet ouvert à côté de la barre ({@code #TabName<X>}).
 * Chaque page du panneau porte la même barre ({@code tab_strip} de {@code tools/generate_board_ui.py}) : l'onglet
 * ouvert est un aplat ({@code #Tab<X>On}), les autres des boutons à icône ({@code #Tab<X>Button}). Les pages appellent
 * {@link #fill} dans leur {@code build} et {@link #handle} en tête de leur {@code handleDataEvent}.
 */
public final class BoardTabs {

    /** Action des boutons d'onglet : {@code Tab<X>}. */
    private static final String PREFIX = "Tab";

    private BoardTabs() {
    }

    /** Textes des onglets, ceux qui s'ouvrent ici, et l'heure. */
    public static void fill(UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store, Village village) {
        tab(cmd, events, "Board", "#TabBoardButton", "#TabNameBoard", Message.translation(WorldPage.KEYS + "tab.board"));
        tab(cmd, events, "Build", "#TabBuildButton", "#TabNameBuild", Message.translation(BuildCatalog.KEYS + "tab"));
        tab(cmd, events, "World", "#TabWorldButton", "#TabNameWorld", Message.translation(WorldPage.KEYS + "button"));
        tab(cmd, events, "Domain", "#TabDomainButton", "#TabNameDomain", Message.translation(DomainPage.KEYS + "tab"));
        tab(cmd, events, "Trade", "#TabTradeButton", "#TabNameTrade",
                Message.translation(dev.nakasar.hytale.peuples.world.TradePage.TAB));
        tab(cmd, events, "People", "#TabPeopleButton", "#TabNamePeople",
                Message.translation(dev.nakasar.hytale.peuples.population.Population.KEYS + "page.tab"));
        // Bâtir : tous les bâtiments par usage (Kevin, 2026-11-21), à la place d'Emplacements, Avant-postes et Ouvrages.
        cmd.set("#TabBuild.Visible", BuildPage.applies(village));
        // Habitants : les villages qui vivent les générations (plan, pas un lieu-dit).
        cmd.set("#TabPeople.Visible", dev.nakasar.hytale.peuples.population.Population.applies(village));
        cmd.set("#Clock.TextSpans", Message.translation(WorldPage.KEYS + "clock").param("day", GameClock.day(store) + 1)
                .param("hour", GameClock.hour(store)));
    }

    /** Un onglet : son nom en info-bulle du bouton à icône, et à côté de la barre quand c'est l'onglet ouvert. */
    private static void tab(UICommandBuilder cmd, UIEventBuilder events, String id, String button, String name, Message text) {
        cmd.set(button + ".TooltipTextSpans", text);
        cmd.set(name + ".TextSpans", text);
        events.addEventBinding(CustomUIEventBindingType.Activating, button, EventData.of("Action", PREFIX + id));
    }

    /** Ouvre l'onglet demandé ; vrai si l'action était un onglet (la page appelante n'a plus rien à faire). */
    public static boolean handle(String action, World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
                                 Village village) {
        if (action == null || !action.startsWith(PREFIX)) {
            return false;
        }
        switch (action.substring(PREFIX.length())) {
            case "Board" -> BoardPage.open(world, store, ref, playerRef, village);
            case "Build" -> BuildPage.open(store, ref, playerRef, village);
            case "World" -> WorldPage.open(store, ref, playerRef, village);
            case "Domain" -> DomainPage.open(store, ref, playerRef, village);
            case "Trade" -> dev.nakasar.hytale.peuples.world.TradePage.open(store, ref, playerRef, village);
            case "People" -> dev.nakasar.hytale.peuples.population.PeoplePage.open(store, ref, playerRef, village);
            default -> {
                return false;
            }
        }
        return true;
    }
}
