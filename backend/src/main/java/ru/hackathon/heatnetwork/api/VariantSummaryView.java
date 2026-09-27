package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import java.math.BigDecimal;
import java.util.List;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Exact summary properties read from the exported file; no second calculation. */
@Schema(name = "VariantSummary")
public final class VariantSummaryView {
    @Schema(required = true, oneOf = {String.class, BigDecimal.class}) public ObjectId id;
    @JsonProperty("object_type") public String objectType;
    @Schema(required = true, oneOf = {String.class, BigDecimal.class})
    @JsonProperty("variant_id") public ObjectId variantId;
    public int rank;
    @JsonProperty("construction_cost") public BigDecimal constructionCost;
    @JsonProperty("chamber_construction_cost") public BigDecimal chamberConstructionCost;
    @JsonProperty("existing_chamber_tie_in_count") public int existingChamberTieInCount;
    @JsonProperty("existing_chamber_tie_in_cost") public BigDecimal existingChamberTieInCost;
    @JsonProperty("unconnected_penalty") public BigDecimal unconnectedPenalty;
    @JsonProperty("calculated_cost") public BigDecimal calculatedCost;
    @JsonProperty("new_network_length") public double newNetworkLength;
    public BigDecimal score;
    @ArraySchema(schema = @Schema(oneOf = {String.class, BigDecimal.class}))
    @JsonProperty("unconnected_oks_ids") public List<ObjectId> unconnectedOksIds;
}
