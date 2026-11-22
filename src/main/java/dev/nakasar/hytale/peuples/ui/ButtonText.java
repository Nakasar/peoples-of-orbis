package dev.nakasar.hytale.peuples.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.nakasar.hytale.peuples.steward.TextRender;

/**
 * Texte d'un bouton ({@code #Id.Text}). Le client affiche la propriété {@code .Text} d'un bouton telle quelle : une clé
 * traduite sans paramètre passe, mais les paramètres d'un {@link Message} ne sont jamais remplacés (« OUVRIR AVEC
 * {PARTNER} », « {TAB} · FERMÉ »). Le texte final est donc construit ici, côté serveur, dans la langue du joueur.
 *
 * <p>Tout texte de bouton ({@code .Text}) qui n'est pas une clé simple ({@code Message.translation(clé)} sans
 * {@code .param}) ou un {@code Message.raw} passe par {@link #of} : {@code tools/CheckButtonText.java} le vérifie au build.
 */
public final class ButtonText {

    private ButtonText() {
    }

    public static Message of(PlayerRef player, Message message) {
        return Message.raw(TextRender.text(message, language(player)));
    }

    private static String language(PlayerRef player) {
        String lang = player == null ? null : player.getLanguage();
        return lang == null || lang.isBlank() ? I18nModule.DEFAULT_LANGUAGE : lang;
    }
}
