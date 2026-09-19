package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Immutable HTTP snapshot. SUCCEEDED/DONE are reserved for completed calculation and export. */
@Schema(name = "Job")
public final class JobView {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum Stage { QUEUED, VALIDATING, ROUTING, CALCULATING, EXPORTING, DONE, FAILED }

    @Schema(required = true) public final String jobId;
    @Schema(required = true) public final Status status;
    @Schema(required = true) public final Stage stage;
    @Schema(required = true, allowableValues = {"2d", "depth"}) public final String mode;
    @Schema(required = true) public final List<ApiError> diagnostics;

    @JsonCreator
    public JobView(@JsonProperty("jobId") String jobId, @JsonProperty("status") Status status,
                   @JsonProperty("stage") Stage stage, @JsonProperty("mode") String mode,
                   @JsonProperty("diagnostics") List<ApiError> diagnostics) {
        this.jobId = jobId;
        this.status = status;
        this.stage = stage;
        this.mode = mode;
        this.diagnostics = List.copyOf(diagnostics);
    }

    JobView validating() { return new JobView(jobId, Status.RUNNING, Stage.VALIDATING, mode, List.of()); }
    JobView failed(List<ApiError> errors) { return new JobView(jobId, Status.FAILED, Stage.FAILED, mode, errors); }
    boolean terminal() { return status == Status.FAILED || status == Status.SUCCEEDED; }
}
