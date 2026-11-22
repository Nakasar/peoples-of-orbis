import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Refuse, dans les sources Java d'un mod, tout texte de bouton ({@code cmd.set("#….Text", …)}) dont les paramètres ne
 * seraient pas remplacés. Le client affiche la propriété {@code .Text} d'un bouton telle quelle : une clé traduite simple
 * passe, mais un {@code Message.translation(…).param(…)} s'affiche brut (« OUVRIR AVEC {PARTNER} », « {TAB} · FERMÉ »,
 * Kevin 2026-11-22). Seuls sont acceptés :
 * <ul>
 *   <li>{@code Message.translation(clé)} sans {@code .param} ni message imbriqué ;</li>
 *   <li>{@code Message.raw(…)} ;</li>
 *   <li>{@code ButtonText.of(joueur, message)} (texte final construit côté serveur, ui/ButtonText).</li>
 * </ul>
 * Une variable ({@code cmd.set("#Action.Text", action)}) est refusée : on ne sait pas ce qu'elle contient.
 *
 * <p>Usage : {@code java tools/CheckButtonText.java <dossier de sources>...}
 */
public final class CheckButtonText {

    /** Début d'un {@code .set("….Text", } (la clé peut être concaténée : {@code "#Row" + n + ".Text"}). */
    static final Pattern SET_TEXT = Pattern.compile("\\.set\\(((?:[^;()]|\\([^()]*\\))*?\\.Text\")\\s*,");

    public static void main(String[] args) throws IOException {
        List<String> errors = new ArrayList<>();
        for (String dir : args) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String src = Files.readString(f);
                    Matcher m = SET_TEXT.matcher(src);
                    while (m.find()) {
                        String value = argument(src, m.end());
                        String why = refuse(value);
                        if (why != null) {
                            int line = 1 + (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                            errors.add(root.relativize(f) + ":" + line + " : " + why + " : " + value.replaceAll("\\s+", " "));
                        }
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            errors.forEach(System.err::println);
            System.err.println(errors.size() + " texte(s) de bouton dont les paramètres s'afficheraient bruts : passer par "
                    + "ui.ButtonText.of(playerRef, message).");
            System.exit(1);
        }
        System.out.println("checkButtonText : tous les textes de bouton sont finaux.");
    }

    /** Le second argument de l'appel, jusqu'à la parenthèse fermante de {@code set(}. */
    static String argument(String src, int from) {
        int depth = 0;
        boolean str = false;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (str) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    str = false;
                }
                continue;
            }
            if (c == '"') {
                str = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return src.substring(from, i).strip();
                }
                depth--;
            }
        }
        return src.substring(from).strip();
    }

    static String refuse(String v) {
        if (v.startsWith("ButtonText.of(") || v.startsWith("dev.nakasar.hytale.peuples.ui.ButtonText.of(")) {
            return null;
        }
        if (v.startsWith("Message.raw(") && v.endsWith(")")) {
            return null;
        }
        if (v.startsWith("Message.translation(") && v.endsWith(")")
                && argument(v, "Message.translation(".length()).length() == v.length() - "Message.translation(".length() - 1) {
            return null; // une seule clé, rien d'enchaîné après
        }
        if (v.contains(".param(")) {
            return "message à paramètres";
        }
        return "texte non vérifiable (variable ou message composé)";
    }
}
