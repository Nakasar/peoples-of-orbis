import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Refuse, dans les sources Java d'un mod, tout id de terrain écrit à la main : un littéral {@code "E1"}, {@code "E2_"},
 * {@code "_E3_Conseil"}… Un bâtiment de projet se retrouve sur TOUS les terrains du plan, par
 * {@code village.Plots.projectBuildings} / {@code builtProjects} ou en parcourant {@code Plots.plots(village)} :
 * l'Ambassade n'était cherchée que sur E1 et E2, jamais sur E3 (Kevin, 2026-11-21 : « quel que soit leur emplacement »).
 *
 * <p>Usage : {@code java tools/CheckPlotLookups.java <dossier de sources>...}
 */
public final class CheckPlotLookups {

    /** Littéral entier qui est un id de terrain, seul ou collé à un id de bâtiment par « _ ». */
    static final Pattern PLOT_LITERAL = Pattern.compile("\"(?:[A-Za-z0-9]+_)*E[1-9][0-9]?(?:_[A-Za-z0-9_]*)?\"");

    public static void main(String[] args) throws IOException {
        List<String> errors = new ArrayList<>();
        for (String dir : args) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        Matcher m = PLOT_LITERAL.matcher(lines.get(i));
                        while (m.find()) {
                            errors.add(root.relativize(f) + ":" + (i + 1) + " : id de terrain écrit à la main " + m.group());
                        }
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            errors.forEach(System.err::println);
            System.err.println(errors.size() + " recherche(s) de bâtiment limitée(s) à certains terrains : passer par "
                    + "Plots.projectBuildings / Plots.builtProjects ou Plots.plots(village).");
            System.exit(1);
        }
        System.out.println("checkPlotLookups : aucun id de terrain écrit à la main.");
    }
}
