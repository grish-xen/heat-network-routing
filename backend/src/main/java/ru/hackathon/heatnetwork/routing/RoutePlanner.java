package ru.hackathon.heatnetwork.routing;

import java.util.Optional;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

public interface RoutePlanner {
    SearchSession open(Dataset dataset, SearchOptions options);

    interface SearchSession extends AutoCloseable {
        /** Empty means the search has exhausted its candidate budget. */
        Optional<RouteCandidate> next();
        /** Receives the evaluation of the last candidate before next() is called again. */
        void feedback(Evaluation evaluation);
        @Override void close();
    }
}
