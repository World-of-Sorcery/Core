package me.hektortm.wosCore.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A failed wos-api call. {@link #status()} is the HTTP status (0 when the API
 * could not be reached at all) and {@link #code()} the stable error code from
 * the API's error envelope ({@code {"error":{"code","message"}}}), if any.
 * {@link #body()} is the whole JSON object the API answered, when it was one
 * (some errors carry extra fields next to the envelope, e.g. {@code held_by}).
 */
public class ApiException extends Exception {
    private final int status;
    private final String code;
    /** Not serialized: Gson's tree is not {@link java.io.Serializable}. */
    private final transient @Nullable JsonObject body;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    /** As {@link #ApiException(int, String, String)}, keeping the answer's parsed JSON object (or null). */
    public ApiException(int status, String code, String message, @Nullable JsonObject body) {
        super(message);
        this.status = status;
        this.code = code;
        this.body = body == null ? null : body.deepCopy();
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "unreachable";
        this.body = null;
    }

    public int status() { return status; }

    public String code() { return code; }

    /** A copy of the JSON object the API answered; empty when there was none or it was not a JSON object. */
    public Optional<JsonObject> body() {
        return body == null ? Optional.empty() : Optional.of(body.deepCopy());
    }

    /** A top-level string field of the answer (e.g. {@code held_by}); empty when missing or not a string. */
    public Optional<String> field(String name) {
        if (body == null) return Optional.empty();
        JsonElement v = body.get(name);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return Optional.empty();
        return Optional.of(v.getAsString());
    }

    public boolean isNotFound() { return status == 404; }

    /** True when the API is down, unreachable, or failing server-side. */
    public boolean isUnavailable() { return status == 0 || status >= 500; }
}
