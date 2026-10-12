package dev.minenotify;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;

public record Notification(String source, String title, String message) {
    public Notification {
        source = plain(source, 64, true);
        title = plain(title, 128, true);
        message = plain(message, 2048, false);
    }

    private static String plain(String value, int limit, boolean required) {
        if (value == null || value.length() > limit || (required && value.isBlank())) {
            throw new IllegalArgumentException("Invalid notification text");
        }
        StringBuilder clean = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (c == '\n' || c == '\r' || c == '\t') clean.append(' ');
            else if (!Character.isISOControl(c) && c != 0x00a7 && Character.getType(c) != Character.FORMAT) clean.appendCodePoint(c);
        });
        String result = clean.toString().strip();
        if (required && result.isBlank()) throw new IllegalArgumentException("Empty notification text");
        return result;
    }

    public static Notification parse(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement value = new Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) {
                throw new IllegalArgumentException("Expected JSON object");
            }
            JsonObject object = value.getAsJsonObject();
            return new Notification(string(object, "source", true), string(object, "title", true), string(object, "message", false));
        }
    }

    private static String string(JsonObject object, String key, boolean required) {
        JsonElement value = object.get(key);
        if (value == null && !required) return "";
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected text field");
        }
        return value.getAsString();
    }
}
