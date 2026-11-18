package dev.nakasar.hytale.peuples.steward;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Le robinet du modèle, commun à tous les PNJ qui parlent (gestionnaire {@link Steward}, voyageurs et Klops
 * {@link FreeTalk}, répliques reformulées {@link Retell}) : un seul budget de jetons par jour, une seule limite de
 * messages par joueur et par heure, une seule pause après trois échecs de suite.
 */
final class LlmGate {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int FAILURES_BEFORE_PAUSE = 3;
    private static final long PAUSE_MS = 10L * 60 * 1000;

    /** Par joueur, hors conversation : survit au changement de PNJ, pour que la limite horaire ne se contourne pas. */
    private static final Map<UUID, Quota> QUOTAS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS_TODAY = new AtomicLong();
    private static volatile LocalDate tokenDay = LocalDate.now();
    private static final AtomicLong CALLS_TODAY = new AtomicLong();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static volatile long pausedUntil;
    private static volatile String lastError;

    private LlmGate() {
    }

    /** Un joueur : son message en cours (un seul à la fois) et l'heure de ses derniers messages (limite par heure). */
    static final class Quota {
        volatile boolean busy;
        private final Deque<Long> calls = new ArrayDeque<>();

        /** Vrai si le joueur peut encore envoyer un message cette heure (et le compte). */
        synchronized boolean allowCall(int perHour) {
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

    static Quota quota(PlayerRef player) {
        return QUOTAS.computeIfAbsent(player.getUuid(), u -> new Quota());
    }

    /** Pourquoi le modèle ne peut pas être appelé maintenant (clé de la réplique de secours), ou null. */
    static String refusal(StewardConfig config) {
        if (!config.enabled) {
            return "off";
        }
        if (StewardConfig.CLAUDE.equals(config.backend) && config.apiKey() == null) {
            return "key";
        }
        if (System.currentTimeMillis() < pausedUntil) {
            return "paused";
        }
        return overBudget(config) ? "budget" : null;
    }

    static boolean overBudget(StewardConfig config) {
        rollDay();
        return config.dailyTokenBudget > 0 && TOKENS_TODAY.get() >= config.dailyTokenBudget;
    }

    private static void rollDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(tokenDay)) {
            tokenDay = today;
            TOKENS_TODAY.set(0);
            CALLS_TODAY.set(0);
        }
    }

    static void called() {
        CALLS_TODAY.incrementAndGet();
    }

    /** Une réponse du modèle : les jetons comptent, la série d'échecs s'arrête. */
    static void succeeded(LlmClient.Reply reply) {
        FAILURES.set(0);
        rollDay();
        TOKENS_TODAY.addAndGet(reply.inputTokens() + reply.outputTokens());
    }

    /** Un appel en échec ; trois de suite mettent le modèle en pause 10 minutes, pour tous les PNJ. */
    static void failed(Throwable error, String who) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
        lastError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        LOGGER.atWarning().log("%s : appel au modèle en échec (%s)", who, lastError);
        if (FAILURES.incrementAndGet() >= FAILURES_BEFORE_PAUSE) {
            FAILURES.set(0);
            pausedUntil = System.currentTimeMillis() + PAUSE_MS;
            LOGGER.atWarning().log("PNJ qui parlent : %d échecs de suite, pause de 10 minutes", FAILURES_BEFORE_PAUSE);
        }
    }

    static long tokensToday() {
        rollDay();
        return TOKENS_TODAY.get();
    }

    static long callsToday() {
        rollDay();
        return CALLS_TODAY.get();
    }

    static long pausedUntil() {
        return pausedUntil;
    }

    static String lastError() {
        return lastError;
    }

    static void resetPause() {
        pausedUntil = 0;
        FAILURES.set(0);
        lastError = null;
    }

    static void clearAll() {
        QUOTAS.clear();
    }
}
