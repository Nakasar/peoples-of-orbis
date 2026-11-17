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
    /** Une question en cours : la fenêtre attend la réponse, une deuxième est refusée. */
    volatile boolean busy;
    long lastUsed = System.currentTimeMillis();
    /** Heures des derniers messages (limite par heure). */
    final Deque<Long> calls = new ArrayDeque<>();

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

    /** Vrai si le joueur peut encore envoyer un message cette heure (et le compte). */
    boolean allowCall(int perHour) {
        long now = System.currentTimeMillis();
        while (!calls.isEmpty() && now - calls.peekFirst() > 3_600_000L) {
            calls.removeFirst();
        }
        if (calls.size() >= perHour) {
            return false;
        }
        calls.addLast(now);
        return true;
    }
}
