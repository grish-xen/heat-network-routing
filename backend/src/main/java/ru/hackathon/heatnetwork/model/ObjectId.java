package ru.hackathon.heatnetwork.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Preserves the original JSON scalar and distinguishes numeric IDs from strings. */
public final class ObjectId {
    private final JsonNode value;
    private final String key;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ObjectId(JsonNode value) {
        if (value == null || (!value.isTextual() && !value.isNumber())) {
            throw new IllegalArgumentException("Object ID must be a JSON string or number");
        }
        this.value = value.deepCopy();
        this.key = value.isTextual() ? "s:" + value.textValue()
                : "n:" + value.decimalValue().stripTrailingZeros().toPlainString();
    }

    @JsonValue
    public JsonNode value() { return value; }

    @Override
    public boolean equals(Object other) {
        return other instanceof ObjectId && key.equals(((ObjectId) other).key);
    }

    @Override
    public int hashCode() { return Objects.hash(key); }
}
