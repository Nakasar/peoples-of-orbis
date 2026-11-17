package dev.nakasar.hytale.peuples.steward;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * La conversation d'un joueur avec un gestionnaire : ses derniers tours (question, réponse ; {@link StewardConfig#memoryTurns}),
 * et les actions qui attendent son clic sur Confirmer. Oubliée quand il parle à un autre gestionnaire, quand il quitte le serveur, ou après une heure sans parler.
 */
final class StewardSession {

    /** Une action retenue jusqu'au clic sur Confirmer : l'outil, ses paramètres, et son libellé. */
    record Pending(StewardActions.Spec spec, JsonObject input, String label) {
    }

    static final long IDLE_MS = 60L * 60 * 1000;

    final String villageId;
    final CapturingPlayerRef player;
    /** Tours passés, au format des messages de l'API Claude (rôle user puis assistant, texte seul). */
    final Deque<JsonObject> history = new ArrayDeque<>();
    final List<Pending> pending = new ArrayList<>();
    /** Les demandes au tableau lors du dernier message (null : pas encore vues) : le village vit entre deux messages. */
    List<String> boardSeen;
    long lastUsed = System.currentTimeMillis();

    StewardSession(String villageId, PlayerRef player) {
        this.villageId = villageId;
        this.player = new CapturingPlayerRef(player);
    }

    void remember(JsonObject user, JsonObject assistant, int turns) {
        history.addLast(user);
        history.addLast(assistant);
        while (history.size() > turns * 2) {
            history.removeFirst();
        }
    }

    /** Ajoute une note au dernier tour du gestionnaire (actions confirmées) : le modèle la lira au message suivant. */
    void note(String text) {
        JsonObject last = history.peekLast();
        if (last == null || !"assistant".equals(last.get("role").getAsString())) {
            return;
        }
        last.addProperty("content", last.get("content").getAsString() + "\n[" + text + "]");
    }
}
