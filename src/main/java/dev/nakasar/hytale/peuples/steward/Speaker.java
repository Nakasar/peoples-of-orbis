package dev.nakasar.hytale.peuples.steward;

import java.util.List;
import java.util.function.Supplier;

/**
 * Un PNJ de passage qui parle (Kevin, 18 novembre 2026) : le Klops errant, un voyageur des routes, un caravanier, un
 * aubergiste ou un campeur. Bâti par {@code world.Wayfarers} sur le thread du monde, à chaque message, à partir de
 * l'état du moment : le modèle ne sait que ce que disent ses sujets, et n'agit que par ses actions, qui appellent les
 * mêmes fonctions du jeu que les réponses de son dialogue.
 *
 * @param key    clé stable de ce PNJ (conversation et cache des répliques), par exemple {@code T:<voyageur>}
 * @param who    qui il est, à la troisième personne (« un colporteur kweebec qui va de Chêneclair à Rochebrune »)
 * @param voice  sa façon de parler
 * @param topics ce qu'il sait, par sujet (outil {@code lire})
 * @param acts   ce qu'il peut faire pour le joueur
 */
public record Speaker(String key, String who, String voice, List<Topic> topics, List<Act> acts) {

    /** Un sujet : son id (paramètre de {@code lire}), ce qu'il couvre, et son texte, lu sur le thread du monde. */
    public record Topic(String id, String about, Supplier<String> text) {
    }

    /**
     * Une action : le nom de l'outil, sa description pour le modèle, et la fonction du jeu (thread du monde) qui rend
     * vrai si elle a été faite. Ses paroles ({@code DialogueSpeech.say}) et refus sont montrés au joueur tels quels.
     */
    public record Act(String name, String about, Supplier<Boolean> run) {
    }

    public Topic topic(String id) {
        for (Topic t : topics) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    public Act act(String name) {
        for (Act a : acts) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }
}
