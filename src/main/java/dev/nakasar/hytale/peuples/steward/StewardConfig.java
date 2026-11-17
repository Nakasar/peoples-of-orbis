package dev.nakasar.hytale.peuples.steward;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Réglages du gestionnaire qui parle, propres au serveur : fichier {@value #FILE} du dossier de données du plugin, hors
 * du pack d'assets (Kevin, 17 novembre 2026 : l'administrateur du serveur fournit et règle la clé, désactivé par défaut).
 * Le fichier est écrit avec les valeurs par défaut s'il manque ; {@code /village gestionnaire recharger} le relit.
 * <p>
 * La clé n'est jamais dans le fichier : il donne le NOM de la variable d'environnement qui la porte ({@link #apiKeyEnv}).
 */
public final class StewardConfig {

    public static final String FILE = "gestionnaire.json";
    public static final String CLAUDE = "claude";
    public static final String LOCAL = "local";

    private static final Gson GSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE).setPrettyPrinting().disableHtmlEscaping().create();

    /** Désactivé par défaut : aucun appel, aucun bouton « Parler librement ». */
    public boolean enabled = false;
    /** {@value #CLAUDE} (API Claude) ou {@value #LOCAL} (serveur compatible OpenAI : Ollama, llama.cpp...). */
    public String backend = CLAUDE;
    /** Adresse de l'API ; vide : celle par défaut du backend. */
    public String endpoint = "";
    /** Modèle ; vide : celui par défaut du backend (Claude Haiku 5.5, décision de Kevin). */
    public String model = "";
    /** Nom de la variable d'environnement qui porte la clé (jamais la clé elle-même). */
    public String apiKeyEnv = "ANTHROPIC_API_KEY";
    /** Jetons (entrée + sortie) par jour de serveur, tous joueurs confondus ; au-delà, le gestionnaire se tait jusqu'à minuit. */
    public long dailyTokenBudget = 1_000_000;
    /** Messages par joueur et par heure. */
    public int playerHourlyCalls = 60;
    /** Délai d'une requête, en millisecondes. */
    public int timeoutMs = 20_000;
    /** Longueur maximale d'une réponse, en jetons. */
    public int maxTokens = 600;
    /** Langue du texte du panneau donné au modèle, et de ses réponses. */
    public String language = "fr-FR";
    /** Tours de conversation gardés par joueur (question + réponse). */
    public int memoryTurns = 10;
    /** Appels d'outils au plus par message du joueur. */
    public int maxToolCalls = 8;
    /** Longueur maximale d'un message du joueur, en caractères. */
    public int maxInputChars = 300;
    /** Journal des échanges (questions et réponses) dans le log du serveur, en plus des actions toujours journalisées. */
    public boolean logConversations = false;

    private static volatile StewardConfig current = new StewardConfig();
    private static volatile Path file;
    private static volatile String loadError;

    public static StewardConfig get() {
        return current;
    }

    /** Erreur de lecture du dernier chargement, ou null. */
    public static String loadError() {
        return loadError;
    }

    public static Path file() {
        return file;
    }

    /** Charge (ou crée) le fichier de réglages dans `dataDirectory`. */
    public static synchronized void load(Path dataDirectory) {
        if (dataDirectory != null) {
            file = dataDirectory.resolve(FILE);
        }
        reload();
    }

    /** Relit le fichier ; en cas d'erreur, le gestionnaire est coupé et l'erreur gardée pour {@code /village gestionnaire}. */
    public static synchronized String reload() {
        loadError = null;
        if (file == null) {
            current = new StewardConfig();
            return null;
        }
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(new StewardConfig()), StandardCharsets.UTF_8);
            }
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            StewardConfig c = GSON.fromJson(json, StewardConfig.class);
            current = c == null ? new StewardConfig() : c.sane();
        } catch (IOException | RuntimeException e) {
            loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
            StewardConfig off = new StewardConfig();
            off.enabled = false;
            current = off;
        }
        return loadError;
    }

    private StewardConfig sane() {
        backend = backend == null ? CLAUDE : backend.trim().toLowerCase(java.util.Locale.ROOT);
        endpoint = endpoint == null ? "" : endpoint.trim();
        model = model == null ? "" : model.trim();
        apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.trim();
        language = language == null || language.isBlank() ? "fr-FR" : language.trim();
        dailyTokenBudget = Math.max(0, dailyTokenBudget);
        playerHourlyCalls = Math.max(1, playerHourlyCalls);
        timeoutMs = Math.max(2_000, Math.min(120_000, timeoutMs));
        maxTokens = Math.max(100, Math.min(4_000, maxTokens));
        memoryTurns = Math.max(0, Math.min(30, memoryTurns));
        maxToolCalls = Math.max(1, Math.min(16, maxToolCalls));
        maxInputChars = Math.max(40, Math.min(1_000, maxInputChars));
        return this;
    }

    public String modelOrDefault() {
        if (!model.isEmpty()) {
            return model;
        }
        return LOCAL.equals(backend) ? "llama3.1" : "claude-haiku-5-5";
    }

    public String endpointOrDefault() {
        if (!endpoint.isEmpty()) {
            return endpoint;
        }
        return LOCAL.equals(backend) ? "http://127.0.0.1:11434/v1/chat/completions" : "https://api.anthropic.com/v1/messages";
    }

    /** La clé lue dans l'environnement, ou null (le backend local peut s'en passer). */
    public String apiKey() {
        if (apiKeyEnv.isEmpty()) {
            return null;
        }
        String key = System.getenv(apiKeyEnv);
        return key == null || key.isBlank() ? null : key.trim();
    }
}
