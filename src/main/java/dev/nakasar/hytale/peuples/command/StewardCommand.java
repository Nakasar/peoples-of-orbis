package dev.nakasar.hytale.peuples.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.steward.Steward;
import dev.nakasar.hytale.peuples.steward.StewardConfig;
import dev.nakasar.hytale.peuples.village.Village;

/**
 * {@code /village gestionnaire} (opérateurs) : le gestionnaire qui parle ({@link Steward}).
 * <ul>
 *   <li>{@code etat} : activé ou non, backend et modèle, clé présente, jetons du jour, dernière erreur ;</li>
 *   <li>{@code lire <onglet>} : ce que le modèle lit d'un onglet du panneau du village le plus proche (aucun appel) ;</li>
 *   <li>{@code recharger} : relit {@value StewardConfig#FILE} et lève la pause après des échecs.</li>
 * </ul>
 */
final class StewardCommand extends AbstractCommandCollection {

    private static final String KEYS = VillageCommand.KEYS + "steward.";

    StewardCommand() {
        super("gestionnaire", KEYS + "desc");
        requirePermission(VillageCommand.ADMIN);
        addSubCommand(new StatusCommand());
        addSubCommand(new ReadCommand());
        addSubCommand(new ReloadCommand());
    }

    private static final class StatusCommand extends AbstractPlayerCommand {
        StatusCommand() {
            super("etat", KEYS + "status.desc");
            requirePermission(VillageCommand.ADMIN);
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            for (String line : Steward.status()) {
                player.sendMessage(Message.raw(line));
            }
        }
    }

    private static final class ReadCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> tab = withRequiredArg("onglet", KEYS + "read.tab", ArgTypes.STRING);

        ReadCommand() {
            super("lire", KEYS + "read.desc");
            requirePermission(VillageCommand.ADMIN);
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            Village village = VillageCommand.nearest(world, player);
            if (village == null) {
                return;
            }
            for (String line : Steward.readTab(world, store, ref, player, village.id(), tab.get(context)).split("\n")) {
                player.sendMessage(Message.raw(line));
            }
        }
    }

    private static final class ReloadCommand extends AbstractPlayerCommand {
        ReloadCommand() {
            super("recharger", KEYS + "reload.desc");
            requirePermission(VillageCommand.ADMIN);
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String error = StewardConfig.reload();
            Steward.resetPause();
            player.sendMessage(error == null ? Message.translation(KEYS + "reloaded") : Message.raw(error));
            for (String line : Steward.status()) {
                player.sendMessage(Message.raw(line));
            }
        }
    }
}
