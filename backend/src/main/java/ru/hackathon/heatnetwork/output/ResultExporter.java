package ru.hackathon.heatnetwork.output;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

public interface ResultExporter {
    /** Writes WGS84 GeoJSON incrementally. Variants share one mode and are ranked already. */
    void write(Dataset dataset, List<CalculatedVariant> rankedVariants, OutputStream target) throws IOException;
}
