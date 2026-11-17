package dev.nakasar.hytale.peuples.steward;

import com.hypixel.hytale.protocol.BoolParamValue;
import com.hypixel.hytale.protocol.DoubleParamValue;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.protocol.StringParamValue;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import java.util.Map;

/**
 * Un {@link Message} du jeu en texte simple, traduit côté serveur dans la langue voulue : c'est ainsi que le gestionnaire
 * lit le panneau (les mêmes messages que ceux de l'écran). Clés traduites par {@link I18nModule}, paramètres
 * {@code {nom}} remplacés (nombres, textes, messages imbriqués), enfants mis bout à bout. Une clé sans traduction reste
 * telle quelle : le modèle la voit, mieux vaut ça qu'un trou.
 */
public final class TextRender {

    private static final int MAX_DEPTH = 12;

    private TextRender() {
    }

    public static String text(Message message, String language) {
        if (message == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        append(out, message.getFormattedMessage(), language, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, FormattedMessage m, String language, int depth) {
        if (m == null || depth > MAX_DEPTH) {
            return;
        }
        if (m.rawText != null) {
            out.append(m.rawText);
        } else if (m.messageId != null) {
            String pattern = translate(m.messageId, language);
            out.append(fill(pattern, m.params, m.messageParams, language, depth));
        }
        if (m.children != null) {
            for (FormattedMessage child : m.children) {
                append(out, child, language, depth + 1);
            }
        }
    }

    private static String translate(String key, String language) {
        String text = null;
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n != null) {
                text = i18n.getMessage(language, key);
                if (text == null && !I18nModule.DEFAULT_LANGUAGE.equals(language)) {
                    text = i18n.getMessage(I18nModule.DEFAULT_LANGUAGE, key);
                }
            }
        } catch (RuntimeException ignored) {
            // Module de traduction absent (outils hors serveur) : la clé reste.
        }
        return text == null ? key : text;
    }

    private static String fill(String pattern, Map<String, ParamValue> params, Map<String, FormattedMessage> messageParams,
                               String language, int depth) {
        if (pattern.indexOf('{') < 0) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            int end = c == '{' ? pattern.indexOf('}', i + 1) : -1;
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            String name = pattern.substring(i + 1, end).trim();
            int comma = name.indexOf(',');
            if (comma >= 0) {
                name = name.substring(0, comma).trim();
            }
            FormattedMessage nested = messageParams == null ? null : messageParams.get(name);
            ParamValue value = params == null ? null : params.get(name);
            if (nested != null) {
                append(out, nested, language, depth + 1);
            } else if (value != null) {
                out.append(value(value));
            } else {
                out.append(pattern, i, end + 1);
            }
            i = end + 1;
        }
        return out.toString();
    }

    private static String value(ParamValue v) {
        if (v instanceof StringParamValue s) {
            return s.value == null ? "" : s.value;
        }
        if (v instanceof IntParamValue n) {
            return Integer.toString(n.value);
        }
        if (v instanceof LongParamValue n) {
            return Long.toString(n.value);
        }
        if (v instanceof DoubleParamValue d) {
            double x = d.value;
            return x == Math.rint(x) && Math.abs(x) < 1e15 ? Long.toString((long) x) : String.format(java.util.Locale.ROOT, "%.2f", x);
        }
        if (v instanceof BoolParamValue b) {
            return Boolean.toString(b.value);
        }
        return String.valueOf(v);
    }
}
