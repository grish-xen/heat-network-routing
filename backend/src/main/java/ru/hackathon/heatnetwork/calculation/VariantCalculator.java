package ru.hackathon.heatnetwork.calculation;

import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

public interface VariantCalculator {
    /** Does not mutate input; validates final geometry after sizing and splitting. */
    Evaluation evaluate(Dataset dataset, RouteCandidate candidate, Mode mode);
}
