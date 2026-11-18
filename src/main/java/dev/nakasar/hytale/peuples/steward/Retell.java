package dev.nakasar.hytale.peuples.steward;

import com.google.gson.JsonArray;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.nakasar.hytale.peuples.world.Wayfarers;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Les paroles d'information des PNJ de passage redites avec leur voix (Kevin, 18 novembre 2026) : quand le Klops dit sa
 * tournée, qu'un voyageur donne une rumeur, confie une lettre ou dit sa cargaison, le jeu écrit le texte exact
 * ({@code DialogueSpeech.say}) et le modèle le redit en personnage. La réplique du modèle n'est gardée que si elle
 * contient tous les faits du texte du jeu ({@link TextRender#facts} : noms, nombres, directions) ; sinon, et en cas de
 * délai, de refus, de budget épuisé ou de modèle coupé, le texte du jeu s'affiche tel quel.
 * <p>
 * Une réplique déjà redite pour ce PNJ est reprise du cache (même rumeur demandée deux fois : aucun appel).
 */
public final class Retell {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int CACHE = 256;
    private static final int MAX_CHARS = 450;

    private static final Map<String, String> CACHE_LINES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE;
        }
    };

    private Retell() {
    }

    public static boolean enabled() {
        StewardConfig c = StewardConfig.get();
        return c.enabled && c.retellLines;
    }

    static synchronized void clearAll() {
        CACHE_LINES.clear();
    }

    /**
     * Redit `said` avec la voix du PNJ `npc` (thread du monde). Rend faux sans rien appeler (modèle coupé, PNJ qui
     * n'est pas de passage, rien à dire) : l'appelant affiche `said` tel quel. Sinon `done` reçoit, sur le thread du
     * monde et une seule fois, la réplique redite ou `said` inchangé.
     */
    public static boolean retell(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Ref<EntityStore> npc,
                                 List<Message> said, Consumer<List<Message>> done) {
        StewardConfig config = StewardConfig.get();
        if (!config.retellLines || said == null || said.isEmpty() || npc == null || !npc.isValid()) {
            return false;
        }
        Speaker speaker = Wayfarers.speaker(world, store, ref, player, npc);
        if (speaker == null || LlmGate.refusal(config) != null) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        Set<String> facts = new LinkedHashSet<>();
        for (Message m : said) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(TextRender.text(m, config.language));
            facts.addAll(TextRender.facts(m, config.language));
        }
        String original = Steward.mask(text.toString(), player);
        String cacheKey = speaker.key() + "\u0000" + original;
        String cached;
        synchronized (Retell.class) {
            cached = CACHE_LINES.get(cacheKey);
        }
        if (cached != null) {
            done.accept(List.of(Message.raw(cached)));
            return true;
        }
        String system = """
                Tu ES %s, un PNJ du jeu Hytale, et tu parles au joueur. Ta voix : %s
                On te donne ce que tu dois lui dire. Redis-le avec ta voix, en français, à la première personne, en une à \
                trois phrases courtes (350 caractères au plus), sans markdown ni émojis.
                Règles strictes : garde exactement chaque nom propre, chaque nombre et chaque direction du texte ; n'ajoute \
                aucun fait, lieu, nom ou nombre ; ne retire aucune information. Jamais de chiffres de jeu (essor, \
                réputation), jamais d'identifiant entre crochets. Réponds par la réplique seule.""".formatted(speaker.who(), speaker.voice());
        JsonArray messages = new JsonArray();
        messages.add(FreeTalk.textMessage("user", "Ce que tu dois dire :\n" + original));
        LlmGate.called();
        CompletableFuture<LlmClient.Reply> call;
        try {
            call = LlmClient.of(config).send(system, new JsonArray(), messages).orTimeout(config.retellTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((reply, error) -> {
            String line = null;
            if (error != null) {
                LlmGate.failed(error, "Réplique de " + speaker.key());
            } else {
                LlmGate.succeeded(reply);
                line = accept(reply.text(), facts);
                if (line == null) {
                    LOGGER.atInfo().log("Réplique de %s : faits perdus, texte du jeu gardé (%s)", speaker.key(), reply.text());
                }
            }
            String kept = line;
            if (kept != null) {
                synchronized (Retell.class) {
                    CACHE_LINES.put(cacheKey, kept);
                }
            }
            try {
                world.execute(() -> done.accept(kept == null ? said : List.of(Message.raw(kept))));
            } catch (RuntimeException ignored) {
                // Le monde s'arrête : plus personne à qui parler.
            }
        });
        return true;
    }

    /** La réplique du modèle si elle garde tous les faits et reste courte ; null sinon. */
    static String accept(String text, Set<String> facts) {
        if (text == null) {
            return null;
        }
        String line = text.strip();
        if (line.length() >= 2 && (line.startsWith("«") && line.endsWith("»") || line.startsWith("\"") && line.endsWith("\""))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        if (line.isEmpty() || line.length() > MAX_CHARS || line.indexOf('[') >= 0 || line.indexOf('{') >= 0) {
            return null;
        }
        for (String fact : facts) {
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}-])" + Pattern.quote(fact) + "(?![\\p{L}\\p{N}-])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (!p.matcher(line).find()) {
                return null;
            }
        }
        return line;
    }
}
