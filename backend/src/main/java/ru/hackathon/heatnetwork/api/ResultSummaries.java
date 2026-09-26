package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads only properties, skipping geometry tokens without retaining coordinate arrays. */
final class ResultSummaries {
    private ResultSummaries() { }
    static List<VariantSummaryView> read(Path file, ObjectMapper mapper) throws IOException {
        List<VariantSummaryView> summaries = new ArrayList<>();
        try (JsonParser json = mapper.getFactory().createParser(file.toFile())) {
            if (json.nextToken() != JsonToken.START_OBJECT) throw new IOException("Invalid exported collection");
            while (json.nextToken() != JsonToken.END_OBJECT) {
                String field = json.currentName();
                if (json.nextToken() == null) throw new IOException("Truncated exported collection");
                if (!"features".equals(field)) { json.skipChildren(); continue; }
                if (json.currentToken() != JsonToken.START_ARRAY) throw new IOException("Invalid exported features");
                while (json.nextToken() != JsonToken.END_ARRAY) {
                    CalculationCoordinator.interrupted();
                    if (json.currentToken() != JsonToken.START_OBJECT) throw new IOException("Invalid exported feature");
                    while (json.nextToken() != JsonToken.END_OBJECT) {
                        String key = json.currentName();
                        if (json.nextToken() == null) throw new IOException("Truncated exported feature");
                        if ("properties".equals(key)) {
                            JsonNode properties = mapper.readTree(json);
                            if ("variant_summary".equals(properties.path("object_type").asText())) {
                                summaries.add(mapper.treeToValue(properties, VariantSummaryView.class));
                                if (summaries.size() > 3) throw new IOException("Too many exported variants");
                            }
                        } else json.skipChildren();
                    }
                }
            }
        }
        if (summaries.isEmpty()) throw new IOException("Export has no variant summaries");
        return summaries;
    }
}
