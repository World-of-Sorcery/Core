package me.hektortm.wosCore.api;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The single HTTP client every WoS plugin uses to talk to wos-api (the Go
 * service that owns the database). Authenticates with the plugin service token.
 *
 * <p>JSON is mapped with snake_case ↔ camelCase naming, so a record component
 * {@code startInteraction} reads the API field {@code start_interaction}.</p>
 *
 * <p>All calls are blocking — never call them on the main server thread.
 * Idempotent reads are retried on network errors and 5xx responses.</p>
 */
public final class WosApi {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int READ_ATTEMPTS = 3;

    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .create();

    private final String baseUrl;
    private final String token;
    private final OkHttpClient http;
    private final Logger log;

    public WosApi(String baseUrl, String token, Duration timeout, Logger log) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.log = log;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout.multipliedBy(2))
                .build();
    }

    /** GET a resource and map it to {@code type}. A 404 is an {@link ApiException}. */
    public <T> T get(String path, Type type) throws ApiException {
        return GSON.fromJson(getJson(path), type);
    }

    /** GET a resource; empty when it does not exist (404). */
    public <T> Optional<T> find(String path, Type type) throws ApiException {
        try {
            return Optional.ofNullable(get(path, type));
        } catch (ApiException e) {
            if (e.isNotFound()) return Optional.empty();
            throw e;
        }
    }

    /** GET a resource as raw JSON. */
    public JsonElement getJson(String path) throws ApiException {
        ApiException last = null;
        for (int attempt = 1; attempt <= READ_ATTEMPTS; attempt++) {
            try {
                return execute(request(path).get().build());
            } catch (ApiException e) {
                if (!e.isUnavailable()) throw e;
                last = e;
                sleep(attempt * 200L);
            }
        }
        throw last;
    }

    /** GET a resource as raw JSON in one attempt (no retries): the request goes out when this is called. */
    public JsonElement getJsonOnce(String path) throws ApiException {
        return execute(request(path).get().build());
    }

    /** Send a write (POST/PUT/PATCH/DELETE) with an optional JSON body; returns the response JSON (or null). */
    public JsonElement send(String method, String path, Object body) throws ApiException {
        return send(method, path, body, java.util.Map.of());
    }

    /** As {@link #send(String, String, Object)}, with extra request headers (e.g. Idempotency-Key). */
    public JsonElement send(String method, String path, Object body, java.util.Map<String, String> headers) throws ApiException {
        RequestBody rb = body == null ? (method.equals("DELETE") ? null : RequestBody.create(new byte[0], JSON))
                : RequestBody.create(GSON.toJson(body), JSON);
        Request.Builder req = request(path).method(method, rb);
        headers.forEach(req::header);
        return execute(req.build());
    }

    private Request.Builder request(String path) {
        return new Request.Builder()
                .url(baseUrl + (path.startsWith("/") ? path : "/" + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private JsonElement execute(Request req) throws ApiException {
        try (Response res = http.newCall(req).execute()) {
            ResponseBody body = res.body();
            String text = body == null ? "" : body.string();
            if (res.isSuccessful()) {
                return text.isBlank() ? null : JsonParser.parseString(text);
            }
            throw error(res.code(), text, req);
        } catch (IOException e) {
            throw new ApiException("wos-api unreachable: " + req.method() + " " + req.url().encodedPath(), e);
        }
    }

    private ApiException error(int status, String text, Request req) {
        String code = "http_" + status;
        String message = req.method() + " " + req.url().encodedPath() + " -> " + status;
        JsonObject whole = null;
        try {
            JsonElement body = JsonParser.parseString(text);
            if (body.isJsonObject()) whole = body.getAsJsonObject();
            JsonElement err = body.isJsonObject() ? body.getAsJsonObject().get("error") : null;
            if (err != null && err.isJsonObject()) {
                JsonObject e = err.getAsJsonObject();
                if (e.has("code")) code = e.get("code").getAsString();
                if (e.has("message")) message += " (" + e.get("message").getAsString() + ")";
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException notAnEnvelope) {
            // body is not an API error envelope — keep the generic code/message
        }
        if (status >= 500) log.warning("[WosApi] " + message);
        return new ApiException(status, code, message, whole);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
