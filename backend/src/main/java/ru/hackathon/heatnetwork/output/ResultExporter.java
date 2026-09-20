package ru.hackathon.heatnetwork.output;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

public interface ResultExporter {
    /**
     * Writes WGS84 GeoJSON incrementally. Variants share one mode and are ranked already.
     * Does not close the Dataset or target. Failed output must be discarded by the caller.
     * The 2D implementation rejects invalid results and output larger than 500 MiB via IOException.
     */
    void write(Dataset dataset, List<CalculatedVariant> rankedVariants, OutputStream target) throws IOException;
}
