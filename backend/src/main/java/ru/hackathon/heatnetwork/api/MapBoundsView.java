package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MapBounds")
public final class MapBoundsView {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @ArraySchema(minItems = 4, maxItems = 4,
            arraySchema = @Schema(required = true, nullable = true, description = "WGS84: minLon,minLat,maxLon,maxLat; null when both layers are empty"),
            schema = @Schema(type = "number", format = "double"))
    public final double[] bbox;

    MapBoundsView(double[] bbox) { this.bbox = bbox == null ? null : bbox.clone(); }
}
