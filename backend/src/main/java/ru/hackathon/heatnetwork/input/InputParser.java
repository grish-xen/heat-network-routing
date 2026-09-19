package ru.hackathon.heatnetwork.input;

import java.io.IOException;
import java.nio.file.Path;
import ru.hackathon.heatnetwork.model.Dataset;

public interface InputParser {
    /** Reads incrementally; validates and projects to EPSG:32637. Caller closes Dataset. */
    Dataset parse(Path uploadedFile) throws IOException, InvalidInputException;
}
