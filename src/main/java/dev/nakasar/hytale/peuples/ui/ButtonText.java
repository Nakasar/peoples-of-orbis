package dev.nakasar.hytale.peuples.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.nakasar.hytale.peuples.steward.TextRender;

/**
 * Texte d'un bouton ({@code #Id.Text}). Le client affiche la propriété {@code .Text} d'un bouton telle quelle : une clé
 * traduite sans paramètre passe, mais les paramètres d'un {@link Message} ne sont jamais remplacés (« OUVRIR AVEC
 * {PARTNER} », « {TAB} · FERMÉ »). Le texte final est donc construit ici, côté serveur, dans la langue du joueur, et
 * envoyé en simple {@link String} : la propriété {@code .Text} est une chaîne côté client, et un {@code Message.raw}
 * (objet) y fait planter le client (« CustomUI Set command couldn't set value. Selector: #Action0.Text -> No
 * parameterless constructor defined for type 'System.String' », Kevin 2026-11-23).
 *
 * <p>Tout texte de bouton ({@code .Text}) qui n'est pas une clé simple ({@code Message.translation(clé)} sans
 * {@code .param}) passe par {@link #of} ou {@link #plain} : {@code tools/CheckButtonText.java} le vérifie au build.
 */
public final class ButtonText {

    private ButtonText() {
    }

    public static String of(PlayerRef player, Message message) {
        return TextRender.text(message, language(player));
    }

    /** Un texte déjà final (nom proposé par le joueur…), envoyé tel quel en chaîne. */
    public static String plain(String text) {
        return text == null ? "" : text;
    }

    private static String language(PlayerRef player) {
        String lang = player == null ? null : player.getLanguage();
        return lang == null || lang.isBlank() ? I18nModule.DEFAULT_LANGUAGE : lang;
    }
}
