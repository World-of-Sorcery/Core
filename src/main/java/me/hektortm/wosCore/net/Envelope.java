package me.hektortm.wosCore.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

/**
 * One message on the bus: {@code {"from": <serverId>, "type": ..., "body": {...}, "reply_to": <id or null>}}.
 *
 * @param from    id of the sending server
 * @param type    what the message is (for a plain publish: the channel name; for a request: the request type)
 * @param body    the payload
 * @param replyTo set on a request: the id whose {@code wos:reply:<id>} channel the answer goes to; otherwise null
 */
public record Envelope(String from, String type, JsonObject body, @Nullable String replyTo) {

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("from", from);
        o.addProperty("type", type);
        o.add("body", body == null ? new JsonObject() : body);
        o.addProperty("reply_to", replyTo); // null becomes JSON null
        return o.toString();
    }

    /** Parses a message; throws {@link IllegalArgumentException} for anything that is not a valid envelope. */
    public static Envelope parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
            JsonObject o = root.getAsJsonObject();
            String from = string(o, "from");
            String type = string(o, "type");
            if (from == null || type == null) throw new IllegalArgumentException("missing from or type");
            JsonElement body = o.get("body");
            JsonObject b = body != null && body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
            return new Envelope(from, type, b, string(o, "reply_to"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid bus message: " + e.getMessage(), e);
        }
    }

    private static @Nullable String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
