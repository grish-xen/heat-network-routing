package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;

/** Public Error schema; internal diagnostics are translated, never serialized directly. */
public final class ApiError {
    @Schema(required = true) public final String code;
    @Schema(required = true) public final String message;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public final List<Map<String, Object>> details;

    @JsonCreator
    public ApiError(@JsonProperty("code") String code, @JsonProperty("message") String message,
                    @JsonProperty("details") List<Map<String, Object>> details) {
        this.code = code;
        this.message = message;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ApiError(String code, String message) { this(code, message, List.of()); }

    static ApiError from(Diagnostic diagnostic) {
        Map<String, Object> references = new LinkedHashMap<>();
        if (diagnostic.inputObjectId != null) references.put("inputObjectId", diagnostic.inputObjectId);
        if (diagnostic.candidateId != null) references.put("candidateId", diagnostic.candidateId);
        if (diagnostic.segmentId != null) references.put("segmentId", diagnostic.segmentId);
        return new ApiError(diagnostic.code, diagnostic.message,
                references.isEmpty() ? List.of() : List.of(references));
    }
}
