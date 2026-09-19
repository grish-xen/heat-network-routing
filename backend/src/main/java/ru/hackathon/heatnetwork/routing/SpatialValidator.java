package ru.hackathon.heatnetwork.routing;

import java.util.List;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

public interface SpatialValidator {
    /** Validate actual sized geometry, topology, clearances, turns, and special passes. */
    List<Diagnostic> validate(Dataset dataset, CalculatedVariant variant);
}
