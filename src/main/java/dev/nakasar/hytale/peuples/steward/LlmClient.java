package dev.nakasar.hytale.peuples.steward;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Le modèle qui fait parler le gestionnaire, appelé hors du thread du monde (requêtes HTTP asynchrones). Deux backends,
 * choisis par {@link StewardConfig#backend} : l'API Claude ({@code /v1/messages}) ou un serveur local compatible OpenAI
 * ({@code /v1/chat/completions} : Ollama, llama.cpp, LM Studio...). La conversation est tenue au format de l'API Claude
 * (messages, blocs {@code text}/{@code tool_use}/{@code tool_result}) et traduite pour le backend local.
 * <p>
 * HTTP brut ({@link HttpClient} du JDK) et Gson (fourni par le serveur) : le jar du mod n'embarque aucune bibliothèque.
 */
abstract class LlmClient {

    /** Un appel d'outil demandé par le modèle. */
    record ToolCall(String id, String name, JsonObject input) {
    }

    /**
     * Une réponse : son texte, ses appels d'outils, le contenu à remettre tel quel dans la conversation (blocs de l'API
     * Claude, réflexion comprise), et les jetons comptés.
     */
    record Reply(String text, List<ToolCall> calls, JsonArray assistantContent, long inputTokens, long outputTokens, String stop) {
    }

    /** Refus du backend (code HTTP, réseau, délai) : le gestionnaire retombe sur sa réplique de secours. */
    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    protected final StewardConfig config;

    LlmClient(StewardConfig config) {
        this.config = config;
    }

    static LlmClient of(StewardConfig config) {
        return StewardConfig.LOCAL.equals(config.backend) ? new Local(config) : new Claude(config);
    }

    abstract CompletableFuture<Reply> send(String system, JsonArray tools, JsonArray messages);

    protected CompletableFuture<JsonObject> post(HttpRequest.Builder request, JsonObject body) {
        HttpRequest r = request.timeout(Duration.ofMillis(config.timeoutMs))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return HTTP.sendAsync(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new Failure(response.statusCode(), "HTTP " + response.statusCode() + " " + errorText(response.body()));
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        });
    }

    /** Le message d'erreur du backend, court (jamais la requête : elle porte la clé dans ses en-têtes, pas dans le corps). */
    private static String errorText(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement error = json.get("error");
            if (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")) {
                return error.getAsJsonObject().get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // corps non JSON
        }
        return body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
    }

    /** API Claude : {@code POST /v1/messages}, clé dans {@code x-api-key}. */
    static final class Claude extends LlmClient {

        Claude(StewardConfig config) {
            super(config);
        }

        @Override
        CompletableFuture<Reply> send(String system, JsonArray tools, JsonArray messages) {
            String key = config.apiKey();
            if (key == null) {
                return CompletableFuture.failedFuture(new Failure(0, "clé absente (variable " + config.keyEnv() + ")"));
            }
            JsonObject body = new JsonObject();
            body.addProperty("model", config.modelOrDefault());
            body.addProperty("max_tokens", config.maxTokens);
            JsonArray sys = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", system);
            sys.add(block);
            body.add("system", sys);
            if (!tools.isEmpty()) {
                body.add("tools", tools);
            }
            body.add("messages", messages);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.endpointOrDefault()))
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01");
            return post(request, body).thenApply(Claude::reply);
        }

        private static Reply reply(JsonObject json) {
            JsonArray content = json.has("content") && json.get("content").isJsonArray() ? json.getAsJsonArray("content") : new JsonArray();
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonElement e : content) {
                JsonObject b = e.getAsJsonObject();
                String type = b.has("type") ? b.get("type").getAsString() : "";
                if (type.equals("text")) {
                    text.append(b.get("text").getAsString());
                } else if (type.equals("tool_use")) {
                    JsonObject input = b.has("input") && b.get("input").isJsonObject() ? b.getAsJsonObject("input") : new JsonObject();
                    calls.add(new ToolCall(b.get("id").getAsString(), b.get("name").getAsString(), input));
                }
            }
            long in = 0;
            long out = 0;
            if (json.has("usage") && json.get("usage").isJsonObject()) {
                JsonObject u = json.getAsJsonObject("usage");
                in = longOf(u, "input_tokens") + longOf(u, "cache_read_input_tokens") + longOf(u, "cache_creation_input_tokens");
                out = longOf(u, "output_tokens");
            }
            String stop = json.has("stop_reason") && !json.get("stop_reason").isJsonNull() ? json.get("stop_reason").getAsString() : "";
            return new Reply(text.toString().strip(), calls, content, in, out, stop);
        }
    }

    /** Serveur local compatible OpenAI : {@code POST /v1/chat/completions}, outils en {@code function}. */
    static final class Local extends LlmClient {

        Local(StewardConfig config) {
            super(config);
        }

        @Override
        CompletableFuture<Reply> send(String system, JsonArray tools, JsonArray messages) {
            JsonObject body = new JsonObject();
            body.addProperty("model", config.modelOrDefault());
            body.addProperty("max_tokens", config.maxTokens);
            body.add("messages", messages(system, messages));
            JsonArray functions = new JsonArray();
            for (JsonElement t : tools) {
                JsonObject tool = t.getAsJsonObject();
                JsonObject f = new JsonObject();
                f.addProperty("name", tool.get("name").getAsString());
                f.addProperty("description", tool.get("description").getAsString());
                f.add("parameters", tool.get("input_schema"));
                JsonObject wrapped = new JsonObject();
                wrapped.addProperty("type", "function");
                wrapped.add("function", f);
                functions.add(wrapped);
            }
            if (!functions.isEmpty()) {
                body.add("tools", functions);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.endpointOrDefault()));
            String key = config.apiKey();
            if (key != null) {
                request.header("authorization", "Bearer " + key);
            }
            return post(request, body).thenApply(Local::reply);
        }

        /** La conversation au format Claude, traduite en messages OpenAI. */
        private static JsonArray messages(String system, JsonArray messages) {
            JsonArray out = new JsonArray();
            out.add(message("system", system));
            for (JsonElement e : messages) {
                JsonObject m = e.getAsJsonObject();
                String role = m.get("role").getAsString();
                JsonElement content = m.get("content");
                if (content.isJsonPrimitive()) {
                    out.add(message(role, content.getAsString()));
                    continue;
                }
                StringBuilder text = new StringBuilder();
                JsonArray toolCalls = new JsonArray();
                for (JsonElement be : content.getAsJsonArray()) {
                    JsonObject b = be.getAsJsonObject();
                    String type = b.get("type").getAsString();
                    switch (type) {
                        case "text" -> text.append(b.get("text").getAsString());
                        case "tool_use" -> {
                            JsonObject f = new JsonObject();
                            f.addProperty("name", b.get("name").getAsString());
                            f.addProperty("arguments", b.get("input").toString());
                            JsonObject call = new JsonObject();
                            call.addProperty("id", b.get("id").getAsString());
                            call.addProperty("type", "function");
                            call.add("function", f);
                            toolCalls.add(call);
                        }
                        case "tool_result" -> {
                            JsonObject tool = message("tool", b.get("content").getAsString());
                            tool.addProperty("tool_call_id", b.get("tool_use_id").getAsString());
                            out.add(tool);
                        }
                        default -> {
                            // réflexion : propre à Claude
                        }
                    }
                }
                if (!text.isEmpty() || !toolCalls.isEmpty()) {
                    JsonObject msg = message(role, text.toString());
                    if (!toolCalls.isEmpty()) {
                        msg.add("tool_calls", toolCalls);
                    }
                    out.add(msg);
                }
            }
            return out;
        }

        private static JsonObject message(String role, String content) {
            JsonObject m = new JsonObject();
            m.addProperty("role", role);
            m.addProperty("content", content);
            return m;
        }

        private static Reply reply(JsonObject json) {
            JsonArray choices = json.has("choices") ? json.getAsJsonArray("choices") : new JsonArray();
            if (choices.isEmpty()) {
                throw new Failure(0, "réponse vide");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            String text = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
            JsonArray content = new JsonArray();
            if (!text.isBlank()) {
                JsonObject b = new JsonObject();
                b.addProperty("type", "text");
                b.addProperty("text", text);
                content.add(b);
            }
            List<ToolCall> calls = new ArrayList<>();
            if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                int n = 0;
                for (JsonElement ce : message.getAsJsonArray("tool_calls")) {
                    JsonObject c = ce.getAsJsonObject();
                    JsonObject f = c.getAsJsonObject("function");
                    String id = c.has("id") && !c.get("id").isJsonNull() ? c.get("id").getAsString() : "call_" + n;
                    n++;
                    JsonObject input;
                    try {
                        JsonElement args = f.get("arguments");
                        input = args.isJsonObject() ? args.getAsJsonObject() : JsonParser.parseString(args.getAsString()).getAsJsonObject();
                    } catch (RuntimeException e) {
                        input = new JsonObject();
                    }
                    String name = f.get("name").getAsString();
                    calls.add(new ToolCall(id, name, input));
                    JsonObject b = new JsonObject();
                    b.addProperty("type", "tool_use");
                    b.addProperty("id", id);
                    b.addProperty("name", name);
                    b.add("input", input);
                    content.add(b);
                }
            }
            long in = 0;
            long out = 0;
            if (json.has("usage") && json.get("usage").isJsonObject()) {
                in = longOf(json.getAsJsonObject("usage"), "prompt_tokens");
                out = longOf(json.getAsJsonObject("usage"), "completion_tokens");
            }
            String stop = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? choice.get("finish_reason").getAsString() : "";
            return new Reply(text.strip(), calls, content, in, out, calls.isEmpty() ? stop : "tool_use");
        }
    }

    private static long longOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }
}
